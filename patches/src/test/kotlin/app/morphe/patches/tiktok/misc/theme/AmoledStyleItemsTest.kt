package app.morphe.patches.tiktok.misc.theme

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The sheet fills live in style literals, not in colors.xml, and only the dark ones move. */
class AmoledStyleItemsTest {
    @Test
    fun `dark sheet literals take the colour and the light and translucent ones stay`() {
        val styles = parse(
            """
            <resources>
              <style name="a9z"><item name="agv">#ff282828</item><item name="c7">#FF252525</item></style>
              <style name="zb"><item name="agv">#ff1e1e1e</item><item name="c7">#ffffffff</item></style>
              <style name="z6"><item name="dy">#1effffff</item><item name="agv">?attr/aut</item></style>
              <style name="a_0"><item name="agv">#1d2026</item></style>
              <style name="zf"><item name="ail">#ff1e1e1e</item><item name="axl">#ffffffff</item></style>
              <style name="zl"><item name="air">#ff1e1e1e</item><item name="ait">#ff2c2c2c</item><item name="agy">#ff1e1e1e</item></style>
              <style name="zd"><item name="c1">#ff121212</item></style>
              <style name="zo"><item name="c1">#ffffffff</item><item name="axx">#fff5f5f5</item><item name="avy">#ffffffff</item></style>
              <style name="a_d"><item name="agy">#1a1e24</item></style>
            </resources>
            """.trimIndent(),
        )

        val changed = rewriteDarkStyleItems(styles, surfaceStyleItems("48.0.2"), "#000000")

        assertEquals(setOf("agv", "c7", "ail", "air", "ait", "c1", "agy"), changed)
        assertEquals(
            listOf(
                "#000000", "#000000", "#000000", "#ffffffff", "#1effffff", "?attr/aut", "#000000", "#000000", "#ffffffff",
                // The long-press panel's fill and its cards (ait) go black. The settings pages'
                // cards (agy) go black in every dark theme, and light stays white.
                "#000000", "#000000", "#000000", "#000000", "#ffffffff", "#fff5f5f5", "#ffffffff", "#000000",
            ),
            values(styles),
        )
    }

    /**
     * A picked color reached the sheets and cards but left Inbox and profiles black, since the
     * pages' own tokens are #000000 in the dark themes and weren't rewritten (#348).
     */
    @Test
    fun `a picked color reaches the pages, black ones included, and light pages stay`() {
        val styles = parse(
            """
            <resources>
              <style name="zo"><item name="agq">#ff000000</item><item name="agw">#ff000000</item><item name="ags">#ff1e1e1e</item></style>
              <style name="a_k"><item name="agq">#ff0c0e12</item><item name="agw">#ff0c0e12</item><item name="ags">#ff16191d</item></style>
              <style name="a_b"><item name="agr">#ff1e1e1e</item><item name="agx">#ff1e1e1e</item></style>
              <style name="z6"><item name="aw2">#ffffffff</item><item name="aw8">#fff3f5f6</item><item name="aw4">#fff7f8f9</item></style>
            </resources>
            """.trimIndent(),
        )

        val changed = rewriteDarkStyleItems(styles, surfaceStyleItems("48.0.2"), "#181825")

        assertEquals(setOf("agq", "agr", "ags", "agw", "agx"), changed)
        assertEquals(
            listOf(
                "#181825", "#181825", "#181825",
                "#181825", "#181825", "#181825",
                "#181825", "#181825",
                "#ffffffff", "#fff3f5f6", "#fff7f8f9",
            ),
            values(styles),
        )
    }

