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
import android.content.ContentUris;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;

import app.morphe.extension.shared.BackgroundPoolSaturation;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The photo saves that stay TikTok's own (#197): with Check for already-saved videos on, the job
 * is held for the check and started again, and its first gallery copy is recorded under the
 * post's id, so a second save offers Open or Save again worded for a photo post or a story. The
 * copies are MediaStore Uris, as TikTok's gallery copy hands back on Android 10 and later.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en")
public class NativePhotoSavesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private boolean previousOriginals, previousCheck, previousAdvanced;
    private ActivityController<SaveNoticeTest.HostActivity> owner;
    private long nextPhoto = 41L;

    /**
     * TikTok's job as the patch leaves it: the hook runs first, and only when it lets the save
     * through does the job set its static post and start. The live-video flag reaches the hook
     * as an int, the way the patch hands it over.
     */
    public static final class Job {
        static final Job INSTANCE = new Job();
        static AdvancedDownloadsTest.PhotoPost post;
        /** What the job hands its gallery copy, as 47.7.3's keeps a static map for it. */
        static Map<String, Object> progress = new HashMap<>();
        static final List<String> RAN = new ArrayList<>();
        /** Set for a job that fails as it starts. */
        static boolean refuse;
        /** Whether the job last started on the main thread. */
        static volatile boolean ranOnMain;

        public static void save(Job job, String from, String key, Object extra, AdvancedDownloadsTest.PhotoPost aweme,
                Set<?> indices, Object share, boolean video, Object callback, int defaults) {
            if (OriginalPhotos.startPhotoJob(job, from, key, extra, aweme, indices, share, video ? 1 : 0,
                    callback, defaults)) return;
            if (refuse) throw new IllegalStateException("the job would not start");
            post = aweme;
            ranOnMain = Looper.myLooper() == Looper.getMainLooper();
            RAN.add(aweme.getAid() + " " + from + " " + defaults);
        }
    }

    /** The same job on a build where it's handed a real post, so a story reads as one. */
    public static final class StoryJob {
        static final StoryJob INSTANCE = new StoryJob();
        static Aweme post;
        static Map<String, Object> progress = new HashMap<>();

        public static void save(StoryJob job, String from, String key, Object extra, Aweme aweme,
                Set<?> indices, Object share, boolean video, Object callback, int defaults) {
            if (OriginalPhotos.startPhotoJob(job, from, key, extra, aweme, indices, share, video ? 1 : 0,
                    callback, defaults)) return;
            post = aweme;
            Job.RAN.add(aweme.getAid() + " " + from + " " + defaults);
        }
    }

    /**
     * TikTok's gallery copy, holding what it was handed: the job's static map for the job's own
     * copy, or a map of its own for the album copies a DM or a share runs.
     */
    static final class Copy {
        private final Map<?, ?> progress;
        private final Set<?> chosen = Set.of(0L);

        Copy(Map<?, ?> progress) { this.progress = progress; }

        @Override public String toString() { return "copy of " + progress + " " + chosen; }
    }

    /** A photo story, as TikTok's own post says it is one. */
    public static final class Story extends Aweme {
        private final String aid;

        Story(String aid) { this.aid = aid; }

        @Override public String getAid() { return aid; }
        @Override public boolean getIsTikTokStory() { return true; }
        @Override public int getAwemeType() { return 0; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
    }

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Utils.awaitBackgroundTasksForTests();
        SavedVideoArchiveAccess.reset(context);
        SaveRecordsTest.RecordingMediaProvider.reset();
        Robolectric.setupContentProvider(SaveRecordsTest.RecordingMediaProvider.class, "media");
        // Robolectric loads SQLite on the first open. Doing it here keeps that out of the five
        // seconds a background wait allows, where two pool threads would otherwise share it.
        assertNull(SavedVideoArchive.find(context, "warm-up"));
        NativePhotoSaves.resetForTests();
        Job.post = null;
        Job.RAN.clear();
        Job.refuse = false;
        Job.ranOnMain = false;
        StoryJob.post = null;
        previousOriginals = Settings.DOWNLOAD_ORIGINAL_PHOTOS.savedValue();
        previousCheck = Settings.CHECK_SAVED_VIDEOS.savedValue();
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        // Neither of Hushfeed's photo saves takes it, so every save here stays TikTok's.
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(false);
        Settings.CHECK_SAVED_VIDEOS.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        Utils.setActivity(owner.get());
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            settle();
        } finally {
            Utils.setActivity(null);
            owner.close();
            NativePhotoSaves.resetForTests();
            Job.refuse = false;
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(previousOriginals);
            Settings.CHECK_SAVED_VIDEOS.save(previousCheck);
            SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
            SavedVideoArchiveAccess.reset(context);
            SaveRecordsTest.RecordingMediaProvider.reset();
        }
    }

    @Test public void aNewPostIsCheckedThenSavedByTikTokWithEverythingItWasHanded() throws Exception {
        save(post("new-post"));
        assertTrue("the save started before the record was read", Job.RAN.isEmpty());
        settle();
        assertEquals("the held job never started again, or lost its arguments", List.of("new-post feed 384"), Job.RAN);
        assertNull("a new post was offered as already saved", ShadowDialog.getLatestDialog());
    }

    @Test public void theFirstGalleryCopyIsRecordedAndMarked() throws Exception {
        save(post("recorded"));
        settle();
        copiedByJob(landed(photo("recorded-1.jpeg"), photo("recorded-2.jpeg")));
        settle();

        MediaFileWriter.Saved found = SavedVideoArchive.find(context, "recorded");
        assertNotNull("TikTok's own photo save never reached the record", found);
        assertEquals("Open should go to the first photo that landed", "recorded-1.jpeg", found.name);
        assertTrue("the mark skipped TikTok's own photo save", marked("recorded"));
    }

    @Test public void aSecondSaveOffersOpenOrSaveAgainWordedForAPhotoPost() throws Exception {
        save(post("again"));
        settle();
        copiedByJob(landed(photo("again-1.jpeg")));
        settle();

        save(post("again"));
        settle();
        assertEquals("the photos were saved a second time with no word", 1, Job.RAN.size());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("no already-saved choice came up", dialog);
        assertEquals("Save again", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals("This photo post is already saved", Shadows.shadowOf(dialog).getTitle().toString());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals("Save again hands the save back to TikTok", 2, Job.RAN.size());
        assertTrue("a save that came on the main thread left it", Job.ranOnMain);
    }

    @Test public void aStorySavedAgainIsCalledAStory() throws Exception {
        Story story = new Story("story-again");
        saveStory(story);
        settle();
        assertEquals(List.of("story-again feed 384"), Job.RAN);
        NativePhotoSaves.copyEnding(new Copy(StoryJob.progress));
        NativePhotoSaves.copied(landed(photo("story-1.jpeg")));
        settle();
        assertNotNull("TikTok's own story save never reached the record", SavedVideoArchive.find(context, "story-again"));

        saveStory(story);
        settle();
        assertEquals("the story was saved a second time with no word", 1, Job.RAN.size());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("no already-saved choice came up", dialog);
        assertEquals("This story is already saved", Shadows.shadowOf(dialog).getTitle().toString());
    }

    @Test public void aSaveThatCameOffTheMainThreadStartsAgainOffIt() throws Exception {
        assertTrue(Utils.runOnBackgroundThread(() -> save(post("worker"))));
        settle();
        assertFalse(Job.ranOnMain);
        copiedByJob(landed(photo("worker.jpeg")));
        settle();

        assertTrue(Utils.runOnBackgroundThread(() -> save(post("worker"))));
        settle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("no already-saved choice came up", dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals(2, Job.RAN.size());
        assertFalse("Save again ran TikTok's download on the main thread", Job.ranOnMain);
    }

    @Test public void aCopyThatLandsAfterTheSaveWasForgottenIsNotRecorded() throws Exception {
        save(post("late"));
        settle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NativePhotoSaves.PENDING_MILLIS + 1));
        copiedByJob(landed(photo("late.jpeg")));
        settle();
        assertNull("a copy long after the save was taken for it", SavedVideoArchive.find(context, "late"));
        assertNull(NativePhotoSaves.pendingForTests("late"));
    }

    @Test public void aCopyOfAnotherPostIsNotRecordedUnderThisOne() throws Exception {
        save(post("held"));
        settle();
        Job.post = post("someone-else");
        copiedByJob(landed(photo("other.jpeg")));
        settle();
        assertNull(SavedVideoArchive.find(context, "held"));
        assertNotNull("a copy that wasn't this save's used up its record", NativePhotoSaves.pendingForTests("held"));
    }

    @Test public void anAlbumCopyFromADmOrAShareIsNotTakenForTheJobs() throws Exception {
        save(post("held-for-job"));
        settle();
        // A DM's or a share's album copy lands while the job's post is still this save's.
        NativePhotoSaves.copyEnding(new Copy(Map.of("dm-photo", true)));
        NativePhotoSaves.copied(landed(photo("from-a-chat.jpeg")));
        NativePhotoSaves.copied(landed(photo("no-copy-handed.jpeg")));
        settle();
        assertNull("another copy's photo was recorded as this save", SavedVideoArchive.find(context, "held-for-job"));
        assertNotNull("another copy used up this save's record", NativePhotoSaves.pendingForTests("held-for-job"));

        copiedByJob(landed(photo("job-1.jpeg")));
        settle();
        MediaFileWriter.Saved found = SavedVideoArchive.find(context, "held-for-job");
        assertNotNull(found);
        assertEquals("job-1.jpeg", found.name);
    }

    @Test public void aCopyThatLandsWithTheQueueFullIsStillRecorded() throws Exception {
        save(post("queue-full"));
        settle();
        Uri first = photo("queue-full.jpeg");
        Thread copy = new Thread(() -> copiedByJob(landed(first)));
        try (BackgroundPoolSaturation full = BackgroundPoolSaturation.fill()) {
            copy.start();
            copy.join(5000);
            assertFalse(copy.isAlive());
            assertNull(NativePhotoSaves.pendingForTests("queue-full"));
        }
        settle();
        MediaFileWriter.Saved found = SavedVideoArchive.find(context, "queue-full");
        assertNotNull("a full queue lost TikTok's photo save", found);
        assertEquals("queue-full.jpeg", found.name);
    }

    @Test public void twoSavesBeforeEitherCopyLandsAreBothRecorded() throws Exception {
        save(post("first"));
        settle();
        save(post("second"));
        settle();
        assertEquals(2, Job.RAN.size());

        Job.post = post("first");
        copiedByJob(landed(photo("first.jpeg")));
        Job.post = post("second");
        copiedByJob(landed(photo("second.jpeg")));
        settle();
        assertNotNull("the later save pushed out the earlier one's record", SavedVideoArchive.find(context, "first"));
        assertNotNull(SavedVideoArchive.find(context, "second"));
    }

    @Test public void aCopyWithNothingInItKeepsTheSaveWaiting() throws Exception {
        save(post("empty"));
        settle();
        copiedByJob(new LinkedHashSet<>());
        copiedByJob(null);
        settle();
        assertNull(SavedVideoArchive.find(context, "empty"));

        copiedByJob(landed(photo("empty.jpeg")));
        settle();
        assertNotNull("an empty copy used up the save's record", SavedVideoArchive.find(context, "empty"));
    }

    @Test public void aSaveWhoseCopyNeverCameIsForgottenInTime() throws Exception {
        save(post("stale"));
        settle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NativePhotoSaves.PENDING_MILLIS + 1));
        save(post("later"));
        settle();
        assertNull("a save TikTok never finished still waits for a copy", NativePhotoSaves.pendingForTests("stale"));
        assertNotNull(NativePhotoSaves.pendingForTests("later"));
    }

    @Test public void aRecordThatCannotBeReadLetsTikTokSaveAnyway() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                context.getDatabasePath(SavedVideoArchive.DATABASE_NAME).getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("DROP TABLE saved_videos");
        }
        save(post("unreadable"));
        settle();
        assertEquals("a broken record cost TikTok's own save", List.of("unreadable feed 384"), Job.RAN);
        assertNull("TikTok's save went ahead, so nothing failed", ShadowToast.getTextOfLatestToast());
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test public void aJobThatFailsToStartAgainSaysSoAndLeavesNothingWaiting() throws Exception {
        Job.refuse = true;
        save(post("refused"));
        settle();
        assertTrue(Job.RAN.isEmpty());
        assertEquals("None of the photos could be saved. Try again.", ShadowToast.getTextOfLatestToast());
        assertNull(NativePhotoSaves.pendingForTests("refused"));
    }

    @Test public void aPostTheIdsRuleOutStartsAtOnce() throws Exception {
        assertFalse("the record's ids didn't load", marked("unknown"));
        save(post("fresh"));
        assertEquals("a post the record can't hold waited for the check", 1, Job.RAN.size());
        copiedByJob(landed(photo("fresh.jpeg")));
        settle();
        assertNotNull(SavedVideoArchive.find(context, "fresh"));
    }

    @Test public void withTheCheckOffTikTokSavesAndNothingIsRecorded() throws Exception {
        Settings.CHECK_SAVED_VIDEOS.save(false);
        save(post("unchecked"));
        assertEquals(1, Job.RAN.size());
        copiedByJob(landed(photo("unchecked.jpeg")));
        settle();
        assertNull(SavedVideoArchive.find(context, "unchecked"));
    }

    @Test public void aGalleryUriIsKeptWithItsOwnName() throws Exception {
        Uri named = photo("named.jpeg");
        MediaFileWriter.Saved saved = NativePhotoSaves.saved(context, named);
        assertEquals(named, saved.uri);
        assertEquals("named.jpeg", saved.name);
        Uri unnamed = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 9000L);
        assertEquals("a row MediaStore won't name falls back to the Uri's own last part",
                "9000", NativePhotoSaves.saved(context, unnamed).name);
    }

    @Test public void theJobIsFoundByItsShape() {
        Object[] arguments = {Job.INSTANCE, "feed", "key", null, post("shape"), Set.of(0L), null, false, null, 384};
        assertNotNull(NativePhotoSaves.entry(Job.class, arguments));
        assertNotNull(NativePhotoSaves.postField(Job.class, arguments[4]));
        assertNull("a post of another type isn't the job's", NativePhotoSaves.entry(Job.class,
                new Object[]{Job.INSTANCE, "feed", "key", null, "not a post", Set.of(0L), null, false, null, 384}));
    }

    private static void save(AdvancedDownloadsTest.PhotoPost post) {
        Job.save(Job.INSTANCE, "feed", "key", null, post, Set.of(0L), null, false, null, 384);
    }

    private static void saveStory(Story story) {
        StoryJob.save(StoryJob.INSTANCE, "feed", "key", null, story, Set.of(0L), null, false, null, 384);
    }

    private boolean marked(String id) throws Exception {
        SavedVideoArchive.resetForTests();
        SavedVideoArchive.readIdsLater();
        Utils.awaitBackgroundTasksForTests();
        return SavedVideoArchive.isSaved(id);
    }

    /** A photo in the gallery, as MediaStore holds it: a row with its name, and its bytes. */
    private Uri photo(String name) throws IOException {
        long id = nextPhoto++;
        SaveRecordsTest.RecordingMediaProvider.add(id, name, 0, "Pictures/TikTok/");
        File bytes = new File(context.getCacheDir(), "recorded-media-" + id);
        try (FileOutputStream out = new FileOutputStream(bytes)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    /** The job's own gallery copy returning {@code landed}, as the patch reports it. */
    private static void copiedByJob(Object landed) {
        NativePhotoSaves.copyEnding(new Copy(Job.progress));
        NativePhotoSaves.copied(landed);
    }

    /** What TikTok's gallery copy hands back: the Uris that landed, in order. */
    private static Set<Uri> landed(Uri... uris) {
        Set<Uri> set = new LinkedHashSet<>();
        for (Uri uri : uris) set.add(uri);
        return set;
    }

    private static AdvancedDownloadsTest.PhotoPost post(String id) {
        List<AdvancedDownloadsTest.Photo> images = new ArrayList<>();
        images.add(new AdvancedDownloadsTest.Photo("https://example.com/" + id));
        return new AdvancedDownloadsTest.PhotoPost(id, images);
    }

    /** Until the background tasks and the main looper both have nothing left. */
    private static void settle() throws Exception {
        for (int round = 0; round < 5; round++) {
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
