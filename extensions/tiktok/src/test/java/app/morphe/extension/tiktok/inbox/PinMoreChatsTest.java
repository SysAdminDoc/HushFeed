/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Pin more chats (#385). The patch hands TikTok's cap on pinned chats, 5 unless the server sends
 * another, to {@link PinMoreChats#pinLimit}. While the switch is on a cap below {@link
 * PinMoreChats#LIFTED_LIMIT} reads as that, and with the switch off, while paused or before the
 * context exists the cap is TikTok's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PinMoreChatsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PIN_MORE_CHATS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals("pin_more_chats", Settings.PIN_MORE_CHATS.key);
        assertEquals(Boolean.FALSE, Settings.PIN_MORE_CHATS.defaultValue);
        assertTrue("TikTok reads the cap once per launch", Settings.PIN_MORE_CHATS.rebootApp);
        assertEquals(5, PinMoreChats.pinLimit(5));
    }

    @Test
    public void onTheCapIsLifted() {
        Settings.PIN_MORE_CHATS.save(true);
        assertEquals(PinMoreChats.LIFTED_LIMIT, PinMoreChats.pinLimit(5));
        assertEquals("a cap the server lowered", PinMoreChats.LIFTED_LIMIT, PinMoreChats.pinLimit(0));
        assertEquals("a cap already higher stays", 250, PinMoreChats.pinLimit(250));
        // TikTok works out the room left as cap - pinned + 1, so the lifted cap must not overflow.
        assertTrue(PinMoreChats.LIFTED_LIMIT < Integer.MAX_VALUE / 2);

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("pin more chats"));
    }

    @Test
    public void pausedOrWithoutAContextTheCapIsTikToks() {
        Settings.PIN_MORE_CHATS.save(true);
        PausedProcess.set(true);
        assertEquals(5, PinMoreChats.pinLimit(5));
        PausedProcess.set(false);
        Utils.setContext(null);
        try {
            assertEquals(5, PinMoreChats.pinLimit(5));
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
        assertEquals(PinMoreChats.LIFTED_LIMIT, PinMoreChats.pinLimit(5));
        Settings.PIN_MORE_CHATS.save(false);
        assertEquals("turned back off, the next read is TikTok's", 5, PinMoreChats.pinLimit(5));
    }

    /** The row shows under Inbox only where the patch hooked the cap. */
    @Test
    public void theRowShowsOnlyWhereTheCapWasHooked() {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean hooked = SettingsStatus.pinMoreChatsEnabled;
        try (var controller = Robolectric.buildActivity(InboxFilterTabsTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;

            SettingsStatus.pinMoreChatsEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.PIN_MORE_CHATS.key));

            SettingsStatus.pinMoreChatsEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, with);
            Preference row = with.findPreference(Settings.PIN_MORE_CHATS.key);
            assertNotNull(row);
            assertTrue(row instanceof TogglePreference);
            assertEquals("Pin more chats", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("pinned chats"));
            assertTrue(summary, summary.endsWith(TogglePreference.RESTART_SENTENCE));
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.pinMoreChatsEnabled = hooked;
        }
    }
}
