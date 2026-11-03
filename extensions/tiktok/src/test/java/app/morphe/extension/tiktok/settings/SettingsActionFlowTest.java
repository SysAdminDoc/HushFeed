package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.preference.HookStatusPreference;
import app.morphe.extension.tiktok.settings.preference.MorpheTikTokAboutPreference;
import app.morphe.extension.tiktok.settings.preference.RecentSearchPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsSearchEntryPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsSearchInputPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import java.lang.reflect.Field;
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
import org.robolectric.shadows.ShadowAlertDialog;

/** Exercises the controls installed by the real settings page, including their click wiring. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsActionFlowTest {
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private ActivityController<Activity> controller;

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        // The history is a Setting, whose value outlives the test that searched.
        SettingsSearchHistory.clear();
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        HookStatus.clear();
        SettingsSearchHistory.clear();
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void typingAndClearingSearchUpdatesTheInstalledResults() {
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        TikTokPreferenceFragment fragment = attach(arguments);
        SettingsSearchInputPreference preference = row(fragment, SettingsSearchInputPreference.class);
        EditText input = fragment.getView().findViewWithTag("settings_search_input");
        View clear = fragment.getView().findViewWithTag("settings_search_clear");
        assertNotNull("the search input was not attached", input);
        assertNotNull(clear);
        assertEquals(View.GONE, clear.getVisibility());
        List<String> empty = titles(fragment);
        assertTrue(empty.contains("Start typing"));

        input.setText("comment");
        idle();
        assertEquals("comment", preference.getQuery());
        assertEquals(View.VISIBLE, clear.getVisibility());
        assertEquals("Clear search", clear.getContentDescription());
        assertTrue(titles(fragment).stream().anyMatch(title -> title.toLowerCase().contains("sort")));
        assertFalse(titles(fragment).contains("Start typing"));

        input.setText("zzq_no_such_setting_zzq");
        idle();
        assertTrue(titles(fragment).contains("No matching settings"));
        Preference none = null;
        for (int i = 0; i < fragment.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference row = fragment.getPreferenceScreen().getPreference(i);
            if ("No matching settings".equals(String.valueOf(row.getTitle()))) none = row;
        }
        assertNotNull(none);
        assertTrue("an empty search does not say where a missing switch went",
                String.valueOf(none.getSummary()).contains("Morphe Manager"));
        assertTrue(clear.performClick());
        idle();
        assertEquals("", input.getText().toString());
        assertEquals("", preference.getQuery());
        assertEquals(View.GONE, clear.getVisibility());
        assertEquals(empty, titles(fragment));
    }

    @Test public void locatingAResultClosesInlineSearchAndItsRecentQueryRunsAgain() {
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        TikTokPreferenceFragment fragment = attach(arguments);
        SettingsSearchInputPreference originalPreference = row(fragment, SettingsSearchInputPreference.class);
        EditText input = fragment.getView().findViewWithTag("settings_search_input");
        assertNotNull("the search input was not attached", input);

        input.setText("speed");
        idle();
        assertTrue(titles(fragment).contains("Default playback speed"));

        clickTitle(fragment, "Default playback speed");
        fragment.getFragmentManager().executePendingTransactions();
        settle(fragment);
        assertSame(fragment, fragment.getFragmentManager().findFragmentById(android.R.id.content));
        assertEquals(0, fragment.getFragmentManager().getBackStackEntryCount());
        assertTrue(((SettingsMenuPreference) fragment.findPreference("section_playback")).isExpanded());
        Preference entry = fragment.findPreference("action_search_settings");
        assertTrue("locating the result left inline search open", entry instanceof SettingsSearchEntryPreference);

        assertEquals("opening a result keeps its search", List.of("speed"), SettingsSearchHistory.recent());

        assertTrue(entry.getOnPreferenceClickListener().onPreferenceClick(entry));
        settle(fragment);
        SettingsSearchInputPreference reopenedPreference = row(fragment, SettingsSearchInputPreference.class);
        assertNotSame(originalPreference, reopenedPreference);
        EditText restored = fragment.getView().findViewWithTag("settings_search_input");
        assertNotNull("the search input was not rebuilt", restored);
        assertTrue(restored.isShown());
        assertTrue("the reopened search field did not receive focus", restored.hasFocus());
        assertEquals("", restored.getText().toString());
        assertEquals("", reopenedPreference.getQuery());
        assertEquals(List.of("speed"), recents(fragment));
        assertTrue(recentRow(fragment, "speed").performClick());
        settle(fragment);
        assertSame(restored, fragment.getView().findViewWithTag("settings_search_input"));
        assertEquals("speed", restored.getText().toString());
        assertEquals("speed", reopenedPreference.getQuery());
        assertEquals(5, restored.getSelectionStart());
        assertEquals(5, restored.getSelectionEnd());
        assertTrue(titles(fragment).contains("Default playback speed"));

        restored.setText("comment");
        restored.setSelection(3);
        settle(fragment);
        assertSame(restored, fragment.getView().findViewWithTag("settings_search_input"));
        assertTrue(restored.hasFocus());
        assertEquals("comment", restored.getText().toString());
        assertEquals("comment", reopenedPreference.getQuery());
        assertEquals("the reopened field still belongs to the discarded preference",
                "speed", originalPreference.getQuery());
        assertEquals(3, restored.getSelectionStart());
        assertEquals(3, restored.getSelectionEnd());
        assertTrue(titles(fragment).stream().anyMatch(title -> title.toLowerCase().contains("sort")));
        assertSame(fragment, fragment.getFragmentManager().findFragmentById(android.R.id.content));
        assertEquals(0, fragment.getFragmentManager().getBackStackEntryCount());
    }

    @Test public void searchOpenedFromAnExpandedLowerCategoryKeepsItsVisibleInputAcrossResultUpdates() {
        TikTokPreferenceFragment fragment = attach(new Bundle());
        SettingsMenuPreference backup = (SettingsMenuPreference) fragment.findPreference("section_backup");
        assertNotNull(backup);
        assertTrue(backup.getOnPreferenceClickListener().onPreferenceClick(backup));
        settle(fragment);
        assertTrue(backup.isExpanded());

        ListView list = fragment.getView().findViewById(android.R.id.list);
        int backupPosition = -1;
        for (int position = 0; position < list.getCount(); position++) {
            if (list.getItemAtPosition(position) == backup) backupPosition = position;
        }
        assertTrue(backupPosition > 0);
        list.setSelection(backupPosition);
        settle(fragment);
        assertTrue("the lower category was never scrolled into view", list.getFirstVisiblePosition() > 0);

        Preference entry = fragment.findPreference("action_search_settings");
        assertTrue(entry instanceof SettingsSearchEntryPreference);
        assertTrue(entry.getOnPreferenceClickListener().onPreferenceClick(entry));
        settle(fragment);
        EditText input = fragment.getView().findViewWithTag("settings_search_input");
        assertNotNull("opening search from the lower category did not reveal its field; visible "
                + list.getFirstVisiblePosition() + ".." + list.getLastVisiblePosition() + " of " + list.getCount(), input);
        assertTrue(input.isShown());
        assertTrue("opening search did not focus the visible field; focusable=" + input.isFocusable()
                + " enabled=" + input.isEnabled() + " touch=" + input.isInTouchMode()
                + " descendantMode=" + list.getDescendantFocusability(), input.hasFocus());
        assertFalse(backup.isExpanded());

        for (String query : new String[]{"speed", "comment", "zzq_no_such_setting_zzq", ""}) {
            input.setText(query);
            int cursor = query.length() / 2;
            input.setSelection(cursor);
            settle(fragment);
            EditText visible = fragment.getView().findViewWithTag("settings_search_input");
            assertSame("updating results replaced the visible search field", input, visible);
            assertTrue(visible.isShown());
            assertTrue("updating results lost search focus", visible.hasFocus());
            assertEquals("updating results moved the cursor", cursor, visible.getSelectionStart());
            assertEquals(cursor, visible.getSelectionEnd());
            assertEquals(query, visible.getText().toString());
            assertEquals(query, row(fragment, SettingsSearchInputPreference.class).getQuery());
            if ("speed".equals(query)) assertTrue(titles(fragment).contains("Default playback speed"));
            else if ("comment".equals(query)) {
                assertTrue(titles(fragment).stream().anyMatch(title -> title.toLowerCase().contains("sort")));
            } else if (query.isEmpty()) assertTrue(titles(fragment).contains("Start typing"));
            else assertTrue(titles(fragment).contains("No matching settings"));
        }

        assertSame(fragment, fragment.getFragmentManager().findFragmentById(android.R.id.content));
        assertEquals(0, fragment.getFragmentManager().getBackStackEntryCount());
        assertTrue(fragment.closeInlineSearch());
        settle(fragment);
        assertTrue("closing search lost the expanded lower category", backup.isExpanded());
    }

    /** #232: the empty box lists earlier searches, and each one runs again or goes away. */
    @Test public void recentSearchesShowUnderTheEmptyBoxAndRunAgain() {
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        TikTokPreferenceFragment fragment = attach(arguments);
        EditText input = fragment.getView().findViewWithTag("settings_search_input");
        assertTrue(titles(fragment).contains("Start typing"));

        // Typing alone keeps nothing; the keyboard's search key does.
        input.setText("spe");
        idle();
        assertEquals(List.of(), SettingsSearchHistory.recent());
        for (String query : new String[]{"speed", "comment"}) {
            input.setText(query);
            idle();
            input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        }
        input.setText("");
        idle();
        assertEquals(List.of("comment", "speed"), recents(fragment));
        assertTrue(titles(fragment).contains("Recent searches"));
        assertTrue(titles(fragment).contains("Clear search history"));
        assertFalse(titles(fragment).contains("Start typing"));

        // A tap searches it again, with the words back in the box.
        recentRow(fragment, "speed").performClick();
        idle();
        assertEquals("speed", input.getText().toString());
        assertTrue(titles(fragment).contains("Default playback speed"));

        // The bin forgets that one search and leaves the rest.
        input.setText("");
        idle();
        android.view.ViewGroup row = (android.view.ViewGroup) recentRow(fragment, "speed");
        View bin = row.getChildAt(row.getChildCount() - 1);
        assertEquals("Delete search", bin.getContentDescription());
        // A 48dp target that a screen reader calls a button, not a picture.
        int dp48 = Math.round(48 * fragment.getResources().getDisplayMetrics().density);
        assertEquals(dp48, bin.getLayoutParams().width);
        assertEquals(dp48, bin.getLayoutParams().height);
        assertEquals(android.widget.Button.class.getName(), bin.createAccessibilityNodeInfo().getClassName());
        bin.performClick();
        idle();
        assertEquals(List.of("comment"), recents(fragment));

        clickTitle(fragment, "Clear search history");
        idle();
        assertEquals(List.of(), SettingsSearchHistory.recent());
        assertTrue(titles(fragment).contains("Start typing"));
        assertFalse(titles(fragment).contains("Recent searches"));
    }

    private static List<String> recents(TikTokPreferenceFragment fragment) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < fragment.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference row = fragment.getPreferenceScreen().getPreference(i);
            if (row instanceof RecentSearchPreference) result.add(((RecentSearchPreference) row).getQuery());
        }
        return result;
    }

    /** The view the list shows for one recent search, with its own click wiring. */
    private static View recentRow(TikTokPreferenceFragment fragment, String query) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int i = 0; i < list.getCount(); i++) {
            Object item = list.getItemAtPosition(i);
            if (item instanceof RecentSearchPreference && query.equals(((RecentSearchPreference) item).getQuery())) {
                return list.getAdapter().getView(i, null, list);
            }
        }
        throw new AssertionError("no recent search row for " + query);
    }

    @Test public void hookStatusShowsTheEmptyReportFromItsInstalledRow() {
        TikTokPreferenceFragment fragment = diagnostics();
        HookStatusPreference preference = row(fragment, HookStatusPreference.class);
        assertEquals("Nothing has been looked up yet. Use the app for a moment, then come back.",
                preference.getSummary());
        clickRow(fragment, HookStatusPreference.class);
        AlertDialog report = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(report);
        assertEquals("Nothing has been checked yet, so there's nothing to report.",
                Shadows.shadowOf(report).getMessage());
        report.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(report.isShowing());
    }

    @Test public void hookStatusReflectsNewFindingsAndOpensTheirDetails() {
        TikTokPreferenceFragment fragment = diagnostics();
        HookStatusPreference preference = row(fragment, HookStatusPreference.class);
        HookStatus.bound("comments", "like_button");
        assertTrue(preference.getSummary().toString().contains(
                "One area was checked and everything it needs is here."));
        HookStatus.bound("inbox", "itemView");
        assertTrue(preference.getSummary().toString().contains(
                "2 areas were checked and everything they need is here."));
        HookStatus.missingViewId("inbox", "bo5");
        assertTrue(preference.getSummary().toString().contains(
                "Something is missing from inbox. Tap for the whole report."));

        clickRow(fragment, HookStatusPreference.class);
        AlertDialog report = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(report);
        String message = Shadows.shadowOf(report).getMessage().toString();
        assertTrue(message, message.contains("comments: 1 found, 0 missing"));
        assertTrue(message, message.contains("inbox: 1 found, 1 missing. First missing: view id 'bo5'"));
        report.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(report.isShowing());
    }

    @Test public void theAboutRowOpensTheProjectsSourceAndReleases() {
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_hub", "ABOUT");
        TikTokPreferenceFragment fragment = attach(arguments);
        clickRow(fragment, MorpheTikTokAboutPreference.class);
        Intent opened = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull("clicking the about row launched nothing", opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals("https://github.com/SysAdminDoc/hushfeed", opened.getDataString());
        assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    private TikTokPreferenceFragment diagnostics() {
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "DIAGNOSTICS");
        return attach(arguments);
    }

    private TikTokPreferenceFragment attach(Bundle arguments) {
        controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        controller.visible();
        settle(fragment);
        return fragment;
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static void settle(TikTokPreferenceFragment fragment) {
        View decor = fragment.getActivity().getWindow().getDecorView();
        // Scrolling and focus are posted across traversal and animation frames.
        for (int frame = 0; frame < 4; frame++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 1080, 1920);
        }
        idle();
    }

    private static List<String> titles(TikTokPreferenceFragment fragment) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < fragment.getPreferenceScreen().getPreferenceCount(); i++) {
            CharSequence title = fragment.getPreferenceScreen().getPreference(i).getTitle();
            if (title != null) result.add(title.toString());
        }
        return result;
    }

    private static <T extends Preference> T row(TikTokPreferenceFragment fragment, Class<T> type) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int i = 0; i < list.getCount(); i++) {
            if (type.isInstance(list.getItemAtPosition(i))) return type.cast(list.getItemAtPosition(i));
        }
        throw new AssertionError("The page did not install " + type.getSimpleName());
    }

    private static void clickRow(TikTokPreferenceFragment fragment, Class<? extends Preference> type) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int i = 0; i < list.getCount(); i++) {
            if (type.isInstance(list.getItemAtPosition(i))) {
                assertTrue(list.performItemClick(list.getAdapter().getView(i, null, list),
                        i, list.getItemIdAtPosition(i)));
                idle();
                return;
            }
        }
        throw new AssertionError("The page did not install " + type.getSimpleName());
    }

    private static void clickTitle(TikTokPreferenceFragment fragment, String title) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int i = 0; i < list.getCount(); i++) {
            Object item = list.getItemAtPosition(i);
            if (!(item instanceof Preference)) continue;
            CharSequence itemTitle = ((Preference) item).getTitle();
            if (itemTitle == null || !title.contentEquals(itemTitle)) continue;
            assertTrue(list.performItemClick(list.getAdapter().getView(i, null, list),
                    i, list.getItemIdAtPosition(i)));
            idle();
            return;
        }
        throw new AssertionError("The page did not install " + title);
    }
}
