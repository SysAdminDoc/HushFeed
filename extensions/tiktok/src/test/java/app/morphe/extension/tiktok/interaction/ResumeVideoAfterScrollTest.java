package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Which feeds hand back a stored position, and when a finished video stops holding one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ResumeVideoAfterScrollTest {
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.RESUME_VIDEO_AFTER_SCROLL.resetToDefault();
    }

    @After
    public void tearDown() {
        Settings.RESUME_VIDEO_AFTER_SCROLL.resetToDefault();
    }

    @Test
    public void everyFeedResumesOnceTheSettingIsOn() {
        // TikTok answers this for itself from FeedPlayProgressContinueConfig.event_type_list,
        // which on 46.2.3 holds homepage_hot and five page types and neither Following nor
        // Friends. The position is stored for those tabs all the same, so the switch reads as
        // doing nothing there. false is what the app answers for them.
        Settings.RESUME_VIDEO_AFTER_SCROLL.save(true);
        assertTrue(ResumeVideoAfterScrollPatch.allowResumeInThisFeed(false));
        assertTrue(ResumeVideoAfterScrollPatch.allowResumeInThisFeed(true));
    }

    @Test
    public void withTheSettingOffTheAppKeepsItsOwnAnswer() {
        Settings.RESUME_VIDEO_AFTER_SCROLL.save(false);
        assertFalse(ResumeVideoAfterScrollPatch.allowResumeInThisFeed(false));
        assertTrue(ResumeVideoAfterScrollPatch.allowResumeInThisFeed(true));
    }

    @Test
    public void aFinishedVideoStopsHoldingAPosition() {
        Settings.RESUME_VIDEO_AFTER_SCROLL.save(true);
        assertTrue(ResumeVideoAfterScrollPatch.shouldClearCompletedProgress(15_000, 15_000));
        assertTrue(ResumeVideoAfterScrollPatch.shouldClearCompletedProgress(15_100, 15_000));
        assertFalse(ResumeVideoAfterScrollPatch.shouldClearCompletedProgress(14_999, 15_000));
        // A duration TikTok has not worked out yet is not a video that finished at zero.
        assertFalse(ResumeVideoAfterScrollPatch.shouldClearCompletedProgress(0, 0));

        Settings.RESUME_VIDEO_AFTER_SCROLL.save(false);
        assertFalse(ResumeVideoAfterScrollPatch.shouldClearCompletedProgress(15_000, 15_000));
    }

    @Test
    public void aPausedPineDramaEpisodeCarriesOnWhereItIs() {
        // PineDrama's unpause asked for the episode's start and got the series' start point back,
        // so the episode played over (#156). 0 is PineDrama's own answer when the store has none.
        // The switch doesn't decide it: the reporter saw it with the switch off.
        for (boolean on : new boolean[] {false, true}) {
            Settings.RESUME_VIDEO_AFTER_SCROLL.save(on);
            assertEquals(0, ResumeVideoAfterScrollPatch.seriesResumeStart(41_000, 0, "resumePlay", 1));
        }
    }

    @Test
    public void everyOtherPineDramaStartKeepsTheSeriesPoint() {
        // Play all into an episode, a stopped player and a new surface keep PineDrama's start.
        assertEquals(41_000, ResumeVideoAfterScrollPatch.seriesResumeStart(41_000, 0, "tryPlay", 0));
        assertEquals(41_000, ResumeVideoAfterScrollPatch.seriesResumeStart(41_000, 0, "resumePlay", 0));
        assertEquals(41_000, ResumeVideoAfterScrollPatch.seriesResumeStart(41_000, 0, "onTextureAvailable", 1));
        assertEquals(0, ResumeVideoAfterScrollPatch.seriesResumeStart(0, 0, "resumePlay", 1));
        assertEquals(-1, ResumeVideoAfterScrollPatch.seriesResumeStart(-1, -1, "resumePlay", 1));
    }
}
