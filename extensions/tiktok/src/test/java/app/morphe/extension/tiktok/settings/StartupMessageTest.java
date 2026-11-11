/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;

/** The reader's own message each time TikTok opens (#347), and not again on a recreation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StartupMessageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A main activity that can say it's being recreated, as one is on a theme change. */
    public static class MainScreen extends Activity {
        boolean changing;

        @Override public boolean isChangingConfigurations() {
            return changing;
        }
    }

    @Before public void setUp() {
        StartupMessage.resetForTests();
        ShadowAlertDialog.reset();
        Settings.STARTUP_MESSAGE.save(true);
        Settings.STARTUP_MESSAGE_TITLE.save("Hey you");
        Settings.STARTUP_MESSAGE_TEXT.save("Go to bed before midnight");
    }

    @After public void tearDown() {
        StartupMessage.resetForTests();
        Settings.STARTUP_MESSAGE.resetToDefault();
        Settings.STARTUP_MESSAGE_TITLE.resetToDefault();
        Settings.STARTUP_MESSAGE_TEXT.resetToDefault();
        Settings.STARTUP_MESSAGE_DISMISSIBLE.resetToDefault();
        Settings.STARTUP_MESSAGE_HIDDEN.resetToDefault();
    }

    /** What the patched MainActivity.onCreate does, then the first frames. */
    private static ActivityController<MainScreen> open() {
        ActivityController<MainScreen> controller = Robolectric.buildActivity(MainScreen.class).setup();
        Utils.setContext(controller.get());
        StartupMessage.onMainActivityCreate(controller.get());
        shadowOf(Looper.getMainLooper()).idle();
        return controller;
    }

    @Test public void theMessageShowsWithItsTitleAndTextAndBackClosesIt() {
        try (ActivityController<MainScreen> ignored = open()) {
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("no message on opening", dialog);
            assertTrue(dialog.isShowing());
            assertEquals("Hey you", String.valueOf(shadowOf(dialog).getTitle()));
            assertEquals("Go to bed before midnight", String.valueOf(shadowOf(dialog).getMessage()));
            assertEquals("OK", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(shadowOf(dialog).isCancelable());
            assertTrue(shadowOf(dialog).isCancelableOnTouchOutside());
        }
    }

    @Test public void notDismissibleLeavesOnlyItsButton() {
        Settings.STARTUP_MESSAGE_DISMISSIBLE.save(false);
        try (ActivityController<MainScreen> ignored = open()) {
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertFalse("Back closed it", shadowOf(dialog).isCancelable());
            assertFalse("a tap outside closed it", shadowOf(dialog).isCancelableOnTouchOutside());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse("its button didn't close it", dialog.isShowing());
        }
    }

    @Test public void aTextOnlyMessageHasNoTitle() {
        Settings.STARTUP_MESSAGE_TITLE.save("   ");
        try (ActivityController<MainScreen> ignored = open()) {
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("", String.valueOf(shadowOf(dialog).getTitle() == null ? "" : shadowOf(dialog).getTitle()));
            assertEquals("Go to bed before midnight", String.valueOf(shadowOf(dialog).getMessage()));
        }
    }

    @Test public void offOrEmptyShowsNothing() {
        Settings.STARTUP_MESSAGE.save(false);
        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("the switch is off", ShadowAlertDialog.getLatestAlertDialog());
        }
        Settings.STARTUP_MESSAGE.save(true);
        Settings.STARTUP_MESSAGE_TITLE.save("");
        Settings.STARTUP_MESSAGE_TEXT.save(" ");
        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("nothing to say", ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void aRecreatedOrStackedMainScreenDoesNotShowItAgain() {
        ActivityController<MainScreen> first = open();
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
        ShadowAlertDialog.reset();

        // A link stacks a second main screen on the first.
        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("a stacked main screen showed it again", ShadowAlertDialog.getLatestAlertDialog());
        }

        // A theme change recreates the one in front.
        ActivityController<MainScreen> front = open();
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        front.get().changing = true;
        front.pause().stop().destroy();
        first.pause().stop().destroy();
        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("a recreation showed it again", ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    /** The dialog dies with the activity, so a rotation mid-read would lose a message nobody closed. */
    @Test public void aMessageUpWhenItsScreenIsRecreatedComesBackOnTheNewOneOnce() {
        Settings.STARTUP_MESSAGE_DISMISSIBLE.save(false);
        ActivityController<MainScreen> first = open();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        ShadowAlertDialog.reset();
        first.get().changing = true;
        first.pause().stop().destroy();

        ActivityController<MainScreen> second = open();
        AlertDialog back = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the recreation lost a message still up", back);
        assertTrue(back.isShowing());
        assertEquals("Hey you", String.valueOf(shadowOf(back).getTitle()));
        assertEquals("Go to bed before midnight", String.valueOf(shadowOf(back).getMessage()));
        assertFalse("it came back dismissible", shadowOf(back).isCancelable());
        ShadowAlertDialog.reset();

        // A link stacks another main screen on the one with the message.
        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("a stacked main screen got it too", ShadowAlertDialog.getLatestAlertDialog());
        }

        // Closed on the new screen, it stays closed through the next rotation.
        back.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        second.get().changing = true;
        second.pause().stop().destroy();
        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("a closed message came back", ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void aMessageClosedWithBackStaysClosedThroughARecreation() {
        ActivityController<MainScreen> first = open();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.onBackPressed();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("Back didn't close it", dialog.isShowing());
        ShadowAlertDialog.reset();
        first.get().changing = true;
        first.pause().stop().destroy();

        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("a closed message came back", ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    /** #347's reporter: having it come up every time gets tiresome. */
    @Test public void dontShowAgainHidesThatMessageUntilItChanges() {
        ActivityController<MainScreen> first = open();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Don't show again", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        ShadowAlertDialog.reset();
        first.get().finish();
        first.pause().stop().destroy();

        ActivityController<MainScreen> second = open();
        assertNull("a hidden message came back", ShadowAlertDialog.getLatestAlertDialog());
        second.get().finish();
        second.pause().stop().destroy();

        Settings.STARTUP_MESSAGE_TEXT.save("Go to bed before eleven");
        try (ActivityController<MainScreen> ignored = open()) {
            AlertDialog changed = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("a new message stayed hidden", changed);
            assertEquals("Go to bed before eleven", String.valueOf(shadowOf(changed).getMessage()));
        }
    }

    /** #347's reporter: dismissing it once per download is enough. */
    @Test public void aNewInstallOrUpdateShowsAHiddenMessageOnceMore() {
        Context application = RuntimeEnvironment.getApplication();
        shadowOf(application.getPackageManager())
                .getInternalMutablePackageInfo(application.getPackageName()).lastUpdateTime = 1_700_000_000_000L;
        ActivityController<MainScreen> first = open();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ShadowAlertDialog.reset();
        first.get().finish();
        first.pause().stop().destroy();

        ActivityController<MainScreen> same = open();
        assertNull("hidden, yet it came back on the same install", ShadowAlertDialog.getLatestAlertDialog());
        same.get().finish();
        same.pause().stop().destroy();

        shadowOf(application.getPackageManager())
                .getInternalMutablePackageInfo(application.getPackageName()).lastUpdateTime = 1_700_000_600_000L;
        ActivityController<MainScreen> updated = open();
        AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("an updated TikTok kept it hidden", again);
        again.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ShadowAlertDialog.reset();
        updated.get().finish();
        updated.pause().stop().destroy();

        try (ActivityController<MainScreen> ignored = open()) {
            assertNull("hidden again on the update, yet it came back", ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void leavingAndOpeningTikTokAgainShowsItAgain() {
        ActivityController<MainScreen> first = open();
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
        ShadowAlertDialog.reset();
        first.get().finish();
        first.pause().stop().destroy();

        try (ActivityController<MainScreen> ignored = open()) {
            assertNotNull("opening TikTok again showed nothing", ShadowAlertDialog.getLatestAlertDialog());
        }
    }
}
