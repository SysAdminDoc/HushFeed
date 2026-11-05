package app.morphe.extension.tiktok.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Show only the search bar on Discover: the hashtags, banners and tabs go, the box stays (#338). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DiscoverSearchBarOnlyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's content pager, an androidx ViewPager under an R8 name. */
    public static class ViewPager extends FrameLayout {
        public ViewPager(Context context) {
            super(context);
        }
    }

    public static final class RenamedPager extends ViewPager {
        public RenamedPager(Context context) {
            super(context);
        }
    }

    private ActivityController<Activity> owner;
    private LinearLayout page;
    private FrameLayout searchBox;
    private TextView hint;
    private HorizontalScrollView tabs;
    private RenamedPager pager;
    private TextView banner;

    @Before public void setUp() {
        DiscoverSearchBarOnly.resetForTests();
        PausedProcess.set(false);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "hideSearchSuggestionsEnabled", true);
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = owner.get();
        page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        searchBox = new FrameLayout(activity);
        hint = new TextView(activity);
        hint.setText("Search");
        searchBox.addView(hint);
        tabs = new HorizontalScrollView(activity);
        tabs.addView(new TextView(activity));
        pager = new RenamedPager(activity);
        banner = new TextView(activity);
        banner.setText("#trending");
        pager.addView(banner);
        FrameLayout top = new FrameLayout(activity);
        top.addView(searchBox);
        page.addView(top);
        page.addView(tabs);
        page.addView(pager);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.DISCOVER_SEARCH_BAR_ONLY.resetToDefault();
        ReflectionHelpers.setStaticField(SettingsStatus.class, "hideSearchSuggestionsEnabled", false);
        DiscoverSearchBarOnly.resetForTests();
        owner.pause().stop().destroy();
    }

    @Test public void offByDefaultDiscoverKeepsEverything() {
        assertFalse(Settings.DISCOVER_SEARCH_BAR_ONLY.get());
        open();
        assertAllShown();
    }

    @Test public void onOnlyTheSearchBoxIsLeft() {
        Settings.DISCOVER_SEARCH_BAR_ONLY.save(true);
        open();
        assertEquals("the hashtags and banners still show", View.GONE, pager.getVisibility());
        assertEquals("the tabs under the box still show", View.GONE, tabs.getVisibility());
        assertEquals("the search box went too", View.VISIBLE, searchBox.getVisibility());
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertEquals("a view inside the pager was touched", View.VISIBLE, banner.getVisibility());
    }

    @Test public void whatTikTokShowsAgainGoesBeforeTheNextFrame() {
        Settings.DISCOVER_SEARCH_BAR_ONLY.save(true);
        open();
        pager.setVisibility(View.VISIBLE);
        assertFalse("the stale frame was drawn", preDraw());
        assertEquals(View.GONE, pager.getVisibility());
        assertTrue("a frame with nothing to change was held back", preDraw());
    }

    @Test public void switchedOffOrPausedEverythingComesBackAndOnAgainItGoes() {
        tabs.setVisibility(View.INVISIBLE);
        Settings.DISCOVER_SEARCH_BAR_ONLY.save(true);
        open();
        assertEquals(View.GONE, tabs.getVisibility());

        Settings.DISCOVER_SEARCH_BAR_ONLY.save(false);
        preDraw();
        assertEquals(View.VISIBLE, pager.getVisibility());
        assertEquals("the tabs didn't go back to TikTok's own visibility", View.INVISIBLE, tabs.getVisibility());

        Settings.DISCOVER_SEARCH_BAR_ONLY.save(true);
        preDraw();
        assertEquals(View.GONE, pager.getVisibility());

        PausedProcess.set(true);
        preDraw();
        assertEquals("paused, Discover stayed cut down", View.VISIBLE, pager.getVisibility());
    }

    @Test public void withoutThePatchTheSwitchDoesNothing() {
        ReflectionHelpers.setStaticField(SettingsStatus.class, "hideSearchSuggestionsEnabled", false);
        Settings.DISCOVER_SEARCH_BAR_ONLY.save(true);
        open();
        assertAllShown();
    }

    @Test public void aPageLeftBehindIsNoLongerWatched() {
        Settings.DISCOVER_SEARCH_BAR_ONLY.save(true);
        open();
        owner.get().setContentView(new FrameLayout(owner.get()));
        pager.setVisibility(View.VISIBLE);
        preDraw();
        assertEquals("a page off the window was still changed", View.VISIBLE, pager.getVisibility());
    }

    private void open() {
        DiscoverSearchBarOnly.onViewCreated(page);
        owner.get().setContentView(page);
        preDraw();
    }

    /** Whether the frame went ahead: a listener that changed the page holds it back. */
    private boolean preDraw() {
        return !owner.get().getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
    }

    private void assertAllShown() {
        assertEquals(View.VISIBLE, pager.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
        assertEquals(View.VISIBLE, searchBox.getVisibility());
    }
}
