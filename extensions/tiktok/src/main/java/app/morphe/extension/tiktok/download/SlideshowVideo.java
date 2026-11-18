/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A photo post saved as one MP4: each photo held on screen for the chosen time, the post's sound
 * under it, and nothing of TikTok's added. TikTok's own "Download video" on a one-photo post
 * builds that video on the phone and stamps its logo, the creator's handle and an end card on
 * it, and a post with several photos has no video choice at all.
 *
 * <p>Two ways in, both from saves Advanced downloads already hooks. "Download video" on a
 * one-photo post is the conversion entry, and with the switch on it comes here instead of to
 * TikTok's renderer. The photo picker's Download on a post with several photos is the photo save
 * job, and with the switch on it asks whether the picked photos go out as photos or as a video.
 *
 * <p>The video is drawn in {@link SlideshowEncoder}. Everything decided along the way is a static
 * method here, so a test can reach it without a codec.
 */
public final class SlideshowVideo {
    /** Frames a second. A still doesn't need more, and a 35-photo post has to fit the job's time. */
    static final int FPS = 15;
    /** The longest the row lets a photo stay on screen (#465). */
    static final int MAX_SECONDS_PER_PHOTO = 300;
    /** A photo that would take more frames than this gets a lower rate, which a still never shows. */
    private static final int FRAMES_PER_PHOTO = 150;
    private static final int MIN_FPS = 2;
    /** Sound is encoded at least this many times faster than it plays. */
    private static final long SOUND_SPEED = 5;
    static final int KEYFRAME_SECONDS = 1;
    /** The frame a portrait photo fills, as long and short sides. Landscape turns it round. */
    static final int LONG_SIDE = 1920;
    static final int SHORT_SIDE = 1080;
    /** Smaller frames for an encoder that turns the full size down. */
    private static final int[][] FALLBACK_SIDES = {{1280, 720}, {960, 540}};
    /** The shortest a pass of a repeated sound counts as. */
    static final long MIN_LOOP_US = 100_000L;
    /** TikTok's own defaults for a story with no video of its own (47.6.3). */
    private static final int STORY_SECONDS_WITH_SOUND = 10, STORY_SECONDS_SILENT = 5;

