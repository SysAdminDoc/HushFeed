/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.bytedance.applog.priority.PriorityHttpResponse;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DisableTelemetryPatchTest {
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.DISABLE_ANALYTICS.save(false);
    }

    @After
    public void tearDown() {
        Settings.DISABLE_ANALYTICS.save(false);
    }

    @Test
    public void telemetryIsDisabledWhenTheSettingIsOn() {
        Settings.DISABLE_ANALYTICS.save(true);
        assertTrue(DisableTelemetryPatch.isTelemetryDisabled());
    }

    @Test
    public void telemetryIsEnabledWhenTheSettingIsOff() {
        assertFalse(DisableTelemetryPatch.isTelemetryDisabled());
    }

    /** Issue #379: the resolver lookup answers locally with the switch on, and only then. */
    @Test
    public void theResolverLookupStaysOnThePhoneOnlyWithTheSwitchOn() {
        assertEquals("whoami.akamai.net", DisableTelemetryPatch.resolverLookupHost("whoami.akamai.net"));
        Settings.DISABLE_ANALYTICS.save(true);
        assertEquals("localhost", DisableTelemetryPatch.resolverLookupHost("whoami.akamai.net"));
    }

    /**
     * Issue #379: with the switch on, Orbu's telemetry proxy (the log, sampling and challenge
     * uploads all post there) points at the phone itself, in whatever case or region a config
     * names it. Orbu's own configuration host keeps working, and so does any host that only
     * contains the word.
     */
    @Test
    public void orbuTelemetryHostsPointAtThePhoneOnlyWithTheSwitchOn() {
        String proxy = "https://proxy.telemetry.us-ashburn-1.oci.oraclecloud.com/";
        String config = "https://config.mtp.sag.us-ashburn-1.oci.oraclecloud.com/";
        assertEquals(proxy, DisableTelemetryPatch.orbuBaseUrl(proxy));

        Settings.DISABLE_ANALYTICS.save(true);
        assertEquals("https://localhost/", DisableTelemetryPatch.orbuBaseUrl(proxy));
        assertEquals("https://localhost/",
                DisableTelemetryPatch.orbuBaseUrl("https://PROXY.Telemetry.us-phoenix-1.oci.oraclecloud.com:443/v2/"));
        assertEquals("https://localhost/", DisableTelemetryPatch.orbuBaseUrl("proxy.telemetry.example.com"));
        assertEquals(config, DisableTelemetryPatch.orbuBaseUrl(config));
        assertEquals("https://notelemetry.example.com/telemetry/",
                DisableTelemetryPatch.orbuBaseUrl("https://notelemetry.example.com/telemetry/"));
        assertEquals(null, DisableTelemetryPatch.orbuBaseUrl(null));
    }

    @Test
    public void telemetryStaysEnabledBeforeTheContextIsSet() {
        Utils.setContext(null);
        Settings.DISABLE_ANALYTICS.save(true);
        assertFalse(DisableTelemetryPatch.isTelemetryDisabled());
    }

    /**
     * The priority uploader's checker takes a reply as delivered only for a 2xx code whose data
     * says message success and carries the SDK's magic tag (TelemetrySendAnchorsTest pins that
     * checker on 47.0.3), so the guarded send has to hand back exactly that.
     */
    @Test
    public void thePriorityReplyIsTheOneItsCheckerTakesAsDelivered() throws Exception {
        PriorityHttpResponse reply = (PriorityHttpResponse) DisableTelemetryPatch.deliveredPriorityResponse();
        assertEquals(200, reply.getCode());
        JSONObject data = new JSONObject(reply.getData());
        assertEquals("success", data.optString("message"));
        assertEquals("ss_app_log", data.optString("magic_tag"));
    }
}
