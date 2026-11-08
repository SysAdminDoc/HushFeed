/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A copy of a video for Make sticker (#214), kept in the media cache while TikTok's sticker editor
 * reads it. The clean file comes first, then the one that plays, then TikTok's stamped one, so a
 * sticker only carries the watermark when nothing else can be fetched.
 */
public final class StickerClip {
    private static final String[][] ADDRESSES = {
            {"getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr"},
            {"getPlayAddr", "playAddr"},
            {"getDownloadAddr", "downloadAddr"},
    };

    private StickerClip() {}

    /** Every address the clip can come from, best first, or empty for a photo post or a video with none. */
    static List<String> urls(Object aweme) {
        if (aweme == null || Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo") != null) {
            return List.of();
        }
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video == null) return List.of();
        List<String> urls = new ArrayList<>();
        for (String[] field : ADDRESSES) {
            for (String url : VideoDownloads.urls(Reflect.property(video, field[0], field[1]))) {
                if (!urls.contains(url)) urls.add(url);
            }
        }
        return urls;
    }

    /** Fetches the video into a new cache file, or null when it has no address. The caller drops it. */
    public static File fetch(Context context, Object aweme) throws IOException {
        List<String> urls = urls(aweme);
        if (urls.isEmpty()) return null;
        File clip = MediaCache.createTempFile(context, "sticker-clip-", ".mp4");
        try {
            RemoteMedia.fetch(urls, clip, RemoteMedia.Kind.VIDEO);
            return clip;
        } catch (IOException | RuntimeException error) {
            MediaCache.delete(clip);
            throw error;
        }
    }

    /** Hands a clip back to the cache sweep once the editor is done with it. */
    public static void drop(File clip) {
        if (clip != null) MediaCache.delete(clip);
    }
}