    private static final Set<String> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private SlideshowVideo() {}

    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.DOWNLOAD_PHOTOS_AS_VIDEO.get();
    }

    /**
     * The picker's save on a post with several photos. Asks between the picked photos and one
     * video of them, and answers whether it took the save. A post with one photo, the switch off
     * or no screen to ask on leaves the save where it was going.
     */
    static boolean offer(Object aweme, Set<?> indices) {
        return offer(aweme, indices, seconds(Settings.PHOTO_VIDEO_SECONDS.get()), StoryShareSave.isStory(aweme));
    }

    /** {@code story} names it a story, not a photo post, when it turns out to be saved already. */
    private static boolean offer(Object aweme, Set<?> indices, int seconds, boolean story) {
        if (!enabled()) return false;
        Object info = Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo");
        Object raw = Reflect.property(info, "getImageList", "imageList");
        if (!(raw instanceof List<?>) || ((List<?>) raw).size() < 2) return false;
        Activity activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        Set<Object> picked = indices == null ? null : new HashSet<Object>(indices);
        Utils.runOnMainThread(() -> ask(aweme, picked, seconds, story));
        return true;
    }

    private static void ask(Object aweme, Set<?> picked, int seconds, boolean story) {
        try {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                throw new IllegalStateException("No screen to ask on");
            }
            SettingsUi.syncDarkMode(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Save photos or a video?"))
                    .setNegativeButton(L10n.t("Save photos"), (ignored, which) -> OriginalPhotos.savePicked(aweme, picked, story))
                    .setPositiveButton(L10n.t("Save as video"), (ignored, which) -> start(aweme, picked, true, seconds, story))
                    .setNeutralButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (RuntimeException failure) {
            // Download was pressed, so the photos still go out rather than nothing.
            Logger.printException(() -> "Could not ask between photos and a video", failure);
            OriginalPhotos.savePicked(aweme, picked, story);
        }
    }

    /**
     * Queues the video of the photos at {@code indices}, all of them for null. Always takes the
     * save: TikTok's own path from here is a watermarked render, and its callback wants that
     * video's path, so a failure says so itself instead of falling back.
     */
    static boolean start(Object aweme, Set<?> indices) {
        return start(aweme, indices, true, seconds(Settings.PHOTO_VIDEO_SECONDS.get()), false);
    }

    /**
     * A photo story as one video with its sound, as long as TikTok shows it: the row's few seconds
     * would cut the sound short (#327). Several photos ask first, as a post does.
     */
    static boolean saveStory(Object aweme) {
        int seconds = storySeconds(aweme);
        return offer(aweme, null, seconds, true) || start(aweme, null, true, seconds, true);
    }

    /** How long TikTok keeps a story with no video of its own on screen, on its default settings. */
    static int storySeconds(Object aweme) {
        return soundSources(aweme).isEmpty() ? STORY_SECONDS_SILENT : STORY_SECONDS_WITH_SOUND;
    }

    /**
     * With Check for already-saved videos on, a post saved here before is offered Open or Save
     * again first ({@code check}, false for Save again), and the video that's made is recorded
     * under the post's id. {@code story} titles that choice for a story.
     */
    private static boolean start(Object aweme, Set<?> indices, boolean check, int seconds, boolean story) {
        Context context = Utils.getContext();
        if (context == null) return failed();
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return failed();
        List<List<String>> photos = OriginalPhotos.sources(aweme);
        if (photos.isEmpty()) {
            Utils.showToastShort(L10n.t("No video saved. The original photos are missing."));
            return true;
        }
        List<Integer> chosen = OriginalPhotos.positions(indices, photos.size());
        String id = Reflect.string(aweme, "getAid", "aid");
        if (chosen.isEmpty() || id == null) return failed();
        List<List<String>> sources = new ArrayList<>();
        for (int position : chosen) sources.add(new ArrayList<>(photos.get(position)));
        List<String> sound = soundSources(aweme);
        String name, path;
        try {
            name = DownloadFilenameFormatter.formatSelectedVideoName(aweme);
            path = DownloadFilenameFormatter.destinationPath(aweme, false);
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not name the photo video", exception);
            return failed();
        }
        String key = "photo video " + id;
        if (!ACTIVE.add(id)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return true;
        }
        Context app = context.getApplicationContext();
        SaveProgress progress = SaveProgress.queued(1, Settings.DOWNLOAD_PROGRESS.get());
        boolean checkSaved = Settings.CHECK_SAVED_VIDEOS.get();
        // Read as the save is accepted: a forget in settings while it runs leaves it unrecorded.
        long archiveGeneration = SavedVideoArchive.generation();
        AtomicBoolean asking = new AtomicBoolean();
        // With the check on, "Making" waits for its answer, so it never shows just before an
        // already-saved choice for a video that is never made (#356).
        boolean checking = checkSaved && check;
        String making = L10n.t("Making a video of the photos");
        MediaJobScheduler.Job job = progress.submit("photo video", key, () -> {
            MediaFileWriter.Saved[] saved = {null};
            boolean[] silent = {false};
            try {
                if (checking) {
                    Runnable saveAgain = () -> {
                        ACTIVE.remove(id);
                        start(aweme, indices, false, seconds, story);
                    };
                    asking.set(true);
                    SavedVideoArchive.Check found = SavedVideoArchive.check(app, id,
                            SavedVideoArchive.photoTitle(story), saveAgain, () -> ACTIVE.remove(id));
                    if (found == SavedVideoArchive.Check.OFFERED) {
                        progress.dismiss();
                        return;
                    }
                    asking.set(false);
                    if (found == SavedVideoArchive.Check.FAILED) return;
                    Utils.showToastShort(making);
                }
                SaveProgress.Outcome outcome = progress.run(index -> saved[0] = make(app, sources,
                        Collections.singletonList(new Sound(sound, sources.size())), seconds, name, path, progress, silent));
                if (saved[0] == null) {
                    Utils.showToastLong(outcome.stop == SaveProgress.Stop.SERVER_WAIT
                            ? SaveProgress.message(outcome, "")
                            : outcome.stop == SaveProgress.Stop.NETWORK
                            ? L10n.t("Save stopped when Wi-Fi dropped. Retry on Wi-Fi.")
                            : L10n.t("Couldn't make the video. Try again."));
                } else {
                    if (checkSaved) SavedVideoArchive.rememberQuietly(app, id, saved[0], archiveGeneration);
                    SaveNotice.savedVideo(silent[0]
                            ? L10n.f("Saved to %1$s, but without sound", path)
                            : L10n.f("Saved the photos as a video in %1$s", path), saved[0]);
                }
            } catch (RuntimeException exception) {
                Logger.printException(() -> "Photo video failed", exception);
                Utils.showToastLong(L10n.t("Couldn't make the video. Try again."));
            }
        }, () -> {
            if (!asking.get()) ACTIVE.remove(id);
        });
        if (job == null) return true;
        progress.acknowledge(checking ? null : making, L10n.t("Waiting to save video"));
        return true;
    }

    /** For the tests: stands in for drawing, muxing and publishing the video. */
    interface Maker {
        MediaFileWriter.Saved make(String name, int seconds) throws IOException;
    }

    static volatile Maker makerForTests;

    /**
     * The video of all of a post's photos, made now on the media worker that's calling, for a run
     * of picked posts (#197). It is the single save without its queue slot, its progress row and
     * its banner: the run has its own.
     */
    static MediaFileWriter.Saved makeForRun(Context app, Object aweme) throws IOException {
        List<List<String>> photos = OriginalPhotos.sources(aweme);
        if (photos.isEmpty()) throw new IOException("The original photos aren't available");
        String name = DownloadFilenameFormatter.formatSelectedVideoName(aweme);
        String path = DownloadFilenameFormatter.destinationPath(aweme, false);
        return make(app, photos, Collections.singletonList(new Sound(soundSources(aweme), photos.size())),
                seconds(Settings.PHOTO_VIDEO_SECONDS.get()), name, path, SaveProgress.begin(1), new boolean[]{false});
    }

    /**
     * One video of several picked photo posts (#430), made now on the media worker that's calling,
     * for a run of picked posts. Each post's photos follow the one before's, with that post's own
     * sound under them, and the video is named and filed the way its first post's would be.
     */
    static MediaFileWriter.Saved makeJoined(Context app, List<?> posts) throws IOException {
        Plan plan = plan(posts);
        if (plan.first == null) throw new IOException("The original photos aren't available");
        String name = DownloadFilenameFormatter.formatSelectedVideoName(plan.first);
        String path = DownloadFilenameFormatter.destinationPath(plan.first, false);
        return make(app, plan.photos, plan.sounds, seconds(Settings.PHOTO_VIDEO_SECONDS.get()), name, path,
                SaveProgress.begin(1), new boolean[]{false});
    }

    /** One post's run of photos in a video, and where the sound that goes under them comes from. */
    static final class Sound {
        final List<String> sources;
        final int photos;

        Sound(List<String> sources, int photos) {
            this.sources = sources;
            this.photos = photos;
        }
    }

    /** What a video of several photo posts is made from: every photo in pick order, and each post's sound. */
    static final class Plan {
        final List<List<String>> photos = new ArrayList<>();
        final List<Sound> sounds = new ArrayList<>();
        /** The first post with photos, which names the video. Null when none has any. */
        Object first;
    }

    /** {@code posts} in the order given, leaving out a post whose photos aren't available. */
    static Plan plan(List<?> posts) {
        Plan plan = new Plan();
        for (Object post : posts) {
            List<List<String>> photos = OriginalPhotos.sources(post);
            if (photos.isEmpty()) continue;
            if (plan.first == null) plan.first = post;
            plan.photos.addAll(photos);
            plan.sounds.add(new Sound(soundSources(post), photos.size()));
        }
        return plan;
    }

    /** Whether a save of this post's video holds it. */
    static boolean saving(String id) {
        return ACTIVE.contains(id);
    }

    private static boolean failed() {
        Utils.showToastLong(L10n.t("Couldn't make the video. Try again."));
        return true;
    }

    /**
     * Draws the video, adds the sound when it can and publishes the one file. Nothing reaches the
     * gallery until the MP4 is finished, so a failure anywhere leaves only temporary files, and
     * those go here.
     */
    private static MediaFileWriter.Saved make(Context app, List<List<String>> photos, List<Sound> sounds,
            int seconds, String name, String path, SaveProgress progress, boolean[] silent) throws IOException {
        Maker stand = makerForTests;
        if (stand != null) return stand.make(name, seconds);
        List<File> temporary = new ArrayList<>();
        try {
            MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
            File video = temp(app, temporary, ".mp4");
            Set<Integer> postStarts = new HashSet<>();
            int at = 0;
            for (Sound sound : sounds) {
                postStarts.add(at);
                at += sound.photos;
            }
            drawPhotos(app, photos, seconds, video, progress, postStarts);
            File result = video;
            try {
                File track = soundTrack(app, sounds, seconds, temporary);
                File muxed = temp(app, temporary, ".mp4");
                TrackMuxer.combine(video, track, muxed);
                result = muxed;
            } catch (MediaBudget.StopException stop) {
                throw stop;
            } catch (IOException | RuntimeException noSound) {
                Logger.printException(() -> "The photo video goes out without its sound", noSound);
                silent[0] = true;
            }
            return MediaFileWriter.publishForResult(app, result, name, "video/mp4", path, true);
        } finally {
            for (File file : temporary) {
                if (!MediaCache.delete(file)) Logger.printInfo(() -> "Could not remove a photo video temporary file");
            }
        }
    }

    /**
     * The sound under the video. One post's runs the whole video, from the top again when it's
     * shorter. Several posts' (#430) each run under that post's photos, and when they can't share
     * one track (one doesn't load, or two differ in format) the first post's sound that loads
     * runs under all of it.
     */
    private static File soundTrack(Context app, List<Sound> sounds, int seconds, List<File> temporary)
            throws IOException {
        long total = 0;
        for (Sound sound : sounds) total += durationUs(sound.photos, seconds);
        if (sounds.size() > 1) {
            try {
                return joinedSound(app, sounds, seconds, temporary);
            } catch (MediaBudget.StopException stop) {
                throw stop;
            } catch (IOException | RuntimeException mixed) {
                Logger.printException(() -> "The joined photo video takes one post's sound for all of it", mixed);
            }
        }
        IOException failure = new IOException("No post lists a sound that loads");
        for (Sound sound : sounds) {
            if (sound.sources.isEmpty()) continue;
            try {
                return cutSound(app, sound.sources, total, temporary);
            } catch (MediaBudget.StopException stop) {
                throw stop;
            } catch (IOException | RuntimeException skipped) {
                failure.addSuppressed(skipped);
            }
        }
        throw failure;
    }

    /** Each post's sound cut to its own photos and the cuts laid end to end in one track. */
    private static File joinedSound(Context app, List<Sound> sounds, int seconds, List<File> temporary)
            throws IOException {
        List<File> parts = new ArrayList<>();
        long[] starts = new long[sounds.size()];
        long at = 0;
        for (int i = 0; i < sounds.size(); i++) {
            Sound sound = sounds.get(i);
            if (sound.sources.isEmpty()) throw new IOException("Post " + (i + 1) + " lists no sound");
            // Each post's sound is its own fetch and encode, so each gets the time one save does.
            MediaBudget.deadline().allowAtLeast(MediaBudget.JOB_DEADLINE_MS);
            long length = durationUs(sound.photos, seconds);
            parts.add(cutSound(app, sound.sources, length, temporary));
            starts[i] = at;
            at += length;
        }
        File joined = temp(app, temporary, ".m4a");
        TrackMuxer.joinAudio(parts, starts, joined);
        return joined;
    }

    private static File cutSound(Context app, List<String> sources, long lengthUs, List<File> temporary)
            throws IOException {
        // A long video's sound takes longer to encode than the time a save starts with.
        MediaBudget.deadline().allowAtLeast(soundWorkMs(lengthUs));
        File fetched = temp(app, temporary, ".tmp");
        RemoteMedia.fetch(sources, fetched, RemoteMedia.Kind.AUDIO);
        File track = temp(app, temporary, ".m4a");
        SlideshowEncoder.soundTrack(fetched, track, lengthUs);
        return track;
    }

    private static File temp(Context app, List<File> temporary, String suffix) throws IOException {
        File file = MediaCache.createTempFile(app, "photo-video-", suffix);
        temporary.add(file);
        return file;
    }

    /**
     * One photo at a time, each fetched, drawn into the frame and let go before the next.
     * {@code postStarts} holds where each post's photos begin. With several posts, each one gets
     * the time a single save gets, and Cancel on the run stops the video under way, since one
     * video of many posts can take as long as all of their saves would.
     */
    private static void drawPhotos(Context app, List<List<String>> photos, int seconds, File output,
            SaveProgress progress, Set<Integer> postStarts) throws IOException {
        int perPhoto = framesPerPhoto(seconds);
        int fps = frameRate(seconds);
        long total = (long) perPhoto * photos.size();
        boolean joined = postStarts.size() > 1;
        SlideshowEncoder encoder = null;
        Bitmap frame = null;
        try {
            Canvas canvas = null;
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            for (int i = 0; i < photos.size(); i++) {
                if (progress.isCancelled() || (joined && MediaBudget.cancelled())) {
                    throw new MediaBudget.StopException("Photo video cancelled", MediaBudget.StopException.Reason.CANCELLED);
                }
                // Photos held for a long time each get the time a save gets, as each post does.
                if (i > 0 && (fps < FPS || (joined && postStarts.contains(i)))) {
                    MediaBudget.deadline().allowAtLeast(MediaBudget.JOB_DEADLINE_MS);
                }
                Bitmap photo = fetchPhoto(app, photos.get(i), encoder == null ? null : new int[]{encoder.width, encoder.height}, i + 1);
                try {
                    if (encoder == null) {
                        encoder = SlideshowEncoder.open(output, frameSizes(photo.getWidth(), photo.getHeight()), fps);
                        frame = Bitmap.createBitmap(encoder.width, encoder.height, Bitmap.Config.ARGB_8888);
                        canvas = new Canvas(frame);
                    }
                    canvas.drawColor(Color.BLACK);
                    canvas.drawBitmap(photo, null, fit(photo.getWidth(), photo.getHeight(), frame.getWidth(), frame.getHeight()), paint);
                } finally {
                    photo.recycle();
                }
                encoder.add(frame, perPhoto, drawn -> progress.transfer(drawn, total));
            }
            if (encoder == null) throw new IOException("The post had no photos to draw");
            encoder.finish();
        } finally {
            if (encoder != null) encoder.close();
            if (frame != null) frame.recycle();
        }
    }

    /**
     * Fetches one photo and decodes it small enough for the frame, trying each copy TikTok lists
     * in turn: a phone that can't read the HEIF copy can still read the WebP one. {@code frame}
     * is null for the first photo, which the frame is then sized from.
     */
    private static Bitmap fetchPhoto(Context app, List<String> urls, int[] frame, int position) throws IOException {
        IOException failure = new IOException("No copy of photo " + position + " could be drawn");
        for (String url : urls) {
            File file = MediaCache.createTempFile(app, "photo-video-", ".tmp");
            try {
                RemoteMedia.fetch(Collections.singletonList(url), file, RemoteMedia.Kind.IMAGE);
                Bitmap bitmap = decode(file, frame);
                if (bitmap != null) return bitmap;
                failure.addSuppressed(new IOException("Photo " + position + " didn't decode from "
                        + RemoteMedia.summarizeUrl(url)));
            } catch (MediaBudget.StopException stop) {
                throw stop;
            } catch (IOException | RuntimeException | OutOfMemoryError skipped) {
                failure.addSuppressed(skipped);
            } finally {
                if (!MediaCache.delete(file)) Logger.printInfo(() -> "Could not remove a photo video temporary file");
            }
        }
        throw failure;
    }

    /**
     * Decodes {@code file} at a power-of-two fraction of its size that still covers the frame,
     * so a 4,000 pixel photo never sits in memory whole. Null when this phone can't read it.
     */
    static Bitmap decode(File file, int[] frame) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int[] target = frame != null ? frame : frameSize(bounds.outWidth, bounds.outHeight, LONG_SIDE, SHORT_SIDE);
        Rect fitted = fit(bounds.outWidth, bounds.outHeight, target[0], target[1]);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, fitted.width(), fitted.height());
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    /** Where the post's sound can be fetched: its sound entry, then the addresses its video lists. */
    static List<String> soundSources(Object aweme) {
        List<String> sources = new ArrayList<>(OriginalSoundDownloads.sourceUrls(aweme));
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video != null) {
            for (String url : VideoDownloads.sourceUrls(video)) {
                if (MediaTransport.hasAllowedShape(url) && !sources.contains(url)) sources.add(url);
            }
        }
        return sources;
    }

    /** The setting, held to what the row offers, in case a restored backup carried something else. */
    static int seconds(int setting) {
        return Math.max(1, Math.min(MAX_SECONDS_PER_PHOTO, setting));
    }

    /**
     * The video's frame rate. Up to ten seconds a photo it's {@link #FPS}. Past that it drops so a
     * photo costs about the same to draw however long it stays, since nothing moves in a still.
     */
    static int frameRate(int seconds) {
        return Math.max(MIN_FPS, Math.min(FPS, FRAMES_PER_PHOTO / Math.max(1, seconds)));
    }

    static int framesPerPhoto(int seconds) {
        return seconds * frameRate(seconds);
    }

    /** The time to leave for fetching and encoding a sound of this length. */
    static long soundWorkMs(long lengthUs) {
        return lengthUs / 1000L / SOUND_SPEED;
    }

    /** When frame {@code index} shows, counted from zero, in nanoseconds. */
    static long presentationTimeNs(long index, int fps) {
        return index * 1_000_000_000L / fps;
    }

    /** How long the video runs, which is where the sound is cut. */
    static long durationUs(int photos, int seconds) {
        return (long) photos * seconds * 1_000_000L;
    }

    /** Samples, per channel, that fit in {@code limitUs} at {@code sampleRate}. */
    static long trimFrames(long limitUs, int sampleRate) {
        return limitUs * sampleRate / 1_000_000L;
    }

    /**
     * How far one pass of a sound that's played again reaches: its last sample's time plus the
     * gap before it, so the next pass starts a sample later. A pass never counts as shorter than
     * {@link #MIN_LOOP_US}, which keeps a broken file's timestamps from repeating it forever.
     */
    static long loopLengthUs(long lastSampleUs, long sampleGapUs) {
        return Math.max(MIN_LOOP_US, Math.max(0, lastSampleUs) + Math.max(0, sampleGapUs));
    }

    /**
     * How many PCM bytes go into one encoder buffer: what's left of the decoded buffer, what the
     * encoder buffer holds and what's left before the cut, whichever is least, in whole samples.
     */
    static int pcmBytes(int available, int room, long framesLeft, int frameBytes) {
        if (frameBytes <= 0 || framesLeft <= 0) return 0;
        long bytes = Math.min(Math.min(available, room), framesLeft * frameBytes);
        return (int) (bytes - bytes % frameBytes);
    }

    static int bitRate(int width, int height) {
        long proposed = (long) width * height * 2L;
        return (int) Math.max(1_000_000L, Math.min(6_000_000L, proposed));
    }

    static int audioBitRate(int channels) {
        return channels == 1 ? 96_000 : 128_000;
    }

    /** The frame sizes to try, full size first, all shaped like the first photo. */
    static List<int[]> frameSizes(int width, int height) {
        List<int[]> sizes = new ArrayList<>();
        sizes.add(frameSize(width, height, LONG_SIDE, SHORT_SIDE));
        for (int[] sides : FALLBACK_SIDES) {
            int[] size = frameSize(width, height, sides[0], sides[1]);
            int[] last = sizes.get(sizes.size() - 1);
            if (size[0] != last[0] || size[1] != last[1]) sizes.add(size);
        }
        return sizes;
    }

    /**
     * The largest frame inside the long by short box with the photo's shape, both sides a
     * multiple of 16, which every H.264 encoder takes. A shape past 9:16 or 16:9 is held to that,
     * so a panorama doesn't make a sliver of a video; it gets bars instead.
     */
    static int[] frameSize(int width, int height, int longSide, int shortSide) {
        boolean landscape = width > height;
        int boxWidth = landscape ? longSide : shortSide;
        int boxHeight = landscape ? shortSide : longSide;
        double narrowest = (double) shortSide / longSide;
        double aspect = width > 0 && height > 0 ? (double) width / height : narrowest;
        aspect = Math.max(narrowest, Math.min(1 / narrowest, aspect));
        double frameWidth, frameHeight;
        if (aspect >= (double) boxWidth / boxHeight) {
            frameWidth = boxWidth;
            frameHeight = boxWidth / aspect;
        } else {
            frameHeight = boxHeight;
            frameWidth = boxHeight * aspect;
        }
        return new int[]{multipleOf16(frameWidth), multipleOf16(frameHeight)};
    }

    private static int multipleOf16(double side) {
        return Math.max(16, ((int) Math.floor(side + 1e-6)) / 16 * 16);
    }

    /** Where a photo sits in the frame: as large as fits, centered, black bars around the rest. */
    static Rect fit(int width, int height, int frameWidth, int frameHeight) {
        if (width <= 0 || height <= 0) return new Rect(0, 0, frameWidth, frameHeight);
        double scale = Math.min((double) frameWidth / width, (double) frameHeight / height);
        int fittedWidth = Math.max(1, Math.min(frameWidth, (int) Math.round(width * scale)));
        int fittedHeight = Math.max(1, Math.min(frameHeight, (int) Math.round(height * scale)));
        int left = (frameWidth - fittedWidth) / 2;
        int top = (frameHeight - fittedHeight) / 2;
        return new Rect(left, top, left + fittedWidth, top + fittedHeight);
    }

    /** The largest power of two that still leaves the decoded photo at least the fitted size. */
    static int sampleSize(int width, int height, int fittedWidth, int fittedHeight) {
        int sample = 1;
        if (fittedWidth <= 0 || fittedHeight <= 0) return sample;
        while (sample < 64 && width / (sample * 2L) >= fittedWidth && height / (sample * 2L) >= fittedHeight) {
            sample *= 2;
        }
        return sample;
    }
}
