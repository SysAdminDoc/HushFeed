package app.morphe.extension.shared.settings;

import static org.junit.Assert.*;

import app.morphe.extension.tiktok.SettingsContextRule;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PatchDefaultsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private void defaults(JSONObject values) throws Exception {
        Field field = PatchDefaults.class.getDeclaredField("defaults");
        field.setAccessible(true);
        field.set(null, values);
    }

    @After public void restore() throws Exception {
        defaults(null);
        Setting.setPausedForProcess(false);
        Setting.preferences.preferences.edit().remove("patch_test_switch")
                .remove("patch_test_country").commit();
    }

    @Test public void overridesAreRealDefaultsButSavedChoicesWin() throws Exception {
        defaults(new JSONObject("{\"patch_test_switch\":true}"));
        BooleanSetting first = new BooleanSetting("patch_test_switch", false, false, false);
        assertTrue(first.defaultValue);
        assertTrue(first.get());
        assertTrue(first.save(false));
        // Reload the saved choice from SharedPreferences rather than relying on the cached value.
        first.load();
        assertFalse(first.get());
        assertTrue(first.resetToDefault());
        assertFalse(Setting.preferences.preferences.contains("patch_test_switch"));
        first.load();
        assertTrue(first.get());
    }

    @Test public void pauseUsesNeutralStockValuesAndKeepsCustomDefaults() throws Exception {
        defaults(new JSONObject("{\"patch_test_country\":\"jp\"}"));
        StringSetting country = new StringSetting("patch_test_country", "default", false, false);
        assertEquals("jp", country.defaultValue);
        Setting.setPausedForProcess(true);
        assertEquals("default", country.get());
        assertEquals("jp", country.savedValue());
        Setting.setPausedForProcess(false);
        assertEquals("jp", country.get());
    }

    @Test public void resolverSupportsEverySettingTypeAndRejectsLossyNumbers() throws Exception {
        JSONObject values = SettingsJson.parseObject("{\"b\":true,\"s\":\"jp\",\"i\":42,"
                + "\"l\":9223372036854775807,\"f\":1.5,\"e\":\"RU\"}");
        assertEquals(Boolean.TRUE, PatchDefaults.resolve(values, "b", false));
        assertEquals("jp", PatchDefaults.resolve(values, "s", "us"));
        assertEquals(Integer.valueOf(42), PatchDefaults.resolve(values, "i", 0));
        assertEquals(Long.valueOf(Long.MAX_VALUE), PatchDefaults.resolve(values, "l", 0L));
        assertEquals(Float.valueOf(1.5f), PatchDefaults.resolve(values, "f", 1f));
        assertEquals(AppLanguage.RU, PatchDefaults.resolve(values, "e", AppLanguage.DEFAULT));
        assertEquals(Integer.valueOf(3), PatchDefaults.resolve(new JSONObject("{\"n\":1.5}"), "n", 3));
        assertEquals(Integer.valueOf(3), PatchDefaults.resolve(new JSONObject("{\"n\":2147483648}"), "n", 3));
        assertEquals(Boolean.FALSE, PatchDefaults.resolve(new JSONObject("{\"n\":\"true\"}"), "n", false));
        assertEquals("unchanged", PatchDefaults.resolve(values, "missing", "unchanged"));
    }
}
