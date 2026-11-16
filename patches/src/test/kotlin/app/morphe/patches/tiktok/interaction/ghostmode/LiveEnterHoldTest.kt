/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.interaction.live.LIVE_PLAY_FRAGMENT
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
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
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

    /**
     * The eye lets the host see you by having the page enter the room again through its own
     * enter, whose success builds the chat and host bar the held enter never brought. On each
     * declared build the room enter and its reset are one page method each, the plan reads one
     * public callback, field and send off the enter, and the filled bridge resets, makes the
     * callback from the page and the room id, keeps it and sends it, then answers true.
     */
    @Test
    fun `each declared build enters a LIVE room again through the page's own enter`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val byType = classes.associateBy { it.type }
            fun matches(fingerprint: Fingerprint) = classes.flatMap { cls ->
                cls.methods.filter { it.implementation != null && fingerprint.takes(it, cls) }
            }.distinctBy { it.toString() }
            val enters = matches(LiveRoomEnterFingerprint)
            val resets = matches(LiveRoomEnterResetFingerprint)
            assertEquals("${apk.name}: room enters ${enters.map { it.name }}", 1, enters.size)
            assertEquals("${apk.name}: enter resets ${resets.map { it.name }}", 1, resets.size)

            val plan = planLiveReEnter(enters.single(), resets.single()) { byType[it] }
            assertEquals(listOf(LIVE_PLAY_FRAGMENT, "J"), plan.callback.parameterTypes.map { it.toString() })
            assertEquals(LIVE_PLAY_FRAGMENT, plan.kept.definingClass)
            assertEquals(plan.callback.definingClass, plan.kept.type)
            assertEquals(LIVE_PLAY_FRAGMENT, plan.send.definingClass)
            assertEquals("J", plan.send.parameterTypes[0].toString())
            assertTrue(
                "${apk.name}: the callback is what the send takes",
                plan.send.parameterTypes[1].toString() in byType.getValue(plan.callback.definingClass).interfaces,
            )

            val bridge = MutableMethod(reEnterStub())
            bridge.enterLiveRoomAgain(plan)
            val code = bridge.implementation!!.instructions.toList()
            assertEquals(
                listOf(
                    Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT,
                    Opcode.IPUT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.CONST_4, Opcode.RETURN,
                ),
                code.take(8).map { it.opcode },
            )
            fun ref(index: Int) = (code[index] as ReferenceInstruction).reference.toString()
            assertEquals("$LIVE_PLAY_FRAGMENT->${resets.single().name}()V", ref(1))
            assertEquals(plan.callback.definingClass, ref(2))
            assertEquals(plan.callback.toString(), ref(3))
            assertEquals(plan.kept.toString(), ref(4))
            assertEquals(plan.send.toString(), ref(5))
            // p0 is v2 and the room id p1-p2 is v3-v4 in the stub's five registers.
            val made = code[3] as FiveRegisterInstruction
            assertEquals(listOf(0, 2, 3, 4), listOf(made.registerC, made.registerD, made.registerE, made.registerF))
            val sent = code[5] as FiveRegisterInstruction
            assertEquals(listOf(2, 3, 4, 0), listOf(sent.registerC, sent.registerD, sent.registerE, sent.registerF))
        }
    }

    @Test
    fun `a room enter without its callback, or a bridge of the wrong shape, stops the patch`() {
        val enter = ImmutableMethod(
            LIVE_PLAY_FRAGMENT, "U30",
            listOf(ImmutableMethodParameter("J", null, null), ImmutableMethodParameter("Z", null, null)),
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(4, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        val reset = ImmutableMethod(
            LIVE_PLAY_FRAGMENT, "W20", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        assertThrows(PatchException::class.java) { planLiveReEnter(enter, reset) { null } }

        val plan = LiveReEnter(
            reset,
            ImmutableMethodReference("LX/cb;", "<init>", listOf(LIVE_PLAY_FRAGMENT, "J"), "V"),
            ImmutableFieldReference(LIVE_PLAY_FRAGMENT, "n2", "LX/cb;"),
            ImmutableMethodReference(LIVE_PLAY_FRAGMENT, "T30", listOf("J", "LX/iface;"), "V"),
        )
        val voidBridge = ImmutableMethod(
            LIVE_UNSEEN_EXTENSION, "enterAgain",
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null), ImmutableMethodParameter("J", null, null)),
            "V", AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(5, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        assertThrows(PatchException::class.java) { MutableMethod(voidBridge).enterLiveRoomAgain(plan) }
    }

    /** The extension's enterAgain(Object, long) stub, `return false`, with the spare register the patch adds. */
    private fun reEnterStub(): Method = ImmutableMethod(
        LIVE_UNSEEN_EXTENSION,
        "enterAgain",
        listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null), ImmutableMethodParameter("J", null, null)),
        "Z",
        AccessFlags.STATIC.value,
        null,
        null,
        ImmutableMethodImplementation(
            5,
            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)),
            null,
            null,
        ),
    )

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
