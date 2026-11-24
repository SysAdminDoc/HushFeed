/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.GridPicker;
import app.morphe.extension.tiktok.feed.GridSort;
import app.morphe.extension.tiktok.privacy.AccountCleanupSources.Kind;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Tap a video to remove it (#401): with the switch on, a tap on a video in your own Liked
 * videos, Favorites or Reposts takes it off that list instead of opening it, with the same
 * request Account cleanup sends for one video. Anyone else's grids, and your other tabs, open
 * videos as usual.
 *
 * <p>The cell dims while the request is out, and the video leaves the grid once TikTok says
 * yes. A second tap on it meanwhile sends nothing more.</p>
 */
public final class QuickRemove {
    /** The grid fragments' arguments: the profile is the signed-in one, and which tab it is. */
    static final String MY_PROFILE = "is_my_profile";
    static final String TAB_TYPE = "type";
    static final int TAB_LIKED = 1;
    static final int TAB_FAVORITES = 4;
    static final int TAB_FAVORITE_VIDEOS = 25;
    static final float DIMMED = 0.4f;

    /** The videos whose removal is out, as kind and id. */
    private static final Set<String> SENDING = new HashSet<>();

    /** Stand-ins for a test: TikTok's services, and where a request's thread comes from. */
    static volatile AccountCleanupSources.Services servicesForTests;
    static volatile Executor executorForTests;

    private QuickRemove() {
    }

    /**
     * The profile grids' cell tap, before TikTok opens the video. True when the tap was taken
     * here, so TikTok doesn't open it.
     */
    public static boolean tap(Object fragment, Object video, View cell) {
        try {
            if (!Settings.TAP_TO_REMOVE.get()) return false;
            Kind kind = gridKind(Reflect.invoke(fragment, "getArguments"));
            String id = kind == null ? null : TikTokCalls.idOf(video);
            if (id == null || SignedInUser.id() == null) return false;
            return start(kind, id, cell, () -> dropFromGrid(cell, id));
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not take a tap on a grid video", ex);
            return false;
        }
    }

    /**
     * The Reposts tab's cell tap, before TikTok opens the video: the tab's view model, the row,
     * its video and the uid of the profile the tab belongs to. True when the tap was taken here.
     */
    public static boolean repostTap(Object viewModel, Object row, Object video, String owner, View view) {
        try {
            if (!Settings.TAP_TO_REMOVE.get()) return false;
            String me = SignedInUser.id();
            if (me == null || !me.equals(owner)) return false;
            String id = TikTokCalls.idOf(video);
            if (id == null) return false;
            return start(Kind.REPOSTS, id, view, () -> dropFromReposts(viewModel, row, id));
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not take a tap on a repost", ex);
            return false;
        }
    }

    /** Which list a grid fragment's arguments make it: your own Liked or Favorites, or null. */
    static Kind gridKind(Object arguments) {
        if (!(arguments instanceof Bundle)) return null;
        Bundle bundle = (Bundle) arguments;
        if (!bundle.getBoolean(MY_PROFILE, false)) return null;
        switch (bundle.getInt(TAB_TYPE, -1)) {
            case TAB_LIKED:
                return Kind.LIKES;
            case TAB_FAVORITES:
            case TAB_FAVORITE_VIDEOS:
                return Kind.FAVORITES;
            default:
                return null;
        }
    }

    /** What takes a removed video off the screen. */
    interface Drop {
        void run() throws Exception;
    }

    private static boolean start(Kind kind, String id, View view, Drop onRemoved) {
        String key = kind + ":" + id;
        synchronized (SENDING) {
            // Already on its way: the tap is still taken, so the video doesn't open.
            if (!SENDING.add(key)) return true;
        }
        float alpha = view == null ? 1f : view.getAlpha();
        if (view != null) view.setAlpha(DIMMED);
        Runnable work = () -> {
            boolean removed = false;
            try {
                AccountCleanupSources.Services services = servicesForTests;
                AccountCleanupSources.sourceFor(kind, services != null ? services : AccountCleanupSources.TIKTOK)
                        .remove(id);
                removed = true;
            } catch (Throwable ex) {
                Logger.printInfo(() -> "Tap to remove: " + kind + " " + id + " not removed: " + ex.getMessage());
            }
            boolean done = removed;
            Utils.runOnMainThread(() -> finish(kind, key, view, alpha, done, onRemoved));
        };
        Executor executor = executorForTests;
        if (executor != null) executor.execute(work);
        else new Thread(work, "Hushfeed quick remove").start();
        return true;
    }

    private static void finish(Kind kind, String key, View view, float alpha, boolean removed, Drop onRemoved) {
        synchronized (SENDING) {
            SENDING.remove(key);
        }
        if (view != null) view.setAlpha(alpha);
        if (!removed) {
            Utils.showToastShort(L10n.t("Couldn't remove that video. Try again."));
            return;
        }
        try {
            onRemoved.run();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not take a removed video off the screen", ex);
        }
        switch (kind) {
            case LIKES:
                Utils.showToastShort(L10n.t("Removed from Liked videos"));
                break;
            case FAVORITES:
                Utils.showToastShort(L10n.t("Removed from Favorites"));
                break;
            default:
                Utils.showToastShort(L10n.t("Removed from Reposts"));
                break;
        }
    }

    /** Takes the video off the grid the cell sits in, and off the lists kept to sort that grid. */
    static void dropFromGrid(View cell, String id) throws ReflectiveOperationException {
        dropFromAdapter(GridPicker.adapterOf(cell), id);
    }

    /** Takes the video off a grid's adapter, and off the lists kept to sort that grid. */
    static void dropFromAdapter(Object adapter, String id) throws ReflectiveOperationException {
        if (adapter == null) return;
        GridSort.forget(adapter, id);
        Object data = Reflect.invoke(adapter, "getData");
        if (data instanceof List && !dropIn((List<?>) data, id)) {
            // A list that can't change: the grid gets a copy without the video.
            Field items = Reflect.field(adapter.getClass(), "mmItems");
            if (items != null) items.set(adapter, without((List<?>) data, id));
        }
        adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
    }

    /** Takes the row off the Reposts tab the way TikTok does when you take a repost back there. */
    static void dropFromReposts(Object viewModel, Object row, String id) throws ReflectiveOperationException {
        Object all = Reflect.invoke(viewModel, "listGetAll");
        if (!(all instanceof List)) return;
        List<?> rows = (List<?>) all;
        int at = -1;
        for (int index = 0; index < rows.size() && at < 0; index++) {
            Object entry = rows.get(index);
            if (entry == row || id.equals(idOfEntry(entry))) at = index;
        }
        if (at < 0) return;
        viewModel.getClass().getMethod("listRemoveItemAt", int.class).invoke(viewModel, at);
    }

    /**
     * Takes every entry for the video out of {@code list}, in place. False only when the list
     * held it and can't change.
     */
    public static boolean dropIn(List<?> list, String id) {
        try {
            for (Iterator<?> it = list.iterator(); it.hasNext(); ) {
                if (id.equals(idOfEntry(it.next()))) it.remove();
            }
            return true;
        } catch (UnsupportedOperationException fixed) {
            return false;
        }
    }

    /** A copy of {@code list} without the video. */
    public static List<Object> without(List<?> list, String id) {
        List<Object> kept = new ArrayList<>(list.size());
        for (Object entry : list) if (!id.equals(idOfEntry(entry))) kept.add(entry);
        return kept;
    }

    /**
     * The video id of a grid or Reposts entry: the video itself, an entry with an aweme getter
     * or field, or a row that keeps its video in a renamed field.
     */
    static String idOfEntry(Object entry) {
        String id = TikTokCalls.idOf(entry);
        if (id != null || entry == null) return id;
        for (Class<?> type = entry.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !"Aweme".equals(field.getType().getSimpleName())) continue;
                try {
                    field.setAccessible(true);
                    String held = TikTokCalls.idOf(field.get(entry));
                    if (held != null) return held;
                } catch (Throwable ignored) {
                    // the next field
                }
            }
        }
        return null;
    }
}
