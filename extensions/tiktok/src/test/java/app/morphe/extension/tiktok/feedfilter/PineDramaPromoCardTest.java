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
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The PineDrama "Trending free dramas" card is never asked for once a drama or card switch is on (#286). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PineDramaPromoCardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.HIDE_MINI_DRAMAS.resetToDefault();
        Settings.HIDE_SERIES.resetToDefault();
        Settings.HIDE_INSERTED_CARDS.resetToDefault();
    }

    @Test public void offByDefaultKeepsTheCard() {
        assertFalse(CardFilters.shouldSkipPineDramaMidInsert());
    }

    @Test public void eachOfTheThreeSwitchesSkipsTheRequest() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        assertTrue(CardFilters.shouldSkipPineDramaMidInsert());
        Settings.HIDE_MINI_DRAMAS.save(false);

        Settings.HIDE_SERIES.save(true);
        assertTrue(CardFilters.shouldSkipPineDramaMidInsert());
        Settings.HIDE_SERIES.save(false);

        Settings.HIDE_INSERTED_CARDS.save(true);
        assertTrue(CardFilters.shouldSkipPineDramaMidInsert());
    }

    @Test public void pausedHushfeedKeepsTheCard() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        PausedProcess.set(true);
        assertFalse(CardFilters.shouldSkipPineDramaMidInsert());
    }
}
