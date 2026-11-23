/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.captions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.CaptionCues;
import app.morphe.extension.tiktok.feedfilter.KeywordRules;
import app.morphe.extension.tiktok.settings.Settings;

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
 * Skip promo lines (#493): a caption line holding a word from Promo words is skipped, the way
 * SponsorBlock skips a sponsor read, with an Undo that goes back and lets it play. No captions,
 * no match, the switch off or an empty list all mean the video plays as usual.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PromoSkipTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long LENGTH = 60_000;
    private static final Object CONTROLLER = new Object();

    private final Map<String, List<CaptionCues.Cue>> captions = new HashMap<>();
    private final List<Long> seeks = new ArrayList<>();
    private final List<String> banners = new ArrayList<>();
    private Runnable undo;
    private int fetches;

    @Before public void setUp() {
        PromoSkip.resetForTests();
        PromoSkip.source = id -> {
            fetches++;
            return captions.getOrDefault(id, new ArrayList<>());
        };
        PromoSkip.seeker = (controller, target, length) -> {
            assertEquals(CONTROLLER, controller);
            assertEquals(LENGTH, length);
            seeks.add(target);
            return true;
        };
        PromoSkip.banner = (message, action) -> {
            banners.add(message);
            undo = action;
        };
        Settings.SKIP_PROMO_LINES.save(true);
        Settings.SKIP_PROMO_WORDS.save("casino, 1win, promo code");
        captions.put("v1", List.of(
            new CaptionCues.Cue(0, 4_000, "Hey everyone, today we cook"),
            new CaptionCues.Cue(10_000, 13_000, "Download 1WIN now and"),
            new CaptionCues.Cue(13_200, 16_000, "use my Promo\nCode for a bonus"),
            new CaptionCues.Cue(16_000, 20_000, "Back to the pasta")));
    }

    @After public void tearDown() {
        PromoSkip.resetForTests();
        PausedProcess.set(false);
        Settings.SKIP_PROMO_LINES.resetToDefault();
        Settings.SKIP_PROMO_WORDS.resetToDefault();
    }

    private static void tick(String id, long position) throws Exception {
        PromoSkip.onProgress(CONTROLLER, id, position, LENGTH);
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void matchingLinesThatNearlyTouchAreOneStretch() {
        List<PromoSkip.Span> spans = PromoSkip.spans(captions.get("v1"), KeywordRules.parse("casino, 1win, promo code"));
        assertEquals(1, spans.size());
        assertEquals(10_000, spans.get(0).startMs);
        assertEquals(16_000, spans.get(0).endMs);
        assertEquals("1win", spans.get(0).rule);

        List<CaptionCues.Cue> apart = List.of(
            new CaptionCues.Cue(1_000, 2_000, "casino"),
            new CaptionCues.Cue(5_000, 6_000, "plain"),
            new CaptionCues.Cue(8_000, 9_000, "CASINO again"));
        assertEquals(2, PromoSkip.spans(apart, KeywordRules.parse("casino")).size());
        assertTrue(PromoSkip.spans(apart, KeywordRules.parse("")).isEmpty());
        assertEquals(1, PromoSkip.spans(apart, KeywordRules.parse("\"casino\" !& \"again\"")).size());
    }

    @Test public void aTickInsideAPromoSkipsToItsEndAndUndoGoesBack() throws Exception {
        tick("v1", 1_000);
        tick("v1", 2_000);
        assertTrue("nothing to skip before the promo", seeks.isEmpty());

        tick("v1", 10_300);
        assertEquals(List.of(16_000L), seeks);
        assertEquals(List.of("Skipped a promo line"), banners);

        undo.run();
        assertEquals(List.of(16_000L, 10_000L), seeks);
        tick("v1", 10_100);
        tick("v1", 12_000);
        assertEquals("Undo lets that stretch play", List.of(16_000L, 10_000L), seeks);
        assertEquals("the captions were read once", 1, fetches);
    }

    @Test public void aVideoWithoutCaptionsOrMatchesPlaysAsUsual() throws Exception {
        captions.put("plain", List.of(new CaptionCues.Cue(0, 5_000, "Just a dog")));
        tick("none", 100);
        tick("none", 3_000);
        tick("plain", 100);
        tick("plain", 3_000);
        assertTrue(seeks.isEmpty());
        assertTrue(banners.isEmpty());
    }

    @Test public void theSwitchOffAnEmptyListOrAPauseLeavesEveryVideoAlone() throws Exception {
        Settings.SKIP_PROMO_LINES.save(false);
        tick("v1", 1_000);
        tick("v1", 11_000);
        Settings.SKIP_PROMO_LINES.save(true);
        Settings.SKIP_PROMO_WORDS.save(" ");
        tick("v1", 1_000);
        tick("v1", 11_000);
        Settings.SKIP_PROMO_WORDS.save("casino");
        PausedProcess.set(true);
        tick("v1", 1_000);
        tick("v1", 11_000);
        assertTrue(seeks.isEmpty());
        assertEquals("nothing was fetched either", 0, fetches);
    }

    @Test public void aChangedListLooksAgain() throws Exception {
        Settings.SKIP_PROMO_WORDS.save("pasta");
        tick("v1", 1_000);
        tick("v1", 11_000);
        assertTrue(seeks.isEmpty());
        tick("v1", 17_000);
        assertEquals(List.of(20_000L), seeks);

        Settings.SKIP_PROMO_WORDS.save("casino");
        tick("v1", 17_500);
        tick("v1", 18_000);
        assertEquals(List.of(20_000L), seeks);
    }

    @Test public void theNextVideoGetsItsOwnLines() throws Exception {
        tick("v1", 1_000);
        tick("v2", 1_000);
        tick("v2", 11_000);
        assertTrue("v1's promo isn't in v2", seeks.isEmpty());
        assertEquals(2, fetches);
    }

    @Test public void theDownloadsSrtReadsBackAsCues() throws Exception {
        List<CaptionCues.Cue> cues = CaptionCues.parse(
            "1\n00:00:01,500 --> 00:00:03,000\nUse promo code\nHUSH10\n\n2\n01:00:00,000 --> 01:00:02,250\nBye\n\n");
        assertEquals(2, cues.size());
        assertEquals(1_500, cues.get(0).startMs);
        assertEquals(3_000, cues.get(0).endMs);
        assertEquals("Use promo code\nHUSH10", cues.get(0).text);
        assertEquals(3_600_000, cues.get(1).startMs);
        assertEquals(3_602_250, cues.get(1).endMs);
        assertTrue(CaptionCues.parse("not captions").isEmpty());
        assertTrue(CaptionCues.parse(null).isEmpty());
    }

    @Test public void aTickWithNothingToGoOnIsIgnored() throws Exception {
        PromoSkip.onProgress(null, "v1", 11_000, LENGTH);
        PromoSkip.onProgress(CONTROLLER, null, 11_000, LENGTH);
        PromoSkip.onProgress(CONTROLLER, "v1", 11_000, 0);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(0, fetches);
        assertNull(undo);
    }
}
