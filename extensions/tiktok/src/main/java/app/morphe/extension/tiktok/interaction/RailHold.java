/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.download.OriginalPhotos;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * A long press on the Comment, Share or Favorites button plays the video at the hold speed while
 * it's held (upstream #87), the way a long press did before TikTok gave those buttons menus of
 * their own.
 *
 * <p>TikTok's hold gesture sits on the feed cell, sees every touch and speeds a video up when a
 * press lands near the left or right edge of the screen, where the side buttons are. As the press
 * lands it asks the buttons whether the press is on one of them, and a press on one is not a
 * hold. So the patch answers "not on it" for a chosen button, and TikTok's own hold runs there:
 * the hold speed, its banner, the pull-down lock and the release. The button's own long press,
 * the emoji row on Comment, the quick share row on Share and the new collection offer on
 * Favorites, is skipped for that press, so a hold doesn't open a menu too, and letting go doesn't
 * count as a tap. Comment's and Favorites' are skipped in the method that opens the menu, and
 * Share's by wrapping the listeners TikTok sets.
 *
 * <p>Only a press the hold check let through skips the menu, and only while TikTok still means to
 * hold it. The check turns a press down before it asks the buttons when Hushfeed has the edge for
 * a seek or the long press for an action of its own, and when TikTok won't speed up at all. TikTok
 * drops a hold it hasn't started yet when the finger moves off the spot or a second finger lands,
 * and ends every press on release, and each of those forgets the pass. The menu then opens as it
 * always did, as long as the button's own timer hasn't fired yet. Favorites' fires at 200 ms,
 * before the hold starts at 300 ms, so a release between the two gets neither.
 */
public final class RailHold {
    static final String FAMILY = "rail hold";

    /**
     * How long past the long-press timeout a long press still belongs to the press the hold check
     * let through. The check runs as the press lands and the long press comes a timeout later.
     */
    static final long GRACE_MS = 1_000;

    /** A side button a hold can be chosen for, and when the check last let a press on it through. */
    enum Button {
        COMMENT("comment", Settings.RAIL_HOLD_COMMENT),
        SHARE("share", Settings.RAIL_HOLD_SHARE),
        FAVORITES("favorites", Settings.RAIL_HOLD_FAVORITES);

        final String label;
        final BooleanSetting setting;
        /** In uptime, or -1. Touched on the main thread only, where TikTok's touches run. */
        long heldAt = -1;

        Button(String label, BooleanSetting setting) {
            this.label = label;
            this.setting = setting;
        }

        /** TikTok's hold check asks whether a press is on this button, where a hold doesn't start. */
        boolean stopsHold(boolean onButton) {
            HookStatus.bound(FAMILY, label + " check");
            if (!onButton || !setting.get() || (this == FAVORITES && holdDownloads())) return onButton;
            HookStatus.bound(FAMILY, "held on " + label);
            heldAt = SystemClock.uptimeMillis();
            return false;
        }

        /** Whether the long press on this button now is one the hold check just let through. */
        boolean heldJustNow() {
            return heldAt >= 0 && setting.get()
                    && SystemClock.uptimeMillis() - heldAt <= ViewConfiguration.getLongPressTimeout() + GRACE_MS;
        }

        /** TikTok's long press on this button, about to open its menu. True skips the menu. */
        boolean skipsMenu() {
            HookStatus.bound(FAMILY, label + " long press");
            if (!heldJustNow()) return false;
            HookStatus.bound(FAMILY, label + " menu skipped");
            return true;
        }
    }

    private RailHold() {
    }

    public static boolean commentStopsHold(boolean onComment) {
        return Button.COMMENT.stopsHold(onComment);
    }

    public static boolean shareStopsHold(boolean onShare) {
        return Button.SHARE.stopsHold(onShare);
    }

    public static boolean favoritesStopsHold(boolean onFavorites) {
        return Button.FAVORITES.stopsHold(onFavorites);
    }

    /**
     * Comment's long press, about to open the emoji row. TikTok marks the press as a long one
     * before it gets here, so a true answer skips the row and letting go still isn't a tap.
     */
    public static boolean skipCommentMenu() {
        return Button.COMMENT.skipsMenu();
    }

    /**
     * Favorites' long press, about to offer a new collection. TikTok marks the press as a long one
     * before it gets here, so a true answer skips the offer and letting go doesn't save the video.
     * With Hold TikTok's save button to download on, the press saves the video with the download
     * settings instead (#433).
     */
    public static boolean skipFavoritesMenu() {
        if (holdDownloads()) {
            HookStatus.bound(FAMILY, "favorites hold download");
            saveOnScreen();
            return true;
        }
        return Button.FAVORITES.skipsMenu();
    }

    /** Saves the video on screen with the download settings, as a hold on Favorites does. */
    private static void saveOnScreen() {
        Context context = Utils.getActivity();
        OriginalPhotos.startFromButton(GestureActions.onScreenAweme(),
                context != null ? context : Utils.getContext());
    }

