/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.interaction.Haptics;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.LocalWatchHistoryPreference;

/**
 * A long press on the Profile tab opens TikTok's Watch history (#199), which otherwise sits
 * behind Profile, the menu, Settings and privacy and Activity center. A switch under Keep my own
 * watch history sends it to that list over the feed instead (#172), and another sends it to
 * TikTok's Activity center (#425).
 *
 * <p>The page is TikTok's own hybrid page, opened the way its profile menu does: a
 * {@code webview} route on TikTok's scheme carrying the inapp.tiktokv.com watch_history address.
 * A VIEW intent kept to TikTok's own package reaches its deep link handler, which opens the page
 * in a SparkActivity (47.4.3, S22, 2026-10-21, cold start included).
 *
 * <p>On some accounts holding Profile is TikTok's account switcher. That listener stays on the
 * tab: the patch has TikTok's switcher handler ask {@link #takesSwitcherPress} first, and while
 * the switch is on the hold opens Watch history in place of the account sheet. Off, TikTok's
 * handler runs as it always did. Taking TikTok's listener off the tab and putting it back
 * depended on reading the view's private listener fields, and where that read failed the tab was
 * left with no long press at all once the switch went off (#341).
 *
 * <p>On accounts where the tab has no long press of its own, this sets one. It declines while the
 * switch is off, and comes off the tab only when it reads back as this one, so a listener TikTok
 * sets on the tab later is never cleared.
 *
 * <p>Rides on {@link HomeTabSettingsShortcut}'s front window and layout passes, so it follows the
 * same main activity and the same rebuilt tab bars.
 */
public final class ProfileTabWatchHistory {
    /** The address TikTok's profile menu opens for Watch history, as 47.4.3 ships it. */
    static final String WATCH_HISTORY_URI = "snssdk1233://webview?_pia_=1&hide_nav_bar=1&use_spark=1"
            + "&show_loading=1&should_full_screen=1&allow_webview_gesture=1&url=https%3A%2F%2F"
            + "inapp.tiktokv.com%2Ftpp%2Finapp%2Fpns_product_poseidon%2Fwatch_history%3F_pia_%3D1"
            + "%26hide_nav_bar%3D1%26use_spark%3D1%26show_loading%3D1%26should_full_screen%3D1"
            + "%26allow_webview_gesture%3D1%26bdhm_bid%3Dtiktok_privacy_product_hybrid"
            + "%26enable_search_history%3D1%26enter_from%3Dprofile_menu";

    /**
     * Where TikTok keeps the Activity center address its Settings and privacy row opens (#425).
     * The address comes from TikTok's servers, so it's read from here at each hold. A fixed
     * {@code aweme://pns/activity_center} route reached TikTok's router and opened nothing (S22,
     * 47.8.3), and its deep link handler drops the route from outside.
     */
    static final String ENTRANCE_CLASS = "com.ss.android.ugc.aweme.ActivityCenterEntranceExperiment";
    /** What TikTok's row adds to the address before opening it. */
    static final String ENTRANCE_SUFFIX = "%26grey_bg%3D1";
    /** Settings and privacy, one tap from Activity center, for an account TikTok sent no address. */
    static final String SETTINGS_ROUTE = "aweme://setting";
    static final String ROUTER_CLASS = "com.bytedance.router.SmartRouter";

    /** The tab tag TikTok's switcher handler gets for Profile. */
    static final String PROFILE_TAG = "USER";

    private static WeakReference<View> attached = new WeakReference<>(null);
    /** A tab view that doesn't take the tap itself, so a long press on it would swallow taps. */
    private static WeakReference<View> refused = new WeakReference<>(null);
    /** A tab carrying TikTok's own long press, which is left on it. */
    private static WeakReference<View> tikToks = new WeakReference<>(null);

    private static Field listenerInfoField;
    private static Field longClickField;
    private static boolean listenerUnreadable;

