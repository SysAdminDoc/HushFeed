/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.download.LiveSpeechAudio;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowSpeechRecognizer;

/**
 * Listening for your name in a LIVE's sound (#424). The phone's speech recognition reads the
 * room's sound from a pipe, never the microphone, and one that leaves the pipe unread is taken
 * to be on the microphone and stopped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class LiveListenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static final String ROOM = "7571234567890123456";
    private static final String ADDRESS = "https://p.example.com/ao.flv";
    private static final long START = 1_000_000;

    private final List<LiveMentions.Alert> alerts = new ArrayList<>();
    private final List<FakePipe> pipes = new ArrayList<>();
    private boolean installed;
    private boolean pipesRead = true;
    private String audioUrl;
    private boolean audioStopped;
    private Context context;

    /** A pipe that a reader drains as fast as it's filled, or that nobody reads and fills at 64 KB. */
    private final class FakePipe implements LiveListen.Pipe {
        final ParcelFileDescriptor read;
        final boolean drained;
        int held;
        boolean closed;

        FakePipe(boolean drained) throws IOException {
            this.drained = drained;
            File file = folder.newFile();
            read = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override public ParcelFileDescriptor readSide() {
            return read;
        }

        @Override public int offer(byte[] data, int length) {
            if (drained) return length;
            int taken = Math.min(length, LiveListen.PIPE_BYTES - held);
            held += taken;
            return taken;
        }

        @Override public void close() {
            closed = true;
        }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        installed = SettingsStatus.liveControlsEnabled;
        SettingsStatus.liveControlsEnabled = true;
        Settings.LIVE_NAME_ALERTS.save(true);
        Settings.LIVE_NAME_ALERTS_LISTEN.save(true);
        Settings.LIVE_LISTEN_SOUND_IGNORED.save(false);
        Settings.LIVE_NAME_ALERT_NAMES.save("Jess");
        LiveMentions.resetForTests();
        LiveMentions.alertsForTests = alerts;
        LiveMentions.nowForTests = START;
        SignedInUser.idForTests = "7200000000000000001";
        SignedInUser.handleForTests = "jess_plays22";
        SignedInUser.nicknameForTests = "Jessica Moreno";
        LiveListen.resetForTests();
        LiveListen.nowForTests = START;
        LiveListen.pipes = () -> {
            FakePipe pipe = new FakePipe(pipesRead);
            pipes.add(pipe);
            return pipe;
        };
        LiveListen.audioSource = (url, sink) -> {
            audioUrl = url;
            return () -> audioStopped = true;
        };
        ShadowSpeechRecognizer.reset();
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true);
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.RECORD_AUDIO);
    }

    @After public void tearDown() {
        LiveListen.resetForTests();
        PausedProcess.set(false);
        SettingsStatus.liveControlsEnabled = installed;
        Settings.LIVE_NAME_ALERTS.save(false);
        Settings.LIVE_NAME_ALERTS_LISTEN.save(false);
        Settings.LIVE_LISTEN_SOUND_IGNORED.save(false);
        Settings.LIVE_NAME_ALERT_NAMES.save("");
        LiveMentions.resetForTests();
        SignedInUser.idForTests = null;
        SignedInUser.handleForTests = null;
        SignedInUser.nicknameForTests = null;
        ShadowSpeechRecognizer.reset();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static ShadowSpeechRecognizer recognizer() {
        SpeechRecognizer latest = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        assertNotNull("no recognizer was made", latest);
        return Shadow.extract(latest);
    }

    private static Bundle said(String... lines) {
        Bundle results = new Bundle();
        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(Arrays.asList(lines)));
        return results;
    }

    private LiveListen.Session listening() {
        LiveListen.Session session = LiveListen.sessionForTests(context, ROOM, ADDRESS);
        session.begin();
        sound(session, 640);
        idle();
        return session;
    }

    private static void sound(LiveListen.Session session, int bytes) {
        byte[] piece = new byte[640];
        for (int sent = 0; sent < bytes; sent += piece.length) session.pcm(piece, piece.length);
    }

    @Test public void theRecognizerReadsTheRoomsSoundAndNeverTheMicrophone() {
        listening();

        assertEquals(ADDRESS, audioUrl);
        assertEquals(1, pipes.size());
        Intent intent = recognizer().getLastRecognizerIntent();
        assertSame(pipes.get(0).read, intent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE));
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, -1));
        assertEquals(LiveSpeechAudio.RATE, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, -1));
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, -1));
        assertEquals(RecognizerIntent.EXTRA_AUDIO_SOURCE, intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION));
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false));
    }

    @Test public void nothingStartsBeforeTheRoomMakesASound() {
        LiveListen.sessionForTests(context, ROOM, ADDRESS).begin();
        idle();

        assertTrue(pipes.isEmpty());
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer());
    }

    @Test public void yourNameSaidOutLoudAlertsAndListeningGoesOn() {
        listening();
        ShadowSpeechRecognizer recognizer = recognizer();
        recognizer.triggerOnReadyForSpeech(new Bundle());

        recognizer.triggerOnResults(said("thanks just for the gift", "thanks Jess for the gift"));
        idle();

        assertEquals(1, alerts.size());
        LiveMentions.Alert alert = alerts.get(0);
        assertEquals(ROOM, alert.room);
        assertNull(alert.who);
        assertEquals("thanks Jess for the gift", alert.text);
        assertTrue(alert.spoken);
        assertEquals("each listen gets a fresh pipe", 2, pipes.size());
        assertTrue(pipes.get(0).closed);
        assertSame(pipes.get(1).read, recognizer().getLastRecognizerIntent()
                .getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE));
    }

    @Test public void aSegmentedSessionAlertsOnEachPiece() {
        listening();
        ShadowSpeechRecognizer recognizer = recognizer();
        recognizer.triggerOnReadyForSpeech(new Bundle());

        recognizer.triggerOnSegmentResults(said("nobody here yet"));
        recognizer.triggerOnSegmentResults(said("oh hi Jess"));

        assertEquals(1, alerts.size());
        assertEquals("oh hi Jess", alerts.get(0).text);
        assertEquals("the session reads on", 1, pipes.size());
    }

    @Test public void aRecognizerThatLeavesTheSoundUnreadIsStopped() {
        pipesRead = false;
        LiveListen.Session session = listening();
        ShadowSpeechRecognizer recognizer = recognizer();
        recognizer.triggerOnReadyForSpeech(new Bundle());
        sound(session, LiveListen.PIPE_BYTES * 2);

        session.check(START + LiveListen.NOT_READING_MS);
        assertFalse("still within its grace", Settings.LIVE_LISTEN_SOUND_IGNORED.get());

        session.check(START + LiveListen.NOT_READING_MS + 1);
        assertTrue(Settings.LIVE_LISTEN_SOUND_IGNORED.get());
        assertTrue(session.ended());
        assertTrue(recognizer.isDestroyed());
        assertTrue(audioStopped);
        assertTrue(pipes.get(0).closed);
        assertFalse(LiveListen.enabled());
        assertEquals("Off: this phone's speech recognition only hears the microphone. Turn this off and on to try again.",
                LiveListen.statusLine(context));
    }

    @Test public void listensInARowThatReadNothingTurnListeningOff() {
        pipesRead = false;
        LiveListen.Session session = listening();
        sound(session, LiveListen.PIPE_BYTES * 2);
        recognizer().triggerOnResults(said("hello everyone"));
        idle();
        assertFalse("one could be chance", Settings.LIVE_LISTEN_SOUND_IGNORED.get());

        sound(session, LiveListen.PIPE_BYTES * 2);
        recognizer().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH);

        assertTrue(Settings.LIVE_LISTEN_SOUND_IGNORED.get());
        assertTrue(session.ended());
    }

    @Test public void aReaderThatKeepsUpIsLeftListening() {
        LiveListen.Session session = listening();
        recognizer().triggerOnReadyForSpeech(new Bundle());
        sound(session, LiveListen.PIPE_BYTES * 4);
        session.check(START + LiveListen.READY_MS * 10);
        recognizer().triggerOnResults(said("hello everyone"));
        idle();
        sound(session, LiveListen.PIPE_BYTES * 4);
        recognizer().triggerOnResults(said("hello again"));
        idle();

        assertFalse(Settings.LIVE_LISTEN_SOUND_IGNORED.get());
        assertFalse(session.ended());
        assertEquals(3, pipes.size());
    }

    @Test public void aStartThatNeverGetsReadyIsTriedAgainWithAFreshRecognizer() {
        LiveListen.Session session = listening();
        ShadowSpeechRecognizer first = recognizer();

        session.check(START + LiveListen.READY_MS + 1);
        assertTrue(first.isDestroyed());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LiveListen.retryDelay(1)));

        assertNotSame(first, recognizer());
        assertEquals(2, pipes.size());
    }

    @Test public void aQuietRoomWaitsForSoundBeforeListeningAgain() {
        LiveListen.Session session = listening();
        recognizer().triggerOnReadyForSpeech(new Bundle());
        LiveListen.nowForTests = START + LiveListen.QUIET_ROOM_MS + 1;
        recognizer().triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LiveListen.RESTART_MS));
        assertEquals(1, pipes.size());

        sound(session, 640);
        idle();
        assertEquals(2, pipes.size());
    }

    @Test public void aLanguageWithNoModelEndsTheRoomAndSaysWhatToDo() {
        LiveListen.Session session = listening();

        recognizer().triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);

        assertTrue(session.ended());
        assertNull(LiveListen.currentForTests());
        assertEquals("Speech recognition has no model for your phone's language. "
                + "Download one in Android's speech settings, then open the LIVE again.", LiveListen.statusLine(context));
    }

    @Test public void aRoomWhoseStreamIsNotFilledInYetIsAskedAgain() {
        Activity room = Robolectric.buildActivity(Activity.class).setup().get();
        String[] offered = {null};
        LiveListen.streams = roomObject -> offered[0];

        // Both of a room's pages resume before TikTok fills in its stream fields.
        LiveListen.start(room, ROOM, new Object());
        LiveListen.start(room, ROOM, new Object());
        assertNull(LiveListen.currentForTests());
        assertNull(LiveListen.deadRoomForTests());

        LiveListen.nowForTests = START + LiveListen.TICK_MS;
        offered[0] = ADDRESS;
        LiveListen.start(room, ROOM, new Object());

        assertNotNull(LiveListen.currentForTests());
        assertEquals(ADDRESS, audioUrl);
    }

    @Test public void aRoomThatNeverGetsAStreamIsGivenUpOn() {
        Activity room = Robolectric.buildActivity(Activity.class).setup().get();
        LiveListen.streams = roomObject -> null;

        LiveListen.start(room, ROOM, new Object());
        LiveListen.nowForTests = START + LiveListen.ADDRESS_WAIT_MS - 1;
        LiveListen.start(room, ROOM, new Object());
        assertNull(LiveListen.deadRoomForTests());

        LiveListen.nowForTests = START + LiveListen.ADDRESS_WAIT_MS;
        LiveListen.start(room, ROOM, new Object());

        assertEquals(ROOM, LiveListen.deadRoomForTests());
        assertNull(LiveListen.currentForTests());
        assertNull(audioUrl);
    }

    @Test public void aRoomGhostModeHidesYouFromWaitsForTheEyeAndThenListens() {
        Activity room = Robolectric.buildActivity(Activity.class).setup().get();
        String[] offered = {null};
        boolean[] hiding = {true};
        LiveListen.streams = roomObject -> offered[0];
        LiveListen.hidden = key -> hiding[0];

        // Held for a minute: no stream fields until the eye sends the room's enter.
        LiveListen.start(room, ROOM, new Object());
        LiveListen.nowForTests = START + LiveListen.ADDRESS_WAIT_MS * 6;
        LiveListen.start(room, ROOM, new Object());
        assertNull(LiveListen.deadRoomForTests());

        hiding[0] = false;
        LiveListen.nowForTests += LiveListen.TICK_MS;
        offered[0] = ADDRESS;
        LiveListen.start(room, ROOM, new Object());

        assertNotNull(LiveListen.currentForTests());
        assertEquals(ADDRESS, audioUrl);
    }

    @Test public void leavingTheRoomStopsEverything() {
        LiveListen.Session session = listening();
        ShadowSpeechRecognizer recognizer = recognizer();

        session.stop();
        sound(session, 640);
        idle();

        assertTrue(audioStopped);
        assertTrue(recognizer.isDestroyed());
        assertTrue(pipes.get(0).closed);
        assertEquals(1, pipes.size());
    }

    @Test public void errorsLeadWhereTheyShould() {
        assertEquals(LiveListen.Next.RESTART, LiveListen.afterError(SpeechRecognizer.ERROR_NO_MATCH, 99));
        assertEquals(LiveListen.Next.RESTART, LiveListen.afterError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, 99));
        assertEquals(LiveListen.Next.ASK_PERMISSION,
                LiveListen.afterError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, 1));
        assertEquals(LiveListen.Next.STOP, LiveListen.afterError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, 1));
        assertEquals(LiveListen.Next.RESTART_LATER, LiveListen.afterError(SpeechRecognizer.ERROR_SERVER, 1));
        assertEquals(LiveListen.Next.STOP, LiveListen.afterError(SpeechRecognizer.ERROR_SERVER, LiveListen.MAX_FAILURES));
        assertEquals(1_000, LiveListen.retryDelay(1));
        assertEquals(2_000, LiveListen.retryDelay(2));
        assertEquals(30_000, LiveListen.retryDelay(6));
        assertEquals(30_000, LiveListen.retryDelay(40));
    }

    @Test public void turningItOnStartsAfreshAndAsksForTheMicrophone() {
        Settings.LIVE_LISTEN_SOUND_IGNORED.save(true);
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.RECORD_AUDIO);
        Activity settings = Robolectric.buildActivity(Activity.class).setup().get();

        LiveListen.settingChanged(settings, true);

        assertFalse(Settings.LIVE_LISTEN_SOUND_IGNORED.get());
        ShadowActivity.PermissionsRequest asked = Shadows.shadowOf(settings).getLastRequestedPermission();
        assertNotNull(asked);
        assertEquals(LiveListen.PERMISSION_REQUEST, asked.requestCode);
        assertEquals(List.of(Manifest.permission.RECORD_AUDIO), Arrays.asList(asked.requestedPermissions));
        assertEquals("TikTok needs the microphone permission for speech recognition. "
                + "Allow it in Android's app settings, then open the LIVE again.", LiveListen.statusLine(settings));
    }

    @Test public void theRowSaysNothingWhileAllIsWell() {
        assertNull(LiveListen.statusLine(context));
        Settings.LIVE_NAME_ALERTS_LISTEN.save(false);
        assertNull(LiveListen.statusLine(context));
    }

    @Config(sdk = 31)
    @Test public void olderAndroidIsTold() {
        assertFalse(LiveListen.enabled());
        assertEquals("Needs Android 13 or newer.", LiveListen.statusLine(context));
    }
}
