/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import android.content.Context;
import android.content.SharedPreferences;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.SignedInUser;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stories you watched while Ghost mode held the view back, so the inbox's story row still
 * shows them as watched after a restart (#427).
 *
 * <p>TikTok marks a story watched on the phone as it plays, and the server learns it from the
 * view report. Ghost mode stops that report, so the next launch brings the story back from the
 * server unwatched and its ring lights up again. {@link #remember} is told each story whose report
 * Ghost mode held back, and {@link #inboxStatus} marks those stories watched on each item the
 * inbox's story row builds, the way TikTok does once a story plays.
 *
 * <p>The profile page loads its own copy of the person, so its ring keeps what the server knows,
 * which is how you can still see that the view went unreported. Stories last a day, so a story is
 * kept for two and then forgotten. Each is kept under the account that watched it.
 */
@SuppressWarnings("unused")
public final class StoryReads {
    public static final String PREFS_NAME = "hushfeed_story_reads";
    /** A story lasts a day. Two covers one posted just before you watched it. */
    static final long KEEP_MS = 48L * 60 * 60 * 1000;
    /** TikTok's story status for a ring with something left to watch, and for one with nothing. */
    static final int UNWATCHED = 1;
    static final int ALL_WATCHED = 2;

    /** So a test can set the clock. */
    static volatile Long nowForTests;

    /** Account, a slash, the story's id, mapped to when it was watched. */
    private static final Map<String, Long> watched = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private StoryReads() {
    }

    /** Called with a story whose view report Ghost mode held back. Never throws. */
    static void remember(Aweme story) {
        try {
            if (story == null) return;
            String id = story.getAid();
            String account = SignedInUser.id();
            if (id == null || id.isEmpty() || account == null) return;
            load();
            String key = key(account, id);
            long now = now();
            watched.put(key, now);
            SharedPreferences preferences = preferences();
            if (preferences != null) preferences.edit().putLong(key, now).apply();
        } catch (Throwable unsaved) {
            // The ring lights up again on the next launch, as it did before.
            Logger.printDebug(() -> "Could not keep a watched story: " + unsaved);
        }
    }

    /**
     * Called as the inbox's story row builds an item, with the status TikTok gave it. Marks the
     * person's remembered stories watched and answers the status to keep: watched when nothing
     * else is left, otherwise TikTok's own. Never throws.
     */
    public static int inboxStatus(Object item, int status) {
        try {
            if (item == null || HushfeedPause.isPaused()) return status;
            String account = SignedInUser.id();
            if (account == null) return status;
            load();
            if (watched.isEmpty()) return status;
            Object story = call(item, "getStoryCollection");
            Object author = call(story, "getAuthor");
            Object metadata = read(call(author, "getAllStoryMetadata"), "storyLiteMetadata");
            if (!(metadata instanceof List)) return status;
            Set<Long> hidden = hiddenStories(story);
            int marked = 0;
            int left = 0;
            for (Object entry : (List<?>) metadata) {
                if (entry == null || Boolean.TRUE.equals(read(entry, "viewed"))) continue;
                Object id = read(entry, "itemID");
                if (!(id instanceof Long) || hidden.contains(id)) continue;
                if (watched.containsKey(key(account, String.valueOf(id)))) {
                    entry.getClass().getMethod("setViewed", boolean.class).invoke(entry, true);
                    marked++;
                } else {
                    left++;
                }
            }
            if (marked == 0 || left > 0) return status;
            author.getClass().getMethod("setStoryStatus", int.class).invoke(author, ALL_WATCHED);
            int was = status;
            Logger.printInfo(() -> "Kept watched stories watched; status " + was);
            return status == UNWATCHED ? ALL_WATCHED : status;
        } catch (Throwable unreadable) {
            // The row shows what TikTok sent.
            Logger.printDebug(() -> "Could not mark watched stories in the inbox: " + unreadable);
            return status;
        }
    }

    /** The stories TikTok leaves out of a ring (filterStoryIds), which never count as unwatched. */
    private static Set<Long> hiddenStories(Object story) {
        try {
            Object info = call(call(story, "getUserStory"), "getUserFilterStoryInfo");
            Object ids = read(info, "filterStoryIds");
            if (!(ids instanceof List)) return Collections.emptySet();
            Set<Long> hidden = new HashSet<>();
            for (Object id : (List<?>) ids) {
                if (id instanceof Long) hidden.add((Long) id);
            }
            return hidden;
        } catch (Throwable unreadable) {
            return Collections.emptySet();
        }
    }

    /** Reads the saved stories once a process, dropping the ones too old to still be up. */
    private static void load() {
        if (loaded) return;
        synchronized (StoryReads.class) {
            if (loaded) return;
            SharedPreferences preferences = preferences();
            if (preferences == null) return;
            long oldest = now() - KEEP_MS;
            SharedPreferences.Editor expired = null;
            for (Map.Entry<String, ?> saved : preferences.getAll().entrySet()) {
                Object at = saved.getValue();
                if (at instanceof Long && (Long) at >= oldest) {
                    watched.put(saved.getKey(), (Long) at);
                } else {
                    if (expired == null) expired = preferences.edit();
                    expired.remove(saved.getKey());
                }
            }
            if (expired != null) expired.apply();
            loaded = true;
        }
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String key(String account, String story) {
        return account + "/" + story;
    }

    private static long now() {
        Long forTests = nowForTests;
        return forTests != null ? forTests : System.currentTimeMillis();
    }

    private static Object call(Object target, String name) throws Exception {
        if (target == null) return null;
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static Object read(Object target, String name) throws Exception {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException notHere) {
                // Look in the class it extends.
            }
        }
        return null;
    }

    /** Forgets what this process read, keeping the saved file, as a restart does. For tests. */
    static void forgetLoadedForTests() {
        watched.clear();
        loaded = false;
    }

    /** Forgets everything, for tests. */
    static void clearForTests() {
        watched.clear();
        loaded = false;
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().clear().commit();
    }
}
