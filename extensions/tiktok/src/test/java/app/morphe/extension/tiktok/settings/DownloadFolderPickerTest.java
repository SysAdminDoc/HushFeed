package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * The folder picker is a separate activity, so the settings screen behind it is routinely
 * destroyed and rebuilt. The screen used to hold the waiting preference itself and drop it on
 * the way out, and the returning screen then took the chosen folder and did nothing with it,
 * silently.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class DownloadFolderPickerTest {
    private static final int REQUEST_DOWNLOAD_PATH_FOLDER = 8841;
    private final java.util.Map<java.lang.reflect.Field, Boolean> statuses = new java.util.LinkedHashMap<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The folder rows are only built when the download patches are in the bundle.
        for (java.lang.reflect.Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
        ReflectionHelpers.setStaticField(TikTokPreferenceFragment.class, "pendingDownloadPathKey", null);
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        for (java.util.Map.Entry<java.lang.reflect.Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        statuses.clear();
    }

    @Test public void aFolderChosenAcrossARebuiltScreenStillLands() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment first = attach(activity, "DOWNLOADS");
        Preference path = firstDownloadPath(first);
        assertNotNull("no download path preference on the downloads page", path);
        String before = path.getSharedPreferences().getString(path.getKey(), null);

        // Opening the picker records which setting is waiting.
        TikTokPreferenceFragment.openDownloadPathFolderPicker(
                (app.morphe.extension.tiktok.settings.preference.DownloadPathPreference) path);
        assertEquals(path.getKey(),
                ReflectionHelpers.getStaticField(TikTokPreferenceFragment.class, "pendingDownloadPathKey"));

        // The picker's activity takes the foreground and this one is rebuilt behind it.
        Bundle state = new Bundle();
        first.onSaveInstanceState(state);
        activity.getFragmentManager().beginTransaction().remove(first).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        TikTokPreferenceFragment second = attach(activity, "DOWNLOADS");

        second.onActivityResult(REQUEST_DOWNLOAD_PATH_FOLDER, Activity.RESULT_OK, treeIntent("primary:Movies/Chosen"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Preference after = firstDownloadPath(second);
        assertNotNull(after);
        String stored = after.getSharedPreferences().getString(after.getKey(), null);
        assertNotEquals("the chosen folder was dropped", before, stored);
        assertTrue(String.valueOf(stored), String.valueOf(stored).contains("Chosen"));
    }

    /**
     * The rebuild above keeps the key because it is static, which covers Android destroying the
     * screen while the process lives. Killing the process takes the static with it, and only the
     * saved bundle carries the key across that, so this clears the static first.
     */
    @Test public void aFolderChosenAcrossAKilledProcessStillLands() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment first = attach(activity, "DOWNLOADS");
        Preference path = firstDownloadPath(first);
        assertNotNull(path);
        String before = path.getSharedPreferences().getString(path.getKey(), null);

        TikTokPreferenceFragment.openDownloadPathFolderPicker(
                (app.morphe.extension.tiktok.settings.preference.DownloadPathPreference) path);
        Bundle state = new Bundle();
        first.onSaveInstanceState(state);
        assertEquals(path.getKey(), state.getString("morphe_pending_download_path"));

        // Nothing of the old process is left but what Android wrote down.
        ReflectionHelpers.setStaticField(TikTokPreferenceFragment.class, "pendingDownloadPathKey", null);
        activity.getFragmentManager().beginTransaction().remove(first).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        TikTokPreferenceFragment second = attach(activity, "DOWNLOADS");
        second.onActivityCreated(state);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        second.onActivityResult(REQUEST_DOWNLOAD_PATH_FOLDER, Activity.RESULT_OK,
                treeIntent("primary:Movies/Restored"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Preference after = firstDownloadPath(second);
        assertNotNull(after);
        String stored = after.getSharedPreferences().getString(after.getKey(), null);
        assertNotEquals("the folder was lost with the process", before, stored);
        assertTrue(String.valueOf(stored), String.valueOf(stored).contains("Restored"));
    }

    /**
     * A refused folder used to be a toast, and the reason lists the allowed folders, which ran
     * past the two lines a toast shows. The screen behind the picker is usually rebuilt, so the
     * folder box opens again holding the pick with the reason under it.
     */
    @Test public void aRefusedFolderReopensTheBoxWithTheReason() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment page = attach(activity, "DOWNLOADS");
        Preference path = firstDownloadPath(page);
        assertNotNull(path);
        String before = path.getSharedPreferences().getString(path.getKey(), null);

        TikTokPreferenceFragment.openDownloadPathFolderPicker(
                (app.morphe.extension.tiktok.settings.preference.DownloadPathPreference) path);
        page.onActivityResult(REQUEST_DOWNLOAD_PATH_FOLDER, Activity.RESULT_OK, treeIntent("primary:Music/Chosen"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull("the refusal was toasted", ShadowToast.getTextOfLatestToast());
        android.app.Dialog dialog = ((android.preference.DialogPreference) path).getDialog();
        assertNotNull("the folder box didn't open again", dialog);
        assertTrue(dialog.isShowing());
        android.view.View root = dialog.getWindow().getDecorView();
        android.widget.EditText field = firstEditText(root);
        assertNotNull(field);
        assertEquals("Music/Chosen", field.getText().toString());
        android.widget.TextView error = root.findViewWithTag("hushfeed_field_error");
        assertNotNull("no reason under the box", error);
        assertEquals(android.view.View.VISIBLE, error.getVisibility());
        String reason = null;
        try {
            app.morphe.extension.tiktok.download.DownloadDestination.validate("Music/Chosen",
                    app.morphe.extension.tiktok.download.DownloadDestination.Kind.VIDEO);
        } catch (IllegalArgumentException refused) {
            reason = refused.getMessage();
        }
        assertNotNull("Music is no longer refused, so this test proves nothing", reason);
        assertEquals(reason, error.getText().toString());
        assertEquals(before, path.getSharedPreferences().getString(path.getKey(), null));
    }

    private static android.widget.EditText firstEditText(android.view.View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                android.widget.EditText found = firstEditText(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void aResultWithNothingWaitingSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment page = attach(activity, "DOWNLOADS");

        page.onActivityResult(REQUEST_DOWNLOAD_PATH_FOLDER, Activity.RESULT_OK, treeIntent("primary:Movies/Chosen"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull("a folder came back with nothing waiting and nothing was said",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void aCancelledPickerChangesNothingAndSaysNothing() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment page = attach(activity, "DOWNLOADS");
        Preference path = firstDownloadPath(page);
        assertNotNull(path);
        String before = path.getSharedPreferences().getString(path.getKey(), null);

        TikTokPreferenceFragment.openDownloadPathFolderPicker(
                (app.morphe.extension.tiktok.settings.preference.DownloadPathPreference) path);
        page.onActivityResult(REQUEST_DOWNLOAD_PATH_FOLDER, Activity.RESULT_CANCELED, null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(before, path.getSharedPreferences().getString(path.getKey(), null));
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    private static Intent treeIntent(String treeDocumentId) {
        Uri tree = Uri.parse("content://com.android.externalstorage.documents/tree/"
                + Uri.encode(treeDocumentId));
        return new Intent().setData(tree);
    }

    private static Preference firstDownloadPath(TikTokPreferenceFragment fragment) {
        android.preference.PreferenceScreen screen = fragment.getPreferenceScreen();
        return find(screen);
    }

    private static Preference find(Preference preference) {
        if (preference instanceof app.morphe.extension.tiktok.settings.preference.DownloadPathPreference) {
            return preference;
        }
        if (preference instanceof android.preference.PreferenceGroup) {
            android.preference.PreferenceGroup group = (android.preference.PreferenceGroup) preference;
            for (int index = 0; index < group.getPreferenceCount(); index++) {
                Preference found = find(group.getPreference(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TikTokPreferenceFragment attach(Activity activity, String section) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", section);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }
}
