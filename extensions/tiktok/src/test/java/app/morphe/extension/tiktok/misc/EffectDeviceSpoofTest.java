/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The model the effect platform sends, with the switch off, on and paused (issue #176). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EffectDeviceSpoofTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.resetToDefault();
        Settings.EFFECT_PHONE_MODEL.resetToDefault();
    }

    @Test public void offTheRealModelGoesOut() {
        assertEquals(Boolean.FALSE, Settings.REPORT_NEWER_PHONE_TO_EFFECTS.defaultValue);
        assertEquals("Redmi A3", EffectDeviceSpoof.deviceType("Redmi A3"));
    }

    @Test public void onTheNewerModelGoesOut() {
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.save(true);
        assertEquals("SM-S928B", EffectDeviceSpoof.deviceType("Redmi A3"));
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.save(false);
        assertEquals("Redmi A3", EffectDeviceSpoof.deviceType("Redmi A3"));
    }

    @Test public void pausedTheRealModelGoesOutWithTheSwitchOn() {
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.save(true);
        PausedProcess.set(true);
        assertEquals("Redmi A3", EffectDeviceSpoof.deviceType("Redmi A3"));
    }

    @Test public void aMissingModelStaysMissingWhenOff() {
        assertNull(EffectDeviceSpoof.deviceType(null));
    }

    @Test public void aTypedModelGoesOutInsteadOfTheGalaxy() {
        assertEquals("", Settings.EFFECT_PHONE_MODEL.defaultValue);
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.save(true);
        Settings.EFFECT_PHONE_MODEL.save("  Pixel 9 Pro ");
        assertEquals("Pixel 9 Pro", EffectDeviceSpoof.deviceType("Redmi A3"));
        Settings.EFFECT_PHONE_MODEL.save("SM-S938B");
        assertEquals("SM-S938B", EffectDeviceSpoof.deviceType("Redmi A3"));
    }

    @Test public void aTypedModelStaysHomeWhileTheSwitchIsOffOrPaused() {
        Settings.EFFECT_PHONE_MODEL.save("Pixel 9 Pro");
        assertEquals("Redmi A3", EffectDeviceSpoof.deviceType("Redmi A3"));
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.save(true);
        PausedProcess.set(true);
        assertEquals("Redmi A3", EffectDeviceSpoof.deviceType("Redmi A3"));
    }

    @Test public void aBlankOrUnusableModelFallsBackToTheGalaxy() {
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.save(true);
        for (String typed : new String[] {"", "   ", "Pixel 9\nPro", "-Pixel", "Pixel&os=ios", "x".repeat(41)}) {
            Settings.EFFECT_PHONE_MODEL.save(typed);
            assertEquals(typed, "SM-S928B", EffectDeviceSpoof.deviceType("Redmi A3"));
        }
    }

    @Test public void modelNamesTakeLettersDigitsAndTheMarksModelsUse() {
        for (String model : new String[] {"SM-S938B", "Pixel 9 Pro", "moto g(60)", "ONEPLUS A6013", "M2101K6G",
                "SM_S901U", "SM+S901U", "a", "x".repeat(40)}) {
            assertEquals(model, true, EffectDeviceSpoof.isModelName(model));
        }
        for (String model : new String[] {null, "", " SM-S938B", ".S938B", "SM/S938B", "iPhone15,3",
                "Pixel 9 Pro" + (char) 0xE9, "x".repeat(41), "a;b", "a\tb"}) {
            assertEquals(String.valueOf(model), false, EffectDeviceSpoof.isModelName(model));
        }
    }

    @Test public void theRowSaysWhatItTakes() {
        assertNull(EffectDeviceSpoof.modelProblem(""));
        assertNull(EffectDeviceSpoof.modelProblem("  "));
        assertNull(EffectDeviceSpoof.modelProblem(" Pixel 9 Pro "));
        assertEquals("Use up to 40 letters and numbers. Spaces, dots, dashes and brackets are fine too.",
                EffectDeviceSpoof.modelProblem("Pixel/9"));
    }

    /** The settings list (#176): the empty default first, then phones that each go out exactly as listed. */
    @Test public void everyListedPhoneGoesOutAsListed() {
        String[] models = EffectDeviceSpoof.listedModels();
        String[] labels = EffectDeviceSpoof.listedLabels();
        assertEquals(models.length, labels.length);
        assertEquals("", models[0]);
        assertEquals("Galaxy S24 Ultra (SM-S928B), the default", labels[0]);
        assertEquals("Galaxy S25 Ultra (SM-S938B)", labels[1]);
        Settings.REPORT_NEWER_PHONE_TO_EFFECTS.save(true);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 1; i < models.length; i++) {
            assertNull(models[i], EffectDeviceSpoof.modelProblem(models[i]));
            org.junit.Assert.assertTrue("listed twice: " + models[i], seen.add(models[i]));
            org.junit.Assert.assertTrue(labels[i], labels[i].startsWith(EffectDeviceSpoof.LISTED_PHONES[i - 1][0]));
            Settings.EFFECT_PHONE_MODEL.save(models[i]);
            assertEquals(models[i], EffectDeviceSpoof.deviceType("Redmi A3"));
        }
        org.junit.Assert.assertFalse("the default is listed a second time", seen.contains("SM-S928B"));
        assertEquals("a Pixel reads as its own name, once", "Pixel 9 Pro",
                labels[java.util.Arrays.asList(models).indexOf("Pixel 9 Pro")]);
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.effectDeviceSpoofEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("report_newer_phone_to_effects"));
            assertNotNull(screen.findPreference("effect_phone_model"));
        } finally {
            SettingsStatus.effectDeviceSpoofEnabled = false;
        }
    }
}
