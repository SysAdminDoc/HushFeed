/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saves the sound a video was made with, rather than the sound the video ends up making.
 *
 * <p>The two are different files. The video's own track is the finished mix: the music with
 * whatever was said over it, cut to the length of the post. The sound entry is the original,
 * whole, as it appears on its own page and in everyone else's videos made from it. Someone
 * saving "that song" wants the second one, and until now only the first was reachable.
 *
 * <p>It is published beside the video's own sound, under Music where Android files audio, and
 * named after the sound rather than the post.
 */
public final class OriginalSoundDownloads {
    /** How long a sound title may be before the filename cap has to deal with it. */
    private static final int MAX_TITLE_LENGTH = 80;

    private static final Set<String> ACTIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private OriginalSoundDownloads() {
    }

    /**
     * Where to fetch the original sound from, best mirror first, or empty when this post has no
     * sound entry. A video with only its own audio is the ordinary case, not a failure.
     */
    static List<String> sourceUrls(Object aweme) {
        Object music = Reflect.property(aweme, "getMusic", "music");
        if (music == null) return List.of();

        List<String> mirrors = new ArrayList<>();
        Object playUrl = Reflect.property(music, "getPlayUrl", "playUrl");
        addAddresses(mirrors, playUrl);
        Object bitrates = null;
        if (mirrors.isEmpty()) {
            // Some posts arrive with an empty play list and the sound only in its bitrates, which
            // is where TikTok's own sound page gets its address from.
            bitrates = Reflect.property(music, "getMultiBitRatePlayInfo", "multiBitRatePlayInfo");
            if (bitrates instanceof List<?>) {
                for (Object bitrate : (List<?>) bitrates) {
                    if (bitrate == null) continue;
                    int before = mirrors.size();
                    addTls(mirrors, Reflect.property(bitrate, "urlList", "urlList"));
                    if (mirrors.size() == before) addAddresses(mirrors, Reflect.property(bitrate, "getPlayAddr", "playAddr"));
                }
            }
        }
        Object reuse = null;
        if (mirrors.isEmpty()) {
            reuse = Reflect.property(music, "getReuseAudioPlayUrl", "reuseAudioPlayUrl");
            addAddresses(mirrors, reuse);
        }
        if (mirrors.isEmpty()) {
            boolean hasPlay = playUrl != null, hasBitrates = bitrates instanceof List<?> && !((List<?>) bitrates).isEmpty(), hasReuse = reuse != null;
            Logger.printDebug(() -> "Sound lists no address: play " + hasPlay + ", bitrates " + hasBitrates + ", reuse " + hasReuse);
        }
        return List.copyOf(mirrors);
    }

    /** Adds a sound address model's mirrors, its list or else its single uri. */
    private static void addAddresses(List<String> mirrors, Object model) {
        if (model == null) return;
        int before = mirrors.size();
        addTls(mirrors, Reflect.property(model, "getUrlList", "urlList"));
        if (mirrors.size() == before) {
            // Some builds carry only the single uri and no list at all.
            addTls(mirrors, Collections.singletonList(Reflect.string(model, "getUri", "uri")));
        }
    }

    /** Adds each address of [urls] not already listed, over TLS only. */
    private static void addTls(List<String> mirrors, Object urls) {
        if (!(urls instanceof List<?>)) return;
        for (Object url : (List<?>) urls) {
            if (url == null) continue;
            // Over TLS only, the way every other saver here reads its addresses. These come out of
            // a server response, and a cleartext one hands the fetch to whoever is on the network
            // between the phone and the host.
            String text = url.toString().trim();
            if (MediaTransport.hasAllowedShape(text) && !mirrors.contains(text)) mirrors.add(text);
        }
    }

    /**
     * What the saved file is called: the sound's own title, falling back to the post's name.
     * The extension is the one the fetched bytes turned out to carry.
     */
    static String fileName(Object aweme, String extension) {
        Object music = Reflect.property(aweme, "getMusic", "music");
        String title = music == null ? null : Reflect.string(music, "getTitle", "title");
        if (title == null || title.isEmpty()) {
            return DownloadFilenameFormatter.formatSelectedAudioName(aweme, extension);
        }
        if (title.length() > MAX_TITLE_LENGTH) title = title.substring(0, MAX_TITLE_LENGTH);
        return DownloadFilenameFormatter.formatSoundName(title, extension);
    }

    /** The type that goes with each container {@link RemoteMedia.Kind#AUDIO} will accept. */
    static String mimeFor(String extension) {
        switch (extension) {
            case "mp3":
                return "audio/mpeg";
            case "aac":
                return "audio/aac";
            case "ogg":
                return "audio/ogg";
            case "wav":
                return "audio/wav";
            case "flac":
                return "audio/flac";
            default:
                return "audio/mp4";
        }
    }

