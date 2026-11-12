/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.interaction.Haptics;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * A long press on the Inbox tab flips Hide read receipts and says which way it went (#333). A tap
 * still opens Inbox.
 *
 * <p>{@code HideReadReceipts} reads its setting each time TikTok is about to send a "mark read"
 * request, so the flip applies to the next chat you open, with no restart.
 *
 * <p>On some accounts the Inbox tab has a long press of its own. While the switch is on this takes
 * the press and keeps TikTok's listener (read through View.mListenerInfo), which goes back on the
 * tab when the switch goes off. If TikTok sets its listener again later, the next layout pass takes
 * the press back and keeps the new one instead.
 *
 * <p>Rides on {@link HomeTabSettingsShortcut}'s front window and layout passes, the way
 * {@link ProfileTabWatchHistory} does.
 */
public final class InboxTabReadReceipts {
    private static WeakReference<View> attached = new WeakReference<>(null);
    /** A tab view that doesn't take the tap itself, so a long press on it would swallow taps. */
    private static WeakReference<View> refused = new WeakReference<>(null);
    /** TikTok's own long press on the attached tab, given back when the switch goes off. */
    private static View.OnLongClickListener original;
    private static boolean originalLongClickable;

    private static Field listenerInfoField;
    private static Field longClickField;
    private static boolean listenerUnreadable;

    static final View.OnLongClickListener FLIP = view -> {
        try {
            Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS);
            flip(view);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not flip Hide read receipts from the Inbox tab", error);
        }
        // Handled, so the release that ends the press isn't also a tap that opens Inbox.
        return true;
    };

    private InboxTabReadReceipts() {
    }

    /** Flips Hide read receipts and toasts the new state. Returns the state it left. */
    static boolean flip(View view) {
        boolean hidden = !Settings.HIDE_READ_RECEIPTS.get();
        Settings.HIDE_READ_RECEIPTS.save(hidden);
        Utils.showToastShort(L10n.t(view.getContext(), hidden ? "Read receipts hidden" : "Read receipts shown"));
        return hidden;
    }

    /** Called on each layout pass of the main activity in front. */
    static void apply(Activity activity) {
        try {
            View current = attached.get();
            // Off, or the patch for the receipts isn't in this build: the tab isn't even looked up.
            if (!Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.get() || !SettingsStatus.hideReadReceiptsEnabled) {
                if (current != null) giveBack(current);
                return;
            }
            View inbox = FeedVisibility.inboxTabView(activity);
            if (current != null && current != inbox) giveBack(current);
            if (inbox == null || refused.get() == inbox) return;

            if (attached.get() == inbox) {
                View.OnLongClickListener now = longClickListener(inbox);
                if (listenerUnreadable || now == FLIP) return;
                // TikTok set its long press again, or cleared it: that is what goes back on the
                // tab when the switch goes off.
                original = now;
                originalLongClickable = now != null;
                inbox.setOnLongClickListener(FLIP);
                Logger.printInfo(() -> "TikTok changed the Inbox tab's long press; took it back");
                return;
            }
            if (!inbox.isClickable()) {
                refused = new WeakReference<>(inbox);
                Logger.printInfo(() -> "The Inbox tab view doesn't take the tap itself; no long press");
                return;
            }
            originalLongClickable = inbox.isLongClickable();
            original = originalLongClickable ? longClickListener(inbox) : null;
            if (originalLongClickable && original == null) {
                Logger.printInfo(() -> "Couldn't keep TikTok's own long press on Inbox; "
                        + "it comes back when TikTok rebuilds the tab bar");
            }
            inbox.setOnLongClickListener(FLIP);
            attached = new WeakReference<>(inbox);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not set the Inbox tab's long press", error);
        }
    }

    /** The main activity followed was destroyed: its tab goes with it, and so does TikTok's listener. */
    static void release() {
        View current = attached.get();
        if (current != null) giveBack(current);
        original = null;
        originalLongClickable = false;
    }

    private static void giveBack(View tab) {
        tab.setOnLongClickListener(original);
        tab.setLongClickable(originalLongClickable);
        attached = new WeakReference<>(null);
        original = null;
        originalLongClickable = false;
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
        if (current != null) giveBack(current);
        attached = new WeakReference<>(null);
        refused = new WeakReference<>(null);
        original = null;
        originalLongClickable = false;
    }
}
