/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Photo posts saved here go into the record Check for already-saved videos keeps (#197), under the
 * post's id like a video: so the mark covers them and a second save offers Open or Save again.
 * A post saved as photos is opened at the first photo that landed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class PhotoPostSavedRecordTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<Integer> tried = new CopyOnWriteArrayList<>();
    private final java.util.Map<Integer, String> names = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> ownIds = new HashSet<>();
    private Context context;
    private boolean previousOriginals, previousCheck, previousAdvanced;
    private ActivityController<SaveNoticeTest.HostActivity> owner;

    @Before public void setUp() throws Exception {
        drainPool();
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Utils.awaitBackgroundTasksForTests();
        SavedVideoArchiveAccess.reset(context);
        previousOriginals = Settings.DOWNLOAD_ORIGINAL_PHOTOS.savedValue();
        previousCheck = Settings.CHECK_SAVED_VIDEOS.savedValue();
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
        Settings.CHECK_SAVED_VIDEOS.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        SaveNotice.windowRootsForTests = List.of();
        OriginalPhotos.photoStepForTests = (position, name) -> {
            tried.add(position);
            names.put(position, name);
            return file(name);
        };
        owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        Utils.setActivity(owner.get());
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            drainPool();
            idle();
        } finally {
            OriginalPhotos.photoStepForTests = null;
            SlideshowVideo.makerForTests = null;
            SaveNotice.windowRootsForTests = null;
            Utils.setActivity(null);
            owner.close();
            Set<String> photoActive = ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE");
            photoActive.removeAll(ownIds);
            Set<String> videoActive = ReflectionHelpers.getStaticField(SlideshowVideo.class, "ACTIVE");
            videoActive.removeAll(ownIds);
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(previousOriginals);
            Settings.CHECK_SAVED_VIDEOS.save(previousCheck);
            SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
            SavedVideoArchiveAccess.reset(context);
        }
    }

    @Test public void aPostSavedAsPhotosIsRecordedAtItsFirstPhotoAndMarked() throws Exception {
        var post = post("photos-recorded", 3);
        assertTrue(OriginalPhotos.startPhotos(post, null, false));
        finish();
        assertEquals(List.of(0, 1, 2), tried);

        MediaFileWriter.Saved found = SavedVideoArchive.find(context, "photos-recorded");
        assertNotNull("the photo post never reached the record", found);
        assertEquals("Open should go to the first photo", names.get(0), found.name);
        assertTrue("the mark skipped a photo post", marked("photos-recorded"));
    }

    @Test public void aSecondSaveOfThePostOffersOpenOrSaveAgainInsteadOfCopies() throws Exception {
        var post = post("photos-again", 2);
        assertTrue(OriginalPhotos.startPhotos(post, null, false));
        finish();
        assertTrue("new photos said nothing as they saved", ShadowToast.showedToast("Saving 2 original photos"));
        tried.clear();

        ShadowToast.reset();
        assertTrue(OriginalPhotos.startPhotos(post, null, false));
        finish();
        assertEquals("the photos were saved a second time with no word", List.of(), tried);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no already-saved choice came up", dialog);
        assertTrue(dialog.isShowing());
        assertEquals("This photo post is already saved", Shadows.shadowOf(dialog).getTitle().toString());
        // #356: "Saving" said before the choice reads as a second copy on its way.
        assertFalse("photos never fetched said they were saving",
                ShadowToast.showedToast("Saving 2 original photos"));

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        finish();
        assertEquals("Save again saves them", List.of(0, 1), tried);
    }

    @Test public void aPostSavedAsAVideoIsRecordedAndOffered() throws Exception {
        var post = post("video-recorded", 2);
        int[] made = {0};
        SlideshowVideo.makerForTests = (name, seconds, sounds) -> {
            made[0]++;
            return file(name);
        };
        assertTrue(SlideshowVideo.start(post, null));
        finish();
        assertEquals(1, made[0]);
        assertNotNull("the slideshow video never reached the record",
                SavedVideoArchive.find(context, "video-recorded"));
        assertTrue(marked("video-recorded"));
        assertTrue("a new video said nothing as it was made",
                ShadowToast.showedToast("Making a video of the photos"));

        ShadowToast.reset();
        assertTrue(SlideshowVideo.start(post, null));
        finish();
        assertEquals("a second video was made with no word", 1, made[0]);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no already-saved choice came up", dialog);
        // The post is a photo post, whatever form it was saved in (#197).
        assertEquals("This photo post is already saved", Shadows.shadowOf(dialog).getTitle().toString());
        assertFalse("a video never made said it was being made",
                ShadowToast.showedToast("Making a video of the photos"));
    }

    @Test public void aPhotoStorySavedAsAVideoAgainIsCalledAStory() throws Exception {
        var story = post("story-video", 2);
        SlideshowVideo.makerForTests = (name, seconds, sounds) -> file(name);
        assertTrue(SlideshowVideo.saveStory(story));
        finish();
        assertNotNull("the story's video never reached the record", SavedVideoArchive.find(context, "story-video"));

        assertTrue(SlideshowVideo.saveStory(story));
        finish();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no already-saved choice came up", dialog);
        assertEquals("This story is already saved", Shadows.shadowOf(dialog).getTitle().toString());
    }

    @Test public void aPhotoStorysPhotosSavedAgainFromTheQuestionAreCalledAStory() throws Exception {
        boolean previousAsVideo = Settings.DOWNLOAD_PHOTOS_AS_VIDEO.savedValue();
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(true);
        try {
            var story = post("story-photos", 2);
            for (int round = 0; round < 2; round++) {
                assertTrue(SlideshowVideo.saveStory(story));
                finish();
                AlertDialog question = ShadowAlertDialog.getLatestAlertDialog();
                assertNotNull("the story never asked between photos and a video", question);
                assertEquals("Save photos or a video?", Shadows.shadowOf(question).getTitle().toString());
                question.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                finish();
            }
            assertEquals("the second save fetched the photos again", List.of(0, 1), tried);
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("This story is already saved", Shadows.shadowOf(dialog).getTitle().toString());
        } finally {
            Settings.DOWNLOAD_PHOTOS_AS_VIDEO.save(previousAsVideo);
        }
    }

    @Test public void aPhotoStorySavedAgainFromTikToksSheetOrTheSaveButtonIsCalledAStory() throws Exception {
        ownIds.add("story-sheet");
        List<AdvancedDownloadsTest.Photo> images = new ArrayList<>();
        for (int i = 0; i < 2; i++) images.add(new AdvancedDownloadsTest.Photo("https://example.com/story-sheet/" + i));
        PhotoStory story = new PhotoStory("story-sheet", images);
        assertTrue(OriginalPhotos.startPhotos(story, null, false));
        finish();
        assertNotNull("the story never reached the record", SavedVideoArchive.find(context, "story-sheet"));

        assertTrue(OriginalPhotos.startPhotos(story, null, false));
        finish();
        AlertDialog sheet = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no already-saved choice came up", sheet);
        assertEquals("This story is already saved", Shadows.shadowOf(sheet).getTitle().toString());
        sheet.dismiss();
        finish();

        OriginalPhotos.startFromButton(story, owner.get());
        finish();
        AlertDialog button = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the save button never asked", button);
        assertTrue("the save button's choice is a new one", button != sheet);
        assertEquals("This story is already saved", Shadows.shadowOf(button).getTitle().toString());
    }

    /** A photo story as TikTok's own post, its photos on the field a photo post keeps them on. */
    public static final class PhotoStory extends Aweme {
        public final AdvancedDownloadsTest.Info photoModeImageInfo;
        private final String aid;

        PhotoStory(String aid, List<AdvancedDownloadsTest.Photo> photos) {
            this.aid = aid;
            photoModeImageInfo = new AdvancedDownloadsTest.Info(photos);
        }

        @Override public String getAid() { return aid; }
        @Override public boolean getIsTikTokStory() { return true; }
        @Override public int getAwemeType() { return 0; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
        @Override public long getCreateTime() { return 0L; }
    }

    @Test public void withTheCheckOffNothingIsRecorded() throws Exception {
        Settings.CHECK_SAVED_VIDEOS.save(false);
        var post = post("photos-unchecked", 2);
        assertTrue(OriginalPhotos.startPhotos(post, null, false));
        finish();
        assertEquals(List.of(0, 1), tried);
        assertNull(SavedVideoArchive.find(context, "photos-unchecked"));
    }

    private boolean marked(String id) throws Exception {
        SavedVideoArchive.resetForTests();
        SavedVideoArchive.readIdsLater();
        Utils.awaitBackgroundTasksForTests();
        return SavedVideoArchive.isSaved(id);
    }

    private MediaFileWriter.Saved file(String name) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        return new MediaFileWriter.Saved(name, null, file);
    }

    private AdvancedDownloadsTest.PhotoPost post(String id, int count) {
        ownIds.add(id);
        List<AdvancedDownloadsTest.Photo> images = new ArrayList<>();
        for (int i = 0; i < count; i++) images.add(new AdvancedDownloadsTest.Photo("https://example.com/" + id + "/" + i));
        return new AdvancedDownloadsTest.PhotoPost(id, images);
    }

    /**
     * Until neither the media pool nor the main looper has work left. A dialog button's click
     * runs on the looper, so Save again queues its job only once the looper idles.
     */
    private void finish() throws Exception {
        do {
            drainPool();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
            idle();
        } while (!MediaJobScheduler.idle());
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void drainPool() throws Exception {
        for (int wait = 0; wait < 500; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("The media pool never emptied");
    }
}
