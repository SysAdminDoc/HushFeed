/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.absettings.APP_AB_INT
import app.morphe.patches.tiktok.misc.absettings.MethodShape
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONTAINER =
    "Lcom/ss/android/ugc/aweme/commentv2/commentpage/commonvideo/DefaultCommentPageContainerFragment;"
private const val TUX_ICON = "Lcom/bytedance/tux/icon/TuxIconView;"
private const val SHRINK_LISTENERS = "[setShowHeightChangeListenerForCommentDialog], scope not valid"
private const val SHRINK_ABILITY = "Lcom/ss/android/ugc/feed/platform/cell/shrink/VideoShrinkAbility;"

/** Two-register branches compare a value with another register; the zero tests compare with 0. */
private val TWO_REGISTER_BRANCHES = setOf(Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE)
private val ZERO_BRANCHES = setOf(Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ)

/**
 * What CommentSheetGates' two answers rely on (#479), held to every declared build. Both keys
 * are read through the App AB int getter into a lazy holder (AppAbTwinFixturesTest holds the
 * getter to a hooked class). The sheet builds its expand button only when
 * `comment_panel_full_page` is 1, and the feed hands the comment sheet its video shrink listeners
 * only when `video_shrink_by_comment` is 1, so answering 1 and 0 is what turns each on and off.
 */
class CommentSheetGatesAnchorsTest {
    @Test
    fun `the expand button is built when comment_panel_full_page is 1`() = Fixtures.forEachDeclared { apk ->
        val version = Fixtures.versionOf(apk)
        val classes = classes(apk)
        val holder = holderOf(classes, "comment_panel_full_page", version)

        val builders = classes.getValue(CONTAINER).methods.filter { method ->
            method.code().any { it.storesIcon() } && method.code().any { it.reads(holder) }
        }
        assertEquals("$version: the container method that builds the expand button", 1, builders.size)
        val code = builders.single().code()
        val atOne = comparisons(code, holder).filter { (_, literal) -> literal == 1 }.map { it.first }
        val store = code.indexOfFirst { it.storesIcon() }
        assertTrue("$version: the expand button no longer waits on comment_panel_full_page == 1", atOne.any { it < store })
    }

    @Test
    fun `the comment sheet shrinks the video only when video_shrink_by_comment is 1`() = Fixtures.forEachDeclared { apk ->
        val version = Fixtures.versionOf(apk)
        val classes = classes(apk)
        val holder = holderOf(classes, "video_shrink_by_comment", version)

        val gates = classes.values.flatMap { classDef ->
            classDef.methods.filter { it.name != "<clinit>" && it.code().any { instruction -> instruction.reads(holder) } }
        }
        assertEquals("$version: methods that read video_shrink_by_comment", 1, gates.size)
        val gate = gates.single()
        assertEquals("$version: the shrink gate's shape", "(II)Z", gate.parameterTypes.joinToString("", "(", ")") + gate.returnType)
        assertTrue(
            "$version: the shrink gate no longer asks for 1",
            comparisons(gate.code(), holder).any { (_, literal) -> literal == 1 },
        )

        // Two tablet getters in the same panel log the same line, so the installer is the one
        // that also asks the cell for its shrink ability.
        val installers = classes.values.flatMap { classDef ->
            classDef.methods.filter { method ->
                method.code().any { it.getReference<StringReference>()?.string == SHRINK_LISTENERS } &&
                    method.code().any { it.opcode == Opcode.CONST_CLASS && it.getReference<TypeReference>()?.type == SHRINK_ABILITY }
            }
        }
        assertEquals("$version: the comment sheet's shrink listener installer", 1, installers.size)
        assertTrue(
            "$version: the shrink listener installer no longer asks the gate",
            installers.single().code().any { instruction ->
                instruction.getReference<MethodReference>()?.let {
                    it.name == gate.name && it.parameterTypes.joinToString("") == "II" && it.returnType == "Z"
                } == true
            },
        )
    }

    /** The static lazy field a key's reader goes into: `<clinit>` news the reader and stores it. */
    private fun holderOf(classes: Map<String, ClassDef>, key: String, version: String): FieldReference {
        val readers = classes.values.filter { classDef ->
            classDef.methods.any { method ->
                method.name == "invoke" && method.code().let { code ->
                    code.any { it.getReference<StringReference>()?.string == key } &&
                        code.any { instruction ->
                            instruction.getReference<MethodReference>()?.let {
                                MethodShape(it.returnType, it.parameterTypes.map(CharSequence::toString)) == APP_AB_INT
                            } == true
                        }
                }
            }
        }
        assertEquals("$version: lazy readers of $key through the App AB int getter", 1, readers.size)
        val reader = readers.single().type
        val stores = classes.values.flatMap { classDef ->
            val code = classDef.methods.singleOrNull { it.name == "<clinit>" }?.code() ?: return@flatMap emptyList()
            code.withIndex()
                .filter { (_, instruction) -> instruction.opcode == Opcode.NEW_INSTANCE && instruction.getReference<TypeReference>()?.type == reader }
                .mapNotNull { (index, _) ->
                    code.drop(index).take(8).firstOrNull { it.opcode == Opcode.SPUT_OBJECT }?.getReference<FieldReference>()
                }
        }
        assertEquals("$version: the lazy holder of $key", 1, stores.size)
        return stores.single()
    }

    /**
     * Each value read of [holder] with the branch after it and the literal that branch compares
     * with. A read is the lazy's `getValue()` on a register the holder's field was last loaded
     * into, since a method that checks the value twice loads the field once (`wZ` on 47.9.3).
     */
    private fun comparisons(code: List<Instruction>, holder: FieldReference): List<Pair<Int, Int?>> =
        code.withIndex().filter { (index, instruction) ->
            val call = instruction as? FiveRegisterInstruction ?: return@filter false
            instruction.getReference<MethodReference>()?.name == "getValue" && call.registerCount == 1 &&
                lastWrite(code, index, call.registerC)?.reads(holder) == true
        }.mapNotNull { (index, _) ->
            val branch = (index + 1 until minOf(code.size, index + 12))
                .firstOrNull { code[it].opcode in TWO_REGISTER_BRANCHES || code[it].opcode in ZERO_BRANCHES }
                ?: return@mapNotNull null
            val compare = code[branch]
            val literal = if (compare is TwoRegisterInstruction && compare.opcode in TWO_REGISTER_BRANCHES) {
                literalIn(code, branch, compare.registerB) ?: literalIn(code, branch, compare.registerA)
            } else {
                0
            }
            branch to literal
        }

    /** The narrow literal last written to [register] before [index], if a constant wrote it. */
    private fun literalIn(code: List<Instruction>, index: Int, register: Int): Int? =
        (lastWrite(code, index, register) as? NarrowLiteralInstruction)?.narrowLiteral

    /** The last instruction before [index] in code order that writes [register]. */
    private fun lastWrite(code: List<Instruction>, index: Int, register: Int): Instruction? {
        for (at in index - 1 downTo 0) {
            val write = code[at]
            if (write.opcode.setsRegister() && (write as? OneRegisterInstruction)?.registerA == register) return write
        }
        return null
    }

    private fun Instruction.reads(holder: FieldReference) = opcode == Opcode.SGET_OBJECT &&
        getReference<FieldReference>()?.let { it.definingClass == holder.definingClass && it.name == holder.name } == true

    private fun Instruction.storesIcon() = opcode == Opcode.IPUT_OBJECT && getReference<FieldReference>()?.type == TUX_ICON

    private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

    private fun classes(apk: File): Map<String, ClassDef> {
        val byType = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
        }
        return byType
    }
}
