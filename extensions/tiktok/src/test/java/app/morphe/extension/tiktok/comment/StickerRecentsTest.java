/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Keep recent stickers (#156): the Recently used row above Saved, and what it held coming back after a restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StickerRecentsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for PineDrama's StickerItem. */
    public static final class FakeSticker {
        final String id;
        final String url;

        FakeSticker(String id, String url) {
            this.id = id;
            this.url = url;
        }

        @Override public boolean equals(Object other) {
            return other instanceof FakeSticker && ((FakeSticker) other).id.equals(id);
        }

        @Override public int hashCode() {
            return id.hashCode();
        }
    }

    /** Stands in for the row's MutableStateFlow, which has real-named getValue and setValue. */
    public static final class FakeFlow {
        Object value;
        int writes;

        public void setValue(Object value) {
            this.value = value;
            writes++;
        }
    }

    /** Writes a sticker the way Gson does, '=' escaped for HTML. */
    static final class FakeCodec implements StickerRecents.Codec {
        @Override public String write(Object item) throws Exception {
            FakeSticker sticker = (FakeSticker) item;
            if (sticker.id.equals("unwritable")) throw new IllegalStateException("Gson can't");
            return new JSONObject().put("id", sticker.id).put("url", sticker.url).toString()
                    .replace("=", ESCAPED_EQUALS);
        }

        @Override public Object read(String text, Class<?> type) throws Exception {
            assertEquals(FakeSticker.class, type);
            JSONObject json = new JSONObject(text.replace(ESCAPED_EQUALS, "="));
            return new FakeSticker(json.getString("id"), json.getString("url"));
        }
    }

    /** Gson's shapes after R8: one writer, one reader, and lookalikes that aren't either. */
    public static final class FakeGson {
        public String LJIILJJIL(Object item) {
            return "written:" + item;
        }

        public Object LJFF(String text, Class<?> type) {
            return type.getSimpleName() + ":" + text;
        }

        public Object LIZ(String text, java.lang.reflect.Type type) {
            return null;
        }

        public void LIZIZ(Object item, Appendable out) {
        }

        public String LIZJ(String text) {
            return text;
        }
    }

    public static final class TwoWriters {
        public String LIZ(Object item) {
            return "";
        }

        public String LIZIZ(Object item) {
            return "";
        }

        public Object LIZJ(String text, Class<?> type) {
            return null;
        }
    }

    private static final String ESCAPED_EQUALS = "\\" + "u003d";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        StickerRecents.resetForTests();
        StickerRecents.codecForTests = new FakeCodec();
        StickerRecents.versionForTests = "47.5.3";
        SignedInUser.idForTests = "200";
        clearStore();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        StickerRecents.codecForTests = null;
        StickerRecents.versionForTests = null;
        StickerRecents.resetForTests();
        SignedInUser.idForTests = null;
        clearStore();
    }

    private void clearStore() {
        context.getSharedPreferences(StickerRecents.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static String expiringAt(long seconds) {
        return "https://p16.example/sticker.webp?x-expires=" + seconds + "&x-signature=abc";
    }

    private static long inAnHour() {
        return System.currentTimeMillis() / 1000 + 3600;
    }

    @Test public void theRowComesBackForTheSameAccountAfterTheStoreEmptiesIt() {
        List<FakeSticker> used = Arrays.asList(
                new FakeSticker("1", expiringAt(inAnHour())), new FakeSticker("2", "https://p16.example/plain.webp"));
        StickerRecents.save(Collections.unmodifiableList(used));

        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals(1, flow.writes);
        assertEquals(used, flow.value);
    }

    @Test public void anotherAccountGetsNothingAndKeepsItsOwn() {
        StickerRecents.save(Collections.singletonList(new FakeSticker("mine", "u")));
        SignedInUser.idForTests = "300";

        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals("nothing was kept for this account", 0, flow.writes);

        StickerRecents.save(Collections.singletonList(new FakeSticker("theirs", "u")));
        SignedInUser.idForTests = "200";
        StickerRecents.restore(flow);
        assertEquals(Collections.singletonList(new FakeSticker("mine", "u")), flow.value);
    }

    @Test public void signedOutNothingIsKeptOrPutBack() {
        SignedInUser.idForTests = "";
        StickerRecents.save(Collections.singletonList(new FakeSticker("1", "u")));
        assertTrue(context.getSharedPreferences(StickerRecents.PREFS_NAME, Context.MODE_PRIVATE).getAll().isEmpty());

        SignedInUser.idForTests = "200";
        StickerRecents.save(Collections.singletonList(new FakeSticker("1", "u")));
        SignedInUser.idForTests = "";
        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals(0, flow.writes);
    }

    @Test public void anotherAppVersionReadsNothingAndItsFirstSaveDropsTheOldLists() {
        StickerRecents.save(Collections.singletonList(new FakeSticker("old", "u")));
        SignedInUser.idForTests = "300";
        StickerRecents.save(Collections.singletonList(new FakeSticker("other", "u")));

        StickerRecents.versionForTests = "47.6.0";
        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals("a list from 47.5.3 isn't read on 47.6.0", 0, flow.writes);

        SignedInUser.idForTests = "200";
        StickerRecents.save(Collections.singletonList(new FakeSticker("new", "u")));
        android.content.SharedPreferences store =
                context.getSharedPreferences(StickerRecents.PREFS_NAME, Context.MODE_PRIVATE);
        assertEquals("47.6.0", store.getString(StickerRecents.VERSION_KEY, null));
        assertNull("the other account's 47.5.3 list went", store.getString("300", null));
        StickerRecents.restore(flow);
        assertEquals(Collections.singletonList(new FakeSticker("new", "u")), flow.value);
    }

    @Test public void stickersWhoseLinksHaveExpiredAreLeftOut() {
        long past = System.currentTimeMillis() / 1000 - 60;
        StickerRecents.save(Arrays.asList(
                new FakeSticker("stale", expiringAt(past)),
                new FakeSticker("fresh", expiringAt(inAnHour())),
                new FakeSticker("plain", "https://p16.example/plain.webp")));

        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals(Arrays.asList(new FakeSticker("fresh", ""), new FakeSticker("plain", "")), flow.value);
    }

    @Test public void theLatestExpiryOfASticker() {
        long now = 1_800_000_000_000L;
        String escaped = "{\"a\":\"x-expires" + ESCAPED_EQUALS + "1700000000\",\"b\":\"x-expires=1900000000\"}";
        assertFalse("one link is still good", StickerRecents.expired(escaped, now));
        assertTrue(StickerRecents.expired("{\"a\":\"x-expires" + ESCAPED_EQUALS + "1700000000\"}", now));
        assertFalse("no expiry, nothing to judge", StickerRecents.expired("{\"a\":\"plain\"}", now));
    }

    @Test public void aStickerGsonCantWriteIsLeftOutAndTheRestAreKept() {
        StickerRecents.save(Arrays.asList(new FakeSticker("unwritable", "u"), new FakeSticker("2", "u")));
        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals(Collections.singletonList(new FakeSticker("2", "u")), flow.value);
    }

    @Test public void atMostFiftyAreKept() {
        List<FakeSticker> many = new ArrayList<>();
        for (int index = 0; index < 60; index++) many.add(new FakeSticker(String.valueOf(index), "u"));
        StickerRecents.save(many);
        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals(many.subList(0, StickerRecents.MOST), flow.value);
    }

    @Test public void pausedNothingIsKeptOrPutBack() {
        PausedProcess.set(true);
        StickerRecents.save(Collections.singletonList(new FakeSticker("1", "u")));
        assertTrue(context.getSharedPreferences(StickerRecents.PREFS_NAME, Context.MODE_PRIVATE).getAll().isEmpty());

        PausedProcess.set(false);
        StickerRecents.save(Collections.singletonList(new FakeSticker("1", "u")));
        PausedProcess.set(true);
        FakeFlow flow = new FakeFlow();
        StickerRecents.restore(flow);
        assertEquals(0, flow.writes);
    }

    @Test public void anythingButAListIsIgnoredAndNothingThrows() {
        StickerRecents.save("not a list");
        StickerRecents.save(null);
        StickerRecents.restore(null);
        StickerRecents.restore(new Object());
        assertTrue(context.getSharedPreferences(StickerRecents.PREFS_NAME, Context.MODE_PRIVATE).getAll().isEmpty());
    }

    /** Stands in for the Saved page's heading and sticker cells. A sticker named "broken" gets no cell. */
    static final class FakeCells implements StickerRecents.RowCells {
        @Override public Object item(Object sticker) {
            String id = ((FakeSticker) sticker).id;
            return id.equals("broken") ? null : "cell " + id;
        }

        @Override public Object title(String heading) {
            return "heading " + heading;
        }
    }

    private static List<Object> savedRows() {
        return new ArrayList<>(Arrays.<Object>asList("heading Saved", "cell saved1"));
    }

    @Test public void theRowPutsAHeadingAndTheFourNewestAboveSaved() {
        List<Object> rows = savedRows();
        List<FakeSticker> recents = Arrays.asList(new FakeSticker("1", "u"), null, new FakeSticker("2", "u"),
                new FakeSticker("3", "u"), new FakeSticker("4", "u"), new FakeSticker("5", "u"));
        assertEquals(5, StickerRecents.prepend(rows, recents, new FakeCells(), "Recently used"));
        assertEquals(Arrays.<Object>asList("heading Recently used", "cell 1", "cell 2", "cell 3", "cell 4",
                "heading Saved", "cell saved1"), rows);
    }

    @Test public void aShortListGetsAShortRow() {
        List<Object> rows = savedRows();
        assertEquals(2, StickerRecents.prepend(rows, Collections.singletonList(new FakeSticker("1", "u")), new FakeCells(), "Recently used"));
        assertEquals(Arrays.<Object>asList("heading Recently used", "cell 1", "heading Saved", "cell saved1"), rows);
    }

    @Test public void noRecentsOrACellThatCantBeMadeLeavesSavedAlone() {
        List<Object> rows = savedRows();
        assertEquals(0, StickerRecents.prepend(rows, Collections.emptyList(), new FakeCells(), "Recently used"));
        assertEquals(0, StickerRecents.prepend(rows, null, new FakeCells(), "Recently used"));
        assertEquals(0, StickerRecents.prepend(rows, Arrays.asList(new FakeSticker("1", "u"), new FakeSticker("broken", "u")),
                new FakeCells(), "Recently used"));
        assertEquals(savedRows(), rows);
    }

    @Test public void unpatchedOrPausedTheSavedPageKeepsItsRows() {
        List<Object> rows = savedRows();
        StickerRecents.addRow(new Object(), rows);
        assertEquals("the stubs answer nothing off the app", savedRows(), rows);
        assertEquals(0, StickerRecents.rowsAbove());

        PausedProcess.set(true);
        StickerRecents.addRow(new Object(), rows);
        assertEquals(savedRows(), rows);
        assertEquals(0, StickerRecents.rowsAbove());

        PausedProcess.set(false);
        StickerRecents.addRow(null, "not a list");
        StickerRecents.addRow(null, null);
        assertEquals(0, StickerRecents.rowsAbove());
    }

    @Test public void gsonIsFoundByItsShapes() throws Exception {
        StickerRecents.Codec gson = StickerRecents.gson(FakeGson.class);
        assertNotNull(gson);
        assertEquals("written:7", gson.write(7));
        assertEquals("String:x", gson.read("x", String.class));
        assertNull("two writers, so neither is trusted", StickerRecents.gson(TwoWriters.class));
        assertNull("no writer at all", StickerRecents.gson(FakeFlow.class));
    }

    @Test public void theSameListTwiceOnANewVersionStillMovesTheVersion() {
        StickerRecents.save(Collections.singletonList(new FakeSticker("1", "u")));
        StickerRecents.versionForTests = "47.6.0";
        StickerRecents.save(Collections.singletonList(new FakeSticker("1", "u")));
        assertEquals("47.6.0", context.getSharedPreferences(StickerRecents.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(StickerRecents.VERSION_KEY, null));
    }
}
