/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.systemfont

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fonts that come with Hushfeed (#487): every font the patch lists is in the patches'
 * resources as a TrueType file the extension can read by its name, nothing unlisted rides along,
 * every one has its licence in LICENSES.txt, and they go to the asset folder the extension's
 * BuiltInFonts.FOLDER names.
 */
class BuiltInFontsPatchTest {
    @Test
    fun `every listed font ships as a TrueType file with its licence`() {
        assertTrue("20 to 50 fonts", BUILT_IN_FONTS.size in 20..50)
        assertEquals("no font listed twice", BUILT_IN_FONTS.size, BUILT_IN_FONTS.toSet().size)
        assertEquals(BUILT_IN_FONTS.sortedWith(String.CASE_INSENSITIVE_ORDER), BUILT_IN_FONTS)
        var total = 0L
        for (name in BUILT_IN_FONTS) {
            val bytes = BuiltInFontsSource.open("$name.ttf")?.use { it.readBytes() }
            assertNotNull("$name.ttf is missing from the patches", bytes)
            val head = bytes!!.copyOf(4)
            assertTrue(
                "$name.ttf isn't a TrueType file",
                head.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(head, Charsets.ISO_8859_1) == "true",
            )
            total += bytes.size
        }
        assertTrue("the fonts stay small: $total bytes", total < 4L * 1024 * 1024)

        val licences = BuiltInFontsSource.open(BUILT_IN_FONTS_LICENSES)?.use { String(it.readBytes(), Charsets.UTF_8) }
        assertNotNull(licences)
        for (name in BUILT_IN_FONTS) {
            assertTrue("$name has no licence", licences!!.contains("\n$name.ttf (from "))
        }
        assertTrue(licences!!.contains("SIL OPEN FONT LICENSE Version 1.1") && licences.contains("Apache License"))
        assertEquals(BUILT_IN_FONTS.map { "$it.ttf" } + "LICENSES.txt", builtInFontFiles())
    }

    @Test
    fun `nothing unlisted rides along`() {
        val folder = BuiltInFontsSource::class.java.getResource(BUILT_IN_FONTS_RESOURCES)
        assertNotNull(folder)
        val shipped = File(folder!!.toURI()).list()!!.sorted()
        assertEquals(builtInFontFiles().sorted(), shipped)
        assertEquals("assets/hushfeed_builtin_fonts", BUILT_IN_FONTS_ASSETS)
    }
}
