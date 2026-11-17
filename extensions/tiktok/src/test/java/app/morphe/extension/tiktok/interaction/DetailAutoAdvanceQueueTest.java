/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** #292: a page opened from a profile keeps playing its videos in TikTok's background queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DetailAutoAdvanceQueueTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before @After public void reset() {
        PausedProcess.set(false);
        Settings.AUTO_ADVANCE.resetToDefault();
        Settings.AUTO_ADVANCE_LIMIT.resetToDefault();
        Settings.BACKGROUND_PLAY.resetToDefault();
        SessionBudget.clear();
    }

    private static void bothOn() {
        Settings.AUTO_ADVANCE.save(true);
        Settings.BACKGROUND_PLAY.save(true);
    }

    @Test public void aProfilePageGetsAQueueKeyOfItsOwn() {
        bothOn();
        assertEquals(DetailAutoAdvance.PROFILE_SCOPE + "6890", DetailAutoAdvance.backgroundScope(null, "6890"));
        assertEquals(DetailAutoAdvance.PROFILE_SCOPE + "6890", DetailAutoAdvance.backgroundScope("", "6890"));
    }

    @Test public void tikToksOwnKeyIsKept() {
        bothOn();
        assertEquals("collection-12", DetailAutoAdvance.backgroundScope("collection-12", "6890"));
    }

    @Test public void aPageFromNoProfileKeepsTikToksAnswer() {
        bothOn();
        assertNull(DetailAutoAdvance.backgroundScope(null, null));
        assertEquals("", DetailAutoAdvance.backgroundScope("", ""));
    }

    @Test public void eitherSwitchOffLeavesTheKeyAlone() {
        Settings.AUTO_ADVANCE.save(true);
        assertNull(DetailAutoAdvance.backgroundScope(null, "6890"));
        Settings.AUTO_ADVANCE.save(false);
        Settings.BACKGROUND_PLAY.save(true);
        assertNull(DetailAutoAdvance.backgroundScope(null, "6890"));
    }

    @Test public void tikToksNextOnScreenWaitsForAutoAdvance() {
        assertEquals("collection-12", DetailAutoAdvance.foregroundScope("collection-12"));
        Settings.AUTO_ADVANCE.save(true);
        assertNull(DetailAutoAdvance.foregroundScope("collection-12"));
    }

    @Test public void theQueueIsTurnedOnOnlyWithBothSwitches() {
        assertEquals(0, DetailAutoAdvance.collectionMode(0));
        Settings.AUTO_ADVANCE.save(true);
        assertEquals(0, DetailAutoAdvance.collectionMode(0));
        Settings.BACKGROUND_PLAY.save(true);
        assertEquals(1, DetailAutoAdvance.collectionMode(0));
        // TikTok's mode with the collection bar stays as the server gave it.
        assertEquals(2, DetailAutoAdvance.collectionMode(2));
    }

    @Test public void theLimitEndsTheQueueAtItsCount() {
        bothOn();
        Settings.AUTO_ADVANCE_LIMIT.save(2);
        Object queue = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertFalse(DetailAutoAdvance.backgroundNext(true, queue, "b"));
    }

    @Test public void theSameVideoEndingTwiceCountsOnce() {
        bothOn();
        Settings.AUTO_ADVANCE_LIMIT.save(2);
        Object queue = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertFalse(DetailAutoAdvance.backgroundNext(true, queue, "b"));
    }

    @Test public void eachQueueKeepsItsOwnCount() {
        bothOn();
        Settings.AUTO_ADVANCE_LIMIT.save(2);
        Object first = new Object();
        Object second = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, first, "a"));
        assertTrue(DetailAutoAdvance.backgroundNext(true, second, "a"));
        assertFalse(DetailAutoAdvance.backgroundNext(true, first, "b"));
    }

    @Test public void noLimitKeepsGoing() {
        bothOn();
        Object queue = new Object();
        for (int i = 0; i < 25; i++) assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "v" + i));
    }

    @Test public void theLastVideoStaysTheLast() {
        bothOn();
        assertFalse(DetailAutoAdvance.backgroundNext(false, new Object(), "a"));
    }

    @Test public void withAutoAdvanceOffTikToksAnswerGoesThrough() {
        Settings.BACKGROUND_PLAY.save(true);
        Settings.AUTO_ADVANCE_LIMIT.save(1);
        Object queue = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "b"));
    }
}
