package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.download.LiveRecorder;
import com.bytedance.android.livesdkapi.depend.model.live.Room;
import com.bytedance.android.livesdkapi.session.EnterRoomConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

/** #37: choosing what to record from a room's own stream fields. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveRoomStreamTest {
    private static final String JSON = "{\"common\":{},\"data\":{"
            + "\"sd\":{\"main\":{\"flv\":\"https://p.example.com/sd.flv\",\"hls\":\"https://p.example.com/sd.m3u8\"}},"
            + "\"hd\":{\"main\":{\"flv\":\"https://p.example.com/hd.flv\",\"hls\":\"https://p.example.com/hd.m3u8\"}},"
            + "\"ao\":{\"main\":{\"flv\":\"https://p.example.com/ao.flv\"}},"
            + "\"origin\":{\"main\":{\"flv\":\"https://p.example.com/o.flv\",\"hls\":\"https://p.example.com/o.m3u8\"}}}}";

    @Test public void theBestFlvIsChosenAndAudioOnlyNever() {
        LiveRoomStream.Pick pick = LiveRoomStream.choose(JSON, null, null, null, null, "someone", "123");
        assertNull(pick.problem);
        assertEquals("https://p.example.com/o.flv", pick.target.url);
        assertEquals(LiveRecorder.Transport.FLV, pick.target.transport);
        assertEquals("origin", pick.target.quality);
        assertEquals("someone", pick.target.creator);
        assertEquals("123", pick.target.room);
    }

    @Test public void theQualityBeingWatchedIsChosenWhenTheStreamHasIt() {
        LiveRoomStream.Pick pick = LiveRoomStream.choose(JSON, null, null, null, "hd", null, null);
        assertEquals("https://p.example.com/hd.flv", pick.target.url);
        LiveRoomStream.Pick missing = LiveRoomStream.choose(JSON, null, null, null, "ld", null, null);
        assertEquals("a quality the stream lacks falls to the best", "origin", missing.target.quality);
    }

    /** #203: the LIVE recording quality choice, watching hd. */
    @Test public void theRecordingQualityChoiceOverridesTheQualityBeingWatched() {
        assertEquals("hd", LiveRoomStream.choose(JSON, null, null, null,
                LiveRoomStream.forRecording("room", "hd"), null, null).target.quality);
        assertEquals("origin", LiveRoomStream.choose(JSON, null, null, null,
                LiveRoomStream.forRecording("highest", "hd"), null, null).target.quality);
        LiveRoomStream.Pick lowest = LiveRoomStream.choose(JSON, null, null, null,
                LiveRoomStream.forRecording("lowest", "hd"), null, null);
        assertEquals("audio only is never the smallest", "sd", lowest.target.quality);
        assertEquals("https://p.example.com/sd.flv", lowest.target.url);
        assertNull("an unread choice records what's being watched, or the best",
                LiveRoomStream.forRecording(null, null));
    }

    /** #424: name alerts listen to the room's sound alone where the room has it, else its smallest FLV. */
    @Test public void listeningTakesTheSoundOnlyStreamElseTheSmallestFlv() {
        assertEquals("https://p.example.com/ao.flv", LiveRoomStream.listenAddress(JSON, null));
        String noSoundOnly = "{\"data\":{"
                + "\"hd\":{\"main\":{\"flv\":\"https://p.example.com/hd.flv\"}},"
                + "\"sd\":{\"main\":{\"flv\":\"https://p.example.com/sd.flv\"}},"
                + "\"ao\":{\"main\":{\"flv\":\"http://p.example.com/ao.flv\"}}}}";
        assertEquals("a plain http sound stream is passed over",
                "https://p.example.com/sd.flv", LiveRoomStream.listenAddress(noSoundOnly, null));
        String hlsOnly = "{\"data\":{\"hd\":{\"main\":{\"hls\":\"https://p.example.com/hd.m3u8\"}}}}";
        assertNull("an HLS room has nothing to listen to", LiveRoomStream.listenAddress(hlsOnly, null));
        assertNull(LiveRoomStream.listenAddress(null, null));
        assertNull(LiveRoomStream.listenAddress((Object) null));
    }

    private static final String ALL = "{\"data\":{"
            + "\"origin\":{\"main\":{\"flv\":\"https://p.example.com/origin.flv\"}},"
            + "\"uhd\":{\"main\":{\"flv\":\"https://p.example.com/uhd.flv\"}},"
            + "\"hd\":{\"main\":{\"flv\":\"https://p.example.com/hd.flv\"}},"
            + "\"sd\":{\"main\":{\"flv\":\"https://p.example.com/sd.flv\"}},"
            + "\"ld\":{\"main\":{\"flv\":\"https://p.example.com/ld.flv\"}},"
            + "\"ao\":{\"main\":{\"flv\":\"https://p.example.com/ao.flv\"}}}}";

    private static String preset(String json, String choice) {
        return LiveRoomStream.choose(json, null, null, null,
                LiveRoomStream.forRecording(choice, "ld"), null, null).target.quality;
    }

    private static String room(String... keys) {
        StringBuilder json = new StringBuilder("{\"data\":{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) json.append(',');
            json.append('"').append(keys[i]).append("\":{\"main\":{\"flv\":\"https://p.example.com/")
                    .append(keys[i]).append(".flv\"}}");
        }
        return json.append("}}").toString();
    }

    /** #203: each of the room's own presets records that stream when the room has it. */
    @Test public void eachPresetRecordsItsOwnStream() {
        for (String key : new String[]{"origin", "uhd", "hd", "sd", "ld"}) {
            assertEquals(key, preset(ALL, key));
        }
    }

    @Test public void aMissingPresetFallsToTheNearestBelowThenAbove() {
        assertEquals("below first", "sd", preset(room("origin", "sd", "ld"), "hd"));
        assertEquals("the closest below, not the lowest", "hd", preset(room("origin", "hd", "ld"), "uhd"));
        assertEquals("nothing below, so the nearest above", "hd", preset(room("origin", "hd"), "ld"));
        assertEquals("only the top exists", "origin", preset(room("origin", "ao"), "sd"));
        assertEquals("only the bottom exists", "ld", preset(room("ld"), "origin"));
    }

    /** #203 on the S22 (2026-10-24): 1080p in a 1080p60 room recorded the 720p30 stream. */
    @Test public void aRoomsSixtyFpsStreamsRankAsTheirQualityAndBeatThirty() {
        String sixty = room("origin", "uhd_60", "hd_60", "hd", "sd", "ld");
        assertEquals("1080p", "uhd_60", preset(sixty, "uhd"));
        assertEquals("720p takes its 60 fps stream", "hd_60", preset(sixty, "hd"));
        assertEquals("highest", "origin", LiveRoomStream.choose(sixty, null, null, null, null, null, null).target.quality);
        assertEquals("the best without an original", "uhd_60",
                LiveRoomStream.choose(room("hd", "uhd_60", "hd_60"), null, null, null, null, null, null).target.quality);
        assertEquals("lowest takes the slower of two", "hd",
                LiveRoomStream.choose(room("hd_60", "hd"), null, null, null, LiveRoomStream.LOWEST, null, null).target.quality);
        assertEquals("the stream being watched, exactly", "hd",
                LiveRoomStream.choose(sixty, null, null, null, "hd", null, null).target.quality);
        assertEquals("a missing 1080p falls to 720p at 60", "hd_60", preset(room("origin", "hd_60", "hd", "ld"), "uhd"));

        assertEquals("hd", LiveRoomStream.base("hd_60"));
        assertEquals(60, LiveRoomStream.frameRate("hd_60"));
        assertEquals("not a frame rate", "hd_x", LiveRoomStream.base("hd_x"));
        assertEquals(0, LiveRoomStream.frameRate("hd"));
        assertEquals("_60", LiveRoomStream.base("_60"));
        assertEquals("hd_", LiveRoomStream.base("hd_"));
        assertEquals(0, LiveRoomStream.frameRate("hd_99999999999"));
        assertNull(LiveRoomStream.base(null));

        assertEquals("the diagnostics line names what the room offered",
                "origin, uhd_60, hd_60, hd, sd, ld", LiveRoomStream.choose(sixty, null, null, null, null, null, null).offered);
    }

    @Test public void aPresetNeverRecordsAudioOnly() {
        assertEquals("ld", preset(room("ao", "ld"), "hd"));
        LiveRoomStream.Pick onlyAudio = LiveRoomStream.choose(room("ao"), null, null, null,
                LiveRoomStream.forRecording("hd", null), null, null);
        assertNull(onlyAudio.target);
        assertNotNull(onlyAudio.problem);
    }

    @Test public void aPresetAlsoFallsBackOnHls() {
        Map<String, String> hls = new LinkedHashMap<>();
        hls.put("origin", "https://p.example.com/o.m3u8");
        hls.put("ld", "https://p.example.com/l.m3u8");
        LiveRoomStream.Pick pick = LiveRoomStream.choose(null, null, hls, null,
                LiveRoomStream.forRecording("sd", null), null, null);
        assertEquals(LiveRecorder.Transport.HLS, pick.target.transport);
        assertEquals("ld", pick.target.quality);
    }

    @Test public void lowestPrefersAKnownSmallStreamToAnUnknownKey() {
        Map<String, String> flv = new LinkedHashMap<>();
        flv.put("ld", "https://p.example.com/ld.flv");
        flv.put("xx", "https://p.example.com/xx.flv");
        flv.put("hd", "https://p.example.com/hd.flv");
        assertEquals("ld", LiveRoomStream.choose(null, flv, null, null, LiveRoomStream.LOWEST, null, null).target.quality);
        Map<String, String> unknownOnly = new LinkedHashMap<>();
        unknownOnly.put("xx", "https://p.example.com/xx.flv");
        assertEquals("xx", LiveRoomStream.choose(null, unknownOnly, null, null, LiveRoomStream.LOWEST, null, null).target.quality);
        Map<String, String> hls = new LinkedHashMap<>();
        hls.put("hd", "https://p.example.com/hd.m3u8");
        hls.put("ld", "https://p.example.com/ld.m3u8");
        LiveRoomStream.Pick hlsOnly = LiveRoomStream.choose(null, null, hls, null, LiveRoomStream.LOWEST, null, null);
        assertEquals(LiveRecorder.Transport.HLS, hlsOnly.target.transport);
        assertEquals("ld", hlsOnly.target.quality);
    }

    @Test public void hlsIsTheFallbackWhenThereIsNoFlv() {
        Map<String, String> hls = new LinkedHashMap<>();
        hls.put("sd", "https://p.example.com/a.m3u8");
        hls.put("hd", "https://p.example.com/b.m3u8");
        LiveRoomStream.Pick pick = LiveRoomStream.choose(null, null, hls, null, null, null, null);
        assertEquals(LiveRecorder.Transport.HLS, pick.target.transport);
        assertEquals("https://p.example.com/b.m3u8", pick.target.url);
        LiveRoomStream.Pick single = LiveRoomStream.choose(null, null, null, "https://p.example.com/one.m3u8", null, null, null);
        assertEquals("https://p.example.com/one.m3u8", single.target.url);
    }

    @Test public void theFlvMapIsUsedWhenTheJsonIsMissingOrBroken() {
        Map<String, String> flv = new LinkedHashMap<>();
        flv.put("sd", "https://p.example.com/x.flv");
        LiveRoomStream.Pick pick = LiveRoomStream.choose("{not json", flv, null, null, null, null, null);
        assertEquals("https://p.example.com/x.flv", pick.target.url);
    }

    @Test public void onlySecureAddressesAreChosenAndNothingIsAProblemNotACrash() {
        Map<String, String> flv = new LinkedHashMap<>();
        flv.put("hd", "http://p.example.com/plain.flv");
        flv.put("sd", null);
        LiveRoomStream.Pick pick = LiveRoomStream.choose(null, flv, null, null, null, null, null);
        assertNull(pick.target);
        assertNotNull(pick.problem);
        assertNotNull(LiveRoomStream.fromRoom(null, null).problem);
        assertNotNull(LiveRoomStream.fromRoom(new Object(), null).problem);
    }

    private static final class StreamUrl {
        public Map<String, String> flvPullUrl;
    }

    private static final class StreamRoom extends Room {
        public StreamUrl streamUrl;
    }

    /** A LIVE room page: its room and the enter config it was opened with. */
    private static final class RoomPage {
        public Room room;
        public EnterRoomConfig config;

        RoomPage(Room room, EnterRoomConfig config) {
            this.room = room;
            this.config = config;
        }
    }

    private static Room heldRoom(String id) {
        Room room = new Room();
        room.idStr = id;
        return room;
    }

    private static EnterRoomConfig opened(String json, long streamRoomId, long enteredRoomId) {
        EnterRoomConfig config = new EnterRoomConfig();
        config.mStreamData.pullStreamData = json;
        config.mStreamData.streamRoomId = streamRoomId;
        config.mRoomsData.roomId = enteredRoomId;
        return config;
    }

    /**
     * #413: with Ghost mode holding the room enter back, TikTok never fills the room's stream, so
     * the window's Record found none. The player plays the stream the page was opened with.
     */
    @Test public void aRoomWhoseEnterWasHeldBackRecordsTheStreamItsPageOpenedWith() {
        Room room = heldRoom("7");
        assertEquals("No stream", LiveRoomStream.fromRoom(room, "hd").problem);
        LiveRoomStream.Pick pick = LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 7, 0)), room, "hd");
        assertNull(pick.problem);
        assertEquals("https://p.example.com/hd.flv", pick.target.url);
        assertEquals(LiveRecorder.Transport.FLV, pick.target.transport);
        assertEquals("7", pick.target.room);
        assertEquals("sd, hd, origin", pick.offered);
        LiveRoomStream.Pick entered = LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 0, 7)), room, null);
        assertEquals("with no stream room id, the entered room's id counts",
                "https://p.example.com/o.flv", entered.target.url);
    }

    @Test public void anotherRoomsStreamIsNeverRecorded() {
        Room room = heldRoom("7");
        LiveRoomStream.Pick other = LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 8, 7)), room, null);
        assertNull(other.target);
        assertTrue(other.problem, other.problem.startsWith("No stream, "));
        assertNull(LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 0, 8)), room, null).target);
        assertNull(LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 0, 0)), room, null).target);
    }

    @Test public void aPageWithoutAnOpeningStreamKeepsTheRoomsOwnReason() {
        Room room = heldRoom("7");
        assertEquals("No stream", LiveRoomStream.fromPage(null, room, null).problem);
        assertNull(LiveRoomStream.fromPage(new Object(), room, null).target);
        assertNull(LiveRoomStream.fromPage(new RoomPage(room, null), room, null).target);
        assertNull(LiveRoomStream.fromPage(new RoomPage(room, opened(null, 7, 7)), room, null).target);
        assertNull(LiveRoomStream.fromPage(new RoomPage(room, opened("{not json", 7, 7)), room, null).target);
        assertNull(LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 7, 7)), heldRoom(null), null).target);
        assertNotNull(LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 7, 7)), null, null).problem);
    }

    /** A room that carries its own pull stream data and no stream URL, as a held room can on 48.0.2. */
    private static final class DataRoom extends Room {
        public String pullStreamData;
    }

    /**
     * #413 on the S22: in a room Ghost mode held back, Record logged "No stream, the page opened
     * with another room's stream". The room had no stream URL, so its own pull stream data was
     * never read, though that data is the room's by definition.
     */
    @Test public void aHeldRoomRecordsItsOwnStreamDataWhenItHasNoStreamUrl() {
        DataRoom room = new DataRoom();
        room.idStr = "7";
        room.pullStreamData = JSON;
        LiveRoomStream.Pick own = LiveRoomStream.fromRoom(room, "hd");
        assertNull(own.problem);
        assertEquals("https://p.example.com/hd.flv", own.target.url);
        assertEquals("7", own.target.room);
        LiveRoomStream.Pick page = LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 8, 8)), room, null);
        assertEquals("another room's opening stream doesn't get in the way",
                "https://p.example.com/o.flv", page.target.url);
        room.pullStreamData = "";
        assertEquals("No stream", LiveRoomStream.fromRoom(room, null).problem);
    }

    @Test public void aStreamRefusedForAnotherRoomSaysWhichRooms() {
        Room room = heldRoom("7");
        String problem = LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 0, 0)), room, null).problem;
        assertTrue(problem, problem.endsWith("(stream room 0, entered 0, this room 7)"));
    }

    @Test public void aRoomsOwnStreamStillComesFirst() {
        StreamRoom room = new StreamRoom();
        room.idStr = "7";
        room.streamUrl = new StreamUrl();
        room.streamUrl.flvPullUrl = new LinkedHashMap<>();
        room.streamUrl.flvPullUrl.put("sd", "https://p.example.com/own.flv");
        LiveRoomStream.Pick pick = LiveRoomStream.fromPage(new RoomPage(room, opened(JSON, 7, 7)), room, null);
        assertEquals("https://p.example.com/own.flv", pick.target.url);
    }

    @Test public void theEnteredRoomsCachedCopyIsUsedWhenItHasTheStream() {
        Room held = heldRoom("7");
        StreamRoom cached = new StreamRoom();
        cached.idStr = "7";
        cached.streamUrl = new StreamUrl();
        cached.streamUrl.flvPullUrl = new LinkedHashMap<>();
        cached.streamUrl.flvPullUrl.put("hd", "https://p.example.com/cached.flv");
        EnterRoomConfig config = opened(null, 0, 7);
        config.mRoomsData.enterRoomCache = cached;
        assertEquals("https://p.example.com/cached.flv",
                LiveRoomStream.fromPage(new RoomPage(held, config), held, null).target.url);
        cached.idStr = "8";
        assertNull("another room's cached copy is never used",
                LiveRoomStream.fromPage(new RoomPage(held, config), held, null).target);
    }
}
