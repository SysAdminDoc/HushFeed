/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The series player asks TikTok's servers for an advert to drop in between episodes (#286).
 * Remove feed ads makes the app treat every answer as no advert, and the app then goes on to
 * the next episode as it does when the ad server has nothing to give.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeriesRealtimeAdTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean adsBefore;

    @Before public void setUp() {
        adsBefore = Settings.REMOVE_ADS.get();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.REMOVE_ADS.save(adsBefore);
    }

    @Test public void theSeriesAdIsRefusedWithRemoveFeedAdsOn() {
        Settings.REMOVE_ADS.save(true);
        assertTrue(CardFilters.shouldRefuseSeriesRealtimeAd());
    }

    @Test public void theSeriesAdIsLeftAloneWithRemoveFeedAdsOff() {
        Settings.REMOVE_ADS.save(false);
        assertFalse(CardFilters.shouldRefuseSeriesRealtimeAd());
    }

    @Test public void pausedHushfeedLeavesTheSeriesAdAlone() {
        Settings.REMOVE_ADS.save(true);
        PausedProcess.set(true);
        assertFalse(CardFilters.shouldRefuseSeriesRealtimeAd());
    }
}
