package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.navigation.ForYouTabSettingsShortcut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Hide the Hushfeed row (#264): the patched Settings and privacy list asks this before adding it.
 * The row only goes while a long press still opens the settings (#244).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsRowVisibilityTest {
    /** The tag TikTok's top tab model answers for For You. */
    private static final String FOR_YOU = "homepage_hot";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> owner;
    private FrameLayout strip;
    private LinearLayout tab;

    @Before
    public void setUp() {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
    }

    @After
    public void reset() {
        Settings.HIDE_SETTINGS_ROW.resetToDefault();
        Settings.HIDE_FEED_TAB_STRIP.resetToDefault();
        Settings.FOR_YOU_HOLD_OPENS_WATCH_HISTORY.resetToDefault();
        Settings.LOCAL_WATCH_HISTORY.resetToDefault();
        // Off the window, the tab is no way in for the next test.
        if (strip != null) strip.removeAllViews();
        owner.pause().stop().destroy();
    }

    /** TikTok's For You tab on screen, with Hushfeed's long press on it. */
    private void forYouTab() {
        Activity activity = owner.get();
        tab = new LinearLayout(activity);
        tab.setClickable(true);
        strip = new FrameLayout(activity);
        strip.addView(tab, new FrameLayout.LayoutParams(300, 120));
        activity.setContentView(strip);
        ShadowLooper.idleMainLooper();
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        assertTrue("the For You long press isn't set up", ForYouTabSettingsShortcut.opensSettingsNow());
    }

    @Test
    public void theRowShowsUntilTheSwitchIsOn() {
        forYouTab();
        assertFalse(Settings.HIDE_SETTINGS_ROW.defaultValue);
        assertTrue(TikTokActivityHook.showSettingsRow());

        Settings.HIDE_SETTINGS_ROW.save(true);
        assertFalse(TikTokActivityHook.showSettingsRow());

        Settings.HIDE_SETTINGS_ROW.save(false);
        assertTrue(TikTokActivityHook.showSettingsRow());
    }

    @Test
    public void withNoLongPressToOpenTheSettingsTheRowStays() {
        Settings.HIDE_SETTINGS_ROW.save(true);
        assertTrue("the row went with nothing else left to open the settings",
                TikTokActivityHook.showSettingsRow());
    }

    @Test
    public void losingTheForYouLongPressBringsTheRowBack() {
        forYouTab();
        Settings.HIDE_SETTINGS_ROW.save(true);
        assertFalse(TikTokActivityHook.showSettingsRow());

        Settings.HIDE_FEED_TAB_STRIP.save(true);
        assertTrue("the tab strip is hidden, yet the row stayed away", TikTokActivityHook.showSettingsRow());
        Settings.HIDE_FEED_TAB_STRIP.save(false);
        assertFalse(TikTokActivityHook.showSettingsRow());

        Settings.LOCAL_WATCH_HISTORY.save(true);
        Settings.FOR_YOU_HOLD_OPENS_WATCH_HISTORY.save(true);
        assertTrue("For You opens the watch history, yet the row stayed away",
                TikTokActivityHook.showSettingsRow());
        Settings.FOR_YOU_HOLD_OPENS_WATCH_HISTORY.save(false);
        assertFalse(TikTokActivityHook.showSettingsRow());

        strip.setVisibility(View.GONE);
        assertTrue("the tab is out of sight, yet the row stayed away", TikTokActivityHook.showSettingsRow());
        strip.setVisibility(View.VISIBLE);
        assertFalse(TikTokActivityHook.showSettingsRow());

        // TikTok reuses the view for another tab.
        ForYouTabSettingsShortcut.onTopTab(tab, "homepage_follow");
        assertTrue("the view shows Following now, yet the row stayed away", TikTokActivityHook.showSettingsRow());
        ForYouTabSettingsShortcut.onTopTab(tab, FOR_YOU);
        assertFalse(TikTokActivityHook.showSettingsRow());

        tab.setLongClickable(true);
        assertTrue("TikTok took the long press back, yet the row stayed away",
                TikTokActivityHook.showSettingsRow());
        tab.setLongClickable(false);

        strip.removeView(tab);
        assertTrue("the tab left the window, yet the row stayed away", TikTokActivityHook.showSettingsRow());
    }

    @Test
    public void theFeedBehindSettingsAndPrivacyStillCounts() {
        forYouTab();
        Settings.HIDE_SETTINGS_ROW.save(true);
        // Settings and privacy covers TikTok's main screen, whose window Android then hides.
        owner.pause().stop();
        assertFalse("the stopped feed's long press didn't count", TikTokActivityHook.showSettingsRow());
        owner.start().resume();
    }

    @Test
    public void theRowGoesFirstOnlyWhileItShows() {
        forYouTab();
        Object row = new Object();
        List<Object> rows = new ArrayList<>(Arrays.asList("a", "b"));
        TikTokActivityHook.addSettingsRowFirst(rows, row);
        assertEquals(Arrays.asList(row, "a", "b"), rows);

        Settings.HIDE_SETTINGS_ROW.save(true);
        rows = new ArrayList<>(Arrays.asList("a", "b"));
        TikTokActivityHook.addSettingsRowFirst(rows, row);
        TikTokActivityHook.addSettingsRowLast(rows, row);
        assertEquals(Arrays.asList("a", "b"), rows);

        Settings.HIDE_SETTINGS_ROW.save(false);
        TikTokActivityHook.addSettingsRowLast(rows, row);
        assertEquals(Arrays.asList("a", "b", row), rows);
    }

    @Test
    public void theSwitchTravelsWithBackups() {
        assertTrue(Settings.HIDE_SETTINGS_ROW.includeWithImportExport);
    }
}
