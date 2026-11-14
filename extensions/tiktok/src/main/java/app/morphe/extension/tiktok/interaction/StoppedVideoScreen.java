/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lets the screen turn off on its usual timeout once a video has stopped at its end (#414).
 *
 * <p>TikTok keeps the screen on for as long as the feed's window is up, playing or not: the
 * window carries {@link WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} with a video paused as
 * well. That never mattered while every video looped. With Stop video looping on, a video that
 * reaches its end just sits there, and the screen stays lit until someone locks the phone.
 *
 * <p>So when the player says a video completed and the loop is stopped, the hold comes off:
 * the window's flag and any view that asks for the same thing. It goes back, exactly as it was,
 * the moment the player moves again, which is another video playing or this one starting over.
 * A touch already keeps the screen awake by itself, so reading the comments under a finished
 * video still works the way it does everywhere else on the phone.
 */
public final class StoppedVideoScreen {
    /**
     * How far from the end a progress report has to be to count as the video playing again.
     * The player can report its last position once more after saying it completed.
     */
    static final long REPLAY_MARGIN_MS = 1000;

    private static final int KEEP_SCREEN_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    /** Whether the hold is off, read on every progress report, so volatile and nothing else. */
    private static volatile boolean released;
    private static volatile String completedId;

    /** What was taken off, so only that goes back. Main thread only. */
    private static WeakReference<Window> clearedWindow = new WeakReference<>(null);
    private static final List<WeakReference<View>> CLEARED_VIEWS = new ArrayList<>();

    private StoppedVideoScreen() {
    }

    /** Called from {@code PlayerController.onPlayCompleted} with the video that finished. */
    public static void onPlayCompleted(String id) {
        try {
            if (!Settings.STOP_VIDEO_LOOPING.get()) return;
            completedId = id;
            released = true;
            Utils.runOnMainThread(() -> {
                // A video that started again before this ran keeps its hold.
                if (released && Objects.equals(completedId, id)) release(Utils.getVisibleActivity());
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not let the screen sleep after a video ended", ex);
        }
    }

    /**
     * Called from {@code PlayerController.onPlayProgressChange} with the video, where it is and
     * how long it is. Cheap while nothing has been released, which is nearly always.
     */
    public static void onPlayProgress(String id, long position, long duration) {
        if (!released) return;
        try {
            if (!playingAgain(completedId, id, position, duration)) return;
            released = false;
            completedId = null;
            Utils.runOnMainThread(StoppedVideoScreen::restore);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not keep the screen on for the next video", ex);
        }
    }

    /**
     * Whether a progress report means a video is playing again: a different one, or the one that
     * finished back near its start. The report that repeats the end of the finished video is not.
     */
    static boolean playingAgain(String completed, String id, long position, long duration) {
        if (!Objects.equals(completed, id)) return true;
        return duration > REPLAY_MARGIN_MS && position >= 0 && position < duration - REPLAY_MARGIN_MS;
    }

    private static void release(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        restore(); // Anything still off from an earlier window goes back first.
        if ((window.getAttributes().flags & KEEP_SCREEN_ON) != 0) {
            window.clearFlags(KEEP_SCREEN_ON);
            clearedWindow = new WeakReference<>(window);
        }
        clearViews(window.getDecorView());
        Logger.printDebug(() -> "Video ended with the loop stopped; the screen can turn off");
    }

    private static void clearViews(View view) {
        if (view == null) return;
        if (view.getKeepScreenOn()) {
            view.setKeepScreenOn(false);
            CLEARED_VIEWS.add(new WeakReference<>(view));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) clearViews(group.getChildAt(index));
        }
    }

    /** Forgets what was cleared without putting it back, for tests that leave between cases. */
    static void resetForTests() {
        released = false;
        completedId = null;
        clearedWindow = new WeakReference<>(null);
        CLEARED_VIEWS.clear();
    }

    private static void restore() {
        Window window = clearedWindow.get();
        clearedWindow = new WeakReference<>(null);
        if (window != null) window.addFlags(KEEP_SCREEN_ON);
        for (WeakReference<View> reference : CLEARED_VIEWS) {
            View view = reference.get();
            if (view != null) view.setKeepScreenOn(true);
        }
        CLEARED_VIEWS.clear();
    }
}
