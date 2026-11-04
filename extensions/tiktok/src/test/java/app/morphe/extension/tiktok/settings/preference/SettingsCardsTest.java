/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for hushfeed (TikTok), 2026.
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * The main page's rows run full bleed with no corners. A section opens under its row as one inset
 * card, rounded where it starts and ends and split by its headings, and the search's results sit
 * on the same card. Every row leads with a decorative icon, and only a row that opens something
 * ends in a chevron. Text still wraps at twice the size.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-night-mdpi")
@SuppressWarnings("deprecation")
public class SettingsCardsTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 640;

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
        throw new AssertionError("not on the page: " + wanted);
    }

    private Preference keyed(String key) {
        Preference found = page.findPreference(key);
        assertNotNull("nothing keyed " + key, found);
        return found;
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        layoutPage();
    }

    /** Row [position], drawn by the page's own adapter at the page's width. */
    private View render(int position) {
        ListView list = list();
        View row = adapter().getView(position, null, list);
        row.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, WIDTH, row.getMeasuredHeight());
        return row;
    }

    private View render(Preference row) {
        return render(position(row));
    }

    /** Every section that opens in place under its row on the main page, About included. */
    private List<SettingsMenuPreference> sections() {
        List<SettingsMenuPreference> sections = new ArrayList<>();
        ListAdapter adapter = adapter();
        for (int position = 0; position < adapter.getCount(); position++) {
            Object item = adapter.getItem(position);
            if (item instanceof SettingsMenuPreference && ((SettingsMenuPreference) item).isExpandable()) {
                sections.add((SettingsMenuPreference) item);
            }
        }
        assertTrue("too few sections to mean anything: " + sections.size(), sections.size() >= 10);
        return sections;
    }

    private static void addDescendants(PreferenceGroup group, Set<Object> into) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            into.add(child);
            if (child instanceof PreferenceGroup) addDescendants((PreferenceGroup) child, into);
        }
    }

    /** The positions of [section]'s rows, open, in runs between its headings. */
    private List<List<Integer>> runs(SettingsMenuPreference section) {
        Set<Object> inside = Collections.newSetFromMap(new IdentityHashMap<>());
        addDescendants(section, inside);
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        ListAdapter adapter = adapter();
        for (int position = position(section) + 1;
             position < adapter.getCount() && inside.contains(adapter.getItem(position)); position++) {
            Object item = adapter.getItem(position);
            if (splitsCard(item)) {
                if (!run.isEmpty()) runs.add(run);
                run = new ArrayList<>();
            } else run.add(position);
        }
        if (!run.isEmpty()) runs.add(run);
        return runs;
    }

    private static boolean splitsCard(Object item) {
        return item instanceof SectionHeadingPreference || item instanceof PreferenceCategory
                || item instanceof SettingsStatusPreference || item instanceof SettingsHeaderPreference;
    }

    private static Drawable unwrapped(View row) {
        Drawable background = row.getBackground();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("no card behind the row: " + background, background instanceof InsetDrawable);
        return background;
    }

    /** The surface the row's card is drawn with. */
    private static GradientDrawable card(View row) {
        Drawable surface = ((InsetDrawable) unwrapped(row)).getDrawable();
        assertTrue(surface instanceof GradientDrawable);
        return (GradientDrawable) surface;
    }

    /** Where the card lands inside the row. */
    private static Rect cardBounds(View row) {
        InsetDrawable inset = (InsetDrawable) unwrapped(row);
        inset.setBounds(0, 0, row.getWidth(), row.getHeight());
        return new Rect(inset.getDrawable().getBounds());
    }

    private int dp(int value) {
        return SettingsUi.dp(owner.get(), value);
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                owner.get().getResources().getDisplayMetrics());
    }

    private static ImageView chevron(View row) {
        View chevron = row.findViewWithTag(ScreenColors.CHEVRON);
        if (chevron == null || chevron.getVisibility() != View.VISIBLE
                || ((View) chevron.getParent()).getVisibility() != View.VISIBLE) return null;
        return (ImageView) chevron;
    }

    private static ScreenColors.Chevron chevronDrawable(View row) {
        ImageView chevron = chevron(row);
        assertNotNull("no chevron", chevron);
        return (ScreenColors.Chevron) chevron.getDrawable();
    }

    private static int disabled(ColorStateList colors) {
        return colors.getColorForState(new int[0], 0);
    }

    @Test public void theMainPageRunsFullBleedRowsWithNoCorners() {
        openHome();
        for (String key : new String[]{"section_playback", TikTokPreferenceFragment.REPORT_PROBLEM_KEY,
                TikTokPreferenceFragment.SUPPORT_KEY}) {
            View row = render(keyed(key));
            GradientDrawable card = card(row);
            for (float radius : card.getCornerRadii()) assertEquals(key + " has corners", 0f, radius, 0f);
            assertEquals("the card takes the page's colours", ScreenColors.DARK.card,
                    card.getColor().getDefaultColor());
            Rect bounds = cardBounds(row);
            assertEquals(key + "'s side stroke shows", -dp(1), bounds.left);
            assertEquals(row.getWidth() + dp(1), bounds.right);
            assertEquals("rows meet on one hairline", -dp(1), bounds.top);
            assertEquals(row.getHeight(), bounds.bottom);
            assertEquals(dp(20), row.getPaddingStart());
            assertEquals(dp(20), row.getPaddingEnd());
            assertEquals(dp(6), row.getPaddingTop());
            assertEquals(dp(6), row.getPaddingBottom());
            assertEquals(dp(52), row.getMinimumHeight());
            assertTrue(row.getHeight() >= dp(52));
            TextView title = row.findViewById(android.R.id.title);
            assertEquals(sp(15), title.getTextSize(), 0.01f);
            TextView summary = row.findViewById(android.R.id.summary);
            if (summary != null && summary.getVisibility() == View.VISIBLE) {
                assertEquals(sp(12), summary.getTextSize(), 0.01f);
            }
        }
    }

    @Test @Config(sdk = {23, 34})
    public void everySectionOpensAsOneInsetCardThatItsHeadingsSplit() {
        openHome();
        boolean radii = Build.VERSION.SDK_INT >= 24;
        int headings = 0;
        int rows = 0;
        for (SettingsMenuPreference section : sections()) {
            tap(section);
            assertTrue(section.getTitle() + " didn't open", section.isExpanded());
            List<List<Integer>> runs = runs(section);
            assertFalse(section.getTitle() + " opened empty", runs.isEmpty());
            headings += runs.size() - 1;
            for (List<Integer> run : runs) {
                for (int i = 0; i < run.size(); i++, rows++) {
                    boolean first = i == 0;
                    boolean last = i == run.size() - 1;
                    View row = render(run.get(i));
                    String name = section.getTitle() + " > " + ((Preference) adapter().getItem(run.get(i))).getTitle();
                    Rect bounds = cardBounds(row);
                    assertEquals(name + " isn't on the 18 dp line", dp(18), bounds.left);
                    assertEquals(name, row.getWidth() - dp(18), bounds.right);
                    assertEquals(name + "'s top", first ? dp(4) : -dp(1), bounds.top);
                    assertEquals(name + "'s bottom", row.getHeight() - (last ? dp(4) : 0), bounds.bottom);
                    if (radii) {
                        float[] corners = card(row).getCornerRadii();
                        for (int c = 0; c < 4; c++) assertEquals(name + "'s top corners", first ? dp(10) : 0, corners[c], 0f);
                        for (int c = 4; c < 8; c++) assertEquals(name + "'s bottom corners", last ? dp(10) : 0, corners[c], 0f);
                    }
                    assertEquals(name, dp(30), row.getPaddingStart());
                    assertEquals(name, dp(30), row.getPaddingEnd());
                    assertEquals(name, dp(first ? 14 : 10), row.getPaddingTop());
                    assertEquals(name, dp(last ? 14 : 10), row.getPaddingBottom());
                    assertEquals(name, dp(64), row.getMinimumHeight());
                    TextView title = row.findViewById(android.R.id.title);
                    if (title != null) assertEquals(name, sp(16), title.getTextSize(), 0.01f);
                    TextView summary = row.findViewById(android.R.id.summary);
                    if (summary != null) {
                        assertEquals(name, sp(13), summary.getTextSize(), 0.01f);
                        assertEquals(name, 1f, summary.getLineSpacingMultiplier(), 0f);
                    }
                }
            }
            tap(section);
            assertFalse(section.isExpanded());
        }
        assertTrue("too few rows to mean anything: " + rows, rows > 100);
        assertTrue("no heading split a card", headings > 0);
    }

    @Test public void aHeadingIsSmallCapitalsInTheHeadingColourOnTheCardsLine() {
        openHome();
        SettingsMenuPreference playback = (SettingsMenuPreference) keyed("section_playback");
        tap(playback);
        // A heading with a row of the section's card on either side of it.
        int at = -1;
        List<List<Integer>> runs = runs(playback);
        for (int i = 1; i < runs.size() && at < 0; i++) {
            int before = runs.get(i - 1).get(runs.get(i - 1).size() - 1);
            int after = runs.get(i).get(0);
            if (after - before == 2 && adapter().getItem(before + 1) instanceof SectionHeadingPreference) at = before + 1;
        }
        assertTrue("Playback has a heading between two of its rows", at > 0);
        View row = render(at);
        TextView title = row.findViewById(android.R.id.title);
        assertEquals(ScreenColors.DARK.heading, title.getCurrentTextColor());
        assertEquals(sp(12), title.getTextSize(), 0.01f);
        assertEquals(0.055f, title.getLetterSpacing(), 0.0001f);
        assertTrue(title.isAllCaps());
        assertTrue(title.isAccessibilityHeading());
        assertEquals(dp(22), row.getPaddingStart());
        assertEquals(dp(20), row.getPaddingTop());
        assertEquals(dp(22), row.getPaddingEnd());
        assertEquals(dp(8), row.getPaddingBottom());
        assertNull("a heading is no card", row.getBackground());

        float[] above = card(render(at - 1)).getCornerRadii();
        assertTrue("the card above the heading closes", above[4] > 0 && above[6] > 0);
        float[] below = card(render(at + 1)).getCornerRadii();
        assertTrue("the card under the heading opens", below[0] > 0 && below[2] > 0);
    }

    @Test public void onlyARowThatOpensSomethingEndsInAChevron() {
        openHome();
        SettingsMenuPreference playback = (SettingsMenuPreference) keyed("section_playback");
        View closed = render(playback);
        assertEquals("a closed section points down", 1, chevronDrawable(closed).direction);
        ImageView slot = chevron(closed);
        assertEquals(dp(16), slot.getLayoutParams().width);
        assertEquals(dp(24), slot.getLayoutParams().height);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, slot.getImportantForAccessibility());
        assertEquals(ScreenColors.DARK.summary, chevronDrawable(closed).color);
        tap(playback);
        assertEquals("an open one points up", -1, chevronDrawable(render(playback)).direction);

        assertEquals("a link points on", 0,
                chevronDrawable(render(keyed(TikTokPreferenceFragment.REPORT_PROBLEM_KEY))).direction);

        SwitchPreference toggle = null;
        ListPreference picker = null;
        for (SettingsMenuPreference section : sections()) {
            if (!section.isExpanded()) tap(section);
            Set<Object> inside = new HashSet<>();
            addDescendants(section, inside);
            for (Object item : inside) {
                if (toggle == null && item instanceof SwitchPreference && ((Preference) item).isEnabled()) {
                    toggle = (SwitchPreference) item;
                }
                if (picker == null && item instanceof ListPreference && ((Preference) item).isSelectable()) {
                    picker = (ListPreference) item;
                }
            }
            if (toggle != null && picker != null) break;
        }
        assertNotNull(toggle);
        assertNotNull(picker);
        assertNull("a switch says what it does with its switch", chevron(render(toggle)));
        assertEquals("a picker opens its choices", 0, chevronDrawable(render(picker)).direction);
    }

    @Test public void theChevronIsMirroredForARightToLeftReader() {
        ScreenColors.Chevron chevron = new ScreenColors.Chevron(Color.WHITE, 2, 0);
        assertTrue(chevron.isAutoMirrored());
        chevron.setBounds(0, 0, 16, 24);
        float[] ltr = chevron.points();
        assertTrue("it points right", ltr[2] > ltr[0]);
        chevron.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        float[] rtl = chevron.points();
        assertTrue("it points left", rtl[2] < rtl[0]);
        assertEquals(ltr[0] - 8, 8 - rtl[0], 0.001f);

        ScreenColors.Chevron down = new ScreenColors.Chevron(Color.WHITE, 2, 1);
        down.setBounds(0, 0, 16, 24);
        float[] points = down.points();
        assertTrue("a closed section's points down", points[3] > points[1]);
        ScreenColors.Chevron up = new ScreenColors.Chevron(Color.WHITE, 2, -1);
        up.setBounds(0, 0, 16, 24);
        points = up.points();
        assertTrue("an open section's points up", points[3] < points[1]);
    }

    @Test public void everyRowLeadsWithADecorativeIconInTheHeadingColour() {
        openHome();
        int rows = 0;
        SwitchPreference toggle = null;
        for (SettingsMenuPreference section : sections()) {
            assertNotNull(section.getTitle() + " has no icon", section.getIcon());
            tap(section);
            for (List<Integer> run : runs(section)) {
                for (int position : run) {
                    Preference row = (Preference) adapter().getItem(position);
                    assertNotNull(section.getTitle() + " > " + row.getTitle() + " has no icon", row.getIcon());
                    if (toggle == null && row instanceof SwitchPreference) toggle = (SwitchPreference) row;
                    rows++;
                }
            }
        }
        assertTrue("too few rows to mean anything: " + rows, rows > 100);

        assertNotNull(toggle);
        View row = render(toggle);
        ImageView icon = row.findViewById(android.R.id.icon);
        assertNotNull(icon);
        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals("the icon is decoration", View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertNull(icon.getContentDescription());
        assertIconSlot(icon);

        View menu = render(keyed("section_playback"));
        assertIconSlot(menu.findViewById(android.R.id.icon));
    }

    private void assertIconSlot(ImageView icon) {
        assertEquals(dp(24), icon.getLayoutParams().width);
        assertEquals(dp(24), icon.getLayoutParams().height);
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, icon.getScaleType());
        assertNull("the line icon has no tile", icon.getBackground());
        ColorStateList tint = icon.getImageTintList();
        assertEquals(ScreenColors.DARK.heading, tint.getDefaultColor());
        assertEquals("a disabled row's icon dims", 0x61, Color.alpha(disabled(tint)));
        View frame = (View) icon.getParent();
        assertEquals(dp(14), frame.getPaddingEnd());
        assertEquals(dp(38), frame.getLayoutParams().width);
        if (frame.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            assertEquals("the icon starts on the text's line", 0,
                    ((ViewGroup.MarginLayoutParams) frame.getLayoutParams()).getMarginStart());
        }
    }

    @Test public void aDisabledRowDimsItsTitleAndKeepsItsSummaryReadable() {
        openHome();
        View row = render(keyed("section_playback"));
        TextView title = row.findViewById(android.R.id.title);
        assertEquals(ScreenColors.DARK.title, title.getTextColors().getDefaultColor());
        assertEquals(0x61, Color.alpha(disabled(title.getTextColors())));
        TextView summary = row.findViewById(android.R.id.summary);
        assertEquals(ScreenColors.DARK.summary, summary.getTextColors().getDefaultColor());
        assertEquals(0xC0, Color.alpha(disabled(summary.getTextColors())));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aPressRipplesInTheHeadingColourAndFocusOutlinesTheCard() {
        openHome();
        SettingsMenuPreference playback = (SettingsMenuPreference) keyed("section_playback");
        tap(playback);
        int at = -1;
        for (List<Integer> run : runs(playback)) {
            for (int position : run) {
                if (at < 0 && ((Preference) adapter().getItem(position)).isSelectable()) at = position;
            }
        }
        assertTrue(at > 0);
        View row = render(at);
        assertTrue("a row that takes a tap shows the press", row.getBackground() instanceof RippleDrawable);
        assertEquals((ScreenColors.DARK.heading & 0x00FFFFFF) | 0x33000000,
                ScreenColors.interactionRipple(ScreenColors.DARK.heading).getDefaultColor());

        GradientDrawable surface = card(row);
        assertNear("resting", ScreenColors.DARK.outline, edge(surface, android.R.attr.state_enabled));
        assertNear("focused", ScreenColors.DARK.heading,
                edge(surface, android.R.attr.state_enabled, android.R.attr.state_focused));
        assertNear("selected", ScreenColors.DARK.heading,
                edge(surface, android.R.attr.state_enabled, android.R.attr.state_selected));
    }

    private static void assertNear(String message, int expected, int actual) {
        boolean near = Math.abs(Color.red(expected) - Color.red(actual)) <= 3
                && Math.abs(Color.green(expected) - Color.green(actual)) <= 3
                && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 3;
        assertTrue(message + ": expected " + Integer.toHexString(expected) + " but was "
                + Integer.toHexString(actual), near);
    }

    /** The colour of [surface]'s top edge in [state], drawn on black. */
    private static int edge(GradientDrawable surface, int... state) {
        Bitmap bitmap = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        surface.setBounds(0, 0, 100, 40);
        surface.setState(state);
        surface.draw(canvas);
        int pixel = bitmap.getPixel(50, 0);
        bitmap.recycle();
        return pixel;
    }

    @Test public void theSearchResultsSitOnTheSectionCard() throws Exception {
        openHome();
        Preference search = keyed("action_search_settings");
        assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
        layoutPage();
        Field input = TikTokPreferenceFragment.class.getDeclaredField("searchInput");
        input.setAccessible(true);
        ((SettingsSearchInputPreference) input.get(page)).setQuery("download");
        var update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(page, "download");
        layoutPage();

        Field found = TikTokPreferenceFragment.class.getDeclaredField("searchRows");
        found.setAccessible(true);
        List<?> results = (List<?>) found.get(page);
        Set<Object> inSearch = Collections.newSetFromMap(new IdentityHashMap<>());
        inSearch.addAll(results);
        int drawn = 0;
        ListAdapter adapter = adapter();
        for (int position = 0; position < adapter.getCount(); position++) {
            Object item = adapter.getItem(position);
            if (!inSearch.contains(item) || splitsCard(item) || item instanceof SettingsSearchInputPreference) continue;
            View row = render(position);
            assertEquals(((Preference) item).getTitle() + " isn't on the 18 dp line", dp(18), cardBounds(row).left);
            assertNotNull(((Preference) item).getTitle() + " has no icon", ((Preference) item).getIcon());
            drawn++;
        }
        assertTrue("the search found too little to mean anything: " + drawn, drawn > 3);
    }

    @Test public void clearingTheSearchHistoryActsInPlaceWithNoChevron() throws Exception {
        app.morphe.extension.tiktok.settings.SettingsSearchHistory.clear();
        try {
            app.morphe.extension.tiktok.settings.SettingsSearchHistory.add("speed");
            openHome();
            Preference search = keyed("action_search_settings");
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            layoutPage();
            var update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
            update.setAccessible(true);
            update.invoke(page, "");
            layoutPage();

            Preference clear = null;
            ListAdapter adapter = adapter();
            for (int position = 0; position < adapter.getCount(); position++) {
                Object item = adapter.getItem(position);
                if (item instanceof Preference
                        && "Clear search history".contentEquals(String.valueOf(((Preference) item).getTitle()))) {
                    clear = (Preference) item;
                }
            }
            assertNotNull("no row to clear the history", clear);
            assertNull("the row clears where it stands and opens nothing", chevron(render(clear)));
        } finally {
            app.morphe.extension.tiktok.settings.SettingsSearchHistory.clear();
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-notnight-mdpi")
    public void theLightLookPaintsTheSameShapesInItsOwnColours() {
        openHome();
        View row = render(keyed("section_playback"));
        assertEquals(ScreenColors.LIGHT.card, card(row).getColor().getDefaultColor());
        assertEquals(ScreenColors.LIGHT.heading,
                ((ImageView) row.findViewById(android.R.id.icon)).getImageTintList().getDefaultColor());
        assertEquals(ScreenColors.LIGHT.summary, chevronDrawable(row).color);
        assertEquals(ScreenColors.LIGHT.title,
                ((TextView) row.findViewById(android.R.id.title)).getTextColors().getDefaultColor());
    }

    @Test public void aRowsIconFollowsTheWordsOfItsKey() {
        String fallback = SettingsIcons.PLAYBACK;
        assertSame(SettingsIcons.DOWNLOADS, SettingsIcons.forKey("download_subtitles", fallback));
        assertSame("an earlier word still wins", SettingsIcons.LIKE, SettingsIcons.forKey("confirm_comment_like", fallback));
        assertSame(SettingsIcons.PLACE, SettingsIcons.forKey("region_only_from", fallback));
        assertSame(SettingsIcons.BLOCK, SettingsIcons.forKey("remove_ads", fallback));
        assertSame(SettingsIcons.REELS, SettingsIcons.forKey("hide_live_replays", fallback));
        assertNotSame("whole words only: blocked isn't locked", SettingsIcons.LOCK,
                SettingsIcons.forKey("blocked_words_in_stickers", fallback));
        assertSame("Screen time keeps its clock", SettingsIcons.UPDATES,
                SettingsIcons.forKey("screen_time_budget", fallback));
        assertSame(fallback, SettingsIcons.forKey("start_today_over", fallback));
        assertSame(fallback, SettingsIcons.forKey(null, fallback));
    }

    @Test public void everyCategoryWearsItsOwnIcon() {
        Set<String> seen = new HashSet<>();
        for (SettingsMenuPreference.Icon icon : SettingsMenuPreference.Icon.values()) {
            String picked = SettingsIcons.forMenu(icon);
            assertNotNull(picked);
            assertTrue(icon + " shares its picture", seen.add(picked));
        }
        assertSame(SettingsIcons.SETTINGS, SettingsIcons.forMenu(null));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aCategoryIconTakesTheTintItsRowGivesIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (SettingsMenuPreference.Icon kind : SettingsMenuPreference.Icon.values()) {
            Drawable icon = SettingsMenuPreference.lineIconDrawable(activity, kind);
            assertEquals(kind + " is green before any tint", 0, green(icon, null));
            icon.setTintList(ColorStateList.valueOf(Color.GREEN));
            assertTrue(kind + " ignored the tint", green(icon, new int[]{android.R.attr.state_enabled}) > 0);
            icon.setTintList(ScreenColors.dimmedWhenDisabled(Color.GREEN));
            assertEquals(kind + " didn't dim when disabled", 0, green(icon, new int[0]));
        }
    }

    /** How many of [icon]'s pixels come out solid green, drawn in [state]. */
    private static int green(Drawable icon, int[] state) {
        if (state != null) icon.setState(state);
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 48, 48);
        icon.draw(new Canvas(bitmap));
        int count = 0;
        int drawn = 0;
        for (int x = 0; x < 48; x++) {
            for (int y = 0; y < 48; y++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) >= 200 && Color.green(pixel) >= 200
                        && Color.red(pixel) <= 40 && Color.blue(pixel) <= 40) count++;
                if (Color.alpha(pixel) > 0) drawn++;
            }
        }
        bitmap.recycle();
        assertTrue("the icon drew nothing at all", drawn > 0);
        return count;
    }

    @Test @Config(fontScale = 2.0f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void textWrapsAndTheCardsGrowAtTwiceTheSize() {
        openHome();
        SettingsMenuPreference playback = (SettingsMenuPreference) keyed("section_playback");
        assertWhole(render(playback));
        tap(playback);
        int rows = 0;
        for (List<Integer> run : runs(playback)) {
            for (int position : run) {
                assertWhole(render(position));
                rows++;
            }
        }
        assertTrue(rows > 3);
    }

    /** Android 9 hyphenates a TextView by default, so a row says it doesn't, as Android 10 does. */
    @Test @Config(sdk = 28)
    public void aRowsTextIsNeverHyphenated() {
        openHome();
        SettingsMenuPreference playback = (SettingsMenuPreference) keyed("section_playback");
        tap(playback);
        int texts = 0;
        for (List<Integer> run : runs(playback)) {
            for (int position : run) {
                View row = render(position);
                for (int which : new int[]{android.R.id.title, android.R.id.summary}) {
                    TextView text = row.findViewById(which);
                    if (text == null) continue;
                    assertEquals(text.getText() + " hyphenates",
                            Layout.HYPHENATION_FREQUENCY_NONE, text.getHyphenationFrequency());
                    texts++;
                }
            }
        }
        assertTrue(texts > 3);
    }

    /** Every line of [row]'s text is drawn in full, inside the row. */
    private static void assertWhole(View row) {
        int texts = 0;
        for (int which : new int[]{android.R.id.title, android.R.id.summary}) {
            TextView text = row.findViewById(which);
            if (text == null || text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
            texts++;
            Layout layout = text.getLayout();
            assertNotNull(text.getText() + " was never laid out", layout);
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(text.getText() + " line " + line + " is cut short", 0, layout.getEllipsisCount(line));
            }
            assertTrue(text.getText() + " is taller than its view",
                    layout.getHeight() <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1);
            int bottom = text.getHeight();
            for (View at = text; at != row; at = (View) at.getParent()) bottom += at.getTop();
            assertTrue(text.getText() + " runs past the bottom of its row", bottom <= row.getHeight());
        }
        assertTrue("the row drew no text", texts > 0);
    }
}
