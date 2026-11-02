/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private const val ROW = "Lapp/morphe/extension/tiktok/share/CommentSendToRow;"

/**
 * The comment menu's Send to row (#313), held to each declared build: one FrameLayout logs under
 * the row's own tag from a (List)V callback, the list goes through the extension first, and the
 * attach hook takes the register the superclass call used.
 */
class CommentSendToRowAnchorsTest {
    @Test
    fun `one row class takes the contacts and the hook hands them over first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val rows = sendToRows(apk)
            assertEquals("$version: rows ${rows.map { it.type }}", 1, rows.size)
            val load = rows.single().methods.single(::isCommentSendToLoad)

            val hooked = MutableMethod(load)
            hooked.hookCommentSendToLoad()
            val code = hooked.implementation!!.instructions.toList()
            val parameter = load.implementation!!.registerCount - 1

            assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
            val call = code[0].getReference<MethodReference>()!!
            assertEquals(ROW, call.definingClass)
            assertEquals("people", call.name)
            assertEquals("Ljava/util/List;", call.returnType)
            assertEquals(parameter, (code[0] as RegisterRangeInstruction).startRegister)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
            assertEquals(parameter, (code[1] as OneRegisterInstruction).registerA)
        }
    }

    @Test
    fun `the row's attach hands over the view the superclass call used`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val attach = sendToRows(apk).single().methods.single {
                it.name == "onAttachedToWindow" && it.parameterTypes.isEmpty()
            }
            val code = attach.implementation!!.instructions.toList()
            val index = sendToRowAttachIndex(attach)
            assertEquals("$version: the superclass call moved", 1, index)

            val hooked = MutableMethod(attach)
            hooked.hookCommentSendToAttach("Share sheet tools")
            val after = hooked.implementation!!.instructions.toList()
            val call = after[index + 1]
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals("onAttached", call.getReference<MethodReference>()!!.name)
            assertEquals(ROW, call.getReference<MethodReference>()!!.definingClass)
            assertEquals(
                (code[index] as FiveRegisterInstruction).registerC,
                (call as FiveRegisterInstruction).registerC,
            )
            assertEquals(
                "$version: the row's own code moved",
                code.map { it.opcode }.filter { it != Opcode.NOP },
                after.filterIndexed { at, _ -> at != index + 1 }.map { it.opcode }.filter { it != Opcode.NOP },
            )
        }
    }

    @Test
    fun `a row that stopped calling its superclass stops the patch untouched`() {
        val moved = MutableMethod(
            ImmutableMethod(
                "LX/row;", "onAttachedToWindow", emptyList(), "V",
                AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
            ),
        )
        val before = moved.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { moved.hookCommentSendToAttach("Share sheet tools") }
        assertEquals(before, moved.implementation!!.instructions.toList())
    }

    private fun sendToRows(apk: File): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.filter { classDef ->
                classDef.superclass == SEND_TO_ROW_SUPERCLASS && classDef.methods.any(::isCommentSendToLoad)
            }
        }
    }
}
