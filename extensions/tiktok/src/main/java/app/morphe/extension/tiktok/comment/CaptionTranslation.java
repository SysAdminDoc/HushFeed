/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.editpost.ClientText;
import com.ss.android.ugc.aweme.editpost.ClientTextWrapper;
import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * The caption row at the top of a video's comments, in the language the feed shows it in (#488).
 *
 * <p>The feed caption follows TikTok's own translation: Always translate, and the video's See
 * translation and See original. The caption row in the comments draws the video's caption as
 * posted and never asks. With either caption switch on, the row asks the way the feed does: if
 * TikTok shows this video's caption translated and already holds that translation, the row is
 * bound to a copy of the video carrying the translated text and the translation's own hashtag and
 * mention spans. The video itself is never changed, and nothing outside TikTok is asked.
 */
public final class CaptionTranslation {
    /** TikTok's translation protocols: a plain string, and a ClientText with its own spans. */
    static final int PLAIN_TEXT = 0;
    static final int CLIENT_TEXT = 2;

    /** TikTok's translation state, read through the bridges the patch writes. */
    interface Source {
        /** Whether TikTok shows this video's caption translated. */
        boolean showsTranslated(Object aweme);

        /** The language TikTok translates into. */
        String language();

        /** The cached rich translation's ClientTextWrapper, or null. */
        Object richWrapper(Object aweme, String language);

        /** The cached rich translation's plain text, or null. */
        String richText(Object aweme, String language);

        /** The plain description repo's cached translation, or null. */
        String plainText(Object aweme, String language);
    }

    /** A caption's translated text and the spans that go with it. */
    static final class Translation {
        final String text;
        final List<?> spans;

        Translation(String text, List<?> spans) {
            this.text = text;
            this.spans = spans;
        }
    }

    private static final Source NATIVE = new Source() {
        @Override public boolean showsTranslated(Object aweme) {
            return nativeShowsTranslated(aweme);
        }

        @Override public String language() {
            return nativeLanguage();
        }

        @Override public Object richWrapper(Object aweme, String language) {
            return nativeRichWrapper(aweme, language);
        }

        @Override public String richText(Object aweme, String language) {
            return nativeRichText(aweme, language);
        }

        @Override public String plainText(Object aweme, String language) {
            return nativePlainText(aweme, language);
        }
    };

    static volatile Source source = NATIVE;

    private CaptionTranslation() {
    }

    /**
     * The video the caption row binds. Either the video itself or, while TikTok shows its caption
     * translated, a copy carrying that translation.
     */
    public static Object captionAweme(Object aweme) {
        HookStatus.bound(CaptionComments.FAMILY, "caption row bind");
        if (!enabled() || !(aweme instanceof Aweme)) return aweme;
        try {
            Translation translation = translationOf(aweme, source);
            if (translation == null) return aweme;
            Aweme copy = ((Aweme) aweme).clone();
            copy.setDesc(translation.text);
            copy.setTextExtra(new ArrayList<>(translation.spans));
            HookStatus.bound(CaptionComments.FAMILY, "caption row translated");
            return copy;
        } catch (RuntimeException | LinkageError failure) {
            HookStatus.threw(CaptionComments.FAMILY, "caption row translation", failure);
            Logger.printException(() -> "Could not translate the caption row", failure);
            return aweme;
        }
    }

    static boolean enabled() {
        return Settings.CAPTION_OPENS_COMMENTS.get() || Settings.CAPTION_ABOVE_COMMENTS.get();
    }

    /**
     * The translation TikTok shows for this video's caption, read the way its feed caption reads
     * it: the rich translation first, as a ClientText or as plain text by its protocol, then the
     * plain description repo. Null when TikTok shows the caption as posted or holds no translation.
     */
    static Translation translationOf(Object aweme, Source source) {
        if (!source.showsTranslated(aweme)) return null;
        String language = source.language();
        if (language == null || language.isEmpty()) return null;
        Object wrapper = source.richWrapper(aweme, language);
        if (wrapper instanceof ClientTextWrapper) {
            ClientTextWrapper rich = (ClientTextWrapper) wrapper;
            if (rich.translationProtocol == CLIENT_TEXT) {
                ClientText text = rich.clientText;
                if (text != null && notEmpty(text.markupText)) {
                    return new Translation(text.markupText, text.textExtra == null ? new ArrayList<>() : text.textExtra);
                }
            } else if (rich.translationProtocol == PLAIN_TEXT) {
                String text = source.richText(aweme, language);
                if (notEmpty(text)) return new Translation(text, new ArrayList<>());
            }
        }
        String plain = source.plainText(aweme, language);
        return notEmpty(plain) ? new Translation(plain, new ArrayList<>()) : null;
    }

    private static boolean notEmpty(String text) {
        return text != null && !text.trim().isEmpty();
    }

    // The patch rewrites each bridge to read TikTok's own state. Their bodies here answer as if
    // TikTok held no translation, which is what an unpatched run sees.

    static boolean nativeShowsTranslated(Object aweme) {
        return false;
    }

    static String nativeLanguage() {
        return null;
    }

    static Object nativeRichWrapper(Object aweme, String language) {
        return null;
    }

    static String nativeRichText(Object aweme, String language) {
        return null;
    }

    static String nativePlainText(Object aweme, String language) {
        return null;
    }

    static void resetForTests() {
        source = NATIVE;
    }
}
