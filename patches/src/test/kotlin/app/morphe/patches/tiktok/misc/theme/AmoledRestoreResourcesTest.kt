/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.InternalApi
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The resources behind the AMOLED dark mode switch (#372): TikTok's own grays stay in the APK beside
 * the repaint, every style says what TikTok gave each repainted attribute, and the restore styles the
 * extension applies point each attribute back at that. On the declared build the whole round trip
 * runs as Manager does it, decode, patch and encode, and the encoded table is read back.
 */
class AmoledRestoreResourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `the restorable attributes are the surfaces and every item naming a repainted color`() {
        val styles = parse(
            """
            <resources>
              <style name="Dark"><item name="g5d">@color/a2c</item><item name="zz">@color/other</item><item name="android:colorBackground">@color/a2e</item></style>
              <style name="Light"><item name="agp">#ffffffff</item><item name="g62">?attr/agp</item></style>
            </resources>
            """,
        )

        assertEquals(
            listOf("agp", "aif", "android:colorBackground", "g5d"),
            restorableAttributes(listOf(styles), setOf("aif", "agp"), setOf("a2c", "a2e")),
        )
    }

    @Test
    fun `the twins, siblings, attributes and restore styles each say TikTok's value`() {
        val colors = parse("""<resources><color name="a2c">#ff121212</color><color name="x">#ffffffff</color></resources>""")
        assertEquals(1, addTwinColors(colors, setOf("a2c", "a2e")))
        assertEquals(
            listOf("a2c" to "#ff121212", "x" to "#ffffffff", "hushfeed_amoled_twin_a2c" to "#ff121212"),
            colors.elements("color").map { it.getAttribute("name") to it.textContent },
        )

        val styles = parse(
            """
            <resources>
              <style name="Dark"><item name="agp">#ff282828</item><item name="g5d">@color/a2c</item><item name="other">?attr/agp</item></style>
              <style name="Light"><item name="agp">#ffffffff</item></style>
            </resources>
            """,
        )
        val restorable = listOf("agp", "g5d")
        assertEquals(3, addStockSiblings(styles, restorable, setOf("a2c")))
        assertEquals(
            listOf(
                "agp" to "#ff282828", "hushfeed_amoled_stock_0" to "#ff282828",
                "g5d" to "@color/a2c", "hushfeed_amoled_stock_1" to "@color/hushfeed_amoled_twin_a2c",
                "other" to "?attr/agp",
            ),
            styles.items("Dark"),
        )
        assertEquals(listOf("agp" to "#ffffffff", "hushfeed_amoled_stock_0" to "#ffffffff"), styles.items("Light"))

        addRestoreStyles(styles, restorable)
        assertEquals(listOf("agp" to "?attr/hushfeed_amoled_stock_0"), styles.items("hushfeed_amoled_restore_0"))
        assertEquals(listOf("g5d" to "?attr/hushfeed_amoled_stock_1"), styles.items("hushfeed_amoled_restore_1"))
        assertEquals(
            listOf("agp" to "?attr/hushfeed_amoled_stock_0", "g5d" to "?attr/hushfeed_amoled_stock_1"),
            styles.items(RESTORE_ALL_STYLE),
        )

        val attrs = parse(
            """
            <resources>
              <attr name="agp" format="color"/>
              <declare-styleable name="S"><attr name="g5d"/></declare-styleable>
            </resources>
            """,
        )
        declareStockAttributes(attrs, listOf("agp", "g5d", "android:colorBackground"))
        assertEquals(
            listOf(
                "hushfeed_amoled_stock_0" to "color",
                "hushfeed_amoled_stock_1" to "reference|color",
                "hushfeed_amoled_stock_2" to "reference|color",
            ),
            attrs.elements("attr").drop(2).map { it.getAttribute("name") to it.getAttribute("format") },
        )
    }

    /** An APK that already has the switch's names was patched before, and patching it again is refused. */
    @Test
    fun `a name the switch adds that's already taken is found in attrs, colors and styles`() {
        val clean = parse("""<resources><color name="a2c">#ff121212</color><style name="x"/><attr name="agp" format="color"/></resources>""")
        assertTrue(restoreNamesTaken(listOf(clean)).isEmpty())
        val patched = parse(
            """
            <resources>
              <attr name="hushfeed_amoled_stock_0" format="color"/>
              <color name="hushfeed_amoled_twin_a2c">#ff121212</color>
              <style name="hushfeed_amoled_restore_all"/>
              <string name="hushfeed_amoled_note">x</string>
            </resources>
            """,
        )
        assertEquals(
            listOf("hushfeed_amoled_stock_0", "hushfeed_amoled_twin_a2c", "hushfeed_amoled_restore_all"),
            restoreNamesTaken(listOf(clean, patched)),
        )
    }

    /** The extension looks the switch's resources up by these names. */
    @Test
    fun `the extension asks for the names the patch adds`() {
        val source = listOf(
            File("../extensions/tiktok/src/main/java/app/morphe/extension/tiktok/theme/AmoledTheme.java"),
            File("extensions/tiktok/src/main/java/app/morphe/extension/tiktok/theme/AmoledTheme.java"),
        ).firstOrNull { it.isFile }
        assertNotNull("could not find AmoledTheme.java from ${File(".").absolutePath}", source)
        val text = String(source!!.readBytes(), StandardCharsets.UTF_8)
        for (name in listOf(stockAttribute(0).dropLast(1), restoreStyleName(0).dropLast(1), RESTORE_ALL_STYLE)) {
            assertTrue("AmoledTheme names \"$name\"", text.contains("\"$name\""))
        }
    }

    /**
     * The declared build through Manager's own decode and encode: every resource keeps its number,
     * the added names all carry the switch's prefix, the twins hold TikTok's grays while the palette
     * turns black, each style's stock items hold what that style gave the attribute, and the
     * restore styles point every attribute at its stock item. Patching the result again is refused.
     */
    // The encoded resources APK is only reachable through the patcher's internal resource context.
    @OptIn(InternalApi::class)
    @Test
    fun `the declared build keeps TikTok's grays and every id`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val before = FixtureResourceTable(apk)
            var restorable = emptyList<String>()
            var again: Throwable? = null
            val probe = resourcePatch(name = "amoled restore probe") {
                execute {
                    restorable = paintAmoledResources("#000000")
                    again = runCatching { paintAmoledResources("#000000") }.exceptionOrNull()
                }
            }
            val encoded = File(temporary.newFolder(), "resources.apk")
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
                val context = PatcherContext::class.java.getMethod("getResourceContext\$morphe_patcher")
                    .invoke(patcher.context) as ResourcePatchContext
                requireNotNull(context.get().resourcesApk) { "the patcher encoded no resources APK" }.copyTo(encoded)
            }
            assertTrue("$version: patching twice is refused, not $again", again is PatchException && again!!.message.orEmpty().contains("already has resources named"))
            val after = FixtureResourceTable(encoded)

            val palette = darkBackgroundColors(version)
            assertTrue("$version: every surface is restorable", restorable.containsAll(surfaceStyleItems(version)))
            assertTrue("$version: the dark app themes' palette items, $restorable", "g5d" in restorable && "g62" in restorable)

            assertTrue("$version: attrs moved", before.attrNames.filter { (id, name) -> after.attrNames[id] != name }.isEmpty())
            assertTrue("$version: colors moved", before.colorNames.filter { (id, name) -> after.colorNames[id] != name }.isEmpty())
            assertTrue("$version: styles moved", before.styleIds.filter { (name, id) -> after.styleIds[name] != id }.isEmpty())
            assertEquals("$version: drawables", before.drawableIds, after.drawableIds)

            val addedAttrs = after.attrNames.values.toSet() - before.attrNames.values.toSet()
            val addedColors = after.colorNames.values.toSet() - before.colorNames.values.toSet()
            val addedStyles = after.styleIds.keys - before.styleIds.keys
            assertEquals("$version: the stock attributes", restorable.indices.map(::stockAttribute).toSet(), addedAttrs)
            assertEquals("$version: the twins", palette.map(::twinColor).toSet(), addedColors)
            assertEquals(
                "$version: the restore styles",
                restorable.indices.map(::restoreStyleName).toSet() + RESTORE_ALL_STYLE,
                addedStyles,
            )
            assertTrue("$version: only the switch's names", (addedAttrs + addedColors + addedStyles).all { it.startsWith(AMOLED_RESTORE_PREFIX) })

            val colorIds = after.colorNames.entries.associate { it.value to it.key }
            palette.forEach { name ->
                val gray = before.colorValues[name]
                assertNotNull("$version: TikTok's $name", gray)
                assertTrue("$version: $name is a dark gray, $gray", isDarkOpaqueLiteral(gray!!))
                assertEquals("$version: $name's twin", gray, after.colorValues[twinColor(name)])
                assertEquals("$version: $name is repainted", "#ff000000", after.colorValues[name])
            }

            // A style's stock item holds what the style gave the attribute; a repainted color is named by its twin.
            val twinOf = palette.associate { name ->
                reference(colorIds.getValue(name)) to reference(colorIds.getValue(twinColor(name)))
            }
            val attrIds = after.attrNames.entries.associate { it.value to it.key }
            restorable.forEachIndexed { index, name ->
                val stockValues = after.styleValues(stockAttribute(index))
                assertTrue("$version: $name's stock items", stockValues.isNotEmpty())
                if (name.contains(':')) return@forEachIndexed
                assertEquals(
                    "$version: $name's stock items hold TikTok's values",
                    before.styleValues(name).map { twinOf[it] ?: it }.sorted(),
                    stockValues.sorted(),
                )
            }

            restorable.forEachIndexed { index, name ->
                val style = after.styles.single { it.name == restoreStyleName(index) }
                val item = style.items.single()
                assertEquals("$version: ${style.name} reads the stock item", FixtureResourceTable.TYPE_ATTRIBUTE, item.type)
                assertEquals("$version: ${style.name} reads the stock item", attrIds.getValue(stockAttribute(index)), item.data)
                if (!name.contains(':')) assertEquals("$version: ${style.name} sets $name", attrIds.getValue(name), item.attr)
            }
            val all = after.styles.single { it.name == RESTORE_ALL_STYLE }
            assertEquals("$version: restore_all sets every attribute", restorable.size, all.items.size)
            assertTrue("$version: restore_all reads stock items", all.items.all { it.type == FixtureResourceTable.TYPE_ATTRIBUTE })
            assertEquals(
                "$version: restore_all reads each stock item",
                restorable.indices.map { attrIds.getValue(stockAttribute(it)) }.toSet(),
                all.items.map { it.data }.toSet(),
            )
        }
    }

    /** A reference as FixtureResourceTable shows a style value. */
    private fun reference(id: Int) = "0x" + Integer.toHexString(FixtureResourceTable.TYPE_REFERENCE) + ":" + Integer.toHexString(id)

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.trimIndent().toByteArray(StandardCharsets.UTF_8)))

    private fun Document.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Document.items(style: String): List<Pair<String, String>> {
        val element = elements("style").single { it.getAttribute("name") == style }
        val list = element.getElementsByTagName("item")
        return (0 until list.length).map { (list.item(it) as Element).let { item -> item.getAttribute("name") to item.textContent } }
    }
}
