package app.morphe.extension.tiktok.accountmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Completion;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Kind;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Page;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Row;

import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The buttons and the status line of the Saved posts and Blocked accounts dialogs.
 *
 * <p>The neutral button stops the page loads and keeps the list on screen, and Close is the one
 * that leaves. Named Cancel, it read as the way out. The status line is the one place that says a
 * page landed or loading stopped, so a screen reader has to hear it change on its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class AccountManagementDialogTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A signed-in account that never changes. */
    private static final AccountListSession.Accounts SIGNED_IN = new AccountListSession.Accounts() {
        @Override public String current() { return "42"; }
        @Override public AccountListSession.Cancellation observe(Runnable changed) { return () -> { }; }
    };

    @Test public void theNeutralButtonSaysStopLoadingAndIsLiveOnlyWhileAPageLoads() {
        Completion[] inFlight = new Completion[1];
        AccountListSession.Transport transport = (kind, cursor, guard, completion) -> {
            inFlight[0] = completion;
            return () -> { };
        };
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = AccountManagement.open(activity, Kind.SAVED_POSTS, SIGNED_IN, transport);
            shadowOf(Looper.getMainLooper()).idle();

            Button stop = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            assertEquals("Stop loading", stop.getText().toString());
            assertEquals("Close", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            TextView status = dialog.getWindow().getDecorView().findViewWithTag("account_list_status");
            assertNotNull("the status line is missing", status);
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.getAccessibilityLiveRegion());
            assertEquals("Loading page 1...", status.getText().toString());
            assertTrue("Stop loading is dead while the first page loads", stop.isEnabled());

            // The last page lands: there is no load left to stop, and the list stays.
            inFlight[0].finished(new Page(Collections.singletonList(new Row("1", "A post", "")), 1, false, null), null);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("TikTok reports no more items in this list.", status.getText().toString());
            assertFalse("Stop loading stayed live with nothing loading", stop.isEnabled());
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertTrue(dialog.isShowing());
            dialog.dismiss();
        }
    }

    @Test public void stopLoadingKeepsTheListOpenAndSaysWhatItDid() {
        boolean[] cancelled = new boolean[1];
        AccountListSession.Transport transport = (kind, cursor, guard, completion) -> () -> cancelled[0] = true;
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = AccountManagement.open(activity, Kind.BLOCKED_ACCOUNTS, SIGNED_IN, transport);
            shadowOf(Looper.getMainLooper()).idle();

            Button stop = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            assertTrue(stop.isEnabled());
            assertTrue(stop.performClick());
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the page request was left running", cancelled[0]);
            assertTrue("Stop loading closed the dialog", dialog.isShowing());
            TextView status = dialog.getWindow().getDecorView().findViewWithTag("account_list_status");
            assertEquals("Loading cancelled. The list is incomplete.", status.getText().toString());
            assertFalse(stop.isEnabled());
            dialog.dismiss();
        }
    }
}
