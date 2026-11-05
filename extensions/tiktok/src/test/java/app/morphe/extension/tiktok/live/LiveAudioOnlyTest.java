package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** #265: Audio only in a LIVE room's Video quality list. */
@RunWith(RobolectricTestRunner.class)
public class LiveAudioOnlyTest {
    private static final String WITH_AUDIO = "{\"data\":{"
            + "\"hd\":{\"main\":{\"flv\":\"https://pull.example/hd.flv\"}},"
            + "\"ao\":{\"main\":{\"flv\":\"https://pull.example/ao.flv\"}}}}";
    private static final String WITHOUT_AUDIO = "{\"data\":{"
            + "\"hd\":{\"main\":{\"flv\":\"https://pull.example/hd.flv\"}}}}";

    private Locale savedLocale;

    /** Stands in for TikTok's quality row: the three fields the picker shows and switches by. */
    @SuppressWarnings("unused")
    static final class Quality {
        String name;
        String sdkKey;
        int level;

        Quality() {
        }

        Quality(String sdkKey, int level) {
            this.sdkKey = sdkKey;
            this.name = sdkKey.toUpperCase(Locale.US);
            this.level = level;
        }
    }

    /** Stands in for TikTok's room, with the stream data it keeps when the stream address has none. */
    @SuppressWarnings("unused")
    static final class Room {
        final Object streamUrl = null;
        final String pullStreamData;

        Room(String pullStreamData) {
            this.pullStreamData = pullStreamData;
        }
    }

