/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Swaps TikTok's own text font for the device's system font (issue #7), or for a font file the
 * reader imported.
 *
 * <p>TikTok draws its interface text in a bundled family, TikTok Sans, built by one font engine
 * (two implementations of one interface, renamed on every build; SystemFontAnchorsTest holds
 * the patch's match to each declared build). Every text
 * typeface the app asks for is produced by that engine's two methods, the variable-font builder
 * and the static asset loader, and this stands at their exit. The engine handles only the
 * TikTok text and display fonts, so the icon glyph fonts, the gift combo font and the
 * mention/hashtag font, which load through other paths, are left alone. TikTok's static
 * typeface factory (the nine-parameter one that picks TikTok Display or TikTok Text by size)
 * and its Compose font loader both build through that engine, so they reach this too.
 *
 * <p>The replacement is returned at the same weight and slant the engine built, so bold stays
 * bold. The weight is read back from the typeface the engine produced rather than trusted from a
 * parameter, so a build that reshuffles the engine cannot feed a stale register in. An imported
 * font with one weight is made bolder by Android where TikTok asks for a heavier one.
 */
public final class SystemFont {
    private static final String HOOK_FAMILY = "system font";

    /** How many faces the engine built this run went out in each family, for the FONT section. */
    private static final AtomicInteger IN_IMPORTED = new AtomicInteger();
    private static final AtomicInteger IN_SYSTEM = new AtomicInteger();
    private static final AtomicInteger IN_TIKTOK = new AtomicInteger();
    private static volatile boolean reporting;

    private SystemFont() {}

    /**
     * The weight and slant the engine was last asked for on this thread, read at its entry, so a
     * face it can't build still comes out at the size of stroke TikTok wanted (#346).
     */
    private static final int REGULAR = 400;
    private static final ThreadLocal<int[]> REQUESTED = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() {
            return new int[] {REGULAR, 0};
        }
    };

    /** The variable-font builder's own weight and slant arguments, taken at its entry. */
    public static void requested(int weight, int italic) {
        int[] request = REQUESTED.get();
        request[0] = weight;
        request[1] = italic;
    }

    /** The static asset loader's file name, taken at its entry: the weight is in the name. */
    public static void requestedAsset(String path) {
        int[] request = REQUESTED.get();
        request[0] = weightNamed(path);
        request[1] = path != null && path.toLowerCase(java.util.Locale.ROOT).contains("italic") ? 1 : 0;
    }

    /**
     * The replacement at {@code original}'s weight and slant while a switch asks for one,
     * otherwise {@code original} unchanged. A null original is the engine's "no typeface"
     * answer, which is every answer on an install patched from the split bundle: TikTok's own
     * fonts ship in a feature split the bundle leaves out, so the engine finds no file (#346).
     * The replacement still goes out then, at the weight and slant the engine was asked for, and
     * with both switches off the null goes back unchanged.
     */
    public static Typeface systemize(Typeface original) {
        Typeface family = replacementFamily();
        // Reported whichever way the switches are set, so an export separates "patch not present"
        // from "present and off" from "present and swapping". The FONT section says which font.
        HookStatus.bound(HOOK_FAMILY, family == null ? "tiktok" : family == Typeface.DEFAULT ? "system" : "imported");
        report(family);
        if (family == null) return original;
        try {
            if (original == null) {
                int[] request = REQUESTED.get();
                return restyled(family, request[0], request[1] != 0);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                return Typeface.create(family, original.getWeight(), original.isItalic());
            }
            return Typeface.create(family, original.getStyle());
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "systemize", failure);
            return original;
        }
    }

    private static Typeface restyled(Typeface family, int weight, boolean italic) {
        int clamped = weight < 1 || weight > 1000 ? REGULAR : weight;
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(family, clamped, italic);
        int style = clamped >= 600 ? Typeface.BOLD : Typeface.NORMAL;
        return Typeface.create(family, italic ? style | Typeface.ITALIC : style);
    }

    /** The weight a TikTok font file's name stands for: TikTok-Text-Bold.otf is 700. */
    static int weightNamed(String path) {
        if (path == null) return REGULAR;
        String name = path.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("semibold") || name.contains("demibold")) return 600;
        if (name.contains("extrabold") || name.contains("black") || name.contains("heavy")) return 900;
        if (name.contains("bold")) return 700;
        if (name.contains("medium")) return 500;
        if (name.contains("light")) return 300;
        return REGULAR;
    }

    /**
     * The one place that decides what TikTok's text is drawn in: the family to restyle into, or
     * null to keep TikTok's own. An imported font wins while Use an imported font is on and the
     * file loads. With that switch off, or the file gone or unreadable, the answer is what Use
     * system font alone would give: the device font while it's on, TikTok's own while it's off.
     */
    static Typeface replacementFamily() {
        if (Settings.IMPORTED_FONT.get()) {
            Typeface imported = ImportedFont.typeface();
            if (imported != null) return imported;
        }
        return Settings.SYSTEM_FONT.get() ? Typeface.DEFAULT : null;
    }

    /** Counts one face toward the FONT section, which goes in the export from the first one. */
    private static void report(Typeface family) {
        (family == null ? IN_TIKTOK : family == Typeface.DEFAULT ? IN_SYSTEM : IN_IMPORTED).incrementAndGet();
        if (reporting) return;
        try {
            reporting = true;
            LogBufferManager.registerReportSection(Report.INSTANCE);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not add the font section to the export", failure);
        }
    }

    /**
     * The FONT section of the diagnostic report: both switches, the imported file and whether it
     * loaded, which family the faces built this run went out in, and Samsung's own font style,
     * which swaps fonts under the app on Galaxy phones (#346).
     */
    static final class Report implements LogBufferManager.ReportSection {
        static final Report INSTANCE = new Report();

        @Override public String title() {
            return "FONT";
        }

        @Override public List<String> lines() {
            List<String> lines = new ArrayList<>();
            boolean system = Settings.SYSTEM_FONT.savedValue();
            boolean imported = Settings.IMPORTED_FONT.savedValue();
            lines.add("Use system font: " + (system ? "on" : "off"));
            lines.add("Use an imported font: " + (imported ? "on" : "off"));
            if ((system || imported) && !Settings.SYSTEM_FONT.get() && !Settings.IMPORTED_FONT.get()) {
                lines.add("Hushfeed is paused, so TikTok keeps its own font");
            }
            Context context = Utils.getContext();
            File file = ImportedFont.file(context);
            lines.add("Imported file: " + (file == null ? "none" : file.getName() + ", " + file.length() + " bytes"));
            lines.add("Imported face: " + ImportedFont.outcome());
            lines.add("Faces built this run: " + IN_IMPORTED.get() + " in the imported font, "
                    + IN_SYSTEM.get() + " in the system font, " + IN_TIKTOK.get() + " in TikTok's own");
            String samsungStyle = samsungFontStyle(context);
            if (samsungStyle != null) lines.add("Samsung font style: " + samsungStyle);
            return lines;
        }
    }

    /**
     * Samsung's font style index, 0 for the phone's default, or null off a Galaxy phone or where
     * it can't be read.
     */
    private static String samsungFontStyle(Context context) {
        if (context == null) return null;
        try {
            // Samsung's Settings app writes it to the global table (One UI 8); the system one reads null.
            return android.provider.Settings.Global.getString(context.getContentResolver(), "flip_font_style");
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    static void resetReportForTests() {
        IN_IMPORTED.set(0);
        IN_SYSTEM.set(0);
        IN_TIKTOK.set(0);
        REQUESTED.remove();
    }
}
