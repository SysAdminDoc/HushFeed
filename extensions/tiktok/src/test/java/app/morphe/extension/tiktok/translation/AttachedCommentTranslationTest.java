package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Auto translate on TikTok's newer comment path (#282): the cell's translation controller is asked
 * to translate once it is switched on, only for a foreign comment nobody flipped by hand.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AttachedCommentTranslationTest {
    private boolean savedSwitch;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        savedSwitch = Settings.COMMENT_BATCH_TRANSLATION.get();
        Settings.COMMENT_BATCH_TRANSLATION.save(true);
    }

    @After public void tearDown() {
        Settings.COMMENT_BATCH_TRANSLATION.save(savedSwitch);
        Settings.COMMENT_TRANSLATION_TURNED_OFF.save(false);
        Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.save(false);
    }

    @Test public void aForeignCommentIsTranslatedOnceAndNotAskedAgainWhenItScrollsBackIn() {
        Controller controller = new Controller(new Comment("zh", false));

        CommentBatchTranslator.onTranslationControllerAttached(controller);
        CommentBatchTranslator.onTranslationControllerAttached(controller);

        assertEquals(1, controller.translates);
    }

    @Test public void seeOriginalSticks() {
        Controller controller = new Controller(new Comment("zh", false));
        controller.override = Override.OVERRIDE_TO_ORIGINAL;

        CommentBatchTranslator.onTranslationControllerAttached(controller);

        assertEquals(0, controller.translates);
    }

    @Test public void anAlreadyTranslatedCommentIsLeftAlone() {
        Controller controller = new Controller(new Comment("zh", true));

        CommentBatchTranslator.onTranslationControllerAttached(controller);

        assertEquals(0, controller.translates);
    }

    @Test public void aCommentInTheViewersLanguageOrWithNoLanguageIsLeftAlone() {
        Controller same = new Controller(new Comment("en", false));
        Controller unknown = new Controller(new Comment("", false));

        CommentBatchTranslator.onTranslationControllerAttached(same);
        CommentBatchTranslator.onTranslationControllerAttached(unknown);

        assertEquals(0, same.translates);
        assertEquals(0, unknown.translates);
    }

    @Test public void nothingHappensWithTheSwitchOff() {
        Settings.COMMENT_BATCH_TRANSLATION.save(false);
        Controller controller = new Controller(new Comment("zh", false));

        CommentBatchTranslator.onTranslationControllerAttached(controller);

        assertEquals(0, controller.translates);
    }

    @Test public void nothingHappensOnceTheReaderTurnedTranslationOff() {
        // The header's toggle off resets every cell to no override, so the override can't say it.
        CommentTranslateButton.onToggleWritten(Boolean.FALSE, null);
        Controller controller = new Controller(new Comment("zh", false));

        CommentBatchTranslator.onTranslationControllerAttached(controller);
        assertEquals(0, controller.translates);

        CommentTranslateButton.onToggleWritten(Boolean.TRUE, null);
        CommentBatchTranslator.onTranslationControllerAttached(controller);
        assertEquals("turned back on, it translates again", 1, controller.translates);
    }

    @Test public void aControllerWithoutAnOverrideStatusIsNeverAsked() {
        // Without the override there is no telling whether the viewer chose See original.
        ControllerWithoutOverride controller = new ControllerWithoutOverride(new Comment("zh", false));

        CommentBatchTranslator.onTranslationControllerAttached(controller);
        CommentBatchTranslator.onTranslationControllerAttached(null);
        CommentBatchTranslator.onTranslationControllerAttached(new Controller(null));

        assertEquals(0, controller.translates);
    }

    enum Override { NOT_OVERWRITTEN, OVERRIDE_TO_TRANSLATED, OVERRIDE_TO_ORIGINAL }

    public static final class Comment {
        private final String language;
        private final boolean translated;

        Comment(String language, boolean translated) {
            this.language = language;
            this.translated = translated;
        }

        public String getCid() { return "cid-" + language; }
        public String getCommentLanguage() { return language; }
        public boolean isTranslated() { return translated; }
    }

    /** TikTok's controller in the shape the extension reads: a tag, the comment, an override and translate(). */
    public static final class Controller {
        private final String tag = "OverallCommentTranslationStateMachine";
        private final Comment comment;
        Override override = Override.NOT_OVERWRITTEN;
        int translates;

        Controller(Comment comment) {
            this.comment = comment;
        }

        public final Override status() { return override; }

        /** Like TikTok's, a translate marks the comment translated by hand. */
        public void translate() {
            translates++;
            override = Override.OVERRIDE_TO_TRANSLATED;
        }
    }

    public static final class ControllerWithoutOverride {
        private final Comment comment;
        int translates;

        ControllerWithoutOverride(Comment comment) {
            this.comment = comment;
        }

        public void translate() { translates++; }
    }
}