    /**
     * Fetches the sound and publishes it. Says why when it cannot, because a long press that
     * quietly does nothing reads as a broken button rather than as a video with no sound entry.
     */
    public static void start(Object aweme, Context context) {
        if (context == null) return;
        // Android 6 to 9 write a real file, so without the permission the save fails after the
        // fetch has already run and the reader is told only that it could not be saved. Asked
        // before anything is fetched, the way every other saver here asks it.
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("Allow storage for TikTok to save sounds"));
            return;
        }
        List<String> sources = sourceUrls(aweme);
        if (sources.isEmpty()) {
            Utils.showToastShort(L10n.t("This video has no original sound to save"));
            return;
        }

        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) return;
        queue(context.getApplicationContext(), aweme, sources, id, true);
    }

    /**
     * The name a sound goes by in the already-saved record: the sound's own id, so the same song
     * saved from a second video is caught too. Null for a sound entry with no id, which is saved
     * without a check. Prefixed, so it can never be taken for a video's id.
     */
    static String recordId(Object aweme) {
        Object music = Reflect.property(aweme, "getMusic", "music");
        String id = music == null ? null : SoundIdentity.idOf(music);
        return id == null ? null : "sound:" + id;
    }

    /**
     * Saves the sound, first offering Open or Save again for one saved before when the
     * already-saved check is on ({@code check}, false for Save again).
     */
    private static void queue(Context app, Object aweme, List<String> sources, String id, boolean check) {
        String key = "original sound " + id;
        if (!ACTIVE.add(id)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return;
        }
        String record = Settings.CHECK_SAVED_VIDEOS.get() ? recordId(aweme) : null;
        // Read as the save is accepted: a forget in settings while it runs leaves it unrecorded.
        long archiveGeneration = SavedVideoArchive.generation();
        AtomicBoolean asking = new AtomicBoolean();
        // With the check on, "Saving" waits for its answer: said at once, it would show just
        // before an already-saved choice for a sound that is never fetched (#356).
        boolean checking = record != null && check;
        String saying = L10n.t("Saving the sound");
        MediaJobScheduler.Job job = MediaJobScheduler.submit("original-sound", key, () -> {
            if (checking) {
                Runnable saveAgain = () -> {
                    ACTIVE.remove(id);
                    queue(app, aweme, sources, id, false);
                };
                asking.set(true);
                SavedVideoArchive.Check found = SavedVideoArchive.check(app, record,
                        L10n.t("This sound is already saved"), saveAgain, () -> ACTIVE.remove(id));
                if (found == SavedVideoArchive.Check.OFFERED) return;
                asking.set(false);
                if (found == SavedVideoArchive.Check.FAILED) return;
                Utils.showToastShort(saying);
            }
            File fetched = null, encoded = null;
            try {
                fetched = MediaCache.createTempFile(app, "original-sound-", ".tmp");
                // A sound entry is not always an MP4 audio track, so the name and the type both
                // wait for the header instead of assuming one. A file called .m4a that holds
                // MPEG frames is one the gallery refuses to play.
                String extension = RemoteMedia.fetch(sources, fetched, RemoteMedia.Kind.AUDIO);
                File publish = fetched;
                // An Ogg that is Opus already goes out as it came, with no second encode.
                if (SoundFormat.opus() && !OpusTranscoder.isOpus(fetched)) {
                    encoded = MediaCache.createTempFile(app, "original-sound-", ".ogg");
                    if (OpusTranscoder.transcodeOrKeep(fetched, encoded)) {
                        publish = encoded;
                        extension = "ogg";
                    }
                } else if (SoundFormat.mp3()) {
                    // Frames TikTok already sent as MP3 go out as they came, with no second encode.
                    if (Mp3Transcoder.isMp3(fetched)) {
                        extension = Mp3Transcoder.EXTENSION;
                    } else {
                        encoded = MediaCache.createTempFile(app, "original-sound-", ".mp3");
                        if (Mp3Transcoder.transcodeOrKeep(fetched, encoded)) {
                            publish = encoded;
                            extension = Mp3Transcoder.EXTENSION;
                        }
                    }
                }
                String name = fileName(aweme, extension);
                String path = AudioDownloads.audioPath(DownloadsPatch.getVideoDownloadPath());
                MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, publish, name, mimeFor(extension), path, true);
                if (record != null) SavedVideoArchive.rememberQuietly(app, record, saved, archiveGeneration);
                SaveNotice.saved(L10n.f("Sound saved to %1$s", path), saved);
            } catch (IOException | RuntimeException exception) {
                Logger.printException(() -> "Original sound download failed", exception);
                Utils.showToastLong(L10n.t("The sound couldn't be saved. Try again."));
            } finally {
                for (File temporary : new File[]{fetched, encoded}) {
                    if (temporary != null && !MediaCache.delete(temporary)) {
                        Logger.printInfo(() -> "Could not remove sound temporary file");
                    }
                }
            }
        }, () -> {
            if (!asking.get()) ACTIVE.remove(id);
        });
        MediaJobScheduler.acknowledge(job, checking ? null : saying, saying);
    }
}
