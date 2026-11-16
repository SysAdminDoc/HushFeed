/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.OverlayGlyphDrawable;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The eye in a LIVE room while Watch LIVEs unseen is on (#420). Crossed out, the host can't see
 * you: the room's enter was held back. A tap lets this host see you, by sending the enter that
 * was held when the room opened, and the eye opens. It stays open for that room until TikTok is
 * closed.
 *
 * <p>The room's page tells this class it is resuming, the hook the Record chip and the block
 * button use. The pager resumes the next room right after the one being watched, so the room is
 * read at tap time from the page with the most of itself on screen.
 */
@SuppressWarnings("unused")
public final class LiveUnseenButton {
    private LiveUnseenButton() {
    }

    private static final long TICK_MS = 1000;
    /** Below the block button, which sits at 188dp and is 48dp tall. */
    static final int TOP_DP = 244;
    private static final int SIZE_DP = 48;
    private static final int SIDE_DP = 12;
    private static final int GAP_DP = 8;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // Everything below is touched on the main thread only.
    private static final Map<Object, Boolean> PAGES = new WeakHashMap<>();
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static WeakReference<TextView> buttonReference = new WeakReference<>(null);
    private static WeakReference<Application> followed = new WeakReference<>(null);
    private static boolean ticking;
    private static boolean revealing;
    /** Whether the button on screen draws the open eye, so a tick redraws it only on a change. */
    private static Boolean drawnRevealed;

    /** Called as a LIVE room's page begins to resume, with that page. */
    public static void noteRoomFragment(Object fragment) {
        try {
            if (fragment == null) return;
            Utils.runOnMainThread(() -> onRoomResumed(fragment));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not note the LIVE room page for Ghost mode", failure);
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
            Logger.printException(() -> "Could not set up Ghost mode's LIVE eye", failure);
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

    /** Once a second while a room is open: the switches, Pause and a swipe to the next room apply. */
    private static void scheduleTick() {
        if (ticking) return;
        ticking = true;
        HANDLER.postDelayed(LiveUnseenButton::tick, TICK_MS);
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
            Logger.printException(() -> "Could not refresh Ghost mode's LIVE eye", failure);
        }
    }

    static boolean enabled() {
        return LiveUnseen.enabled() && !Setting.isPaused();
    }

    /** Shows, hides or redraws the eye for the room open in {@code activity}. */
    static void refresh(Activity activity) {
        if (activity == null) return;
        boolean wanted = enabled() && !activity.isFinishing() && !activity.isDestroyed()
                && !FeedVisibility.isFeedWindow(activity) && !inPictureInPicture(activity);
        if (!wanted) {
            removeButton();
            return;
        }
        TextView button = buttonReference.get();
        if (button == null || button.getContext() != activity || button.getParent() == null) {
            removeButton();
            button = showButton(activity);
            if (button == null) return;
        }
        draw(button, LiveUnseen.revealed(roomInFront(activity)));
        // Clear screen hides the room's controls, and a screen recording hides Hushfeed's own
        // buttons so they aren't filmed (#265); the eye goes with them.
        boolean hidden = LiveRecordChip.clearScreenOn() || LiveRecordChip.filmingScreen();
        button.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        if (button.getParent() instanceof ViewGroup) LiveRecordChip.restack((ViewGroup) button.getParent());
    }

    private static boolean inPictureInPicture(Activity activity) {
        return Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode();
    }

    private static TextView showButton(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return null;
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        SettingsUi.markAsButton(button);
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onTapped(activity);
        });
        drawnRevealed = null;
        LiveRecordChip.markStacked(button, LiveRecordChip.STACK_UNSEEN,
                SettingsUi.dp(activity, TOP_DP), SettingsUi.dp(activity, GAP_DP));
        int size = SettingsUi.dp(activity, SIZE_DP);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        params.topMargin = SettingsUi.dp(activity, TOP_DP);
        params.rightMargin = SettingsUi.dp(activity, SIDE_DP);
        content.addView(button, params);
        buttonReference = new WeakReference<>(button);
        return button;
    }

    /** The crossed-out eye while the host can't see you, the open one once they can. */
    private static void draw(TextView button, boolean revealed) {
        if (drawnRevealed != null && drawnRevealed == revealed) return;
        drawnRevealed = revealed;
        OverlayGlyphDrawable glyph = new OverlayGlyphDrawable(
                revealed ? OverlayGlyphDrawable.Shape.EYE : OverlayGlyphDrawable.Shape.EYE_OFF,
                SettingsUi.OVERLAY_TEXT, SettingsUi.dp(button.getContext(), 2));
        button.setBackground(SettingsUi.overlayControl(button.getContext(), SettingsUi.RADIUS_OVERLAY, glyph));
        button.setContentDescription(L10n.t(button.getContext(),
                revealed ? "The host can see you" : "Let the host see you"));
    }

    private static void removeButton() {
        View button = buttonReference.get();
        buttonReference = new WeakReference<>(null);
        drawnRevealed = null;
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }
    }

    static void onTapped(Activity activity) {
        if (revealing) return;
        String room = roomInFront(activity);
        if (room == null) {
            Utils.showToastShort(L10n.t("Couldn't tell which LIVE this is. Open it again."));
            return;
        }
        if (LiveUnseen.revealed(room)) {
            Utils.showToastShort(L10n.t("The host can already see you in this LIVE"));
            return;
        }
        revealing = true;
        TextView button = buttonReference.get();
        if (button != null) SettingsUi.setBusy(button, true, L10n.t(activity, "Letting the host see you"));
        LiveUnseen.reveal(room, outcome -> {
            revealing = false;
            TextView shown = buttonReference.get();
            if (shown != null) {
                SettingsUi.setBusy(shown, false, null);
                draw(shown, LiveUnseen.revealed(room));
            }
            tell(outcome);
        });
    }

    /** Says what the tap did. Each toast is written out here so the wrap check reads every one. */
    static void tell(LiveUnseen.Reveal outcome) {
        switch (outcome) {
            case SENT:
                Utils.showToastShort(L10n.t("The host can see you now"));
                break;
            case NEXT_TIME:
                Utils.showToastShort(L10n.t("The host sees you next time you open it"));
                break;
            default:
                Utils.showToastShort(L10n.t("Couldn't reach TikTok. Open this LIVE again."));
        }
    }

    /** The room of the page with the most of itself on screen, or the only page known. */
    static String roomInFront(Activity activity) {
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
        if (best == null && pages == 1 && !Boolean.FALSE.equals(Reflect.invoke(only, "isResumed"))) best = only;
        return best == null ? null : LiveRecordChip.roomKey(LiveRecordChip.roomOf(best));
    }

    static void resetForTests() {
        HANDLER.removeCallbacksAndMessages(null);
        ticking = false;
        PAGES.clear();
        removeButton();
        host = new WeakReference<>(null);
        revealing = false;
    }
}
