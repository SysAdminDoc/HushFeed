/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A story watched while Ghost mode held its view back keeps a watched ring after a restart
 * (#427), the way TikTok shows it right after it plays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryReadsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final long NOW = 1_800_000_000_000L;

    @Before public void setUp() {
        SignedInUser.idForTests = "111";
        StoryReads.nowForTests = NOW;
        StoryReads.clearForTests();
        Settings.GHOST_MODE.save(true);
        HookStatus.clear();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_STORY_VIEWS.save(Settings.GHOST_HIDE_STORY_VIEWS.defaultValue);
        StoryReads.clearForTests();
        StoryReads.nowForTests = null;
        SignedInUser.idForTests = null;
        HookStatus.clear();
    }

    @Test public void aStoryGhostModeHeldBackHasAWatchedRingAfterARestart() {
        assertTrue(GhostMode.shouldBlockStoryStats(story("7001")));
        restart();

        User user = user(lite(7001, false));
        StoryReads.markWatched(user, null);
        assertTrue(user.metadata.storyLiteMetadata.get(0).viewed);
        assertEquals(StoryReads.ALL_WATCHED, user.storyStatus);
    }

    @Test public void aNewStoryBesideAWatchedOneKeepsTheRingLit() {
        GhostMode.shouldBlockStoryStats(story("7001"));
        User user = user(lite(7001, false), lite(7002, false));

        StoryReads.markWatched(user, null);
        assertTrue(user.metadata.storyLiteMetadata.get(0).viewed);
        assertFalse(user.metadata.storyLiteMetadata.get(1).viewed);
        assertEquals(StoryReads.UNWATCHED, user.storyStatus);
    }

    @Test public void aStoryTikTokLeavesOutOfTheRingDoesNotKeepItLit() {
        GhostMode.shouldBlockStoryStats(story("7001"));
        User user = user(lite(7001, false), lite(7003, false));
        FilterInfo filter = new FilterInfo();
        filter.filterStoryIds = new ArrayList<>(List.of(7003L));

        StoryReads.markWatched(user, filter);
        assertEquals(StoryReads.ALL_WATCHED, user.storyStatus);
    }

    @Test public void withTheStorySwitchOffTheViewIsReportedAndNothingIsKept() {
        Settings.GHOST_HIDE_STORY_VIEWS.save(false);
        assertFalse(GhostMode.shouldBlockStoryStats(story("7001")));

        User user = user(lite(7001, false));
        StoryReads.markWatched(user, null);
        assertEquals(StoryReads.UNWATCHED, user.storyStatus);
    }

    @Test public void anotherAccountStillSeesTheStoryUnwatched() {
        GhostMode.shouldBlockStoryStats(story("7001"));
        SignedInUser.idForTests = "222";

        User user = user(lite(7001, false));
        StoryReads.markWatched(user, null);
        assertEquals(StoryReads.UNWATCHED, user.storyStatus);
    }

    @Test public void aStoryWatchedMoreThanTwoDaysAgoIsForgotten() {
        GhostMode.shouldBlockStoryStats(story("7001"));
        StoryReads.nowForTests = NOW + StoryReads.KEEP_MS + 1;
        restart();

        User user = user(lite(7001, false));
        StoryReads.markWatched(user, null);
        assertEquals(StoryReads.UNWATCHED, user.storyStatus);
    }

    @Test public void aPausedProcessShowsWhatTikTokSent() {
        GhostMode.shouldBlockStoryStats(story("7001"));
        PausedProcess.set(true);
        User user = user(lite(7001, false));

        StoryReads.markWatched(user, null);
        assertEquals(StoryReads.UNWATCHED, user.storyStatus);
        assertFalse(user.metadata.storyLiteMetadata.get(0).viewed);
    }

    @Test public void aPersonItCannotReadKeepsTikToksRing() {
        GhostMode.shouldBlockStoryStats(story("7001"));

        StoryReads.markWatched(new Object(), new Object());
        StoryReads.markWatched(null, null);
    }

    /** A new process: the stories come back from the saved file. */
    private static void restart() {
        StoryReads.forgetLoadedForTests();
    }

    private static Aweme story(String aid) {
        return new Aweme() {
            @Override public boolean getIsTikTokStory() { return true; }
            @Override public int getAwemeType() { return 40; }
            @Override public String getAid() { return aid; }
        };
    }

    private static Lite lite(long id, boolean viewed) {
        Lite lite = new Lite();
        lite.itemID = id;
        lite.viewed = viewed;
        return lite;
    }

    private static User user(Lite... stories) {
        User user = new User();
        user.metadata.storyLiteMetadata = new ArrayList<>(Arrays.asList(stories));
        return user;
    }

    /** Stand-ins with the names TikTok's Aweme, User and story models use. */
    public static final class User {
        final AllMetadata metadata = new AllMetadata();
        int storyStatus = StoryReads.UNWATCHED;

        public AllMetadata getAllStoryMetadata() { return metadata; }

        public int getStoryStatus() { return storyStatus; }

        public void setStoryStatus(int status) { storyStatus = status; }
    }

    public static final class AllMetadata {
        public List<Lite> storyLiteMetadata;
    }

    public static final class Lite {
        public long itemID;
        public boolean viewed;

        public void setViewed(boolean viewed) { this.viewed = viewed; }
    }

    public static final class FilterInfo {
        public List<Long> filterStoryIds;
    }
}
