package app.morphe.extension.tiktok.spoof.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.telephony.TelephonyManager;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowTelephonyManager;

/**
 * #415: with the US preset on a phone on a Bangladeshi network, 317 of 340 image fetches came
 * back 403 until a VPN was on. TikTok's network stack was routing by the preset. Its getters'
 * answers come through here, and each one still holding the preset gets the phone's own SIM
 * back, while anything else, and everything with the spoof off or no SIM in the phone, stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NetworkStackSimTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String INIT =
        "carrierregion=US;mccmnc=310260;sysregion=BD;appregion=US";

    @Before public void setUp() {
        ImageLoads.resetForTests();
        Settings.SIM_SPOOF.save(true);
        Settings.SIM_SPOOF_ISO.save("us");
        Settings.SIMSPOOF_MCCMNC.save("310260");
        phone("bd", "bd", "47001", "47001");
    }

    @After public void tearDown() {
        ImageLoads.resetForTests();
        Settings.SIM_SPOOF.resetToDefault();
        Settings.SIM_SPOOF_ISO.resetToDefault();
        Settings.SIMSPOOF_MCCMNC.resetToDefault();
    }

    private static void phone(String simCountry, String networkCountry, String simOperator, String networkOperator) {
        TelephonyManager phone = (TelephonyManager)
            RuntimeEnvironment.getApplication().getSystemService(Context.TELEPHONY_SERVICE);
        ShadowTelephonyManager shadow = Shadows.shadowOf(phone);
        shadow.setSimCountryIso(simCountry);
        shadow.setNetworkCountryIso(networkCountry);
        shadow.setSimOperator(simOperator);
        shadow.setNetworkOperator(networkOperator);
    }

    @Test public void thePresetGoesBackToTheRealSimInTheCaseTikTokWroteIt() {
        assertEquals("BD", NetworkStackSim.region("US"));
        assertEquals("bd", NetworkStackSim.region("us"));
        assertEquals("47001", NetworkStackSim.networkOperator("310260"));
        assertEquals("47001", NetworkStackSim.simOperator("310260"));
        assertEquals("carrierregion=BD;mccmnc=47001;sysregion=BD;appregion=BD", NetworkStackSim.initRegion(INIT));
        assertEquals(4 + 3, NetworkStackSim.swapped());
    }

    @Test public void anAnswerThatIsNotThePresetStays() {
        // The app region falls back to the language setting's region when there's no carrier one.
        assertEquals("GB", NetworkStackSim.region("GB"));
        assertEquals("", NetworkStackSim.region(""));
        assertNull(NetworkStackSim.region(null));
        assertEquals("", NetworkStackSim.networkOperator(""));
        assertEquals("23415", NetworkStackSim.simOperator("23415"));
        assertEquals("carrierregion=;mccmnc=;sysregion=US;appregion=GB",
            NetworkStackSim.initRegion("carrierregion=;mccmnc=;sysregion=US;appregion=GB"));
        assertEquals("not a header", NetworkStackSim.initRegion("not a header"));
        assertEquals(0, NetworkStackSim.swapped());
    }

    @Test public void theSystemRegionIsTheLanguageSettingAndStaysEvenWhenItMatches() {
        assertEquals("carrierregion=BD;mccmnc=47001;sysregion=US;appregion=BD",
            NetworkStackSim.initRegion("carrierregion=US;mccmnc=310260;sysregion=US;appregion=US"));
    }

    @Test public void withTheSpoofOffNothingChanges() {
        Settings.SIM_SPOOF.save(false);
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("310260", NetworkStackSim.networkOperator("310260"));
        assertEquals(INIT, NetworkStackSim.initRegion(INIT));
        assertEquals(0, NetworkStackSim.swapped());
    }

    @Test public void aPhoneWithNoSimKeepsWhatItWasTold() {
        phone("", "", "", "");
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("310260", NetworkStackSim.networkOperator("310260"));
        assertEquals(INIT, NetworkStackSim.initRegion(INIT));
    }

    @Test public void theNetworkWinsWhereTheSimIsBlankAndTheOtherWayRound() {
        phone("", "bd", "", "47002");
        assertEquals("BD", NetworkStackSim.region("US"));
        assertEquals("47002", NetworkStackSim.networkOperator("310260"));
        assertEquals("47002", NetworkStackSim.simOperator("310260"));
        phone("bd", "in", "47001", "40445");
        assertEquals("BD", NetworkStackSim.region("US"));
        assertEquals("40445", NetworkStackSim.networkOperator("310260"));
        assertEquals("47001", NetworkStackSim.simOperator("310260"));
    }

    @Test public void anUnusablePresetIsNotOneTikTokWasToldSoNothingIsSwapped() {
        Settings.SIM_SPOOF_ISO.save("zz");
        Settings.SIMSPOOF_MCCMNC.save("31026a");
        assertEquals("ZZ", NetworkStackSim.region("ZZ"));
        assertEquals("31026a", NetworkStackSim.networkOperator("31026a"));
    }

    @Test public void theExportSaysHowManyAnswersWentBack() {
        NetworkStackSim.region("US");
        NetworkStackSim.networkOperator("310260");
        assertEquals("SIM spoof: on, US, network stack kept on the real SIM (2 answers)",
            ImageLoads.placement(null).get(0));
        ImageLoads.resetForTests();
        assertEquals("SIM spoof: on, US", ImageLoads.placement(null).get(0));
    }
}
