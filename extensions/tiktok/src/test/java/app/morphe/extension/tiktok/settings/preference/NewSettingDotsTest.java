package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * A setting an update added wears a dot after its title, and so does the closed section it's in,
 * for the visit that shows it (#349). A fresh install shows none.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@SuppressWarnings("deprecation")
public class NewSettingDotsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class PageActivity extends Activity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private Context context;
    private SharedPreferences store;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        store = context.getSharedPreferences(NewSettingDots.FILE, Context.MODE_PRIVATE);
        store.edit().clear().commit();
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
        store.edit().clear().commit();
    }

    private Preference row(String key) {
        Preference row = new Preference(context);
        row.setKey(key);
        return row;
    }

    private static NewSettingDots.Rows rows(List<NewSettingDots.Row> rows, int[] asked) {
        return () -> {
            asked[0]++;
            return rows;
        };
    }

    @Test public void aFirstOpenShowsNoDotsAndCountsEverySettingSeen() {
        int[] asked = {0};
        NewSettingDots dots = new NewSettingDots(store, Arrays.asList("a", "b"),
                rows(List.of(new NewSettingDots.Row("section_x", "a", "a")), asked));
        assertFalse(dots.fresh(row("a")));
        assertFalse(dots.fresh(row("section_x")));
        assertEquals("a fresh install built every section to find nothing", 0, asked[0]);
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), store.getStringSet(NewSettingDots.SEEN, null));
    }

    @Test public void aNewSettingDotsItsRowAndItsClosedSectionForOneVisit() {
        store.edit().putStringSet(NewSettingDots.SEEN, new HashSet<>(List.of("a"))).commit();
        List<NewSettingDots.Row> all = List.of(
                new NewSettingDots.Row("section_x", "a", "a"),
                new NewSettingDots.Row("section_x", "list", "b"),
                new NewSettingDots.Row(null, "c", "c"));
        int[] asked = {0};
        NewSettingDots dots = new NewSettingDots(store, Arrays.asList("a", "b", "c"), rows(all, asked));

        assertTrue("the closed section hides a new setting and shows no dot", dots.fresh(row("section_x")));
        assertFalse("an old setting wears a dot", dots.fresh(row("a")));
        assertTrue("a checklist row with a new box has no dot", dots.fresh(row("list")));
        assertTrue("a new row on the home page has no dot", dots.fresh(row("c")));
        assertTrue("the dot went out while its row was still on screen", dots.fresh(row("list")));
        assertFalse("the section kept its dot after every new row in it was shown", dots.fresh(row("section_x")));

        SettingsMenuPreference open = new SettingsMenuPreference(context, "X", "", SettingsMenuPreference.Icon.FILTER, p -> true);
        open.setKey("section_y");
        open.setExpandable(true);
        open.setExpanded(true);
        store.edit().putStringSet(NewSettingDots.SEEN, new HashSet<>(List.of("a"))).commit();
        NewSettingDots again = new NewSettingDots(store, Arrays.asList("a", "b"),
                rows(List.of(new NewSettingDots.Row("section_y", "list", "b")), asked));
        assertFalse("an open section doubles the dot its rows already show", again.fresh(open));
        open.setExpanded(false);
        assertTrue(again.fresh(open));
    }

    @Test public void aSettingNoRowShowsIsSeenAtOnce() {
        store.edit().putStringSet(NewSettingDots.SEEN, new HashSet<>(List.of("a"))).commit();
        int[] asked = {0};
        List<NewSettingDots.Row> all = List.of(new NewSettingDots.Row("section_x", "a", "a"));
        new NewSettingDots(store, Arrays.asList("a", "counter"), rows(all, asked));
        assertEquals(1, asked[0]);
        assertTrue(store.getStringSet(NewSettingDots.SEEN, null).contains("counter"));
        NewSettingDots next = new NewSettingDots(store, Arrays.asList("a", "counter"), rows(all, asked));
        assertEquals("every visit builds every section for a setting that isn't a row", 1, asked[0]);
        assertFalse(next.fresh(row("section_x")));
    }

    @Test public void theDotSitsAfterTheTitleAndLeavesItsStartMark() {
        FrameLayout rowView = new FrameLayout(context);
        TextView title = new TextView(context);
        title.setId(android.R.id.title);
        title.setText("Hide shop");
        rowView.addView(title);
        Drawable mark = new ColorDrawable(0xff00ff00);
        mark.setBounds(0, 0, 4, 4);
        title.setCompoundDrawablesRelative(mark, null, null, null);

        NewSettingDots.paint(rowView, true, 0xff2196f3);
        Drawable[] drawables = title.getCompoundDrawablesRelative();
        assertSame(mark, drawables[0]);
        assertNotNull("no dot after the title", drawables[2]);
        assertEquals("Hide shop, new", String.valueOf(title.getContentDescription()));

        NewSettingDots.paint(rowView, false, 0xff2196f3);
        drawables = title.getCompoundDrawablesRelative();
        assertSame("taking the dot off took the row's own mark too", mark, drawables[0]);
        assertNull("a recycled row kept the dot", drawables[2]);
        assertNull(title.getContentDescription());
    }

    /** The whole way through: the real home page, the section opened and closed, then left. */
    @Test public void theHomePageDotsTheSectionWithANewSettingUntilItsRowIsSeen() {
        String fresh = Settings.HIDE_SHOP.key;
        assertNotNull(BaseSettings.PAUSED);
        Set<String> seen = new HashSet<>();
        for (Setting<?> setting : Setting.allLoadedSettings()) seen.add(setting.key);
        seen.remove(fresh);
        store.edit().putStringSet(NewSettingDots.SEEN, seen).commit();

        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, page).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("Feed filter has no dot", dotted(page, "section_feed_filter"));
            assertFalse("Comments has a dot with nothing new in it", dotted(page, "section_comments"));

            Preference section = page.findPreference("section_feed_filter");
            assertTrue(section.getOnPreferenceClickListener().onPreferenceClick(section));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the new row has no dot", dotted(page, fresh));
            assertFalse("the open section still has its dot", dotted(page, "section_feed_filter"));

            assertTrue(section.getOnPreferenceClickListener().onPreferenceClick(section));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the section kept its dot after its new row was seen", dotted(page, "section_feed_filter"));

            owner.pause();
            assertTrue("leaving the settings didn't count the row seen",
                    store.getStringSet(NewSettingDots.SEEN, new HashSet<>()).contains(fresh));
        }
    }

    /** Whether the row with [key] wears the dot, after scrolling it into view. */
    private static boolean dotted(TikTokPreferenceFragment page, String key) {
        ListView list = page.getView().findViewById(android.R.id.list);
        int position = -1;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Object item = list.getAdapter().getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                position = i;
                break;
            }
        }
        assertTrue("no row " + key, position >= 0);
        list.setSelectionFromTop(position, 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View row = list.getChildAt(position - list.getFirstVisiblePosition());
        assertNotNull("row " + key + " isn't on screen", row);
        TextView title = row.findViewById(android.R.id.title);
        assertNotNull(title);
        return title.getTag(NewSettingDots.TAG) != null;
    }
}
