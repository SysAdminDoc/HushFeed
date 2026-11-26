/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.os.Environment;
import android.os.Looper;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * A subscriber-only video saved as a file that played black with no sound (#544). A download
 * that comes down encrypted or as no video at all never reaches the gallery. Every save path that
 * publishes a fetched video is covered: the single save, a story's own save and a run of picked
 * videos. An ordinary save still lands byte for byte.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UnplayableSaveTest {
    private static final String VIDEO_URL = "https://v16.tiktokcdn.com/video/one";
    private static final String OTHER_URL = "https://v16.tiktokcdn.com/video/two";
    private static final String COPY_PROTECTED = "Copy-protected videos can't be saved";
    private static final String NOT_A_VIDEO = "Not saved. The file wasn't a playable video.";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final Set<String> ownIds = new HashSet<>();
    private final List<File> published = new ArrayList<>();
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, byte[]> served = Collections.synchronizedMap(new LinkedHashMap<>());
    private File storage;
    private Set<String> before;
    private String previousQuality;
    private boolean previousMute, previousDetails, previousTags, previousBurn, previousCheck,
            previousProgress, previousFallback, previousAdvanced;

    @Before public void setUp() throws Exception {
        drainPool();
        previousQuality = Settings.DOWNLOAD_VIDEO_QUALITY.savedValue();
        previousMute = Settings.DOWNLOAD_WITHOUT_SOUND.savedValue();
        previousDetails = Settings.DOWNLOAD_DETAILS.savedValue();
        previousTags = Settings.DOWNLOAD_TAGS.savedValue();
        previousBurn = Settings.DOWNLOAD_BURN_CAPTION.savedValue();
        previousCheck = Settings.CHECK_SAVED_VIDEOS.savedValue();
        previousProgress = Settings.DOWNLOAD_PROGRESS.savedValue();
        previousFallback = Settings.DOWNLOAD_WATERMARK_FALLBACK.savedValue();
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Settings.DOWNLOAD_VIDEO_QUALITY.save("highest");
        Settings.DOWNLOAD_WITHOUT_SOUND.save(false);
        Settings.DOWNLOAD_DETAILS.save(false);
        Settings.DOWNLOAD_TAGS.save(false);
        Settings.DOWNLOAD_BURN_CAPTION.save(false);
        Settings.CHECK_SAVED_VIDEOS.save(false);
        Settings.DOWNLOAD_PROGRESS.save(false);
        Settings.DOWNLOAD_WATERMARK_FALLBACK.save(false);
        // No picker, no sound file beside the video and no cover: the video is the whole save.
        SettingsStatus.advancedDownloadsEnabled = false;
        RemoteMedia.transportForTests = MediaTransportFixtures.publicClient(url -> {
            asked.add(url.toString());
            byte[] body = served.get(url.toString());
            return MediaTransportFixtures.response(url, body != null ? 200 : 404, body != null ? body : new byte[0]);
        });
        storage = Environment.getExternalStorageDirectory();
        before = listed(storage);
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            drainPool();
        } finally {
            RemoteMedia.transportForTests = null;
            for (File file : published) file.delete();
            videoIds().removeAll(ownIds);
            Settings.DOWNLOAD_VIDEO_QUALITY.save(previousQuality);
            Settings.DOWNLOAD_WITHOUT_SOUND.save(previousMute);
            Settings.DOWNLOAD_DETAILS.save(previousDetails);
            Settings.DOWNLOAD_TAGS.save(previousTags);
            Settings.DOWNLOAD_BURN_CAPTION.save(previousBurn);
            Settings.CHECK_SAVED_VIDEOS.save(previousCheck);
            Settings.DOWNLOAD_PROGRESS.save(previousProgress);
            Settings.DOWNLOAD_WATERMARK_FALLBACK.save(previousFallback);
            SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
        }
    }

    @Test public void anOrdinaryVideoIsStillSavedAsItCame() throws Exception {
        byte[] movie = Mp4Fixture.playable();
        served.put(VIDEO_URL, movie);
        PhotoConversionTest.VideoPost post = post("plain-1", VIDEO_URL);

        assertTrue(VideoDownloads.start(post, RuntimeEnvironment.getApplication()));
        settle();

        List<File> added = added();
        assertEquals("one file reaches the gallery: " + added, 1, added.size());
        assertArrayEquals(movie, Files.readAllBytes(added.get(0).toPath()));
        assertFalse(VideoDownloads.saving(post.getAid()));
    }

    @Test public void aCopyProtectedDownloadNeverReachesTheGallery() throws Exception {
        // CENC (ISO 23001-7): the sample entries are encv and enca, which nothing outside the
        // player's DRM can decode, so the picture is black and the sound is silent.
        served.put(VIDEO_URL, Mp4Fixture.movie(Mp4Fixture.track("vide", "encv", 3), Mp4Fixture.track("soun", "enca", 3)));
        PhotoConversionTest.VideoPost post = post("protected-1", VIDEO_URL);

        assertTrue("the save is still Hushfeed's, so TikTok doesn't make the same file",
                VideoDownloads.start(post, RuntimeEnvironment.getApplication()));
        settle();

        assertEquals("the file was fetched once", List.of(VIDEO_URL), asked);
        assertEquals("nothing reaches the gallery", List.of(), added());
        assertEquals(COPY_PROTECTED, ShadowToast.getTextOfLatestToast());
        assertNoTemporaryLeft("selected-video-");
        assertFalse("nothing holds the video afterwards", VideoDownloads.saving(post.getAid()));
    }

    @Test public void aDownloadThatIsNoVideoNeverReachesTheGallery() throws Exception {
        // Each starts with a file type box, so the fetch takes it for an MP4. A body that doesn't,
        // like a web page, is refused by the fetch itself.
        Map<String, byte[]> bodies = new LinkedHashMap<>();
        bodies.put("a file type box alone", Mp4Fixture.ftyp());
        bodies.put("a sound track alone", Mp4Fixture.movie(Mp4Fixture.track("soun", "mp4a", 3)));
        bodies.put("a picture track with no samples", Mp4Fixture.movie(Mp4Fixture.track("vide", "avc1", 0)));
        int round = 0;
        for (Map.Entry<String, byte[]> body : bodies.entrySet()) {
            served.clear();
            served.put(VIDEO_URL, body.getValue());
            ShadowToast.reset();
            PhotoConversionTest.VideoPost post = post("broken-" + round++, VIDEO_URL);

            assertTrue(body.getKey(), VideoDownloads.start(post, RuntimeEnvironment.getApplication()));
            settle();

            assertEquals(body.getKey() + " reaches the gallery", List.of(), added());
            assertEquals(body.getKey(), NOT_A_VIDEO, ShadowToast.getTextOfLatestToast());
            assertNoTemporaryLeft("selected-video-");
            assertFalse(body.getKey(), VideoDownloads.saving(post.getAid()));
        }
    }

    @Test public void aStoryIsCheckedTheSameWay() throws Exception {
        // Automatic with nothing taller listed: the story's own save takes TikTok's clean address.
        Settings.DOWNLOAD_VIDEO_QUALITY.save("auto");
        served.put(VIDEO_URL, Mp4Fixture.movie(Mp4Fixture.track("vide", "encv", 3)));
        AdvancedDownloadsTest.VideoData video = new AdvancedDownloadsTest.VideoData(List.of());
        video.downloadNoWatermarkAddr = new AdvancedDownloadsTest.Address(VIDEO_URL, 900);
        PhotoConversionTest.VideoPost story = new PhotoConversionTest.VideoPost("story-1", video);
        ownIds.add(story.getAid());

        assertTrue(StoryDownloads.saveSingle(RuntimeEnvironment.getApplication(), story));
        settle();
        assertEquals("the story's own save fetched it", List.of(VIDEO_URL), asked);
        assertEquals(List.of(), added());
        assertEquals(COPY_PROTECTED, ShadowToast.getTextOfLatestToast());
        assertNoTemporaryLeft("story-");
    }

    @Test public void aRunOfPickedVideosLeavesOutTheOnesThatDontPlay() throws Exception {
        byte[] movie = Mp4Fixture.playable();
        served.put(VIDEO_URL, Mp4Fixture.movie(Mp4Fixture.track("vide", "encv", 3)));
        served.put(OTHER_URL, movie);
        PhotoConversionTest.VideoPost locked = post("run-1", VIDEO_URL);
        PhotoConversionTest.VideoPost plain = post("run-2", OTHER_URL);

        assertTrue(BatchSave.start(RuntimeEnvironment.getApplication(), List.of(locked, plain)));
        settle();

        assertEquals(List.of(VIDEO_URL, OTHER_URL), asked);
        List<File> added = added();
        assertEquals("only the video that plays is saved: " + added, 1, added.size());
        assertArrayEquals(movie, Files.readAllBytes(added.get(0).toPath()));
        assertNoTemporaryLeft("picked-video-");
    }

    private PhotoConversionTest.VideoPost post(String id, String url) {
        AdvancedDownloadsTest.VideoData video = new AdvancedDownloadsTest.VideoData(List.of(
                new AdvancedDownloadsTest.Gear("normal_1080_0", 900, url)));
        PhotoConversionTest.VideoPost post = new PhotoConversionTest.VideoPost(id, video);
        ownIds.add(id);
        return post;
    }

    private void settle() throws Exception {
        drainPool();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The files this test added under external storage, which it deletes afterwards. */
    private List<File> added() {
        Set<String> now = listed(storage);
        now.removeAll(before);
        List<File> files = new ArrayList<>();
        for (String path : now) files.add(new File(path));
        published.addAll(files);
        return files;
    }

    private static void assertNoTemporaryLeft(String prefix) throws Exception {
        File[] left = MediaCache.cacheDirectory(RuntimeEnvironment.getApplication())
                .listFiles((dir, name) -> name.startsWith(prefix));
        assertEquals("the download is not left in the cache", 0, left == null ? 0 : left.length);
    }

    private static Set<String> listed(File root) {
        Set<String> paths = new HashSet<>();
        File[] children = root.listFiles();
        if (children == null) return paths;
        for (File child : children) {
            if (child.isDirectory()) paths.addAll(listed(child));
            else paths.add(child.getAbsolutePath());
        }
        return paths;
    }

    private static void drainPool() throws Exception {
        for (int wait = 0; wait < 500; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("The media pool never emptied");
    }

    private static Set<String> videoIds() { return ReflectionHelpers.getStaticField(VideoDownloads.class, "ACTIVE"); }
}
