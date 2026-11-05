/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bytedance.android.livesdk.pip.PipVideoViewContainDialog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** #337: a LIVE recording goes on while the room plays in TikTok's in-app mini window. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveMiniWindowTest {
    /** A room page the way TikTok's obfuscated one holds the mini window: a field of the dialog's class. */
    private static class RoomPage {
        PipVideoViewContainDialog L2;
    }

    private static final class SubPage extends RoomPage {
        @SuppressWarnings("unused") String other = "x";
    }

    /** A page that is on its way out, as the fragment reports it. */
    private static final class ClosingPage extends RoomPage {
        public boolean isRemoving() { return true; }
    }

    @Test public void theWindowTypeIsTheOneTikTokNames() {
        assertEquals(PipVideoViewContainDialog.class.getName(), LiveRecordChip.MINI_WINDOW_TYPE);
    }

    @Test public void aPageHoldingTheWindowIsInTheMiniWindow() {
        RoomPage page = new RoomPage();
        assertFalse("full size page", LiveRecordChip.inMiniWindow(page));
        page.L2 = new PipVideoViewContainDialog();
        assertTrue(LiveRecordChip.inMiniWindow(page));
    }

    @Test public void theWindowClosingEndsIt() {
        RoomPage page = new RoomPage();
        page.L2 = new PipVideoViewContainDialog();
        assertTrue(LiveRecordChip.inMiniWindow(page));
        page.L2 = null;
        assertFalse(LiveRecordChip.inMiniWindow(page));
    }

    @Test public void theFieldIsFoundOnASuperclass() {
        SubPage page = new SubPage();
        page.L2 = new PipVideoViewContainDialog();
        assertTrue(LiveRecordChip.inMiniWindow(page));
    }

    @Test public void aPageBeingRemovedDoesNotKeepTheRecording() {
        ClosingPage page = new ClosingPage();
        page.L2 = new PipVideoViewContainDialog();
        assertFalse(LiveRecordChip.inMiniWindow(page));
    }

    @Test public void nothingReadableMeansFullSize() {
        assertFalse(LiveRecordChip.inMiniWindow(null));
        assertFalse(LiveRecordChip.inMiniWindow(new Object()));
    }
}
