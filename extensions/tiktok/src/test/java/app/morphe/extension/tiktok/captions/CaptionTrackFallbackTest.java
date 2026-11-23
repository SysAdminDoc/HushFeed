/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.captions;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #217: with the Captions row back, every video toasted that it had no captions. TikTok shows a
 * video's captions only from the track whose language id is the original caption language's. While
 * Keep captions available is on, a video whose tracks miss that match gets one of them, and every
 * other case is TikTok's own answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CaptionTrackFallbackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** CaptionLanguage as 47.9.3 has it. */
    public static final class Language {
        public final long languageId;
        public final String languageCode;
        Language(long languageId, String languageCode) {
            this.languageId = languageId;
            this.languageCode = languageCode;
        }
    }

    /** CaptionItemModel as 47.9.3 has it. */
    public static final class Track {
        public final long languageId;
        public final String languageCode;
        public final boolean isOriginalCaption;
        public final String url;
        public final List<String> urlList;
        Track(long languageId, String languageCode, boolean original, String url, String... mirrors) {
            this.languageId = languageId;
            this.languageCode = languageCode;
            this.isOriginalCaption = original;
            this.url = url;
            this.urlList = new ArrayList<>(Arrays.asList(mirrors));
        }
    }

    public static final class Model {
        public final List<Object> captionList;
        public final Language originalCaptionLanguage;
        Model(Language original, Object... tracks) {
            this.originalCaptionLanguage = original;
            this.captionList = new ArrayList<>(Arrays.asList(tracks));
        }
    }

    public static final class Video {
        private final Model model;
        Video(Model model) { this.model = model; }
        public Object getCaptionModel() { return model; }
    }

    public static final class Aweme {
        private final Video video;
        Aweme(Model model) { this.video = new Video(model); }
        public Object getVideo() { return video; }
    }

    private static final Language INDONESIAN = new Language(31, "id");

    @Before public void setUp() {
        Settings.KEEP_CAPTIONS_AVAILABLE.save(true);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.KEEP_CAPTIONS_AVAILABLE.resetToDefault();
        HookStatus.clear();
    }

    @Test public void tikToksOwnMatchIsLeftToTikTok() {
        Track english = new Track(2, "en", false, "https://v16.example/en.srt");
        Track own = new Track(31, "id", true, "https://v16.example/id.srt");
        assertNull(CaptionsAvailable.originalTrack(new Aweme(new Model(INDONESIAN, english, own))));
    }

    @Test public void aTrackMarkedOriginalIsShownFirst() {
        Track english = new Track(2, "en", false, "https://v16.example/en.srt");
        Track marked = new Track(44, "in", true, null, "https://v19.example/in.srt");
        assertSame(marked, CaptionsAvailable.originalTrack(new Aweme(new Model(INDONESIAN, english, marked))));
    }

    @Test public void thenATrackInTheOriginalLanguagesCode() {
        Track english = new Track(2, "en", false, "https://v16.example/en.srt");
        Track sameCode = new Track(44, "ID", false, "https://v16.example/id.srt");
        assertSame(sameCode, CaptionsAvailable.originalTrack(new Aweme(new Model(INDONESIAN, english, sameCode))));
    }

    @Test public void thenTheFirstTrackThatHasSomewhereToLoadFrom() {
        Track nowhere = new Track(2, "en", true, " ");
        Track spanish = new Track(3, "es", false, "https://v16.example/es.srt");
        assertSame(spanish, CaptionsAvailable.originalTrack(new Aweme(new Model(INDONESIAN, nowhere, spanish))));
        // No original language at all: TikTok's lookup answers nothing, so a track still shows.
        assertSame(spanish, CaptionsAvailable.originalTrack(new Aweme(new Model(null, spanish))));
    }

    @Test public void aVideoWithNoTracksStaysWithoutCaptions() {
        assertNull(CaptionsAvailable.originalTrack(new Aweme(new Model(INDONESIAN))));
        assertNull(CaptionsAvailable.originalTrack(new Aweme(new Model(INDONESIAN, new Track(2, "en", false, null)))));
        assertNull(CaptionsAvailable.originalTrack(new Aweme(null)));
        assertNull(CaptionsAvailable.originalTrack(null));
    }

    @Test public void withTheSwitchOffOrPausedEveryAnswerIsTikToks() {
        Aweme video = new Aweme(new Model(INDONESIAN, new Track(2, "en", false, "https://v16.example/en.srt")));
        Settings.KEEP_CAPTIONS_AVAILABLE.save(false);
        assertNull(CaptionsAvailable.originalTrack(video));
        Settings.KEEP_CAPTIONS_AVAILABLE.save(true);
        PausedProcess.set(true);
        assertNull(CaptionsAvailable.originalTrack(video));
        PausedProcess.set(false);
        Utils.setContext(null);
        assertNull(CaptionsAvailable.originalTrack(video));
    }
}
