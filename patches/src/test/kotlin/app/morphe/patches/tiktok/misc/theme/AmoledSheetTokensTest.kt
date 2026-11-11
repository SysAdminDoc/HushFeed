package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where TikTok's two sheets take their dark fill, read off the declared build's fixture (first
 * traced on 47.0.3), so the name the AMOLED patch rewrites for them is held to that build.
 *
 * <p>The comment panel's shapes and the Tux sheet behind the share sheet fill with one theme
 * attribute: the comment page sets drawable af4 or af9 on its container, whose solid is
 * ?attr/a26, and the Tux sheet reads TuxSheet's tux_sheetBackgroundColor, which its style points
 * at ?attr/a26 as well. The dark themes send a26 to the palette token aif, a dark literal
 * (#1E1E1E on the S22, 2026-09-23), and the light ones to axq, white. The names are the build's
 * own: 46.x reached the same sheets through agk and c3, which 47.x keeps as unrelated tokens.
 * a26 is what TikTok's own token table calls UISheetFlat1, the fill of its sheets, panels, modals
 * and text cells as well, so all of those go black with it.
 */
class AmoledSheetTokensTest {
    @Test
    fun `each declared build fills both sheets through a26, which the dark themes send to aif`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)

            val sheetAttr = styleableAttr(apk, "TuxSheet", "TuxSheet__tux_sheetBackgroundColor")
            assertEquals("the Tux sheet background attribute", "b7h", table.attrNames[sheetAttr])
            val sheetValues = table.styleValues("b7h").toSet()
            assertTrue("b7h reaches a26 in a Tux sheet style: $sheetValues", "?attr/a26" in sheetValues)

            // The comment page's two container shapes were traced on 47.0.3 (af4 and af9).
            // Drawable names move with every build and nothing here reads which ones the
            // declared build's page sets.

            val a26 = table.styleValues("a26")
            assertEquals("what the themes point a26 at: $a26", setOf("?attr/aif", "?attr/axq"), a26.toSet())
            val dark = table.styleValues("aif")
            val light = table.styleValues("axq")
            assertTrue("aif is a dark opaque literal wherever it is set: $dark", dark.isNotEmpty() && dark.all(::isDarkOpaqueLiteral))
            assertTrue("axq is light wherever it is set: $light", light.isNotEmpty() && light.none(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites aif", "aif" in surfaceStyleItems(Fixtures.versionOf(apk)))
        }
    }

    /**
     * The feed's long-press panel and Inbox > Activity stayed gray under AMOLED (Facebook report,
     * 2026-10-12; #1E1E1E and #121212 on the S22). LongPressAwemeFragment fills with
     * UISheetGrouped1 (a2b), which the dark themes send to ail and the light ones to axw, and its
     * cards are UISheetGrouped2 (a2d, ain), which stayed gray through 0.80.0 (#312). The notification
     * page's container and its rows fill with BGPrimary (bx), a literal of its own in every theme.
     */
    @Test
    fun `the long-press panel and the Activity page fill with tokens the patch rewrites`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val idOf = { name: String -> table.attrNames.entries.single { it.value == name }.key }

            assertTrue(
                "LongPressAwemeFragment fills with UISheetGrouped1",
                loadsConstant(apk, "Lcom/ss/android/ugc/profile/business/ur/longpress/LongPressAwemeFragment;", "onCreateView", idOf("a2b")),
            )
            assertEquals("what the themes point a2b at", setOf("?attr/ail", "?attr/axw"), table.styleValues("a2b").toSet())
            val grouped = table.styleValues("ail")
            assertTrue("ail is a dark opaque literal wherever it is set: $grouped", grouped.isNotEmpty() && grouped.all(::isDarkOpaqueLiteral))
            assertTrue("axw stays light", table.styleValues("axw").none(::isDarkOpaqueLiteral))
            assertEquals("the panel's cards", setOf("?attr/ain", "?attr/axy"), table.styleValues("a2d").toSet())
            val cards = table.styleValues("ain")
            assertTrue("ain is a dark opaque literal wherever it is set: $cards", cards.isNotEmpty() && cards.all(::isDarkOpaqueLiteral))
            assertTrue("axy stays light", table.styleValues("axy").none(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites the cards", "ain" in surfaceStyleItems(Fixtures.versionOf(apk)))

            assertTrue(
                "the notification page's container fills with BGPrimary",
                loadsConstant(apk, "Lcom/by/andInflater/awemenotice_fragment_navigation_container;", "LIZ", idOf("bx")),
            )
            val primary = table.styleValues("bx")
            assertTrue("bx is a literal in every theme: $primary", primary.none { it.startsWith("?") })
            assertTrue("bx is dark in a dark theme: $primary", primary.any(::isDarkOpaqueLiteral))
            assertTrue("bx is light in a light theme: $primary", !primary.all(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites ail and bx", surfaceStyleItems(Fixtures.versionOf(apk)).containsAll(setOf("ail", "bx")))
        }
    }

    /**
     * TikTok's own settings pages stayed stock under AMOLED (#152): Settings and privacy is
     * UIPageGrouped1, already black in TikTok's dark mode, and its cards were #1E1E1E on the S22.
     * A TuxCell (the settings row) fills with UIPageGrouped2 (a0i) unless it sits on a sheet,
     * where it takes UISheetGrouped2 (a2d) like the long-press panel's cards. The dark themes send
     * a0i to ags and the light ones to aw3.
     */
    @Test
    fun `settings rows off a sheet fill with UIPageGrouped2, which the patch rewrites`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val idOf = { name: String -> table.attrNames.entries.single { it.value == name }.key }

            val cell = "Lcom/ss/android/ugc/aweme/cell/TuxCell;"
            assertTrue("a TuxCell fills with UIPageGrouped2", loadsConstant(apk, cell, null, idOf("a0i")))
            assertTrue("a TuxCell on a sheet fills with UISheetGrouped2", loadsConstant(apk, cell, null, idOf("a2d")))
            assertEquals("what the themes point a0i at", setOf("?attr/ags", "?attr/aw3"), table.styleValues("a0i").toSet())
            val dark = table.styleValues("ags")
            assertTrue("ags is a dark opaque literal wherever it is set: $dark", dark.isNotEmpty() && dark.all(::isDarkOpaqueLiteral))
            assertTrue("aw3 stays light", table.styleValues("aw3").none(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites ags", "ags" in surfaceStyleItems(Fixtures.versionOf(apk)))
            assertTrue("a sheet's cards go black with it", "ain" in surfaceStyleItems(Fixtures.versionOf(apk)))
        }
    }

    /**
     * Inbox, profiles and TikTok's other full pages fill with UIPageFlat1 (a0a) or
     * UIPageGrouped1 (a0g), named so by TikTok's own token table (a profile's background
     * component and Inbox's activity and followers pages load them by id). The dark themes send them to
     * agk and agq, #000000 in three themes and #0C0E12 in the tinted one, so a picked color
     * left those pages black until the patch rewrote them too (#348). Their raised forms (agl,
     * agr) and UIPageFlat2 (a0c, agm) go with them.
     */
    @Test
    fun `the pages fill with tokens the patch rewrites, so a picked color reaches them`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val idOf = { name: String -> table.attrNames.entries.single { it.value == name }.key }
            val rewritten = surfaceStyleItems(Fixtures.versionOf(apk))

            val profile = "Lcom/ss/android/ugc/profile/platform/business/background/ProfileBackgroundComponent;"
            assertTrue("a profile's background fills with UIPageFlat1", loadsConstant(apk, profile, null, idOf("a0a")))
            val activity = "Lcom/ss/android/ugc/aweme/notification/aggnotice/AggregateNoticeFragment;"
            assertTrue("Inbox's activity pages fill with UIPageFlat1", loadsConstant(apk, activity, "onViewCreated", idOf("a0a")))
            val followers = "Lcom/ss/android/ugc/aweme/inbox/followerv2/InboxFollowerFragmentV2;"
            assertTrue("Inbox's followers page fills with UIPageGrouped1", loadsConstant(apk, followers, "onViewCreated", idOf("a0g")))

            val pages = mapOf(
                "a0a" to ("agk" to "avv"),
                "a0b" to ("agl" to "avw"),
                "a0c" to ("agm" to "avx"),
                "a0g" to ("agq" to "aw1"),
                "a0h" to ("agr" to "aw2"),
            )
            for ((token, values) in pages) {
                val (dark, light) = values
                assertEquals("what the themes point $token at", setOf("?attr/$dark", "?attr/$light"), table.styleValues(token).toSet())
                val darkValues = table.styleValues(dark)
                assertTrue("$dark is a dark opaque literal wherever it is set: $darkValues", darkValues.isNotEmpty() && darkValues.all(::isDarkOpaqueLiteral))
                assertTrue("$light stays light", table.styleValues(light).none(::isDarkOpaqueLiteral))
                assertTrue("the patch rewrites $dark", dark in rewritten)
            }
            assertTrue("a dark theme's page is pure black before patching", "#ff000000" in table.styleValues("agk"))
        }
    }

    /**
     * The snackbar after Not interested ("You'll see fewer posts like this") is a TuxToast, and the
     * comments' sort menu a TuxTooltip popover (CommentPowerListAssem builds it). Both stayed
     * #3A3A3A under AMOLED (#368): TuxToast's background and the popover's both point at
     * UISheetFlat3 (a2_), which the dark themes send to aij and the light ones to axu. aij is
     * #484848 or a tinted #323C43 in the other dark palettes, over the 0x40 gate, so it's a raised
     * item the patch takes up to its own ceiling.
     */
    @Test
    fun `toasts and popover menus fill with UISheetFlat3, which the patch rewrites in every dark palette`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val version = Fixtures.versionOf(apk)

            val toast = table.attrNames.getValue(styleableAttr(apk, "TuxToast", "TuxToast__tux_toastBackgroundColor"))
            assertTrue("a toast's background is UISheetFlat3: ${table.styleValues(toast)}", "?attr/a2_" in table.styleValues(toast))
            val popover = table.attrNames.getValue(styleableAttr(apk, "TuxTooltip", "TuxTooltip__tux_tooltipPopoverBgColor"))
            assertTrue("a popover's background is UISheetFlat3: ${table.styleValues(popover)}", "?attr/a2_" in table.styleValues(popover))

            assertEquals("what the themes point a2_ at", setOf("?attr/aij", "?attr/axu"), table.styleValues("a2_").toSet())
            val dark = table.styleValues("aij")
            assertTrue(
                "aij is a dark opaque literal under the raised ceiling wherever it is set: $dark",
                dark.isNotEmpty() && dark.all { isOpaqueLiteralUnder(it, RAISED_SURFACE_CEILING) },
            )
            assertTrue("one palette's aij is over the plain gate, so it needs the raised one: $dark", !dark.all(::isDarkOpaqueLiteral))
            assertTrue("axu stays light", table.styleValues("axu").none { isOpaqueLiteralUnder(it, RAISED_SURFACE_CEILING) })
            assertTrue("the patch rewrites aij", "aij" in surfaceStyleItems(version))
            assertTrue("aij takes the raised ceiling", "aij" in RAISED_STYLE_ITEMS.getValue(version))
        }
    }

    /**
     * The X at the top right of the comments sat on a gray circle on the black sheet (#371).
     * CommentPageActionBarAssem sets an oval shape on the X when TikTok draws round header buttons,
     * and the oval's only fill is UISheetFlat2 (a28), which the dark themes send to aih and the light
     * ones to axs. aih is #2C2C2C, #3A3A3A, #2A2F35 or #292D32 by palette, all under the plain gate.
     */
    @Test
    fun `the comments' close button sits on UISheetFlat2, which the patch rewrites`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val version = Fixtures.versionOf(apk)

            val oval = CLOSE_BUTTON_OVAL.getValue(version)
            val ovalId = table.drawableIds[oval] ?: error("$version: no drawable $oval")
            val actionBar = "Lcom/ss/android/ugc/aweme/commentv2/actionbar/CommentPageActionBarAssem;"
            assertTrue("$version: the comments' action bar sets $oval", loadsConstant(apk, actionBar, "onViewCreated", ovalId))
            val paths = table.drawableFiles[oval].orEmpty()
            assertTrue("$version: $oval's files are $paths", paths.isNotEmpty() && paths.all { it.endsWith(".xml") })
            ZipFile(apk).use { zip ->
                for (path in paths) {
                    val xml = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                    val attrs = attributeIds(xml).map { table.attrNames[it] ?: Integer.toHexString(it) }.toSet()
                    assertEquals("$version: $path fills with", setOf("a28"), attrs)
                }
            }

            assertEquals("what the themes point a28 at", setOf("?attr/aih", "?attr/axs"), table.styleValues("a28").toSet())
            val dark = table.styleValues("aih")
            assertTrue("aih is a dark opaque literal wherever it is set: $dark", dark.isNotEmpty() && dark.all(::isDarkOpaqueLiteral))
            assertTrue("axs stays light", table.styleValues("axs").none(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites aih", "aih" in surfaceStyleItems(version))
        }
    }

    /** The theme attributes a compiled XML file's values read: each Res_value of type attribute. */
    private fun attributeIds(xml: ByteArray): Set<Int> {
        val buffer = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        val ids = mutableSetOf<Int>()
        for (at in 0..xml.size - 8) {
            // size 8, res0 0, dataType 2 (attribute), then the attr id.
            if (buffer.getShort(at).toInt() != 8 || xml[at + 2].toInt() != 0) continue
            if (xml[at + 3].toInt() != FixtureResourceTable.TYPE_ATTRIBUTE) continue
            val id = buffer.getInt(at + 4)
            if (id ushr 24 == 0x7f) ids += id
        }
        return ids
    }

    private fun loadsConstant(apk: File, type: String, method: String?, value: Int): Boolean {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classDef = container.dexEntryNames.asSequence().mapNotNull { entry ->
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == type }
        }.first()
        return classDef.methods.filter { method == null || it.name == method }.any { candidate ->
            candidate.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == value } == true
        }
    }

    /** R$styleable.[array][[field]]: the int field and the array <clinit> fills. */
    private fun styleableAttr(apk: File, array: String, field: String): Int {
        val styleable = "Lcom/ss/android/ugc/aweme/app/R\$styleable;"
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classDef = container.dexEntryNames.asSequence().mapNotNull { entry ->
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == styleable }
        }.first()
        val index = (classDef.staticFields.single { it.name == field }.initialValue as IntEncodedValue).value
        val instructions = classDef.methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
        val address = IntArray(instructions.size + 1)
        for (i in instructions.indices) address[i + 1] = address[i] + instructions[i].codeUnits
        val filled = HashMap<Int, List<Number>>()
        for (i in instructions.indices) {
            val instruction = instructions[i]
            if (instruction.opcode == Opcode.FILL_ARRAY_DATA) {
                val payload = instructions[address.indexOf(address[i] + (instruction as OffsetInstruction).codeOffset)] as ArrayPayload
                filled[(instruction as OneRegisterInstruction).registerA] = payload.arrayElements
            } else if (instruction.opcode == Opcode.SPUT_OBJECT) {
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                if (field.definingClass == styleable && field.name == array) {
                    return filled.getValue((instruction as OneRegisterInstruction).registerA)[index].toInt()
                }
            }
        }
        error("no $array array in $styleable")
    }

    private companion object {
        /** The oval behind the comments' close X, per build: drawable names move with every build. */
        val CLOSE_BUTTON_OVAL = mapOf("47.7.3" to "af9")
    }
}
