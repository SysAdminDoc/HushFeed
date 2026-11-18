/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.os.Build;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Use an imported emoji font (#458).
 *
 * <p>TikTok's own fonts carry no emoji, so an emoji falls through a text face to the device's
 * emoji font. This puts the imported font on that path: each face the font engine hands out
 * becomes the same text font, then the emoji font, then the device's fonts. Text keeps the font it
 * had, whichever of TikTok's, the system's or an imported one that is, and only what the text font
 * doesn't draw reaches the emoji font. The emoji font can't simply go first, because emoji fonts
 * also hold digits and a few symbols.
 *
 * <p>A fallback chain is built from font files rather than from faces, and Android gives no way
 * from a face back to its file. From Android 12 shaping one letter names the font it used, which
 * covers TikTok's font and the system's alike, so the switch needs Android 12.
 */
public final class EmojiFont {
    /** The Android level the switch and its rows appear on. */
    public static final int MIN_SDK = 31;

    /** Builds the chain. Android's own, except in tests, which have no real fonts to build from. */
    interface Stacker {
        Typeface stack(Typeface text, File importedText, File emoji) throws IOException;
    }

    static volatile Stacker stacker;

    /** Each text face and its chain. The engine rebuilds few faces, so this stays small. */
    private static final Map<Typeface, Typeface> STACKED = new WeakHashMap<>();
    /** Why the chain couldn't be built, which leaves every face as it was for this run. */
    private static volatile String failure;

    private EmojiFont() {}

    /** Whether emoji should be drawn in the imported font this run. */
    public static boolean active() {
        return Build.VERSION.SDK_INT >= MIN_SDK
                && Settings.IMPORTED_EMOJI_FONT.get()
                && failure == null
                && ImportedFont.EMOJI.face() != null;
    }

    /**
     * {@code text} with the emoji font behind it, or {@code text} itself if that can't be done.
     * {@code importedText} is the imported text font's file when {@code text} is drawn from it.
     * Never throws: it runs inside TikTok's font engine.
     */
    static Typeface over(Typeface text, File importedText) {
        try {
            synchronized (STACKED) {
                Typeface stacked = STACKED.get(text);
                if (stacked != null) return stacked;
                Context context = Utils.getContext();
                File emoji = context == null ? null : ImportedFont.EMOJI.fontFile(context);
                if (emoji == null) return text;
                Stacker using = stacker;
                if (using == null) stacker = using = new Platform();
                stacked = using.stack(text, importedText, emoji);
                STACKED.put(text, stacked);
                return stacked;
            }
        } catch (Throwable problem) {
            failure = String.valueOf(problem);
            Logger.printException(() -> "Could not put the imported emoji font behind the text", problem);
            return text;
        }
    }

    /** What became of the emoji font this run, for the diagnostic export. */
    static String outcome() {
        if (Build.VERSION.SDK_INT < MIN_SDK) return "needs Android 12";
        return failure != null ? "couldn't be used: " + failure : ImportedFont.EMOJI.lastOutcome();
    }

    static void resetForTests() {
        synchronized (STACKED) {
            STACKED.clear();
        }
        stacker = null;
        failure = null;
        ImportedFont.EMOJI.drop();
    }

    /** Android's fallback builder. Kept apart so older phones never load what it names. */
    private static final class Platform implements Stacker {
        private FontFamily emojiFamily;
        private FontFamily importedFamily;

        @Override public Typeface stack(Typeface text, File importedText, File emoji) throws IOException {
            if (emojiFamily == null) emojiFamily = familyOf(new Font.Builder(emoji).build());
            FontFamily first;
            if (importedText == null) {
                first = familyOf(fontDrawing(text));
            } else {
                if (importedFamily == null) importedFamily = familyOf(new Font.Builder(importedText).build());
                first = importedFamily;
            }
            int slant = text.isItalic() ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT;
            return new Typeface.CustomFallbackBuilder(first)
                    .addCustomFallback(emojiFamily)
                    .setSystemFallback("sans-serif")
                    .setStyle(new FontStyle(text.getWeight(), slant))
                    .build();
        }

        private static FontFamily familyOf(Font font) {
            return new FontFamily.Builder(font).build();
        }

        /** The font this face draws a Latin letter in, which is the face's own first font. */
        private static Font fontDrawing(Typeface text) throws IOException {
            Paint paint = new Paint();
            paint.setTypeface(text);
            paint.setTextSize(32f);
            PositionedGlyphs glyphs = TextRunShaper.shapeTextRun("a", 0, 1, 0, 1, 0f, 0f, false, paint);
            if (glyphs.glyphCount() == 0) throw new IOException("The text face drew nothing");
            return glyphs.getFont(0);
        }
    }
}
