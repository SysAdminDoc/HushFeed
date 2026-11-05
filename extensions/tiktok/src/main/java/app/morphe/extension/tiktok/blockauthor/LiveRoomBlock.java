/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.blockauthor;

import android.app.Activity;
import android.app.Application;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The block button inside a LIVE room (#138). With Block author button on, the room shows the
 * same button the feed has, under the Record chip. One tap blocks the room's host through TikTok's
 * own block request, the same one the feed button sends, and a confirmed block closes the room.
 * The screen it lands on offers Unblock for two seconds, as a block on the feed does.
 *
 * <p>The room's page tells this class it is resuming, the same hook the Record chip uses. The
 * pager resumes the next room right after the one being watched, so the host is read at tap time
 * from the page with the most of itself on screen, never from the last page to resume.
 */
@SuppressWarnings("unused")
public final class LiveRoomBlock {
    private LiveRoomBlock() {
    }

    private static final long TICK_MS = 1000;
    /** Below the Record chip, which sits at 132dp and is 48dp tall. */
    static final int TOP_DP = 188;
    private static final int SIZE_DP = 48;
    private static final int SIDE_DP = 12;
    private static final int GAP_DP = 8;
    /** How long the screen under a closed room gets to take focus before Unblock becomes a toast. */
    static final long UNDO_WAIT_MS = 1500;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // Everything below is touched on the main thread only.
    /** Every room page seen resuming; the one in front is picked from these by what is on screen. */
    private static final Map<Object, Boolean> PAGES = new WeakHashMap<>();
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static WeakReference<View> buttonReference = new WeakReference<>(null);
    private static WeakReference<Application> followed = new WeakReference<>(null);
    private static boolean ticking;
    private static boolean requestInFlight;
    /** A host blocked from a room that just closed, waiting for the next screen to offer Unblock. */
    private static VideoAuthor pendingUndo;
    private static int undoGeneration;

    /** True while the switch is on and Hushfeed isn't paused. */
    static boolean enabled() {
        return Settings.BLOCK_AUTHOR_BUTTON.get() && !Setting.isPaused();
    }

    /** Called as a LIVE room's page begins to resume, with that page. */
    public static void noteRoomFragment(Object fragment) {
        try {
            if (fragment == null) return;
            Utils.runOnMainThread(() -> onRoomResumed(fragment));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not note the LIVE room page for the block button", failure);
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
            refresh(activity);
            scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set up the LIVE block button", failure);
        }
    }

    private static void registerCallbacks(Activity activity) {
        Application application = activity.getApplication();
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity paused) {
                if (paused == host.get()) removeButton();
            }

            @Override public void onActivityResumed(Activity resumed) {
                if (resumed == host.get()) {
                    refresh(resumed);
                    scheduleTick();
                } else if (pendingUndo != null) {
                    offerUndoOn(resumed);
                }
            }

            @Override public void onActivityDestroyed(Activity destroyed) {
                if (destroyed == host.get()) {
                    removeButton();
                    host = new WeakReference<>(null);
                }
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) { }
        });
    }

    /** Once a second while a room is open: the switch, Pause and LIVE Clear screen apply without a restart. */
    private static void scheduleTick() {
        if (ticking) return;
        ticking = true;
        HANDLER.postDelayed(LiveRoomBlock::tick, TICK_MS);
    }

    private static void tick() {
        ticking = false;
        try {
            Activity activity = host.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                removeButton();
                return;
            }
            refresh(activity);
            scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not refresh the LIVE block button", failure);
        }
    }

    /** Shows, hides or moves the button for the room open in {@code activity}. */
    static void refresh(Activity activity) {
        if (activity == null) return;
        // A LIVE playing inside the feed already has the feed's own button, and Android's
        // picture-in-picture window shows only the stream, like TikTok's own controls.
        boolean wanted = enabled() && !activity.isFinishing() && !activity.isDestroyed()
                && !FeedVisibility.isFeedWindow(activity) && !inPictureInPicture(activity);
        if (!wanted) {
            removeButton();
            return;
        }
        View button = buttonReference.get();
        if (button == null || button.getContext() != activity || button.getParent() == null) {
            removeButton();
            button = showButton(activity);
            if (button == null) return;
        }
        // Clear screen hides the room's controls; the button goes with them and comes back after.
        // A screen recording of the room hides it the same way, so it isn't filmed (#265).
        boolean hidden = LiveRecordChip.clearScreenOn() || LiveRecordChip.filmingScreen();
        button.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        stayClear(button);
    }

    private static boolean inPictureInPicture(Activity activity) {
        return Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode();
    }

    private static View showButton(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return null;
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Block this creator"));
        button.setFocusable(true);
        SettingsUi.markAsButton(button);
        Drawable glyph = new OverlayGlyphDrawable(OverlayGlyphDrawable.Shape.BLOCK,
                SettingsUi.OVERLAY_TEXT, SettingsUi.dp(activity, 2));
        button.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY, glyph));
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onBlockTapped(activity);
        });
        LiveRecordChip.markStacked(button, LiveRecordChip.STACK_BLOCK,
                SettingsUi.dp(activity, TOP_DP), SettingsUi.dp(activity, GAP_DP));
        int size = SettingsUi.dp(activity, SIZE_DP);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        params.topMargin = SettingsUi.dp(activity, TOP_DP);
        params.rightMargin = SettingsUi.dp(activity, SIDE_DP);
        content.addView(button, params);
        buttonReference = new WeakReference<>(button);
        button.post(() -> stayClear(button));
        return button;
    }

    /**
     * Moves the button below anything to tap that TikTok draws where it sits, such as the Record
     * chip or the Discover panel's avatar row, and back up once it's gone.
     */
    private static void stayClear(View button) {
        if (button.getParent() instanceof ViewGroup) LiveRecordChip.restack((ViewGroup) button.getParent());
    }

    private static void removeButton() {
        View button = buttonReference.get();
        buttonReference = new WeakReference<>(null);
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }
    }

    private static void setBusy(boolean busy) {
        View button = buttonReference.get();
        if (button == null) return;
        SettingsUi.setBusy(button, busy, L10n.t(button.getContext(), "Blocking"));
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(busy ? SettingsUi.OVERLAY_TEXT_MUTED : SettingsUi.OVERLAY_TEXT);
        }
    }

    static void onBlockTapped(Activity activity) {
        if (requestInFlight) return;
        Object front = frontPage(activity);
        Object room = front == null ? null : LiveRecordChip.roomOf(front);
        VideoAuthor author = room == null ? null : LiveFeedCells.hostOf(room);
        if (author == null) {
            Utils.showToastShort(L10n.t("No creator to block on this video"));
            return;
        }
        requestInFlight = true;
        setBusy(true);
        BlockAuthorService.block(author, result -> {
            requestInFlight = false;
            setBusy(false);
            onBlocked(activity, author, result);
        });
    }

    /** A confirmed block closes the room, and the screen under it offers Unblock. */
    static void onBlocked(Activity activity, VideoAuthor author, BlockAuthorService.Result result) {
        if (result != BlockAuthorService.Result.CONFIRMED
                || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            BlockAuthorOverlay.reportBlockResult(author, result);
            return;
        }
        removeButton();
        pendingUndo = author;
        int token = ++undoGeneration;
        activity.finish();
        // The screen under the room may never take focus (another app, the lock screen), so the
        // undo falls back to its toast instead of waiting for good.
        HANDLER.postDelayed(() -> {
            if (token == undoGeneration) offerUndo();
        }, UNDO_WAIT_MS);
    }

    /** Offers Unblock on {@code next} once its window has focus, which the undo chip needs. */
    private static void offerUndoOn(Activity next) {
        View decor = next.getWindow() == null ? null : next.getWindow().getDecorView();
        if (decor == null || next.hasWindowFocus()) {
            offerUndo();
            return;
        }
        int token = undoGeneration;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override public void onWindowFocusChanged(boolean focused) {
                if (!focused) return;
                decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                if (token == undoGeneration) offerUndo();
            }
        });
    }

    private static void offerUndo() {
        VideoAuthor author = pendingUndo;
        pendingUndo = null;
        undoGeneration++;
        if (author != null) BlockAuthorOverlay.reportBlockResult(author, BlockAuthorService.Result.CONFIRMED);
    }

    /**
     * The room page of {@code activity} with the most of itself on screen. With a single page
     * known it is the room, unless it says it isn't resumed.
     */
    static Object frontPage(Activity activity) {
        Object best = null;
        Object only = null;
        int pages = 0;
        int bestHeight = 0;
        for (Object fragment : new ArrayList<>(PAGES.keySet())) {
            if (fragment == null || Reflect.invoke(fragment, "getActivity") != activity) continue;
            pages++;
            only = fragment;
            int shown = LiveRecordChip.heightOnScreen(fragment);
            if (shown > bestHeight) {
                best = fragment;
                bestHeight = shown;
            }
        }
        if (best != null) return best;
        if (pages == 1 && !Boolean.FALSE.equals(Reflect.invoke(only, "isResumed"))) return only;
        return null;
    }

    static void resetForTests() {
        HANDLER.removeCallbacksAndMessages(null);
        ticking = false;
        PAGES.clear();
        removeButton();
        host = new WeakReference<>(null);
        requestInFlight = false;
        pendingUndo = null;
        undoGeneration++;
    }
}
