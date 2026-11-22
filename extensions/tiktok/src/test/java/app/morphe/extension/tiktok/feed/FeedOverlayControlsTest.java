package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class FeedOverlayControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    boolean originalFullscreen, originalLocation;

    @Before public void prepare() {
        originalFullscreen=Settings.HIDE_FULLSCREEN_BUTTON.get();
        originalLocation=Settings.HIDE_LOCATION_LABELS.get();
        Settings.HIDE_FULLSCREEN_BUTTON.save(true);
        Settings.HIDE_LOCATION_LABELS.save(true);
        FeedOverlayControls.resolveForTests("view_rootview",1001);
        FeedOverlayControls.resolveForTests("feed_multi_tag_layout",1002);
    }
    @After public void restore() {
        Settings.HIDE_FULLSCREEN_BUTTON.save(originalFullscreen);
        Settings.HIDE_LOCATION_LABELS.save(originalLocation);
    }

    /** Issue #21: TikTok's regional Report button gate is closed only by its own switch. */
    @Test public void theReportButtonGateIsLeftAloneUntilItsSwitchIsOn() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_FEED_REPORT_BUTTON.defaultValue);
            assertFalse("the Full screen and location switches closed the Report gate",
                    FeedOverlayControls.shouldHideReportButton());
            Settings.HIDE_FEED_REPORT_BUTTON.save(true);
            assertTrue(FeedOverlayControls.shouldHideReportButton());
        } finally {
            Settings.HIDE_FEED_REPORT_BUTTON.resetToDefault();
        }
    }

    /**
     * The Footnotes banner's gate is closed only by its own switch, is left to TikTok while
     * Hushfeed is paused, and says it ran so the build report can tell a bound hook from a
     * missing one.
     */
    @Test public void theFootnotesGateIsClosedOnlyByItsOwnSwitchAndHonoursPause() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_FOOTNOTES.defaultValue);
            Settings.HIDE_FEED_REPORT_BUTTON.save(true);
            Settings.HIDE_FEED_SURVEYS.save(true);
            assertFalse("another overlay switch closed the Footnotes gate",
                    FeedOverlayControls.shouldHideFootnotes());
            Settings.HIDE_FEED_REPORT_BUTTON.save(false);
            Settings.HIDE_FEED_SURVEYS.save(false);
            Settings.HIDE_FOOTNOTES.save(true);
            assertTrue(FeedOverlayControls.shouldHideFootnotes());
            assertFalse("the Footnotes switch closed the Report gate",
                    FeedOverlayControls.shouldHideReportButton());
            PausedProcess.set(true);
            assertFalse("a paused Hushfeed still closed the gate", FeedOverlayControls.shouldHideFootnotes());
            PausedProcess.set(false);
            assertTrue(String.join(" ", HookStatus.report()).contains("footnote banner"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_FOOTNOTES.resetToDefault();
            Settings.HIDE_FEED_REPORT_BUTTON.resetToDefault();
            Settings.HIDE_FEED_SURVEYS.resetToDefault();
        }
    }

    /** #462: the people you may know tag goes only with its own switch, and Pause brings it back. */
    @Test public void theMayKnowTagGateIsClosedOnlyByItsOwnSwitchAndHonoursPause() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_MAY_KNOW_TAG.defaultValue);
            Settings.HIDE_FOOTNOTES.save(true);
            assertFalse("another overlay switch closed the gate", FeedOverlayControls.shouldHideMayKnowTag());
            Settings.HIDE_FOOTNOTES.save(false);
            Settings.HIDE_MAY_KNOW_TAG.save(true);
            assertTrue(FeedOverlayControls.shouldHideMayKnowTag());
            assertFalse("this switch closed the Footnotes gate", FeedOverlayControls.shouldHideFootnotes());
            PausedProcess.set(true);
            assertFalse("a paused Hushfeed still closed the gate", FeedOverlayControls.shouldHideMayKnowTag());
            PausedProcess.set(false);
            assertTrue(String.join(" ", HookStatus.report()).contains("may know tag"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_MAY_KNOW_TAG.resetToDefault();
            Settings.HIDE_FOOTNOTES.resetToDefault();
        }
    }

    /**
     * #489: the pause icon's alpha goes to zero only with its own switch, TikTok's alpha comes
     * back untouched otherwise, and Pause brings the icon back. The switch is read on every
     * pause, so it needs no restart.
     */
    @Test public void thePauseIconFadesToNothingOnlyWithItsOwnSwitchAndHonoursPause() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_PAUSE_ICON.defaultValue);
            assertFalse(Settings.HIDE_PAUSE_ICON.rebootApp);
            Settings.HIDE_MAY_KNOW_TAG.save(true);
            assertEquals("another overlay switch hid the icon", 0.5f, FeedOverlayControls.pauseIconAlpha(0.5f), 0f);
            assertEquals(0.3f, FeedOverlayControls.pauseIconAlpha(0.3f), 0f);
            Settings.HIDE_MAY_KNOW_TAG.save(false);
            Settings.HIDE_PAUSE_ICON.save(true);
            assertEquals(0f, FeedOverlayControls.pauseIconAlpha(0.5f), 0f);
            assertEquals(0f, FeedOverlayControls.pauseIconAlpha(1f), 0f);
            assertFalse("this switch closed the may know gate", FeedOverlayControls.shouldHideMayKnowTag());
            PausedProcess.set(true);
            assertEquals("a paused Hushfeed still hid the icon", 0.3f, FeedOverlayControls.pauseIconAlpha(0.3f), 0f);
            PausedProcess.set(false);
            assertTrue(String.join(" ", HookStatus.report()).contains("pause icon"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_PAUSE_ICON.resetToDefault();
            Settings.HIDE_MAY_KNOW_TAG.resetToDefault();
        }
    }

    /**
     * Issue #262: the Following tab's LIVE row is kept folded only by its own switch. A fold
     * TikTok asked for stays a fold, and Pause hands TikTok's answer back.
     */
    @Test public void theFollowingLiveRowIsFoldedOnlyByItsOwnSwitchAndHonoursPause() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_FOLLOWING_LIVE_ROW.defaultValue);
            Settings.HIDE_FOOTNOTES.save(true);
            assertTrue("another overlay switch folded the row", FeedOverlayControls.skylightShown(true));
            assertFalse(FeedOverlayControls.skylightShown(false));
            Settings.HIDE_FOOTNOTES.save(false);
            Settings.HIDE_FOLLOWING_LIVE_ROW.save(true);
            assertFalse(FeedOverlayControls.skylightShown(true));
            assertFalse(FeedOverlayControls.skylightShown(false));
            PausedProcess.set(true);
            assertTrue("a paused Hushfeed still folded the row", FeedOverlayControls.skylightShown(true));
            PausedProcess.set(false);
            assertTrue(String.join(" ", HookStatus.report()).contains("following live row"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_FOLLOWING_LIVE_ROW.resetToDefault();
            Settings.HIDE_FOOTNOTES.resetToDefault();
        }
    }

    /** Issue #293: a report names the kinds of component a video was given, each kind once. */
    @Test public void eachFeedComponentKindIsNamedInTheReportOnce() {
        try {
            HookStatus.clear();
            FeedOverlayControls.feedComponentBuilt("issue293_component");
            FeedOverlayControls.feedComponentBuilt("issue293_component");
            FeedOverlayControls.feedComponentBuilt(null);
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains("feed component issue293_component: 1 found"));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * Issue #439: with Hide surveys on, the interest buttons went to a renderer the button-group
     * hook never sees. Every component naming interest feedback, in its type or its container
     * type, gets an empty container type back, which the factory skips. Other components, the
     * switch off and Pause all leave TikTok's name as it was.
     */
    @Test public void theInterestButtonsAreSkippedOnEveryRenderer() {
        try {
            HookStatus.clear();
            assertEquals("hybrid", FeedOverlayControls.feedComponent("interest_feedback_button_group", "hybrid"));
            Settings.HIDE_FEED_SURVEYS.save(true);
            assertEquals("", FeedOverlayControls.feedComponent("interest_feedback_button_group", "hybrid"));
            assertEquals("", FeedOverlayControls.feedComponent("Interest_Feedback_Card", "ugen"));
            assertEquals("", FeedOverlayControls.feedComponent(null, "interest_feedback_button_group"));
            assertEquals("", FeedOverlayControls.feedComponent("issue439_type", "interest_feedback_button_group"));
            assertEquals("another component was skipped", "feed_cta", FeedOverlayControls.feedComponent("feed_cta", "feed_cta"));
            assertEquals("a template that isn't the buttons was skipped", "hybrid", FeedOverlayControls.feedComponent("issue439_card", "hybrid"));
            assertNull(FeedOverlayControls.feedComponent("interest_feedback_button_group", null));
            PausedProcess.set(true);
            assertEquals("a paused Hushfeed still skipped the buttons", "hybrid",
                    FeedOverlayControls.feedComponent("interest_feedback_button_group", "hybrid"));
            PausedProcess.set(false);
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains("feed component hybrid (interest_feedback_button_group): 1 found"));
            assertTrue(report, report.contains("feed component hybrid (issue439_card): 1 found"));
            assertTrue(report, report.contains("interest buttons"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_FEED_SURVEYS.resetToDefault();
        }
    }

    /**
     * Issue #484: a search for "wildwood" opened on a brand zone, the "Ad" banner TikTok builds
     * from a hybrid template with the advertiser's videos and a Follow button. Its search card
     * carries no ad field, so only the component factory sees it. With Remove feed ads on, a
     * component whose type or container type names a brand zone gets an empty container type
     * back, which the factory skips. The switch off, Pause and other search components leave
     * TikTok's name as it was.
     */
    @Test public void searchBrandZonesAreSkippedWithFeedAds() {
        try {
            HookStatus.clear();
            FeedFilterCounters.clear();
            Settings.REMOVE_ADS.save(false);
            assertEquals("the switch off still skipped the brand zone", "hybrid",
                    FeedOverlayControls.feedComponent("search_regular_brand_zone", "hybrid"));
            Settings.REMOVE_ADS.save(true);
            assertEquals("", FeedOverlayControls.feedComponent("search_regular_brand_zone", "hybrid"));
            assertEquals("", FeedOverlayControls.feedComponent("Search_Premium_Brand_Zone", "ugen"));
            assertEquals("", FeedOverlayControls.feedComponent(null, "search_brand_zone"));
            assertEquals("a search component that isn't an ad was skipped", "hybrid",
                    FeedOverlayControls.feedComponent("search_regular_user_card", "hybrid"));
            assertNull(FeedOverlayControls.feedComponent("search_regular_brand_zone", null));
            PausedProcess.set(true);
            assertEquals("a paused Hushfeed still skipped the brand zone", "hybrid",
                    FeedOverlayControls.feedComponent("search_regular_brand_zone", "hybrid"));
            PausedProcess.set(false);
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains("search brand zone"));
            String counters = String.join("\n", FeedFilterCounters.report());
            assertTrue(counters, counters.contains("SearchBrandZone"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            FeedFilterCounters.clear();
            Settings.REMOVE_ADS.resetToDefault();
        }
    }

    /**
     * Issue #293: the Not interested and Interested buttons go with Hide surveys. Another
     * component type stays unmatched whatever the switch says, and Pause hands TikTok's match back.
     */
    @Test public void theInterestButtonsGoWithSurveysAndHonourPause() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_FEED_SURVEYS.defaultValue);
            Settings.HIDE_FOOTNOTES.save(true);
            assertTrue("another overlay switch dropped the buttons", FeedOverlayControls.interestButtonsShown(1));
            assertFalse(FeedOverlayControls.interestButtonsShown(0));
            Settings.HIDE_FOOTNOTES.save(false);
            Settings.HIDE_FEED_SURVEYS.save(true);
            assertFalse(FeedOverlayControls.interestButtonsShown(1));
            assertFalse("another component turned into the button group", FeedOverlayControls.interestButtonsShown(0));
            PausedProcess.set(true);
            assertTrue("a paused Hushfeed still dropped the buttons", FeedOverlayControls.interestButtonsShown(1));
            PausedProcess.set(false);
            assertTrue(String.join(" ", HookStatus.report()).contains("interest buttons"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_FEED_SURVEYS.resetToDefault();
            Settings.HIDE_FOOTNOTES.resetToDefault();
        }
    }

    /** Issue #319: a video TikTok gives no chip stays that way, and Pause hands TikTok's answer back. */
    @Test public void theRepostChipGoesWithItsOwnSwitchAndHonoursPause() {
        try {
            assertEquals(Boolean.FALSE, Settings.HIDE_REPOST_CHIP.defaultValue);
            Settings.HIDE_FEED_SURVEYS.save(true);
            assertTrue("another overlay switch dropped the chip", FeedOverlayControls.repostChipShown(1));
            assertFalse(FeedOverlayControls.repostChipShown(0));
            Settings.HIDE_FEED_SURVEYS.save(false);
            Settings.HIDE_REPOST_CHIP.save(true);
            assertFalse(FeedOverlayControls.repostChipShown(1));
            assertFalse("a video with no chip got one", FeedOverlayControls.repostChipShown(0));
            PausedProcess.set(true);
            assertTrue("a paused Hushfeed still dropped the chip", FeedOverlayControls.repostChipShown(1));
            PausedProcess.set(false);
            assertTrue(String.join(" ", HookStatus.report()).contains("repost chip"));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_REPOST_CHIP.resetToDefault();
            Settings.HIDE_FEED_SURVEYS.resetToDefault();
        }
    }

    /** The prompt to repost reaches the same bar without going through the chip's question. */
    @Test public void theRepostBarStaysOffWithTheChipsSwitchAndHonoursPause() {
        try {
            assertEquals(View.VISIBLE, FeedOverlayControls.repostBarVisibility(View.VISIBLE));
            Settings.HIDE_REPOST_CHIP.save(true);
            assertEquals(View.GONE, FeedOverlayControls.repostBarVisibility(View.VISIBLE));
            assertEquals("TikTok taking the bar down still does", View.GONE, FeedOverlayControls.repostBarVisibility(View.GONE));
            assertEquals(View.INVISIBLE, FeedOverlayControls.repostBarVisibility(View.INVISIBLE));
            PausedProcess.set(true);
            assertEquals("a paused Hushfeed still kept the bar off", View.VISIBLE, FeedOverlayControls.repostBarVisibility(View.VISIBLE));
        } finally {
            PausedProcess.set(false);
            HookStatus.clear();
            Settings.HIDE_REPOST_CHIP.resetToDefault();
        }
    }

    @Test public void fullscreenOnlyHidesItsDedicatedRootAndKeepsNativeClickOnRestore() {
        try(Fixture f=new Fixture()) {
            int[] clicks={0};
            f.fullscreen.setOnClickListener(v -> clicks[0]++);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            assertEquals(View.GONE,f.fullscreen.getVisibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,f.fullscreen.getImportantForAccessibility());
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.other.getVisibility());
            assertEquals(500,f.cell.getLayoutParams().height);
            Settings.HIDE_FULLSCREEN_BUTTON.save(false); f.layout();
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,f.fullscreen.getImportantForAccessibility());
            f.fullscreen.performClick(); assertEquals(1,clicks[0]);
        }
    }

    @Test public void locationOnlyHidesTheCardAndItsExclusiveWrapperWithoutLeavingHeight() {
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.GONE,f.card.getVisibility());
            assertEquals(View.GONE,f.tags.getVisibility());
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.VISIBLE,f.other.getVisibility());
            Settings.HIDE_LOCATION_LABELS.save(false); f.layout();
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.tags.getVisibility());
        }
    }

    @Test public void mixedTagsAreNeverHiddenAndANewSiblingRestoresTheSharedWrapper() {
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.GONE,f.tags.getVisibility());
            View otherTag=new View(f.activity);
            f.tags.addView(otherTag);
            f.layout();
            assertEquals(View.VISIBLE,f.tags.getVisibility());
            assertEquals(View.VISIBLE,otherTag.getVisibility());
            assertEquals(View.GONE,f.card.getVisibility());
        }
        try(Fixture f=new Fixture()) {
            f.tags.addView(new View(f.activity));
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.VISIBLE,f.tags.getVisibility());
            assertEquals(View.GONE,f.card.getVisibility());
        }
    }

    @Test public void outsideFeedAndUnknownLayoutsFailOpen() {
        try(Fixture f=new Fixture()) {
            f.cell.setId(77);
            FeedOverlayControls.bindLocation(f.card);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.VISIBLE,f.tags.getVisibility());
        }
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.resolveForTests("view_rootview",0);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
        }
    }

    @Test public void nativeReappearanceIsHiddenAgainAndDetachRestoresForReuse() {
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindLocation(f.card); idle();
            f.card.setVisibility(View.VISIBLE); f.layout();
            assertEquals(View.GONE,f.card.getVisibility());
            f.tags.removeView(f.card);
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(View.VISIBLE,f.tags.getVisibility());
            f.tags.addView(f.card); idle();
            assertEquals(View.GONE,f.card.getVisibility());
            f.tags.removeView(f.card);
            FrameLayout outside=new FrameLayout(f.activity);
            f.activity.setContentView(outside); outside.addView(f.card); idle();
            assertEquals(View.VISIBLE,f.card.getVisibility());
        }
    }

    @Test public void offIsANoopAndOriginalInvisibleOrGoneStatesArePreserved() {
        Settings.HIDE_FULLSCREEN_BUTTON.save(false);
        Settings.HIDE_LOCATION_LABELS.save(false);
        try(Fixture f=new Fixture()) {
            FeedOverlayControls.bindFullscreen(f.fullscreen);
            FeedOverlayControls.bindLocation(f.card); idle();
            assertEquals(View.VISIBLE,f.fullscreen.getVisibility());
            assertEquals(View.VISIBLE,f.card.getVisibility());
            assertEquals(Boolean.FALSE,Settings.HIDE_FULLSCREEN_BUTTON.defaultValue);
            assertEquals(Boolean.FALSE,Settings.HIDE_LOCATION_LABELS.defaultValue);
        }
        Settings.HIDE_FULLSCREEN_BUTTON.save(true);
        for(int visibility:new int[]{View.INVISIBLE,View.GONE}) try(Fixture f=new Fixture()) {
            f.fullscreen.setVisibility(visibility);
            FeedOverlayControls.bindFullscreen(f.fullscreen); idle();
            Settings.HIDE_FULLSCREEN_BUTTON.save(false); f.layout();
            assertEquals(visibility,f.fullscreen.getVisibility());
            Settings.HIDE_FULLSCREEN_BUTTON.save(true);
        }
    }

    private static void idle(){shadowOf(Looper.getMainLooper()).idle();}
    private static final class Fixture implements AutoCloseable {
        final ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible();
        final Activity activity=owner.get();
        final FrameLayout cell=new FrameLayout(activity), tags=new FrameLayout(activity), card=new FrameLayout(activity);
        final View fullscreen=new View(activity),other=new View(activity);
        Fixture(){
            cell.setId(1001);tags.setId(1002);
            FrameLayout content=new FrameLayout(activity);
            content.addView(cell,new FrameLayout.LayoutParams(400,500));
            cell.addView(fullscreen,new FrameLayout.LayoutParams(150,40));
            cell.addView(tags,new FrameLayout.LayoutParams(300,60));
            cell.addView(other,new FrameLayout.LayoutParams(40,40));
            tags.addView(card,new FrameLayout.LayoutParams(300,60));
            activity.setContentView(content); idle();
        }
        void layout(){cell.getViewTreeObserver().dispatchOnGlobalLayout();idle();}
        @Override public void close(){owner.close();}
    }
}
