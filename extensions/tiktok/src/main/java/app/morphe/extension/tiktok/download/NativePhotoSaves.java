/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The photo saves Hushfeed leaves to TikTok's own job (#197): a live photo, or a still with
 * Download original photos and Save photo posts as a video both off. With Check for
 * already-saved videos on they get what a photo post Hushfeed saves gets: a post saved before
 * is offered Open or Save again first, under the photo post's or the story's title, and the
 * first photo TikTok copies to the gallery is recorded under the post's id, so the grid's mark
 * covers it.
 *
 * <p>To ask first, the job is held while the record is read off the main thread, then started
 * again with the arguments it was handed, through its own class. A post the record's ids rule
 * out starts at once, and a job whose class can't be read runs as it came and is only recorded.
 * Anything that goes wrong here lets TikTok's save run as it would have.
 *
 * <p>Only Android 10 and later are recorded: there the gallery copy hands back a content Uri
 * for each photo, while older versions copy to file paths it doesn't hand back.
 */
public final class NativePhotoSaves {
    /** Saves TikTok's job runs, by post id, each waiting for its gallery copy. */
    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();
    /**
     * How long a save waits for its copy. TikTok turns a job away while another one downloads,
     * and a job it turned away never copies anything.
     */
    static final long PENDING_MILLIS = 10 * 60 * 1000L;
    /** The job class's static post, which says which post a gallery copy belongs to. */
    private static volatile Field jobPost;
    /** Set while a held job starts again, so its hook lets it through. */
    private static final ThreadLocal<Boolean> RERUNNING = new ThreadLocal<>();

    private NativePhotoSaves() {}

    /** One save TikTok's job runs, as it was accepted. */
    static final class Pending {
        final String id;
        final long generation;
        final long since;

        Pending(String id, long generation) {
            this.id = id;
            this.generation = generation;
            this.since = SystemClock.elapsedRealtime();
        }
    }

    /** Whether a held job is starting again on this thread. */
    static boolean rerunning() {
        return Boolean.TRUE.equals(RERUNNING.get());
    }

    /**
     * After Hushfeed's own save stood aside. {@code arguments} are the job's, in order, the job's
     * own instance first.
     *
     * @return true when the job is held for the already-saved check, so TikTok's must not run now
     */
    static boolean hold(Object job, Object aweme, Object[] arguments) {
        try {
            return holdForCheck(job, aweme, arguments);
        } catch (RuntimeException | LinkageError failure) {
            Logger.printException(() -> "Could not hold TikTok's photo save for the check", failure);
            return false;
        }
    }

