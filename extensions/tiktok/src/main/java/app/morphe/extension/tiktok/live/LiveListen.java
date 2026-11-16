/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.download.LiveSpeechAudio;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Listens to the LIVE in front for your name (#424), in rooms without captions too. The room's
 * sound is pulled a second time, decoded to 16 kHz and written into a pipe that the phone's own
 * speech recognition reads instead of the microphone (Android 13's EXTRA_AUDIO_SOURCE). What it
 * hears goes through the same name check and alert as the chat.
 *
 * <p>A recognizer that doesn't support reading a pipe opens the microphone instead. The pipe
 * shows it: nothing drains it, so it fills. When that happens listening stops and stays off
 * until the switch is turned off and on again.
 */
@TargetApi(33)
public final class LiveListen {
    static final int MIN_SDK = 33;
    static final long TICK_MS = 1_000;
    /** How long after the room's page resumes the tick keeps looking for it before giving up. */
    static final long LOOK_MS = 5_000;
    /**
     * How long a room may go without a stream address before listening gives up on it. A room's
     * page resumes before TikTok fills in its stream fields: both pages of a room looked 130 ms
     * after it opened, found nothing and dropped it, while 4 s later it had FLV at every quality
     * (S22, 2026-11-16).
     */
    static final long ADDRESS_WAIT_MS = 10_000;
    /** How long a full pipe may sit unread, while speech hasn't ended, before it counts as ignored. */
    static final long NOT_READING_MS = 6_000;
    /** How long a start may take to say it's ready, the first one loading the speech model. */
    static final long READY_MS = 20_000;
    /** The pause before listening again after a quiet stretch. */
    static final long RESTART_MS = 500;
    /** No sound for this long and listening waits for the room to make some. */
    static final long QUIET_ROOM_MS = 5_000;
    /** Failures in a row, quiet stretches aside, before the room is given up on. */
    static final int MAX_FAILURES = 8;
    /** What a pipe holds before a write has to wait for the reader. */
    static final int PIPE_BYTES = 64 * 1024;
    /** Pipe writes this size or smaller go in whole or not at all, so a sample never splits. */
    static final int ATOMIC_WRITE = 4096;
    /** Listens in a row that took no more than the pipe holds and filled it, before the sound counts as ignored. */
    static final int UNREAD_LISTENS = 2;
    static final int PERMISSION_REQUEST = 0x4C4C;

    /** What a recognizer error leads to. */
    enum Next { RESTART, RESTART_LATER, ASK_PERMISSION, STOP }

    /** What ended listening last, for the settings row. */
    enum Problem { NONE, NEEDS_MICROPHONE, LANGUAGE, FAILED }

    /** Where the sound goes for the recognizer. */
    interface Pipe {
        ParcelFileDescriptor readSide();

        /** Writes what fits right now without waiting; returns the bytes taken. */
        int offer(byte[] data, int length);

        void close();
    }

    interface Pipes {
        Pipe make() throws IOException;
    }

    interface Audio {
        void stop();
    }

    interface AudioSource {
        Audio start(String url, LiveSpeechAudio.Sink sink);
    }

    /** The address to listen to in a room object, or null while it has none. */
    interface Streams {
        String address(Object room);
    }

    static final Pipes OS_PIPES = OsPipe::new;
    static final AudioSource NETWORK_AUDIO = (url, sink) -> {
        LiveSpeechAudio audio = LiveSpeechAudio.start(url, sink);
        return audio::stop;
    };

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable TICK = LiveListen::tick;

    static volatile Pipes pipes = OS_PIPES;
    static volatile AudioSource audioSource = NETWORK_AUDIO;
    static volatile Streams streams = LiveRoomStream::listenAddress;
    static volatile long nowForTests;
    static volatile Problem problem = Problem.NONE;

    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static WeakReference<Application> callbacksOn = new WeakReference<>(null);
    private static Session session;
    /** A room with no sound to listen to or no way to listen, so the tick doesn't try it each second. */
    private static String deadRoom;
    private static long lookUntil;
    /** The room waiting for its stream address, and since when. */
    private static String addressWaitRoom;
    private static long addressWaitSince;

    private LiveListen() {
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= MIN_SDK;
    }

    static boolean enabled() {
        return supported() && LiveMentions.enabled() && Settings.LIVE_NAME_ALERTS_LISTEN.get()
                && !Settings.LIVE_LISTEN_SOUND_IGNORED.get();
    }

    // ---- The room ----

    /** Called by the Record chip's room watch as a LIVE room page resumes in {@code activity}. */
    static void roomResumed(Activity activity) {
        try {
            if (activity == null || !enabled()) return;
            host = new WeakReference<>(activity);
            // Back from the settings, say: a room that couldn't listen gets another look.
            deadRoom = null;
            addressWaitRoom = null;
            lookUntil = now() + LOOK_MS;
            registerCallbacks(activity);
            HANDLER.removeCallbacks(TICK);
            tick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set up LIVE listening", failure);
        }
    }

    private static void tick() {
        try {
            Activity activity = host.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || !enabled()) {
                end();
                return;
            }
            Object roomObject = null;
            String room = null;
            Object front = LiveRecordChip.pageInFront(activity);
            if (front != null) {
                roomObject = LiveRecordChip.roomOf(front);
                room = LiveRecordChip.roomKey(roomObject);
            } else if (session != null && pictureInPicture(activity)) {
                // The small window keeps the room it shows.
                room = session.room;
            }
            if (room == null) {
                stopSession();
                if (now() > lookUntil) {
                    end();
                    return;
                }
            } else if (session == null || !room.equals(session.room)) {
                stopSession();
                if (roomObject != null && !room.equals(deadRoom)) start(activity, room, roomObject);
            }
            Session running = session;
            if (running != null) running.check(now());
            HANDLER.postDelayed(TICK, TICK_MS);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE room for listening", failure);
        }
    }

    static void start(Activity activity, String room, Object roomObject) {
        Context context = activity.getApplicationContext();
        if (!microphoneAllowed(context)) {
            Logger.printInfo(() -> "LIVE listen: TikTok has no microphone permission");
            problem = Problem.NEEDS_MICROPHONE;
            deadRoom = room;
            return;
        }
        if (!recognitionAvailable(context)) {
            Logger.printInfo(() -> "LIVE listen: the phone has no speech recognition");
            deadRoom = room;
            return;
        }
        String address = streams.address(roomObject);
        if (address == null) {
            // Not filled in yet, most likely: the tick asks again each second for a while.
            long now = now();
            if (!room.equals(addressWaitRoom)) {
                addressWaitRoom = room;
                addressWaitSince = now;
            }
            if (now - addressWaitSince >= ADDRESS_WAIT_MS) {
                Logger.printInfo(() -> "LIVE listen: this room has no FLV stream to listen to");
                deadRoom = room;
            }
            return;
        }
        addressWaitRoom = null;
        Logger.printDebug(() -> "LIVE listen: listening to the room's sound");
        Session started = new Session(context, room, address);
        session = started;
        started.begin();
    }

    private static void stopSession() {
        Session running = session;
        session = null;
        if (running != null) running.stop();
    }

    /** Stops listening and the tick, until a room page resumes again. */
    private static void end() {
        HANDLER.removeCallbacks(TICK);
        stopSession();
    }

    private static boolean pictureInPicture(Activity activity) {
        return activity.isInPictureInPictureMode();
    }

    private static void registerCallbacks(Activity activity) {
        Application application = activity.getApplication();
        if (application == null || callbacksOn.get() == application) return;
        callbacksOn = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStopped(Activity stopped) {
                // Out of sight and not in the small window: nobody is watching this room.
                if (stopped == host.get() && !stopped.isInPictureInPictureMode()) end();
            }

            @Override public void onActivityDestroyed(Activity destroyed) {
                if (destroyed == host.get()) {
                    end();
                    host = new WeakReference<>(null);
                }
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityResumed(Activity resumed) { }
            @Override public void onActivityPaused(Activity paused) { }
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) { }
        });
    }

    // ---- The recognizer ----

    static boolean microphoneAllowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean recognitionAvailable(Context context) {
        return SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context);
    }

    /** The phone's on-device recognizer where it has one, so the room's sound stays on the phone. */
    private static SpeechRecognizer newRecognizer(Context context) {
        return SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                : SpeechRecognizer.createSpeechRecognizer(context);
    }

    /** What the recognizer is asked to do: read {@code sound}, 16 kHz mono 16-bit PCM, until it closes. */
    static Intent intent(ParcelFileDescriptor sound) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, sound);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, LiveSpeechAudio.RATE);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        // One long session that ends when the sound does, where the recognizer can.
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        return intent;
    }

    /** What happens after the recognizer reports {@code error}, the {@code failuresInARow}th failure. */
    static Next afterError(int error, int failuresInARow) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return Next.RESTART;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return Next.ASK_PERMISSION;
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return Next.STOP;
            default:
                return failuresInARow >= MAX_FAILURES ? Next.STOP : Next.RESTART_LATER;
        }
    }

    /** One second after the first failure, doubling to half a minute. */
    static long retryDelay(int failuresInARow) {
        return Math.min(30_000, 1_000L << Math.min(5, Math.max(0, failuresInARow - 1)));
    }

    /** Whether a listen left the sound unread: it filled the pipe and took no more than the pipe holds. */
    static boolean leftUnread(boolean everFull, long accepted) {
        return everFull && accepted <= PIPE_BYTES + ATOMIC_WRITE;
    }

    private static void soundIgnored() {
        Logger.printInfo(() -> "LIVE listen: speech recognition left the LIVE's sound unread, so listening is off");
        Settings.LIVE_LISTEN_SOUND_IGNORED.save(true);
        end();
    }

    /** Listening in one room: the sound, the pipe and the recognizer. */
    static final class Session implements LiveSpeechAudio.Sink {
        final String room;
        private final Context context;
        private final String address;
        private final Object pipeLock = new Object();
        private final Runnable listenAgain = this::listen;
        // Guarded by pipeLock, since the sound arrives on its own thread.
        private Pipe pipe;
        private long fullSince;
        private long accepted;
        private boolean everFull;
        private long soundAt;
        private boolean waitingForSound = true;
        private volatile boolean ended;
        // Main thread only.
        private Audio audio;
        private SpeechRecognizer recognizer;
        private long listenedAt;
        private long readyAt;
        private long speechEndedAt;
        private int failuresInARow;
        private int unreadInARow;

        Session(Context context, String room, String address) {
            this.context = context;
            this.room = room;
            this.address = address;
        }

        void begin() {
            audio = audioSource.start(address, this);
        }

        @Override public void pcm(byte[] data, int length) {
            boolean startListening;
            synchronized (pipeLock) {
                if (ended) return;
                soundAt = now();
                startListening = waitingForSound;
                waitingForSound = false;
                Pipe current = pipe;
                if (current != null) {
                    int taken = current.offer(data, length);
                    accepted += taken;
                    if (taken >= length) {
                        fullSince = 0;
                    } else {
                        everFull = true;
                        if (fullSince == 0) fullSince = now();
                    }
                }
            }
            if (startListening) HANDLER.post(listenAgain);
        }

        void listen() {
            HANDLER.removeCallbacks(listenAgain);
            if (ended) return;
            synchronized (pipeLock) {
                if (soundAt == 0 || now() - soundAt > QUIET_ROOM_MS) {
                    // Nothing to hear: the next sound starts listening again.
                    waitingForSound = true;
                    return;
                }
            }
            Pipe next;
            try {
                next = pipes.make();
            } catch (IOException | RuntimeException failure) {
                Logger.printDebug(() -> "LIVE listen: no pipe for the sound: " + failure);
                failed(SpeechRecognizer.ERROR_CLIENT);
                return;
            }
            Pipe previous;
            synchronized (pipeLock) {
                previous = pipe;
                pipe = next;
                fullSince = 0;
                accepted = 0;
                everFull = false;
            }
            if (previous != null) previous.close();
            try {
                if (recognizer == null) {
                    recognizer = newRecognizer(context);
                    recognizer.setRecognitionListener(listener);
                }
                readyAt = 0;
                speechEndedAt = 0;
                listenedAt = now();
                recognizer.startListening(intent(next.readSide()));
            } catch (RuntimeException failure) {
                Logger.printDebug(() -> "LIVE listen: speech recognition didn't start: " + failure);
                failed(SpeechRecognizer.ERROR_CLIENT);
            }
        }

        /** The tick's look at a listen in progress: a start that never got ready, or sound left unread. */
        void check(long now) {
            if (ended || listenedAt == 0) return;
            if (readyAt == 0) {
                if (now - listenedAt > READY_MS) failed(SpeechRecognizer.ERROR_CLIENT);
                return;
            }
            long full;
            synchronized (pipeLock) {
                full = fullSince;
            }
            if (full == 0) return;
            // After speech ends the recognizer stops reading until its results are out, which
            // gets longer, but not forever: one on the microphone could stay there.
            long since = Math.max(full, Math.max(readyAt, speechEndedAt));
            if (now - since > (speechEndedAt != 0 ? READY_MS : NOT_READING_MS)) soundIgnored();
        }

        private void heard(Bundle results) {
            if (ended || results == null) return;
            ArrayList<String> said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (said == null) return;
            for (String line : said) {
                if (LiveMentions.heard(room, line)) return;
            }
        }

        /** Whether this listen was the last of {@link #UNREAD_LISTENS} in a row that left the sound unread. */
        private boolean unreadAgain() {
            boolean unread;
            synchronized (pipeLock) {
                unread = leftUnread(everFull, accepted);
            }
            unreadInARow = unread ? unreadInARow + 1 : 0;
            if (unreadInARow < UNREAD_LISTENS) return false;
            soundIgnored();
            return true;
        }

        private void listenEnded() {
            if (ended) return;
            listenedAt = 0;
            failuresInARow = 0;
            if (unreadAgain()) return;
            HANDLER.post(listenAgain);
        }

        void failed(int error) {
            if (ended) return;
            listenedAt = 0;
            boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (quiet && unreadAgain()) return;
            if (!quiet) failuresInARow++;
            Logger.printDebug(() -> "LIVE listen: speech recognition error " + error);
            switch (afterError(error, failuresInARow)) {
                case RESTART:
                    HANDLER.postDelayed(listenAgain, RESTART_MS);
                    break;
                case RESTART_LATER:
                    // A recognizer that failed is replaced, in case it's stuck.
                    dropRecognizer();
                    HANDLER.postDelayed(listenAgain, retryDelay(failuresInARow));
                    break;
                case ASK_PERMISSION:
                    giveUp(Problem.NEEDS_MICROPHONE);
                    break;
                case STOP:
                    boolean language = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
                    giveUp(language ? Problem.LANGUAGE : Problem.FAILED);
                    break;
            }
        }

        private void giveUp(Problem why) {
            problem = why;
            if (session == this) {
                deadRoom = room;
                stopSession();
            } else {
                stop();
            }
        }

        void stop() {
            Pipe current;
            synchronized (pipeLock) {
                if (ended) return;
                ended = true;
                current = pipe;
                pipe = null;
            }
            HANDLER.removeCallbacks(listenAgain);
            Audio running = audio;
            audio = null;
            if (running != null) running.stop();
            dropRecognizer();
            if (current != null) current.close();
        }

        private void dropRecognizer() {
            SpeechRecognizer running = recognizer;
            recognizer = null;
            if (running == null) return;
            try {
                running.cancel();
            } catch (RuntimeException ignored) {
                // Done already.
            }
            try {
                running.destroy();
            } catch (RuntimeException ignored) {
                // Gone already.
            }
        }

        boolean ended() {
            return ended;
        }

        private final RecognitionListener listener = new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (!ended) readyAt = now();
            }

            @Override public void onBeginningOfSpeech() {
                speechEndedAt = 0;
            }

            @Override public void onRmsChanged(float rmsdB) {
            }

            @Override public void onBufferReceived(byte[] buffer) {
            }

            @Override public void onEndOfSpeech() {
                if (!ended) speechEndedAt = now();
            }

            @Override public void onError(int error) {
                failed(error);
            }

            @Override public void onResults(Bundle results) {
                heard(results);
                listenEnded();
            }

            @Override public void onPartialResults(Bundle partialResults) {
            }

            @Override public void onEvent(int eventType, Bundle params) {
            }

            @Override public void onSegmentResults(Bundle segmentResults) {
                heard(segmentResults);
                failuresInARow = 0;
                // A segmented session reads on after each piece.
                speechEndedAt = 0;
            }

            @Override public void onEndOfSegmentedSession() {
                listenEnded();
            }
        };
    }

    /** A real pipe whose write end never waits, so a recognizer that stops reading can't hold up the sound. */
    static final class OsPipe implements Pipe {
        private final ParcelFileDescriptor read;
        private final ParcelFileDescriptor write;

        OsPipe() throws IOException {
            ParcelFileDescriptor[] ends = ParcelFileDescriptor.createPipe();
            read = ends[0];
            write = ends[1];
            try {
                FileDescriptor fd = write.getFileDescriptor();
                int flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0);
                Os.fcntlInt(fd, OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);
            } catch (ErrnoException failure) {
                close();
                throw new IOException("Could not make the sound pipe non-blocking", failure);
            }
        }

        @Override public ParcelFileDescriptor readSide() {
            return read;
        }

        @Override public int offer(byte[] data, int length) {
            int at = 0;
            try {
                while (at < length) {
                    // Whole even-sized pieces, so what's dropped is whole samples.
                    int piece = Math.min(ATOMIC_WRITE, length - at);
                    int wrote = Os.write(write.getFileDescriptor(), data, at, piece);
                    if (wrote <= 0) break;
                    at += wrote;
                }
            } catch (ErrnoException | InterruptedIOException full) {
                // EAGAIN while the reader is behind; the rest of this piece is dropped.
            }
            return at;
        }

        @Override public void close() {
            closeQuietly(write);
            closeQuietly(read);
        }

        private static void closeQuietly(ParcelFileDescriptor end) {
            try {
                end.close();
            } catch (IOException ignored) {
                // Closed already.
            }
        }
    }

    // ---- The settings row ----

    /** What the settings row says about listening, or null when there's nothing to say. */
    public static String statusLine(Context context) {
        if (!supported()) return L10n.t(context, "Needs Android 13 or newer.");
        if (!Settings.LIVE_NAME_ALERTS_LISTEN.get()) return null;
        if (Settings.LIVE_LISTEN_SOUND_IGNORED.get()) {
            return L10n.t(context, "Off: this phone's speech recognition only hears the microphone. "
                    + "Turn this off and on to try again.");
        }
        if (!recognitionAvailable(context)) {
            return L10n.t(context, "This phone has no speech recognition. Choose one under Android's "
                    + "voice input settings, then open the LIVE again.");
        }
        if (!microphoneAllowed(context)) {
            return L10n.t(context, "TikTok needs the microphone permission for speech recognition. "
                    + "Allow it in Android's app settings, then open the LIVE again.");
        }
        switch (problem) {
            case LANGUAGE:
                return L10n.t(context, "Speech recognition has no model for your phone's language. "
                        + "Download one in Android's speech settings, then open the LIVE again.");
            case NEEDS_MICROPHONE:
            case FAILED:
                return L10n.t(context, "Speech recognition kept failing in the last LIVE. "
                        + "Open the LIVE again to retry.");
            default:
                return null;
        }
    }

    /** Called as the switch flips: on starts afresh and asks for the microphone when TikTok lacks it. */
    public static void settingChanged(Context context, boolean on) {
        Settings.LIVE_LISTEN_SOUND_IGNORED.save(false);
        problem = Problem.NONE;
        if (!on) {
            end();
            return;
        }
        if (!supported() || microphoneAllowed(context)) return;
        Activity activity = activityOf(context);
        if (activity != null) activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static long now() {
        long forTests = nowForTests;
        return forTests != 0 ? forTests : SystemClock.elapsedRealtime();
    }

    // ---- For the tests ----

    static Session sessionForTests(Context context, String room, String address) {
        Session made = new Session(context, room, address);
        session = made;
        return made;
    }

    static Session currentForTests() {
        return session;
    }

    static String deadRoomForTests() {
        return deadRoom;
    }

    static void resetForTests() {
        end();
        host = new WeakReference<>(null);
        deadRoom = null;
        addressWaitRoom = null;
        addressWaitSince = 0;
        lookUntil = 0;
        problem = Problem.NONE;
        pipes = OS_PIPES;
        audioSource = NETWORK_AUDIO;
        streams = LiveRoomStream::listenAddress;
        nowForTests = 0;
    }
}
