/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BUTTONS = "Lapp/morphe/extension/tiktok/feed/StoryRowButtons;"

/**
 * What the story row switches hook (#308, #309), held to each declared build: each story cell has
 * one bind asking whether the viewer's own item has no Story, and the skylight list shows All in
 * exactly two methods. The hooks are one straight call each with the registers TikTok's own write
 * and call use, and nothing in TikTok's code branches around the Create hooks.
 */
class StoryRowButtonsAnchorsTest {
    @Test
    fun `each story cell has one bind asking about the viewer's Story and the hooks read its registers`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            for (cell in listOf(FOLLOWING_STORY_CELL, INBOX_STORY_CELL)) {
                val binds = methodsOf(apk, cell).filter(::isStoryCellBind)
                assertEquals("$version: $cell binds ${binds.map { it.name }}", 1, binds.size)
                val bind = binds.single()
                val code = bind.implementation!!.instructions.toList()
                val owner = storyOwnerWriteIndex(bind)
                val answer = storyAnswerIndex(bind)
                val write = code[owner] as TwoRegisterInstruction
                val result = (code[answer] as OneRegisterInstruction).registerA

                // A yes skips the flag that picks the "your Story" label, so yes means no Story up.
                assertEquals("$version: $cell's answer", Opcode.IF_NEZ, code[answer + 1].opcode)
                assertEquals("$version: $cell's owner branch", Opcode.IF_EQZ, code.drop(owner + 1).first { it is OffsetInstruction }.opcode)
                assertFalse("$version: $cell branches past the owner", branchedOnto(code).contains(owner + 1))
                assertFalse("$version: $cell branches past the answer", branchedOnto(code).contains(answer + 1))

                val hooked = MutableMethod(bind)
                hooked.hookStoryCreateCell("Hide video overlays")
                val after = hooked.implementation!!.instructions.toList()

                val ownerCall = after[owner + 1]
                assertEquals(Opcode.INVOKE_STATIC, ownerCall.opcode)
                val ownerTarget = ownerCall.getReference<MethodReference>()!!
                assertEquals(BUTTONS, ownerTarget.definingClass)
                assertEquals("onStoryCellOwner", ownerTarget.name)
                assertEquals(listOf("Ljava/lang/Object;", "I"), ownerTarget.parameterTypes.map(CharSequence::toString))
                assertEquals(write.registerB, (ownerCall as FiveRegisterInstruction).registerC)
                assertEquals(write.registerA, ownerCall.registerD)

                val answerCall = after[answer + 2]
                assertEquals(Opcode.INVOKE_STATIC, answerCall.opcode)
                val answerTarget = answerCall.getReference<MethodReference>()!!
                assertEquals(BUTTONS, answerTarget.definingClass)
                assertEquals("onOwnCellAnswer", answerTarget.name)
                assertEquals(listOf("Ljava/lang/Object;", "I"), answerTarget.parameterTypes.map(CharSequence::toString))
                assertEquals(write.registerB, (answerCall as FiveRegisterInstruction).registerC)
                assertEquals(result, answerCall.registerD)

                // The cell's register still holds the cell by the time of the answer.
                (owner + 1..answer).forEach { index ->
                    val writes = (code[index] as? OneRegisterInstruction)?.registerA == write.registerB &&
                        code[index].opcode.setsRegister()
                    assertFalse("$version: $cell overwrites the cell before the answer", writes)
                }
                assertEquals(
                    "$version: $cell's own code moved",
                    code.map { it.opcode }.filter { it != Opcode.NOP },
                    after.filterIndexed { index, _ -> index != owner + 1 && index != answer + 2 }
                        .map { it.opcode }.filter { it != Opcode.NOP },
                )
            }
        }
    }

    @Test
    fun `the skylight list shows All in two methods and the hook takes the button TikTok just set`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val shows = methodsOf(apk, SKYLIGHT_LIST_ASSEM).filter { allButtonShowIndex(it) >= 0 }
            assertEquals("$version: shows ${shows.map { it.name }}", 2, shows.size)
            assertTrue("$version: onViewCreated isn't one of them", shows.any { it.name == "onViewCreated" })
            for (method in shows) {
                val code = method.implementation!!.instructions.toList()
                val show = allButtonShowIndex(method)
                val button = (code[show] as FiveRegisterInstruction).registerD

                val hooked = MutableMethod(method)
                hooked.hookAllButtonShow("Hide video overlays")
                val after = hooked.implementation!!.instructions.toList()
                val call = after[show + 1]
                assertEquals(Opcode.INVOKE_STATIC, call.opcode)
                val target = call.getReference<MethodReference>()!!
                assertEquals(BUTTONS, target.definingClass)
                assertEquals("hideAllButton", target.name)
                assertEquals(listOf("Landroid/view/View;"), target.parameterTypes.map(CharSequence::toString))
                assertEquals(button, (call as FiveRegisterInstruction).registerC)
                assertEquals(
                    "$version: ${method.name}'s own code moved",
                    code.map { it.opcode }.filter { it != Opcode.NOP },
                    after.filterIndexed { index, _ -> index != show + 1 }.map { it.opcode }.filter { it != Opcode.NOP },
                )
            }
        }
    }

    @Test
    fun `a method without either shape stops the patch untouched`() {
        val moved = MutableMethod(
            ImmutableMethod(
                FOLLOWING_STORY_CELL, "e6",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
            ),
        )
        val before = moved.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { moved.hookStoryCreateCell("Hide video overlays") }
        assertThrows(PatchException::class.java) { moved.hookAllButtonShow("Hide video overlays") }
        assertEquals(before, moved.implementation!!.instructions.toList())
    }

    /** Every instruction index a branch lands on. */
    private fun branchedOnto(code: List<Instruction>): Set<Int> {
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        return code.indices.filter { code[it] is OffsetInstruction }.mapNotNull { index ->
            addresses.indexOf(addresses[index] + (code[index] as OffsetInstruction).codeOffset).takeIf { it >= 0 }
        }.toSet()
    }

    private fun methodsOf(apk: File, type: String): List<Method> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type == type) return classDef.methods.toList()
            }
        }
        return emptyList()
    }
}
