/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #502: TikTok Lite's author row carries the date and, with Show author region, the country the
 * video was posted from. Each switch only counts in a build that patched it in, and the date
 * stays off wherever Lite leaves it off unless Always show publish date asks for it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiteAuthorRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String DATE = "3d ago";
    private static final String SEP = AuthorRegion.SEPARATOR;

    private boolean datePatched;
    private boolean regionPatched;
    private final Object video = new Object();

    @Before
    public void setUp() {
        datePatched = SettingsStatus.alwaysShowPublishDateEnabled;
        regionPatched = SettingsStatus.authorRegionEnabled;
        LiteVideoRegion.clear();
    }

    @After
    public void tearDown() {
        SettingsStatus.alwaysShowPublishDateEnabled = datePatched;
        SettingsStatus.authorRegionEnabled = regionPatched;
        Settings.ALWAYS_SHOW_PUBLISH_DATE.resetToDefault();
        Settings.SHOW_AUTHOR_REGION.resetToDefault();
        Settings.REGION_AS_FLAG.resetToDefault();
        LiteVideoRegion.clear();
    }

    /** What the row ends up with after Lite's checks said {@code lite} (non-zero shows the date). */
    private String bind(int lite) {
        return LiteAuthorRow.show(lite, video) ? LiteAuthorRow.text(DATE, video) : null;
    }

    @Test
    public void theDateShowsWhereLiteHidesItWhileTheSwitchIsOn() {
        SettingsStatus.alwaysShowPublishDateEnabled = true;
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(true);
        assertEquals(DATE, bind(0));
        assertEquals(DATE, bind(1));

        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(false);
        assertFalse("Lite's own answer stands", LiteAuthorRow.show(0, video));
        assertEquals(DATE, bind(1));
    }

    @Test
    public void theCountryFollowsTheDateOrStandsAloneWhereLiteHidesTheDate() {
        SettingsStatus.authorRegionEnabled = true;
        Settings.SHOW_AUTHOR_REGION.save(true);
        LiteVideoRegion.keep(video, "us");

        assertEquals(DATE + SEP + "US", bind(1));
        assertEquals("For You keeps its date off, so only the country shows", "US", bind(0));

        Settings.REGION_AS_FLAG.save(true);
        assertEquals(DATE + SEP + "🇺🇸", bind(1));
    }

    @Test
    public void bothSwitchesTogether() {
        SettingsStatus.alwaysShowPublishDateEnabled = true;
        SettingsStatus.authorRegionEnabled = true;
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(true);
        Settings.SHOW_AUTHOR_REGION.save(true);
        LiteVideoRegion.keep(video, "JP");
        assertEquals(DATE + SEP + "JP", bind(0));
        assertEquals(DATE + SEP + "JP", bind(1));
    }

    @Test
    public void noCountryLeavesLitesRowAlone() {
        SettingsStatus.authorRegionEnabled = true;
        Settings.SHOW_AUTHOR_REGION.save(true);
        assertFalse("nothing known for this video", LiteAuthorRow.show(0, video));
        assertEquals(DATE, bind(1));

        LiteVideoRegion.keep(video, "FAKE-AD");
        assertFalse("an ad's made up region is no country", LiteAuthorRow.show(0, video));
        assertEquals(DATE, bind(1));
    }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    /** Lite only gets the country after the date, so its page has no caption line or handle rows. */
    @Test
    public void litesPageOffersOnlyTheCountryRows() {
        boolean fullRow = SettingsStatus.authorRegionFullRowEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.authorRegionEnabled = true;

            SettingsStatus.authorRegionFullRowEnabled = false;
            PreferenceScreen lite = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, lite);
            assertNotNull(lite.findPreference(Settings.SHOW_AUTHOR_REGION.key));
            assertNotNull(lite.findPreference(Settings.REGION_AS_FLAG.key));
            assertNull(lite.findPreference(Settings.COUNTRY_DATE_UNDER_CAPTION.key));
            assertNull(lite.findPreference(Settings.COUNTRY_DATE_SHADOW.key));
            assertNull(lite.findPreference(Settings.SHOW_AUTHOR_HANDLE.key));

            SettingsStatus.authorRegionFullRowEnabled = true;
            PreferenceScreen tiktok = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, tiktok);
            assertNotNull(tiktok.findPreference(Settings.COUNTRY_DATE_UNDER_CAPTION.key));
            assertNotNull(tiktok.findPreference(Settings.SHOW_AUTHOR_HANDLE.key));
        } finally {
            SettingsStatus.authorRegionFullRowEnabled = fullRow;
        }
    }

    @Test
    public void aSwitchSavedOnCountsOnlyInABuildThatPatchedItIn() {
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(true);
        Settings.SHOW_AUTHOR_REGION.save(true);
        LiteVideoRegion.keep(video, "US");
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        SettingsStatus.authorRegionEnabled = false;
        assertFalse(LiteAuthorRow.show(0, video));
        assertTrue(LiteAuthorRow.show(1, video));
        assertEquals(DATE, LiteAuthorRow.text(DATE, video));
    }
}
