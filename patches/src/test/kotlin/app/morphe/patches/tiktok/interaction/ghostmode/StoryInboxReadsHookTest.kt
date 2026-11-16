/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The inbox's story row keeps a story Ghost mode held back as watched (#427): right after each
 * declared build's StoryInboxItem constructor stores the ring status TikTok worked out, the hook
 * hands that status to the extension and stores the answer, with the store's own registers.
 */
class StoryInboxReadsHookTest {
    @Test
    fun `each declared build hands the inbox item's status to the extension and stores its answer`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val constructors = container.dexEntryNames
                .flatMap { container.getEntry(it)!!.dexFile.classes }
                .filter { it.type == STORY_INBOX_ITEM }
                .flatMap { it.methods }
                .filter { it.name == "<init>" }
                .distinctBy { it.toString() }
            assertEquals("${apk.name}: StoryInboxItem constructors", 1, constructors.size)

            val stock = constructors.single().implementation!!.instructions.toList()
            val method = MutableMethod(constructors.single())
            method.keepWatchedStoriesWatched()
            val code = method.implementation!!.instructions.toList()

            val store = stock.indexOfFirst { it.opcode == Opcode.IPUT && it.writesStatus() }
            val put = stock[store] as TwoRegisterInstruction
            val call = code[store + 1]
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals(
                "$STORY_READS_EXTENSION->inboxStatus(Ljava/lang/Object;I)I",
                (call as ReferenceInstruction).reference.toString(),
            )
            assertEquals(listOf(put.registerB, put.registerA), (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            assertEquals(Opcode.MOVE_RESULT, code[store + 2].opcode)
            assertEquals(put.registerA, (code[store + 2] as OneRegisterInstruction).registerA)
            val again = code[store + 3] as TwoRegisterInstruction
            assertEquals(Opcode.IPUT, code[store + 3].opcode)
            assertEquals(listOf(put.registerA, put.registerB), listOf(again.registerA, again.registerB))
            assertEquals(true, code[store + 3].writesStatus())
            assertEquals(stock.map { it.opcode }, code.filterIndexed { i, _ -> i !in store + 1..store + 3 }.map { it.opcode })
        }
    }

    @Test
    fun `a constructor that no longer stores the status stops the patch`() {
        assertThrows(PatchException::class.java) { MutableMethod(emptyConstructor()).keepWatchedStoriesWatched() }
    }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.writesStatus(): Boolean =
        (this as? ReferenceInstruction)?.reference?.toString() == "$STORY_INBOX_ITEM->storyStatusForInbox:I"

    private fun emptyConstructor(): Method = ImmutableMethod(
        STORY_INBOX_ITEM,
        "<init>",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
        null,
        null,
        ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
    )
}
