package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.comment.CommentSearch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

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
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Search instead of Home: the Home tab reads Search and opens TikTok's search, never the feed (#338). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SearchInsteadOfHomeTest {
    private static final int HOME_ID = 0x7f0a4b89;

    /** TikTok's bottom tab view as far as the switch reads it: an icon, an animated icon and a name. */
    public static final class FakeTab extends FrameLayout {
        final ImageView icon;
        final View lottie;
        final TextView title;

        public FakeTab(Context context) {
            super(context);
            icon = new ImageView(context);
            lottie = new View(context);
            title = new TextView(context);
            addView(icon, new LayoutParams(72, 72, Gravity.CENTER_HORIZONTAL));
            addView(lottie, new LayoutParams(72, 72, Gravity.CENTER_HORIZONTAL));
            addView(title, new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }

        public ImageView getTabIcon() {
            return icon;
        }

        public View getTabLottieIcon() {
            return lottie;
        }

        public TextView getTabTitleView() {
            return title;
        }
    }

    private ActivityController<Activity> owner;
    private Activity activity;
    private FakeTab home;
    private Drawable houseIcon;
    private final AtomicInteger taps = new AtomicInteger();

    @Before public void setUp() {
        HomeTabSettingsShortcut.resetForTests();
        StartPage.resetForTests();
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", true);
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
        PausedProcess.set(false);
        home = addHomeTab(activity);
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.6.3:onv", HOME_ID);
        Settings.SEARCH_INSTEAD_OF_HOME.save(true);
    }

    private FakeTab addHomeTab(Activity into) {
        FrameLayout content = new FrameLayout(into);
        FrameLayout bar = new FrameLayout(into);
        FakeTab tab = new FakeTab(into);
        tab.setId(HOME_ID);
        tab.setOnClickListener(view -> taps.incrementAndGet());
        houseIcon = new ColorDrawable(0xFFFFFFFF);
        tab.icon.setImageDrawable(houseIcon);
        tab.title.setText("Home");
        bar.addView(tab, new FrameLayout.LayoutParams(216, 138));
        content.addView(bar, new FrameLayout.LayoutParams(-1, 138, Gravity.BOTTOM));
        into.setContentView(content);
        idle();
        return tab;
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        HomeTabSettingsShortcut.resetForTests();
        StartPage.resetForTests();
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.6.3:onv", 0);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", false);
        Settings.SEARCH_INSTEAD_OF_HOME.resetToDefault();
        Settings.HOME_TAB_OPENS_SETTINGS.resetToDefault();
        Settings.START_PAGE.resetToDefault();
        Settings.BOTTOM_NAVIGATION.resetToDefault();
        Settings.BOTTOM_NAVIGATION_TABS.resetToDefault();
        owner.pause().stop().destroy();
    }

    @Test public void aTapOnHomeOpensTikToksSearchAndNotTheFeed() {
        installAndResume();
        assertTrue("the tab was never laid out", home.getWidth() > 0);

        tap();

        assertSearchOpened();
        assertEquals("the tap also reached TikTok's Home, which starts For You", 0, taps.get());
        assertFalse("the tab stayed pressed", home.isPressed());
        assertNull("one tap opened two pages", nextStarted());
    }

    @Test public void holdingHomeStillOpensHushfeedSettingsAndNotSearch() {
        installAndResume();

        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, down);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(800, TimeUnit.MILLISECONDS);
        touch(MotionEvent.ACTION_UP, down);

        Intent opened = nextStarted();
        assertNotNull("the long press opened nothing", opened);
        assertEquals("morphe_settings", opened.getAction());
        assertNull("the release after the long press opened search too", nextStarted());
        assertEquals(0, taps.get());
    }

    @Test public void aScreenReaderDoubleTapOpensSearchAndTheTabReadsSearch() {
        installAndResume();

        assertTrue(home.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertSearchOpened();
        assertEquals(0, taps.get());
        AccessibilityNodeInfo info = home.createAccessibilityNodeInfo();
        assertEquals("Search", String.valueOf(info.getContentDescription()));
    }

    @Test public void theIconAndNameReadSearchAndComeBackWhenSwitchedOff() {
        installAndResume();

        assertTrue("the icon isn't a magnifier",
                home.icon.getDrawable() instanceof CommentSearch.SearchGlyphDrawable);
        assertEquals(View.VISIBLE, home.icon.getVisibility());
        assertEquals("the animated house still shows over the magnifier", View.GONE, home.lottie.getVisibility());
        assertEquals("Search", home.title.getText().toString());

        // TikTok sets its icon and name again, as it does on a tab change: the switch holds.
        home.icon.setImageDrawable(houseIcon);
        home.lottie.setVisibility(View.VISIBLE);
        home.title.setText("Home");
        preDraw();
        assertTrue(home.icon.getDrawable() instanceof CommentSearch.SearchGlyphDrawable);
        assertEquals(View.GONE, home.lottie.getVisibility());
        assertEquals("Search", home.title.getText().toString());

        Settings.SEARCH_INSTEAD_OF_HOME.save(false);
        preDraw();
        assertSame("TikTok's icon didn't come back", houseIcon, home.icon.getDrawable());
        assertEquals(View.VISIBLE, home.lottie.getVisibility());
        assertEquals("Home", home.title.getText().toString());
    }

    @Test public void switchedOffATapReachesTikTokAndOnAgainItOpensSearch() {
        Settings.SEARCH_INSTEAD_OF_HOME.save(false);
        installAndResume();
        tap();
        assertEquals("off, a tap on Home didn't reach TikTok", 1, taps.get());
        assertNull(nextStarted());

        Settings.SEARCH_INSTEAD_OF_HOME.save(true);
        layoutPass();
        tap();
        assertSearchOpened();
        assertEquals(1, taps.get());

        Settings.SEARCH_INSTEAD_OF_HOME.save(false);
        layoutPass();
        tap();
        assertEquals("switched off, the tab kept opening search", 2, taps.get());
        assertNull(nextStarted());
        assertTrue(home.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals("the screen reader's click didn't go back to TikTok", 3, taps.get());
    }

    @Test public void pausedTheTabIsTikToksHomeAgain() {
        installAndResume();
        PausedProcess.set(true);
        layoutPass();
        tap();
        assertEquals(1, taps.get());
        assertNull(nextStarted());
    }

    @Test public void theBottomTabNameIsSearchOverTheReadersOwnName() {
        assertEquals("Search", BottomTabLabels.nameFor(BottomNavigationTabOptions.HOME));
        Settings.SEARCH_INSTEAD_OF_HOME.save(false);
        assertEquals("", BottomTabLabels.nameFor(BottomNavigationTabOptions.HOME));
    }

    @Test public void aLauncherStartLandsOnInboxOrProfileInsteadOfTheFeed() {
        assertEquals("TikTok's own Home start", "NOTIFICATION", start("HOME"));
        assertEquals("a tab TikTok picked off the feed was moved", "SHOP_MALL", start("SHOP_MALL"));
        Settings.START_PAGE.save(StartPage.FOR_YOU);
        assertEquals("a chosen For You", "NOTIFICATION", start("HOME"));
        Settings.START_PAGE.save(StartPage.PROFILE);
        assertEquals("a chosen Profile was changed", "USER", start("HOME"));

        Settings.START_PAGE.resetToDefault();
        Settings.BOTTOM_NAVIGATION.save(true);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
        assertEquals("with Inbox hidden", "USER", start("HOME"));

        Settings.SEARCH_INSTEAD_OF_HOME.save(false);
        assertEquals("off, TikTok keeps its own tab", "HOME", start("HOME"));
    }

    private String start(String tag) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return StartPage.coldStartTag(new StartPageTest.Host(launcher), tag, null);
    }

    private void assertSearchOpened() {
        Intent opened = nextStarted();
        assertNotNull("nothing was opened", opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        Uri page = opened.getData();
        assertNotNull(page);
        assertEquals("snssdk1233", page.getScheme());
        assertEquals("search", page.getHost());
        assertEquals("search opened outside TikTok", activity.getPackageName(), opened.getPackage());
    }

    private void tap() {
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, down);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS);
        touch(MotionEvent.ACTION_UP, down);
        idle();
    }

    private void touch(int action, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 40, 40, 0);
        try {
            home.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void installAndResume() {
        HomeTabSettingsShortcut.install(activity);
        owner.pause().resume();
        idle();
    }

    private void layoutPass() {
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private void preDraw() {
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Intent nextStarted() {
        return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    }
}
