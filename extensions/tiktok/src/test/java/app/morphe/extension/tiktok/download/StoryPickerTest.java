/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.ListView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A story save with Pick which stories to save on (#393): a checklist of the person's loaded
 * stories with the one on screen checked, the count still to load in its title, one pick saved
 * the way a single save is, several as one run, and the old single save whenever the switch is
 * off or the person has one story.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en")
public class StoryPickerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** One story as TikTok's list holds it: its id, when it went up, and photos when it's a photo story. */
    public static final class Story {
        private final String aid;
        private final long createTime;
        private final Object photos;

        Story(String aid, long createTime, Object photos) {
            this.aid = aid;
            this.createTime = createTime;
            this.photos = photos;
        }

        public String getAid() { return aid; }
        public long getCreateTime() { return createTime; }
        public Object getPhotoModeImageInfo() { return photos; }
        public int getAwemeType() { return photos == null ? 0 : 152; }
        @Override public String toString() { return "story " + aid; }
    }

    /** The person's list: the stories loaded so far, and how many they have in all. */
    public static final class UserStory {
        private final List<Object> stories;
        private final int total;

        UserStory(int total, Object... stories) {
            this.stories = new ArrayList<>(Arrays.asList(stories));
            this.total = total;
        }

        public List<Object> getStories() { return stories; }
        public int getTotalCount() { return total; }
    }

    /** The model a story play area binds, one per person, its id the person's own. */
    public static final class Person {
        private final String uid;
        private final UserStory userStory;

        Person(String uid, UserStory userStory) {
            this.uid = uid;
            this.userStory = userStory;
        }

        public String getAid() { return uid; }
        public UserStory getUserStory() { return userStory; }
    }

    /** The story hold's monitor, its release asking the checklist first as the patch has it do. */
    public static final class Monitor {
        int playedOn;

        public void release() {
            if (StoryPicker.holdRelease(this, "release")) return;
            playedOn++;
        }
    }

    private final List<Object> components = new ArrayList<>();
    private Activity activity;

    @Before public void setUp() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        Utils.setContext(activity.getApplicationContext());
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_STORY_PICK.save(true);
        SaveNotice.windowRootsForTests = List.of();
        SaveRecordsFixtures.reset(activity.getApplicationContext());
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        AlertDialog open = ShadowAlertDialog.getLatestAlertDialog();
        if (open != null) open.dismiss();
        BatchSave.saverForTests = null;
        SaveRecordsFixtures.reset(activity.getApplicationContext());
        SaveNotice.windowRootsForTests = null;
        Settings.SAVE_STORY_PICK.save(false);
        Settings.DOWNLOAD_VIDEO_QUALITY.resetToDefault();
        Settings.REMOVE_DOWNLOAD_WATERMARK.resetToDefault();
        SettingsStatus.advancedDownloadsEnabled = false;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Binds a person's model the way a story play area does. */
    private Person bind(String uid, UserStory list) {
        Person person = new Person(uid, list);
        Object component = new Object();
        components.add(component);
        StoryDownloads.recordStory(component, components.size(), person);
        return person;
    }

    private static Story story(String id) {
        return new Story(id, 0, null);
    }

    /** Opens the checklist from a story save and hands it back, its rows laid out. */
    private AlertDialog open(Story onScreen) {
        assertTrue(StoryDownloads.saveStory(activity, onScreen));
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the save went ahead without the checklist", dialog);
        assertTrue(dialog.isShowing());
        ListView list = dialog.getListView();
        for (int i = 0; i < list.getCount(); i++) list.getAdapter().getView(i, null, list);
        return dialog;
    }

    private static void tapRow(AlertDialog dialog, int row) {
        ListView list = dialog.getListView();
        assertTrue(list.performItemClick(list.getAdapter().getView(row, null, list), row, row));
        idle();
    }

    private static void tap(AlertDialog dialog, int which) {
        assertTrue(dialog.getButton(which).performClick());
        idle();
    }

    /** Collects the stories a run is handed, off the main thread, until {@code count} arrive. */
    private static List<Object> collect(CountDownLatch done) {
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        BatchSave.saverForTests = (app, story, last) -> {
            assertFalse("a story was saved on the main thread", Utils.isCurrentlyOnMainThread());
            seen.add(story);
            done.countDown();
            return BatchSave.Result.SAVED;
        };
        return seen;
    }

    private static String said() throws Exception {
        SaveRecordsFixtures.drain();
        idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
        return ShadowToast.getTextOfLatestToast();
    }

    @Test public void theListIsTheBoundOneThatHoldsTheStoryOnScreen() {
        Story first = story("a1");
        Story second = story("a2");
        Story third = story("a3");
        Person other = new Person("u-other", new UserStory(2, story("b1"), story("b2")));
        // A story the server sent without an id has nothing to save by and stays off the list.
        Person owner = new Person("u-owner", new UserStory(22, first, story(null), second, third));

        StoryPicker.Stories found = StoryPicker.find(second, Arrays.asList(other, owner));
        assertEquals(Arrays.asList(first, second, third), found.items);
        assertEquals(1, found.current);
        assertEquals(22, found.total);

        assertSame(StoryPicker.Stories.NONE, StoryPicker.find(story("a9"), Arrays.asList(other, owner)));
        assertSame(StoryPicker.Stories.NONE, StoryPicker.find(story(null), Arrays.asList(other, owner)));
        assertSame(StoryPicker.Stories.NONE, StoryPicker.find(second, null));

        // TikTok's count lagging behind its own list never says fewer than are loaded.
        Person stale = new Person("u-stale", new UserStory(1, story("c1"), story("c2")));
        assertEquals(2, StoryPicker.find(story("c2"), Collections.singletonList(stale)).total);
    }

    @Test public void theChecklistOpensWithOnlyTheStoryOnScreenChecked() {
        Story onScreen = story("d2");
        long now = System.currentTimeMillis();
        Story photos = new Story("d3", now / 1000 - 2 * 3600, new Object());
        bind("u-d", new UserStory(22, story("d1"), onScreen, photos));

        AlertDialog dialog = open(onScreen);
        ListView list = dialog.getListView();
        assertEquals(3, list.getCount());
        assertFalse(list.isItemChecked(0));
        assertTrue(list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));
        assertEquals("Pick stories to save\n3 of 22 stories are loaded. Watch further to load the rest.",
                Shadows.shadowOf(dialog).getTitle().toString());

        assertEquals("Story 1", list.getAdapter().getItem(0).toString());
        assertEquals("Story 2 · On screen", list.getAdapter().getItem(1).toString());
        String photoRow = list.getAdapter().getItem(2).toString();
        assertTrue(photoRow, photoRow.startsWith("Story 3 · "));
        assertTrue(photoRow, photoRow.endsWith(" · Photos"));
        assertTrue("the row says nothing of when it went up: " + photoRow, photoRow.contains("2"));
    }

    @Test public void everyStoryLoadedLeavesTheTitleAtItsQuestion() {
        Story onScreen = story("e1");
        bind("u-e", new UserStory(2, onScreen, story("e2")));
        assertEquals("Pick stories to save", Shadows.shadowOf(open(onScreen)).getTitle().toString());
    }

    @Test public void severalPicksAreOneRunInTheViewersOrder() throws Exception {
        Story first = story("f1");
        Story onScreen = story("f2");
        Story third = story("f3");
        bind("u-f", new UserStory(3, first, onScreen, third));
        CountDownLatch done = new CountDownLatch(2);
        List<Object> seen = collect(done);

        AlertDialog dialog = open(onScreen);
        tapRow(dialog, 2);
        tap(dialog, DialogInterface.BUTTON_POSITIVE);

        assertFalse(dialog.isShowing());
        assertTrue("the run never saved its stories", done.await(10, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(onScreen, third), seen);
        assertEquals("Saved 2 videos", said());
    }

    @Test public void pickAllChecksEveryRowAndWaitsForSave() throws Exception {
        Story first = story("g1");
        Story onScreen = story("g2");
        Story third = story("g3");
        bind("u-g", new UserStory(3, first, onScreen, third));
        CountDownLatch done = new CountDownLatch(3);
        List<Object> seen = collect(done);

        AlertDialog dialog = open(onScreen);
        tap(dialog, DialogInterface.BUTTON_NEUTRAL);
        assertTrue("Pick all closed the checklist", dialog.isShowing());
        ListView list = dialog.getListView();
        for (int i = 0; i < 3; i++) assertTrue("row " + i, list.isItemChecked(i));
        assertTrue("Pick all saved before Save was tapped", seen.isEmpty());

        tap(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(first, onScreen, third), seen);
    }

    @Test public void saveWithNothingCheckedSaysSoAndStaysOpen() {
        Story onScreen = story("h1");
        bind("u-h", new UserStory(2, onScreen, story("h2")));
        BatchSave.saverForTests = (app, story, last) -> {
            throw new AssertionError("a run started with nothing picked");
        };

        AlertDialog dialog = open(onScreen);
        tapRow(dialog, 0);
        tap(dialog, DialogInterface.BUTTON_POSITIVE);

        assertTrue(dialog.isShowing());
        assertEquals("Pick at least one story", ShadowToast.getTextOfLatestToast());
        assertEquals(0, MediaJobScheduler.queuedJobs());
    }

    @Test public void onePickIsTheSingleSave() {
        Story onScreen = story("i1");
        bind("u-i", new UserStory(2, onScreen, story("i2")));
        BatchSave.saverForTests = (app, story, last) -> {
            throw new AssertionError("one pick went out as a run");
        };

        AlertDialog dialog = open(onScreen);
        tap(dialog, DialogInterface.BUTTON_POSITIVE);

        assertFalse(dialog.isShowing());
        // The single save had the story: this one carries no file, which it says.
        assertEquals("This story isn't available to save", ShadowToast.getTextOfLatestToast());
        assertEquals(0, MediaJobScheduler.queuedJobs());
    }

    @Test public void aSecondSaveWhileTheChecklistIsUpOpensNoOther() {
        Story onScreen = story("j1");
        bind("u-j", new UserStory(2, onScreen, story("j2")));
        AlertDialog dialog = open(onScreen);

        assertTrue(StoryDownloads.saveStory(activity, onScreen));
        idle();
        assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void theStoryStaysPausedUnderTheChecklistAndPlaysOnWhenItCloses() {
        Story onScreen = story("l1");
        bind("u-l", new UserStory(2, onScreen, story("l2")));
        Monitor monitor = new Monitor();
        monitor.release();
        assertEquals("a lift with no checklist up waited", 1, monitor.playedOn);

        // The hold opened the checklist with the finger down, so the lift comes while it's up.
        AlertDialog dialog = open(onScreen);
        monitor.release();
        assertEquals("the story played on under the checklist", 1, monitor.playedOn);
        tap(dialog, DialogInterface.BUTTON_NEUTRAL);
        assertEquals(1, monitor.playedOn);
        tap(dialog, DialogInterface.BUTTON_NEGATIVE);
        assertFalse(dialog.isShowing());
        assertEquals("the story stayed paused after the checklist closed", 2, monitor.playedOn);
        monitor.release();
        assertEquals("a later lift waited for a checklist that was gone", 3, monitor.playedOn);

        // From the share sheet there was no hold and no lift, so closing plays nothing on.
        Monitor untouched = new Monitor();
        AlertDialog shared = open(onScreen);
        tap(shared, DialogInterface.BUTTON_POSITIVE);
        assertFalse(shared.isShowing());
        assertEquals(0, untouched.playedOn);
        assertEquals(3, monitor.playedOn);
    }

    @Test public void switchOffOrOneStoryKeepsTheSingleSave() {
        Story alone = story("k1");
        bind("u-k", new UserStory(1, alone));
        assertTrue(StoryDownloads.saveStory(activity, alone));
        idle();
        assertNull("one story got a checklist", ShadowAlertDialog.getLatestAlertDialog());
        assertEquals("This story isn't available to save", ShadowToast.getTextOfLatestToast());

        ShadowToast.reset();
        Story onScreen = story("k2");
        bind("u-k2", new UserStory(2, onScreen, story("k3")));
        Settings.SAVE_STORY_PICK.save(false);
        assertTrue(StoryDownloads.saveStory(activity, onScreen));
        idle();
        assertNull("the switch is off", ShadowAlertDialog.getLatestAlertDialog());
        assertEquals("This story isn't available to save", ShadowToast.getTextOfLatestToast());

        Settings.SAVE_STORY_PICK.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        assertFalse("without advanced downloads in the build", StoryPicker.enabled());
    }

    @Test public void aStoryInARunTakesTheFileASingleStorySaveWould() {
        AdvancedDownloadsTest.Gear hevc = new AdvancedDownloadsTest.Gear("adapt_lowest_1080_1", 400, "https://example.com/1080-hevc").codec(1);
        AdvancedDownloadsTest.Gear vc2 = new AdvancedDownloadsTest.Gear("adapt_lower_720_2", 900, "https://example.com/720-bvc2").codec(2);
        QualityChoiceTest.VideoPost post = new QualityChoiceTest.VideoPost("l1", vc2, hevc);
        post.video.downloadNoWatermarkAddr = new AdvancedDownloadsTest.Address("https://example.com/clean-720", 9000).frame(720, 1280);

        BatchSave.Source auto = BatchSave.storySource(post.video, "auto", false);
        assertEquals(Collections.singletonList("https://example.com/1080-hevc"), auto.video);
        assertTrue(auto.audio.isEmpty());
        assertFalse(auto.stripSound);
        assertTrue("a save without sound kept it", BatchSave.storySource(post.video, "auto", true).stripSound);

        // With the watermark kept a post's Automatic save is TikTok's file; a story's is still its tallest.
        Settings.REMOVE_DOWNLOAD_WATERMARK.save(false);
        assertEquals(Collections.singletonList("https://example.com/1080-hevc"),
                BatchSave.storySource(post.video, "auto", false).video);
        Settings.REMOVE_DOWNLOAD_WATERMARK.resetToDefault();

        // A chosen quality is the same choice a post's save makes.
        assertEquals(BatchSave.source(post.video, "720", false).video,
                BatchSave.storySource(post.video, "720", false).video);

        // Nothing listed but the address the story carries: that address.
        QualityChoiceTest.VideoPost bare = new QualityChoiceTest.VideoPost("l2");
        assertEquals(Collections.singletonList("https://example.com/clean"),
                BatchSave.storySource(bare.video, "auto", false).video);
    }
}
