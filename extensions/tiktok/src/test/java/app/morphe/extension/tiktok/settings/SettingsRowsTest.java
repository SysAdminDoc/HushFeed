/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Hide Settings and privacy rows: TikTok's sorted page list loses the picked rows by enum name,
 * a section left with none loses its header, and the names listed are kept for the checklist.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Three of the 47.x page's sections, each an enum led by its header, as TikTok builds them. */
    enum Account { SECTION_HEADER, ACCOUNT_ITEM, PRIVACY, QRCODE }

    enum Cache { SECTION_HEADER, OFFLINE_VIDEOS, CLEAR_CACHE }

    enum Support {
        SECTION_HEADER, OPEN_DEBUG, FEEDBACK,
        // A constant with a body is a subclass of its enum, and still belongs to its section.
        SUPPORT { @Override public String toString() { return "support"; } }
    }

    @Before public void setUp() {
        HookStatus.clear();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.SETTINGS_ROW_PICKS.save("");
        Settings.SETTINGS_ROW_CATALOG.save("");
        HookStatus.clear();
    }

    private static List<Object> page() {
        return new ArrayList<>(Arrays.asList(
                Account.SECTION_HEADER, Account.ACCOUNT_ITEM, Account.PRIVACY, Account.QRCODE,
                Cache.SECTION_HEADER, Cache.OFFLINE_VIDEOS, Cache.CLEAR_CACHE,
                Support.SECTION_HEADER, Support.FEEDBACK, Support.SUPPORT));
    }

    @Test public void thePickedRowsGoAndASectionLeftEmptyLosesItsHeader() {
        Settings.SETTINGS_ROW_PICKS.save("qrcode, Offline_Videos\nclear_cache, support");
        List<Object> rows = page();
        Object hushfeed = new Object();

        TikTokActivityHook.addSettingsRowFirst(rows, hushfeed);

        assertEquals(Arrays.asList(hushfeed,
                Account.SECTION_HEADER, Account.ACCOUNT_ITEM, Account.PRIVACY,
                Support.SECTION_HEADER, Support.FEEDBACK), rows);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("settings rows: 1 found, 0 missing"));
    }

    @Test public void aHeaderStaysWhenItsSectionLostNothing() {
        Settings.SETTINGS_ROW_PICKS.save("feedback");
        List<Object> rows = new ArrayList<>(Arrays.asList(
                Account.SECTION_HEADER, Support.SECTION_HEADER, Support.FEEDBACK));

        SettingsRows.filter(rows);

        assertEquals(List.of(Account.SECTION_HEADER), rows);
    }

    @Test public void theHushfeedRowIsLeftToItsOwnSwitch() {
        Settings.SETTINGS_ROW_PICKS.save("open_debug, feedback, support");
        List<Object> rows = new ArrayList<>(Arrays.asList(
                Support.SECTION_HEADER, Support.OPEN_DEBUG, Support.FEEDBACK, Support.SUPPORT));

        SettingsRows.filter(rows);

        assertEquals("a section that still has a row keeps its header",
                List.of(Support.SECTION_HEADER, Support.OPEN_DEBUG), rows);
    }

    @Test public void pausedThePageStaysWholeAndIsStillRecorded() {
        Settings.SETTINGS_ROW_PICKS.save("qrcode, privacy");
        PausedProcess.set(true);
        List<Object> rows = page();

        SettingsRows.filter(rows);

        assertEquals(page(), rows);
        assertEquals("account_item,privacy,qrcode,offline_videos,clear_cache,feedback,support",
                Settings.SETTINGS_ROW_CATALOG.savedValue());
    }

    @Test public void theListedNamesAreAddedToAndNothingIsWrittenWhenNoneAreNew() {
        Settings.SETTINGS_ROW_CATALOG.save("signup,whats_new");
        List<Object> rows = new ArrayList<>(Arrays.asList("not an enum", Support.SECTION_HEADER,
                Support.OPEN_DEBUG, Support.FEEDBACK));

        SettingsRows.filter(rows);
        assertEquals("signup,whats_new,feedback", Settings.SETTINGS_ROW_CATALOG.savedValue());

        Settings.SETTINGS_ROW_CATALOG.save("feedback,signup,whats_new");
        SettingsRows.filter(rows);
        assertEquals("feedback,signup,whats_new", Settings.SETTINGS_ROW_CATALOG.savedValue());
    }

    @Test public void theChecklistOffersWhatThisPhoneListedInThePagesOrder() {
        Settings.SETTINGS_ROW_CATALOG.save("logout,feedback,new_row_name,account_item");

        List<String> labels = new ArrayList<>();
        for (SettingsRows.Row row : SettingsRows.offered(Set.of("shake"))) labels.add(row.label);

        assertEquals(List.of("Account", "Shake to report", "Help Center", "Log out", "New row name"),
                labels);
    }

    /**
     * The 36 names TikTok 47.9.2 listed on the S22 (2026-11-20), as the hook recorded them, and
     * the labels and order that phone's Settings and privacy page showed, section titles left out.
     */
    @Test public void the4792PageGetsItsOwnLabelsInItsOwnOrder() {
        Settings.SETTINGS_ROW_CATALOG.save("analytics_center,manage_posts,content_preference,live_settings,"
                + "notification_settings,screen_time,family_pairing,account_item,security_and_permissions,"
                + "share_profile,switch_inbox_mode,audience_control,ads_settings,playback_settings,display,"
                + "language,accessibility,contacts_and_location,offline_videos,clear_cache,data_saver,"
                + "local_live_wallpaper,feedback,privacy_center,about,private_account,blocked_account,comments,"
                + "mention,direct_messages,reuse_of_content,shared_links,downloads,following_list,liked_video,"
                + "viewers");

        List<String> labels = new ArrayList<>();
        for (SettingsRows.Row row : SettingsRows.offered(Set.of())) labels.add(row.label);

        assertEquals(List.of("Manage posts", "Content preferences", "LIVE", "Notifications",
                "Time and well-being", "Family Pairing", "Account", "Security & permissions", "Share profile",
                "Private account", "Blocked accounts",
                "Comments", "Mentions", "Direct messages", "Reuse of content",
                "Display profile when sharing links", "Downloads", "Following list", "Liked videos", "Viewers",
                "Inbox & Messaging", "Activity center", "Audience control", "Ads", "Playback", "Display",
                "Language", "Accessibility", "Contacts and location",
                "Offline videos", "Free up space", "Data Saver", "Wallpaper",
                "Help Center", "Privacy Center", "Terms and Policies"), labels);
    }

    @Test public void withNothingListedYetTheChecklistOffersTheWholeTable() {
        assertEquals(SettingsRows.ROWS.size(), SettingsRows.offered(Set.of()).size());
        assertEquals("Manage posts", SettingsRows.offered(Set.of()).get(0).label);
    }

    @Test public void aListThatCanNotChangeIsLeftAsItIs() {
        Settings.SETTINGS_ROW_PICKS.save("qrcode");
        List<Object> rows = List.of(Account.SECTION_HEADER, Account.QRCODE);

        SettingsRows.filter(rows);

        assertEquals(List.of(Account.SECTION_HEADER, Account.QRCODE), rows);
    }
}
