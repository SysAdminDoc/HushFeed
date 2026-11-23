/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Issue #496: on a OnePlus, notifications never came. TikTok's OPPO push adapter asks the
 * extension before it answers whether the phone has OPPO's push and before it registers there.
 * The answer is yes, skip it, so TikTok lists Google's push like on any other phone, and the
 * diagnostic report says so. Pause hands the adapter back to TikTok.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OppoPushTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        PausedProcess.set(false);
        HookStatus.clear();
    }

    @Test
    public void oppoPushIsSkippedAndTheReportSaysSo() {
        HookStatus.clear();
        assertTrue("TikTok's OPPO push adapter wasn't told to skip OPPO's push", OppoPush.skipOppoPush());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(OppoPush.FAMILY + ": 1 found, 0 missing"));
    }

    @Test
    public void pauseLeavesOppoPushToTikTok() {
        PausedProcess.set(true);
        assertFalse("a paused Hushfeed still skipped OPPO's push", OppoPush.skipOppoPush());
    }
}
