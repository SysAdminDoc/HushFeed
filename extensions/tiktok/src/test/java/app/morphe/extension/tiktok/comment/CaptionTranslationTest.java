package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.editpost.ClientText;
import com.ss.android.ugc.aweme.editpost.ClientTextWrapper;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CaptionTranslationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A video's caption as the row reads it, copied the way TikTok's own clone copies it. */
    static final class Video extends Aweme {
        final String id;
        String desc;
        List textExtra;

        Video(String id, String desc, List textExtra) {
            this.id = id;
            this.desc = desc;
            this.textExtra = textExtra;
        }

        @Override public String getAid() { return id; }
        @Override public String getDesc() { return desc; }
        @Override public void setDesc(String desc) { this.desc = desc; }
        @Override public List getTextExtra() { return textExtra; }
        @Override public void setTextExtra(List textExtra) { this.textExtra = textExtra; }
        @Override public Aweme clone() { return new Video(id, desc, textExtra); }
    }

    /** TikTok's translation state for one video, as the bridges would read it. */
    static class Tiktok implements CaptionTranslation.Source {
        boolean translated;
        String language = "en";
        Object wrapper;
        String richText;
        String plainText;
        int asked;

        @Override public boolean showsTranslated(Object aweme) {
            asked++;
            return translated;
        }

        @Override public String language() { return language; }
        @Override public Object richWrapper(Object aweme, String language) { return this.language.equals(language) ? wrapper : null; }
        @Override public String richText(Object aweme, String language) { return this.language.equals(language) ? richText : null; }
        @Override public String plainText(Object aweme, String language) { return this.language.equals(language) ? plainText : null; }
    }

    private Tiktok tiktok;

    @Before public void setUp() {
        reset();
        tiktok = new Tiktok();
        CaptionTranslation.source = tiktok;
    }

    @After public void tearDown() {
        reset();
    }

    private static void reset() {
        Settings.CAPTION_OPENS_COMMENTS.resetToDefault();
        Settings.CAPTION_ABOVE_COMMENTS.resetToDefault();
        CaptionTranslation.resetForTests();
        HookStatus.clear();
    }

    private static Video posted(String id) {
        return new Video(id, "Hola #gatos", new ArrayList<>(Collections.singletonList("span over #gatos")));
    }

    private static ClientTextWrapper rich(String text, List spans) {
        ClientText clientText = new ClientText();
        clientText.markupText = text;
        clientText.textExtra = spans;
        return new ClientTextWrapper(CaptionTranslation.CLIENT_TEXT, clientText);
    }

    @Test public void withBothSwitchesOffTheRowKeepsTikToksCaption() {
        tiktok.translated = true;
        tiktok.wrapper = rich("Hello #cats", Collections.singletonList("span over #cats"));
        Video video = posted("withBothSwitchesOffTheRowKeepsTikToksCaption");
        assertSame(video, CaptionTranslation.captionAweme(video));
        assertEquals("TikTok isn't even asked", 0, tiktok.asked);
    }

    @Test public void aTranslatedCaptionComesWithTheTranslationsOwnSpans() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        tiktok.translated = true;
        tiktok.wrapper = rich("Hello #cats", Collections.singletonList("span over #cats"));
        Video video = posted("aTranslatedCaptionComesWithTheTranslationsOwnSpans");
        Aweme row = (Aweme) CaptionTranslation.captionAweme(video);
        assertNotSame("the row gets a copy", video, row);
        assertEquals("Hello #cats", row.getDesc());
        assertEquals(Collections.singletonList("span over #cats"), row.getTextExtra());
        assertEquals("the same video", video.getAid(), row.getAid());
        assertEquals("and the video itself keeps its caption", "Hola #gatos", video.getDesc());
        assertEquals(Collections.singletonList("span over #gatos"), video.getTextExtra());
    }

    @Test public void aPlainTranslationDropsSpansThatPointIntoTheOriginal() {
        Settings.CAPTION_ABOVE_COMMENTS.save(true);
        tiktok.translated = true;
        ClientText none = new ClientText();
        tiktok.wrapper = new ClientTextWrapper(CaptionTranslation.PLAIN_TEXT, none);
        tiktok.richText = "Hello #cats";
        Aweme row = (Aweme) CaptionTranslation.captionAweme(posted("aPlainTranslationDropsSpansThatPointIntoTheOriginal"));
        assertEquals("Hello #cats", row.getDesc());
        assertTrue("no spans to misplace", row.getTextExtra().isEmpty());
    }

    @Test public void theDescriptionReposPlainTranslationIsTheFallback() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        tiktok.translated = true;
        tiktok.wrapper = rich("  ", null);
        tiktok.plainText = "Hello #cats";
        Aweme row = (Aweme) CaptionTranslation.captionAweme(posted("theDescriptionReposPlainTranslationIsTheFallback"));
        assertEquals("an empty rich text isn't a translation", "Hello #cats", row.getDesc());
        assertTrue(row.getTextExtra().isEmpty());
    }

    @Test public void aRichTranslationWithNoSpansStillReplacesTheOriginalOnes() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        tiktok.translated = true;
        tiktok.wrapper = rich("Hello cats", null);
        Aweme row = (Aweme) CaptionTranslation.captionAweme(posted("aRichTranslationWithNoSpansStillReplacesTheOriginalOnes"));
        assertEquals("Hello cats", row.getDesc());
        assertTrue(row.getTextExtra().isEmpty());
    }

    @Test public void seeOriginalAndAnUncachedTranslationKeepTheCaption() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        tiktok.wrapper = rich("Hello #cats", Collections.singletonList("span over #cats"));
        tiktok.plainText = "Hello #cats";
        Video original = posted("seeOriginalAndAnUncachedTranslationKeepTheCaption-original");
        assertSame("TikTok shows this one as posted", original, CaptionTranslation.captionAweme(original));

        tiktok.translated = true;
        tiktok.wrapper = null;
        tiktok.plainText = null;
        Video uncached = posted("seeOriginalAndAnUncachedTranslationKeepTheCaption-uncached");
        assertSame("nothing translated yet", uncached, CaptionTranslation.captionAweme(uncached));

        tiktok.plainText = "Hello #cats";
        tiktok.language = "";
        assertSame("no target language", uncached, CaptionTranslation.captionAweme(uncached));
    }

    @Test public void anotherLanguagesTranslationIsntTaken() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        tiktok.translated = true;
        tiktok.wrapper = rich("Hello #cats", Collections.singletonList("span over #cats"));
        CaptionTranslation.Translation found = CaptionTranslation.translationOf(posted("anotherLanguagesTranslationIsntTaken"),
                new CaptionTranslation.Source() {
                    @Override public boolean showsTranslated(Object aweme) { return true; }
                    @Override public String language() { return "fr"; }
                    @Override public Object richWrapper(Object aweme, String language) { return tiktok.richWrapper(aweme, language); }
                    @Override public String richText(Object aweme, String language) { return tiktok.richText(aweme, language); }
                    @Override public String plainText(Object aweme, String language) { return tiktok.plainText(aweme, language); }
                });
        assertEquals("the cache is read for TikTok's target language only", null, found);
    }

    @Test public void aFailingReadLeavesTheCaptionAndIsReported() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        CaptionTranslation.source = new Tiktok() {
            @Override public boolean showsTranslated(Object aweme) {
                throw new IllegalStateException("not ready");
            }
        };
        Video video = posted("aFailingReadLeavesTheCaptionAndIsReported");
        assertSame(video, CaptionTranslation.captionAweme(video));
        assertFalse(HookStatus.missing(CaptionComments.FAMILY).isEmpty());
    }

    @Test public void unpatchedBridgesHoldNoTranslation() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        CaptionTranslation.resetForTests();
        Video video = posted("unpatchedBridgesHoldNoTranslation");
        assertSame(video, CaptionTranslation.captionAweme(video));
        assertEquals(Arrays.asList(), HookStatus.missing(CaptionComments.FAMILY));
    }
}
