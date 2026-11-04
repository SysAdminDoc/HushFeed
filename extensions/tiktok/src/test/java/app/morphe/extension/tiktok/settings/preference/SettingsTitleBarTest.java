/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for hushfeed (TikTok), 2026.
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The main page shows Hushfeed's name once: the header's while it's on the screen, and the bar's
 * otherwise. A search names itself in the bar either way, and the bar stays put above the rows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, qualifiers = "w320dp-h480dp-night-mdpi")
@SuppressWarnings("deprecation")
public class SettingsTitleBarTest {
    private static final int WIDTH = 320, HEIGHT = 480;
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private ActivityController<Page> owner;
    private TikTokPreferenceFragment page;

    public static class Page extends Activity {
        @Override public void onCreate(Bundle state) {
            boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
            Utils.setIsDarkModeEnabled(dark);
            super.onCreate(state);
        }
    }

    @Before public void installControls() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void restore() throws Exception {
        if (owner != null) owner.close();
        for (var entry : statuses.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
        Utils.setIsDarkModeEnabled(true);
    }

    private void openHome() {
        owner = Robolectric.buildActivity(Page.class).setup().visible();
        Activity activity = owner.get();
        Utils.setContext(activity);
        page = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, page).commit();
        activity.getFragmentManager().executePendingTransactions();
        layoutPage();
    }

    private void layoutPage() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View root = page.getView();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private LinearLayout bar() {
        return page.getView().findViewWithTag("hushfeed_page_header");
    }

    private TextView barTitle() {
        return page.getView().findViewWithTag("hushfeed_page_title");
    }

    private ListView list() {
        return page.getView().findViewById(android.R.id.list);
    }

    private int barTop() {
        int[] where = new int[2];
        bar().getLocationInWindow(where);
        return where[1];
    }

    /**
     * Scrolls the page by [pixels], down for more than zero, the way a finger does. A selection
     * would skip the header, which isn't selectable, and land on the first row that is.
     */
    private void scrollBy(int pixels) {
        list().scrollListBy(pixels);
        layoutPage();
    }

    private void scrollToTop() {
        ListView list = list();
        for (int i = 0; i < 40 && (list.getFirstVisiblePosition() > 0
                || list.getChildAt(0).getTop() < list.getPaddingTop()); i++) {
            scrollBy(-list.getHeight() / 3);
        }
        assertEquals("at the top", 0, list.getFirstVisiblePosition());
    }

    /** How many times a person reading the screen sees the page's name: in the bar, the header or both. */
    private int namesShown() {
        int count = 0;
        TextView title = barTitle();
        if (title.isShown() && "Hushfeed".contentEquals(title.getText())) count++;
        View name = list().findViewWithTag(SettingsHeader.NAME);
        if (name != null && name.isShown() && name.getLocalVisibleRect(new Rect())) count++;
        return count;
    }

    private void type(String query) throws Exception {
        Field input = TikTokPreferenceFragment.class.getDeclaredField("searchInput");
        input.setAccessible(true);
        ((SettingsSearchInputPreference) input.get(page)).setQuery(query);
        var update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(page, query);
        layoutPage();
    }

    @Test public void theMainPageShowsTheNameOnceInItsHeader() {
        openHome();
        TextView name = list().findViewWithTag(SettingsHeader.NAME);
        assertNotNull("the header's name is on the page", name);
        assertTrue(name.isShown());
        assertEquals("the bar leaves the name to the header", View.INVISIBLE, barTitle().getVisibility());
        assertEquals("the bar keeps the page's name for when it shows", "Hushfeed", barTitle().getText().toString());
        assertEquals(1, namesShown());
        LinearLayout toolbar = page.getView().findViewWithTag("hushfeed_toolbar");
        assertTrue("Back stays in the bar", toolbar.getChildAt(0).isShown());
        assertTrue("the status stays in the bar", page.getView().findViewWithTag("hushfeed_compact_status").isShown());
    }

    @Test @Config(qualifiers = "w320dp-h480dp-notnight-mdpi")
    public void theLightPageShowsTheNameOnceToo() {
        openHome();
        assertSame(ScreenColors.LIGHT, ScreenColors.current());
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        assertEquals(1, namesShown());
    }

    @Test public void theBarIsPinnedAboveTheRowsAndNotOneOfThem() {
        openHome();
        assertNull("the bar scrolls with the rows", list().findViewWithTag("hushfeed_page_title"));
        assertTrue("the bar isn't above the rows", barTop() + bar().getHeight() <= rowsTop());
        int top = barTop(), height = bar().getHeight();
        scrollBy(list().getHeight());
        assertEquals("the bar moved with the rows", top, barTop());
        assertEquals(height, bar().getHeight());
    }

    private int rowsTop() {
        int[] where = new int[2];
        list().getLocationInWindow(where);
        return where[1];
    }

    @Test public void scrollingTheHeaderAwayPutsTheNameBackInTheBar() {
        openHome();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        scrollBy(list().getHeight());
        assertTrue("the header row has scrolled off", list().getFirstVisiblePosition() > 0);
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("Hushfeed", barTitle().getText().toString());
        assertEquals(1, namesShown());
        scrollToTop();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        assertEquals(1, namesShown());
    }

    @Test public void aSearchNamesItselfInTheBarAndClosingItHandsTheNameBack() throws Exception {
        openHome();
        int height = bar().getHeight();
        Preference search = page.findPreference("action_search_settings");
        assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
        layoutPage();
        assertEquals("an empty search is still the page", 1, namesShown());
        type("speed");
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("Search settings", barTitle().getText().toString());
        assertEquals("the rows don't move when the title shows", height, bar().getHeight());
        type("");
        assertEquals("the page is named once after a search is cleared", 1, namesShown());
        assertTrue(page.closeInlineSearch());
        layoutPage();
        assertEquals("the page is named once after a search", 1, namesShown());
        scrollToTop();
        assertEquals("back at the top the header has the name", View.INVISIBLE, barTitle().getVisibility());
        assertEquals("Hushfeed", barTitle().getText().toString());
        assertEquals(1, namesShown());
        assertEquals(height, bar().getHeight());
    }

    @Test public void aRebuiltPageKeepsOneBar() {
        openHome();
        owner.recreate();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        page = (TikTokPreferenceFragment) owner.get().getFragmentManager().findFragmentById(android.R.id.content);
        assertNotNull(page);
        layoutPage();
        View root = page.getView();
        assertEquals(1, count(root, "hushfeed_page_header"));
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        assertEquals(1, namesShown());
    }

    private static int count(View view, String tag) {
        int found = tag.equals(view.getTag()) ? 1 : 0;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found += count(group.getChildAt(i), tag);
        }
        return found;
    }
}
