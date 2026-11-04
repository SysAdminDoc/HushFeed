/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.robolectric.annotation.GraphicsMode;

/**
 * The row under the search switches the home page between pages folded under their rows and every
 * page open in one list, and the choice comes back on the next open, paused or not. The restart
 * bar and the search sit on the cards' 18 dp side line.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsViewToggleTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 640;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private final List<String> owedBefore = new ArrayList<>();
    private ActivityController<Page> owner;
    private TikTokPreferenceFragment page;

    public static class Page extends Activity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            Utils.setIsDarkModeEnabled(true);
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
        owedBefore.addAll(AbstractPreferenceFragment.restartPending);
        Settings.SETTINGS_AS_LIST.resetToDefault();
    }

    @After public void restore() throws Exception {
        if (owner != null) owner.close();
        Method reset = HushfeedPause.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        Settings.SETTINGS_AS_LIST.resetToDefault();
        AbstractPreferenceFragment.restartPending.clear();
        AbstractPreferenceFragment.restartPending.addAll(owedBefore);
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

    private ListView list() {
        return page.getView().findViewById(android.R.id.list);
    }

    private ListAdapter adapter() {
        return list().getAdapter();
    }

    private int position(Object wanted) {
        ListAdapter adapter = adapter();
        for (int position = 0; position < adapter.getCount(); position++) {
            if (adapter.getItem(position) == wanted) return position;
        }
        return -1;
    }

    private Preference keyed(String key) {
        Preference found = page.getPreferenceScreen().findPreference(key);
        assertNotNull("nothing keyed " + key, found);
        return found;
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        layoutPage();
    }

    private View render(Preference row) {
        int position = position(row);
        assertTrue("not on the page: " + row.getKey(), position >= 0);
        View view = adapter().getView(position, null, list());
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, WIDTH, view.getMeasuredHeight());
        return view;
    }

    private int dp(int value) {
        return SettingsUi.dp(owner.get(), value);
    }

    /** The row's chevron when it shows, else null. A detached row is never isShown(). */
    private static View chevron(View row) {
        View chevron = row.findViewWithTag(ScreenColors.CHEVRON);
        if (chevron == null || chevron.getVisibility() != View.VISIBLE
                || ((View) chevron.getParent()).getVisibility() != View.VISIBLE) return null;
        return chevron;
    }

    /** The home page's rows for the pages that open in place, About left out. */
    private List<SettingsMenuPreference> pages() {
        List<SettingsMenuPreference> pages = new ArrayList<>();
        ListAdapter adapter = adapter();
        for (int position = 0; position < adapter.getCount(); position++) {
            Object item = adapter.getItem(position);
            if (item instanceof SettingsMenuPreference
                    && ((SettingsMenuPreference) item).getKey().startsWith("section_")) {
                pages.add((SettingsMenuPreference) item);
            }
        }
        assertTrue("too few pages to mean anything: " + pages.size(), pages.size() >= 10);
        return pages;
    }

    /** How many of [group]'s rows the list draws. */
    private int shownRows(PreferenceGroup group) {
        int shown = 0;
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference child = group.getPreference(index);
            if (position(child) >= 0) shown++;
            if (child instanceof PreferenceGroup) shown += shownRows((PreferenceGroup) child);
        }
        return shown;
    }

    private void assertEveryPageOpenInPlace() {
        for (SettingsMenuPreference menu : pages()) {
            assertFalse(menu.getTitle() + " still folds", menu.isExpandable());
            assertFalse(menu.getTitle() + " can still be tapped", menu.isSelectable());
            assertTrue(menu.getTitle() + " shows none of its rows", shownRows(menu) > 0);
            View row = render(menu);
            assertNull(menu.getTitle() + " promises a tap with a chevron", chevron(row));
            assertTrue(menu.getTitle() + " isn't read as the heading of its rows", row.isAccessibilityHeading());
        }
    }

    private void assertEveryPageFolded() {
        for (SettingsMenuPreference menu : pages()) {
            assertTrue(menu.getTitle() + " doesn't fold", menu.isExpandable());
            assertFalse(menu.getTitle() + " is open", menu.isExpanded());
            assertTrue(menu.getTitle() + " can't be tapped", menu.isSelectable());
            assertEquals(menu.getTitle() + " shows its rows folded", 0, shownRows(menu));
        }
    }

    @Test public void theToggleSitsUnderTheSearchAndNamesTheViewATapGives() {
        openHome();
        Preference toggle = keyed(SettingsViewTogglePreference.KEY);
        int search = position(keyed("action_search_settings"));
        assertEquals("the toggle isn't right under the search", search + 1, position(toggle));
        assertTrue("the toggle isn't above the first page", position(toggle) < position(pages().get(0)));
        assertEquals("Show all settings as a list", String.valueOf(toggle.getTitle()));
        assertNotNull("the toggle has no icon", toggle.getIcon());
        assertTrue("a tap switches in place", ((SettingsViewTogglePreference) toggle).actsOnTap());

        View row = render(toggle);
        assertEquals("the toggle isn't a 48 dp row", dp(48), row.getMinimumHeight());
        assertNull("the toggle promises a page with a chevron", chevron(row));
        Drawable background = row.getBackground();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("the toggle isn't drawn as a page row", background instanceof InsetDrawable);
        InsetDrawable inset = (InsetDrawable) background;
        inset.setBounds(0, 0, row.getWidth(), row.getHeight());
        assertEquals("the toggle isn't full bleed", -dp(1), inset.getDrawable().getBounds().left);
    }

    @Test public void aTapShowsEveryPageOpenAndATapBackFoldsThem() {
        openHome();
        assertEveryPageFolded();

        tap(keyed(SettingsViewTogglePreference.KEY));
        assertTrue("the list view wasn't saved", Settings.SETTINGS_AS_LIST.get());
        assertEquals("Show settings by category",
                String.valueOf(keyed(SettingsViewTogglePreference.KEY).getTitle()));
        assertEveryPageOpenInPlace();

        tap(keyed(SettingsViewTogglePreference.KEY));
        assertFalse("the category view wasn't saved", Settings.SETTINGS_AS_LIST.get());
        assertEquals("Show all settings as a list",
                String.valueOf(keyed(SettingsViewTogglePreference.KEY).getTitle()));
        assertEveryPageFolded();
    }

    @Test public void theListViewComesBackOnTheNextOpenEvenWhilePaused() throws Exception {
        Settings.SETTINGS_AS_LIST.save(true);
        Method pause = HushfeedPause.class.getDeclaredMethod("pauseForTests", HushfeedPause.Reason.class);
        pause.setAccessible(true);
        pause.invoke(null, HushfeedPause.Reason.SWITCH);
        openHome();
        assertEquals("Show settings by category",
                String.valueOf(keyed(SettingsViewTogglePreference.KEY).getTitle()));
        assertEveryPageOpenInPlace();
    }

    @Test public void aSearchFoldsTheListAndEndingItOpensItAgain() throws Exception {
        Settings.SETTINGS_AS_LIST.save(true);
        openHome();
        tap(keyed("action_search_settings"));
        assertNull("the toggle sits between the search and its results",
                page.getPreferenceScreen().findPreference(SettingsViewTogglePreference.KEY));
        for (SettingsMenuPreference menu : pages()) {
            assertEquals(menu.getTitle() + " stayed open under the search", 0, shownRows(menu));
        }

        Method close = TikTokPreferenceFragment.class.getDeclaredMethod("closeSearch");
        close.setAccessible(true);
        assertTrue((Boolean) close.invoke(page));
        layoutPage();
        assertNotNull("the toggle didn't come back", page.getPreferenceScreen()
                .findPreference(SettingsViewTogglePreference.KEY));
        assertEveryPageOpenInPlace();
        assertTrue("ending the search changed the view", Settings.SETTINGS_AS_LIST.get());
    }

    @Test public void theRestartBarIsAnOutlinedButtonOnTheEighteenDpLine() throws Exception {
        openHome();
        AbstractPreferenceFragment.restartPending.add(Settings.REGION_SPOOF.key);
        Method refresh = TikTokPreferenceFragment.class.getDeclaredMethod("refreshRestartPending");
        refresh.setAccessible(true);
        refresh.invoke(page);
        layoutPage();

        View holder = render(keyed(RestartPendingPreference.KEY));
        assertTrue("the restart bar was painted over as a page row",
                holder.getBackground() instanceof ColorDrawable);
        assertEquals(dp(18), holder.getPaddingStart());
        assertEquals(dp(18), holder.getPaddingEnd());
        assertEquals(dp(8), holder.getPaddingTop());
        assertEquals(dp(8), holder.getPaddingBottom());

        TextView bar = holder.findViewWithTag(RestartPendingPreference.ROW_TAG);
        assertNotNull("no restart bar", bar);
        assertEquals("Restart TikTok to apply this change", bar.getText().toString());
        assertTrue("the bar is shorter than 56 dp", bar.getHeight() >= dp(56));
        assertEquals(dp(18), bar.getLeft());
        assertEquals(WIDTH - dp(18), bar.getRight());
        ScreenColors colors = ScreenColors.current();
        assertEquals(colors.title, bar.getCurrentTextColor());
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15,
                owner.get().getResources().getDisplayMetrics()), bar.getTextSize(), 0.01f);
        Drawable arrow = bar.getCompoundDrawablesRelative()[0];
        assertNotNull("the bar has no restart arrow", arrow);
        assertEquals(new Rect(0, 0, dp(24), dp(24)), arrow.getBounds());

        assertTrue("the bar shows no press", bar.getBackground() instanceof RippleDrawable);
        Drawable surface = ((RippleDrawable) bar.getBackground()).getDrawable(0);
        assertTrue(surface instanceof GradientDrawable);
        assertEquals(dp(SettingsUi.RADIUS_SETTINGS_CARD), ((GradientDrawable) surface).getCornerRadius(), 0.01f);
        Bitmap bitmap = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        surface.setBounds(0, 0, 200, 60);
        surface.draw(canvas);
        assertNear("the bar's edge isn't the heading colour", colors.heading, bitmap.getPixel(100, 0));
        assertNear("the bar isn't filled with the card colour", colors.card, bitmap.getPixel(100, 30));
        bitmap.recycle();
    }

    @Test public void theSearchSitsOnTheEighteenDpLine() {
        openHome();
        View entry = render(keyed("action_search_settings"));
        assertTrue(entry.getBackground() instanceof InsetDrawable);
        InsetDrawable inset = (InsetDrawable) entry.getBackground();
        inset.setBounds(0, 0, entry.getWidth(), entry.getHeight());
        Rect field = inset.getDrawable().getBounds();
        assertEquals("the search's start edge is off the line", dp(18), field.left);
        assertEquals("the search's end edge is off the line", WIDTH - dp(18), field.right);
        assertEquals("the search's icon isn't on the cards' icon line", dp(30), entry.getPaddingStart());

        tap(keyed("action_search_settings"));
        View input = render(keyed("action_search_settings"));
        EditText text = input.findViewWithTag("settings_search_input");
        assertNotNull(text);
        View frame = (View) text.getParent();
        assertEquals("the search field's start edge is off the line", dp(18), frame.getLeft());
        assertEquals("the search field's end edge is off the line", WIDTH - dp(18), frame.getRight());
        assertTrue(frame.getLayoutParams() instanceof ViewGroup.MarginLayoutParams);
    }

    private static void assertNear(String message, int expected, int actual) {
        int distance = Math.abs(Color.red(expected) - Color.red(actual))
                + Math.abs(Color.green(expected) - Color.green(actual))
                + Math.abs(Color.blue(expected) - Color.blue(actual));
        assertTrue(message + String.format(": expected %08X, was %08X", expected, actual), distance <= 24);
    }
}
