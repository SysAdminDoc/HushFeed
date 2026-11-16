package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.FragmentManager;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import app.morphe.extension.shared.Utils;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The save button's switch is on Feed screen, under Buttons on videos, and a reporter who turned
 * on Advanced downloads went looking for it in Downloads, where the patch description had sent
 * them (#299). Downloads now carries a row that says where it is and takes you there, and the
 * search answers "download button" with the real row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SaveButtonPointerTest {
    private static final String TITLE = "Show the save button on videos";
    private static final String SUMMARY = "It's in Feed screen, under Buttons on videos. Tap to go there.";

    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private ActivityController<Activity> owner;

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void tearDown() throws Exception {
        if (owner != null) owner.pause().stop().destroy();
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        statuses.clear();
    }

    @Test public void downloadsPointsToTheSwitchAndOneTapOpensFeedScreenOnIt() {
        TikTokPreferenceFragment page = attach();
        assertNull("Feed screen was open before the tap, so the tap would prove nothing",
                page.findPreference(Settings.DOWNLOAD_BUTTON.key));
        Preference downloads = page.findPreference("section_downloads");
        assertTrue(downloads.getOnPreferenceClickListener().onPreferenceClick(downloads));
        settle();

        List<Preference> pointers = rowsTitled(page.getPreferenceScreen(), TITLE);
        assertEquals("Downloads should hold one row naming the save button", 1, pointers.size());
        Preference pointer = pointers.get(0);
        assertEquals(SUMMARY, String.valueOf(pointer.getSummary()));
        assertTrue(pointer.isSelectable());

        assertTrue(pointer.getOnPreferenceClickListener().onPreferenceClick(pointer));
        settle();
        FragmentManager manager = owner.get().getFragmentManager();
        assertSame("the pointer replaced the page instead of opening in place",
                page, manager.findFragmentById(android.R.id.content));
        assertTrue(((SettingsMenuPreference) page.findPreference("section_interface")).isExpanded());
        Preference toggle = page.findPreference(Settings.DOWNLOAD_BUTTON.key);
        assertNotNull("Feed screen opened without the save button's switch", toggle);
        assertTrue(toggle instanceof TogglePreference);
        assertEquals(TITLE, String.valueOf(toggle.getTitle()));
        assertEquals("following the pointer changed a setting",
                Settings.DOWNLOAD_BUTTON.defaultValue, Settings.DOWNLOAD_BUTTON.get());
    }

    @Test public void theSearchFindsTheSwitchByItsOtherNameAndListsItOnce() throws Exception {
        TikTokPreferenceFragment page = attach(true);
        assertEquals(List.of(TITLE), results(page, "download button"));
        // The pointer has no key, so it is never a second result for the same switch.
        assertEquals(List.of(TITLE), results(page, TITLE));
    }

    private static List<Preference> rowsTitled(PreferenceGroup group, String title) {
        List<Preference> found = new ArrayList<>();
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference row = group.getPreference(index);
            if (row.getTitle() != null && title.contentEquals(row.getTitle()) && !row.hasKey()) {
                found.add(row);
            }
            if (row instanceof PreferenceGroup) found.addAll(rowsTitled((PreferenceGroup) row, title));
        }
        return found;
    }

    private static List<String> results(TikTokPreferenceFragment fragment, String query)
            throws Exception {
        Method update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(fragment, query);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Field rows = TikTokPreferenceFragment.class.getDeclaredField("searchRows");
        rows.setAccessible(true);
        List<String> titles = new ArrayList<>();
        for (Object row : (List<?>) rows.get(fragment)) {
            Preference result = (Preference) row;
            if (result.isSelectable() && TITLE.contentEquals(String.valueOf(result.getTitle()))) {
                titles.add(String.valueOf(result.getTitle()));
            }
        }
        return titles;
    }

    private void settle() {
        if (owner != null) owner.get().getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private TikTokPreferenceFragment attach() {
        return attach(false);
    }

    /** The page, opened on its search when {@code search} is true. */
    private TikTokPreferenceFragment attach(boolean search) {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        if (search) {
            android.os.Bundle arguments = new android.os.Bundle();
            arguments.putBoolean("morphe_settings_search", true);
            fragment.setArguments(arguments);
        }
        owner.get().getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
        settle();
        return fragment;
    }
}
