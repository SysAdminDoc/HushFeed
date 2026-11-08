/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.comment.StickerCreator;
import app.morphe.extension.tiktok.download.CoverSaver;
import app.morphe.extension.tiktok.download.StickerClip;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Make sticker in a video's share sheet (#88), built on the same native Report carrier as Save
 * cover. It opens TikTok's own animated sticker editor for the video (#214), which TikTok's Create
 * sticker only offers on some videos, feeding it a downloaded copy when the creator turned
 * stickers off, and turns the video's cover into one of your saved stickers through Create
 * stickers when that editor can't open.
 */
public final class ShareMakeSticker {
    static final String KEY = "hushfeed_make_sticker";
    private static final String[] AFTER_KEYS = {ShareSaveCover.KEY, "save"};
    private static final String FAMILY = "share stickers";
    private static final String POST_PACKAGE = "com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage";
    private static final List<Built> BUILT = new ArrayList<>();

    private ShareMakeSticker() {}

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

    /** Whether Create stickers is patched in and Hushfeed isn't paused. */
    static boolean enabled() {
        return SettingsStatus.stickerCreatorEnabled && !HushfeedPause.isPaused();
    }

    /** Finished native row. Stale owned actions come out even when off or on another surface. */
    public static List<?> actions(Object sharePackage, List<?> actions) {
        if (actions == null) return null;
        List<Object> row = new ArrayList<>(actions.size() + 1);
        Built reusable = null;
        try {
            Object post = enabled() ? post(sharePackage) : null;
            boolean eligible = post != null && CoverSaver.hasCover(post);
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
            Logger.printException(() -> "Could not add Make sticker to the share sheet", failure);
            return sameRow(actions, row) ? actions : row;
        }
    }

