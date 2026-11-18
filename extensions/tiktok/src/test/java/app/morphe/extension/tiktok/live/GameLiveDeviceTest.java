/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The phone the LIVE setup requests carry, with the switch off, on and paused (issue #459). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GameLiveDeviceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Before public void setUp() {
        // A mark left by another test on this thread would leak into the first fill here.
        GameLiveDevice.requestPath(null);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.resetToDefault();
        GameLiveDevice.requestPath(null);
    }

    /** What TikTok's common-parameter builder puts for a Redmi, with a few of its neighbors. */
    private static Map<String, Object> realPhone() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("device_platform", "android");
        params.put("device_type", "23124RN87G");
        params.put("device_brand", "Redmi");
        params.put("os_version", "14");
        return params;
    }

    /** The handler's order: the path, then the builder fills the map, then the filled map. */
    private static Map<String, Object> fill(String path) {
        GameLiveDevice.requestPath(path);
        Map<String, Object> params = realPhone();
        GameLiveDevice.requestParams(params);
        return params;
    }

    @Test public void offTheRealPhoneGoesOut() {
        assertEquals(Boolean.FALSE, Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.defaultValue);
        assertEquals(realPhone(), fill("webcast/room/create_info/"));
    }

    @Test public void onTheSetupRequestsCarryTheGalaxy() {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        for (String path : new String[] {"webcast/room/create_info/", "/webcast/room/create_info/",
                "webcast/room/create_info", "webcast/room/create/", "/webcast/room/push_stream_info/"}) {
            Map<String, Object> params = fill(path);
            assertEquals(path, "SM-S938B", params.get("device_type"));
            assertEquals(path, "samsung", params.get("device_brand"));
            assertEquals(path, "android", params.get("device_platform"));
            assertEquals(path, "14", params.get("os_version"));
            assertEquals(path, 4, params.size());
        }
    }

    @Test public void onEveryOtherRequestKeepsTheRealPhone() {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        for (String path : new String[] {"webcast/feed/", "webcast/room/enter/", "webcast/room/create_info/extra/",
                "webcast/room/creat/", "aweme/v1/feed/", "passport/token/beat/", "room/create_info/",
                "webcast/room/create_infos/", "", "/"}) {
            assertEquals(path, realPhone(), fill(path));
        }
        assertEquals(realPhone(), fill(null));
    }

    @Test public void pausedTheRealPhoneGoesOutWithTheSwitchOn() {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        PausedProcess.set(true);
        assertEquals(realPhone(), fill("webcast/room/create_info/"));
    }

    @Test public void theMarkLastsForOneFill() {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        GameLiveDevice.requestPath("webcast/room/create_info/");
        Map<String, Object> first = realPhone();
        GameLiveDevice.requestParams(first);
        assertEquals("SM-S938B", first.get("device_type"));
        // A second fill with no path in between, as when a request is retried off the same map.
        Map<String, Object> second = realPhone();
        GameLiveDevice.requestParams(second);
        assertEquals(realPhone(), second);
    }

    @Test public void anotherRequestClearsAMarkAFailedFillLeftBehind() {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        // The builder threw, so the handler never reached requestParams for the setup request.
        GameLiveDevice.requestPath("webcast/room/create_info/");
        assertEquals(realPhone(), fill("webcast/feed/"));
    }

    @Test public void fieldsTikTokLeftOutStayOut() {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        GameLiveDevice.requestPath("webcast/room/create_info/");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("device_platform", "android");
        params.put("device_brand", 7);
        GameLiveDevice.requestParams(params);
        assertFalse(params.containsKey("device_type"));
        assertEquals("a field that isn't text stays as it was", 7, params.get("device_brand"));
        assertEquals(2, params.size());
    }

    @Test public void aMissingOrBrokenMapDoesNotThrow() {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        GameLiveDevice.requestPath("webcast/room/create_info/");
        GameLiveDevice.requestParams(null);
        GameLiveDevice.requestPath("webcast/room/create_info/");
        Map<String, Object> locked = java.util.Collections.unmodifiableMap(realPhone());
        GameLiveDevice.requestParams(locked);
        assertEquals(realPhone(), locked);
    }

    @Test public void eachThreadHasItsOwnMark() throws InterruptedException {
        Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.save(true);
        GameLiveDevice.requestPath("webcast/room/create_info/");
        AtomicReference<Map<String, Object>> other = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            Map<String, Object> params = realPhone();
            GameLiveDevice.requestParams(params);
            other.set(params);
        });
        thread.start();
        thread.join();
        assertEquals("another thread's fill isn't the setup request", realPhone(), other.get());
        Map<String, Object> mine = realPhone();
        GameLiveDevice.requestParams(mine);
        assertEquals("SM-S938B", mine.get("device_type"));
    }

    @Test public void setupPathsMatchWithOrWithoutTheirSlashes() {
        assertTrue(GameLiveDevice.isSetup("webcast/room/create_info"));
        assertTrue(GameLiveDevice.isSetup("//webcast/room/push_stream_info//"));
        assertFalse(GameLiveDevice.isSetup("webcast/room/create_info/x"));
        assertFalse(GameLiveDevice.isSetup("xwebcast/room/create"));
        assertFalse(GameLiveDevice.isSetup("///"));
        assertFalse(GameLiveDevice.isSetup(null));
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.gameLiveDeviceEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("report_newer_phone_to_game_live"));
        } finally {
            SettingsStatus.gameLiveDeviceEnabled = false;
        }
    }
}
