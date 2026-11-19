/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import com.ss.android.ugc.aweme.backgroundplay.ScopedBgPlayScene;
import com.ss.android.ugc.aweme.backgroundplay.ScopedBgPlaySessionState;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** #292: a page opened from a profile keeps playing its videos in TikTok's background queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DetailAutoAdvanceQueueTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final DetailAutoAdvance.Hidden APP_HIDDEN = DetailAutoAdvance.appHidden;
    private static final DetailAutoAdvance.SurfaceSource HIDDEN_SURFACE = DetailAutoAdvance.hiddenSurface;

    @Before @After public void reset() {
        DetailAutoAdvance.appHidden = APP_HIDDEN;
        DetailAutoAdvance.hiddenSurface = HIDDEN_SURFACE;
        DetailAutoAdvance.queueOffScreen = false;
        PausedProcess.set(false);
        Settings.AUTO_ADVANCE.resetToDefault();
        Settings.AUTO_ADVANCE_LIMIT.resetToDefault();
        Settings.BACKGROUND_PLAY.resetToDefault();
        SessionBudget.clear();
    }

    private static void bothOn() {
        Settings.AUTO_ADVANCE.save(true);
        Settings.BACKGROUND_PLAY.save(true);
    }

    @Test public void aProfilePageGetsAQueueKeyOfItsOwn() {
        bothOn();
        assertEquals(DetailAutoAdvance.PROFILE_SCOPE + "6890", DetailAutoAdvance.backgroundScope(null, "6890", null));
        assertEquals(DetailAutoAdvance.PROFILE_SCOPE + "6890", DetailAutoAdvance.backgroundScope("", "6890", null));
    }

    /** A page opened from a profile's grid names the profile only as the page's user (S22 trace). */
    @Test public void aProfileGridPageGetsAKeyFromItsUser() {
        bothOn();
        assertEquals(DetailAutoAdvance.PROFILE_SCOPE + "7123", DetailAutoAdvance.backgroundScope(null, null, "7123"));
        assertEquals(DetailAutoAdvance.PROFILE_SCOPE + "7123", DetailAutoAdvance.backgroundScope("", "", "7123"));
        // The route that says which profile it came from wins over the page's user.
        assertEquals(DetailAutoAdvance.PROFILE_SCOPE + "6890", DetailAutoAdvance.backgroundScope(null, "6890", "7123"));
        Settings.BACKGROUND_PLAY.save(false);
        assertNull(DetailAutoAdvance.backgroundScope(null, null, "7123"));
    }

    @Test public void tikToksOwnKeyIsKept() {
        bothOn();
        assertEquals("collection-12", DetailAutoAdvance.backgroundScope("collection-12", "6890", null));
    }

    @Test public void aPageFromNoProfileKeepsTikToksAnswer() {
        bothOn();
        assertNull(DetailAutoAdvance.backgroundScope(null, null, null));
        assertEquals("", DetailAutoAdvance.backgroundScope("", "", null));
    }

    @Test public void eitherSwitchOffLeavesTheKeyAlone() {
        Settings.AUTO_ADVANCE.save(true);
        assertNull(DetailAutoAdvance.backgroundScope(null, "6890", null));
        Settings.AUTO_ADVANCE.save(false);
        Settings.BACKGROUND_PLAY.save(true);
        assertNull(DetailAutoAdvance.backgroundScope(null, "6890", null));
    }

    @Test public void tikToksNextOnScreenWaitsForAutoAdvance() {
        assertEquals("collection-12", DetailAutoAdvance.foregroundScope("collection-12"));
        Settings.AUTO_ADVANCE.save(true);
        assertNull(DetailAutoAdvance.foregroundScope("collection-12"));
    }

    @Test public void theQueueIsTurnedOnOnlyWithBothSwitches() {
        assertEquals(0, DetailAutoAdvance.collectionMode(0));
        Settings.AUTO_ADVANCE.save(true);
        assertEquals(0, DetailAutoAdvance.collectionMode(0));
        Settings.BACKGROUND_PLAY.save(true);
        assertEquals(1, DetailAutoAdvance.collectionMode(0));
        // TikTok's mode with the collection bar stays as the server gave it.
        assertEquals(2, DetailAutoAdvance.collectionMode(2));
    }

    @Test public void theLimitEndsTheQueueAtItsCount() {
        bothOn();
        Settings.AUTO_ADVANCE_LIMIT.save(2);
        Object queue = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertFalse(DetailAutoAdvance.backgroundNext(true, queue, "b"));
    }

    @Test public void theSameVideoEndingTwiceCountsOnce() {
        bothOn();
        Settings.AUTO_ADVANCE_LIMIT.save(2);
        Object queue = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertFalse(DetailAutoAdvance.backgroundNext(true, queue, "b"));
    }

    @Test public void eachQueueKeepsItsOwnCount() {
        bothOn();
        Settings.AUTO_ADVANCE_LIMIT.save(2);
        Object first = new Object();
        Object second = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, first, "a"));
        assertTrue(DetailAutoAdvance.backgroundNext(true, second, "a"));
        assertFalse(DetailAutoAdvance.backgroundNext(true, first, "b"));
    }

    @Test public void noLimitKeepsGoing() {
        bothOn();
        Object queue = new Object();
        for (int i = 0; i < 25; i++) assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "v" + i));
    }

    @Test public void theLastVideoStaysTheLast() {
        bothOn();
        assertFalse(DetailAutoAdvance.backgroundNext(false, new Object(), "a"));
    }

    /** TikTok's queue session as 47.9.2 lays it out: obfuscated fields, the scene and the state among them. */
    private static final class Session {
        private final String LIZ = "owner";
        private final ScopedBgPlayScene LIZIZ;
        private ScopedBgPlaySessionState LJFF = ScopedBgPlaySessionState.OFF;

        Session(String scope) {
            LIZIZ = new ScopedBgPlayScene("collection", scope);
        }
    }

    /**
     * #292 on the S22: TikTok skipped a profile page's queue as the app left the screen because
     * its session was never turned on, and the video stopped on Home.
     */
    @Test public void aProfilePagesQueueIsTurnedOnAtTheTrigger() {
        bothOn();
        Session session = new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955");
        DetailAutoAdvance.armProfileQueue(session);
        assertEquals(ScopedBgPlaySessionState.ACTIVE, session.LJFF);
    }

    @Test public void yourOwnCollectionsKeepTikToksSwitch() {
        bothOn();
        Session session = new Session("7301234567");
        DetailAutoAdvance.armProfileQueue(session);
        assertEquals(ScopedBgPlaySessionState.OFF, session.LJFF);
    }

    @Test public void eitherSwitchOffLeavesTheQueueOff() {
        Settings.AUTO_ADVANCE.save(true);
        Session session = new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955");
        DetailAutoAdvance.armProfileQueue(session);
        assertEquals(ScopedBgPlaySessionState.OFF, session.LJFF);
    }

    /** TikTok's own switch can turn the queue off again, and the next trigger leaves it off. */
    @Test public void aQueueTurnedOffAgainStaysOff() {
        bothOn();
        Session session = new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955");
        DetailAutoAdvance.armProfileQueue(session);
        session.LJFF = ScopedBgPlaySessionState.OFF;
        DetailAutoAdvance.armProfileQueue(session);
        assertEquals(ScopedBgPlaySessionState.OFF, session.LJFF);
    }

    @Test public void anEndedQueueIsLeftEnded() {
        bothOn();
        Session session = new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955");
        session.LJFF = ScopedBgPlaySessionState.ENDED;
        DetailAutoAdvance.armProfileQueue(session);
        assertEquals(ScopedBgPlaySessionState.ENDED, session.LJFF);
        DetailAutoAdvance.armProfileQueue(null);
        DetailAutoAdvance.armProfileQueue(new Object());
    }

    private static void hidden(boolean hidden) {
        DetailAutoAdvance.appHidden = () -> hidden;
    }

    /** TikTok's engine, as far as the queue's surface goes. */
    public static final class Engine {
        Surface surface;

        public void setSurface(Surface surface) {
            this.surface = surface;
        }
    }

    /**
     * #292 on the S22: the queue's next video started its sound track and never fed it, since a
     * video's sound waits for its first frame and off the screen nothing draws. Its engine gets a
     * surface nobody sees while the armed queue plays off the screen, and keeps the page's on it.
     */
    @Test public void theQueuesNextVideoGetsASurfaceOffTheScreen() {
        Surface hiddenOne = new Surface(new SurfaceTexture(0));
        DetailAutoAdvance.hiddenSurface = () -> hiddenOne;
        bothOn();
        hidden(true);
        DetailAutoAdvance.armProfileQueue(new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955"));
        Engine engine = new Engine();
        DetailAutoAdvance.playOffScreen(engine);
        assertSame(hiddenOne, engine.surface);
        DetailAutoAdvance.playOffScreen(null);

        hidden(false);
        Engine shown = new Engine();
        DetailAutoAdvance.playOffScreen(shown);
        assertNull(shown.surface);
        // Once back on the screen, a later trip off it waits for the queue to be armed again.
        hidden(true);
        DetailAutoAdvance.playOffScreen(shown);
        assertNull(shown.surface);
    }

    /**
     * #292 on the S22: the video playing as the app left kept going, and the queue's next one sat
     * on its first frame, since TikTok's player waits for a screen to draw on and none comes.
     */
    @Test public void anArmedQueueOffTheScreenStartsItsNextVideo() {
        bothOn();
        hidden(true);
        DetailAutoAdvance.armProfileQueue(new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955"));
        assertTrue(DetailAutoAdvance.playingOffScreen());
        assertTrue(DetailAutoAdvance.playingOffScreen());
    }

    /** Leaving the app again with the queue already on, as a second trip to Home does. */
    @Test public void aQueueAlreadyOnStartsItsNextVideoToo() {
        bothOn();
        hidden(true);
        Session session = new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955");
        session.LJFF = ScopedBgPlaySessionState.ACTIVE;
        DetailAutoAdvance.armProfileQueue(session);
        assertTrue(DetailAutoAdvance.playingOffScreen());
    }

    @Test public void onTheScreenVideosKeepTheirPicture() {
        bothOn();
        DetailAutoAdvance.armProfileQueue(new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955"));
        hidden(false);
        assertFalse(DetailAutoAdvance.playingOffScreen());
        // Back on the screen ends it, until the app leaves with the queue on again.
        hidden(true);
        assertFalse(DetailAutoAdvance.playingOffScreen());
    }

    @Test public void noQueueLeavesThePlayerWaiting() {
        bothOn();
        hidden(true);
        assertFalse(DetailAutoAdvance.playingOffScreen());
        DetailAutoAdvance.armProfileQueue(new Session("7301234567"));
        assertFalse(DetailAutoAdvance.playingOffScreen());
        DetailAutoAdvance.armProfileQueue(null);
        assertFalse(DetailAutoAdvance.playingOffScreen());
    }

    /** Leaving from a page with no profile queue drops the one a trip before left behind. */
    @Test public void leavingFromAnotherPageDropsTheLastQueue() {
        bothOn();
        hidden(true);
        DetailAutoAdvance.armProfileQueue(new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955"));
        DetailAutoAdvance.armProfileQueue(new Session("7301234567"));
        assertFalse(DetailAutoAdvance.playingOffScreen());
    }

    @Test public void eitherSwitchOffLeavesThePlayerWaiting() {
        bothOn();
        hidden(true);
        DetailAutoAdvance.armProfileQueue(new Session(DetailAutoAdvance.PROFILE_SCOPE + "107955"));
        Settings.BACKGROUND_PLAY.save(false);
        assertFalse(DetailAutoAdvance.playingOffScreen());
    }

    @Test public void withAutoAdvanceOffTikToksAnswerGoesThrough() {
        Settings.BACKGROUND_PLAY.save(true);
        Settings.AUTO_ADVANCE_LIMIT.save(1);
        Object queue = new Object();
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "a"));
        assertTrue(DetailAutoAdvance.backgroundNext(true, queue, "b"));
    }
}