    private static boolean sameRow(List<?> before, List<?> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    /** Right after Save cover, else after Download, else at the end of the row. */
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
        String label = ShareActionCatalog.canonical(L10n.t("Make sticker"));
        for (String entry : ShareModelFilter.hiddenItems(ShareSurface.VIDEO).split("[,\\n]")) {
            String key = ShareActionCatalog.canonical(entry);
            if (key.equals(ShareActionCatalog.canonical(KEY)) || key.equals("make sticker") || key.equals(label)) return true;
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
    public static String label(Object action) { return owns(action) ? L10n.t("Make sticker") : null; }

    /** Called after the native drawable store on every bind, including already-attached cells. */
    public static Drawable icon(Object action) {
        if (!owns(action)) return null;
        Context context = Utils.getVisibleActivity();
        if (context == null) context = Utils.getContext();
        return new StickerGlyphDrawable(SettingsUi.textPrimaryOn(SettingsUi.isDarkContext(context)),
                SettingsUi.dp(context, 2));
    }

    public static void bindIcon(Object action, ImageView image) {
        if (!owns(action)) return;
        Drawable icon = NativeShareIcon.restyle(image.getDrawable(), StickerGlyphDrawable::new);
        if (icon == null) icon = icon(action);
        if (icon != null) image.setImageDrawable(icon);
    }

    /** Only owned carriers are consumed, and never fall through to their original Report behavior. */
    public static boolean click(Object action, Context context, Object sharePackage) {
        Built built = find(action);
        if (built == null) return false;
        try {
            Object post = post(sharePackage);
            if (!enabled() || hidden() || !built.matches(sharePackage, post)) return true;
            Context from = context instanceof Activity ? context : Utils.getVisibleActivity();
            Maker cover = coverForTests;
            make(from, post, cover != null ? cover : StickerCreator::fromCover);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "share sheet button", failure);
            Logger.printException(() -> "Could not make a sticker from the share sheet", failure);
        }
        return true;
    }

    /**
     * TikTok's animated sticker editor for the video (#214). A video whose creator allows stickers
     * opens the way TikTok's own Create sticker does. Any other goes in as a downloaded copy, the
     * way TikTok makes a sticker from a saved video, since the editor won't save one from the video
     * itself. The cover becomes the sticker when neither opens.
     */
    static void make(Context from, Object post, Maker cover) {
        Runnable coverSticker = () -> cover.make(from, post);
        if (!(from instanceof Activity)) {
            coverSticker.run();
            return;
        }
        Activity activity = (Activity) from;
        Runnable copy = () -> fromCopy(activity, post, coverSticker);
        if (creatorAllows(post) && VideoStickerEditor.open(activity, post, copy)) return;
        copy.run();
    }

    /** Whether the video's creator lets people make stickers from it, the check behind TikTok's Create sticker. */
    static boolean creatorAllows(Object post) {
        Object permissions = Reflect.property(post, "getInteractPermission", "interactPermission");
        Object sticker = Reflect.property(permissions, "getAllowCreateSticker", "allowCreateSticker");
        Object status = Reflect.property(sticker, "getStatus", "status");
        return status instanceof Number && ((Number) status).intValue() == 0;
    }

    /** How long TikTok gets to bring its editor up for a file before the cover takes over. */
    static final long FILE_OPEN_WAIT_MS = 4000;
    private static final AtomicBoolean COPYING = new AtomicBoolean();
    /** The copy the editor last read, dropped when the next one starts. */
    private static final AtomicReference<File> LAST_CLIP = new AtomicReference<>();

    /** Downloads the video off the main thread, then hands the file to TikTok's editor. */
    static void fromCopy(Activity activity, Object post, Runnable cover) {
        if (!VideoStickerEditor.takesFiles()) {
            cover.run();
            return;
        }
        Utils.showToastShort(L10n.t("Making your sticker"));
        if (!COPYING.compareAndSet(false, true)) return;
        Context app = activity.getApplicationContext();
        Clips clips = clipsForTests;
        boolean started = worker.run(() -> {
            File clip = null;
            try {
                StickerClip.drop(LAST_CLIP.getAndSet(null));
                clip = clips != null ? clips.fetch(app, post) : StickerClip.fetch(app, post);
            } catch (IOException | RuntimeException error) {
                Logger.printException(() -> "Could not download the video for its sticker", error);
            }
            File fetched = clip;
            Utils.runOnMainThreadNowOrLater(() -> {
                COPYING.set(false);
                openCopy(activity, fetched, cover);
            });
        });
        if (!started) {
            COPYING.set(false);
            cover.run();
        }
    }

    private static void openCopy(Activity activity, File clip, Runnable cover) {
        if (clip == null) {
            cover.run();
            return;
        }
        Activity from = activity.isFinishing() || activity.isDestroyed() ? Utils.getVisibleActivity() : activity;
        if (from == null || from.isFinishing() || from.isDestroyed()) {
            StickerClip.drop(clip);
            cover.run();
            return;
        }
        LeaveWatch left = LeaveWatch.on(from);
        if (!VideoStickerEditor.openFile(from, clip.getAbsolutePath())) {
            left.stop();
            StickerClip.drop(clip);
            cover.run();
            return;
        }
        LAST_CLIP.set(clip);
        // TikTok returns without a word when it can't read the file, leaving the video on screen.
        Utils.runOnMainThreadDelayed(() -> {
            if (left.stop()) return;
            Logger.printInfo(() -> "TikTok's sticker editor didn't take the video file");
            cover.run();
        }, FILE_OPEN_WAIT_MS);
    }

    /** Notices the video's activity giving way to TikTok's editor. */
    private static final class LeaveWatch implements Application.ActivityLifecycleCallbacks {
        private final Activity watched;
        private final Application application;
        private volatile boolean left;

        private LeaveWatch(Activity watched) {
            this.watched = watched;
            this.application = watched.getApplication();
        }

        static LeaveWatch on(Activity activity) {
            LeaveWatch watch = new LeaveWatch(activity);
            if (watch.application != null) watch.application.registerActivityLifecycleCallbacks(watch);
            return watch;
        }

        /** Stops watching and says whether the activity was left meanwhile. */
        boolean stop() {
            if (application != null) application.unregisterActivityLifecycleCallbacks(this);
            return left;
        }

        @Override public void onActivityPaused(Activity activity) {
            if (activity == watched) left = true;
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

    /** Catalog discovery uses the same localized label without guessing obfuscated getter names. */
    private static final class CatalogEntry {
        public String key() { return KEY; }
        public String getLabel() { return L10n.t("Make sticker"); }
    }

    interface Native { Object newAction(Activity activity, Object aweme); }
    interface Maker { void make(Context context, Object aweme); }
    interface Clips { File fetch(Context context, Object aweme) throws IOException; }
    interface Worker { boolean run(Runnable task); }
    static volatile Native nativeForTests;
    static volatile Maker coverForTests;
    static volatile Clips clipsForTests;
    static volatile Worker worker = Utils::runOnBackgroundThread;

    /** A native Report carrier from the shared bridge the patch fills in. */
    static Object newAction(Activity activity, Object aweme) {
        Native stand = nativeForTests;
        return stand == null ? OwnedShareActions.newAction(activity, aweme) : stand.newAction(activity, aweme);
    }
}
