/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * The fonts that come with Hushfeed (#487). The Use system font patch puts them in TikTok's
 * assets, one file a font named for it, like {@code Press Start 2P.ttf}, with their licences
 * beside them. Picking one copies it in as the imported font ({@link ImportedFont}), so it draws
 * through the same hook as a file the reader imports and is removed the same way.
 */
public final class BuiltInFonts {
    /** The folder under TikTok's assets the patch writes the fonts to. */
    public static final String FOLDER = "hushfeed_builtin_fonts";
    static final String EXTENSION = ".ttf";

    /** Where the font files are read from: TikTok's assets, or a test's stand-in. */
    public interface Source {
        String[] list(Context context) throws IOException;

        InputStream open(Context context, String file) throws IOException;

        Typeface face(Context context, String file);
    }

    static final Source ASSETS = new Source() {
        @Override public String[] list(Context context) throws IOException {
            return context.getAssets().list(FOLDER);
        }

        @Override public InputStream open(Context context, String file) throws IOException {
            return context.getAssets().open(FOLDER + "/" + file, AssetManager.ACCESS_STREAMING);
        }

        @Override public Typeface face(Context context, String file) {
            return Typeface.createFromAsset(context.getAssets(), FOLDER + "/" + file);
        }
    };

    static volatile Source source = ASSETS;

    /** Each preview face, loaded once a process. A font that didn't load is kept as null. */
    private static final Map<String, Typeface> FACES = new HashMap<>();

    private BuiltInFonts() {
    }

    /** The built-in fonts' names, A to Z, or none where the patch put no fonts in. */
    public static List<String> names(Context context) {
        List<String> out = new ArrayList<>();
        try {
            String[] files = source.list(context);
            if (files == null) return out;
            for (String file : files) {
                if (file.toLowerCase(Locale.ROOT).endsWith(EXTENSION) && file.length() > EXTENSION.length()) {
                    out.add(file.substring(0, file.length() - EXTENSION.length()));
                }
            }
        } catch (IOException | RuntimeException failure) {
            Logger.printInfo(() -> "Could not list the built-in fonts: " + failure);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The font's file name, which is also the name it's kept under once picked. */
    public static String fileName(String name) {
        return name + EXTENSION;
    }

    /** The font file, for copying it in as the imported font. */
    public static InputStream open(Context context, String name) throws IOException {
        return source.open(context, fileName(name));
    }

    /** The font's face for its preview, or null when it can't be loaded. */
    public static Typeface face(Context context, String name) {
        synchronized (FACES) {
            if (FACES.containsKey(name)) return FACES.get(name);
        }
        Typeface face = null;
        try {
            face = source.face(context, fileName(name));
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not load the built-in font " + name + ": " + failure);
        }
        synchronized (FACES) {
            FACES.put(name, face);
        }
        return face;
    }

    public static void useForTests(Source stand) {
        resetForTests();
        source = stand;
    }

    public static void resetForTests() {
        source = ASSETS;
        synchronized (FACES) {
            FACES.clear();
        }
    }
}
