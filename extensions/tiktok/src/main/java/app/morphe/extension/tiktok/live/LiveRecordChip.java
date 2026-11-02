/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.LiveRecorder;
import app.morphe.extension.tiktok.download.LiveRecordingSaver;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Record button in a LIVE room (#37). The room's page tells this class it is resuming; while
 * the switch is on a small chip shows on that page. Tapping it starts a recording from that
 * moment, tapping it again stops, and leaving the room stops it too. Stream recordings also
 * continue while the same LIVE is playing in Android's native picture-in-picture window.
 * A once-a-second check on the main thread notices a room change or the window going away.
 * Screen recordings hide the chip so it isn't filmed, and the notification's Stop ends them (#265).
 */
@SuppressWarnings("unused")
public final class LiveRecordChip {
    private LiveRecordChip() {
    }

    private static final long TICK_MS = 1000;
    /** onPause can run before Android has entered its native PiP window. */
    private static final long PIP_TRANSITION_MS = 1500;
    /** A LIVE activity can be destroyed while TikTok itself remains open (including after PiP). */
    static final LiveRecorder.End LIVE_ACTIVITY_DESTROYED_END = LiveRecorder.End.LEFT_ROOM;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // Everything below is touched on the main thread only.
    /**
     * Every room page seen resuming. The one in front is picked from these by what is on screen:
     * on 47.1.4 the pager resumes the next room right after the one being watched, so the last
     * page to resume is usually one that isn't shown.
     */
    private static final Map<Object, Boolean> PAGES = new WeakHashMap<>();
    /** Clear-screen controllers can be created before their room page resumes. */
    private static final Map<String, Boolean> CLEAR_ROOM_STATES = new HashMap<>();
    private static WeakReference<Object> page = new WeakReference<>(null);
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static TextView chip;
    private static LiveRecorder recorder;
    private static boolean saving;
    private static boolean ticking;
    private static boolean callbacksRegistered;
    private static boolean clearScreen;
    private static long pipTransitionUntil;
    private static int pipTransitionGeneration;
    private static boolean recordingWasPinned;
    private static long pipReturnUntil;

    /** True while the switch is on and Hushfeed isn't paused. */
    public static boolean enabled() {
        return SettingsStatus.liveControlsEnabled && Settings.RECORD_LIVE.get() && !Setting.isPaused();
    }

    /** Called as a LIVE room's page begins to resume, with that page. */
    public static void noteRoomFragment(Object fragment) {
        try {
            if (fragment == null) return;
            Utils.runOnMainThread(() -> onRoomResumed(fragment));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not note the LIVE room page", failure);
        }
    }

    private static void onRoomResumed(Object fragment) {
        try {
            Object activityObject = Reflect.invoke(fragment, "getActivity");
            if (!(activityObject instanceof Activity)) return;
            Activity activity = (Activity) activityObject;
            PAGES.put(fragment, Boolean.TRUE);
            host = new WeakReference<>(activity);
            registerCallbacks(activity);
            // A page laid out on screen wins over an adjacent page resuming. During PiP
            // restoration, no full-size page may be shown yet: do not mistake a prefetched
            // neighbor for the recorded room before the layout has settled.
            Object front = pageInFront(activity);
            if (front != null) follow(front);
            else if (!streamRecording()) follow(fragment);
            else {
                removeChip();
                scheduleTick();
                return;
            }
            if (!enabled()) {
                removeChip();
                return;
            }
            showChip(activity);
            refresh();
            scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set up the LIVE record button", failure);
        }
    }

    private static void registerCallbacks(Activity activity) {
        if (callbacksRegistered) return;
        Application application = activity.getApplication();
        if (application == null) return;
        callbacksRegistered = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity paused) {
                if (paused == host.get()) {
                    pipReturnUntil = 0;
                    removeChip();
                    if (!streamRecording()) {
                        leave(LiveRecorder.End.LEFT_ROOM);
                    } else {
                        // Wait briefly for Android to report native PiP before treating this as
                        // a regular background exit. Never extend a screen capture into Home.
                        pipTransitionUntil = SystemClock.uptimeMillis() + PIP_TRANSITION_MS;
                        int generation = ++pipTransitionGeneration;
                        HANDLER.postDelayed(() -> {
                            if (generation != pipTransitionGeneration || paused != host.get()) return;
                            pipTransitionUntil = 0;
                            if (!nativePip(paused)) leave(LiveRecorder.End.LEFT_ROOM);
                        }, PIP_TRANSITION_MS);
                    }
                }
            }

            @Override public void onActivityDestroyed(Activity destroyed) {
                if (destroyed == host.get()) {
                    pipTransitionGeneration++;
                    pipTransitionUntil = 0;
                    recordingWasPinned = false;
                    pipReturnUntil = 0;
                    leave(LIVE_ACTIVITY_DESTROYED_END);
                    removeChip();
                    clearScreen = false;
                    CLEAR_ROOM_STATES.clear();
                    page = new WeakReference<>(null);
                    host = new WeakReference<>(null);
                }
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityResumed(Activity resumed) {
                if (resumed == host.get()) {
                    pipTransitionGeneration++;
                    pipTransitionUntil = 0;
                    // Returning from PiP can precede the room page becoming visible again.
                    pipReturnUntil = pipReturnDeadline(streamRecording(), recordingWasPinned,
                            pipReturnUntil, SystemClock.uptimeMillis());
                    recordingWasPinned = false;
                    scheduleTick();
                }
            }
            @Override public void onActivityStopped(Activity stopped) {
                if (stopped == host.get() && !keepWhileHidden(stopped)) {
                    leave(LiveRecorder.End.LEFT_ROOM);
                    removeChip();
                }
            }
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) { }
        });
    }

    // ---- the chip -------------------------------------------------------------------------

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void showChip(Activity activity) {
        if (chip != null && chip.getContext() == activity && chip.getParent() != null) return;
        removeChip();
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        TextView view = new TextView(activity);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(activity, 48));
        view.setMinWidth(dp(activity, 48));
        view.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(activity, 24));
        background.setColor(0xB3000000);
        view.setBackground(background);
        view.setOnClickListener(clicked -> onChipTap());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.topMargin = dp(activity, TOP_DP);
        params.rightMargin = dp(activity, 12);
        content.addView(view, params);
        chip = view;
        view.post(() -> stayClear(activity));
    }

    /** Below the room's top bar, where the chip sits unless something to tap is there. */
    private static final int TOP_DP = 132;
    /** The gap left between the chip and a tap target it moved below. */
    private static final int GAP_DP = 8;

    /**
     * Moves the chip below anything to tap that TikTok draws where it sits, such as the avatar row
     * of the Discover panel when that opens over the room, and back up once it's gone. A view a
     * quarter of the screen or bigger is the room's own surface and doesn't count.
     */
    private static void stayClear(Activity activity) {
        TextView view = chip;
        if (view == null || !(view.getParent() instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) view.getParent();
        Rect area = new Rect();
        if (!content.isShown() || !content.getGlobalVisibleRect(area)) return;
        List<Rect> taken = new ArrayList<>();
        tapTargets(content, view, area, (long) area.width() * area.height() / 4, taken);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        int width = view.getWidth() > 0 ? view.getWidth() : dp(activity, 96);
        int height = view.getHeight() > 0 ? view.getHeight() : dp(activity, 48);
        int right = area.width() - params.rightMargin;
        int top = freeTop(dp(activity, TOP_DP), height, right - width, right, taken,
                dp(activity, GAP_DP), area.height() * 2 / 3);
        if (top != params.topMargin) {
            params.topMargin = top;
            view.setLayoutParams(params);
        }
    }

    /** Every visible view under {@code root} that takes a tap, other than {@code skip}, in {@code area}'s coordinates. */
    static void tapTargets(View root, View skip, Rect area, long largest, List<Rect> out) {
        if (root == skip || root.getVisibility() != View.VISIBLE || root.getAlpha() == 0f) return;
        Rect rect = new Rect();
        if ((root.isClickable() || root.isLongClickable()) && root.getGlobalVisibleRect(rect)
                && (long) rect.width() * rect.height() < largest) {
            rect.offset(-area.left, -area.top);
            out.add(rect);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                tapTargets(group.getChildAt(i), skip, area, largest, out);
            }
        }
    }

    /**
     * The first top, from {@code wanted} down, where a chip {@code height} tall between {@code left}
     * and {@code right} covers none of {@code taken}. Past {@code lowest} the chip would sit among
     * the room's own controls, so it stays at {@code wanted} then.
     */
    static int freeTop(int wanted, int height, int left, int right, List<Rect> taken, int gap, int lowest) {
        int top = wanted;
        boolean moved = true;
        while (moved && top <= lowest) {
            moved = false;
            for (Rect rect : taken) {
                if (rect.left < right && rect.right > left && rect.top < top + height && rect.bottom > top) {
                    top = rect.bottom + gap;
                    moved = true;
                }
            }
        }
        return top <= lowest ? top : wanted;
    }

    private static void removeChip() {
        TextView view = chip;
        chip = null;
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static void refresh() {
        TextView view = chip;
        if (view == null) return;
        view.setVisibility(hidesChip(recorder == null ? null : recorder.target(), clearScreen)
                ? View.INVISIBLE : View.VISIBLE);
        if (saving) {
            view.setText(L10n.t("Saving the recording"));
            view.setContentDescription(L10n.t("Saving the recording"));
            view.setEnabled(false);
            return;
        }
        view.setEnabled(true);
        if (recorder != null && recorder.recording()) {
            view.setText("● " + elapsed(recorder.elapsedMs()) + "  " + L10n.t("Stop"));
            view.setContentDescription(L10n.t("Stop recording this LIVE"));
        } else {
            view.setText(L10n.t("Record"));
            view.setContentDescription(L10n.t("Record this LIVE from now"));
        }
    }

    /** Screen recordings hide the chip to avoid filming it (#265). */
    static boolean hidesChip(LiveRecorder.Target target) {
        return hidesChip(target, false);
    }

    /** LIVE Clear screen also hides the chip, without changing recording state. */
    static boolean hidesChip(LiveRecorder.Target target, boolean clearScreen) {
        return clearScreen || (target != null && target.transport == LiveRecorder.Transport.SCREEN);
    }

    /** Called after TikTok's LIVE clear-screen controller changes state. */
    public static void onNativeClearScreenChanged(Object controller, int state) {
        try {
            String roomId = roomKey(roomOf(controller));
            if (roomId == null) return;
            boolean cleared = state != 0;
            Utils.runOnMainThread(() -> {
                CLEAR_ROOM_STATES.put(roomId, cleared);
                if (roomId.equals(roomKey(roomOf(page.get())))) {
                    clearScreen = cleared;
                    refresh();
                    Logger.printDebug(() -> "LIVE record chip: Clear screen " + (cleared ? "on" : "off"));
                }
            });
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not follow LIVE Clear screen", failure);
        }
    }

    /** The controller can inherit Clear screen from the previous room without a new toggle. */
    public static void onNativeClearScreenInitialized(Object controller) {
        try {
            Boolean current = clearStateOf(controller);
            if (current != null) onNativeClearScreenChanged(controller, current ? 1 : 0);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read LIVE Clear screen state", failure);
        }
    }

    /** The controller's only no-argument boolean is its clear state, and that shape outlives a rename. */
    static Boolean clearStateOf(Object controller) throws ReflectiveOperationException {
        if (controller == null) return null;
        Method getter = null;
        for (Method method : controller.getClass().getDeclaredMethods()) {
            if (method.getReturnType() != boolean.class || method.getParameterTypes().length != 0
                    || method.isSynthetic()) continue;
            if (getter != null) return null;
            getter = method;
        }
        if (getter == null) return null;
        getter.setAccessible(true);
        return (Boolean) getter.invoke(controller);
    }

    static String elapsed(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * What a LIVE recording is doing, for the Downloads page's list of saves (#27): its time while
     * it records, "Saving the recording" once it's writing the file, and null when there's none.
     * Opening the settings leaves the room, so the page mostly sees one being saved. Main thread only.
     */
    public static String queueStatus() {
        LiveRecorder current = recorder;
        if (current == null) return null;
        if (!saving && current.recording()) return "● " + elapsed(current.elapsedMs());
        return L10n.t("Saving the recording");
    }

    /** For the tests: the recorder the chip holds, and whether it's being saved; null for none. */
    public static void holdForTests(LiveRecorder held, boolean beingSaved) {
        recorder = held;
        saving = beingSaved;
    }

    // ---- start and stop -------------------------------------------------------------------

    private static void onChipTap() {
        try {
            if (saving || stopRecording()) return;
            start();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not use the LIVE record button", failure);
            Utils.showToastShort(L10n.t("The recording couldn't be saved. Try again."));
        }
    }

    /** Stops a running recording so it's saved; false when none is running. */
    private static boolean stopRecording() {
        if (saving || recorder == null || !recorder.recording()) return false;
        recorder.stop(LiveRecorder.End.STOPPED);
        saving = true;
        refresh();
        return true;
    }

    /**
     * The Stop on the screen recording's notification (#265), the way to end one while the chip
     * is hidden. With nothing recording any more, the projection it still holds is let go.
     */
    static void stopFromNotification() {
        Utils.runOnMainThread(() -> {
            try {
                if (!stopRecording() && !saving) ScreenCapture.discardUnused();
            } catch (Throwable failure) {
                Logger.printException(() -> "Could not stop the screen recording", failure);
            }
        });
    }

    private static void start() throws Exception {
        Activity activity = host.get();
        Object fragment = page.get();
        if (activity == null || fragment == null || !enabled()) return;
        if (Build.VERSION.SDK_INT < 29
                && activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("Allow storage for TikTok to record LIVEs"));
            return;
        }
        Object room = roomOf(fragment);
        if (LiveRecordSource.screen()) {
            startScreen(activity, fragment, room);
            return;
        }
        LiveRoomStream.Pick pick = LiveRoomStream.fromRoom(room,
                LiveRoomStream.forRecording(Settings.LIVE_RECORD_QUALITY.get(), LiveControls.lastQualityPick()));
        if (pick.target == null) {
            Logger.printInfo(() -> "LIVE record: no stream to record (" + pick.problem + ", room offers [" + pick.offered + "])");
            Utils.showToastShort(L10n.t("No stream found to record. Reopen the LIVE."));
            return;
        }
        Logger.printInfo(() -> "LIVE record: " + pick.target.quality + " over " + pick.target.transport
                + " (LIVE recording quality " + Settings.LIVE_RECORD_QUALITY.get() + ", room offers [" + pick.offered + "])");
        LiveRecorder started = LiveRecordingSaver.create(activity, pick.target, result ->
                Utils.runOnMainThread(() -> onFinished(result)));
        recorder = started;
        saving = false;
        started.start();
        Utils.showToastShort(L10n.t("Recording this LIVE from now"));
        refresh();
        scheduleTick();
    }

    /**
     * Records the screen (#37): Android's consent is asked for every recording, and the recorder
     * starts once the service holding the projection is up. The app's sound comes with it only
     * when TikTok already holds the microphone permission, which playback capture requires.
     */
    private static void startScreen(Activity activity, Object fragment, Object room) throws Exception {
        LiveRoomStream.Pick pick = LiveRoomStream.screenTarget(room);
        if (pick.target == null) {
            Utils.showToastShort(L10n.t("No stream found to record. Reopen the LIVE."));
            return;
        }
        boolean audio = LiveRecordSource.audioAllowed(Build.VERSION.SDK_INT,
                activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED);
        saving = true;
        refresh();
        ScreenCapture.request(activity, granted -> {
            saving = false;
            try {
                if (!granted || page.get() != fragment || !enabled()) {
                    ScreenCapture.discardUnused();
                    if (!granted) Utils.showToastShort(L10n.t("Screen recording wasn't allowed"));
                    refresh();
                    return;
                }
                LiveRecorder started = LiveRecordingSaver.createScreen(activity, pick.target, audio, result ->
                        Utils.runOnMainThread(() -> onFinished(result)));
                recorder = started;
                started.start();
                Utils.showToastShort(audio
                        ? L10n.t("Recording this LIVE's screen from now")
                        : L10n.t("Recording this LIVE's screen from now, without sound"));
                refresh();
                scheduleTick();
            } catch (Throwable failure) {
                Logger.printException(() -> "Could not start the screen recording", failure);
                ScreenCapture.discardUnused();
                Utils.showToastShort(L10n.t("The recording couldn't be saved. Try again."));
                refresh();
            }
        });
    }

    private static void onFinished(LiveRecorder.Result result) {
        recorder = null;
        saving = false;
        recordingWasPinned = false;
        pipReturnUntil = 0;
        LiveRecordingSaver.announce(result);
        refresh();
    }

    /** Makes {@code fragment} the page the button belongs to; a recording of another page stops. */
    private static void follow(Object fragment) {
        Object previous = page.get();
        if (previous != null && previous != fragment) {
            LiveRecorder running = recorder;
            if (running != null && running.active()
                    && !sameRoomId(running.target().room, roomKey(roomOf(fragment)))) {
                leave(LiveRecorder.End.LEFT_ROOM);
            }
        }
        if (previous != fragment) {
            clearScreen = Boolean.TRUE.equals(CLEAR_ROOM_STATES.get(roomKey(roomOf(fragment))));
        }
        page = new WeakReference<>(fragment);
    }

    /** Stops a running recording because the room is no longer in front. */
    private static void leave(LiveRecorder.End why) {
        LiveRecorder running = recorder;
        if (running != null && running.active()) {
            running.stop(why);
            saving = true;
        }
    }

    /** Only stream sources can follow native PiP; a screen capture would record Home instead. */
    private static boolean streamRecording() {
        LiveRecorder running = recorder;
        return running != null && running.active() && running.target().transport != LiveRecorder.Transport.SCREEN;
    }

    private static boolean nativePip(Activity activity) {
        return activity != null && Build.VERSION.SDK_INT >= 26 && !activity.isFinishing()
                && !activity.isDestroyed() && activity.isInPictureInPictureMode();
    }

    /** Grace covers only the enter-PiP race, not normal background recording. */
    static boolean keepWhileHidden(boolean streamRecording, boolean nativePip,
                                   long now, long transitionUntil) {
        return streamRecording && (nativePip || (transitionUntil > 0 && now < transitionUntil));
    }

    private static boolean keepWhileHidden(Activity activity) {
        return keepWhileHidden(streamRecording(), nativePip(activity),
                SystemClock.uptimeMillis(), pipTransitionUntil);
    }

    /** A rebuilt page may still represent the same LIVE after returning from native PiP. */
    static boolean sameRoomId(String recorded, String candidate) {
        return recorded != null && candidate != null && recorded.equals(candidate);
    }

    /** The first post-PiP tick can precede onResume; both extend the same brief grace. */
    static long pipReturnDeadline(boolean stream, boolean wasPinned, long previous, long now) {
        return stream && (wasPinned || previous > now) ? now + PIP_TRANSITION_MS : 0;
    }

    // ---- watching the room ----------------------------------------------------------------

    private static void scheduleTick() {
        if (ticking) return;
        ticking = true;
        HANDLER.postDelayed(LiveRecordChip::tick, TICK_MS);
    }

    private static void tick() {
        ticking = false;
        try {
            Activity activity = host.get();
            if (streamRecording() && nativePip(activity)) {
                recordingWasPinned = true;
                pipReturnUntil = 0;
            } else if (recordingWasPinned && streamRecording()) {
                // Android may drop the PiP flag a moment before onActivityResumed runs.
                pipReturnUntil = pipReturnDeadline(true, true, pipReturnUntil,
                        SystemClock.uptimeMillis());
                recordingWasPinned = false;
            }
            if (keepWhileHidden(activity)) {
                // A pinned LIVE has no full-size resumed page. Keep recording its stream, but
                // still stop if TikTok replaces the recorded room while the window is pinned.
                LiveRecorder running = recorder;
                Object remembered = page.get();
                if (running != null && running.active() && remembered != null
                        && !sameRoom(running, remembered)) leave(LiveRecorder.End.LEFT_ROOM);
                removeChip();
                if (recorder != null) scheduleTick();
                return;
            }
            Object fragment = activity != null && enabled() ? pageInFront(activity) : null;
            boolean showing = fragment != null;
            if (!showing && streamRecording() && pipReturnUntil > SystemClock.uptimeMillis()) {
                removeChip();
                scheduleTick();
                return;
            }
            if (!showing) {
                leave(LiveRecorder.End.LEFT_ROOM);
                removeChip();
            } else {
                pipReturnUntil = 0;
                follow(fragment);
                LiveRecorder running = recorder;
                if (running != null && running.active() && !sameRoom(running, fragment)) {
                    leave(LiveRecorder.End.LEFT_ROOM);
                }
                showChip(activity);
                refresh();
                stayClear(activity);
            }
            // Keep watching while a recording runs or the chip is up; a new resume restarts it.
            if (recorder != null || (showing && chip != null)) scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE room", failure);
        }
    }

    /** The resumed room page of {@code activity} with the most of itself on screen, or null. */
    private static Object pageInFront(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null || !decor.isShown()) return null;
        Object best = null;
        int bestHeight = 0;
        for (Object fragment : new ArrayList<>(PAGES.keySet())) {
            if (fragment == null || Reflect.invoke(fragment, "getActivity") != activity) continue;
            int shown = heightOnScreen(fragment);
            if (shown > bestHeight) {
                best = fragment;
                bestHeight = shown;
            }
        }
        return best;
    }

    /** How much of a room page is on screen, in pixels; 0 unless it is resumed and mostly shown. */
    private static int heightOnScreen(Object fragment) {
        if (Boolean.FALSE.equals(Reflect.invoke(fragment, "isResumed"))) return 0;
        if (Boolean.TRUE.equals(Reflect.invoke(fragment, "isRemoving"))) return 0;
        if (Boolean.TRUE.equals(Reflect.invoke(fragment, "isDetached"))) return 0;
        if (Boolean.TRUE.equals(Reflect.invoke(fragment, "isHidden"))) return 0;
        Object view = Reflect.invoke(fragment, "getView");
        if (!(view instanceof View) || !((View) view).isShown()) return 0;
        Rect rect = new Rect();
        if (!((View) view).getGlobalVisibleRect(rect)) return 0;
        return mostlyShown(rect.height(), ((View) view).getHeight()) ? rect.height() : 0;
    }

    /**
     * Whether a page is in front by how much of it is on screen: at least half, which only one
     * page of a pager can be, even mid-swipe, while the rooms beside it sit off screen.
     */
    static boolean mostlyShown(int visibleHeight, int height) {
        return height > 0 && visibleHeight > 0 && visibleHeight * 2 >= height;
    }

    private static boolean sameRoom(LiveRecorder running, Object fragment) {
        String recorded = running.target().room;
        if (recorded == null) return true;
        Object room = roomOf(fragment);
        Object id = room == null ? null : Reflect.readField(room, "idStr");
        return !(id instanceof String) || recorded.equals(id);
    }

    /** Stable room identity across the native controller and the resumed room page. */
    static String roomKey(Object room) {
        if (room == null) return null;
        Object text = Reflect.readField(room, "idStr");
        if (text instanceof String && !((String) text).isEmpty()) return (String) text;
        Object numeric = Reflect.readField(room, "id");
        return numeric instanceof Number ? String.valueOf(((Number) numeric).longValue()) : null;
    }

    /** The Room-typed field on a room page or a native clear-screen controller. */
    private static Object roomOf(Object holder) {
        if (holder == null) return null;
        try {
            for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().getName().equals("com.bytedance.android.livesdkapi.depend.model.live.Room")) {
                        field.setAccessible(true);
                        return field.get(holder);
                    }
                }
            }
        } catch (Throwable ignored) {
            // no room readable
        }
        return null;
    }
}
