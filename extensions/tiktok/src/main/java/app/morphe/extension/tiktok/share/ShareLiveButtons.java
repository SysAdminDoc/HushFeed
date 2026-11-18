/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.live.LiveAutoTap;
import app.morphe.extension.tiktok.live.LivePanelTheme;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Record and Auto tap as rows of a LIVE's menu (#359), for people who'd rather have nothing over
 * the stream. With LIVE buttons in the long-press menu on, the two chips stay off the video and
 * these rows do their job, each present only while its own switch is on. They're built on the same
 * native Report carrier as LIVE recording quality, and say what a tap will do when the menu opens.
 */
public final class ShareLiveButtons {
    static final String RECORD_KEY = "hushfeed_live_record";
    static final String AUTO_TAP_KEY = "hushfeed_live_auto_tap";
    private static final String FAMILY = "live buttons in menu";
    private static final List<Built> BUILT = new ArrayList<>();

    private ShareLiveButtons() {}

    private static final class Built {
        final WeakReference<Object> action;
        final WeakReference<Object> sharePackage;
        final boolean record;
        volatile boolean retired;

        Built(Object action, Object sharePackage, boolean record) {
            this.action = new WeakReference<>(action);
            this.sharePackage = new WeakReference<>(sharePackage);
            this.record = record;
        }
    }

    /** Finished native row. Stale owned actions come out when off or on another surface. */
    public static List<?> actions(Object sharePackage, List<?> actions) {
        if (actions == null) return null;
        List<Object> row = new ArrayList<>(actions.size() + 2);
        try {
            boolean live = ShareLiveQuality.isLive(sharePackage);
            boolean wantRecord = live && LiveRecordChip.inMenu();
            boolean wantAutoTap = live && LiveAutoTap.inMenu();
            Built record = null;
            Built autoTap = null;
            for (Object item : actions) {
                Built built = find(item);
                if (built == null) {
                    row.add(item);
                    continue;
                }
                boolean wanted = built.record ? wantRecord : wantAutoTap;
                boolean free = (built.record ? record : autoTap) == null;
                if (!wanted || !free || built.retired || built.sharePackage.get() != sharePackage) {
                    built.retired = true;
                } else if (built.record) {
                    record = built;
                } else {
                    autoTap = built;
                }
            }
            if (wantRecord) add(row, record, true, sharePackage);
            if (wantAutoTap) add(row, autoTap, false, sharePackage);
            return sameRow(actions, row) ? actions : row;
        } catch (Throwable failure) {
            row.clear();
            for (Object item : actions) {
                Built built = find(item);
                if (built != null) built.retired = true;
                else row.add(item);
            }
            HookStatus.threw(FAMILY, "share sheet row", failure);
            Logger.printException(() -> "Could not add the LIVE buttons to the menu", failure);
            return sameRow(actions, row) ? actions : row;
        }
    }

    private static void add(List<Object> row, Built reusable, boolean record, Object sharePackage) {
        Object action = reusable == null ? null : reusable.action.get();
        if (action == null) {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            action = newAction(activity);
            if (action == null) return;
            remember(new Built(action, sharePackage, record));
        }
        row.add(action);
        HookStatus.bound(FAMILY, "share sheet row");
    }

    private static boolean sameRow(List<?> before, List<?> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    private static synchronized Built find(Object action) {
        if (action == null) return null;
        Built found = null;
        for (Iterator<Built> each = BUILT.iterator(); each.hasNext();) {
            Built built = each.next();
            Object held = built.action.get();
            if (held == null) each.remove();
            else if (held == action) found = built;
        }
        return found;
    }

    private static synchronized void remember(Built built) {
        BUILT.add(built);
    }

    public static boolean owns(Object action) {
        return find(action) != null;
    }

    public static String key(Object action) {
        Built built = find(action);
        if (built == null) return null;
        return built.record ? RECORD_KEY : AUTO_TAP_KEY;
    }

    public static String label(Object action) {
        Built built = find(action);
        if (built == null) return null;
        return built.record ? LiveRecordChip.menuLabel() : LiveAutoTap.menuLabel();
    }

    public static void bindIcon(Object action, ImageView image) {
        Built built = find(action);
        if (built == null) return;
        NativeShareIcon.Glyph glyph = built.record
                ? LiveButtonGlyphDrawable::forRecord : LiveButtonGlyphDrawable::forAutoTap;
        Drawable icon = NativeShareIcon.restyle(image.getDrawable(), glyph);
        if (icon == null) {
            Context context = Utils.getVisibleActivity();
            if (context == null) context = Utils.getContext();
            icon = glyph.make(SettingsUi.textPrimaryOn(SettingsUi.isDarkContext(context)),
                    SettingsUi.dp(context, 2));
        }
        image.setImageDrawable(icon);
    }

    /**
     * Only owned carriers are consumed, and never fall through to their original Report behavior.
     * The menu closes first: Auto tap waits for the room to have the screen back.
     */
    public static boolean click(Object action, Context context, Object sharePackage) {
        Built built = find(action);
        if (built == null) return false;
        try {
            if (built.retired || built.sharePackage.get() != sharePackage
                    || !ShareLiveQuality.isLive(sharePackage)) return true;
            if (!(built.record ? LiveRecordChip.inMenu() : LiveAutoTap.inMenu())) return true;
            LivePanelTheme.closeLiveMenu();
            Runnable stand = built.record ? recordTapForTests : autoTapForTests;
            if (stand != null) stand.run();
            else if (built.record) LiveRecordChip.menuTap();
            else LiveAutoTap.menuTap();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "share sheet row", failure);
            Logger.printException(() -> "Could not use a LIVE button from the menu", failure);
        }
        return true;
    }

    interface Native { Object newAction(Activity activity); }
    static volatile Native nativeForTests;
    static volatile Runnable recordTapForTests;
    static volatile Runnable autoTapForTests;

    /** A native Report carrier from the shared bridge the patch fills in; it takes no post. */
    static Object newAction(Activity activity) {
        Native stand = nativeForTests;
        return stand == null ? OwnedShareActions.newAction(activity, null) : stand.newAction(activity);
    }
}
