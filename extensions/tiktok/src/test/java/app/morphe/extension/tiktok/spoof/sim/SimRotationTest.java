package app.morphe.extension.tiktok.spoof.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.region.RegionSpoof;

/**
 * Several ticked SIM presets take turns, one per start of TikTok (#538). TikTok keeps the SIM it
 * read as it started, so a turn happens once per process, before the first read, and every
 * reader of the three fields (the SIM getters, the store region, the request region) answers
 * that start's country.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SimRotationTest {
    private static final String REAL_COUNTRY = "us";
    private static final String REAL_OPERATOR = "310260";

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SimRotation.resetForTests();
        Settings.SIM_SPOOF.save(true);
    }

    @After public void tearDown() {
        SimRotation.resetForTests();
        Settings.SIM_SPOOF.save(false);
        Settings.REGION_SPOOF.resetToDefault();
        Settings.REGION_STORE_SPOOF.resetToDefault();
        Settings.SIM_SPOOF_PRESETS.resetToDefault();
        Settings.SIM_SPOOF_ISO.resetToDefault();
        Settings.SIMSPOOF_MCCMNC.resetToDefault();
        Settings.SIMSPOOF_OP_NAME.resetToDefault();
    }

    @Test public void severalTickedTakeTurnsOneEachStart() {
        Settings.REGION_SPOOF.save(true);
        Settings.REGION_STORE_SPOOF.save(true);
        // As Done leaves them: the order the next starts take, the fields on the first.
        Settings.SIM_SPOOF_PRESETS.save("de,fr,jp");
        fill(preset("de"));

        String[] expected = {"de", "fr", "jp", "de", "fr"};
        for (int start = 0; start < expected.length; start++) {
            newStart(true);
            // The first read of the start is what takes the turn.
            assertEquals("start " + start, expected[start], SpoofSimPatch.getCountryIso(REAL_COUNTRY));
            SimPreset taken = preset(expected[start]);
            assertEquals(taken.mccMnc, SpoofSimPatch.getOperator(REAL_OPERATOR));
            assertEquals(taken.operatorName, SpoofSimPatch.getOperatorName("Real"));
            assertEquals("the request region is another country than the SIM's",
                    taken.iso.toUpperCase(java.util.Locale.ROOT), RegionSpoof.country("US"));
            assertEquals("the store region is another country than the SIM's",
                    taken.iso.toUpperCase(java.util.Locale.ROOT), RegionSpoof.storeCountry("US"));
            // Later reads in the same start keep its country.
            SimRotation.ensure();
            assertEquals("the country changed within one start",
                    expected[start], SpoofSimPatch.getCountryIso(REAL_COUNTRY));
        }
        assertEquals("the order after five starts", "jp,de,fr", Settings.SIM_SPOOF_PRESETS.get());
    }

    @Test public void oneTickedChangesNothing() {
        Settings.SIM_SPOOF_PRESETS.save("fr");
        fill(preset("de"));
        for (int start = 0; start < 3; start++) {
            newStart(true);
            assertEquals("de", SpoofSimPatch.getCountryIso(REAL_COUNTRY));
        }
        assertEquals("fr", Settings.SIM_SPOOF_PRESETS.get());
    }

    @Test public void aSecondProcessTakesNoTurnOfItsOwn() {
        Settings.SIM_SPOOF_PRESETS.save("de,fr");
        fill(preset("fr"));
        newStart(false);
        assertEquals("fr", SpoofSimPatch.getCountryIso(REAL_COUNTRY));
        assertEquals("de,fr", Settings.SIM_SPOOF_PRESETS.get());
    }

    @Test public void noTurnWhileTheSimSpoofIsOff() {
        Settings.SIM_SPOOF.save(false);
        Settings.SIM_SPOOF_PRESETS.save("de,fr");
        fill(preset("fr"));
        newStart(true);
        SimRotation.ensure();
        assertEquals("fr", Settings.SIM_SPOOF_ISO.get());
        assertEquals("de,fr", Settings.SIM_SPOOF_PRESETS.get());
    }

    @Test public void detailsTypedByHandStopTheTurns() {
        Settings.SIM_SPOOF_PRESETS.save("de,fr");
        Settings.SIM_SPOOF_ISO.save("de");
        Settings.SIMSPOOF_MCCMNC.save("26202");
        Settings.SIMSPOOF_OP_NAME.save("Vodafone.de");
        newStart(true);
        assertEquals("de", SpoofSimPatch.getCountryIso(REAL_COUNTRY));
        assertEquals("26202", SpoofSimPatch.getOperator(REAL_OPERATOR));
        assertEquals("de,fr", Settings.SIM_SPOOF_PRESETS.get());
    }

    @Test public void theOrderAfterATurnPutsTheTakenPresetLast() {
        SimPreset de = preset("de"), fr = preset("fr"), jp = preset("jp");
        assertEquals(Arrays.asList(fr, jp, de),
                SimRotation.next(Arrays.asList(de, fr, jp), jp.iso, jp.mccMnc, jp.operatorName));
        assertNull("one preset took a turn",
                SimRotation.next(Arrays.asList(de), de.iso, de.mccMnc, de.operatorName));
        assertNull("fields that aren't ticked took a turn",
                SimRotation.next(Arrays.asList(de, fr), jp.iso, jp.mccMnc, jp.operatorName));
    }

    @Test public void aSavedListReadsInItsOrderAndDropsWhatItCantUse() {
        assertEquals(Arrays.asList(preset("jp"), preset("de")),
                SimPresets.listed(" JP,xx,,de,jp"));
        assertEquals("jp,de", SimPresets.saved(SimPresets.listed("jp,de")));
    }

    /** A new process: the next read of the fields decides its preset. */
    private static void newStart(boolean mainProcess) {
        SimRotation.resetForTests();
        SimRotation.mainProcessForTests = mainProcess;
    }

    private static void fill(SimPreset preset) {
        Settings.SIM_SPOOF_ISO.save(preset.iso);
        Settings.SIMSPOOF_MCCMNC.save(preset.mccMnc);
        Settings.SIMSPOOF_OP_NAME.save(preset.operatorName);
    }

    private static SimPreset preset(String iso) {
        SimPreset preset = SimPresets.forCountry(iso);
        if (preset == null) throw new AssertionError("no preset for " + iso);
        return preset;
    }
}
