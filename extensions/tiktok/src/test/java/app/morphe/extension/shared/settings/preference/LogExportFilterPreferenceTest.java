package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;

import java.util.Collections;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** What the included-events picker will and won't save. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LogExportFilterPreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void clearingEveryKindLeavesNothingToApply() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(android.R.style.Theme_Material_Light);
            Utils.setContext(activity);
            BaseSettings.DEBUG_LOG_FILTERS.save("all");
            LogExportFilterPreference row = new LogExportFilterPreference(activity);

            row.getOnPreferenceClickListener().onPreferenceClick(row);
            AlertDialog picker = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            ListView kinds = picker.getListView();
            assertTrue("all events is the starting choice", picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());

            // Clearing the only box leaves nothing chosen, and nothing to apply.
            kinds.performItemClick(kinds.getAdapter().getView(0, null, kinds), 0, 0);
            assertFalse(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());

            // Choosing one kind makes it something again, and that is what is saved.
            kinds.performItemClick(kinds.getAdapter().getView(3, null, kinds), 3, 3);
            assertTrue(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            // The dialog hands the click to its listener through the main looper.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("feed", BaseSettings.DEBUG_LOG_FILTERS.get());
        } finally {
            BaseSettings.DEBUG_LOG_FILTERS.save("all");
        }
    }

    @Test
    public void aSavedKindThisRowDoesNotOfferReadsAsEveryEvent() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            BaseSettings.DEBUG_LOG_FILTERS.save("crash");
            LogExportFilterPreference row = new LogExportFilterPreference(activity);

            assertEquals("Includes all Morphe diagnostic events.", row.getSummary().toString());
            assertTrue("events still go in the report",
                    LogBufferManager.includes(DiagnosticCategory.FEED_AND_NAVIGATION));
            assertEquals(Collections.singleton("follow"), LogExportFilterPreference.parse("crash, follow"));
        } finally {
            BaseSettings.DEBUG_LOG_FILTERS.save("all");
        }
    }

    @Test
    public void theRowReadsTheChoiceAgainWhenItIsDrawn() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(android.R.style.Theme_Material_Light);
            Utils.setContext(activity);
            BaseSettings.DEBUG_LOG_FILTERS.save("all");
            LogExportFilterPreference row = new LogExportFilterPreference(activity);
            assertEquals("Includes all Morphe diagnostic events.", row.getSummary().toString());

            // A restore, a reset or an undo saves the choice without going through the row.
            BaseSettings.DEBUG_LOG_FILTERS.save("downloads,errors");
            View drawn = row.getView(null, new FrameLayout(activity));

            assertEquals("Includes these events: Downloads, Errors", row.getSummary().toString());
            TextView summary = drawn.findViewById(android.R.id.summary);
            assertEquals("Includes these events: Downloads, Errors", summary.getText().toString());
        } finally {
            BaseSettings.DEBUG_LOG_FILTERS.save("all");
        }
    }
}
