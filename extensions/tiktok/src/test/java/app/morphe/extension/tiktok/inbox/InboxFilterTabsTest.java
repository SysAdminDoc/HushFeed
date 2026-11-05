package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Show inbox filters: the mode TikTok's Inbox is told, the creator Inbox pop-up it keeps away,
 * and the row that turns it on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InboxFilterTabsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's inbox mode enum keeps these names. */
    enum Mode { BUSINESS_INBOX, PERSONAL_INBOX, PRO_CREATOR_INBOX }

    /** A mode enum some other build might have, without the creator constant. */
    enum Bare { PERSONAL_INBOX }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void restore() {
        PausedProcess.set(false);
        Settings.SHOW_INBOX_FILTERS.resetToDefault();
    }

    @Test public void offByDefaultAndNeedsARestart() {
        assertFalse(Settings.SHOW_INBOX_FILTERS.defaultValue);
        assertTrue(Settings.SHOW_INBOX_FILTERS.rebootApp);
        assertSame(Mode.PERSONAL_INBOX, InboxFilterTabs.shownInboxMode(Mode.PERSONAL_INBOX));
        assertFalse(InboxFilterTabs.hideProInboxGuide());
    }

    @Test public void onThePersonalInboxIsShownAsTheCreatorOne() {
        Settings.SHOW_INBOX_FILTERS.save(true);
        assertSame(Mode.PRO_CREATOR_INBOX, InboxFilterTabs.shownInboxMode(Mode.PERSONAL_INBOX));
        assertTrue(InboxFilterTabs.hideProInboxGuide());
    }

    @Test public void onTheOtherModesAreLeftAlone() {
        Settings.SHOW_INBOX_FILTERS.save(true);
        assertSame(Mode.BUSINESS_INBOX, InboxFilterTabs.shownInboxMode(Mode.BUSINESS_INBOX));
        assertSame(Mode.PRO_CREATOR_INBOX, InboxFilterTabs.shownInboxMode(Mode.PRO_CREATOR_INBOX));
        assertNull(InboxFilterTabs.shownInboxMode(null));
        assertEquals("PERSONAL_INBOX", InboxFilterTabs.shownInboxMode("PERSONAL_INBOX"));
    }

    @Test public void anEnumWithoutTheCreatorModeKeepsItsOwnAnswer() {
        Settings.SHOW_INBOX_FILTERS.save(true);
        assertSame(Bare.PERSONAL_INBOX, InboxFilterTabs.shownInboxMode(Bare.PERSONAL_INBOX));
        assertSame("asked again", Bare.PERSONAL_INBOX, InboxFilterTabs.shownInboxMode(Bare.PERSONAL_INBOX));
        assertSame(Mode.PRO_CREATOR_INBOX, InboxFilterTabs.shownInboxMode(Mode.PERSONAL_INBOX));
    }

    @Test public void pausedTheInboxIsTikToks() {
        Settings.SHOW_INBOX_FILTERS.save(true);
        PausedProcess.set(true);
        assertSame(Mode.PERSONAL_INBOX, InboxFilterTabs.shownInboxMode(Mode.PERSONAL_INBOX));
        assertFalse(InboxFilterTabs.hideProInboxGuide());
    }

    @Test public void theRowShowsOnlyWhereThePatchFoundItsHooks() {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean tabs = SettingsStatus.inboxFilterTabsEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.inboxFilterTabsEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, without);
            assertNull(byTitle(without, "Show inbox filters"));

            SettingsStatus.inboxFilterTabsEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, with);
            Preference row = byTitle(with, "Show inbox filters");
            assertNotNull(row);
            assertEquals(Settings.SHOW_INBOX_FILTERS.key, row.getKey());
            assertNotNull(row.getSummary());
            String summary = row.getSummary().toString();
            assertTrue(summary, summary.contains("Unread"));
            assertFalse(summary, summary.indexOf((char) 0x2014) >= 0 || summary.indexOf((char) 0x2013) >= 0);
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.inboxFilterTabsEnabled = tabs;
        }
    }

    /** The one row with this title, or null when there's none. */
    private static Preference byTitle(PreferenceGroup group, String title) {
        List<Preference> found = new ArrayList<>();
        collect(group, title, found);
        assertTrue("rows titled " + title + ": " + found.size(), found.size() <= 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collect(PreferenceGroup group, String title, List<Preference> found) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child.getTitle() != null && title.contentEquals(child.getTitle())) found.add(child);
            if (child instanceof PreferenceGroup) collect((PreferenceGroup) child, title, found);
        }
    }
}
