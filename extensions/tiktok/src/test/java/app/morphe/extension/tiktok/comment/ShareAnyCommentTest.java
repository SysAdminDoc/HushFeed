/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShareAnyCommentTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.SHARE_ANY_COMMENT.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        Settings.SHARE_ANY_COMMENT.resetToDefault();
        HookStatus.clear();
    }

    @Test public void tikTokKeepsItsShareBlockByDefault() {
        assertFalse(Settings.SHARE_ANY_COMMENT.get());
        assertFalse("TikTok's own answer goes through", ShareAnyComment.allow());
    }

    @Test public void theSwitchLiftsTheShareBlock() {
        Settings.SHARE_ANY_COMMENT.save(true);
        assertTrue(ShareAnyComment.allow());
    }

    @Test public void theExportCountsTheReadAndTheLift() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (ShareAnyComment.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            ShareAnyComment.allow();
            HookStatus.report();
            assertEquals("the read alone", 1, found[0]);
            Settings.SHARE_ANY_COMMENT.save(true);
            ShareAnyComment.allow();
            HookStatus.report();
            assertEquals("and the block lifted", 2, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}