    /**
     * Toasts and popover menus fill with aip, which the default palette sets to #3A3A3A but
     * another to #484848 and the tinted ones to #363A41 (#368). A raised item goes black in all
     * of them; the same gray on a plain item stays, and so does a gray past the raised ceiling.
     */
    @Test
    fun `a raised surface item goes black in every dark palette and nothing lighter moves`() {
        val styles = parse(
            """
            <resources>
              <style name="zo"><item name="aip">#ff3a3a3a</item></style>
              <style name="a_b"><item name="aip">#ff484848</item><item name="ait">#ff484848</item></style>
              <style name="a_k"><item name="aip">#ff363a41</item></style>
              <style name="x"><item name="aip">#ff505050</item><item name="aip">#80202020</item></style>
            </resources>
            """.trimIndent(),
        )

        val changed = rewriteDarkStyleItems(styles, surfaceStyleItems("48.0.2"), "#000000", RAISED_STYLE_ITEMS.getValue("48.0.2"))

        assertEquals(setOf("aip"), changed)
        assertEquals(
            listOf("#000000", "#000000", "#ff484848", "#000000", "#ff505050", "#80202020"),
            values(styles),
        )
        assertFalse(isOpaqueLiteralUnder("#ff484848", 0x40))
        assertTrue(isOpaqueLiteralUnder("#ff484848", RAISED_SURFACE_CEILING))
    }

    @Test
    fun `nothing dark to rewrite is reported as nothing changed`() {
        val styles = parse("""<resources><style name="zb"><item name="c7">#ffffffff</item></style></resources>""")
        assertTrue(rewriteDarkStyleItems(styles, surfaceStyleItems("48.0.2"), "#000000").isEmpty())
    }

    /**
     * The patch rewrites every sheet item and refuses a build whose palette it hasn't read, so the
     * palette has to be read for exactly the declared builds: a declared build without one would
     * be refused, and an undeclared one with one would get sheet names nobody checked. On 46.7.3
     * to 46.9.3 aia was UISheetGrouped3's dark value, another tier (the review of a02d0d67).
     */
    @Test
    fun `the palette is read for exactly the declared builds`() {
        assertEquals(setOf("48.0.2"), declaredVersions())
        assertEquals(declaredVersions(), DARK_BACKGROUND_COLORS.keys)
        assertEquals(declaredVersions(), SURFACE_STYLE_ITEMS.keys)
        assertEquals(declaredVersions(), RAISED_STYLE_ITEMS.keys)
        RAISED_STYLE_ITEMS.forEach { (version, raised) ->
            assertTrue("$version's raised items are surface items", SURFACE_STYLE_ITEMS.getValue(version).containsAll(raised))
        }
    }

    /** Each build moves the grays to new names, and an older build's names are an accent and overlays. */
    @Test
    fun `the palette is each build's own and a build never read is refused`() {
        assertEquals(setOf("a2a", "a2c", "a2d", "a2f", "a2n"), darkBackgroundColors("48.0.2"))
        declaredVersions().forEach { assertTrue("no palette for declared $it", darkBackgroundColors(it).size == 5) }
        listOf("46.9.3", "47.0.3", "47.1.3", "47.1.4", "47.4.3", "47.6.3").forEach { version ->
            assertRefused { darkBackgroundColors(version) }
            assertRefused { surfaceStyleItems(version) }
        }
        assertRefused { darkBackgroundColors(null) }
        assertRefused { surfaceStyleItems(null) }
        val refusal = unreadPaletteRefusal("47.1.4")
        assertTrue(refusal, refusal.contains("TikTok 47.1.4") && refusal.contains("nothing was changed"))
        assertTrue(refusal, refusal.endsWith("The palette is known for TikTok 48.0.2."))
        assertTrue(unreadPaletteRefusal(null).contains("this TikTok build"))
    }

    private fun assertRefused(check: () -> Unit) {
        try {
            check()
        } catch (refusal: PatchException) {
            return
        }
        fail("the check accepted what it should have refused")
    }

    @Test
    fun `a dark opaque literal is six or eight hex digits with every channel under 40`() {
        assertTrue(isDarkOpaqueLiteral("#282828"))
        assertTrue(isDarkOpaqueLiteral("#FF161823"))
        assertTrue(isDarkOpaqueLiteral(" #ff3f3f3f "))
        assertFalse(isDarkOpaqueLiteral("#404040"))
        assertFalse(isDarkOpaqueLiteral("#80000000"))
        assertFalse(isDarkOpaqueLiteral("#fff"))
        assertFalse(isDarkOpaqueLiteral("@color/a40"))
        assertFalse(isDarkOpaqueLiteral("?attr/agq"))
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray()))

    private fun values(styles: Document): List<String> {
        val items = styles.getElementsByTagName("item")
        return (0 until items.length).map { (items.item(it) as Element).textContent }
    }
}
