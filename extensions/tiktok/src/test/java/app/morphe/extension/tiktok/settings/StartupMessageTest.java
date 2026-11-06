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
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
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
