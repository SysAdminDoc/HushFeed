/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * TikTok Lite (#48): its For You response is its own FeedItemList class, with the same Aweme
 * items in a field named items, read through getItems() where the patch calls filterLiteOnRead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiteFeedFilterTest {
    /** Lite's FeedItemList as far as the filter sees it: some other class holding items. */
    public static final class LiteFeedItemList {
        List<Object> items;
    }

    private static AdsFilterTest.Video video(String aid, boolean ad) {
        AdsFilterTest.Video video = new AdsFilterTest.Video();
        video.aid = aid;
        video.ad = ad;
        return video;
    }

    private static List<String> aids(LiteFeedItemList list) {
        List<String> aids = new ArrayList<>();
        for (Object item : list.items) aids.add(((AdsFilterTest.Video) item).getAid());
        return aids;
    }

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG.save(false);
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @After
    public void tearDown() {
        Settings.REMOVE_ADS.resetToDefault();
        FeedItemsFilter.tiktokFeedItemListPresent = true;
        FeedItemsFilter.tiktokFollowFeedListPresent = true;
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    /** Lite has neither of TikTok's feed list classes, which the tests' own classpath does. */
    private static void asLite() {
        FeedItemsFilter.tiktokFeedItemListPresent = false;
        FeedItemsFilter.tiktokFollowFeedListPresent = false;
    }

    @Test
    public void withoutTikToksFeedClassesTheAdIsStillDropped() {
        asLite();
        Settings.REMOVE_ADS.save(true);
        LiteFeedItemList list = new LiteFeedItemList();
        list.items = new ArrayList<>(Arrays.asList(video("first", false), video("ad", true), video("last", false)));
        FeedItemsFilter.filterLiteOnRead(list);
        assertEquals(Arrays.asList("first", "last"), aids(list));
    }

    @Test
    public void aReadOnlyLiteListIsSwappedForTheKeptVideos() {
        asLite();
        Settings.REMOVE_ADS.save(true);
        LiteFeedItemList list = new LiteFeedItemList();
        list.items = List.of(video("ad", true), video("kept", false));
        FeedItemsFilter.filterLiteOnRead(list);
        assertEquals(Arrays.asList("kept"), aids(list));
    }

    @Test
    public void aListTheReaderOpenedKeepsEveryVideo() {
        asLite();
        Settings.REMOVE_ADS.save(true);
        LiteFeedItemList list = new LiteFeedItemList();
        list.items = new ArrayList<>(Arrays.asList(video("opened", true), video("next", false)));
        FeedItemsFilter.markLiteOpenedList(list);
        FeedItemsFilter.filterLiteOnRead(list);
        assertEquals(Arrays.asList("opened", "next"), aids(list));
    }

    @Test
    public void anAdInLitesForYouListIsDroppedOnReadAndTheVideosStay() {
        Settings.REMOVE_ADS.save(true);
        LiteFeedItemList list = new LiteFeedItemList();
        list.items = new ArrayList<>(Arrays.asList(video("first", false), video("ad", true), video("last", false)));
        FeedItemsFilter.filterLiteOnRead(list);
        assertEquals(Arrays.asList("first", "last"), aids(list));
    }

    @Test
    public void withHideAdsOffTheAdStays() {
        Settings.REMOVE_ADS.save(false);
        LiteFeedItemList list = new LiteFeedItemList();
        list.items = new ArrayList<>(Arrays.asList(video("first", false), video("ad", true)));
        FeedItemsFilter.filterLiteOnRead(list);
        assertEquals(Arrays.asList("first", "ad"), aids(list));
    }

    @Test
    public void aListWithoutItemsOrAnOwnerIsLeftAlone() throws ReflectiveOperationException {
        LiteFeedItemList empty = new LiteFeedItemList();
        FeedItemsFilter.filterLiteOnRead(empty);
        FeedItemsFilter.filterLiteOnRead(null);
        assertNull(empty.items);
        assertNull(FeedItemsFilter.liteItems(null));
    }
}
