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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A story Ghost mode held back keeps a watched ring (#427): each declared build's story ring
 * method, the one with TikTok's computeStoryStatusByMetaData log line, first hands the person and
 * the left-out stories to the extension, before it reads the status and the viewed flags.
 */
class StoryStatusReadsHookTest {
    @Test
    fun `each declared build hands the person to the extension before its story ring is worked out`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val rings = container.dexEntryNames
                .flatMap { container.getEntry(it)!!.dexFile.classes }
                .flatMap { it.methods }
                .filter { method ->
                    method.parameterTypes.map { it.toString() } == listOf(STORY_USER, STORY_FILTER_INFO, STORY_ALL_METADATA) &&
                        method.implementation?.instructions?.any { it.loadsRingLog() } == true
                }
                .distinctBy { it.toString() }
            assertEquals("${apk.name}: story ring methods", 1, rings.size)

            val ring = rings.single()
            val stock = ring.implementation!!.instructions.toList()
            val method = MutableMethod(ring)
            method.keepWatchedStoriesWatched()
            val code = method.implementation!!.instructions.toList()

            val call = code.first()
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals(
                "$STORY_READS_EXTENSION->markWatched(Ljava/lang/Object;Ljava/lang/Object;)V",
                (call as ReferenceInstruction).reference.toString(),
            )
            // The person is the first parameter, whether or not a this register sits before it.
            val person = ring.implementation!!.registerCount - ring.parameterTypes.size
            assertEquals(listOf(person, 2), (call as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
            assertEquals(stock.map { it.opcode }, code.drop(1).map { it.opcode })
        }
    }

    @Test
    fun `a ring method that no longer starts with the person stops the patch`() {
        assertThrows(PatchException::class.java) { MutableMethod(otherShape()).keepWatchedStoriesWatched() }
    }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.loadsRingLog(): Boolean =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string ==
            "computeStoryStatusByMetaData, previousStatus: "

    private fun otherShape(): Method = ImmutableMethod(
        "LX/Ring;",
        "LJI",
        listOf(ImmutableMethodParameter(STORY_ALL_METADATA, null, null), ImmutableMethodParameter(STORY_USER, null, null)),
        "Ljava/lang/Object;",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        ImmutableMethodImplementation(2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
    )
}
