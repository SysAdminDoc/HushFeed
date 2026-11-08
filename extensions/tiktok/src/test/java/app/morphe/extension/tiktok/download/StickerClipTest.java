/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

/** The copy of a video Make sticker hands TikTok's editor when the creator turned stickers off (#214). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class StickerClipTest {
    @Test public void theCleanFileComesFirstThenTheOneThatPlaysThenTheStampedOne() {
        Post post = new Post();
        post.video.downloadAddr = new CoverSaverTest.UrlModel(0, 0, "https://v16.example/stamped.mp4");
        post.video.playAddr = new CoverSaverTest.UrlModel(0, 0, "https://v16.example/play.mp4", "https://v19.example/play.mp4");
        assertEquals(List.of("https://v16.example/play.mp4", "https://v19.example/play.mp4",
                "https://v16.example/stamped.mp4"), StickerClip.urls(post));

        post.video.downloadNoWatermarkAddr = new CoverSaverTest.UrlModel(0, 0,
                "https://v16.example/clean.mp4", "https://v16.example/play.mp4");
        assertEquals("an address two of them share is tried once", List.of("https://v16.example/clean.mp4",
                "https://v16.example/play.mp4", "https://v19.example/play.mp4", "https://v16.example/stamped.mp4"),
                StickerClip.urls(post));
    }

    @Test public void aPhotoPostOrAVideoWithNoAddressHasNoClip() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Post bare = new Post();
        assertTrue(StickerClip.urls(bare).isEmpty());
        assertNull(StickerClip.fetch(context, bare));
        File cache = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME);
        String[] left = cache.list();
        assertTrue("no file is made for nothing", left == null || left.length == 0);

        Post photos = new Post();
        photos.video.playAddr = new CoverSaverTest.UrlModel(0, 0, "https://v16.example/play.mp4");
        photos.photoModeImageInfo = new Object();
        assertTrue(StickerClip.urls(photos).isEmpty());
        assertTrue(StickerClip.urls(null).isEmpty());
        StickerClip.drop(null);
    }

    public static final class Video {
        public CoverSaverTest.UrlModel downloadNoWatermarkAddr, playAddr, downloadAddr;
        public CoverSaverTest.UrlModel getDownloadNoWatermarkAddr() { return downloadNoWatermarkAddr; }
        public CoverSaverTest.UrlModel getPlayAddr() { return playAddr; }
        public CoverSaverTest.UrlModel getDownloadAddr() { return downloadAddr; }
    }

    public static final class Post {
        public final Video video = new Video();
        public Object photoModeImageInfo;
        public Video getVideo() { return video; }
        public Object getPhotoModeImageInfo() { return photoModeImageInfo; }
    }
}
