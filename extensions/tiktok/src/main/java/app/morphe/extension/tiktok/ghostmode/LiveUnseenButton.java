/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.OverlayGlyphDrawable;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.live.LiveAutoTap;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The eye in a LIVE room while Watch LIVEs unseen is on (#420). Crossed out, the host can't see
 * you: the room's enter was held back. A tap lets this host see you, by sending the enter that
 * was held when the room opened, and the eye opens. It stays open for the rest of that visit.
 * Leaving the room, or tapping the open eye, crosses it out again, so the next visit is hidden
 * (#491). With `Double tap to let the host see you` on, no eye is drawn and a double tap in the
 * room does what its tap did (D#359).
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
    /** The last window whose touches the double tap reads (D#359); an older one only passes them on. */
    private static WeakReference<Window> watchedWindow = new WeakReference<>(null);
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

    /**
     * True while the eye is a row of the menu from holding the middle of a LIVE instead of a
     * button on the video (D#359), with LIVE buttons in the long-press menu on.
     */
    public static boolean inMenu() {
        return enabled() && LiveRecordChip.buttonsInMenu();
    }

    /**
     * Whether the room in front is one Watch LIVEs unseen holds, which TikTok opened without its
     * chat, its host bar or the menu from holding the middle (D#359).
     */
    public static boolean roomUnbuilt() {
        Activity activity = host.get();
        return activity != null && LiveUnseen.unbuilt(roomInFront(activity));
    }

    /** Whether the host of the room in front can see you, for the eye drawn on the menu row. */
    public static boolean menuRevealed() {
        Activity activity = host.get();
        return activity != null && LiveUnseen.revealed(roomInFront(activity));
    }

    /** What the eye's row in that menu says, which is what a tap on it will do. */
    public static String menuLabel() {
        Activity activity = host.get();
        return menuLabel(activity == null ? null : roomInFront(activity));
    }

    static String menuLabel(String room) {
        if (revealing) return L10n.t("Letting the host see you");
        return LiveUnseen.revealed(room) ? L10n.t("Hide from the host next time") : L10n.t("Let the host see you");
    }

    /** The eye's row was tapped: the same as a tap on the eye, which isn't on the video now. */
    public static void menuTap() {
        Activity activity = host.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Utils.showToastShort(L10n.t("Couldn't tell which LIVE this is. Open it again."));
            return;
        }
        onTapped(activity);
    }

    /** True while a double tap in a LIVE lets its host see you and no eye covers the video (D#359). */
    static boolean doubleTapInstead() {
        return enabled() && Settings.GHOST_LIVE_DOUBLE_TAP.get();
    }

    /** Shows, hides or redraws the eye for the room open in {@code activity}. */
    static void refresh(Activity activity) {
        if (activity == null) return;
        boolean room = !activity.isFinishing() && !activity.isDestroyed() && !FeedVisibility.isFeedWindow(activity);
        if (room && doubleTapInstead()) watchTaps(activity);
        // In the long-press menu, the eye is a row there and nothing covers the stream (D#359).
        // A held room has no such menu, so there the eye stays: it's the only way out of the hold,
        // unless a double tap is.
        boolean wanted = enabled() && !doubleTapInstead() && !LiveRecordChip.buttonsInMenu()
                && room && !inPictureInPicture(activity);
        if (!wanted) {
            removeButton();
            return;
        }
        TextView button = buttonReference.get();
        if (button == null || button.getContext() != activity || button.getParent() == null
                || button.getLayoutParams() == null
                || button.getLayoutParams().width != SettingsUi.dp(activity, LiveRecordChip.scaledDp(SIZE_DP))) {
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
        // Size of the LIVE buttons (#359).
        int size = SettingsUi.dp(activity, LiveRecordChip.scaledDp(SIZE_DP));
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

    /**
     * Wraps the window's callback once so its touches reach {@link #touched} first (D#359). It
     * takes nothing, and once the switch is off or the window belongs to another room's activity
     * it only passes touches on.
     */
    private static void watchTaps(Activity activity) {
        Window window = activity.getWindow();
        if (window == null || watchedWindow.get() == window) return;
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        ViewConfiguration config = ViewConfiguration.get(activity);
        DoubleTapWatch taps = new DoubleTapWatch(config.getScaledTouchSlop(), config.getScaledDoubleTapSlop());
        WeakReference<Activity> owner = new WeakReference<>(activity);
        InvocationHandler watcher = (proxy, method, args) -> {
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                touched(owner.get(), taps, (MotionEvent) args[0]);
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause() != null ? wrapped.getCause() : wrapped;
            }
        };
        window.setCallback((Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, watcher));
        watchedWindow = new WeakReference<>(window);
    }

    /** One touch in a watched window: a double tap there lets the host see you. */
    static void touched(Activity activity, DoubleTapWatch taps, MotionEvent event) {
        try {
            // Auto tap's own taps go through this window too, and a run of them isn't you.
            if (activity == null || activity != host.get() || LiveAutoTap.isInjecting() || !doubleTapInstead()) {
                taps.skipGesture();
                return;
            }
            int action = event.getActionMasked();
            boolean doubled = taps.feed(action, event.getX(), event.getY(), event.getEventTime());
            // A tap on Record, Auto tap or the block button is a tap on that button.
            if (action == MotionEvent.ACTION_DOWN && onOwnButton(activity, event.getX(), event.getY())) {
                taps.skipGesture();
                return;
            }
            if (doubled) HANDLER.post(() -> doubleTapped(activity));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read a tap for Ghost mode's LIVE double tap", failure);
        }
    }

    /**
     * A double tap in a LIVE: the host of the room in front sees you, as the eye's tap did. Once
     * they can, a double tap is TikTok's own again (its likes), so it changes nothing here, and
     * leaving the room hides the next visit as always (#491).
     */
    static void doubleTapped(Activity activity) {
        if (activity == null || activity != host.get() || revealing || !doubleTapInstead()) return;
        String room = roomInFront(activity);
        if (room == null || LiveUnseen.revealed(room)) return;
        activity.getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        onTapped(activity);
    }

    /** Whether ({@code x}, {@code y}) in the window lands on one of Hushfeed's buttons in the room. */
    private static boolean onOwnButton(Activity activity, float x, float y) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return false;
        Rect area = new Rect();
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (LiveRecordChip.isStacked(child) && child.isShown() && child.getGlobalVisibleRect(area)
                    && area.contains((int) x, (int) y)) {
                return true;
            }
        }
        return false;
    }

    static void onTapped(Activity activity) {
        if (revealing) return;
        Object page = pageInFront(activity);
        String room = page == null ? null : LiveRecordChip.roomKey(LiveRecordChip.roomOf(page));
        if (room == null) {
            Utils.showToastShort(L10n.t("Couldn't tell which LIVE this is. Open it again."));
            return;
        }
        if (LiveUnseen.revealed(room)) {
            // Tapped again (#491): the host saw this visit, so the next one is hidden.
            LiveUnseen.hideAgain(room);
            TextView button = buttonReference.get();
            if (button != null) draw(button, false);
            Utils.showToastShort(L10n.t("The host won't see you next time you open it"));
            return;
        }
        revealing = true;
        TextView button = buttonReference.get();
        if (button != null) SettingsUi.setBusy(button, true, L10n.t(activity, "Letting the host see you"));
        LiveUnseen.reveal(page, room, outcome -> {
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
        Object page = pageInFront(activity);
        return page == null ? null : LiveRecordChip.roomKey(LiveRecordChip.roomOf(page));
    }

    /** The room page with the most of itself on screen, or the only page known. */
    static Object pageInFront(Activity activity) {
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
        return best;
    }

    static void resetForTests() {
        HANDLER.removeCallbacksAndMessages(null);
        ticking = false;
        PAGES.clear();
        removeButton();
        host = new WeakReference<>(null);
        watchedWindow = new WeakReference<>(null);
        revealing = false;
    }
}
