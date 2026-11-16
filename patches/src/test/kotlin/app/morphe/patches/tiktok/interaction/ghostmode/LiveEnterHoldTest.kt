/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.takes
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch LIVEs unseen (#420): on each declared build, the hold goes right in front of TikTok's own
 * network send in the executeCall lancet, with the send's interceptor and chain registers, at the
 * start of the try whose handler turns a failure into TikTok's failed request, and on every path
 * that reached the send.
 */
class LiveEnterHoldTest {
    private val hold = "$LIVE_UNSEEN_EXTENSION->holdEnter(Ljava/lang/Object;Ljava/lang/Object;)V"

    @Test
    fun `each declared build holds LIVE enters in front of the send, inside its try`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val lancets = container.dexEntryNames
                .flatMap { container.getEntry(it)!!.dexFile.classes }
                .filter { it.type == CALL_SERVER_INTERCEPTOR }
                .flatMap { it.methods }
                .filter { it.name == NETWORK_EXECUTE_CALL && it.implementation != null }
                .distinctBy { it.toString() }
            assertEquals("${apk.name}: executeCall lancets", 1, lancets.size)

            val stock = lancets.single().implementation!!
            val stockCode = stock.instructions.toList()
            val sendIndex = stockCode.indexOfFirst { it.isSend() }
            val send = stockCode[sendIndex] as FiveRegisterInstruction
            val stockTargets = branchTargets(stock)
            val sendWasATarget = address(stockCode, sendIndex) in stockTargets

            val method = MutableMethod(lancets.single())
            method.holdLiveEnters(planLiveEnterHold(method))
            val patched = method.implementation!!
            val code = patched.instructions.toList()

            val hookIndex = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == hold }
            assertEquals("${apk.name}: the hold sits right before the send", hookIndex + 1, code.indexOfFirst { it.isSend() })
            val call = code[hookIndex] as FiveRegisterInstruction
            assertEquals(Opcode.INVOKE_STATIC, code[hookIndex].opcode)
            assertEquals(listOf(send.registerC, send.registerD), listOf(call.registerC, call.registerD))
            assertEquals(2, call.registerCount)

            val hookAddress = address(code, hookIndex)
            val sendAddress = address(code, hookIndex + 1)
            val tries = patched.tryBlocks.filter {
                hookAddress >= it.startCodeAddress && hookAddress < it.startCodeAddress + it.codeUnitCount
            }
            assertTrue("${apk.name}: the hold is inside the send's try", tries.isNotEmpty())
            assertTrue(
                "${apk.name}: that try takes an Exception",
                tries.any { block -> block.exceptionHandlers.any { it.exceptionType == "Ljava/lang/Exception;" } },
            )
            val targets = branchTargets(patched)
            assertTrue("${apk.name}: nothing jumps past the hold to the send", sendAddress !in targets)
            assertEquals("${apk.name}: a jump to the send lands on the hold", sendWasATarget, hookAddress in targets)
            assertEquals(
                stockCode.map { it.opcode },
                code.filterIndexed { index, _ -> index != hookIndex }.map { it.opcode },
            )
        }
    }

    /**
     * The held enter comes back to TikTok as a failed one, and the room page's leave then slides
     * on to the next LIVE. Each declared build has one such leave on the room page, an instance
     * method whose reason (p1) is an enum with ENTER_FAILED, with a local for the guard.
     */
    @Test
    fun `each declared build has one room page leave that takes ENTER_FAILED`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val byType = classes.associateBy { it.type }
            val leaves = classes.flatMap { cls ->
                cls.methods.filter { it.implementation != null && LiveRoomLeaveFingerprint.takes(it, cls) }
            }.distinctBy { it.toString() }
            assertEquals("${apk.name}: ${leaves.map { "${it.definingClass}->${it.name}" }}", 1, leaves.size)

            val leave = leaves.single()
            assertTrue("${apk.name}: instance method, p0 is the page", !AccessFlags.STATIC.isSet(leave.accessFlags))
            val wide = leave.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
            val parameterRegisters = 1 + leave.parameterTypes.size + wide
            assertTrue("${apk.name}: a local for the guard", leave.implementation!!.registerCount > parameterRegisters)
            val reason = byType[leave.parameterTypes.first().toString()]
            assertTrue("${apk.name}: the reason is an enum", reason != null && reason.superclass == "Ljava/lang/Enum;")
            assertTrue(
                "${apk.name}: the reason has ENTER_FAILED",
                reason!!.staticFields.any { it.name == "ENTER_FAILED" && it.type == reason.type },
            )
        }
    }

    @Test
    fun `a lancet without the send stops the patch`() {
        assertThrows(PatchException::class.java) { planLiveEnterHold(emptyLancet()) }
    }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isSend() =
        opcode == Opcode.INVOKE_VIRTUAL &&
            (this as ReferenceInstruction).reference.toString().contains("->$NETWORK_EXECUTE_CALL_TWIN(")

    private fun address(code: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>, index: Int) =
        code.take(index).sumOf { it.codeUnits }

    /** Every code address a branch or switch-free jump in [implementation] lands on. */
    private fun branchTargets(implementation: MethodImplementation): Set<Int> {
        val code = implementation.instructions.toList()
        var address = 0
        val targets = mutableSetOf<Int>()
        for (instruction in code) {
            if (instruction is OffsetInstruction &&
                instruction.opcode != Opcode.PACKED_SWITCH && instruction.opcode != Opcode.SPARSE_SWITCH &&
                instruction.opcode != Opcode.FILL_ARRAY_DATA
            ) {
                targets += address + instruction.codeOffset
            }
            address += instruction.codeUnits
        }
        return targets
    }

    private fun emptyLancet(): Method = ImmutableMethod(
        CALL_SERVER_INTERCEPTOR,
        NETWORK_EXECUTE_CALL,
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
    )
}
