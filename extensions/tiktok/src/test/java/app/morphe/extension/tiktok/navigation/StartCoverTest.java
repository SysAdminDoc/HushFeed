package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StartCoverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    /** Bottom tab icon views, as TikTok's bar builds them, by tag. */
    private View home;
    private View profile;
    private boolean labelsWere;

    @Before public void setUp() {
        labelsWere = SettingsStatus.bottomTabLabelsEnabled;
        SettingsStatus.bottomTabLabelsEnabled = true;
        Settings.START_PAGE.resetToDefault();
        HookStatus.clear();
        StartPage.resetForTests();
        controller = Robolectric.buildActivity(Activity.class,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)).setup();
        activity = controller.get();
        FrameLayout bar = new FrameLayout(activity);
        activity.setContentView(bar);
        home = tab(bar, "HOME");
        profile = tab(bar, "USER");
        home.setSelected(true);
    }

    @After public void tearDown() {
        // A bar left on screen would answer the next test's cover.
        controller.pause().stop().destroy();
        SettingsStatus.bottomTabLabelsEnabled = labelsWere;
        Settings.START_PAGE.resetToDefault();
        HookStatus.clear();
        StartPage.resetForTests();
    }

    private static View tab(ViewGroup bar, String tag) {
        View icon = new View(bar.getContext());
        bar.addView(icon);
        BottomTabLabels.tabCreated(icon, tag);
        return icon;
    }

    private StartCover.Cover cover() {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            if (decor.getChildAt(i) instanceof StartCover.Cover) return (StartCover.Cover) decor.getChildAt(i);
        }
        return null;
    }

    private void frame() {
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void aStartOnProfileCoversHomeUntilProfileIsTheSelectedTab() {
        Settings.START_PAGE.save(StartPage.PROFILE);
        assertEquals("USER", StartPage.coldStartTag(activity, "HOME", null));
        assertNotNull("Home is covered from the start", cover());

        frame();
        assertNotNull("Home is still the selected tab", cover());

        home.setSelected(false);
        profile.setSelected(true);
        frame();
        assertNull("Profile is up", cover());
    }

    @Test public void theCoverComesOffBySelfWhenTheTabNeverShows() {
        StartCover.show(activity, "NOTIFICATION");
        assertNotNull(cover());
        shadowOf(Looper.getMainLooper()).idleFor(StartCover.LONGEST_MS - 1, TimeUnit.MILLISECONDS);
        assertNotNull(cover());
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MILLISECONDS);
        assertNull(cover());
    }

    @Test public void aStartOnHomesOwnFeedsHasNoCover() {
        Settings.START_PAGE.save(StartPage.FOR_YOU);
        assertEquals("HOME", StartPage.coldStartTag(activity, "HOME", null));
        assertNull(cover());
        Settings.START_PAGE.save(StartPage.TIKTOK);
        assertEquals("HOME", StartPage.coldStartTag(activity, "HOME", null));
        assertNull(cover());
    }

    /** A build whose tab buttons the patch couldn't find would only ever lift the cover late. */
    @Test public void withoutTheTabButtonsThereIsNoCover() {
        SettingsStatus.bottomTabLabelsEnabled = false;
        Settings.START_PAGE.save(StartPage.PROFILE);
        assertEquals("USER", StartPage.coldStartTag(activity, "HOME", null));
        assertNull(cover());
    }
}
