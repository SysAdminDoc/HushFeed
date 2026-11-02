package app.morphe.extension.tiktok.spoof.region;

import static org.junit.Assert.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;
import app.morphe.extension.tiktok.spoof.sim.SimPreset;
import app.morphe.extension.tiktok.spoof.sim.SimPresets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RegionSpoofTest {
    /** The newest API in the @Config above, and the only run that publishes a picture. */
    private static final int PUBLISHED_CAPTURE_SDK = 28;

    /** The default zone as the test found it, put back after it. */
    private TimeZone startZone;

    /** TZ as native code would read it, kept here instead of in the test JVM's environment. */
    private static final class FakeNativeZone extends RegionSpoof.NativeZone {
        String value;
        int writes;

        @Override String get() {
            return value;
        }

        @Override void set(String id) {
            value = id;
            writes++;
        }
    }

    private RegionSpoof.NativeZone realNativeZone;
    private FakeNativeZone tz;

    @Before public void setup() {
        if (startZone == null) startZone = TimeZone.getDefault();
        if (realNativeZone == null) realNativeZone = RegionSpoof.nativeZone;
        tz = new FakeNativeZone();
        RegionSpoof.nativeZone = tz;
        RegionSpoof.forgetProcessZone();
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SIM_SPOOF.save(true);
        Settings.REGION_SPOOF.save(true);
        Settings.REGION_STORE_SPOOF.save(false);
        Settings.REGION_REQUEST_SPOOF.save(false);
        Settings.SIM_SPOOF_ISO.save("jp");
        Settings.SIMSPOOF_MCCMNC.save("44010");
        Settings.SIMSPOOF_OP_NAME.save("NTT DOCOMO");
        Settings.SIM_SPOOF_FEED_REAL.save(false);
        Settings.SIM_SPOOF_TIME_ZONE.save("");
    }
    @After public void tearDown() {
        RegionSpoof.requestDone();
        SettingsStatus.simSpoofEnabled = false;
        SettingsStatus.regionSpoofEnabled = false;
        PausedProcess.set(false);
        RegionSpoof.forgetProcessZone();
        TimeZone.setDefault(startZone);
        RegionSpoof.nativeZone = realNativeZone;
    }

    @Test public void onlyPassportPathsAreSignIns() {
        for (String path : new String[]{"/passport/user/login/", "/passport/mobile/send_code/v1/",
                "//passport/email/register/v2/", "/passport/"}) {
            assertTrue(path, RegionSpoof.isSignIn(path));
        }
        for (String path : new String[]{null, "", "/", "/aweme/v1/feed/", "/aweme/v1/passport/",
                "/passports/", "/passport", "passport/user/login/", "/Passport/user/login/"}) {
            assertFalse(String.valueOf(path), RegionSpoof.isSignIn(path));
        }
    }

    @Test public void theTokenInterceptorsUrlsAreSignInsByTheirPath() {
        for (String url : new String[]{
                "https://api16-normal-useast5.tiktokv.us/passport/token/beat/v2/?aid=1233&ts=1",
                "https://api.tiktokv.com/passport/token/change/",
                "https://api.tiktokv.com/passport/user/logout/#x"}) {
            assertTrue(url, RegionSpoof.isSignIn(RegionSpoof.pathOf(url)));
        }
        for (String url : new String[]{null, "", "https://api.tiktokv.com",
                "https://api.tiktokv.com?next=/passport/user/logout/",
                "https://api.tiktokv.com/aweme/v1/feed/?next=/passport/token/beat/",
                "https://api.tiktokv.com/aweme/v1/passport/"}) {
            assertFalse(String.valueOf(url), RegionSpoof.isSignIn(RegionSpoof.pathOf(url)));
        }
        assertEquals("/passport/token/beat/", RegionSpoof.pathOf("https://h/passport/token/beat/?a=1#b"));
    }

    @Test public void aMarkWhoseEndNeverCameRunsOut() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        // TikTok's fill threw before requestDone() and the thread sends nothing after it.
        RegionSpoof.requestPath("/passport/user/login/");
        assertEquals("US", RegionSpoof.country("US"));
        ShadowSystemClock.advanceBy(
                Duration.ofMillis(RegionSpoof.MARK_LIFETIME_MS - 1));
        assertEquals("still in the fill's time", "US", RegionSpoof.country("US"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        assertEquals("the mark ran out", "JP", RegionSpoof.country("US"));
        // And it went cleanly: the next sign-in marks the thread again, and its end takes it off.
        RegionSpoof.requestPath("/passport/user/login/");
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
    }

    @Test public void aFillInsideASignInsOwnLeavesTheMarkToTheOuterEnd() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestPath("/passport/user/login/");
        // AppLog's own fill for the same URL, run from inside the handler's.
        RegionSpoof.requestUrl("https://api.tiktokv.com/passport/user/login/?aid=1233");
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("the handler's fill is still a sign-in's", "US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
    }

    @Test public void aSignInAfterAMarkRanOutGetsAFullMarkOfItsOwn() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestPath("/passport/user/login/");
        ShadowSystemClock.advanceBy(Duration.ofMillis(RegionSpoof.MARK_LIFETIME_MS));
        // The first fill threw before its end. The next sign-in comes after its mark ran out.
        RegionSpoof.requestPath("/passport/user/login/");
        ShadowSystemClock.advanceBy(Duration.ofMillis(RegionSpoof.MARK_LIFETIME_MS - 1));
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("its end takes it off", "JP", RegionSpoof.country("US"));
    }

    @Test public void aTokenRequestMarksTheThreadLikeASignIn() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestUrl("https://api.tiktokv.com/passport/token/beat/v2/?aid=1233");
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestUrl("https://api.tiktokv.com/aweme/v1/feed/");
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
    }

    @Test public void aSignInKeepsTheRealRegionOnItsOwnThreadOnly() throws Exception {
        Settings.REGION_REQUEST_SPOOF.save(true);
        Settings.REGION_STORE_SPOOF.save(true);
        Locale locale = Locale.US;
        TimeZone zone = TimeZone.getTimeZone("UTC");
        Map<String, String> signIn = requestParams();
        Map<String, String> feed = requestParams();
        String[] elsewhere = new String[1];
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            RegionSpoof.requestParams(signIn);
            assertEquals("US", RegionSpoof.country("US"));
            assertEquals("US", RegionSpoof.storeCountry("US"));
            assertSame(locale, RegionSpoof.locale(locale));
            assertSame(zone, RegionSpoof.timeZone(zone));

            // A feed request built on another thread at the same moment keeps the preset.
            try {
                Thread other = new Thread(() -> elsewhere[0] = RegionSpoof.country("US"));
                other.start();
                other.join();
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }

            RegionSpoof.requestDone();
            assertEquals("JP", RegionSpoof.country("US"));
            assertEquals("JP", RegionSpoof.locale(locale).getCountry());
            RegionSpoof.requestPath("/aweme/v1/feed/");
            RegionSpoof.requestParams(feed);
            RegionSpoof.requestDone();
        });
        // The region TikTok saved and the account's own stay as TikTok set them, and the two
        // copies it took at start-up are what the system says now.
        Map<String, String> expected = requestParams();
        expected.put("sys_region", "DE");
        expected.put("timezone_name", "Europe/Paris");
        assertEquals("a sign-in's fields took the preset", expected, signIn);
        assertEquals("JP", elsewhere[0]);
        assertEquals("JP", feed.get("current_region"));
        assertEquals("440", feed.get("carrier_region_v2"));
        assertEquals("a feed request's copies were replaced", "US", feed.get("sys_region"));
        assertEquals("a feed request's copies were replaced", "Asia/Tokyo", feed.get("timezone_name"));
    }

    @Test public void aSignInOnlyPutsBackTheStartUpCopiesTikTokPutInTheMap() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> sparse = new HashMap<>();
            sparse.put("aid", "1233");
            RegionSpoof.requestParams(sparse);
            assertEquals("a field TikTok left out was added", 1, sparse.size());

            Map<String, Object> odd = new HashMap<>();
            odd.put("sys_region", 7);
            odd.put("timezone_name", null);
            RegionSpoof.requestParams(odd);
            assertEquals("a value that isn't a String was replaced", 7, odd.get("sys_region"));
            assertNull(odd.get("timezone_name"));
            assertEquals(2, odd.size());

            Map<String, String> onlyZone = new HashMap<>();
            onlyZone.put("timezone_name", "Asia/Tokyo");
            RegionSpoof.requestParams(onlyZone);
            assertEquals("Europe/Paris", onlyZone.get("timezone_name"));
            assertEquals("sys_region was added", 1, onlyZone.size());
            RegionSpoof.requestDone();
        });
    }

    @Test public void aSystemLocaleWithoutACountryLeavesSysRegionOutOfASignIn() {
        // TikTok's start-up cache never takes an empty value, so with no country there'd be no field.
        Settings.REGION_REQUEST_SPOOF.save(true);
        withSystem(Locale.GERMAN, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> params = requestParams();
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
            assertFalse(params.containsKey("sys_region"));
            assertEquals("Europe/Paris", params.get("timezone_name"));
            assertEquals(requestParams().size() - 1, params.size());
        });
    }

    @Test public void aSignInGetsTheLiveValuesEvenWhenThePresetIsOffNow() {
        // The copies were made when the preset was on. Switching it off doesn't change them
        // until TikTok restarts, so a sign-in puts them right whatever the preset says.
        Settings.REGION_REQUEST_SPOOF.save(true);
        Settings.REGION_SPOOF.save(false);
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> params = requestParams();
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
            assertEquals("DE", params.get("sys_region"));
            assertEquals("Europe/Paris", params.get("timezone_name"));
            assertEquals("US", params.get("current_region"));
        });
    }

    @Test public void aSignInMapIsLeftAloneWithoutTheRequestSwitch() {
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> params = requestParams();
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
            assertEquals(requestParams(), params);
        });
    }

    @Test public void onlyTheForYouPathsAreFeedFills() {
        for (String path : new String[]{"/aweme/v1/feed/", "/aweme/v2/feed/", "//aweme/v12/feed/"}) {
            assertTrue(path, RegionSpoof.isForYouFeed(path));
        }
        for (String path : new String[]{null, "", "/", "/aweme/v1/feed", "/aweme/v1/follow/feed/",
                "/aweme/v1/friend/feed/", "/aweme/v/feed/", "aweme/v1/feed/", "/aweme/v1/feed/more/",
                "/api/aweme/v1/feed/", "/aweme/vx/feed/", "/passport/user/login/"}) {
            assertFalse(String.valueOf(path), RegionSpoof.isForYouFeed(path));
        }
    }

    @Test public void aForYouRequestCarriesThePhonesOwnSimWhereTheFieldsHeldThePreset() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        Settings.SIM_SPOOF_FEED_REAL.save(true);
        phoneSim("de", "", "26201", "Telekom.de");
        Map<String, String> params = simParams();
        params.put("account_region", "JP");
        Locale locale = Locale.US;
        withSystem(Locale.GERMANY, "Europe/Berlin", () -> {
            RegionSpoof.requestPath("/aweme/v1/feed/");
            // AppLog's fill for the same request runs inside the handler's.
            RegionSpoof.requestUrl("https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/feed/?count=6");
            assertEquals("the getters answered the preset inside a For You fill", "US", RegionSpoof.country("US"));
            assertSame(locale, RegionSpoof.locale(locale));
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
            assertEquals("the inner fill's end took the mark off", "US", RegionSpoof.country("US"));
            RegionSpoof.requestDone();
            assertEquals("JP", RegionSpoof.country("US"));
        });
        assertEquals("DE", params.get("carrier_region"));
        assertEquals("DE", params.get("region"));
        assertEquals("the field's own case is kept", "de", params.get("op_region"));
        assertEquals("de", params.get("sim_region"));
        assertEquals("26201", params.get("mcc_mnc"));
        assertEquals("Telekom.de", params.get("carrier"));
        assertEquals("the start-up copies are the system's now", "DE", params.get("sys_region"));
        assertEquals("Europe/Berlin", params.get("timezone_name"));
        // Match region fields in requests doesn't reach a For You request with the switch on.
        assertEquals("US", params.get("current_region"));
        assertEquals("US", params.get("residence"));
        assertEquals("262", params.get("carrier_region_v2"));
        assertEquals("a field that isn't the SIM's was changed", "JP", params.get("account_region"));
    }

    @Test public void aForYouRequestKeepsWhatIsntThePresetAndEverythingElseKeepsThePreset() {
        Settings.SIM_SPOOF_FEED_REAL.save(true);
        phoneSim("", "fr", "", "");
        Map<String, String> params = simParams();
        params.put("carrier_region", "GB");
        forYou(params);
        assertEquals("a value the preset didn't set was changed", "GB", params.get("carrier_region"));
        assertEquals("the network's country stands in for a missing SIM", "FR", params.get("region"));
        assertEquals("an operator nobody reported replaced the preset's", "44010", params.get("mcc_mnc"));
        assertEquals("NTT DOCOMO", params.get("carrier"));

        phoneSim("", "", "", "");
        params = simParams();
        forYou(params);
        assertEquals("JP", params.get("carrier_region"));
        assertEquals("jp", params.get("op_region"));

        // The Following feed, the switch off, and the SIM override off all leave the map alone.
        phoneSim("de", "de", "26201", "Telekom.de");
        params = simParams();
        RegionSpoof.requestPath("/aweme/v1/follow/feed/");
        RegionSpoof.requestParams(params);
        RegionSpoof.requestDone();
        assertEquals(simParams(), params);
        for (Runnable off : new Runnable[]{
                () -> Settings.SIM_SPOOF_FEED_REAL.save(false),
                () -> Settings.SIM_SPOOF.save(false)}) {
            setup();
            Settings.SIM_SPOOF_FEED_REAL.save(true);
            off.run();
            params = simParams();
            forYou(params);
            assertEquals(simParams(), params);
        }
    }

    /** Builds a For You request's parameters, inside a system that matches the fixture's copies. */
    private static void forYou(Map<String, String> params) {
        withSystem(Locale.JAPAN, "Asia/Tokyo", () -> {
            RegionSpoof.requestPath("/aweme/v2/feed/");
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
        });
    }

    /** What TikTok's builder sends with the SIM preset set to Japan's NTT DOCOMO. */
    private static Map<String, String> simParams() {
        Map<String, String> params = requestParams();
        params.put("carrier_region", "JP");
        params.put("region", "JP");
        params.put("op_region", "jp");
        params.put("sim_region", "jp");
        params.put("sys_region", "JP");
        params.put("carrier_region_v2", "262");
        params.put("mcc_mnc", "44010");
        params.put("carrier", "NTT DOCOMO");
        return params;
    }

    private static void phoneSim(String simCountry, String networkCountry, String operator, String operatorName) {
        android.telephony.TelephonyManager phone = (android.telephony.TelephonyManager)
                RuntimeEnvironment.getApplication().getSystemService(android.content.Context.TELEPHONY_SERVICE);
        org.robolectric.shadows.ShadowTelephonyManager shadow = org.robolectric.Shadows.shadowOf(phone);
        shadow.setSimCountryIso(simCountry);
        shadow.setNetworkCountryIso(networkCountry);
        shadow.setNetworkOperator(operator);
        shadow.setSimOperator("");
        shadow.setNetworkOperatorName(operatorName);
        shadow.setSimOperatorName("");
    }

    @Test public void aUrlBeingBuiltMarksTheThreadLikeAUrlAndANullOneDoesNothing() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestUrlBuilder(new StringBuilder("https://api.tiktokv.com/passport/user/login/?aid=1233"));
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestUrlBuilder(new StringBuilder("https://api.tiktokv.com/aweme/v1/feed/?next=/passport/"));
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        RegionSpoof.requestUrlBuilder(null);
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestUrl((String) null);
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
    }

    @Test public void aSignInIsLeftAloneWithoutTheRequestSwitchAndAMissedEndIsPutRight() {
        // Without Match region fields in requests a sign-in is spoofed as it always was.
        RegionSpoof.requestPath("/passport/user/login/");
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();

        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestPath("/passport/user/login/");
        // TikTok's fill threw before the end was called: the thread's next request puts it right.
        RegionSpoof.requestPath("/aweme/v1/feed/");
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
    }
    @Test public void aKeptAnswerFollowsTheSettingAndEachZoneIsItsOwnCopy() {
        // Answers are kept between calls, because every Locale and TimeZone default in the app
        // comes through here. One must not outlive the setting it was worked out from, and a
        // TimeZone handed out must not be one the next caller sees changed.
        Locale original = Locale.US;
        TimeZone zone = TimeZone.getTimeZone("UTC");
        assertEquals("JP", RegionSpoof.locale(original).getCountry());
        assertSame("the same question was worked out again",
                RegionSpoof.locale(original), RegionSpoof.locale(original));
        TimeZone first = RegionSpoof.timeZone(zone);
        assertEquals("Asia/Tokyo", first.getID());
        first.setRawOffset(0);
        TimeZone second = RegionSpoof.timeZone(zone);
        assertNotSame("a caller could change the zone the next caller gets", first, second);
        assertEquals("a caller's change reached the next caller",
                TimeZone.getTimeZone("Asia/Tokyo").getRawOffset(), second.getRawOffset());

        Settings.SIM_SPOOF_ISO.save("de");
        assertEquals("DE", RegionSpoof.locale(original).getCountry());
        assertEquals("Europe/Berlin", RegionSpoof.timeZone(zone).getID());
        Settings.REGION_SPOOF.save(false);
        assertSame(original, RegionSpoof.locale(original));
        assertSame(zone, RegionSpoof.timeZone(zone));
    }

    @Test public void everyPresetsZoneBecomesTheAppsDefaultAndTheLocaleDefaultStays() {
        Locale systemLocale = Locale.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(PHONE_ZONE));
        for (SimPreset preset : SimPresets.PRESETS) {
            Settings.SIM_SPOOF_ISO.save(preset.iso);
            assertTrue(preset.iso, RegionSpoof.validCountry(preset.iso));
            TimeZone result = RegionSpoof.timeZone(TimeZone.getDefault());
            assertEquals(preset.timeZone, result.getID());
            assertEquals(preset.iso, preset.timeZone, TimeZone.getDefault().getID());
            assertEquals("the phone's zone was lost going from preset to preset",
                    PHONE_ZONE, RegionSpoof.phoneTimeZone().getID());
        }
        assertEquals(systemLocale, Locale.getDefault());
        Settings.REGION_SPOOF.save(false);
        assertEquals(PHONE_ZONE, RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
    }

    /** The phone's own zone in the process-zone tests: no preset uses it. */
    private static final String PHONE_ZONE = "America/Chicago";
    /** 14:00 in Belgrade on summer time, 07:00 in Chicago. */
    private static final long JULY_NOON_UTC = 1_784_116_800_000L;
    /** 13:00 in Belgrade on standard time, 06:00 in Chicago. */
    private static final long JANUARY_NOON_UTC = 1_768_478_400_000L;

    /** The phone in Chicago and the preset Serbia, with the zone set the way start-up sets it. */
    private static void serbiaAtStartUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(PHONE_ZONE));
        Settings.SIM_SPOOF_ISO.save("rs");
        RegionSpoof.applyProcessZone();
    }

    private static String clock(java.text.DateFormat format, long at) {
        return format.format(new java.util.Date(at));
    }

    @Test public void theFrameworksOwnFormattersShowBelgradeTimeForSerbiaDaylightSavingIncluded() {
        serbiaAtStartUp();
        assertEquals("Europe/Belgrade", TimeZone.getDefault().getID());
        // What TikTok's message and comment cells build: formatters that read the default themselves.
        java.text.SimpleDateFormat hours = new java.text.SimpleDateFormat("HH:mm", Locale.US);
        assertEquals("summer time", "14:00", clock(hours, JULY_NOON_UTC));
        assertEquals("standard time", "13:00", clock(hours, JANUARY_NOON_UTC));
        assertEquals("Europe/Belgrade", android.text.format.DateFormat.getTimeFormat(
                RuntimeEnvironment.getApplication()).getTimeZone().getID());
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(JULY_NOON_UTC);
        assertEquals(14, calendar.get(java.util.Calendar.HOUR_OF_DAY));
        assertEquals("Europe/Belgrade", java.time.ZoneId.systemDefault().getId());
        // And the hook's own answer is the same zone, as one more copy.
        TimeZone hooked = RegionSpoof.timeZone(TimeZone.getDefault());
        assertEquals("Europe/Belgrade", hooked.getID());
    }

    @Test public void aTypedZoneWinsOverTheCountrysDaylightSavingAndNativeDatesIncluded() {
        serbiaAtStartUp();
        Settings.SIM_SPOOF_TIME_ZONE.save("Asia/Tokyo");
        assertEquals("Asia/Tokyo", RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals("Asia/Tokyo", TimeZone.getDefault().getID());
        assertEquals("TZ goes with it, for TikTok's native dates", "Asia/Tokyo", tz.value);
        assertEquals("the phone's own zone is kept", PHONE_ZONE, RegionSpoof.phoneTimeZone().getID());

        Settings.SIM_SPOOF_TIME_ZONE.save("America/New_York");
        RegionSpoof.timeZone(TimeZone.getDefault());
        java.text.SimpleDateFormat hours = new java.text.SimpleDateFormat("HH:mm", Locale.US);
        assertEquals("summer time", "08:00", clock(hours, JULY_NOON_UTC));
        assertEquals("standard time", "07:00", clock(hours, JANUARY_NOON_UTC));
    }

    @Test public void aTypedZoneTakesAnyCaseAndSpacesForUnderscores() {
        serbiaAtStartUp();
        Settings.SIM_SPOOF_TIME_ZONE.save(" america/new york ");
        assertEquals("America/New_York", RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals("America/New_York", tz.value);
        assertTrue(RegionSpoof.validTimeZone(" america/new york "));
    }

    @Test public void anOffsetOrANameThatIsNoZoneLeavesTheCountrysZone() {
        serbiaAtStartUp();
        for (String refused : new String[]{"GMT+05:30", "UTC+2", "+02:00", "Belgrade", "Europe/Nowhere", "  "}) {
            assertFalse(refused, RegionSpoof.validTimeZone(refused));
            Settings.SIM_SPOOF_TIME_ZONE.save(refused);
            assertEquals(refused, "Europe/Belgrade", RegionSpoof.timeZone(TimeZone.getDefault()).getID());
            assertEquals(refused, "Europe/Belgrade", tz.value);
        }
    }

    @Test public void clearingATypedZoneGoesBackToTheCountrysAtOnce() {
        serbiaAtStartUp();
        Settings.SIM_SPOOF_TIME_ZONE.save("Asia/Tokyo");
        RegionSpoof.timeZone(TimeZone.getDefault());
        Settings.SIM_SPOOF_TIME_ZONE.save("");
        assertEquals("Europe/Belgrade", RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals("Europe/Belgrade", TimeZone.getDefault().getID());
        assertEquals("Europe/Belgrade", tz.value);
    }

    @Test public void aTypedZoneDoesNothingWhileMatchLocaleIsOffOrHushfeedPauses() {
        TimeZone.setDefault(TimeZone.getTimeZone(PHONE_ZONE));
        Settings.SIM_SPOOF_TIME_ZONE.save("Asia/Tokyo");
        Settings.REGION_SPOOF.save(false);
        assertEquals(PHONE_ZONE, RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
        Settings.REGION_SPOOF.save(true);
        assertEquals("Asia/Tokyo", RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        PausedProcess.set(true);
        assertEquals(PHONE_ZONE, RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
    }

    @Test public void theZoneGoesBackToThePhonesWhenTheSettingGoesOffOrHushfeedPauses() {
        for (Runnable off : new Runnable[]{
                () -> Settings.REGION_SPOOF.save(false),
                () -> Settings.SIM_SPOOF.save(false),
                () -> PausedProcess.set(true)}) {
            try {
                setup();
                serbiaAtStartUp();
                assertEquals("Europe/Belgrade", TimeZone.getDefault().getID());
                assertEquals("the phone's own zone is kept", PHONE_ZONE, RegionSpoof.phoneTimeZone().getID());
                off.run();
                // The first call that notices already gets the phone's zone.
                assertEquals(PHONE_ZONE, RegionSpoof.timeZone(TimeZone.getDefault()).getID());
                assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
                assertEquals("07:00", clock(new java.text.SimpleDateFormat("HH:mm", Locale.US), JULY_NOON_UTC));
                assertEquals(PHONE_ZONE, RegionSpoof.phoneTimeZone().getID());
                // Nothing is set again while it stays off.
                RegionSpoof.applyProcessZone();
                assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
            } finally {
                PausedProcess.set(false);
                TimeZone.setDefault(startZone);
            }
        }
    }

    @Test public void offOrPausedAtStartUpLeavesThePhonesZone() {
        for (Runnable off : new Runnable[]{
                () -> Settings.REGION_SPOOF.save(false),
                () -> Settings.SIM_SPOOF_ISO.save("zz"),
                () -> PausedProcess.set(true)}) {
            try {
                setup();
                TimeZone.setDefault(TimeZone.getTimeZone(PHONE_ZONE));
                Settings.SIM_SPOOF_ISO.save("rs");
                off.run();
                RegionSpoof.applyProcessZone();
                assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
                TimeZone phone = TimeZone.getDefault();
                assertSame(phone, RegionSpoof.timeZone(phone));
                assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
                assertEquals(PHONE_ZONE, RegionSpoof.phoneTimeZone().getID());
            } finally {
                PausedProcess.set(false);
                TimeZone.setDefault(startZone);
            }
        }
    }

    @Test public void aZoneTheSystemPutsBackIsReplacedAtTheNextCallAndTheNewPhoneZoneKept() {
        serbiaAtStartUp();
        // Android's answer to the phone's zone changing: the default is dropped and read again
        // from the system, which here is the phone's new zone.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"));
        assertEquals("America/Denver", RegionSpoof.phoneTimeZone().getID());
        assertEquals("Europe/Belgrade", RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals("Europe/Belgrade", TimeZone.getDefault().getID());
        assertEquals("America/Denver", RegionSpoof.phoneTimeZone().getID());
        Settings.REGION_SPOOF.save(false);
        RegionSpoof.timeZone(TimeZone.getDefault());
        assertEquals("the phone's new zone came back", "America/Denver", TimeZone.getDefault().getID());
    }

    @Test public void aChangedPresetMovesTheAppsZoneAndKeepsThePhones() {
        serbiaAtStartUp();
        Settings.SIM_SPOOF_ISO.save("jp");
        assertEquals("Asia/Tokyo", RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        assertEquals("Asia/Tokyo", TimeZone.getDefault().getID());
        assertEquals(PHONE_ZONE, RegionSpoof.phoneTimeZone().getID());
        TimeZone kept = RegionSpoof.phoneTimeZone();
        kept.setRawOffset(0);
        assertEquals("a caller changed the kept phone zone", -6 * 3_600_000,
                RegionSpoof.phoneTimeZone().getRawOffset());
    }

    @Test public void tikToksNativeDateFormatterGetsThePresetsZoneAndThePhonesBackWhenOffOrPaused() {
        for (Runnable off : new Runnable[]{
                () -> Settings.REGION_SPOOF.save(false),
                () -> Settings.SIM_SPOOF.save(false),
                () -> PausedProcess.set(true)}) {
            try {
                setup();
                serbiaAtStartUp();
                // BDDateFormat (the chat's date headers) formats with localtime_r, which reads TZ.
                assertEquals("Europe/Belgrade", tz.value);
                off.run();
                RegionSpoof.timeZone(TimeZone.getDefault());
                assertNull("TZ is unset again, so localtime_r reads the phone's zone", tz.value);
                RegionSpoof.applyProcessZone();
                assertNull("nothing is set again while it stays off", tz.value);
            } finally {
                PausedProcess.set(false);
                TimeZone.setDefault(startZone);
            }
        }
    }

    @Test public void aTzTheProcessStartedWithIsKeptAcrossPresetsAndSystemResetsAndComesBack() {
        tz.value = "Asia/Kolkata";
        serbiaAtStartUp();
        assertEquals("Europe/Belgrade", tz.value);
        Settings.SIM_SPOOF_ISO.save("jp");
        RegionSpoof.timeZone(TimeZone.getDefault());
        assertEquals("Asia/Tokyo", tz.value);
        // The system puts the phone's new zone back as the default; TZ is still ours.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"));
        RegionSpoof.timeZone(TimeZone.getDefault());
        assertEquals("Asia/Tokyo", tz.value);
        Settings.REGION_SPOOF.save(false);
        RegionSpoof.timeZone(TimeZone.getDefault());
        assertEquals("Asia/Kolkata", tz.value);
    }

    @Test public void offAtStartUpOrTheSamePresetAgainNeverWritesTz() {
        Settings.REGION_SPOOF.save(false);
        serbiaAtStartUp();
        RegionSpoof.timeZone(TimeZone.getDefault());
        assertEquals(0, tz.writes);
        Settings.REGION_SPOOF.save(true);
        RegionSpoof.timeZone(TimeZone.getDefault());
        RegionSpoof.timeZone(TimeZone.getDefault());
        RegionSpoof.applyProcessZone();
        assertEquals("one write for Serbia, none for each answer after it", 1, tz.writes);
    }

    @Test public void aTzThatCantBeSetLeavesTheAppsJavaZoneOnThePreset() {
        RegionSpoof.nativeZone = new RegionSpoof.NativeZone() {
            @Override String get() {
                return null;
            }

            @Override void set(String id) throws android.system.ErrnoException {
                throw new android.system.ErrnoException("setenv", 22);
            }
        };
        serbiaAtStartUp();
        assertEquals("Europe/Belgrade", TimeZone.getDefault().getID());
        assertEquals(PHONE_ZONE, RegionSpoof.phoneTimeZone().getID());
        Settings.REGION_SPOOF.save(false);
        RegionSpoof.timeZone(TimeZone.getDefault());
        assertEquals(PHONE_ZONE, TimeZone.getDefault().getID());
    }

    @Test public void aSignInAndThePhonesClockGetThePhonesZoneWhileSerbiasIsTheDefault() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        serbiaAtStartUp();
        RegionSpoof.requestPath("/passport/user/login/");
        assertEquals(PHONE_ZONE, RegionSpoof.timeZone(TimeZone.getDefault()).getID());
        Map<String, String> params = requestParams();
        RegionSpoof.requestParams(params);
        RegionSpoof.requestDone();
        assertEquals("a sign-in sent the app's zone", PHONE_ZONE, params.get("timezone_name"));
        assertEquals("the default changed for a sign-in", "Europe/Belgrade", TimeZone.getDefault().getID());
        assertEquals("Europe/Belgrade", RegionSpoof.timeZone(TimeZone.getDefault()).getID());

        java.text.DateFormat phone = RegionSpoof.phoneClock(new java.text.SimpleDateFormat("HH:mm", Locale.US));
        assertEquals("07:00", clock(phone, JULY_NOON_UTC));
        assertEquals("06:00", clock(phone, JANUARY_NOON_UTC));
    }

    @Test public void allPresetsMatchTheBundledTzdbRegionWhenAvailable() {
        if (Build.VERSION.SDK_INT < 24) return;
        TimeZone systemZone = TimeZone.getDefault();
        for (SimPreset preset : SimPresets.PRESETS) {
            Settings.SIM_SPOOF_ISO.save(preset.iso);
            TimeZone result = RegionSpoof.timeZone(systemZone);
            assertEquals(preset.iso.toUpperCase(Locale.ROOT),
                    android.icu.util.TimeZone.getRegion(result.getID()));
        }
    }
    @Test public void localeKeepsLanguageScriptAndExtensionsAndFlagsAreIndependent() {
        Locale original = Locale.forLanguageTag("zh-Hant-TW-u-nu-hanidec");
        Locale changed = RegionSpoof.locale(original);
        assertEquals("JP", changed.getCountry());
        assertEquals(original.getLanguage(), changed.getLanguage());
        assertEquals(original.getScript(), changed.getScript());
        assertEquals(original.getUnicodeLocaleType("nu"), changed.getUnicodeLocaleType("nu"));
        assertEquals("JP", RegionSpoof.country("US"));
        assertEquals("US", RegionSpoof.storeCountry("US"));
        Settings.REGION_STORE_SPOOF.save(true);
        assertEquals("JP", RegionSpoof.storeCountry("US"));
        Settings.REGION_SPOOF.save(false);
        assertEquals("US", RegionSpoof.storeCountry("US"));
        assertSame(original, RegionSpoof.locale(original));
        Settings.REGION_SPOOF.save(true);
        Settings.SIM_SPOOF.save(false);
        assertSame(original, RegionSpoof.locale(original));
        assertEquals("US", RegionSpoof.storeCountry("US"));
    }
    @Test public void invalidCountryAndMissingValuesPreserveNativeInputs() {
        Locale original = Locale.CANADA_FRENCH;
        TimeZone zone = TimeZone.getTimeZone("America/Toronto");
        for (String value : new String[]{"", "zz", "USA", "123", "ja-JP"}) {
            Settings.SIM_SPOOF_ISO.save(value);
            assertFalse(value, RegionSpoof.validCountry(value));
            assertSame(original, RegionSpoof.locale(original));
            assertSame(zone, RegionSpoof.timeZone(zone));
            assertNull(RegionSpoof.country(null));
        }
        Settings.SIM_SPOOF_ISO.save(" jp ");
        assertEquals("JP", RegionSpoof.country(null));
        assertNull(RegionSpoof.locale(null));
        assertNull(RegionSpoof.timeZone(null));
        Settings.SIM_SPOOF_ISO.save("is");
        if (Build.VERSION.SDK_INT >= 24) assertEquals("IS", android.icu.util.TimeZone.getRegion(RegionSpoof.timeZone(zone).getID()));
        else assertSame(zone, RegionSpoof.timeZone(zone));
    }
    @Test public void requestFieldsTakeThePresetOnlyWhereTikTokSentThemAndOnlyWithTheirSwitch() {
        assertEquals(Boolean.FALSE, Settings.REGION_REQUEST_SPOOF.defaultValue);
        Map<String, String> params = requestParams();
        RegionSpoof.requestParams(params);
        assertEquals("the fields changed with their switch off", requestParams(), params);

        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestParams(params);
        assertEquals("JP", params.get("current_region"));
        assertEquals("JP", params.get("residence"));
        assertEquals("the network country code is the preset's MCC", "440", params.get("carrier_region_v2"));
        // The hub's fields are the getter hooks' to answer, and the rest is not a region.
        assertEquals("US", params.get("carrier_region"));
        assertEquals("US", params.get("sys_region"));
        assertEquals("US", params.get("region"));
        assertEquals("US", params.get("op_region"));
        assertEquals("US", params.get("account_region"));
        assertEquals("en", params.get("app_language"));
        assertEquals("a start-up copy changed on an ordinary request", "Asia/Tokyo", params.get("timezone_name"));
        assertEquals(requestParams().size(), params.size());

        Map<String, String> sparse = new HashMap<>();
        sparse.put("aid", "1233");
        RegionSpoof.requestParams(sparse);
        assertEquals("a field TikTok left out was added", 1, sparse.size());
        RegionSpoof.requestParams(null);
    }

    @Test public void requestFieldsStayWhenThePresetCannotSayWhatToSend() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        Settings.SIMSPOOF_MCCMNC.save("44a10");
        Map<String, String> params = requestParams();
        RegionSpoof.requestParams(params);
        assertEquals("JP", params.get("current_region"));
        assertEquals("an unusable operator code still went out", "310", params.get("carrier_region_v2"));

        Settings.SIMSPOOF_MCCMNC.save("44010");
        params = requestParams();
        params.put("carrier_region_v2", "us");
        RegionSpoof.requestParams(params);
        assertEquals("a value that isn't an MCC was replaced by one", "us", params.get("carrier_region_v2"));

        for (Runnable off : new Runnable[]{
                () -> Settings.SIM_SPOOF_ISO.save("zz"),
                () -> Settings.REGION_SPOOF.save(false),
                () -> Settings.SIM_SPOOF.save(false)}) {
            setup();
            Settings.REGION_REQUEST_SPOOF.save(true);
            off.run();
            params = requestParams();
            RegionSpoof.requestParams(params);
            assertEquals(requestParams(), params);
        }
    }

    private static Map<String, String> requestParams() {
        Map<String, String> params = new HashMap<>();
        for (String field : new String[]{"carrier_region", "sys_region", "region", "op_region",
                "current_region", "residence", "account_region"}) {
            params.put(field, "US");
        }
        params.put("carrier_region_v2", "310");
        params.put("app_language", "en");
        // Worked out once as TikTok started, with the preset's zone, like sys_region above.
        params.put("timezone_name", "Asia/Tokyo");
        return params;
    }

    /**
     * Runs the body with the system configuration's locale and the default time zone set to
     * these, which are what a sign-in's start-up copies are put back to, and restores both.
     */
    @SuppressWarnings("deprecation")
    private static void withSystem(Locale locale, String zone, Runnable body) {
        Configuration system = Resources.getSystem().getConfiguration();
        Locale savedLocale = system.locale;
        TimeZone savedZone = TimeZone.getDefault();
        try {
            system.locale = locale;
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            body.run();
        } finally {
            system.locale = savedLocale;
            TimeZone.setDefault(savedZone);
        }
    }

    @Test public void legacyVariantKeepsScriptAndExtensionsWhenCountryChanges() {
        Locale original = Locale.forLanguageTag("zh-Hant-TW-u-nu-hanidec-x-custom-lvariant-WIN");
        Locale changed = RegionSpoof.locale(original);
        assertEquals("JP", changed.getCountry());
        assertEquals(original.getLanguage(), changed.getLanguage());
        assertEquals(original.getScript(), changed.getScript());
        for (Character key : original.getExtensionKeys()) {
            assertEquals(original.getExtension(key), changed.getExtension(key));
        }
    }
    @Test public void countryCodesRejectUnicodeCaseExpansion() {
        Locale original = Locale.CANADA_FRENCH;
        TimeZone zone = TimeZone.getTimeZone("America/Toronto");
        for (String value : new String[]{"ß", "ſs", "ıS", "ｊｐ"}) {
            Settings.SIM_SPOOF_ISO.save(value);
            assertFalse(value, RegionSpoof.validCountry(value));
            assertSame(original, RegionSpoof.locale(original));
            assertSame(zone, RegionSpoof.timeZone(zone));
            assertEquals("CA", RegionSpoof.country("CA"));
        }
        assertTrue(RegionSpoof.validCountry(" ss "));
    }
    @Test public void regionControlsAreReachableAndRejectInvalidCountryInput() throws Exception {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            SettingsStatus.simSpoofEnabled = true;
            SettingsStatus.regionSpoofEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new SimSpoofPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("region_spoof"));
            assertNotNull(screen.findPreference("region_store_spoof"));
            assertNotNull(screen.findPreference("region_request_spoof"));
            assertNotNull(screen.findPreference("simspoof_feed_real"));
            // Without the region patch nothing would put the real SIM back, so there's no row.
            SettingsStatus.regionSpoofEnabled = false;
            var simOnly = activity.getPreferenceManager().createPreferenceScreen(activity);
            new SimSpoofPreferenceCategory(activity, simOnly);
            assertNull(simOnly.findPreference("simspoof_feed_real"));
            assertNotNull(simOnly.findPreference("simspoof"));
            SettingsStatus.regionSpoofEnabled = true;
            var country =(app.morphe.extension.tiktok.settings.preference.InputTextPreference)
                    screen.findPreference("simspoof_iso");
            assertFalse(country.callChangeListener("zz"));
            assertFalse(country.callChangeListener(""));
            assertTrue(country.callChangeListener("de"));
            // The listener behind the check still does its own job.
            assertNotNull(country.getOnPreferenceChangeListener());
            activity.setPreferenceScreen(screen);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            // This class runs at both 23 and 28 and both runs wrote the same file, so which one
            // ended up published depended on which finished last. At 23 the decor view draws
            // nothing at all, so half the time the published picture would have been blank.
            // Published captures come from the newest API this class runs at.
            if (Build.VERSION.SDK_INT == PUBLISHED_CAPTURE_SDK) {
                app.morphe.extension.tiktok.UiCapture.save(
                        activity.getWindow().getDecorView(), "region-settings.png");
            }
        }
    }
}
