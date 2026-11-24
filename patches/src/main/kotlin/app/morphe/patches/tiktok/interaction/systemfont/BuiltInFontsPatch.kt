/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.systemfont

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile

/** The folder under TikTok's assets the fonts go to, which the extension's BuiltInFonts reads. */
internal const val BUILT_IN_FONTS_ASSETS = "assets/hushfeed_builtin_fonts"

/** Where the fonts sit in the patches' own resources. */
internal const val BUILT_IN_FONTS_RESOURCES = "/builtin-fonts"

/** The fonts' licences, one file beside them. */
internal const val BUILT_IN_FONTS_LICENSES = "LICENSES.txt"

/**
 * The fonts that come with Hushfeed (#487), each kept as `<name>.ttf` under its name in the
 * picker. All are from Google Fonts: Permanent Marker and Special Elite under the Apache License
 * 2.0, the rest under the SIL Open Font License 1.1, with every licence in LICENSES.txt.
 */
internal val BUILT_IN_FONTS = listOf(
    "Abril Fatface", "Anton", "Atkinson Hyperlegible", "Bangers", "Bebas Neue", "Black Ops One",
    "Caveat Brush", "Comic Neue", "Courier Prime", "Creepster", "Gloria Hallelujah", "Indie Flower",
    "Lilita One", "Lobster", "Monoton", "Pacifico", "Patrick Hand", "Permanent Marker", "Poppins",
    "Press Start 2P", "Righteous", "Shadows Into Light", "Share Tech Mono", "Silkscreen",
    "Special Elite", "VT323",
)

/** The files the patch puts in TikTok's assets: every font, then the licences. */
internal fun builtInFontFiles() = BUILT_IN_FONTS.map { "$it.ttf" } + BUILT_IN_FONTS_LICENSES

/**
 * Puts the fonts that come with Hushfeed in TikTok's assets, for Use system font's Pick a
 * built-in font row. The row shows only where these files are there.
 */
internal val builtInFontsPatch = patch {
    execute {
        for (file in builtInFontFiles()) {
            val input = BuiltInFontsSource.open(file)
                ?: throw PatchException("Use system font: the patches have no $file to put in TikTok.")
            val target = resourceFile("$BUILT_IN_FONTS_ASSETS/$file")
            target.parentFile?.mkdirs()
            input.use { from -> target.outputStream().use { from.copyTo(it) } }
        }
    }
}

internal object BuiltInFontsSource {
    fun open(file: String) = BuiltInFontsSource::class.java.getResourceAsStream("$BUILT_IN_FONTS_RESOURCES/$file")
}
