/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsPagesTest;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.reflect.Field;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * No backdrop on any settings page is a pill, an oval or rounded off the 0, 4, 6, 8, 10 and 12 dp
 * scale: the main page, every category opened in turn, the search box and a recent search.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsRadiusScaleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();

    @Before public void installEveryPatch() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void restore() throws Exception {
        for (var entry : statuses.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
        Utils.setIsDarkModeEnabled(true);
    }

    @Test public void everyPageInBothLooksStaysOnTheRadiusScale() {
        for (boolean dark : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(dark);
            String look = dark ? "dark" : "light";
            try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setContext(activity);
                TikTokPreferenceFragment home = new TikTokPreferenceFragment();
                activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit();
                activity.getFragmentManager().executePendingTransactions();
                idle();
                RadiusScale.assertOnScale(home.getView(), look + " main page");
                walkRows(home, look + " main page");

                List<SettingsMenuPreference> categories = new ArrayList<>();
                ListAdapter adapter = list(home).getAdapter();
                for (int i = 0; i < adapter.getCount(); i++) {
                    Object row = adapter.getItem(i);
                    if (row instanceof SettingsMenuPreference && ((SettingsMenuPreference) row).isExpandable()) {
                        categories.add((SettingsMenuPreference) row);
                    }
                }
                assertTrue("no category opens on the main page", categories.size() > 5);
                for (SettingsMenuPreference category : categories) {
                    String where = look + " " + category.getKey();
                    Preference.OnPreferenceClickListener open = category.getOnPreferenceClickListener();
                    assertTrue(open.onPreferenceClick(category));
                    idle();
                    assertTrue(where + " didn't open", category.isExpanded());
                    walkRows(home, where);
                    assertTrue(open.onPreferenceClick(category));
                    idle();
                }

                SettingsSearchInputPreference search = new SettingsSearchInputPreference(activity, query -> {});
                search.setQuery("cats");
                ListView parent = list(home);
                View box = search.getView(null, parent);
                search.showResultCount(3);
                RadiusScale.assertOnScale(box, look + " search box");
                RadiusScale.assertOnScale(new RecentSearchPreference(activity, "cats", query -> {}, query -> {})
                        .getView(null, parent), look + " recent search");
                RadiusScale.assertOnScale(SettingsHeaderPreference.createHeader(activity, "Hushfeed", () -> {}),
                        look + " page header");
            }
        }
    }

    /** Builds every row the list holds, on screen or not, and walks each one. */
    private static void walkRows(TikTokPreferenceFragment page, String where) {
        ListView list = list(page);
        ListAdapter adapter = list.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            View row = adapter.getView(i, null, list);
            RadiusScale.assertOnScale(row, where + " row " + i);
        }
    }

    private static ListView list(TikTokPreferenceFragment page) {
        return page.getView().findViewById(android.R.id.list);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