    static final View.OnLongClickListener OPEN_WATCH_HISTORY = view -> {
        try {
            // Still on a tab after the switches went off (see takeOff): the release is a tap again.
            if (!wanted()) return false;
            Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS);
            open(view.getContext());
        } catch (Throwable error) {
            Logger.printException(() -> "Could not open Watch history from the Profile tab", error);
        }
        // Handled, so the release that ends the press isn't also a tap that opens Profile.
        return true;
    };

    private ProfileTabWatchHistory() {
    }

    /**
     * Any switch sends the hold somewhere: TikTok's Watch history, the list kept on the phone, or
     * TikTok's Activity center, which only gets the press while the other two are off.
     */
    static boolean wanted() {
        return Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.get() || opensLocalHistory()
                || Settings.PROFILE_TAB_OPENS_ACTIVITY_CENTER.get();
    }

    /**
     * The hold opens the watch history kept on the phone, which wins over TikTok's page when both
     * switches are on (#172). Pause answers both switches' defaults, so a paused hold is TikTok's.
     */
    static boolean opensLocalHistory() {
        return Settings.PROFILE_HOLD_OPENS_LOCAL_WATCH_HISTORY.get() && Settings.LOCAL_WATCH_HISTORY.get();
    }

    static void open(Context context) throws ReflectiveOperationException {
        if (opensLocalHistory()) {
            Logger.printInfo(() -> "Watch history requested by a long press on Profile");
            LocalWatchHistoryPreference.showOverFeed(context);
            return;
        }
        if (!Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.get()) {
            Logger.printInfo(() -> "Activity center requested by a long press on Profile");
            openActivityCenter(context);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(WATCH_HISTORY_URI))
                .setPackage(context.getPackageName());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Opens Activity center the way TikTok's Settings and privacy row does, through its router. */
    static void openActivityCenter(Context context) throws ReflectiveOperationException {
        String address = activityCenterAddress();
        if (address == null) {
            Logger.printInfo(() -> "TikTok sent no Activity center address; opening Settings and privacy");
            address = SETTINGS_ROUTE;
        }
        Class<?> router = Class.forName(ROUTER_CLASS, true, ProfileTabWatchHistory.class.getClassLoader());
        Object route = router.getMethod("buildRoute", Context.class, String.class)
                .invoke(null, context, address);
        route.getClass().getMethod("open").invoke(route);
    }

    /**
     * The address TikTok's Settings and privacy row opens, or null when TikTok has none. The
     * experiment class holds a built-in model with no address and a lazy one with the address its
     * servers sent; the static fields are read by their values, since their names are obfuscated.
     */
    static String activityCenterAddress() {
        try {
            Class<?> entrance = Class.forName(ENTRANCE_CLASS, true, ProfileTabWatchHistory.class.getClassLoader());
            for (Field field : entrance.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                String url = modelUrl(field.get(null));
                if (url != null) return url + ENTRANCE_SUFFIX;
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not read TikTok's Activity center address", error);
        }
        return null;
    }

    private static String modelUrl(Object value) throws ReflectiveOperationException {
        if (value != null && !isEntranceModel(value)) {
            Method get;
            try {
                get = value.getClass().getMethod("getValue");
            } catch (NoSuchMethodException notLazy) {
                return null;
            }
            // The lazy's class isn't public, so its public getValue still needs the access opened.
            get.setAccessible(true);
            value = get.invoke(value);
        }
        if (value == null || !isEntranceModel(value)) return null;
        Object url = value.getClass().getField("url").get(value);
        return url instanceof String && !((String) url).isEmpty() ? (String) url : null;
    }

    private static boolean isEntranceModel(Object value) {
        return value.getClass().getName().equals(ENTRANCE_CLASS + "$ActivityCenterSettingModel");
    }

    /**
     * Injection point: the top of TikTok's Profile-hold handler, the one that shows the account
     * switcher. True means Watch history opened and the handler returns before the sheet.
     *
     * @param tag the tab tag TikTok's long press posted
     */
    public static boolean takesSwitcherPress(Object tag) {
        try {
            if (!PROFILE_TAG.equals(tag) || !wanted()) return false;
            Context context = HomeTabSettingsShortcut.front();
            // The list shows as a dialog, which needs the screen; TikTok's page opens from any context.
            if (context == null && !opensLocalHistory()) context = Utils.getContext();
            if (context == null) return false;
            open(context);
            return true;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not open Watch history from TikTok's Profile hold", error);
            return false;
        }
    }

    /**
     * Injection point: TikTok's read of its switch_account_gesture experiment. The bottom bar asks
     * it before putting TikTok's hold on the Profile tab, the main page before setting up the
     * hold's handler, and the handler again before it shows the account sheet. Some accounts get
     * no, so holding Profile does nothing there; Long-press Profile to switch accounts answers yes
     * (#388). Watch history still asks first in the handler, so that switch keeps the press.
     *
     * @param sent TikTok's own answer
     */
    @SuppressWarnings("unused")
    public static boolean switcherGesture(boolean sent) {
        return sent || Settings.PROFILE_TAB_SWITCHES_ACCOUNTS.get();
    }

    /** Called on each layout pass of the main activity in front. */
    static void apply(Activity activity) {
        try {
            View current = attached.get();
            // Off, the tab isn't even looked up.
            if (!wanted()) {
                if (current != null) takeOff(current);
                return;
            }
            View profile = FeedVisibility.profileTabView(activity);
            if (current != null && current != profile) takeOff(current);
            if (profile == null || attached.get() == profile || refused.get() == profile) return;
            // TikTok takes its switcher off the same tab when the second account goes.
            if (tikToks.get() == profile && tikTokHolds(profile)) return;
            if (!profile.isClickable()) {
                refused = new WeakReference<>(profile);
                Logger.printInfo(() -> "The Profile tab view doesn't take the tap itself; no long press");
                return;
            }
            if (tikTokHolds(profile)) {
                tikToks = new WeakReference<>(profile);
                Logger.printInfo(() -> "The Profile tab has TikTok's own long press; TikTok's handler asks first");
                return;
            }
            profile.setOnLongClickListener(OPEN_WATCH_HISTORY);
            attached = new WeakReference<>(profile);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not set the Profile tab's long press", error);
        }
    }

    /** The main activity followed was destroyed: its tab goes with it. */
    static void release() {
        View current = attached.get();
        if (current != null) takeOff(current);
    }

    /**
     * Takes this long press off a tab, but only once it reads back as this one: TikTok may have
     * set its own on the tab since. Where the read fails it stays, and declines while off.
     */
    private static void takeOff(View tab) {
        attached = new WeakReference<>(null);
        if (longClickListener(tab) != OPEN_WATCH_HISTORY) return;
        tab.setOnLongClickListener(null);
        tab.setLongClickable(false);
    }

    /**
     * TikTok's own long press is on the tab. A null listener leaves a view long-clickable, so the
     * listener is read; where it can't be, a long-clickable tab counts as TikTok's.
     */
    private static boolean tikTokHolds(View tab) {
        if (!tab.isLongClickable()) return false;
        return longClickListener(tab) != null || listenerUnreadable;
    }

    /** The view's long-click listener, or null when there is none or it can't be read. */
    private static View.OnLongClickListener longClickListener(View view) {
        if (listenerUnreadable) return null;
        try {
            if (listenerInfoField == null) {
                Field info = View.class.getDeclaredField("mListenerInfo");
                info.setAccessible(true);
                listenerInfoField = info;
            }
            Object info = listenerInfoField.get(view);
            if (info == null) return null;
            if (longClickField == null) {
                Field field = info.getClass().getDeclaredField("mOnLongClickListener");
                field.setAccessible(true);
                longClickField = field;
            }
            return (View.OnLongClickListener) longClickField.get(info);
        } catch (Throwable error) {
            listenerUnreadable = true;
            Logger.printInfo(() -> "Can't read a view's long press on this Android: " + error);
            return null;
        }
    }

    static void resetForTests() {
        View current = attached.get();
        if (current != null) takeOff(current);
        attached = new WeakReference<>(null);
        refused = new WeakReference<>(null);
        tikToks = new WeakReference<>(null);
        listenerUnreadable = false;
    }
}
