package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.ContextThemeWrapper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * A settings dialog still up when its screen is destroyed was a leaked window: Android logged it
 * and the dialog's listeners kept the screen alive. The screen's end closes it now.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class SettingsDialogScreenEndTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test public void aDialogClosesAndIsForgottenWhenTheScreenItOpenedOnIsDestroyed() {
        ActivityController<Activity> screen = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = screen.get();
        Utils.setContext(activity);
        AlertDialog dialog = shown(activity);
        SettingsUi.dialogOpened("screen_end_row", activity, dialog);
        assertTrue(SettingsUi.dialogOpen("screen_end_row", activity));

        screen.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse("the dialog closed with its screen", dialog.isShowing());
        assertFalse("and the row forgot it", SettingsUi.dialogOpen("screen_end_row", activity));
    }

    /** Settings rows hand over their preference's context, which wraps the screen. */
    @Test public void aDialogOpenedThroughAWrappedContextClosesWithItsScreenToo() {
        ActivityController<Activity> screen = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = screen.get();
        Utils.setContext(activity);
        ContextThemeWrapper wrapped = new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault);
        AlertDialog dialog = shown(activity);
        SettingsUi.dialogOpened("screen_end_wrapped_row", wrapped, dialog);

        screen.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(dialog.isShowing());
    }

    @Test public void anotherScreensEndLeavesTheDialogUp() {
        ActivityController<Activity> kept = Robolectric.buildActivity(Activity.class).setup().visible();
        ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = kept.get();
        Utils.setContext(activity);
        AlertDialog dialog = shown(activity);
        SettingsUi.dialogOpened("screen_end_kept_row", activity, dialog);

        other.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue("a different screen ended", dialog.isShowing());
        assertTrue(SettingsUi.dialogOpen("screen_end_kept_row", activity));
        dialog.dismiss();
        kept.pause().stop().destroy();
    }

    /** The row keeps one dialog for its double-tap check, and that used to be all a screen's end saw. */
    @Test public void aRowOpenedOnTwoScreensClosesEachDialogWithItsOwnScreen() {
        ActivityController<Activity> first = Robolectric.buildActivity(Activity.class).setup().visible();
        ActivityController<Activity> second = Robolectric.buildActivity(Activity.class).setup().visible();
        Utils.setContext(first.get());
        AlertDialog under = shown(first.get());
        SettingsUi.dialogOpened("screen_end_twice_row", first.get(), under);
        AlertDialog over = shown(second.get());
        SettingsUi.dialogOpened("screen_end_twice_row", second.get(), over);

        first.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse("the first screen's dialog closed with it", under.isShowing());
        assertTrue("the second screen's stayed", over.isShowing());
        assertTrue(SettingsUi.dialogOpen("screen_end_twice_row", second.get()));
        second.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(over.isShowing());
    }

    private static AlertDialog shown(Activity activity) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setMessage("Row details").create();
        dialog.show();
        shadowOf(Looper.getMainLooper()).idle();
        return dialog;
    }
}