    private static boolean holdForCheck(Object job, Object aweme, Object[] arguments) {
        forgetStale();
        if (job == null || !Settings.CHECK_SAVED_VIDEOS.get()) return false;
        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null || id.isEmpty()) return false;
        Field post = postField(job.getClass(), aweme);
        if (post == null) {
            Logger.printInfo(() -> "TikTok's photo job keeps no post to check, so it isn't recorded");
            return false;
        }
        jobPost = post;
        Pending pending = new Pending(id, SavedVideoArchive.generation());
        Context context = Utils.getContext();
        Method entry = SavedVideoArchive.knownUnsaved(id) ? null : entry(job.getClass(), arguments);
        if (entry == null || context == null) {
            PENDING.put(id, pending);
            return false;
        }
        Context application = context.getApplicationContext();
        Context app = application == null ? context : application;
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        Runnable start = () -> rerun(entry, arguments, pending, onMain);
        boolean started = Utils.runOnBackgroundThread(() -> {
            MediaFileWriter.Saved previous;
            try {
                previous = SavedVideoArchive.find(app, id);
            } catch (RuntimeException failure) {
                // TikTok would have saved it: an unreadable record only costs the question.
                Logger.printException(() -> "Could not check TikTok's photo save, saving it anyway", failure);
                previous = null;
            }
            if (previous == null) start.run();
            else SavedVideoArchive.offer(previous,
                    SavedVideoArchive.photoTitle(StoryShareSave.isStory(aweme)), start, () -> { });
        });
        if (started) return true;
        PENDING.put(id, pending);
        return false;
    }

    /**
     * Starts the held job again, on the thread kind it first came on. Save again is tapped on the
     * main thread, so a job that came off it is handed back to a background thread.
     */
    private static void rerun(Method entry, Object[] arguments, Pending pending, boolean onMain) {
        Runnable run = () -> {
            PENDING.put(pending.id, pending);
            RERUNNING.set(true);
            try {
                entry.invoke(null, arguments);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                PENDING.remove(pending.id, pending);
                Logger.printException(() -> "Could not start TikTok's photo save again", failure);
                Utils.showToastLong(L10n.t("None of the photos could be saved. Try again."));
            } finally {
                RERUNNING.remove();
            }
        };
        if (onMain) Utils.runOnMainThread(run);
        else if (Looper.myLooper() != Looper.getMainLooper() || !Utils.runOnBackgroundThread(run)) run.run();
    }

    /**
     * TikTok has copied a photo save's files to the gallery: {@code copied} holds a content Uri
     * for each one that landed. The first is recorded when the job's own post is a save
     * {@link #hold} accepted.
     */
    public static void copied(Object copied) {
        try {
            Field post = jobPost;
            forgetStale();
            if (PENDING.isEmpty() || post == null || !(copied instanceof Iterable<?>)) return;
            Uri landed = null;
            for (Object item : (Iterable<?>) copied) {
                if (item instanceof Uri) {
                    landed = (Uri) item;
                    break;
                }
            }
            String id = landed == null ? null : Reflect.string(post.get(null), "getAid", "aid");
            Pending pending = id == null ? null : PENDING.get(id);
            if (pending == null || !PENDING.remove(id, pending)) return;
            Context context = Utils.getContext();
            if (context == null) return;
            Context application = context.getApplicationContext();
            Context app = application == null ? context : application;
            Uri first = landed;
            Utils.runOnBackgroundThread(() -> {
                MediaFileWriter.Saved saved = saved(app, first);
                SavedVideoArchive.rememberQuietly(app, pending.id, saved, pending.generation);
                Logger.printInfo(() -> "Recorded TikTok's photo save of " + pending.id + " as " + saved.name);
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Could not record TikTok's photo save", failure);
        }
    }

    /** What the record keeps for one landed photo. */
    static MediaFileWriter.Saved saved(Context context, Uri landed) {
        return new MediaFileWriter.Saved(displayName(context, landed), landed, null);
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {
            // The name only labels the choice, so the Uri's own last part does.
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
    }

    /** Drops saves whose copy never came, so a later copy can't be taken for theirs. */
    private static void forgetStale() {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Pending> held : PENDING.entrySet()) {
            if (now - held.getValue().since > PENDING_MILLIS) PENDING.remove(held.getKey(), held.getValue());
        }
    }

    /** The job class's one static field holding a post, which the job sets as it starts. */
    static Field postField(Class<?> job, Object aweme) {
        if (aweme == null) return null;
        Field found = null;
        for (Field field : job.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() == Object.class
                    || field.getType().isPrimitive() || !field.getType().isInstance(aweme)) continue;
            if (found != null) return null;
            found = field;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    /**
     * The job's own static entry, which the hook sits on: (its class, String, String, ?, the post,
     * a Set, ?, boolean, ?, int). Null unless exactly one method has that shape.
     */
    static Method entry(Class<?> job, Object[] arguments) {
        if (arguments.length != 10) return null;
        Method found = null;
        for (Method method : job.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                    || types.length != 10 || types[0] != job || types[1] != String.class
                    || types[2] != String.class || types[5] != Set.class || types[7] != boolean.class
                    || types[9] != int.class || !types[4].isInstance(arguments[4])) continue;
            if (found != null) return null;
            found = method;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    /** Forgets every save waiting for its gallery copy. Tests only. */
    static void resetForTests() {
        PENDING.clear();
        jobPost = null;
        RERUNNING.remove();
    }

    static Pending pendingForTests(String id) {
        return PENDING.get(id);
    }
}
