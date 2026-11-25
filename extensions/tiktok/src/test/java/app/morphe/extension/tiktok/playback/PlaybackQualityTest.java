package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.*;
import android.os.Looper;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlaybackQualityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @After public void tearDown() {
        SettingsStatus.playbackQualityEnabled = false;
        Settings.PLAYBACK_BITRATE_LIMIT.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
    }
    private static Map<String, Object> stream(String name, int width, int height, int rate) {
        return Map.of("gear_name", name, "vwidth", width, "vheight", height, "bitrate", rate,
                "main_url", "https://example.com/" + name, "audio_file_id", "sound");
    }
    private static Map<String, Object> model() {
        return Map.of("video_id", "one", "dynamic_video", Map.of(
                "dynamic_type", "segment_base",
                "dynamic_video_list", List.of(stream("1080p", 1080, 1920, 400), stream("360p", 360, 640, 100)),
                "dynamic_audio_list", List.of(Map.of("file_id", "sound", "main_url", "https://example.com/audio"))));
    }

    /** #454: one heavy video drops to a lighter gear, and ordinary ones keep what they had. */
    @Test public void theBitrateLimitTakesOutOnlyTheGearsOverIt() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        PlaybackQuality.resetForTests();
        var light = new AdvancedDownloadsTest.Gear("360p", 900_000, "https://example.com/light");
        var middle = new AdvancedDownloadsTest.Gear("720p", 1_800_000, "https://example.com/middle");
        var heavy = new AdvancedDownloadsTest.Gear("1080p", 50_000_000, "https://example.com/heavy");
        var gears = List.of(heavy, middle, light);
        Object cached = new Object();
        Settings.PLAYBACK_QUALITY.save("auto");
        assertSame("no limit leaves the list alone", gears, PlaybackQuality.filterVideoGears(gears));
        assertSame(cached, PlaybackQuality.cacheModel(cached));

        Settings.PLAYBACK_BITRATE_LIMIT.save("2");
        assertEquals(List.of(middle, light), PlaybackQuality.filterVideoGears(gears));
        assertEquals(List.of(middle, light), PlaybackQuality.filterPlayerUrlModelGears(gears));
        assertNull("TikTok's parsed model would keep the heavy gear", PlaybackQuality.cacheModel(cached));
        var ordinary = List.of(middle, light);
        assertSame("nothing over the limit, nothing changes", ordinary, PlaybackQuality.filterVideoGears(ordinary));

        var alsoHeavy = new AdvancedDownloadsTest.Gear("720p", 20_000_000, "https://example.com/also");
        assertEquals("nothing fits, so the lightest plays", List.of(alsoHeavy),
                PlaybackQuality.filterVideoGears(List.of(heavy, alsoHeavy)));

        Settings.PLAYBACK_QUALITY.save("highest");
        assertEquals("a chosen quality picks inside the limit", List.of(middle),
                PlaybackQuality.filterVideoGears(gears));
    }

    /** #533: 1.5 Mb/s sits between 1 and 2, and keeps a 1.4 Mb/s gear while dropping a 1.6 one. */
    @Test public void aLimitOfOneAndAHalfMegabitsIsAChoiceAndHolds() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        PlaybackQuality.resetForTests();
        assertEquals(List.of("off", "1", "1.5", "2", "4", "8", "16"), List.of(PlaybackQuality.BITRATE_LIMITS));
        var under = new AdvancedDownloadsTest.Gear("540p", 1_400_000, "https://example.com/under");
        var over = new AdvancedDownloadsTest.Gear("720p", 1_600_000, "https://example.com/over");
        Settings.PLAYBACK_QUALITY.save("auto");
        Settings.PLAYBACK_BITRATE_LIMIT.save("1.5");
        assertEquals(1_500_000L, PlaybackQuality.bitrateCap());
        assertEquals(List.of(under), PlaybackQuality.filterVideoGears(List.of(over, under)));

        Settings.PLAYBACK_BITRATE_LIMIT.save("nonsense");
        assertEquals("a value it can't read is no limit", 0L, PlaybackQuality.bitrateCap());
    }

    @Test public void theBitrateLimitReachesTheAdaptiveModels() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        PlaybackQuality.resetForTests();
        Map<String, Object> original = Map.of("video_id", "one", "dynamic_video", Map.of(
                "dynamic_type", "segment_base",
                "dynamic_video_list", List.of(stream("1080p", 1080, 1920, 50_000_000),
                        stream("720p", 720, 1280, 1_800_000), stream("360p", 360, 640, 900_000)),
                "dynamic_audio_list", List.of(Map.of("file_id", "sound", "main_url", "https://example.com/audio"))));
        Settings.PLAYBACK_QUALITY.save("auto");
        Settings.PLAYBACK_BITRATE_LIMIT.save("2");

        Map<?, ?> dynamic = (Map<?, ?>) PlaybackQuality.filterMap(original).get("dynamic_video");
        List<?> kept = (List<?>) dynamic.get("dynamic_video_list");
        assertEquals(2, kept.size());
        assertEquals("720p", ((Map<?, ?>) kept.get(0)).get("gear_name"));

        String json = new JSONObject(original).toString();
        var filtered = new JSONObject(PlaybackQuality.filterVideoModelJson(json)).getJSONObject("dynamic_video");
        assertEquals(2, filtered.getJSONArray("dynamic_video_list").length());
        assertEquals(1, filtered.getJSONArray("dynamic_audio_list").length());

        Settings.PLAYBACK_BITRATE_LIMIT.save("1");
        filtered = new JSONObject(PlaybackQuality.filterVideoModelJson(json)).getJSONObject("dynamic_video");
        assertEquals("a new limit is read again", 1, filtered.getJSONArray("dynamic_video_list").length());
    }

    @Test public void listSelectionDoesNotMutateDownloadVariantsOrReenterNativeGetter() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        var low = new AdvancedDownloadsTest.Gear("360p", 100, "https://example.com/low");
        var high = new AdvancedDownloadsTest.Gear("1080p", 400, "https://example.com/high");
        var original = List.of(high, low);
        Settings.PLAYBACK_QUALITY.save("auto");
        assertSame(original, PlaybackQuality.filterVideoGears(original));
        Object cached = new Object();
        assertSame(cached, PlaybackQuality.cacheModel(cached));
        Settings.PLAYBACK_QUALITY.save("lowest");
        assertEquals(List.of(low), PlaybackQuality.filterVideoGears(original));
        assertNull(PlaybackQuality.cacheModel(cached));
        assertEquals(List.of(high, low), original);
        Settings.PLAYBACK_QUALITY.save("highest");
        assertEquals(List.of(high), PlaybackQuality.filterVideoGears(original));
        assertNull(PlaybackQuality.filterVideoGears(null));
    }

    @Test public void adaptiveMapKeepsAudioAndLeavesNativeCacheUntouched() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PLAYBACK_QUALITY.save("lowest");
        Map<String, Object> original = model();
        Map<?, ?> originalDynamic = (Map<?, ?>) original.get("dynamic_video");
        Map<?, ?> result = PlaybackQuality.filterMap(original);
        Map<?, ?> dynamic = (Map<?, ?>) result.get("dynamic_video");
        List<?> video = (List<?>) dynamic.get("dynamic_video_list");
        assertEquals(1, video.size());
        assertEquals("360p", ((Map<?, ?>) video.get(0)).get("gear_name"));
        assertSame(originalDynamic.get("dynamic_audio_list"), dynamic.get("dynamic_audio_list"));
        assertEquals(2, ((List<?>) originalDynamic.get("dynamic_video_list")).size());
        assertEquals("one", result.get("video_id"));
        Settings.PLAYBACK_QUALITY.save("auto");
        assertSame(original, PlaybackQuality.filterMap(original));
    }

    /**
     * TikTok reads both model getters for a video and a prefetched neighbour between them. The
     * cache held one entry, so every switch parsed and wrote the model again; each is parsed once
     * now, and a change of quality is still its own entry.
     */
    @Test public void alternatingGettersAndANeighbourParseEachModelOnce() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        PlaybackQuality.resetForTests();
        Settings.PLAYBACK_QUALITY.save("lowest");
        String playing = new JSONObject(model()).toString();
        String neighbour = playing.replace("\"one\"", "\"two\"");
        String first = PlaybackQuality.filterVideoModelJson(playing);
        for (int round = 0; round < 3; round++) {
            assertEquals(first, PlaybackQuality.filterVideoModelJson(playing));
            PlaybackQuality.filterDashVideoModelJson(playing);
            PlaybackQuality.filterVideoModelJson(neighbour);
        }
        assertEquals("one parse per getter and model", 3, PlaybackQuality.parsedForTests);
        Settings.PLAYBACK_QUALITY.save("highest");
        PlaybackQuality.filterVideoModelJson(playing);
        assertEquals("a new quality is read again", 4, PlaybackQuality.parsedForTests);
    }

    @Test public void adaptiveJsonTracksSettingChangesAndPreservesAudioMetadata() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String original = new JSONObject(model()).toString();
        Settings.PLAYBACK_QUALITY.save("lowest");
        JSONObject low = new JSONObject(PlaybackQuality.filterVideoModelJson(original));
        JSONObject dynamic = low.getJSONObject("dynamic_video");
        assertEquals("360p", dynamic.getJSONArray("dynamic_video_list").getJSONObject(0).getString("gear_name"));
        assertEquals("sound", dynamic.getJSONArray("dynamic_audio_list").getJSONObject(0).getString("file_id"));
        Settings.PLAYBACK_QUALITY.save("highest");
        JSONObject high = new JSONObject(PlaybackQuality.filterVideoModelJson(original));
        assertEquals("1080p", high.getJSONObject("dynamic_video").getJSONArray("dynamic_video_list").getJSONObject(0).getString("gear_name"));
        Settings.PLAYBACK_QUALITY.save("auto");
        assertSame(original, PlaybackQuality.filterVideoModelJson(original));
        Settings.PLAYBACK_QUALITY.save("lowest");
        assertEquals("invalid", PlaybackQuality.filterVideoModelJson("invalid"));
        assertNull(PlaybackQuality.filterVideoModelJson(null));
    }

    @Test public void mobileDataOnlyEverLowersTheQuality() {
        // The ceiling applies on a metered connection.
        assertEquals("360", PlaybackQuality.effectiveMode("highest", "360", true));
        assertEquals("720", PlaybackQuality.effectiveMode("auto", "720", true));
        assertEquals("lowest", PlaybackQuality.effectiveMode("1080", "lowest", true));

        // It never raises it, however the two choices are set.
        assertEquals("360", PlaybackQuality.effectiveMode("360", "1080", true));
        assertEquals("lowest", PlaybackQuality.effectiveMode("lowest", "highest", true));
        assertEquals("480", PlaybackQuality.effectiveMode("480", "highest", true));
    }

    @Test public void anUnmeteredConnectionKeepsTheMainChoice() {
        assertEquals("highest", PlaybackQuality.effectiveMode("highest", "360", false));
        assertEquals("auto", PlaybackQuality.effectiveMode("auto", "lowest", false));
        assertEquals("1080", PlaybackQuality.effectiveMode("1080", "360", false));
    }

    @Test public void noLimitOnMobileDataChangesNothing() {
        assertEquals("highest", PlaybackQuality.effectiveMode("highest", "off", true));
        assertEquals("auto", PlaybackQuality.effectiveMode("auto", "off", true));
        assertEquals("360", PlaybackQuality.effectiveMode("360", null, true));
    }

    @Test public void aMeteredCeilingPicksTheLowerGear() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String original = new JSONObject(model()).toString();

        // Same stub, same model: only the connection differs.
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.effectiveMode("highest", "360", false));
        JSONObject unmetered = new JSONObject(PlaybackQuality.filterVideoModelJson(original));
        assertEquals("1080p", unmetered.getJSONObject("dynamic_video")
                .getJSONArray("dynamic_video_list").getJSONObject(0).getString("gear_name"));

        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.effectiveMode("highest", "360", true));
        JSONObject metered = new JSONObject(PlaybackQuality.filterVideoModelJson(original));
        assertEquals("360p", metered.getJSONObject("dynamic_video")
                .getJSONArray("dynamic_video_list").getJSONObject(0).getString("gear_name"));
    }

    @Test public void standalonePatchShowsPlaybackChoice() throws Exception {
        try (var controller = Robolectric.buildActivity(AdvancedDownloadsTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            SettingsStatus.playbackQualityEnabled = true;
            Settings.PLAYBACK_QUALITY.save("lowest");
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("playback_quality"));
            assertNotNull(screen.findPreference("playback_quality_metered"));
            assertNotNull(screen.findPreference("playback_bitrate_limit"));
            activity.setPreferenceScreen(screen);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            app.morphe.extension.tiktok.UiCapture.save(activity.getWindow().getDecorView(), "playback-settings.png");
        }
    }
}
