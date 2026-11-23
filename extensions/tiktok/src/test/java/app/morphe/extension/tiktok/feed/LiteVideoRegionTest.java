/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #502: TikTok Lite's feed JSON carries each video's region, which Lite's Aweme has no field for.
 * The value Gson would skip right inside an Aweme it made is read and kept for that video, and
 * everything else is left for skipValue. Each country is also saved by the video's id for the
 * cached videos Lite shows first after a restart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiteVideoRegionTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** JsonToken as Gson has it; only its constant names matter. */
    enum Token { STRING, NULL, BEGIN_OBJECT }

    /** A reader that sits at one path with what comes next queued, and counts what it reads. */
    static final class FakeReader {
        String path;
        final Deque<Object> values = new ArrayDeque<>();
        int strings;

        FakeReader at(String path, Object next) {
            this.path = path;
            values.clear();
            values.add(next);
            return this;
        }
    }

    public static final class Video extends Aweme {
        /** Gson fills it in while it reads the video, so it can still be missing. */
        String aid;

        Video(String aid) {
            this.aid = aid;
        }

        @Override
        public String getAid() {
            return aid;
        }
    }

    private LiteVideoRegion.ReaderCalls original;
    private boolean regionPatched;

    @Before
    public void setUp() {
        original = LiteVideoRegion.calls;
        LiteVideoRegion.calls = new LiteVideoRegion.ReaderCalls() {
            @Override
            public String path(Object reader) {
                return ((FakeReader) reader).path;
            }

            @Override
            public Object peek(Object reader) {
                Object next = ((FakeReader) reader).values.peekFirst();
                return next instanceof String ? Token.STRING : next;
            }

            @Override
            public String nextString(Object reader) {
                FakeReader fake = (FakeReader) reader;
                fake.strings++;
                return (String) fake.values.removeFirst();
            }
        };
        regionPatched = SettingsStatus.authorRegionEnabled;
        SettingsStatus.authorRegionEnabled = true;
        Settings.SHOW_AUTHOR_REGION.save(true);
        LiteVideoRegion.clear();
        savedFile().edit().clear().commit();
    }

    private static SharedPreferences savedFile() {
        return Utils.getContext().getSharedPreferences(LiteVideoRegion.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() {
        LiteVideoRegion.calls = original;
        SettingsStatus.authorRegionEnabled = regionPatched;
        Settings.SHOW_AUTHOR_REGION.resetToDefault();
        LiteVideoRegion.clear();
    }

    @Test
    public void aVideosRegionIsReadInsteadOfSkippedAndKeptForIt() {
        FakeReader reader = new FakeReader();
        Video video = new Video("7300000000000000001");
        LiteVideoRegion.made(video, reader.at("$.aweme_list[3]", Token.BEGIN_OBJECT));

        assertTrue("read, so skipValue must not run",
                LiteVideoRegion.skipping(reader.at("$.aweme_list[3].region", "US")));
        assertEquals(1, reader.strings);
        assertEquals("US", LiteVideoRegion.region(video));
    }

    @Test
    public void theCreatorsRegionAndOtherVideosAreLeftForSkipValue() {
        FakeReader reader = new FakeReader();
        Video video = new Video("7300000000000000002");
        LiteVideoRegion.made(video, reader.at("$.aweme_list[0]", Token.BEGIN_OBJECT));

        assertFalse(LiteVideoRegion.skipping(reader.at("$.aweme_list[0].author.region", "JP")));
        assertFalse(LiteVideoRegion.skipping(reader.at("$.aweme_list[1].region", "JP")));
        assertFalse(LiteVideoRegion.skipping(reader.at("$.aweme_list[0].poi_trace", "x")));
        assertEquals("nothing was read", 0, reader.strings);
        assertNull(LiteVideoRegion.region(video));
    }

    @Test
    public void aNullRegionIsLeftForSkipValue() {
        FakeReader reader = new FakeReader();
        Video video = new Video("7300000000000000003");
        LiteVideoRegion.made(video, reader.at("$.aweme_detail", Token.BEGIN_OBJECT));

        assertFalse(LiteVideoRegion.skipping(reader.at("$.aweme_detail.region", Token.NULL)));
        assertEquals(0, reader.strings);
        assertNull(LiteVideoRegion.region(video));
    }

    @Test
    public void onlyAwemesAreTracked() {
        FakeReader reader = new FakeReader();
        LiteVideoRegion.made(new Object(), reader.at("$.user", Token.BEGIN_OBJECT));
        assertFalse(LiteVideoRegion.skipping(reader.at("$.user.region", "US")));
        assertEquals(0, reader.strings);
        assertTrue(LiteVideoRegion.isAweme(Aweme.class));
        assertFalse(LiteVideoRegion.isAweme(String.class));
    }

    @Test
    public void nothingIsReadWithTheSwitchOffOrUnpatched() {
        FakeReader reader = new FakeReader();
        Video video = new Video("7300000000000000004");
        Settings.SHOW_AUTHOR_REGION.save(false);
        LiteVideoRegion.made(video, reader.at("$.aweme_list[0]", Token.BEGIN_OBJECT));
        Settings.SHOW_AUTHOR_REGION.save(true);
        assertFalse("not made while off", LiteVideoRegion.skipping(reader.at("$.aweme_list[0].region", "US")));

        LiteVideoRegion.made(video, reader.at("$.aweme_list[0]", Token.BEGIN_OBJECT));
        SettingsStatus.authorRegionEnabled = false;
        assertFalse(LiteVideoRegion.skipping(reader.at("$.aweme_list[0].region", "US")));
        assertEquals(0, reader.strings);
    }

    @Test
    public void aCopyOfTheVideoFindsItsRegionByItsId() {
        FakeReader reader = new FakeReader();
        Video made = new Video("7300000000000000005");
        LiteVideoRegion.made(made, reader.at("$.aweme_list[2]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[2].region", "BR")));

        assertEquals("BR", LiteVideoRegion.region(new Video("7300000000000000005")));
        assertNull(LiteVideoRegion.region(new Video("7300000000000000006")));
        assertNull(LiteVideoRegion.region(null));
    }

    @Test
    public void theNewestPageWinsWhereAPlaceIsReused() {
        FakeReader reader = new FakeReader();
        Video first = new Video("7300000000000000007");
        Video second = new Video("7300000000000000008");
        LiteVideoRegion.made(first, reader.at("$.aweme_list[0]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[0].region", "DE")));
        LiteVideoRegion.made(second, reader.at("$.aweme_list[0]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[0].region", "FR")));

        assertEquals("DE", LiteVideoRegion.region(first));
        assertEquals("FR", LiteVideoRegion.region(second));
    }

    /** Lite's cache writes a video from its model, so after a restart the region is gone from it. */
    @Test
    public void aCachedVideoAfterARestartGetsTheCountryItCameWith() {
        FakeReader reader = new FakeReader();
        Video made = new Video("7300000000000000009");
        LiteVideoRegion.made(made, reader.at("$.aweme_list[1]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[1].region", "MY")));
        assertEquals("MY", LiteVideoRegion.region(made));

        LiteVideoRegion.clear();
        assertEquals("MY", LiteVideoRegion.region(new Video("7300000000000000009")));
        assertNull(LiteVideoRegion.region(new Video("7300000000000000010")));
    }

    /** Lite refills its cache as it goes to the background, where no row asks for a country. */
    @Test
    public void aPageReadInTheBackgroundIsSavedWithNoLookupAfterIt() {
        FakeReader reader = new FakeReader();
        Video refill = new Video("7300000000000000015");
        LiteVideoRegion.made(refill, reader.at("$.aweme_list[0]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[0].region", "KH")));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        LiteVideoRegion.clear();
        assertEquals("KH", LiteVideoRegion.region(new Video("7300000000000000015")));
    }

    @Test
    public void aVideoStillBeingReadIsSavedOnceItHasItsId() {
        FakeReader reader = new FakeReader();
        Video made = new Video(null);
        LiteVideoRegion.made(made, reader.at("$.aweme_list[4]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[4].region", "PK")));
        assertNull("another row's lookup while Gson is still reading it",
                LiteVideoRegion.region(new Video("7300000000000000011")));

        made.aid = "7300000000000000012";
        assertEquals("PK", LiteVideoRegion.region(made));
        LiteVideoRegion.clear();
        assertEquals("PK", LiteVideoRegion.region(new Video("7300000000000000012")));
    }

    @Test
    public void onlyCodesWithoutTheSeparatorsAreSaved() {
        FakeReader reader = new FakeReader();
        Video odd = new Video("7300000000000000013");
        Video fine = new Video("7300000000000000014");
        LiteVideoRegion.made(odd, reader.at("$.aweme_list[0]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[0].region", "U=S;x")));
        LiteVideoRegion.made(fine, reader.at("$.aweme_list[1]", Token.BEGIN_OBJECT));
        assertTrue(LiteVideoRegion.skipping(reader.at("$.aweme_list[1].region", "ID")));
        assertEquals("U=S;x", LiteVideoRegion.region(odd));

        LiteVideoRegion.clear();
        assertNull(LiteVideoRegion.region(new Video("7300000000000000013")));
        assertEquals("ID", LiteVideoRegion.region(new Video("7300000000000000014")));
        assertEquals("7300000000000000014=ID", savedFile().getString("by_id", null));
    }
}
