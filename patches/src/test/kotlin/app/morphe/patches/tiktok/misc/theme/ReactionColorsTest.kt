package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The like heart and the save bookmark take colors of their own (#369). */
class ReactionColorsTest {
    @Test
    fun `each declared build's liked hearts and saved bookmarks turn the picked color`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val table = FixtureResourceTable(apk)
            val reactions = reactionRecolors(version, "#2D7FF9", "#8B5CF6")
            assertEquals("$version: both reactions recolor", listOf("Like color", "Save color"), reactions.map { it.label })
            ZipFile(apk).use { zip ->
                for (reaction in reactions) {
                    val files = reaction.files
                    val shade = reactionShade(reaction.source, reaction.target)
                    val paths = files.rawIcons.flatMap { table.rawFiles[it] ?: error("$version: no raw $it") } +
                        files.drawables.flatMap { table.drawableFiles[it] ?: error("$version: no drawable $it") } +
                        files.pictures.map { "assets/$it" }
                    assertEquals("$version: ${reaction.label} pictures", files.rawIcons.size + files.drawables.size + files.pictures.size, paths.size)
                    for (path in paths) {
                        val original = zip.read(path) ?: error("$version: no $path")
                        val recolored = recolorPng(original, shade) ?: error("$version: $path has nothing of ${reaction.source}")
                        assertPictureMoved("$version: $path", original, recolored, reaction.source, reaction.target)
                    }
                    for (path in files.animations) {
                        val json = String(zip.read("assets/$path") ?: error("$version: no $path"), Charsets.UTF_8)
                        val recolored = recolorLottie(json, shade) ?: error("$version: $path has no ${reaction.source}")
                        assertNull("$version: $path keeps a shade of ${reaction.source}", recolorLottie(recolored, familyOf(reaction.source)))
                    }
                }
            }
        }
    }

    /** The files are the ones the feed's buttons load, not lookalikes elsewhere in the app. */
    @Test
    fun `each declared build's feed buttons load the recolored files`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val table = FixtureResourceTable(apk)
            val methods = allMethods(apk)
            val like = LIKE_FILES.getValue(version)
            // The like button's liked state picks one of these by experiment group.
            val likeIds = setOf(
                "icon_color_like_red_shadow_alt_1", "icon_media_like_active",
                "icon_exp_exp_01_icon_media_like_active", "icon_exp_exp_02_icon_media_like_active",
            ).map { table.rawIds[it] ?: error("$version: no raw $it") } + like.drawables.map { table.drawableIds.getValue(it) }
            val picker = methods.filter { method -> literals(method).containsAll(likeIds) }
            assertTrue("$version: no method picks the liked heart from $likeIds", picker.isNotEmpty())
            assertTrue("$version: ${like.rawIcons} misses the picked hearts", like.rawIcons.containsAll(listOf(
                "icon_color_like_red_shadow_alt_1", "icon_media_like_active",
                "icon_exp_exp_01_icon_media_like_active", "icon_exp_exp_02_icon_media_like_active",
            )))
            val loaded = methods.flatMap { strings(it) }.toSet()
            for (animation in like.animations + SAVE_FILES.getValue(version).animations) {
                assertTrue("$version: nothing loads $animation", animation in loaded)
            }
            // The save button names its saved picture, raw or asset, by experiment group.
            for (name in SAVE_FILES.getValue(version).rawIcons.filter { it.contains("favoriate") || it.endsWith("alt_1") }) {
                assertTrue("$version: nothing names $name.png", "$name.png" in loaded)
            }
        }
    }

    @Test
    fun `an RGBA picture moves its color, keeps its alpha and leaves other colors`() {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        val colors = intArrayOf(
            0xfffe2c55.toInt(), 0x80fe2c55.toInt(), 0xffeb284e.toInt(), 0xffffffff.toInt(),
            0x40000000, 0x00000000, 0xff2d7ff9.toInt(), 0xfff52a51.toInt(),
            0xff808080.toInt(), 0xfffe2c55.toInt(), 0xff101010.toInt(), 0xfffd2c55.toInt(),
        )
        for (i in colors.indices) image.setRGB(i % 4, i / 4, colors[i])
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        val recolored = recolorPng(png, reactionShade(TIKTOK_ACCENT, "#1DA153")) ?: error("nothing recolored")

        val after = ImageIO.read(ByteArrayInputStream(recolored))
        assertEquals(0xff, after.getRGB(0, 0) ushr 24)
        assertTrue(Integer.toHexString(after.getRGB(0, 0)), close(after.getRGB(0, 0), 0x1da153))
        assertEquals(0x80, after.getRGB(1, 0) ushr 24)
        assertTrue(Integer.toHexString(after.getRGB(1, 0)), close(after.getRGB(1, 0), 0x1da153))
        assertTrue("the darker edge stays darker", lightness(after.getRGB(2, 0)) < lightness(after.getRGB(0, 0)))
        assertEquals("white stays", 0xffffffff.toInt(), after.getRGB(3, 0))
        assertEquals("shadow stays", 0x40000000, after.getRGB(0, 1))
        assertEquals("blue stays", 0xff2d7ff9.toInt(), after.getRGB(2, 1))
        assertEquals("gray stays", 0xff808080.toInt(), after.getRGB(0, 2))
        assertEquals("near black stays", 0xff101010.toInt(), after.getRGB(2, 2))
        assertNull("nothing of the color to move", recolorPng(recolored, reactionShade(TIKTOK_ACCENT, "#1DA153")))
    }

    @Test
    fun `a palette picture changes only its palette`() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED, java.awt.image.IndexColorModel(
            8, 3, byteArrayOf(0xfa.toByte(), 0xff.toByte(), 0), byteArrayOf(0xce.toByte(), 0xff.toByte(), 0),
            byteArrayOf(0x15, 0xff.toByte(), 0),
        ))
        for (x in 0 until 8) for (y in 0 until 8) image.raster.setSample(x, y, 0, (x + y) % 3)
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        val recolored = recolorPng(png, reactionShade(TIKTOK_SAVE, "#8B5CF6")) ?: error("nothing recolored")

        assertEquals(png.size, recolored.size)
        val after = ImageIO.read(ByteArrayInputStream(recolored))
        assertTrue(Integer.toHexString(after.getRGB(0, 0)), close(after.getRGB(0, 0), 0x8b5cf6))
        assertEquals(0xffffff, after.getRGB(1, 0) and 0xffffff)
        assertEquals(0x000000, after.getRGB(2, 0) and 0xffffff)
    }

    @Test
    fun `a Lottie color array moves and every other array stays`() {
        val json = """{"c":{"a":0,"k":[0.996,0.173,0.333,1]},"s":[1,0.141,0.259,0.5],"p":{"k":[0.5,0.5,0]},""" +
            """"w":{"k":[1,1,1,1]},"o":{"k":[0.2, 0.3, 0.4, 1]},"k2":{"k":[100,100,100,100]}}"""

        val recolored = recolorLottie(json, reactionShade(TIKTOK_ACCENT, "#2D7FF9")) ?: error("nothing recolored")

        val moved = Regex(""""k":\[([0-9.]+),([0-9.]+),([0-9.]+),1]""").find(recolored) ?: error(recolored)
        val rgb = (1..3).fold(0) { value, group -> (value shl 8) or Math.round(moved.groupValues[group].toDouble() * 255).toInt() }
        assertTrue(recolored, close(rgb, 0x2d7ff9))
        assertTrue(recolored, Regex(""""s":\[[0-9.]+,[0-9.]+,[0-9.]+,0\.5]""").containsMatchIn(recolored))
        assertTrue(recolored, !recolored.contains("0.141,0.259"))
        assertTrue(recolored, recolored.endsWith(""""p":{"k":[0.5,0.5,0]},"w":{"k":[1,1,1,1]},"o":{"k":[0.2, 0.3, 0.4, 1]},"k2":{"k":[100,100,100,100]}}"""))
        assertNull(recolorLottie("""{"k":[1,1,1,1]}""", reactionShade(TIKTOK_ACCENT, "#2D7FF9")))
    }

    @Test
    fun `only a changed reaction recolors and an unread build is refused`() {
        assertTrue(reactionRecolors("47.7.3", TIKTOK_ACCENT, TIKTOK_SAVE).isEmpty())
        assertEquals(listOf("Save color"), reactionRecolors("47.7.3", TIKTOK_ACCENT, "#2D7FF9").map { it.label })
        assertTrue(reactionRecolors(null, TIKTOK_ACCENT, TIKTOK_SAVE).isEmpty())
        try {
            reactionRecolors("47.6.3", "#2D7FF9", TIKTOK_SAVE)
            fail("an unread build was accepted")
        } catch (refusal: PatchException) {
            assertTrue(refusal.message, refusal.message!!.contains("TikTok 47.6.3") && refusal.message!!.contains("Like color"))
        }
        assertEquals(LIKE_FILES.keys, SAVE_FILES.keys)
        assertEquals(TIKTOK_SAVE, SAVE_PRESETS.getValue("TikTok original"))
        assertEquals(TIKTOK_ACCENT, LIKE_PRESETS.getValue("TikTok original"))
        assertNotNull(checkedAccent("#ff8b5cf6", "Save color"))
        try {
            checkedAccent("#8b5cf680", "Save color")
            fail("a see-through color was accepted")
        } catch (refusal: PatchException) {
            assertTrue(refusal.message, refusal.message!!.startsWith("Save color: "))
        }
    }

    /** Every pixel keeps its alpha; the source color becomes the target and its shades follow. */
    private fun assertPictureMoved(where: String, original: ByteArray, recolored: ByteArray, source: String, target: String) {
        val before = ImageIO.read(ByteArrayInputStream(original))
        val after = ImageIO.read(ByteArrayInputStream(recolored))
        assertEquals("$where width", before.width, after.width)
        assertEquals("$where height", before.height, after.height)
        val from = source.removePrefix("#").toInt(16)
        val to = target.removePrefix("#").toInt(16)
        val family = familyOf(source)
        var opaque = 0
        var moved = 0
        for (x in 0 until before.width) for (y in 0 until before.height) {
            val was = before.getRGB(x, y)
            val now = after.getRGB(x, y)
            assertEquals("$where alpha at $x,$y", was ushr 24, now ushr 24)
            if (was ushr 24 < 0xc0) continue
            opaque++
            assertNull("$where keeps $source's family at $x,$y: ${Integer.toHexString(now)}", family(now and 0xffffff))
            if (family(was and 0xffffff) != null) moved++
            if (was and 0xffffff == from) {
                assertTrue("$where $source at $x,$y became ${Integer.toHexString(now)}", close(now and 0xffffff, to))
            }
        }
        assertTrue("$where had only $moved of $opaque opaque pixels in $source's family", moved > opaque / 2)
    }

    private fun familyOf(source: String): (Int) -> Int? {
        val from = source.removePrefix("#").toInt(16)
        return { rgb -> familyShade(rgb, from, from)?.let { rgb xor 1 } }
    }

    private fun close(a: Int, b: Int): Boolean = listOf(16, 8, 0).all { shift -> kotlin.math.abs((a shr shift and 0xff) - (b shr shift and 0xff)) <= 2 }

    private fun lightness(argb: Int): Int = (argb shr 16 and 0xff) + (argb shr 8 and 0xff) + (argb and 0xff)

    private fun ZipFile.read(path: String): ByteArray? = getEntry(path)?.let { entry -> getInputStream(entry).use { it.readBytes() } }

    private fun allMethods(apk: File): List<Method> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .flatMap { it.methods.asSequence() }
            .filter { it.implementation != null }
            .toList()
    }

    private fun literals(method: Method): Set<Int> =
        method.implementation!!.instructions.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.toSet()

    private fun strings(method: Method): List<String> =
        method.implementation!!.instructions.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
}
