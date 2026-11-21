package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * A photo post saved as one video. Robolectric has no codecs, so the drawing itself is a device
 * check: this covers the sizes, timings and trims it's planned from, the photo decode, and which
 * saves the switch takes over and which it leaves alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SlideshowVideoTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<CountDownLatch> holds = new ArrayList<>();
    private final Set<String> ownIds = new java.util.HashSet<>();
    private boolean previousAsVideo, previousOriginals, previousPaused, previousAdvanced;
    private int previousSeconds;

    @Before public void setUp() throws Exception {
        drainPool();
        previousAsVideo = Settings.DOWNLOAD_PHOTOS_AS_VIDEO.savedValue();
        previousSeconds = Settings.PHOTO_VIDEO_SECONDS.savedValue();
        previousOriginals = Settings.DOWNLOAD_ORIGINAL_PHOTOS.savedValue();
        previousPaused = Setting.isPaused();
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        setPaused(false);
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(true);
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(false);
        SettingsStatus.advancedDownloadsEnabled = true;
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            for (CountDownLatch hold : holds) hold.countDown();
            drainPool();
            idle();
        } finally {
            videoIds().removeAll(ownIds);
            photoIds().removeAll(ownIds);
            Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(previousAsVideo);
            Settings.PHOTO_VIDEO_SECONDS.save(previousSeconds);
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(previousOriginals);
            SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
            setPaused(previousPaused);
        }
    }

    // Planning.

    @Test public void framesKeepThePhotosShapeInSixteenPixelSteps() {
        assertSize(1072, 1440, SlideshowVideo.frameSize(1080, 1440, 1920, 1080));
        assertSize(1072, 1920, SlideshowVideo.frameSize(1080, 1920, 1920, 1080));
        assertSize(1920, 1072, SlideshowVideo.frameSize(1920, 1080, 1920, 1080));
        assertSize(1072, 1072, SlideshowVideo.frameSize(1000, 1000, 1920, 1080));
        assertSize(1344, 1072, SlideshowVideo.frameSize(1000, 800, 1920, 1080));
        // Past 16:9 either way the frame stops narrowing and the photo gets bars.
        assertSize(1920, 1072, SlideshowVideo.frameSize(4000, 500, 1920, 1080));
        assertSize(1072, 1920, SlideshowVideo.frameSize(500, 4000, 1920, 1080));
        assertSize(1072, 1920, SlideshowVideo.frameSize(0, 0, 1920, 1080));
        assertSize(16, 16, SlideshowVideo.frameSize(1, 1, 10, 10));

        List<int[]> sizes = SlideshowVideo.frameSizes(1080, 1440);
        assertEquals(3, sizes.size());
        assertSize(1072, 1440, sizes.get(0));
        assertSize(720, 960, sizes.get(1));
        assertSize(528, 720, sizes.get(2));
        for (int[] size : SlideshowVideo.frameSizes(3024, 4032)) {
            assertEquals(0, size[0] % 16);
            assertEquals(0, size[1] % 16);
        }
    }

    @Test public void photosSitCenteredWithBarsAndDecodeAtAPowerOfTwo() {
        assertEquals(new Rect(0, 5, 1072, 1434), SlideshowVideo.fit(1080, 1440, 1072, 1440));
        assertEquals(new Rect(0, 416, 1920, 656), SlideshowVideo.fit(4000, 500, 1920, 1072));
        assertEquals(new Rect(436, 0, 636, 1072), SlideshowVideo.fit(200, 1072, 1072, 1072));
        assertEquals(new Rect(0, 0, 100, 200), SlideshowVideo.fit(0, 0, 100, 200));

        assertEquals(2, SlideshowVideo.sampleSize(4000, 3000, 1072, 804));
        assertEquals(4, SlideshowVideo.sampleSize(1000, 800, 160, 128));
        assertEquals(1, SlideshowVideo.sampleSize(1000, 800, 1000, 800));
        assertEquals("Never past 64", 64, SlideshowVideo.sampleSize(100_000, 100_000, 1, 1));
        assertEquals(1, SlideshowVideo.sampleSize(10, 10, 0, 0));
    }

    /** Up to ten seconds nothing changes; past that the rate drops and the length stays exact (#465). */
    @Test public void aPhotoHeldForMinutesTakesAboutTheFramesOfAShortOne() {
        assertEquals(15, SlideshowVideo.frameRate(1));
        assertEquals(15, SlideshowVideo.frameRate(10));
        assertEquals(150, SlideshowVideo.framesPerPhoto(10));
        assertEquals(13, SlideshowVideo.frameRate(11));
        assertEquals(2, SlideshowVideo.frameRate(60));
        assertEquals(120, SlideshowVideo.framesPerPhoto(60));
        assertEquals(2, SlideshowVideo.frameRate(300));
        assertEquals(600, SlideshowVideo.framesPerPhoto(300));
        for (int seconds : new int[]{1, 7, 10, 11, 37, 60, 149, 300}) {
            int fps = SlideshowVideo.frameRate(seconds);
            assertEquals("video length at " + seconds + " seconds", seconds * 1_000_000_000L,
                    SlideshowVideo.presentationTimeNs(SlideshowVideo.framesPerPhoto(seconds), fps));
        }
        assertEquals(60_000L, SlideshowVideo.soundWorkMs(300_000_000L));
    }

    @Test public void timingFollowsTheSettingAndTheSoundIsCutInWholeSamples() {
        assertEquals(1, SlideshowVideo.seconds(0));
        assertEquals(3, SlideshowVideo.seconds(3));
        assertEquals(60, SlideshowVideo.seconds(60));
        assertEquals(300, SlideshowVideo.seconds(5000));
        assertEquals(3, (int) Settings.PHOTO_VIDEO_SECONDS.defaultValue);
        assertFalse(Settings.DOWNLOAD_PHOTOS_AS_VIDEO.defaultValue);

        assertEquals(45, SlideshowVideo.framesPerPhoto(3));
        assertEquals(0L, SlideshowVideo.presentationTimeNs(0, 15));
        assertEquals(66_666_666L, SlideshowVideo.presentationTimeNs(1, 15));
        assertEquals(1_000_000_000L, SlideshowVideo.presentationTimeNs(15, 15));
        assertEquals(12_000_000L, SlideshowVideo.durationUs(4, 3));
        assertEquals(350_000_000L, SlideshowVideo.durationUs(35, 10));
        assertEquals(529_200L, SlideshowVideo.trimFrames(12_000_000L, 44_100));

        assertEquals("The cut is the limit", 400, SlideshowVideo.pcmBytes(4096, 8192, 100, 4));
        assertEquals("The encoder buffer is the limit", 2048, SlideshowVideo.pcmBytes(4096, 2048, 10_000, 4));
        assertEquals("A half sample stays behind", 4096, SlideshowVideo.pcmBytes(4097, 8192, 10_000, 4));
        assertEquals(0, SlideshowVideo.pcmBytes(3, 8192, 10_000, 4));
        assertEquals(0, SlideshowVideo.pcmBytes(4096, 8192, 0, 4));
        assertEquals(0, SlideshowVideo.pcmBytes(4096, 8192, 100, 0));

        assertEquals(9_026_122L, SlideshowVideo.loopLengthUs(9_000_000L, 26_122L));
        assertEquals(SlideshowVideo.MIN_LOOP_US, SlideshowVideo.loopLengthUs(0, 0));
        assertEquals(SlideshowVideo.MIN_LOOP_US, SlideshowVideo.loopLengthUs(-5, -5));

        assertEquals(4_116_480, SlideshowVideo.bitRate(1072, 1920));
        assertEquals(1_000_000, SlideshowVideo.bitRate(16, 16));
        assertEquals(6_000_000, SlideshowVideo.bitRate(4000, 4000));
        assertEquals(96_000, SlideshowVideo.audioBitRate(1));
        assertEquals(128_000, SlideshowVideo.audioBitRate(2));
    }

    /** #465: what's typed in the question, held to the row's range, and the row's value when it's not a number. */
    @Test public void theSecondsTypedInTheQuestionKeepToTheRowsRange() {
        assertEquals(12, SlideshowVideo.typedSeconds("12", 3));
        assertEquals(5, SlideshowVideo.typedSeconds(" 5 ", 3));
        assertEquals("Left empty", 3, SlideshowVideo.typedSeconds("", 3));
        assertEquals(3, SlideshowVideo.typedSeconds(null, 3));
        assertEquals(3, SlideshowVideo.typedSeconds("2.5", 3));
        assertEquals(3, SlideshowVideo.typedSeconds("-4", 3));
        assertEquals(1, SlideshowVideo.typedSeconds("0", 3));
        assertEquals(300, SlideshowVideo.typedSeconds("999", 3));
        assertEquals("Past what an int holds", 300, SlideshowVideo.typedSeconds("99999999999999", 3));
        assertEquals("A story's own length out of range", 300, SlideshowVideo.typedSeconds("", 900));
    }

    /** #465: a post's video keeps its own pace and the photos after it start one frame past its end. */
    @Test public void aPostsVideoKeepsItsTimesAndThePhotosAfterItStartPastItsEnd() {
        long start = 6_000_000_000L;
        assertEquals("Its first frame shows where the photos stopped", start,
                SlideshowVideo.clipFrameNs(start, 40_000L, 40_000L, -1));
        assertEquals(start + 33_333_000L, SlideshowVideo.clipFrameNs(start, 40_000L, 73_333L, start));
        assertEquals("A frame stamped before the first counts from it", start,
                SlideshowVideo.clipFrameNs(start, 40_000L, 10_000L, -1));
        assertEquals("Never at or before the frame before it", start + 1_000L,
                SlideshowVideo.clipFrameNs(start, 40_000L, 40_000L, start));
        assertEquals(start + 33_333_000L, SlideshowVideo.clipEndNs(start, 33_333L));
        assertEquals("A broken stamp can't stretch it", start + 1_000_000_000L,
                SlideshowVideo.clipEndNs(start, 90_000_000L));
        assertEquals(start + 1_000_000L, SlideshowVideo.clipEndNs(start, 0L));

        assertArrayEquals(new int[]{1080, 1920}, SlideshowVideo.shownSize(1080, 1920, 0));
        assertArrayEquals("Stored sideways, shown upright", new int[]{1080, 1920}, SlideshowVideo.shownSize(1920, 1080, 90));
        assertArrayEquals(new int[]{1920, 1080}, SlideshowVideo.shownSize(1920, 1080, 180));
        assertArrayEquals(new int[]{720, 1280}, SlideshowVideo.shownSize(1280, 720, 270));

        Rect wide = SlideshowVideo.fit(1920, 1080, 1072, 1920);
        assertArrayEquals("GL counts the box from the frame's bottom",
                new int[]{wide.left, 1920 - wide.bottom, wide.width(), wide.height()},
                SlideshowVideo.viewport(wide, 1920));
        assertArrayEquals(new int[]{0, 659, 1072, 603}, SlideshowVideo.viewport(wide, 1920));

        assertEquals(0, SlideshowVideo.clipShare(0, 3_000_000L, 45));
        assertEquals(15, SlideshowVideo.clipShare(1_000_000L, 3_000_000L, 45));
        assertEquals("Never past the item's share", 45, SlideshowVideo.clipShare(9_000_000L, 3_000_000L, 45));
        assertEquals("A video with no length moves nothing", 0, SlideshowVideo.clipShare(1_000_000L, 0, 45));
    }

    /** #465: a post's video is fetched clean first, TikTok's stamped download last. */
    @Test public void aPostsVideoIsFetchedCleanFirstAndStampedLast() {
        var video = new AdvancedDownloadsTest.VideoData(List.of());
        video.downloadAddr = new AdvancedDownloadsTest.Address("https://example.com/stamped", 10);
        video.playAddr = new AdvancedDownloadsTest.Address("https://example.com/play", 10);
        assertEquals(List.of("https://example.com/play", "https://example.com/stamped"), SlideshowVideo.clipSources(video));
        video.downloadNoWatermarkAddr = new AdvancedDownloadsTest.Address("https://example.com/clean", 10);
        assertEquals(List.of("https://example.com/clean", "https://example.com/play", "https://example.com/stamped"),
                SlideshowVideo.clipSources(video));

        var post = post("slideshow-clip-sources", List.of(photo("https://example.com/one"),
                photo("https://example.com/two").clip(video), photo("https://example.com/three").live()));
        List<List<String>> clips = SlideshowVideo.clips(post);
        assertEquals(3, clips.size());
        assertEquals(List.of(), clips.get(0));
        assertEquals("https://example.com/clean", clips.get(1).get(0));
        assertEquals("A moving part with no address stays a photo", List.of(), clips.get(2));
    }

    @Test public void aLargePhotoDecodesSmallAndAnUnreadableOneIsNull() throws Exception {
        File png = File.createTempFile("slideshow-", ".png", RuntimeEnvironment.getApplication().getCacheDir());
        File junk = File.createTempFile("slideshow-", ".tmp", RuntimeEnvironment.getApplication().getCacheDir());
        try {
            Bitmap source = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888);
            source.eraseColor(0xff336699);
            try (OutputStream out = new FileOutputStream(png)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            source.recycle();

            Bitmap small = SlideshowVideo.decode(png, new int[]{160, 160});
            assertNotNull(small);
            assertEquals(250, small.getWidth());
            assertEquals(200, small.getHeight());
            Bitmap first = SlideshowVideo.decode(png, null);
            assertNotNull(first);
            assertEquals("The first photo sizes the frame and already fits it", 1000, first.getWidth());

            Files.write(junk.toPath(), "not a photo".getBytes(StandardCharsets.UTF_8));
            assertNull(SlideshowVideo.decode(junk, null));
        } finally {
            assertTrue(png.delete());
            assertTrue(junk.delete());
        }
    }

    // Gating.

    @Test public void switchOffPauseOrNoPatchLeaveEverySaveAsItWas() {
        var one = post("slideshow-off-one", List.of(photo("https://example.com/one")));
        var two = post("slideshow-off-two", List.of(photo("https://example.com/one"), photo("https://example.com/two")));
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(false);
        assertFalse(OriginalPhotos.startImageAsVideo(one));
        assertFalse(OriginalPhotos.startPhotos(two, Set.of(0, 1), true));

        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(true);
        setPaused(true);
        assertFalse(OriginalPhotos.startImageAsVideo(one));
        assertFalse(OriginalPhotos.startPhotos(two, Set.of(0, 1), true));
        setPaused(false);

        SettingsStatus.advancedDownloadsEnabled = false;
        assertFalse(OriginalPhotos.startImageAsVideo(one));
        assertFalse(SlideshowVideo.offer(two, Set.of(0, 1)));

        idle();
        for (var post : List.of(one, two)) {
            assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
            assertNull(MediaJobScheduler.job("photos " + post.getAid()));
        }
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void videoPostsAndLivePhotosStayWithTikTok() {
        var video = new AdvancedDownloadsTest.VideoData(List.of(
                new AdvancedDownloadsTest.Gear("normal_720_0", 700, "https://example.com/video")));
        var ordinary = new PhotoConversionTest.VideoPost("slideshow-ordinary", video);
        ownIds.add(ordinary.getAid());
        assertFalse(OriginalPhotos.startImageAsVideo(ordinary));
        assertFalse(SlideshowVideo.offer(ordinary, null));

        var live = post("slideshow-live", List.of(photo("https://example.com/still"),
                photo("https://example.com/live").live()));
        assertFalse(OriginalPhotos.startImageAsVideo(live));
        assertFalse(OriginalPhotos.startPhotos(live, Set.of(1), true));
        idle();
        assertNull(MediaJobScheduler.job("photo video " + ordinary.getAid()));
        assertNull(MediaJobScheduler.job("photo video " + live.getAid()));
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void downloadVideoOnAOnePhotoPostQueuesThePhotoVideo() throws Exception {
        holdWorkers();
        var post = post("slideshow-one", List.of(photo("https://example.com/one")));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        MediaJobScheduler.Job job = MediaJobScheduler.job("photo video " + post.getAid());
        assertNotNull(job);
        assertTrue(job.waiting());
        assertEquals("photo video", job.record.kind);
        assertEquals(1, job.record.files);
        assertNull("No photo save runs beside it", MediaJobScheduler.job("photos " + post.getAid()));
        assertTrue(videoIds().contains(post.getAid()));

        assertTrue("A second press while it waits takes the save and says it's busy",
                OriginalPhotos.startImageAsVideo(post));
        idle();
        assertEquals(MediaJobScheduler.busyMessage("photo video " + post.getAid()), ShadowToast.getTextOfLatestToast());

        assertTrue(job.cancel());
        assertFalse(videoIds().contains(post.getAid()));
        assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
    }

    /**
     * #430: picked photo posts joined into one video keep the order they were picked in, each
     * post's sound goes under that post's own photos, and a post without its photos is left out.
     */
    @Test public void joinedPostsKeepThePickOrderAndEachPostsOwnSound() {
        var first = post("slideshow-join-first", List.of(photo("https://example.com/join/b0"),
                photo("https://example.com/join/b1")));
        var missing = post("slideshow-join-missing", List.of());
        var last = storyWithSound("slideshow-join-last");

        SlideshowVideo.Plan plan = SlideshowVideo.plan(List.of(first, missing, last));

        assertSame("the video isn't named after the first post with photos", first, plan.first);
        assertEquals(3, plan.photos.size());
        assertTrue(plan.photos.get(0).contains("https://example.com/join/b0"));
        assertTrue(plan.photos.get(1).contains("https://example.com/join/b1"));
        assertTrue(plan.photos.get(2).contains("https://example.com/one"));
        assertEquals("a post without photos got a sound", 2, plan.sounds.size());
        assertEquals(2, plan.sounds.get(0).photos);
        assertTrue("a post with no sound borrowed one", plan.sounds.get(0).sources.isEmpty());
        assertEquals(1, plan.sounds.get(1).photos);
        assertTrue(plan.sounds.get(1).sources.contains("https://example.com/sound"));
        assertNull(SlideshowVideo.plan(List.of(missing)).first);
    }

    @Test public void aPhotoStoryWithASoundRunsTenSecondsAndASilentOneFive() {
        var silent = post("slideshow-story-silent", List.of(photo("https://example.com/one")));
        assertEquals(5, SlideshowVideo.storySeconds(silent));
        assertEquals(10, SlideshowVideo.storySeconds(storyWithSound("slideshow-story-sound")));
    }

    @Test public void aPhotoStorySavesAsAVideoAtTheStorysLengthAndNotTheRows() throws Exception {
        Settings.PHOTO_VIDEO_SECONDS.save(3);
        List<Integer> lengths = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch made = new CountDownLatch(1);
        SlideshowVideo.makerForTests = (name, seconds, sounds, clips) -> {
            lengths.add(seconds);
            made.countDown();
            throw new java.io.IOException("Nothing to draw with here");
        };
        try {
            var story = storyWithSound("slideshow-story-length");
            assertTrue(StoryDownloads.saveStory(RuntimeEnvironment.getApplication(), story));
            assertNull("The photos aren't saved beside it", MediaJobScheduler.job("story " + story.getAid()));
            assertTrue("The video never started", made.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(10), lengths);
        } finally {
            SlideshowVideo.makerForTests = null;
        }
    }

    /** #469: a post with just one photo stays up as long as its sound plays, not the row's seconds. */
    @Test public void aOnePhotoPostPlaysItsWholeSoundAndSeveralPhotosKeepTheRow() throws Exception {
        Settings.PHOTO_VIDEO_SECONDS.save(3);
        List<String> made = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<CountDownLatch> next =
                new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(1));
        SlideshowVideo.makerForTests = (name, seconds, sounds, clips) -> {
            made.add(seconds + " " + sounds.size() + " " + sounds.get(0).whole + " " + sounds.get(0).sources.isEmpty());
            next.get().countDown();
            throw new java.io.IOException("Nothing to draw with here");
        };
        try {
            var one = withSound(post("slideshow-whole-one", List.of(photo("https://example.com/one"))));
            assertTrue(SlideshowVideo.start(one, null));
            assertTrue("The one-photo video never started", next.get().await(5, TimeUnit.SECONDS));

            next.set(new CountDownLatch(1));
            var two = withSound(post("slideshow-whole-two",
                    List.of(photo("https://example.com/a"), photo("https://example.com/b"))));
            assertTrue(SlideshowVideo.start(two, null));
            assertTrue("The two-photo video never started", next.get().await(5, TimeUnit.SECONDS));

            next.set(new CountDownLatch(1));
            var silent = post("slideshow-whole-silent", List.of(photo("https://example.com/one")));
            assertTrue(SlideshowVideo.start(silent, null));
            assertTrue("The silent video never started", next.get().await(5, TimeUnit.SECONDS));

            assertEquals(List.of("3 1 true false", "3 1 false false", "3 1 false true"), made);
        } finally {
            SlideshowVideo.makerForTests = null;
        }
    }

    @Test public void onlyAPostWithOnePhotoAndASoundPlaysTheWholeSound() {
        List<String> sound = List.of("https://example.com/sound");
        assertTrue(SlideshowVideo.playsWholeSound(1, false, sound));
        assertFalse("a story keeps a story's length", SlideshowVideo.playsWholeSound(1, true, sound));
        assertFalse(SlideshowVideo.playsWholeSound(2, false, sound));
        assertFalse("nothing to play", SlideshowVideo.playsWholeSound(1, false, List.of()));
    }

    @Test public void theWholeSoundRoundsUpToASecondAndStopsAtTheRowsLongest() {
        assertEquals(30, SlideshowVideo.wholeSoundSeconds(29_400_000L));
        assertEquals(30, SlideshowVideo.wholeSoundSeconds(30_000_000L));
        assertEquals(31, SlideshowVideo.wholeSoundSeconds(30_000_001L));
        assertEquals(1, SlideshowVideo.wholeSoundSeconds(1L));
        assertEquals(1, SlideshowVideo.wholeSoundSeconds(0L));
        assertEquals(SlideshowVideo.MAX_SECONDS_PER_PHOTO, SlideshowVideo.wholeSoundSeconds(400_000_000L));
    }

    @Test public void aPhotoStoryKeepsItsPhotoSaveWithTheSwitchOff() throws Exception {
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(false);
        holdWorkers();
        var story = storyWithSound("slideshow-story-off");
        assertTrue(StoryDownloads.saveStory(RuntimeEnvironment.getApplication(), story));
        assertNull(MediaJobScheduler.job("photo video " + story.getAid()));
        MediaJobScheduler.Job job = MediaJobScheduler.job("story " + story.getAid());
        assertNotNull(job);
        assertTrue(job.cancel());
    }

    @Test public void aLivePhotoStoryKeepsItsOwnSave() throws Exception {
        holdWorkers();
        var photo = photo("https://example.com/one");
        photo.live();
        var story = post("slideshow-story-live", List.of(photo));
        assertTrue(StoryDownloads.saveStory(RuntimeEnvironment.getApplication(), story));
        assertNull(MediaJobScheduler.job("photo video " + story.getAid()));
        MediaJobScheduler.Job job = MediaJobScheduler.job("story " + story.getAid());
        assertNotNull(job);
        assertTrue(job.cancel());
    }

    @Test public void aOnePhotoPostSkipsTheQuestionAndNoScreenLeavesThePickerAlone() {
        Activity previous = Utils.getActivity();
        try {
            var one = post("slideshow-picker-one", List.of(photo("https://example.com/one")));
            assertFalse(SlideshowVideo.offer(one, Set.of(0)));
            Utils.setActivity(null);
            var two = post("slideshow-picker-no-screen", List.of(photo("https://example.com/one"),
                    photo("https://example.com/two")));
            if (Utils.getVisibleActivity() == null) assertFalse(SlideshowVideo.offer(two, Set.of(0, 1)));
            idle();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            Utils.setActivity(previous);
        }
    }

    @Test public void thePickerAsksAndEachAnswerQueuesItsOwnSave() throws Exception {
        holdWorkers();
        Activity previous = Utils.getActivity();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            var images = List.of(photo("https://example.com/one"), photo("https://example.com/two"),
                    photo("https://example.com/three"));

            var asVideo = post("slideshow-ask-video", images);
            assertTrue(OriginalPhotos.startPhotos(asVideo, Set.of(2, 0), true));
            idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            assertEquals("Save photos or a video?", Shadows.shadowOf(dialog).getTitle().toString());
            assertEquals("Save as video", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Save photos", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertNull("Nothing is queued before an answer", MediaJobScheduler.job("photo video " + asVideo.getAid()));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            MediaJobScheduler.Job video = MediaJobScheduler.job("photo video " + asVideo.getAid());
            assertNotNull(video);
            assertEquals(1, video.record.files);
            assertNull(MediaJobScheduler.job("photos " + asVideo.getAid()));
            assertTrue(video.cancel());

            var asPhotos = post("slideshow-ask-photos", images);
            assertTrue(OriginalPhotos.startPhotos(asPhotos, Set.of(2, 0), true));
            idle();
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            MediaJobScheduler.Job photos = MediaJobScheduler.job("photos " + asPhotos.getAid());
            assertNotNull("Save photos works with Download original photos off", photos);
            assertEquals("The picked photos, not the whole post", 2, photos.record.files);
            assertNull(MediaJobScheduler.job("photo video " + asPhotos.getAid()));
            assertTrue(photos.cancel());

            var cancelled = post("slideshow-ask-cancel", images);
            assertTrue(OriginalPhotos.startPhotos(cancelled, Set.of(1), true));
            idle();
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle();
            assertNull(MediaJobScheduler.job("photos " + cancelled.getAid()));
            assertNull(MediaJobScheduler.job("photo video " + cancelled.getAid()));
        } finally {
            Utils.setActivity(previous);
        }
    }

    /**
     * #465: the question carries the seconds each photo stays, set to the row's. What's typed
     * makes this video and becomes the row's, so the next post starts from it.
     */
    @Test public void theQuestionHoldsTheSecondsAndAPostKeepsWhatWasTyped() throws Exception {
        Settings.PHOTO_VIDEO_SECONDS.save(3);
        List<Integer> lengths = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch made = new CountDownLatch(1);
        SlideshowVideo.makerForTests = (name, seconds, sounds, clips) -> {
            lengths.add(seconds);
            made.countDown();
            throw new java.io.IOException("Nothing to draw with here");
        };
        Activity previous = Utils.getActivity();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            var post = post("slideshow-ask-seconds", List.of(photo("https://example.com/one"),
                    photo("https://example.com/two"), photo("https://example.com/three")));
            assertTrue(OriginalPhotos.startPhotos(post, Set.of(0, 2), true));
            idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            android.widget.EditText field = editText(dialog.getWindow().getDecorView());
            assertNotNull("The question has no seconds field", field);
            assertEquals("3", field.getText().toString());
            assertEquals(android.text.InputType.TYPE_CLASS_NUMBER, field.getInputType());
            assertEquals("The keyboard pushes the buttons up instead of covering them",
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    dialog.getWindow().getAttributes().softInputMode
                            & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            field.setText("7");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertTrue("The video never started", made.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(7), lengths);
            assertEquals("The row keeps what was typed", 7, (int) Settings.PHOTO_VIDEO_SECONDS.get());
        } finally {
            SlideshowVideo.makerForTests = null;
            Utils.setActivity(previous);
        }
    }

    /**
     * #465: photos and videos picked together ask too, and Save as video hands each picked item's
     * video along in the picked order. A video item TikTok gives no still keeps the save going.
     */
    @Test public void photosAndVideosPickedTogetherMakeOneVideo() throws Exception {
        var video = new AdvancedDownloadsTest.VideoData(List.of());
        video.playAddr = new AdvancedDownloadsTest.Address("https://example.com/clip", 10);
        List<List<List<String>>> made = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        SlideshowVideo.makerForTests = (name, seconds, sounds, clips) -> {
            made.add(clips);
            done.countDown();
            throw new java.io.IOException("Nothing to draw with here");
        };
        List<String> tiktoks = new java.util.concurrent.CopyOnWriteArrayList<>();
        Activity previous = Utils.getActivity();
        try (var owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            var mixed = post("slideshow-mixed", List.of(photo("https://example.com/one"),
                    photo(null).clip(video), photo("https://example.com/three")));
            assertTrue(SlideshowVideo.offerMixed(mixed, Set.of(1L, 0L), () -> tiktoks.add("saved")));
            idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("Save photos or a video?", Shadows.shadowOf(dialog).getTitle().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertTrue("The video never started", done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(List.of(List.of(), List.of("https://example.com/clip"))), made);
            assertEquals("TikTok's save stays out of a video", List.of(), tiktoks);

            var photos = post("slideshow-mixed-photos", List.of(photo("https://example.com/one"),
                    photo("https://example.com/two").clip(video)));
            assertTrue(SlideshowVideo.offerMixed(photos, Set.of(0L, 1L), () -> tiktoks.add("saved")));
            idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            assertEquals("Save photos is TikTok's own save", List.of("saved"), tiktoks);
            assertNull(MediaJobScheduler.job("photos " + photos.getAid()));

            assertFalse("One picked item is TikTok's to save", SlideshowVideo.offerMixed(photos, Set.of(1L), () -> { }));
            Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(false);
            assertFalse(SlideshowVideo.offerMixed(photos, Set.of(0L, 1L), () -> { }));
        } finally {
            SlideshowVideo.makerForTests = null;
            Utils.setActivity(previous);
        }
    }

    private static android.widget.EditText editText(android.view.View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (!(view instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.EditText found = editText(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    @Test public void aFailedPhotoEndsWithTheErrorAndNoFilesLeftBehind() throws Exception {
        // The transport refuses this private address before any connection opens.
        var post = post("slideshow-failure", List.of(photo("https://127.0.0.1/one"),
                photo("https://127.0.0.1/two")));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        drainPool();
        idle();
        assertEquals("Couldn't make the video. Try again.", ShadowToast.getTextOfLatestToast());
        assertFalse(videoIds().contains(post.getAid()));
        assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
        assertEquals("No temporary file stays in the cache", List.of(),
                leftovers(RuntimeEnvironment.getApplication().getCacheDir()));
    }

    @Test public void aPostWithoutUsablePhotosSaysSoAndQueuesNothing() {
        var post = post("slideshow-missing", List.of(photo(null)));
        assertTrue(OriginalPhotos.startImageAsVideo(post));
        idle();
        assertEquals("No video saved. The original photos are missing.", ShadowToast.getTextOfLatestToast());
        assertNull(MediaJobScheduler.job("photo video " + post.getAid()));
        assertFalse(videoIds().contains(post.getAid()));
    }

    private static List<String> leftovers(File directory) {
        List<String> found = new ArrayList<>();
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return found;
        for (File file : files) {
            if (file.isDirectory()) found.addAll(leftovers(file));
            else if (file.getName().startsWith("photo-video-")) found.add(file.getName());
        }
        return found;
    }

    private static void assertSize(int width, int height, int[] size) {
        assertEquals("width", width, size[0]);
        assertEquals("height", height, size[1]);
    }

    private AdvancedDownloadsTest.PhotoPost post(String id, List<AdvancedDownloadsTest.Photo> images) {
        ownIds.add(id);
        return new AdvancedDownloadsTest.PhotoPost(id, images);
    }

    private static AdvancedDownloadsTest.Photo photo(String url) {
        return new AdvancedDownloadsTest.Photo(url);
    }

    /** A photo story carries its music as the Video's play address. */
    private AdvancedDownloadsTest.PhotoPost storyWithSound(String id) {
        return withSound(post(id, List.of(photo("https://example.com/one"))));
    }

    private static AdvancedDownloadsTest.PhotoPost withSound(AdvancedDownloadsTest.PhotoPost post) {
        post.video = new AdvancedDownloadsTest.VideoData(List.of());
        post.video.playAddr = new AdvancedDownloadsTest.Address("https://example.com/sound", 100);
        return post;
    }

    private static Set<String> videoIds() { return ReflectionHelpers.getStaticField(SlideshowVideo.class, "ACTIVE"); }
    private static Set<String> photoIds() { return ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE"); }
    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", from(boolean.class, value));
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private void holdWorkers() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        holds.add(hold);
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int i = 0; i < MediaJobScheduler.MAX_RUNNING_JOBS; i++) {
            assertTrue(MediaJobScheduler.submit("slideshow worker hold", () -> {
                started.countDown();
                try { hold.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }));
        }
        assertTrue("The media workers never started", started.await(5, TimeUnit.SECONDS));
    }

    private static void drainPool() throws Exception {
        for (int wait = 0; wait < 500; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("The media pool never emptied");
    }
}
