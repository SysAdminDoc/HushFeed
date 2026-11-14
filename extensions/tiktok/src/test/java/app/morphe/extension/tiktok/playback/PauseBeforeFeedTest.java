package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Pause before the feed: the first For You open after TikTok starts or comes back waits behind
 * a countdown, Continue only works once it has run out, and Leave sends TikTok to the back.
 * Messages open without it, and zero turns it off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PauseBeforeFeedTest {
    public static class HostActivity extends Activity {
        final List<Boolean> movedBack = new ArrayList<>();

        @Override public boolean moveTaskToBack(boolean nonRoot) {
            movedBack.add(nonRoot);
            return true;
        }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.blockAuthorEnabled = true;
        Settings.PAUSE_BEFORE_FEED_SECONDS.resetToDefault();
        Settings.NO_RESUME_ON_FOREGROUND.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        ReflectionHelpers.callStaticMethod(
                app.morphe.extension.tiktok.wellbeing.SessionBudget.class, "resetForTests");
        PausePlayback.resetForTests();
    }

    @After public void tearDown() {
        PausePlayback.resetForTests();
        ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference", new WeakReference<View>(null));
        Settings.PAUSE_BEFORE_FEED_SECONDS.resetToDefault();
        Settings.NO_RESUME_ON_FOREGROUND.resetToDefault();
        SettingsStatus.blockAuthorEnabled = false;
    }

    @Test public void theFirstFeedAfterAReturnWaitsBehindTheCountdown() {
        Settings.PAUSE_BEFORE_FEED_SECONDS.save(5);
        // With the tap the return otherwise waits for, which the pause stands in for.
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            Activity activity = owner.get();
            PausePlayback.install(activity);
            standOnTheFeed(activity);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);

            View panel = PausePlayback.catcherForTests();
            assertNotNull("TikTok's start opened the feed with no pause in front of it", panel);
            assertTrue("the feed played behind the pause", PausePlayback.quietenedForTests());
            TextView go = button(panel, "Continue");
            assertFalse("Continue was offered before the count ran out", go.isEnabled());
            go.performClick();
            assertNotNull("Continue let the feed in before the count ran out", PausePlayback.catcherForTests());

            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
            assertTrue("Continue stayed off after the count ran out", go.isEnabled());
            go.performClick();
            assertNull("Continue left the pause up", PausePlayback.catcherForTests());
            assertFalse("Continue kept the feed quiet", PausePlayback.quietenedForTests());
            assertFalse(PausePlayback.pauseOwedForTests());

            // Back from messages in the same visit: no second pause, and no tap catcher either.
            owner.pause();
            owner.resume();
            layout(activity);
            assertNull("the pause came back inside the same visit", PausePlayback.catcherForTests());

            // Away and back is a new visit, with a new pause the whole length.
            owner.pause().stop();
            owner.restart().resume();
            layout(activity);
            View again = PausePlayback.catcherForTests();
            assertNotNull("coming back from the background opened the feed with no pause", again);
            assertFalse("a new visit's pause started out already run down",
                    button(again, "Continue").isEnabled());
        }
    }

    @Test public void messagesOpenWithoutItAndTheFeedAfterThemGetsIt() {
        Settings.PAUSE_BEFORE_FEED_SECONDS.save(10);
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            Activity activity = owner.get();
            PausePlayback.install(activity);
            View home = standOnTheFeed(activity);
            // The reader comes back to the Inbox tab.
            home.setSelected(false);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);
            assertNull("messages opened behind the pause", PausePlayback.catcherForTests());
            assertTrue(PausePlayback.pauseOwedForTests());

            home.setSelected(true);
            layout(activity);
            View panel = PausePlayback.catcherForTests();
            assertNotNull("the first feed after messages opened with no pause", panel);

            // Back to messages part way through: the pause comes down, still owed, and carries on
            // from the same end when the feed is back.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
            home.setSelected(false);
            layout(activity);
            assertNull("the pause stayed up over messages", PausePlayback.catcherForTests());
            home.setSelected(true);
            layout(activity);
            View back = PausePlayback.catcherForTests();
            assertNotNull(back);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
            assertTrue("the trip to messages started the count over", button(back, "Continue").isEnabled());
        }
    }

    @Test public void leaveSendsTikTokToTheBack() {
        Settings.PAUSE_BEFORE_FEED_SECONDS.save(30);
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            HostActivity activity = owner.get();
            PausePlayback.install(activity);
            standOnTheFeed(activity);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);

            button(PausePlayback.catcherForTests(), "Leave TikTok").performClick();
            assertEquals("one moveTaskToBack(true)", List.of(true), activity.movedBack);
        }
    }

    @Test public void aScreenReaderIsMovedOntoTheTitleWhenTheWindowComesBack() {
        Settings.PAUSE_BEFORE_FEED_SECONDS.save(20);
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            Activity activity = owner.get();
            screenReaderOn(activity);
            PausePlayback.install(activity);
            View home = standOnTheFeed(activity);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);
            TextView title = button(PausePlayback.catcherForTests(), "Pause before the feed");

            // Back from the home screen the panel is up before the window has its focus back,
            // and TalkBack reads the window and picks a stop of its own: the Home tab, on the S22.
            owner.windowFocusChanged(true);
            focus(home);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue("TalkBack was left on the tab bar and never said the title",
                    title.isAccessibilityFocused());
            assertFalse(home.isAccessibilityFocused());

            // A reader who has gone on to the tab bar since stays there when the window only lost
            // its focus to the notification shade or a permission prompt.
            focus(home);
            owner.windowFocusChanged(false);
            owner.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue("the shade closing pulled the reader back off the tab bar",
                    home.isAccessibilityFocused());
            assertFalse(title.isAccessibilityFocused());

            // The home screen and back again: TikTok took the pause down as it went and puts a new
            // one up, and TalkBack is on the tab again.
            owner.windowFocusChanged(false);
            owner.pause().stop();
            owner.restart().resume();
            layout(activity);
            TextView again = button(PausePlayback.catcherForTests(), "Pause before the feed");
            owner.windowFocusChanged(true);
            focus(home);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue("the second return left the reader on the tab bar", again.isAccessibilityFocused());
        }
    }

    @Test public void aPauseUnderAppLockLeavesTheReaderToTheLock() {
        Settings.PAUSE_BEFORE_FEED_SECONDS.save(20);
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            Activity activity = owner.get();
            screenReaderOn(activity);
            PausePlayback.install(activity);
            standOnTheFeed(activity);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);
            TextView title = button(PausePlayback.catcherForTests(), "Pause before the feed");

            // App lock covers the whole window as TikTok comes back, the pause included, and
            // curtains everything under it the way it does in AppLock.cover.
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            TextView lock = new TextView(activity);
            lock.setText("Unlock TikTok");
            decor.addView(lock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            SessionLockOverlay.hideBehind(decor, lock, true);
            owner.windowFocusChanged(true);
            focus(lock);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse("the reader was moved under App lock onto a title it can't read",
                    title.isAccessibilityFocused());
            assertTrue(lock.isAccessibilityFocused());

            // Unlocked: the fingerprint prompt goes, the cover with it, and the pause is the first
            // thing on the window.
            owner.windowFocusChanged(false);
            SessionLockOverlay.hideBehind(decor, lock, false);
            decor.removeView(lock);
            owner.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue("unlocked, the reader wasn't brought to the pause", title.isAccessibilityFocused());
        }
    }

    @Test public void aCoverComingOffInPlaceBringsTheReaderToThePause() {
        Settings.PAUSE_BEFORE_FEED_SECONDS.save(20);
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            Activity activity = owner.get();
            screenReaderOn(activity);
            PausePlayback.install(activity);
            View home = standOnTheFeed(activity);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);
            TextView title = button(PausePlayback.catcherForTests(), "Pause before the feed");

            // A cover over the pause that goes with no window change to try again on, as
            // StartCover timing out or the feed lock's hold ending does.
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            TextView cover = new TextView(activity);
            cover.setText("Inbox");
            decor.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            SessionLockOverlay.hideBehind(decor, cover, true);
            owner.windowFocusChanged(true);
            focus(cover);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            decor.getViewTreeObserver().dispatchOnGlobalLayout();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse("the reader was moved onto the pause with the cover still over it",
                    title.isAccessibilityFocused());
            assertTrue(cover.isAccessibilityFocused());

            // The curtain comes off with nothing removed or hidden and nothing laid out by hand, as
            // when the panel that put it up was collected before it lifted.
            SessionLockOverlay.hideBehind(decor, cover, false);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue("the cover came off and the reader was never brought to the pause",
                    title.isAccessibilityFocused());

            // Said once: later layouts leave a reader who went on to the tab bar there.
            focus(home);
            decor.getViewTreeObserver().dispatchOnGlobalLayout();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue("a later layout pulled the reader back onto the pause", home.isAccessibilityFocused());
        }
    }

    /**
     * Only a reader exploring by touch can be moved. Before Android 9 there is no pane title to
     * say the pause either, so any other reader is told the title, once.
     */
    @Test @Config(sdk = {23, 28}) public void aReaderThatCantBeMovedIsToldThePauseBeforeAndroid9() {
        Settings.PAUSE_BEFORE_FEED_SECONDS.save(20);
        Settings.NO_RESUME_ON_FOREGROUND.save(true);
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            Activity activity = owner.get();
            var manager = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
            Shadows.shadowOf(manager).setEnabled(true);
            Shadows.shadowOf(manager).setTouchExplorationEnabled(false);
            PausePlayback.install(activity);
            standOnTheFeed(activity);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);
            owner.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            long expected = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ? 1 : 0;
            assertEquals(expected, announcements(manager, "Pause before the feed"));

            // A reader that explores by touch hears it from the focus move alone.
            Shadows.shadowOf(manager).setTouchExplorationEnabled(true);
            owner.windowFocusChanged(false);
            owner.pause().stop();
            owner.restart().resume();
            layout(activity);
            owner.windowFocusChanged(true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue(button(PausePlayback.catcherForTests(), "Pause before the feed").isAccessibilityFocused());
            assertEquals("the title was said twice, once moved onto and once told",
                    expected, announcements(manager, "Pause before the feed"));
        }
    }

    @Test public void zeroLeavesTheFeedAlone() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).create()) {
            Activity activity = owner.get();
            PausePlayback.install(activity);
            standOnTheFeed(activity);
            owner.start().resume().visible();
            Utils.setActivity(activity);
            layout(activity);
            assertNull(PausePlayback.catcherForTests());

            // Turned on in the settings with the reader already in: the feed they go back to
            // isn't stopped, and the next return is the first one paused.
            Settings.PAUSE_BEFORE_FEED_SECONDS.save(5);
            owner.pause();
            owner.resume();
            layout(activity);
            assertNull("turning the pause on stopped the feed the reader went back to",
                    PausePlayback.catcherForTests());
        }
    }

    private static void layout(Activity activity) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ViewGroup root = activity.findViewById(android.R.id.content);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * The recommendation feed: a feed pager on screen and a selected Home tab. TikTok's tab
     * resource is absent from the test app, so the lookup is seeded as the other feed tests do.
     */
    private static View standOnTheFeed(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        root.addView(new com.ss.android.ugc.aweme.common.widget.VerticalViewPager(activity),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        View home = new View(activity);
        root.addView(home, new FrameLayout.LayoutParams(96, 100, Gravity.BOTTOM));
        home.setSelected(true);
        ReflectionHelpers.setStaticField(FeedVisibility.class, "homeTabReference", new WeakReference<>(home));
        return home;
    }

    private static void screenReaderOn(Activity activity) {
        var manager = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(manager).setEnabled(true);
        Shadows.shadowOf(manager).setTouchExplorationEnabled(true);
    }

    /** Where TalkBack puts its own focus, as a swipe or its pick on a window change does. */
    private static void focus(View view) {
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
    }

    /** How many times a screen reader was told [said] out loud. */
    private static long announcements(AccessibilityManager manager, CharSequence said) {
        return Shadows.shadowOf(manager).getSentAccessibilityEvents().stream()
                .filter(event -> event.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT)
                .filter(event -> event.getText().stream().anyMatch(text -> said.toString().contentEquals(text)))
                .count();
    }

    private static TextView button(View panel, String label) {
        TextView found = find(panel, label);
        assertNotNull("no " + label + " on the pause", found);
        return found;
    }

    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = find(group.getChildAt(index), label);
            if (found != null) return found;
        }
        return null;
    }
}