    /**
     * Buttons Favorites' bind gave only a plain click, which carry Hushfeed's own long press, each
     * with whether it took long presses before. Main thread only, where TikTok binds.
     */
    private static final Map<View, Boolean> CLICK_ONLY = new WeakHashMap<>();

    /**
     * Favorites' bind, right after TikTok took its touch listener off the button. A button given
     * Hushfeed's long press on an earlier bind loses it here, so a bind that hands the button
     * TikTok's own long press never leaves both on it (#499).
     */
    public static void favoritesBound(View button) {
        HookStatus.bound(FAMILY, "favorites bind");
        Boolean longClickable = CLICK_ONLY.remove(button);
        if (longClickable == null) return;
        button.setOnLongClickListener(null);
        button.setLongClickable(longClickable);
    }

    /**
     * Favorites' bind giving the button only a plain click: signed out, in the landscape feed, or
     * on a post still publishing or scheduled. TikTok then has no long press on it at all, so a
     * hold did nothing however Hold TikTok's save button to download was set (#499). The button
     * gets a long press of Hushfeed's own, which asks the switches at each press, so a change
     * applies to buttons already on screen.
     */
    public static void favoritesClickOnly(View button) {
        HookStatus.bound(FAMILY, "favorites click only");
        if (!CLICK_ONLY.containsKey(button)) CLICK_ONLY.put(button, button.isLongClickable());
        button.setOnLongClickListener(FavoritesHold.INSTANCE);
    }

    /**
     * The long press a plain-click Favorites button gets. With the hold save on it saves the
     * video; a press the hold check let through at the hold speed is taken too, so letting go
     * after the hold doesn't count as a tap. Anything else is left to TikTok's click, as before.
     */
    static final class FavoritesHold implements View.OnLongClickListener {
        static final FavoritesHold INSTANCE = new FavoritesHold();

        private FavoritesHold() {
        }

        @Override public boolean onLongClick(View view) {
            HookStatus.bound(FAMILY, "favorites own long press");
            if (holdDownloads()) {
                HookStatus.bound(FAMILY, "favorites hold download");
                saveOnScreen();
                return true;
            }
            return Button.FAVORITES.skipsMenu();
        }
    }

    /**
     * TikTok's touch listener on Favorites, as a press lands, asking whether it landed on the icon
     * or its count; only then does the long press start. Accounts in TikTok's
     * tt_interaction_area_ux_opt test at 3 or more get this check and the rest don't, so a hold
     * beside the icon did nothing at all for some people (#499). With Hold TikTok's save button
     * to download on, a press anywhere on the button counts. Taps are never asked.
     *
     * @param onIcon TikTok's answer, non-zero for yes
     */
    public static boolean favoritesPressCounts(int onIcon) {
        HookStatus.bound(FAMILY, "favorites press check");
        if (onIcon != 0) return true;
        if (!holdDownloads()) return false;
        HookStatus.bound(FAMILY, "favorites press widened");
        return true;
    }

    /**
     * Whether a long press on Favorites downloads the video (#433). It takes Favorites from the
     * hold speed too, so one press never both speeds the video up and saves it.
     */
    static boolean holdDownloads() {
        return Settings.HOLD_SAVE_TO_DOWNLOAD.get() && SettingsStatus.advancedDownloadsEnabled
                && !HushfeedPause.isPaused();
    }

    /** Where TikTok gives the Share button, or one of its children, its long press. */
    public static void setShareLongClick(View button, View.OnLongClickListener listener) {
        HookStatus.bound(FAMILY, "share listener");
        button.setOnLongClickListener(listener == null ? null : new ShareMenu(listener));
    }

    /**
     * TikTok's hold, dropping the hold it had set to start 300 ms after the press landed: the
     * finger moved off the spot, a second finger landed, or the press ended. Every pass goes with
     * it, since none of them belongs to a press TikTok will hold.
     */
    public static void holdDropped(Handler handler, Runnable pending) {
        handler.removeCallbacks(pending);
        HookStatus.bound(FAMILY, "hold dropped");
        forgetPasses();
    }

    private static void forgetPasses() {
        for (Button button : Button.values()) button.heldAt = -1;
    }

    static void resetForTests() {
        forgetPasses();
        CLICK_ONLY.clear();
    }

    /**
     * Share's own long press, which opens its menu unless the press is being held at the hold
     * speed. Asked each time, so the switch applies to buttons already on screen. Taking the long
     * press is what keeps letting go from counting as a tap.
     */
    static final class ShareMenu implements View.OnLongClickListener {
        final View.OnLongClickListener tikTok;

        ShareMenu(View.OnLongClickListener tikTok) {
            this.tikTok = tikTok;
        }

        @Override public boolean onLongClick(View view) {
            return Button.SHARE.skipsMenu() || tikTok.onLongClick(view);
        }
    }
}
