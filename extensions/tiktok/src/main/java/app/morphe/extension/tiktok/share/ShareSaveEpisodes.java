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
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.EpisodePicker;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Save episodes in a short drama episode's share sheet (#444), built on the same native Report
 * carrier as Save cover. It opens {@link EpisodePicker}, a checklist of the drama's episodes that
 * saves the picked ones in one run. Only an episode of a drama (or another Series TikTok lists
 * episodes for) gets the button.
 */
public final class ShareSaveEpisodes {
    static final String KEY = "hushfeed_save_episodes";
    private static final String[] AFTER_KEYS = {ShareMakeSticker.KEY, ShareSaveCover.KEY, "save"};
    private static final String FAMILY = "episode saves";
    private static final String POST_PACKAGE = "com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage";
    private static final List<Built> BUILT = new ArrayList<>();

    private ShareSaveEpisodes() {}

    private static final class Built {
        final WeakReference<Object> action;
        final WeakReference<Object> sharePackage;
        final WeakReference<Object> aweme;
        volatile boolean retired;

        Built(Object action, Object sharePackage, Object aweme) {
            this.action = new WeakReference<>(action);
            this.sharePackage = new WeakReference<>(sharePackage);
            this.aweme = new WeakReference<>(aweme);
        }

        boolean matches(Object from, Object post) {
            return !retired && post != null && sharePackage.get() == from && aweme.get() == post;
        }
    }

    /** Finished native row. Stale owned actions come out even when off or on another post. */
    public static List<?> actions(Object sharePackage, List<?> actions) {
        if (actions == null) return null;
        List<Object> row = new ArrayList<>(actions.size() + 1);
        Built reusable = null;
        try {
            Object post = EpisodePicker.enabled() ? post(sharePackage) : null;
            boolean eligible = EpisodePicker.isEpisode(post);
            if (eligible) ShareActionCatalog.observe(Collections.singletonList(new CatalogEntry()), ShareSurface.VIDEO);
            eligible &= !hidden();
            for (Object item : actions) {
                Built built = find(item);
                if (built == null) row.add(item);
                else if (eligible && reusable == null && built.matches(sharePackage, post)) reusable = built;
                else if (built != reusable) built.retired = true;
            }
            if (eligible) {
                Object action = reusable == null ? null : reusable.action.get();
                if (action == null) {
                    Activity activity = Utils.getVisibleActivity();
                    if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        action = newAction(activity, post);
                        if (action != null) remember(new Built(action, sharePackage, post));
                    }
                }
                if (action != null) {
                    row.add(place(row), action);
                    HookStatus.bound(FAMILY, "share sheet button");
                }
            } else if (reusable != null) reusable.retired = true;
            return sameRow(actions, row) ? actions : row;
        } catch (Throwable failure) {
            for (Object item : actions) {
                Built built = find(item);
                if (built != null) built.retired = true;
            }
            row.clear();
            for (Object item : actions) if (!owns(item)) row.add(item);
            HookStatus.threw(FAMILY, "share sheet button", failure);
            Logger.printException(() -> "Could not add Save episodes to the share sheet", failure);
            return sameRow(actions, row) ? actions : row;
        }
    }

    private static boolean sameRow(List<?> before, List<?> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    /** Right after Make sticker, else Save cover, else Download, else at the end of the row. */
    private static int place(List<?> row) {
        for (String key : AFTER_KEYS) {
            for (int i = 0; i < row.size(); i++) {
                if (key.equals(Reflect.string(row.get(i), "key", "key"))) return i + 1;
            }
        }
        return row.size();
    }

    private static boolean hidden() {
        if (!SettingsStatus.shareSheetEnabled) return false;
        if (Settings.HIDE_SHARE_ACTIONS.get()) return true;
        String label = ShareActionCatalog.canonical(L10n.t("Save episodes"));
        for (String entry : ShareModelFilter.hiddenItems(ShareSurface.VIDEO).split("[,\\n]")) {
            String key = ShareActionCatalog.canonical(entry);
            if (key.equals(ShareActionCatalog.canonical(KEY)) || key.equals("save episodes") || key.equals(label)) return true;
        }
        return false;
    }

    private static Object post(Object sharePackage) {
        if (sharePackage == null || !POST_PACKAGE.equals(sharePackage.getClass().getName())
                || !"aweme".equals(Reflect.string(sharePackage, "getItemType", "itemType"))) return null;
        Object aweme = Reflect.readField(sharePackage, "aweme");
        return aweme instanceof Aweme ? aweme : null;
    }

    private static synchronized Built find(Object action) {
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
        find(null);
        BUILT.add(built);
    }

    public static boolean owns(Object action) { return action != null && find(action) != null; }
    public static String key(Object action) { return owns(action) ? KEY : null; }
    public static String label(Object action) { return owns(action) ? L10n.t("Save episodes") : null; }

    /** Called after the native drawable store on every bind, including already-attached cells. */
    public static Drawable icon(Object action) {
        if (!owns(action)) return null;
        Context context = Utils.getVisibleActivity();
        if (context == null) context = Utils.getContext();
        return new EpisodesGlyphDrawable(SettingsUi.textPrimaryOn(SettingsUi.isDarkContext(context)),
                SettingsUi.dp(context, 2));
    }

    public static void bindIcon(Object action, ImageView image) {
        if (!owns(action)) return;
        Drawable icon = NativeShareIcon.restyle(image.getDrawable(), EpisodesGlyphDrawable::new);
        if (icon == null) icon = icon(action);
        if (icon != null) image.setImageDrawable(icon);
    }

    /** Only owned carriers are consumed, and never fall through to their original Report behavior. */
    public static boolean click(Object action, Context context, Object sharePackage) {
        Built built = find(action);
        if (built == null) return false;
        try {
            Object post = post(sharePackage);
            if (!EpisodePicker.enabled() || hidden() || !built.matches(sharePackage, post)) return true;
            Context from = context instanceof Activity ? context : Utils.getVisibleActivity();
            Opener stand = openerForTests;
            if (stand != null) stand.open(from, post);
            else EpisodePicker.open(from, post);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "share sheet button", failure);
            Logger.printException(() -> "Could not open the episode picker from the share sheet", failure);
        }
        return true;
    }

    /** Catalog discovery uses the same localized label without guessing obfuscated getter names. */
    private static final class CatalogEntry {
        public String key() { return KEY; }
        public String getLabel() { return L10n.t("Save episodes"); }
    }

    interface Native { Object newAction(Activity activity, Object aweme); }
    interface Opener { void open(Context context, Object aweme); }
    static volatile Native nativeForTests;
    static volatile Opener openerForTests;

    /** A native Report carrier from the shared bridge the patch fills in. */
    static Object newAction(Activity activity, Object aweme) {
        Native stand = nativeForTests;
        return stand == null ? OwnedShareActions.newAction(activity, aweme) : stand.newAction(activity, aweme);
    }
}
