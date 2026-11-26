package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.shared.Utils;

import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PaidGatesTest {
    /** Gates TikTok 48.0.2 registers that open or skip something it sells. */
    private static final String[] PAID = {
            "drama_is_drama_subscribing_local_override_enabled",
            "short_drama_skip_paywall_info_price",
            "live_superfan_skip_fetch_precheck",
            "ai_paywall_gatekeeper_fail_default_value",
            "ai_pay_effect_mock_skip_ensure_access",
            "gift_send_skip_balance_check_wallet_not_ready",
            "enable_ad_free_subscription_lite_api",
            "fans_club_send_new_super_fan",
            "enable_sub_only_video",
            "live_paid_event_switch",
            "paidContentPlaybackOpt",
    };
    private static final String PAID_KEY = PAID[0];
    private static final String FREE_KEY = "show_tag_after_circle_search_block";

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        FeatureGateLabStore.resetAllLabData();
    }

    @After public void tearDown() {
        FeatureGateLabStore.resetAllLabData();
    }

    @Test public void paidGatesAreKnownAndPresetGatesAreNot() {
        for (String key : PAID) assertTrue(key, PaidGates.isPaid(key));
        assertFalse(PaidGates.isPaid(FREE_KEY));
        assertFalse(PaidGates.isPaid("feed_title_timestamp_trial"));
        assertFalse(PaidGates.isPaid("ve_enable_streaming_request_quota"));
        assertTrue(PaidGates.isPaid("text_image_ai_quota_limit"));
        assertFalse(PaidGates.isPaid("4130_app_init_preload_opt"));
        assertFalse(PaidGates.isPaid(""));
        assertFalse(PaidGates.isPaid(null));
        for (FeatureGateLabStore.Preset preset : FeatureGateLabStore.PRESETS) {
            for (String rule : preset.rules) {
                String key = rule.split(":", 3)[0];
                if (key.startsWith(FeatureGateLabStore.LIVE_RULE)) {
                    key = key.substring(FeatureGateLabStore.LIVE_RULE.length());
                }
                assertFalse(preset.id + " holds paid gate " + key, PaidGates.isPaid(key));
            }
        }
    }

    @Test public void aPaidGateCantBeSaved() {
        assertFalse(FeatureGateLabStore.saveRule("abmock", PAID_KEY, "BOOLEAN", "true", true));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertTrue(FeatureGateLabStore.saveRule("abmock", FREE_KEY, "INT", "1", true));
        assertEquals(1, FeatureGateLabStore.rules().size());
    }

    @Test public void aBackupHoldingAPaidGateRestoresTheOtherRules() throws Exception {
        JSONArray rules = new JSONArray()
                .put(backupRule(PAID_KEY, "BOOLEAN", "true"))
                .put(backupRule(FREE_KEY, "INT", "1"));
        JSONObject backup = new JSONObject().put("schema", 1)
                .put("tiktok_version", FeatureGateLabStore.targetVersion())
                .put("master", false).put("acknowledged", true).put("rules", rules);

        List<FeatureGateLabStore.Rule> parsed = FeatureGateLabStore.parseSettings(backup);
        assertEquals(1, parsed.size());
        assertEquals(FREE_KEY, parsed.get(0).key);

        FeatureGateLabStore.replaceSettings(parsed, false, true);
        assertTrue(FeatureGateLabStore.settingsMatch(backup));
    }

    @Test public void aProfileHoldingAPaidGateIsRefusedForIt() throws Exception {
        JSONObject profile = new JSONObject().put("schema", 1)
                .put("tiktok_version", FeatureGateLabStore.targetVersion())
                .put("rules", new JSONArray().put(backupRule(PAID_KEY, "BOOLEAN", "true")));

        FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewProfile(
                profile.toString(), Collections.emptyMap());
        assertTrue(review.accepted.isEmpty());
        assertEquals(1, review.rejected.size());
        assertEquals(FeatureGateLabStore.ImportRejectionCode.UNSUPPORTED_BOUNDARY,
                review.rejected.get(0).code);
    }

    @Test public void aPaidRuleSavedBeforeIsNeitherListedNorApplied() throws Exception {
        FeatureGateLabStore.replaceSettings(List.of(
                rule(PAID_KEY, "BOOLEAN", "true"),
                rule(FREE_KEY, "BOOLEAN", "true")), true, true);

        assertEquals(1, FeatureGateLabStore.rules().size());
        assertEquals(FREE_KEY, FeatureGateLabStore.rules().get(0).key);
        assertFalse(FeatureGateLabRuntime.overrideBoolean(PAID_KEY, false));
        assertTrue(FeatureGateLabRuntime.overrideBoolean(FREE_KEY, false));
    }

    private static JSONObject backupRule(String key, String type, String value) throws Exception {
        return new JSONObject().put("manager", "abmock").put("key", key)
                .put("type", type).put("value", value).put("force", true);
    }

    private static FeatureGateLabStore.Rule rule(String key, String type, String value) {
        return new FeatureGateLabStore.Rule(FeatureGateLabStore.idFor("abmock", key, type),
                "abmock", key, type, value, true, System.currentTimeMillis());
    }
}
