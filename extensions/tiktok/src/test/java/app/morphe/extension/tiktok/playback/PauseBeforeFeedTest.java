package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
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
