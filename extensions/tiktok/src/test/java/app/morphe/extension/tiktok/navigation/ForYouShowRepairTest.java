/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import com.ss.android.ugc.aweme.feed.ui.FeedRecommendFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The frozen For You feed (#322, #326): a For You page in front whose list panel's hint is off
 * gets Home's own switch and then its own show, and a healthy, hidden or paused feed gets nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ForYouShowRepairTest {
    private static final String HOME = "Home container";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FakeTikTok tiktok;
    private FeedRecommendFragment forYou;
    private View pager;

    @Before public void setUp() {
        ForYouShowRepair.resetForTests();
        HookStatus.clear();
        PausedProcess.set(false);
        tiktok = new FakeTikTok();
        forYou = new FeedRecommendFragment();
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        pager = new View(RuntimeEnvironment.getApplication());
        root.addView(pager);
        tiktok.views.put(forYou, root);
        ForYouShowRepair.useHostForTests(tiktok);
        ForYouShowRepair.forYouCreated(forYou);
    }

    @After public void tearDown() {
        ForYouShowRepair.resetForTests();
        PausedProcess.set(false);
        HookStatus.clear();
    }

    @Test public void aHealthyFeedIsLeftAlone() {
        tiktok.hint = true;
        assertEquals(ForYouShowRepair.Outcome.HEALTHY, ForYouShowRepair.check(pager, false));
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void homeOnAnotherTabSwitchesToForYouAndThenShowsThePage() {
        tiktok.current = tiktok.friends;
        assertEquals(ForYouShowRepair.Outcome.SHOWN, ForYouShowRepair.check(pager, false));
        assertEquals(List.of("switch For You", "show"), tiktok.calls);
        for (Bundle bundle : tiktok.bundles) {
            assertEquals("Hox's key for For You's container", 1, bundle.size());
            assertTrue(bundle.getBoolean(HOME));
        }
        assertTrue(tiktok.hint);
    }

    @Test public void aSwitchThatTurnsTheHintOnSendsNoSecondShow() {
        tiktok.current = tiktok.friends;
        tiktok.switchShows = true;
        assertEquals(ForYouShowRepair.Outcome.SWITCHED, ForYouShowRepair.check(pager, false));
        assertEquals(List.of("switch For You"), tiktok.calls);
    }

    @Test public void homeAlreadyOnForYouGetsOnlyThePageShow() {
        assertEquals(ForYouShowRepair.Outcome.SHOWN, ForYouShowRepair.check(pager, false));
        assertEquals(List.of("show"), tiktok.calls);
    }

    @Test public void aShowThatLeavesTheHintOffIsSaidSoAndNotRepeated() {
        tiktok.showShows = false;
        assertEquals(ForYouShowRepair.Outcome.STILL_FROZEN, ForYouShowRepair.check(pager, false));
        assertEquals(List.of("show"), tiktok.calls);
    }

    @Test public void theFirstSwipeIsRepairedThroughThePatchedEntryPoint() {
        ForYouShowRepair.feedPageSelected(pager);
        assertEquals(List.of("show"), tiktok.calls);
        assertTrue(tiktok.hint);
        ForYouShowRepair.feedPageSelected(pager);
        assertEquals("the next swipe finds a healthy feed", List.of("show"), tiktok.calls);
    }

    @Test public void pausedHushfeedLeavesTikTokAlone() {
        PausedProcess.set(true);
        assertEquals(ForYouShowRepair.Outcome.PAUSED, ForYouShowRepair.check(pager, false));
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void aPageThatIsNotInFrontIsLeftAlone() {
        tiktok.front = false;
        assertEquals(ForYouShowRepair.Outcome.NOT_IN_FRONT, ForYouShowRepair.check(pager, false));
        tiktok.front = true;
        tiktok.hidden = true;
        assertEquals(ForYouShowRepair.Outcome.NOT_IN_FRONT, ForYouShowRepair.check(pager, false));
        tiktok.hidden = false;
        tiktok.resumed = false;
        assertEquals(ForYouShowRepair.Outcome.NOT_IN_FRONT, ForYouShowRepair.check(pager, false));
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void aHiddenHomeIsLeftHidden() {
        tiktok.homeShown = false;
        assertEquals(ForYouShowRepair.Outcome.HOME_HIDDEN, ForYouShowRepair.check(pager, false));
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void aPagerOutsideForYouIsLeftAlone() {
        View elsewhere = new View(RuntimeEnvironment.getApplication());
        assertEquals(ForYouShowRepair.Outcome.NOT_FOR_YOU, ForYouShowRepair.check(elsewhere, false));
        assertEquals(ForYouShowRepair.Outcome.NOT_FOR_YOU, ForYouShowRepair.check(new Object(), false));
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void noHoxNodeMeansNothingIsSent() {
        tiktok.hox = null;
        assertEquals(ForYouShowRepair.Outcome.NO_HOX, ForYouShowRepair.check(pager, false));
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void aCheckInsideTikTokOwnShowWaitsForItToFinish() {
        tiktok.inside = true;
        assertEquals(ForYouShowRepair.Outcome.DEFERRED, ForYouShowRepair.check(pager, false));
        assertEquals(List.of(), tiktok.calls);
        assertEquals(1, tiktok.posted.size());

        // TikTok's own show turned the hint on meanwhile: the second look finds it healthy.
        tiktok.inside = false;
        tiktok.hint = true;
        tiktok.posted.remove(0).run();
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void aSecondLookStillInsideAShowIsNotPostedAgain() {
        tiktok.inside = true;
        assertEquals(ForYouShowRepair.Outcome.DEFERRED, ForYouShowRepair.check(pager, false));
        tiktok.posted.remove(0).run();
        assertEquals(List.of(), tiktok.posted);
        assertEquals(List.of(), tiktok.calls);
    }

    @Test public void aSecondLookAfterAShowThatNeverCameRepairs() {
        tiktok.inside = true;
        ForYouShowRepair.check(pager, false);
        tiktok.inside = false;
        tiktok.posted.remove(0).run();
        assertEquals(List.of("show"), tiktok.calls);
    }

    @Test public void aPagePickedDuringTheRepairIsNotRepairedTwice() {
        tiktok.showShows = false;
        tiktok.duringShow = () -> assertEquals(ForYouShowRepair.Outcome.BUSY, ForYouShowRepair.check(pager, false));
        assertEquals(ForYouShowRepair.Outcome.STILL_FROZEN, ForYouShowRepair.check(pager, false));
        assertEquals(List.of("show"), tiktok.calls);
    }

    @Test public void eachForYouPageIsRememberedOnce() {
        ForYouShowRepair.forYouCreated(forYou);
        ForYouShowRepair.forYouCreated(null);
        assertEquals(ForYouShowRepair.Outcome.SHOWN, ForYouShowRepair.check(pager, false));
        assertEquals("one page, one show", List.of("show"), tiktok.calls);
    }

    @Test public void unpatchedStubsNeverReachTikTok() {
        ForYouShowRepair.useHostForTests(null);
        assertEquals(ForYouShowRepair.Outcome.NOT_FOR_YOU, ForYouShowRepair.check(pager, false));
    }

    @Test public void aDetachedPageIsNotOnScreen() {
        assertFalse(ForYouShowRepair.onScreen(pager));
        assertFalse(ForYouShowRepair.onScreen(null));
    }

    /** TikTok as the check sees it: For You's page, its panel's hint and Hox's Home. */
    private static final class FakeTikTok implements ForYouShowRepair.Host {
        final Map<Object, View> views = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Bundle> bundles = new ArrayList<>();
        final List<Runnable> posted = new ArrayList<>();
        final Object panel = new Object();
        final Object activity = new Object();
        final Object home = new Object();
        final Object forYouNode = new Object();
        final Object friends = new Object();
        Object hox = new Object();
        Object current = forYouNode;
        boolean hint;
        boolean resumed = true;
        boolean hidden;
        boolean front = true;
        boolean inside;
        boolean homeShown = true;
        boolean switchShows;
        boolean showShows = true;
        Runnable duringShow;

        @Override public View view(Object fragment) {
            return views.get(fragment);
        }

        @Override public boolean resumed(Object fragment) {
            return resumed;
        }

        @Override public boolean hidden(Object fragment) {
            return hidden;
        }

        @Override public Object panel(Object fragment) {
            return panel;
        }

        @Override public boolean hint(Object panel) {
            return hint;
        }

        @Override public Object activity(Object fragment) {
            return activity;
        }

        @Override public Object hox(Object activity) {
            return hox;
        }

        @Override public String containerTag(Object hox, String tag) {
            return ForYouShowRepair.FOR_YOU.equals(tag) ? HOME : null;
        }

        @Override public Object node(Object hox, String tag) {
            return ForYouShowRepair.FOR_YOU.equals(tag) ? forYouNode : null;
        }

        @Override public Object container(Object node) {
            return node == forYouNode || node == friends ? home : null;
        }

        @Override public Object currentChild(Object container) {
            return current;
        }

        @Override public boolean shown(Object container) {
            return homeShown;
        }

        @Override public String tag(Object node) {
            return node == friends ? "Friends" : ForYouShowRepair.FOR_YOU;
        }

        @Override public void switchTo(Object hox, Object container, String tag, Bundle bundle) {
            calls.add("switch " + tag);
            bundles.add(bundle);
            current = forYouNode;
            if (switchShows) hint = true;
        }

        @Override public void show(Object fragment, Bundle bundle) {
            calls.add("show");
            bundles.add(bundle);
            if (duringShow != null) duringShow.run();
            if (showShows) hint = true;
        }

        @Override public boolean inFront(View page) {
            return front;
        }

        @Override public boolean insideShowOrHide() {
            return inside;
        }

        @Override public void post(Runnable check) {
            posted.add(check);
        }
    }
}
