package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;

import android.os.Looper;
import app.morphe.extension.shared.BuildNames;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The scrolling comments, several-photos, LIVE auto-translate and paused-video visual search
 * presets from TikTok's regional builds (#98).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeatureGateRegionPresetsTest {
    private static final String DANMAKU = "danmaku_exp";
    private static final String LANDSCAPE = "landscape_support_danmaku";
    private static final String PHOTOS = "comment_multi_photo_group";
    private static final String LIVE_TRANSLATE = "live_auto_translate_model_audience";
    private static final String VISION = "enable_vision_search";
    private static final String FEED_TAG = "visual_search_enable_feed_tag";
    private static final String AFTER_BLOCK = "show_tag_after_circle_search_block";
    private static final String[] PAUSE_TAG = {VISION, FEED_TAG, AFTER_BLOCK};
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Map<String, FeatureGateCatalog.Entry> catalog;

    @Before public void setup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        BuildNames.setRunningBuildForTests("47.9.2");
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabUndo.resetForTests();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        catalog = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (FeatureGateCatalog.Entry entry : FeatureGateCatalog.readStaticCatalog("47.9.2")) {
            if (entry.manager.equals("abmock") && (entry.key.equals(DANMAKU)
                    || entry.key.equals(LANDSCAPE) || entry.key.equals(PHOTOS) || entry.key.equals(VISION)
                    || entry.key.equals(FEED_TAG) || entry.key.equals(AFTER_BLOCK))) {
                catalog.put(entry.identity(), entry);
                types.put(entry.key, entry.type);
            }
            if (entry.manager.equals("live") && entry.key.equals(LIVE_TRANSLATE)) {
                catalog.put(entry.identity(), entry);
            }
        }
        assertEquals("all seven keys are in the shipped catalog", 7, catalog.size());
        var cachedTypes = FeatureGateCatalog.class.getDeclaredField("cachedAbTypes");
        cachedTypes.setAccessible(true);
        cachedTypes.set(null, Map.copyOf(types));
    }

    @After public void cleanup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabUndo.resetForTests();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        BuildNames.setRunningBuildForTests(null);
    }

    @Test public void scrollingCommentsSetBothGroupsAndUndoPutsTikToksValuesBack() throws Exception {
        FeatureGateLabStore.saveRule("abmock", PHOTOS, "INT", "1", true);
        FeatureGateLabStore.setMasterEnabled(true);
        JSONObject before = FeatureGateLabStore.exportSettings();

        FeatureGateLabUndo.applyPreset("47.9.2", "scrolling_comments", catalog);
        assertEquals(3, FeatureGateLabStore.rules().size());
        for (int stock : new int[]{0, 1, 2}) {
            assertEquals(1, FeatureGateLabRuntime.overrideInt(DANMAKU, stock));
            assertEquals(1, FeatureGateLabRuntime.overrideInt(LANDSCAPE, stock));
        }
        assertEquals(Integer.valueOf(1), FeatureGateLabRuntime.overrideRawAbValue(DANMAKU, 0, false));
        assertEquals("a rule the preset doesn't name stays as it was", 1,
                FeatureGateLabRuntime.overrideInt(PHOTOS, 0));

        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.settingsMatch(before));
        assertEquals(0, FeatureGateLabRuntime.overrideInt(DANMAKU, 0));
        assertEquals(0, FeatureGateLabRuntime.overrideInt(LANDSCAPE, 0));
    }

    @Test public void severalPhotosSelectGroupTwoOnlyWhileOverridesAreOn() throws Exception {
        FeatureGateLabUndo.applyPreset("47.9.2", "comment_photos", catalog);
        assertEquals(1, FeatureGateLabStore.rules().size());
        assertFalse("a preset never turns the overrides switch on", FeatureGateLabStore.masterEnabled());
        assertEquals("off leaves TikTok's value", 0, FeatureGateLabRuntime.overrideInt(PHOTOS, 0));

        FeatureGateLabStore.setMasterEnabled(true);
        for (int stock : new int[]{0, 1, 3}) {
            assertEquals(2, FeatureGateLabRuntime.overrideInt(PHOTOS, stock));
        }
        assertEquals(0, FeatureGateLabRuntime.overrideInt(DANMAKU, 0));

        FeatureGateLabStore.setMasterEnabled(false);
        assertEquals(1, FeatureGateLabRuntime.overrideInt(PHOTOS, 1));
    }

    @Test public void pausedVisualSearchSetsItsThreeKeysToOneOnlyWhileOverridesAreOn() throws Exception {
        FeatureGateLabUndo.applyPreset("47.9.2", "visual_search_pause", catalog);
        assertEquals(3, FeatureGateLabStore.rules().size());
        assertFalse("a preset never turns the overrides switch on", FeatureGateLabStore.masterEnabled());
        for (String key : PAUSE_TAG) {
            assertEquals("off leaves TikTok's " + key, 0, FeatureGateLabRuntime.overrideInt(key, 0));
        }

        FeatureGateLabStore.setMasterEnabled(true);
        for (String key : PAUSE_TAG) {
            for (int stock : new int[]{0, 2}) {
                assertEquals(key, 1, FeatureGateLabRuntime.overrideInt(key, stock));
            }
        }
        assertEquals("other keys stay TikTok's", 0, FeatureGateLabRuntime.overrideInt(PHOTOS, 0));

        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        for (String key : PAUSE_TAG) {
            assertEquals(0, FeatureGateLabRuntime.overrideInt(key, 0));
        }
    }

    @Test public void liveTranslateWritesTheLiveSettingAndNotAnAbKey() throws Exception {
        FeatureGateLabUndo.applyPreset("47.9.2", "live_translate", catalog);
        assertEquals(1, FeatureGateLabStore.rules().size());
        FeatureGateLabStore.Rule rule = FeatureGateLabStore.rule("live", LIVE_TRANSLATE, "INT");
        assertNotNull("the rule is a LIVE one", rule);
        assertEquals("2", rule.value);
        assertNull(FeatureGateLabStore.rule("abmock", LIVE_TRANSLATE, "INT"));
        assertFalse("a preset never turns the overrides switch on", FeatureGateLabStore.masterEnabled());
        assertEquals("off leaves TikTok's value", 0, FeatureGateLabRuntime.overrideLiveInt(LIVE_TRANSLATE, 0));

        FeatureGateLabStore.setMasterEnabled(true);
        for (int stock : new int[]{0, 1}) {
            assertEquals(2, FeatureGateLabRuntime.overrideLiveInt(LIVE_TRANSLATE, stock));
        }
        assertEquals("the AB reader of the same name is left alone", 0,
                FeatureGateLabRuntime.overrideInt(LIVE_TRANSLATE, 0));

        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertEquals(0, FeatureGateLabRuntime.overrideLiveInt(LIVE_TRANSLATE, 0));
    }

    @Test public void liveTranslateRefusesAMissingKeyAndAnotherBuild() throws Exception {
        Map<String, FeatureGateCatalog.Entry> missing = new LinkedHashMap<>(catalog);
        missing.remove("live\n" + LIVE_TRANSLATE);
        assertThrows(JSONException.class,
                () -> FeatureGateLabUndo.applyPreset("47.9.2", "live_translate", missing));
        BuildNames.setRunningBuildForTests("47.2.1");
        assertThrows(JSONException.class,
                () -> FeatureGateLabUndo.applyPreset("47.9.2", "live_translate", catalog));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
    }

    @Test public void eitherPresetRefusesMissingOrMistypedKeysAndOtherBuilds() throws Exception {
        for (String[] preset : new String[][]{
                {"scrolling_comments", DANMAKU, "BOOLEAN"}, {"scrolling_comments", LANDSCAPE, "STRING"},
                {"comment_photos", PHOTOS, "BOOLEAN"}, {"visual_search_pause", VISION, "BOOLEAN"},
                {"visual_search_pause", FEED_TAG, "STRING"}, {"visual_search_pause", AFTER_BLOCK, "BOOLEAN"}}) {
            Map<String, FeatureGateCatalog.Entry> missing = new LinkedHashMap<>(catalog);
            missing.remove("abmock\n" + preset[1]);
            assertThrows(JSONException.class,
                    () -> FeatureGateLabUndo.applyPreset("47.9.2", preset[0], missing));
            Map<String, FeatureGateCatalog.Entry> mistyped = new LinkedHashMap<>(catalog);
            mistyped.put("abmock\n" + preset[1], new FeatureGateCatalog.Entry(
                    preset[1], preset[1], "abmock", preset[2], true, true,
                    List.of(), List.of(), List.of(), "", "", false, null, null));
            assertThrows(JSONException.class,
                    () -> FeatureGateLabUndo.applyPreset("47.9.2", preset[0], mistyped));
            BuildNames.setRunningBuildForTests("47.2.1");
            assertThrows(JSONException.class,
                    () -> FeatureGateLabUndo.applyPreset("47.9.2", preset[0], catalog));
            BuildNames.setRunningBuildForTests("47.9.2");
        }
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertFalse(FeatureGateLabUndo.canUndo());
    }
}
