package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Swipe LIVEs in your Following row's order (#511): the LIVE tapped in the row opens with the
 * row's other LIVEs named as the rooms to come next, after it in the row first, then before it,
 * and the pager it opens in doesn't get those rooms again from the server's later pages.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveRowOrderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void on() {
        Settings.LIVE_FOLLOWING_ROW_ORDER.save(true);
    }

    @After public void off() {
        Settings.LIVE_FOLLOWING_ROW_ORDER.save(false);
        LiveRowOrder.lineup = null;
    }

    @Test public void theRoomsAfterTheTappedLiveComeFirstThenTheOnesBeforeIt() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"), entry(13, "c"), entry(14, "d"));
        EnterRoomConfig config = tapped(row.get(2));

        LiveRowOrder.seed(config, row.get(2), row);

        assertEquals(Arrays.asList(14L, 11L, 12L), roomIds(config));
        EnterRoomConfig next = (EnterRoomConfig) config.mLogData.moreConfig.get(0);
        assertEquals("d", next.mRoomsData.userId);
        assertEquals("{\"id\":14}", next.mRoomsData.preLoadRoomInfo);
        assertEquals("homepage_follow", next.mRoomsData.enterFromMerge);
        assertEquals("live_cover", next.mRoomsData.enterMethod);
        assertEquals("click", next.mRoomsData.enterType);
        assertEquals("request-1", next.mLogData.requestId);
        // The tapped room itself stays TikTok's.
        assertEquals(13L, config.mRoomsData.roomId);
    }

    @Test public void theLastLiveInTheRowGoesBackToTheStart() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"), entry(13, "c"));
        EnterRoomConfig config = tapped(row.get(2));
        LiveRowOrder.seed(config, row.get(2), row);
        assertEquals(Arrays.asList(11L, 12L), roomIds(config));
    }

    @Test public void entriesThatArentLiveAndRepeatedRoomsAreLeftOut() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(0, "story"), entry(12, "b"), entry(11, "a"), entry(13, "c"));
        EnterRoomConfig config = tapped(row.get(0));
        LiveRowOrder.seed(config, row.get(0), row);
        assertEquals(Arrays.asList(12L, 13L), roomIds(config));
    }

    @Test public void aTappedLiveTheRowDoesntHoldIsFollowedByTheWholeRow() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"));
        Entry elsewhere = entry(20, "z");
        EnterRoomConfig config = tapped(elsewhere);
        LiveRowOrder.seed(config, elsewhere, row);
        assertEquals(Arrays.asList(11L, 12L), roomIds(config));
    }

    @Test public void offOrAloneTheConfigIsLeftAsTikTokMadeIt() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"));
        Settings.LIVE_FOLLOWING_ROW_ORDER.save(false);
        EnterRoomConfig config = tapped(row.get(0));
        LiveRowOrder.seed(config, row.get(0), row);
        assertNull(config.mLogData.moreConfig);

        Settings.LIVE_FOLLOWING_ROW_ORDER.save(true);
        List<Entry> alone = List.of(entry(11, "a"));
        EnterRoomConfig only = tapped(alone.get(0));
        LiveRowOrder.seed(only, alone.get(0), alone);
        assertNull(only.mLogData.moreConfig);
    }

    @Test public void aConfigThatAlreadyNamesTheNextRoomsKeepsThem() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"));
        EnterRoomConfig config = tapped(row.get(0));
        ArrayList<Object> own = new ArrayList<>(List.of(new EnterRoomConfig()));
        config.mLogData.moreConfig = own;
        LiveRowOrder.seed(config, row.get(0), row);
        assertSame(own, config.mLogData.moreConfig);
        assertEquals(1, own.size());
    }

    @Test public void thePagerTheTapOpenedLosesTheLinedUpRoomsFromItsPages() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"), entry(13, "c"));
        LiveRowOrder.seed(tapped(row.get(1)), row.get(1), row);
        Object pager = new Object();

        List<Item> page = Arrays.asList(item(13), item(40), item(11), item(12), item(41));
        assertEquals(Arrays.asList(40L, 41L), ids(LiveRowOrder.page(pager, page, "homepage_follow_live_cover_loadmore")));
        // Later pages of the same pager too, whatever their type.
        assertEquals(Arrays.asList(42L), ids(LiveRowOrder.page(pager, Arrays.asList(item(11), item(42)), "other")));
        // A page with nothing lined up comes back as it was.
        List<Item> clean = Arrays.asList(item(43), item(44));
        assertSame(clean, LiveRowOrder.page(pager, clean, "homepage_follow_live_cover_loadmore"));
    }

    @Test public void otherPagersKeepTheirPages() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"));
        LiveRowOrder.seed(tapped(row.get(0)), row.get(0), row);
        Object feed = new Object();
        Object opened = new Object();
        List<Item> page = Arrays.asList(item(11), item(12), item(40));

        // A pager of another kind loading first doesn't take the lineup.
        assertSame(page, LiveRowOrder.page(feed, page, "toplive_draw_loadmore"));
        assertEquals(Arrays.asList(40L), ids(LiveRowOrder.page(opened, page, "homepage_follow_live_cover_refresh")));
        // Once claimed, no other pager loses anything, row-typed or not.
        assertSame(page, LiveRowOrder.page(new Object(), page, "homepage_follow_live_cover_refresh"));
        assertSame(page, LiveRowOrder.page(feed, page, "toplive_draw_loadmore"));
    }

    @Test public void aTapWithTheSwitchOffOrAStaleTapLeavesPagesAlone() {
        List<Entry> row = Arrays.asList(entry(11, "a"), entry(12, "b"));
        List<Item> page = Arrays.asList(item(11), item(12), item(40));
        LiveRowOrder.seed(tapped(row.get(0)), row.get(0), row);
        Settings.LIVE_FOLLOWING_ROW_ORDER.save(false);
        LiveRowOrder.seed(tapped(row.get(1)), row.get(1), row);
        assertSame(page, LiveRowOrder.page(new Object(), page, "homepage_follow_live_cover_loadmore"));

        Settings.LIVE_FOLLOWING_ROW_ORDER.save(true);
        LiveRowOrder.seed(tapped(row.get(0)), row.get(0), row);
        LiveRowOrder.Lineup fresh = LiveRowOrder.lineup;
        LiveRowOrder.lineup = new LiveRowOrder.Lineup(fresh.rooms, fresh.at - LiveRowOrder.CLAIM_MS - 1);
        assertSame(page, LiveRowOrder.page(new Object(), page, "homepage_follow_live_cover_loadmore"));
    }

    private static List<Long> ids(List<?> page) {
        List<Long> ids = new ArrayList<>();
        for (Object item : page) ids.add(LiveRowOrder.roomIdOf(item));
        return ids;
    }

    private static Item item(long roomId) {
        return new Item(new Room(roomId));
    }

    /** A LIVE page's FeedItem, with its room. */
    public static final class Item {
        private final Room room;

        Item(Room room) {
            this.room = room;
        }

        public Room getRoom() {
            return room;
        }
    }

    public static final class Room {
        private final long id;

        Room(long id) {
            this.id = id;
        }

        public long getId() {
            return id;
        }
    }

    private static List<Long> roomIds(EnterRoomConfig config) {
        List<Long> ids = new ArrayList<>();
        for (Object more : config.mLogData.moreConfig) ids.add(((EnterRoomConfig) more).mRoomsData.roomId);
        return ids;
    }

    private static EnterRoomConfig tapped(Entry entry) {
        EnterRoomConfig config = new EnterRoomConfig();
        config.mRoomsData.roomId = entry.user.roomId;
        config.mRoomsData.userId = entry.user.uid;
        config.mRoomsData.enterFromMerge = "homepage_follow";
        config.mRoomsData.enterMethod = "live_cover";
        config.mRoomsData.enterType = "click";
        config.mLogData.requestId = "request-1";
        return config;
    }

    private static Entry entry(long roomId, String uid) {
        User user = new User();
        user.roomId = roomId;
        user.uid = uid;
        user.roomData = roomId == 0 ? null : "{\"id\":" + roomId + "}";
        return new Entry(user);
    }

    /** TikTok's FollowingInterestUser: the row entry, with its user. */
    static final class Entry {
        final User user;

        Entry(User user) {
            this.user = user;
        }

        public User getUser() {
            return user;
        }
    }

    /** The profile User a row entry holds: its room id (0 when not live), uid and room JSON. */
    static final class User {
        public long roomId;
        String uid;
        String roomData;

        public String getUid() {
            return uid;
        }

        public String getRoomData() {
            return roomData;
        }
    }

    /** EnterRoomConfig with the two parts the hook reads and writes, made by its no-arg constructor. */
    public static final class EnterRoomConfig {
        public final RoomsData mRoomsData = new RoomsData();
        public final LogData mLogData = new LogData();

        public EnterRoomConfig() {
        }
    }

    public static final class RoomsData {
        public long roomId;
        public String userId;
        public String enterFromMerge;
        public String enterMethod;
        public String enterType;
        public String preLoadRoomInfo;
    }

    public static final class LogData {
        public ArrayList<Object> moreConfig;
        public String requestId;
    }
}