    @Before public void setUp() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.liveControlsEnabled = true;
        coldStart();
    }

    /** A new TikTok process: nothing picked this run. */
    private static void coldStart() {
        ReflectionHelpers.setStaticField(LiveControls.class, "pickThisRun", null);
        ReflectionHelpers.setStaticField(LiveControls.class, "lastQualityPick", null);
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.liveControlsEnabled = false;
        Settings.LIVE_AUDIO_ONLY.save(Settings.LIVE_AUDIO_ONLY.defaultValue);
        Settings.OFFER_LIVE_QUALITY.save(Settings.OFFER_LIVE_QUALITY.defaultValue);
        Settings.REMEMBER_LIVE_QUALITY.save(Settings.REMEMBER_LIVE_QUALITY.defaultValue);
        Settings.REMEMBERED_LIVE_QUALITY.save(Settings.REMEMBERED_LIVE_QUALITY.defaultValue);
        coldStart();
        LiveAudioOnly.unpatchedPickerMark = null;
        LiveAudioOnly.noteRoom(null);
        ReflectionHelpers.setStaticField(LiveAudioOnly.class, "audioOnlyRoom", new WeakReference<>(null));
        Locale.setDefault(savedLocale);
    }

    private static LinkedList<Object> rooms(Quality... rows) {
        return new LinkedList<>(Arrays.asList(rows));
    }

    @Test public void offIsTheDefaultAndPauseOrAMissingPatchTurnsItOff() {
        assertFalse(Settings.LIVE_AUDIO_ONLY.defaultValue);
        assertFalse(LiveAudioOnly.enabled());
        Settings.LIVE_AUDIO_ONLY.save(true);
        assertTrue(LiveAudioOnly.enabled());
        setPaused(true);
        assertFalse(LiveAudioOnly.enabled());
        setPaused(false);
        SettingsStatus.liveControlsEnabled = false;
        assertFalse(LiveAudioOnly.enabled());
    }

    @Test public void theListGetsAnAudioOnlyRowBelowTheOthersWhenTheStreamHasIt() {
        Settings.LIVE_AUDIO_ONLY.save(true);
        LinkedList<Object> list = rooms(new Quality("hd", 3), new Quality("sd", 2));

        LinkedList<Object> shown = LiveAudioOnly.qualities(new Room(WITH_AUDIO), list);

        assertEquals("the room's own list changed", 2, list.size());
        assertEquals(3, shown.size());
        Quality added = (Quality) shown.getLast();
        assertEquals("ao", added.sdkKey);
        assertEquals("Audio only", added.name);
        assertEquals("not under the lowest row", 1, added.level);
        assertSame("hd row", list.get(0), shown.get(0));
        assertSame("a second call added another row", shown, LiveAudioOnly.qualities(new Room(WITH_AUDIO), shown));
    }

    @Test public void theListStaysAsItWasOffOrWithoutTheRoomsSound() {
        LinkedList<Object> list = rooms(new Quality("hd", 3));
        assertSame("switch off", list, LiveAudioOnly.qualities(new Room(WITH_AUDIO), list));

        Settings.LIVE_AUDIO_ONLY.save(true);
        assertSame("no ao in the stream", list, LiveAudioOnly.qualities(new Room(WITHOUT_AUDIO), list));
        assertSame("no stream data", list, LiveAudioOnly.qualities(new Room(null), list));
        assertSame("no room", list, LiveAudioOnly.qualities(null, list));
        assertNull("no list", LiveAudioOnly.qualities(new Room(WITH_AUDIO), null));
    }

    /** A quality row whose key belongs to the class, the shape Android 17 won't let a write reach when it's final. */
    @SuppressWarnings("unused")
    static final class ClassKeyedQuality {
        static String sdkKey = "class";
        String name;
        int level;
    }

    @Test public void aRowWhoseKeyIsStaticIsNeverWritten() throws Exception {
        LinkedList<Object> list = new LinkedList<>();
        list.add(new ClassKeyedQuality());

        assertNull("a row was made by writing a static field", LiveAudioOnly.row(list));
        assertEquals("the class's own key changed", "class", ClassKeyedQuality.sdkKey);
    }

    @Test public void thePlayersKeysGetAoOnlyWhenTheListOffersIt() {
        Settings.LIVE_AUDIO_ONLY.save(true);
        List<Object> keys = new ArrayList<>(Arrays.asList("hd", "sd"));
        LinkedList<Object> shown = LiveAudioOnly.qualities(new Room(WITH_AUDIO), rooms(new Quality("hd", 3)));

        List<Object> checked = LiveAudioOnly.playerKeys(shown, keys);
        assertTrue(checked.contains("ao"));
        assertEquals("the player's own list changed", 2, keys.size());

        assertSame("no Audio only row", keys, LiveAudioOnly.playerKeys(rooms(new Quality("hd", 3)), keys));
        assertNull("no keys stays no keys", LiveAudioOnly.playerKeys(shown, null));
        Settings.LIVE_AUDIO_ONLY.save(false);
        assertSame("switch off", keys, LiveAudioOnly.playerKeys(shown, keys));
    }

    /** Stands in for the older picker's row: a label and a key, made from a quality row or from text. */
    @SuppressWarnings("unused")
    static final class Row {
        final String label;
        final String key;
        boolean selected;

        Row(String label, String key) {
            this.label = label;
            this.key = key;
        }

        Row(Quality quality) {
            this(quality.name, quality.sdkKey);
        }
    }

    @Test public void theOlderPickersRowsGetAnAudioOnlyRowForTheRoomItsBuilderLoaded() {
        Settings.LIVE_AUDIO_ONLY.save(true);
        List<Object> rows = new ArrayList<>(Arrays.asList(new Row("720p", "hd"), new Row("360p", "sd")));

        LiveAudioOnly.noteRoom(new Room(WITH_AUDIO));
        List<Object> shown = LiveAudioOnly.pickerRows(rows);

        assertEquals("the builder's own rows changed", 2, rows.size());
        assertEquals(3, shown.size());
        Row added = (Row) shown.get(2);
        assertEquals("ao", added.key);
        assertEquals("Audio only", added.label);
        assertFalse(added.selected);
        assertSame("720p row", rows.get(0), shown.get(0));
        assertSame("a second call added another row", shown, LiveAudioOnly.pickerRows(shown));
    }

    @Test public void theOlderPickersRowsStayAsTheyWereOffOrWithoutTheRoomsSound() {
        List<Object> rows = new ArrayList<>(Arrays.asList(new Row("720p", "hd")));
        LiveAudioOnly.noteRoom(new Room(WITH_AUDIO));
        assertSame("switch off", rows, LiveAudioOnly.pickerRows(rows));

        Settings.LIVE_AUDIO_ONLY.save(true);
        List<Object> resumed = new ArrayList<>(Arrays.asList(new Row("Audio only", "ao")));
        assertSame("TikTok already lists the room's current Audio only pick", resumed, LiveAudioOnly.pickerRows(resumed));
        LiveAudioOnly.noteRoom(new Room(WITHOUT_AUDIO));
        assertSame("no ao in the stream", rows, LiveAudioOnly.pickerRows(rows));
        LiveAudioOnly.noteRoom(null);
        assertSame("no room", rows, LiveAudioOnly.pickerRows(rows));
        List<Object> none = new ArrayList<>();
        assertSame("no rows", none, LiveAudioOnly.pickerRows(none));
        assertNull(LiveAudioOnly.pickerRows(null));
    }

    private static Row addedRow() {
        return (Row) LiveAudioOnly.pickerRows(new ArrayList<>(Arrays.asList(new Row("720p", "hd"), new Row("360p", "sd")))).get(2);
    }

    /** TikTok marks none of its own rows while a room plays Audio only, so the added row carries the mark there. */
    @Test public void theOlderPickersAudioOnlyRowShowsAsCurrentOnlyInTheRoomPlayingIt() {
        Settings.LIVE_AUDIO_ONLY.save(true);
        LiveAudioOnly.unpatchedPickerMark = "selected";
        Room room = new Room(WITH_AUDIO);
        LiveAudioOnly.noteRoom(room);
        assertFalse("nothing picked yet", addedRow().selected);

        LiveControls.noteLiveQualityPick("ao");
        assertTrue("picked in this room", addedRow().selected);

        LiveAudioOnly.noteRoom(new Room(WITH_AUDIO));
        assertFalse("the next room", addedRow().selected);

        LiveAudioOnly.noteRoom(room);
        LiveControls.noteLiveQualityPick("");
        assertTrue("an empty pick changes nothing", addedRow().selected);
        LiveControls.noteLiveQualityPick("sd");
        assertFalse("a picture quality picked after it", addedRow().selected);

        LiveControls.noteLiveQualityPick("ao");
        LiveAudioOnly.unpatchedPickerMark = "label";
        assertFalse("a field that isn't a mark", addedRow().selected);
        LiveAudioOnly.unpatchedPickerMark = "missing";
        assertFalse("a field the row doesn't have", addedRow().selected);
        LiveAudioOnly.unpatchedPickerMark = null;
        assertFalse("no field named", addedRow().selected);
        assertEquals("the row is still added", "ao", addedRow().key);
    }

    /** Stands in for TikTok's room with its id, which a copy of the same LIVE carries too. */
    @SuppressWarnings("unused")
    static final class IdRoom {
        final Object streamUrl = null;
        final String pullStreamData = WITH_AUDIO;
        final long id;

        IdRoom(long id) {
            this.id = id;
        }
    }

    /** A screen recording would keep a still picture, so Record asks whether its room plays Audio only. */
    @Test public void aRoomPlayingAudioOnlyIsKnownFromEitherPickerAndOnlyThere() {
        Settings.LIVE_AUDIO_ONLY.save(true);
        IdRoom room = new IdRoom(42);
        assertFalse("nothing picked", LiveAudioOnly.playing(room));

        LiveAudioOnly.qualities(room, rooms(new Quality("hd", 3)));
        LiveControls.noteLiveQualityPick("ao");
        assertTrue("picked in the newer picker", LiveAudioOnly.playing(room));
        assertTrue("a copy of the same LIVE", LiveAudioOnly.playing(new IdRoom(42)));
        assertFalse("another LIVE", LiveAudioOnly.playing(new IdRoom(43)));
        assertFalse("a room without an id", LiveAudioOnly.playing(new Room(WITH_AUDIO)));
        assertFalse("no room", LiveAudioOnly.playing(null));
        Settings.LIVE_AUDIO_ONLY.save(false);
        assertFalse("switch off", LiveAudioOnly.playing(room));
        Settings.LIVE_AUDIO_ONLY.save(true);

        LiveControls.noteLiveQualityPick("hd");
        assertFalse("a picture quality picked after it", LiveAudioOnly.playing(room));

        LiveAudioOnly.noteRoom(room);
        LiveControls.noteLiveQualityPick("ao");
        assertTrue("picked in the older picker", LiveAudioOnly.playing(room));

        IdRoom unnumbered = new IdRoom(0);
        LiveAudioOnly.noteRoom(unnumbered);
        LiveControls.noteLiveQualityPick("ao");
        assertTrue(LiveAudioOnly.playing(unnumbered));
        assertFalse("id 0 isn't a LIVE's id", LiveAudioOnly.playing(new IdRoom(0)));
    }

    @Test public void theServersAudioFlagAnswersOnOnlyWhileTheSwitchIs() {
        assertEquals(0, LiveAudioOnly.switchAllowed(0));
        assertEquals(1, LiveAudioOnly.switchAllowed(1));
        Settings.LIVE_AUDIO_ONLY.save(true);
        assertEquals(1, LiveAudioOnly.switchAllowed(0));
        setPaused(true);
        assertEquals(0, LiveAudioOnly.switchAllowed(0));
    }

    /** Audio only is for the room it was picked in: never kept, carried or recorded at. */
    @Test public void anAudioOnlyPickIsNeverKeptOrCarriedToTheNextRoom() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        Settings.REMEMBER_LIVE_QUALITY.save(true);
        LiveControls.noteLiveQualityPick("hd");
        LiveControls.noteLiveQualityPick("ao");

        assertEquals("hd", Settings.REMEMBERED_LIVE_QUALITY.get());
        assertEquals("recording at the quality being watched", "hd", LiveControls.lastQualityPick());
        assertEquals("the next room", "hd", LiveControls.liveQualityPick("ao"));
    }

    @Test public void withNoPicturePickTheNextRoomStartsAtTheStreamsDefault() {
        assertNull(LiveControls.liveQualityPick("ao"));
        LiveControls.noteLiveQualityPick("ld");
        assertEquals("ld", LiveControls.liveQualityPick("ao"));
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}
