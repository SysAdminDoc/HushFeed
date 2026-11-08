package app.morphe.extension.tiktok.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The share sheet's Send to new group button, which shows once two people are picked (#375). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShareNewGroupButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restoreSettings() {
        Settings.HIDE_SHARE_NEW_GROUP.resetToDefault();
    }

    @Test public void withTheSwitchOffTikTokGetsItsButtonBack() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            View button = new View(activity);

            assertSame(button, ShareNewGroupButton.filter(button));
            assertEquals(View.VISIBLE, button.getVisibility());
        }
    }

    /** A null answer takes TikTok's own early return, so the button also has to be hidden here. */
    @Test public void withTheSwitchOnTheButtonHidesAndTikTokSeesNone() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.HIDE_SHARE_NEW_GROUP.save(true);
            View button = new View(activity);

            assertNull(ShareNewGroupButton.filter(button));
            assertEquals(View.GONE, button.getVisibility());

            Settings.HIDE_SHARE_NEW_GROUP.save(false);
            View next = new View(activity);
            assertSame(next, ShareNewGroupButton.filter(next));
        }
    }

    @Test public void aSheetWithoutTheButtonStaysWithout() {
        Settings.HIDE_SHARE_NEW_GROUP.save(true);
        assertNull(ShareNewGroupButton.filter(null));
        Settings.HIDE_SHARE_NEW_GROUP.save(false);
        assertNull(ShareNewGroupButton.filter(null));
    }
}
