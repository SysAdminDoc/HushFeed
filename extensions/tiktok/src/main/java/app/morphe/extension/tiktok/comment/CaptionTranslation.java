/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.editpost.ClientText;
import com.ss.android.ugc.aweme.editpost.ClientTextWrapper;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.model.TextExtraStruct;

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
            // A caption with line breaks also comes as its lines, and the row shows those once
            // it's expanded, so the copy drops them and expands into the translation instead.
            copy.setContentDesc(null);
            copy.setContentDescExtra(null);
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
                    return fromMarkup(text.markupText, text.textExtra == null ? new ArrayList<>() : text.textExtra);
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

    /** A markup tag: {@code <h id="288">}, {@code </h>} or {@code <br/>}. */
    private static final Pattern TAG = Pattern.compile(
            "<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[a-zA-Z_][\\w-]*\\s*=\\s*\"[^\"]*\")*)\\s*(/?)>");
    private static final Pattern ID = Pattern.compile("\\bid\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ENTITY = Pattern.compile("&(lt|gt|amp|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);");

    /**
     * A rich translation as TikTok's feed draws it (#488). Its markup tags each words of the text
     * ({@code <h id="288">#xh</h>} for a hashtag, {@code m} for a mention, {@code br} for a line
     * break), and each span's {@code tagId} names the tag it sits on. The text keeps the tagged
     * words without their tags, and every span with a tag in it gets a copy placed on that tag's
     * words; TikTok's cached spans are never changed. Text with no tags at all is kept as it came.
     */
    static Translation fromMarkup(String markup, List<?> spans) {
        Matcher tag = TAG.matcher(markup);
        if (!tag.find()) return new Translation(markup, spans);
        tag.reset();
        StringBuilder text = new StringBuilder(markup.length());
        Map<String, int[]> ranges = new HashMap<>();
        Deque<String[]> open = new ArrayDeque<>();
        int from = 0;
        while (tag.find()) {
            text.append(unescape(markup.substring(from, tag.start())));
            from = tag.end();
            String name = tag.group(2);
            if ("br".equalsIgnoreCase(name)) {
                text.append('\n');
            } else if (tag.group(1).isEmpty()) {
                if (!tag.group(4).isEmpty()) continue;
                Matcher id = ID.matcher(tag.group(3));
                open.push(new String[]{name, id.find() ? id.group(1) : null, String.valueOf(text.length())});
            } else {
                while (!open.isEmpty()) {
                    String[] started = open.pop();
                    if (!started[0].equals(name)) continue;
                    if (started[1] != null) {
                        ranges.put(started[1], new int[]{Integer.parseInt(started[2]), text.length()});
                    }
                    break;
                }
            }
        }
        text.append(unescape(markup.substring(from)));
        List<Object> placed = new ArrayList<>();
        for (Object span : spans) {
            if (!(span instanceof TextExtraStruct)) continue;
            TextExtraStruct own = (TextExtraStruct) span;
            int[] range = own.tagId == null ? null : ranges.get(own.tagId);
            if (range == null) continue;
            TextExtraStruct copy = own.clone();
            copy.start = range[0];
            copy.end = range[1];
            placed.add(copy);
        }
        return new Translation(text.toString(), placed);
    }

    private static String unescape(String text) {
        if (text.indexOf('&') < 0) return text;
        Matcher entity = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (entity.find()) {
            out.append(text, from, entity.start());
            String name = entity.group(1);
            switch (name) {
                case "lt": out.append('<'); break;
                case "gt": out.append('>'); break;
                case "amp": out.append('&'); break;
                case "quot": out.append('"'); break;
                case "apos": out.append('\''); break;
                default:
                    int code = name.startsWith("#x") ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                    if (Character.isValidCodePoint(code)) out.appendCodePoint(code); else out.append(entity.group());
            }
            from = entity.end();
        }
        return out.append(text, from, text.length()).toString();
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
