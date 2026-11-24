/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Remember saved videos (#516): the reader's own Favorites are read as the grid gets them, a
 * video that later comes back unavailable is listed as gone with what was kept, and nobody
 * else's Favorites are read at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SavedVideoMemoryTest {
    private static final String ME = "me";

    /** A video as TikTok's list hands it over, by the names the model has. */
    public static final class Video {
        public String aid;
        public Author author;
        public String desc;
        public long createTime;
        public Status status;
        public Boolean canPlay;
        public Media video;

        public String getAid() {
            return aid;
        }
    }

    public static final class Author {
        public String nickname;
        public String uniqueId;
    }

    public static final class Status {
        public boolean isDelete;
        public boolean isProhibited;
        public boolean isSelfSee;
    }

    public static final class Media {
        public Address cover;
    }

    public static final class Address {
        public List<String> urlList;
    }

    private boolean favoritesJumpEnabled;
    private Object grid;

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        favoritesJumpEnabled = SettingsStatus.favoritesJumpEnabled;
        SettingsStatus.favoritesJumpEnabled = true;
        SignedInUser.idForTests = ME;
        Settings.REMEMBER_SAVED_VIDEOS.save(true);
        SavedVideoMemory.resetForTests();
        grid = new Object();
        GridSort.tab(grid, GridSort.TAB_FAVORITES);
    }

    @After public void tearDown() throws Exception {
        SavedVideoMemory.resetForTests();
        Settings.REMEMBER_SAVED_VIDEOS.resetToDefault();
        SettingsStatus.favoritesJumpEnabled = favoritesJumpEnabled;
        SignedInUser.idForTests = null;
        SignedInUser.secIdForTests = null;
        HookStatus.clear();
        GridSort.resetForTests();
    }

    private static Video video(String aid, String name, String handle, String caption) {
        Video video = new Video();
        video.aid = aid;
        video.author = new Author();
        video.author.nickname = name;
        video.author.uniqueId = handle;
        video.desc = caption;
        video.createTime = 1_700_000_000L;
        video.canPlay = true;
        return video;
    }

    /** The same place in the list after its creator deleted the video: an id and a mark, nothing else. */
    private static Video deleted(String aid) {
        Video video = new Video();
        video.aid = aid;
        video.status = new Status();
        video.status.isDelete = true;
        return video;
    }

    private void myFavorites(boolean fresh, Video... videos) throws Exception {
        SavedVideoMemory.requested(ME, null, GridSort.TAB_FAVORITES);
        SavedVideoMemory.listIn(grid, Arrays.asList(videos), fresh);
        settle();
    }

    private static void settle() throws Exception {
        SavedVideoMemory.drainForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static List<String> gone() throws Exception {
        settle();
        List<String> aids = new ArrayList<>();
        for (SavedVideoMemory.Entry row : SavedVideoMemory.readGone(ME)) aids.add(row.aid);
        return aids;
    }

    @Test public void theSwitchIsOffByDefault() {
        assertEquals("remember_saved_videos", Settings.REMEMBER_SAVED_VIDEOS.key);
        assertFalse(Settings.REMEMBER_SAVED_VIDEOS.defaultValue);
    }

    @Test public void aSavedVideoThatComesBackDeletedIsListedWithWhatWasKept() throws Exception {
        myFavorites(true, video("1", "Ann", "ann", "the first one"), video("2", "Bob", "bob", "the second"));
        assertEquals(new ArrayList<String>(), gone());
        assertNull(SavedVideoMemory.goneFor("1"));

        myFavorites(true, deleted("1"), video("2", "Bob", "bob", "the second"));

        assertEquals(Arrays.asList("1"), gone());
        SavedVideoMemory.Entry kept = SavedVideoMemory.readGone(ME).get(0);
        assertEquals("Ann", kept.creator);
        assertEquals("ann", kept.handle);
        assertEquals("the first one", kept.caption);
        assertEquals(1_700_000_000_000L, kept.postedMs);
        assertTrue(kept.goneMs >= kept.rememberedMs && kept.rememberedMs > 0);
        // The grid's tile draws from this without touching the disk.
        assertNotNull(SavedVideoMemory.goneFor("1"));
        assertNull(SavedVideoMemory.goneFor("2"));
    }

    @Test public void aLaterPageIsReadTooAndOnlyItsNewVideos() throws Exception {
        Video first = video("1", "Ann", "ann", "one");
        myFavorites(true, first);
        // The grown list: the first page is the same videos, so only the second is read.
        first.author.nickname = "Changed";
        myFavorites(false, first, video("2", "Bob", "bob", "two"));

        assertEquals("Ann", SavedVideoMemory.find(ME, "1").creator);
        assertEquals("Bob", SavedVideoMemory.find(ME, "2").creator);
    }

    @Test public void aVideoThatCanNoLongerPlayCountsAsGoneAndAPrivateOneOfMineDoesNot() {
        Video unplayable = video("1", "Ann", "ann", "");
        unplayable.canPlay = false;
        assertTrue(SavedVideoMemory.unavailable(unplayable));

        Video takenDown = video("2", "Ann", "ann", "");
        takenDown.status = new Status();
        takenDown.status.isProhibited = true;
        assertTrue(SavedVideoMemory.unavailable(takenDown));

        Video onlyMe = video("3", "Me", "me", "");
        onlyMe.status = new Status();
        onlyMe.status.isSelfSee = true;
        assertFalse(SavedVideoMemory.unavailable(onlyMe));
        assertFalse(SavedVideoMemory.unavailable(video("4", "Ann", "ann", "")));
        assertTrue(SavedVideoMemory.unavailable(deleted("5")));
    }

    @Test public void aVideoThatComesBackIsNoLongerGone() throws Exception {
        myFavorites(true, video("1", "Ann", "ann", "one"));
        myFavorites(true, deleted("1"));
        assertEquals(Arrays.asList("1"), gone());

        myFavorites(true, video("1", "Ann", "ann", "one, public again"));

        assertEquals(new ArrayList<String>(), gone());
        assertNull(SavedVideoMemory.goneFor("1"));
        assertEquals("one, public again", SavedVideoMemory.find(ME, "1").caption);
    }

    @Test public void aVideoThatWasAlreadyGoneHasNothingToShow() throws Exception {
        myFavorites(true, deleted("9"));

        assertEquals(new ArrayList<String>(), gone());
        assertNull(SavedVideoMemory.find(ME, "9"));
        assertNull(SavedVideoMemory.goneFor("9"));
    }

    @Test public void someoneElsesFavoritesAreNotRead() throws Exception {
        SavedVideoMemory.requested("somebody", "sec-somebody", GridSort.TAB_FAVORITES);
        SavedVideoMemory.listIn(grid, Arrays.asList(video("1", "Ann", "ann", "one")), true);
        settle();

        assertNull(SavedVideoMemory.find(ME, "1"));
    }

    @Test public void myFavoritesAreKnownByTheSecureIdToo() throws Exception {
        SignedInUser.secIdForTests = "sec-me";
        SavedVideoMemory.requested("", "sec-me", GridSort.TAB_FAVORITES);
        SavedVideoMemory.listIn(grid, Arrays.asList(video("1", "Ann", "ann", "one")), true);
        settle();

        assertNotNull(SavedVideoMemory.find(ME, "1"));
    }

    @Test public void otherTabsAndASwitchedOffMemoryReadNothing() throws Exception {
        Object liked = new Object();
        GridSort.tab(liked, GridSort.TAB_LIKED);
        SavedVideoMemory.requested(ME, null, GridSort.TAB_FAVORITES);
        SavedVideoMemory.listIn(liked, Arrays.asList(video("1", "Ann", "ann", "one")), true);
        settle();
        assertNull(SavedVideoMemory.find(ME, "1"));

        Settings.REMEMBER_SAVED_VIDEOS.save(false);
        myFavorites(true, video("2", "Bob", "bob", "two"));
        assertNull(SavedVideoMemory.find(ME, "2"));

        Settings.REMEMBER_SAVED_VIDEOS.save(true);
        SettingsStatus.favoritesJumpEnabled = false;
        myFavorites(true, video("3", "Cam", "cam", "three"));
        assertNull(SavedVideoMemory.find(ME, "3"));
    }

    @Test public void pastTheCapTheVideosStillThereGoBeforeTheGoneOnes() throws Exception {
        SavedVideoMemory.cap = 2;
        // The gone one is the longest remembered, which is the one a plain cap would drop.
        SavedVideoMemory.store(ME, Arrays.asList(SavedVideoMemory.read(video("1", "Ann", "ann", "one"), "1")), 1_000);
        SavedVideoMemory.store(ME, Arrays.asList(SavedVideoMemory.read(deleted("1"), "1")), 1_500);
        SavedVideoMemory.store(ME, Arrays.asList(SavedVideoMemory.read(video("2", "Bob", "bob", "two"), "2")), 2_000);
        SavedVideoMemory.store(ME, Arrays.asList(SavedVideoMemory.read(video("3", "Cam", "cam", "three"), "3")), 3_000);

        assertNotNull(SavedVideoMemory.find(ME, "1"));
        assertNull(SavedVideoMemory.find(ME, "2"));
        assertNotNull(SavedVideoMemory.find(ME, "3"));
    }

    @Test public void aGoneVideosTileShowsWhatWasKeptAndAReusedCellLosesIt() throws Exception {
        myFavorites(true, video("1", "Ann", "ann", "one"));
        myFavorites(true, deleted("1"));
        FrameLayout cell = new FrameLayout(RuntimeEnvironment.getApplication());

        SavedVideoTile.cell(cell, deleted("1"));

        View layer = cell.findViewWithTag(SavedVideoTile.LAYER_TAG);
        assertNotNull(layer);
        assertEquals("Unavailable, @ann", String.valueOf(layer.getContentDescription()));
        assertTrue(layer.hasOnClickListeners());

        // Bound again for the same video, the cell keeps the one layer.
        SavedVideoTile.cell(cell, deleted("1"));
        assertEquals(1, cell.getChildCount());

        SavedVideoTile.cell(cell, video("2", "Bob", "bob", "two"));
        assertNull(cell.findViewWithTag(SavedVideoTile.LAYER_TAG));

        // A gone video nothing was kept of stays TikTok's own tile, and so does every tile with the switch off.
        SavedVideoTile.cell(cell, deleted("9"));
        assertNull(cell.findViewWithTag(SavedVideoTile.LAYER_TAG));
        SavedVideoTile.cell(cell, deleted("1"));
        assertNotNull(cell.findViewWithTag(SavedVideoTile.LAYER_TAG));
        Settings.REMEMBER_SAVED_VIDEOS.save(false);
        SavedVideoTile.cell(cell, deleted("1"));
        assertNull(cell.findViewWithTag(SavedVideoTile.LAYER_TAG));
    }

    @Test public void aClearForgetsTheGoneOnesAndAnUndoBringsThemBack() throws Exception {
        myFavorites(true, video("1", "Ann", "ann", "one"), video("2", "Bob", "bob", "two"));
        myFavorites(true, deleted("1"), video("2", "Bob", "bob", "two"));
        List<SavedVideoMemory.Entry> cleared = new ArrayList<>();

        SavedVideoMemory.clear(cleared::addAll);

        assertEquals(new ArrayList<String>(), gone());
        assertEquals(1, cleared.size());
        assertNull(SavedVideoMemory.goneFor("1"));
        assertNotNull("a video that's still there stays remembered", SavedVideoMemory.find(ME, "2"));

        SavedVideoMemory.restore(cleared);

        assertEquals(Arrays.asList("1"), gone());
        assertEquals("Ann", SavedVideoMemory.readGone(ME).get(0).creator);
    }

    @Test public void aCoverIsKeptOnceAndSweptWhenItsVideoIsForgotten() throws Exception {
        int[] fetches = {0};
        SavedVideoMemory.fetcherForTests = (urls, target) -> {
            fetches[0]++;
            assertEquals(Arrays.asList("https://example.test/cover.jpeg"), urls);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(new byte[]{1, 2, 3});
            }
        };
        Video withCover = video("1", "Ann", "ann", "one");
        withCover.video = new Media();
        withCover.video.cover = new Address();
        withCover.video.cover.urlList = Arrays.asList("https://example.test/cover.jpeg");

        myFavorites(true, withCover);
        myFavorites(true, withCover);

        assertEquals(1, fetches[0]);
        assertTrue(SavedVideoMemory.coverFile("1").isFile());

        myFavorites(true, deleted("1"));
        SavedVideoMemory.clear(rows -> { });
        SavedVideoMemory.sweepCovers();
        settle();

        assertFalse(SavedVideoMemory.coverFile("1").exists());
    }

    @Test public void aCoverIsDecodedNoLargerThanATileNeeds() {
        assertEquals(1, SavedVideoMemory.sampleFor(360));
        assertEquals(1, SavedVideoMemory.sampleFor(719));
        assertEquals(2, SavedVideoMemory.sampleFor(720));
        assertEquals(2, SavedVideoMemory.sampleFor(1080));
        assertEquals(4, SavedVideoMemory.sampleFor(1440));
    }

    @Test public void aLongCaptionIsCutWithoutSplittingAnEmoji() {
        StringBuilder caption = new StringBuilder();
        for (int i = 0; i < SavedVideoMemory.CAPTION_CHARS - 1; i++) caption.append('a');
        caption.append("😀 and more");

        String kept = SavedVideoMemory.shorten(caption.toString());

        assertEquals(SavedVideoMemory.CAPTION_CHARS - 1, kept.length());
        assertEquals("", SavedVideoMemory.shorten(null));
        assertEquals("two\nlines", SavedVideoMemory.shorten("  two\nlines "));
    }
}
