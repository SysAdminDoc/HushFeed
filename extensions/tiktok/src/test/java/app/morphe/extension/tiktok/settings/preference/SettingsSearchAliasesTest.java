package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
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
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * Search read only titles, summaries and a few keywords, so the names people use for a feature
 * ("autoplay", "PiP", "AI") found nothing, and a choice row was findable only by the choice it
 * was set to: "save the frame" answered nothing while Double tap was set to anything else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsSearchAliasesTest {
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
        Settings.DOUBLE_TAP_ACTION.resetToDefault();
    }

    @After public void tearDown() throws Exception {
        if (owner != null) owner.pause().stop().destroy();
        Settings.DOUBLE_TAP_ACTION.resetToDefault();
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        statuses.clear();
    }

    @Test public void commonFeatureNamesFindTheirRows() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        assertFinds(search, "autoplay", "Stop search results playing on their own");
        assertFinds(search, "auto play", "Stop search results playing on their own");
        assertFinds(search, "picture in picture", "Keep watching in a small window");
        assertFinds(search, "pip", "Keep watching in a small window");
        assertFinds(search, "search suggestions", "Hide suggestions on the search page");
        assertFinds(search, "search suggestions", "Hide search suggestions above comments");
        assertFinds(search, "ai", "Hide the Tako chat");
        assertFinds(search, "ai", "Hide suggested replies");
        assertFinds(search, "save photos", "Download original photos");
    }

    /** The preset row had no key, and search skips keyless rows, so its own title found nothing. */
    @Test public void theSimPresetRowIsFoundByItsTitle() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        assertFinds(search, "SIM country preset", "SIM country preset");
        assertFinds(search, "preset", "SIM country preset");
    }

    @Test public void anUnselectedChoiceFindsItsRowOnceAndOpensTheChoices() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        String label = "Save the frame on screen as a photo";
        List<String> titles = search(search, label);
        assertEquals("Double tap should be listed once: " + titles, 1, Collections.frequency(titles, "Double tap"));

        Preference result = result(search, "Double tap");
        assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
        settle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the choices did not open", dialog);
        assertTrue(dialog.isShowing());
        assertEquals("Double tap", Shadows.shadowOf(dialog).getTitle().toString());
        assertEquals("opening the choices changed the setting", "default", Settings.DOUBLE_TAP_ACTION.get());
    }

    @Test public void theChosenValueOpensThePageWithoutTheDialog() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        // The row's summary shows the chosen label, so this match is already visible.
        Preference result = result(search, "TikTok default", "Double tap");
        assertNotNull(result);
        assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
        settle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("a match on the chosen value opened the choices", dialog == null || !dialog.isShowing());
    }

    @Test public void exactTitlesStillRankFirst() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        assertEquals("Double tap", search(search, "double tap").get(0));
        // Double tap and Long press both offer "Save the video's cover", which ranks below every
        // row with cover in its title.
        List<String> titles = search(search, "cover");
        int doubleTap = titles.indexOf("Double tap");
        assertTrue("Double tap was not found by its choices: " + titles, doubleTap > 0);
        for (int i = doubleTap + 1; i < titles.size(); i++) {
            assertTrue("a title hit came after a choice hit: " + titles,
                    !java.util.Arrays.asList(titles.get(i).toLowerCase(java.util.Locale.ROOT).split(" "))
                            .contains("cover"));
        }
    }

    @Test @Config(sdk = 28, qualifiers = "de")
    public void aTranslatedAliasFindsTheTranslatedRow() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        String title = L10n.t(owner.get(), "Keep watching in a small window").toString();
        assertTrue("Bild-im-Bild did not find " + title, search(search, "Bild-im-Bild").contains(title));
        String photos = L10n.t(owner.get(), "Download original photos").toString();
        assertTrue("Fotos speichern did not find " + photos, search(search, "Fotos speichern").contains(photos));
    }

    private static void assertFinds(TikTokPreferenceFragment search, String query, String title) throws Exception {
        List<String> titles = search(search, query);
        assertTrue("\"" + query + "\" did not find " + title + ": " + titles, titles.contains(title));
    }

    private static List<String> search(TikTokPreferenceFragment fragment, String query) throws Exception {
        Method update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(fragment, query);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Field rows = TikTokPreferenceFragment.class.getDeclaredField("searchRows");
        rows.setAccessible(true);
        List<String> titles = new ArrayList<>();
        for (Object row : (List<?>) rows.get(fragment)) {
            Preference result = (Preference) row;
            if (result.isSelectable()) titles.add(String.valueOf(result.getTitle()));
        }
        return titles;
    }

    private static Preference result(TikTokPreferenceFragment search, String title) {
        PreferenceScreen screen = search.getPreferenceScreen();
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (row.isSelectable() && row.getTitle() != null && title.contentEquals(row.getTitle())) return row;
        }
        return null;
    }

    private static Preference result(TikTokPreferenceFragment search, String query, String title) throws Exception {
        search(search, query);
        return result(search, title);
    }

    private TikTokPreferenceFragment attachSearch() {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        fragment.setArguments(arguments);
        owner.get().getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        settle();
        return fragment;
    }

    private void settle() {
        if (owner != null) owner.get().getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
