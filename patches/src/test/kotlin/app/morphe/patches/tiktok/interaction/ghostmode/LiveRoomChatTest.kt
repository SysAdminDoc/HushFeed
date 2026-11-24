/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.live.LIVE_PLAY_FRAGMENT
import app.morphe.patches.tiktok.interaction.live.LIVE_ROOM_TYPE
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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
 * The chat of a LIVE watched unseen (#513), on each declared build: the message channel builder
 * asks which kind to make before it reads the kind, the websocket enter hands its room enter frame
 * over before it checks the frame for null, the room start asks about a reused channel right
 * after it gets one, and the filled bridge moves a PREPARING page on to PREPARED and starts it.
 */
class LiveRoomChatTest {
    private val modeHook = "$LIVE_UNSEEN_EXTENSION->messageMode(JILjava/lang/Object;)Ljava/lang/Object;"
    private val frameHook = "$LIVE_UNSEEN_EXTENSION->imEnter(Ljava/lang/Object;[B)[B"
    private val reuseHook = "$LIVE_UNSEEN_EXTENSION->reuseFor(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

    private class Build(val classes: List<ClassDef>) {
        val byType = classes.associateBy { it.type }

        fun matches(fingerprint: Fingerprint) = classes.flatMap { cls ->
            cls.methods.filter { it.implementation != null && fingerprint.takes(it, cls) }
        }.distinctBy { it.toString() }
    }

    private fun eachBuild(check: (String, Build) -> Unit) = Fixtures.forEachDeclared { apk ->
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
        check(apk.name, Build(classes))
    }

    private fun Instruction.ref() = (this as ReferenceInstruction).reference.toString()

    private fun registers(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(
            instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
        ).take(instruction.registerCount)
        else -> emptyList()
    }

    @Test
    fun `each declared build makes a hidden room's message channel a preview one`() = eachBuild { apk, build ->
        val builders = build.matches(LiveMessageChannelFingerprint)
        assertEquals("$apk: channel builders ${builders.map { "${it.definingClass}->${it.name}" }}", 1, builders.size)
        val stock = builders.single()
        val modeType = planMessageChannel(stock) { build.byType[it] }
        assertEquals(stock.parameterTypes[6].toString(), modeType)
        val kindClass = build.byType.getValue(modeType)
        assertEquals("Ljava/lang/Enum;", kindClass.superclass)
        assertTrue(kindClass.staticFields.any { it.name == "PREVIEW" && it.type == modeType })
        val stockCode = stock.implementation!!.instructions.toList()
        assertTrue("$apk: nothing jumps back to the entry", 0 !in targets(stockCode))

        val method = MutableMethod(stock)
        method.readHiddenRoomsAsPreview(modeType)
        val code = method.implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.MOVE_OBJECT_FROM16,
            ),
            code.take(7).map { it.opcode },
        )
        // Static: p1-p2 is the room id, p3 whether you host, p8 the kind.
        val p0 = stock.implementation!!.registerCount - 10
        fun moved(index: Int) = (code[index] as TwoRegisterInstruction).let { it.registerA to it.registerB }
        assertEquals(0 to p0 + 1, moved(0))
        assertEquals(2 to p0 + 3, moved(1))
        assertEquals(3 to p0 + 8, moved(2))
        assertEquals(modeHook, code[3].ref())
        assertEquals(listOf(0, 1, 2, 3), registers(code[3]))
        assertEquals(3, (code[4] as OneRegisterInstruction).registerA)
        assertEquals(modeType, code[5].ref())
        assertEquals(p0 + 8 to 3, moved(6))
        assertEquals(stockCode.map { kind(it.opcode) }, code.drop(7).map { kind(it.opcode) })
    }

    @Test
    fun `each declared build hands the room enter frame over before it checks it`() = eachBuild { apk, build ->
        val senders = build.matches(LiveImEnterRoomFingerprint)
        assertEquals("$apk: websocket enters ${senders.map { "${it.definingClass}->${it.name}" }}", 1, senders.size)
        val stock = senders.single()
        val hold = planImEnterHold(stock)
        val stockCode = stock.implementation!!.instructions.toList()
        assertEquals(Opcode.MOVE_RESULT_OBJECT, stockCode[hold.index - 1].opcode)
        assertEquals("[B", (stockCode[hold.index - 2] as ReferenceInstruction).reference.toString().substringAfterLast(')'))
        assertTrue("$apk: nothing jumps to the hold", address(stockCode, hold.index) !in targets(stockCode))

        val method = MutableMethod(stock)
        method.holdImEnters(hold)
        val code = method.implementation!!.instructions.toList()
        assertEquals(frameHook, code[hold.index].ref())
        assertEquals(listOf(hold.frame, hold.bytes), registers(code[hold.index]))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[hold.index + 1].opcode)
        assertEquals(hold.bytes, (code[hold.index + 1] as OneRegisterInstruction).registerA)
        // The frame's register holds the ImEnterRoom made for it, and the method stops on null.
        val made = stockCode.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && it.ref() == IM_ENTER_ROOM }
        assertEquals(hold.frame, (stockCode[made] as OneRegisterInstruction).registerA)
        val after = code[hold.index + 2]
        assertEquals(Opcode.GOTO, kind(after.opcode))
        val test = code[indexAt(code, address(code, hold.index + 2) + (after as OffsetInstruction).codeOffset)]
        assertTrue(test.opcode == Opcode.IF_NEZ || test.opcode == Opcode.IF_EQZ)
        assertEquals(hold.bytes, (test as OneRegisterInstruction).registerA)
        assertEquals(stockCode.map { kind(it.opcode) }, code.filterIndexed { i, _ -> i != hold.index && i != hold.index + 1 }.map { kind(it.opcode) })
    }

    @Test
    fun `each declared build asks about a reused channel as the room starts`() = eachBuild { apk, build ->
        val starts = build.matches(LiveRoomStartFingerprint)
        assertEquals("$apk: room starts ${starts.map { it.name }}", 1, starts.size)
        val stock = starts.single()
        val check = planReuseCheck(stock)
        val stockCode = stock.implementation!!.instructions.toList()
        assertEquals("$MESSAGE_SERVICE->getReuse()$MESSAGE_MANAGER", stockCode[check.index - 2].ref())
        assertEquals(check.channel, (stockCode[check.index - 1] as OneRegisterInstruction).registerA)
        assertTrue("$apk: nothing jumps to the check", address(stockCode, check.index) !in targets(stockCode))
        // The page register is the one the page's next field read takes.
        val pageRead = (check.index until stockCode.size).first {
            stockCode[it].opcode == Opcode.IGET_OBJECT && stockCode[it].ref().startsWith("$LIVE_PLAY_FRAGMENT->")
        }
        assertEquals(check.page, (stockCode[pageRead] as TwoRegisterInstruction).registerB)

        val method = MutableMethod(stock)
        method.keepHiddenRoomsOffReusedChannels(check)
        val code = method.implementation!!.instructions.toList()
        assertEquals(reuseHook, code[check.index].ref())
        assertEquals(listOf(check.channel, check.page), registers(code[check.index]))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[check.index + 1].opcode)
        assertEquals(check.channel, (code[check.index + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CHECK_CAST, code[check.index + 2].opcode)
        assertEquals(MESSAGE_MANAGER, code[check.index + 2].ref())
        assertEquals(check.channel, (code[check.index + 2] as OneRegisterInstruction).registerA)
        assertEquals(
            stockCode.map { kind(it.opcode) },
            code.filterIndexed { i, _ -> i !in check.index..check.index + 2 }.map { kind(it.opcode) },
        )
    }

    @Test
    fun `each declared build starts a kept room's chat through the page's own startInteraction`() = eachBuild { apk, build ->
        val starts = build.matches(LiveStartInteractionFingerprint)
        assertEquals("$apk: startInteraction ${starts.map { it.name }}", 1, starts.size)
        val plan = planLiveStart(starts.single()) { build.byType[it] }
        assertEquals(LIVE_PLAY_FRAGMENT, plan.state.definingClass)
        assertEquals(LIVE_PLAY_FRAGMENT, plan.start.definingClass)
        assertEquals(listOf(LIVE_ROOM_TYPE), plan.start.parameterTypes.map { it.toString() })
        val state = build.byType.getValue(plan.state.type)
        assertTrue(listOf("PREPARING", "PREPARED", "LIVE_STARTED").all { name -> state.staticFields.any { it.name == name } })

        val bridge = MutableMethod(startStub())
        bridge.startLiveInteraction(plan)
        val code = bridge.implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.SGET_OBJECT, Opcode.IF_NE,
                Opcode.SGET_OBJECT, Opcode.IPUT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.CONST_4, Opcode.RETURN,
                Opcode.CONST_4, Opcode.RETURN,
            ),
            code.take(12).map { it.opcode },
        )
        assertEquals(LIVE_PLAY_FRAGMENT, code[0].ref())
        assertEquals(LIVE_ROOM_TYPE, code[1].ref())
        assertEquals(plan.state.toString(), code[2].ref())
        assertEquals("${plan.state.type}->PREPARING:${plan.state.type}", code[3].ref())
        assertEquals("${plan.state.type}->PREPARED:${plan.state.type}", code[5].ref())
        assertEquals(plan.state.toString(), code[6].ref())
        assertEquals(plan.start.toString(), code[7].ref())
        // p0 is v2 and p1 is v3 in the stub's four registers.
        assertEquals(listOf(2, 3), registers(code[7]))
        val skip = code[4] as OffsetInstruction
        assertEquals("a page in any other state answers false", 10, indexAt(code, address(code, 4) + skip.codeOffset))
        assertEquals(0, (code[11] as OneRegisterInstruction).registerA)
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
    }

    @Test
    fun `a moved channel, frame, start or bridge stops the patch`() {
        val empty = ImmutableMethodImplementation(12, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
        fun method(definingClass: String, name: String, types: List<String>, returns: String, flags: Int) =
            ImmutableMethod(definingClass, name, types.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null, empty)
        val builder = method("LX/b;", "LJ", listOf("Landroid/content/Context;", "J", "Z", "J", "Z", "Z", "LX/k;", "Z"),
            MESSAGE_MANAGER, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)
        assertThrows(PatchException::class.java) { planMessageChannel(builder) { null } }
        assertThrows(PatchException::class.java) { planImEnterHold(method("LX/c;", "sendImEnterRoomMsg", listOf("Ljava/lang/String;", "J", "J", "J"), "V", AccessFlags.PUBLIC.value)) }
        assertThrows(PatchException::class.java) { planReuseCheck(method(LIVE_PLAY_FRAGMENT, "I40", emptyList(), "V", AccessFlags.PUBLIC.value)) }
        assertThrows(PatchException::class.java) { planLiveStart(method(LIVE_PLAY_FRAGMENT, "y40", listOf(LIVE_ROOM_TYPE), "V", AccessFlags.PUBLIC.value)) { null } }

        val plan = LiveStart(
            ImmutableFieldReference(LIVE_PLAY_FRAGMENT, "LLLJ", "LX/s;"),
            ImmutableMethodReference(LIVE_PLAY_FRAGMENT, "y40", listOf(LIVE_ROOM_TYPE), "V"),
        )
        val narrow = ImmutableMethod(
            LIVE_UNSEEN_EXTENSION, "startInteraction",
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null), ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            "Z", AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(3, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)), null, null),
        )
        assertThrows("one local isn't enough", PatchException::class.java) { MutableMethod(narrow).startLiveInteraction(plan) }
        val voidBridge = ImmutableMethod(
            LIVE_UNSEEN_EXTENSION, "startInteraction",
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null), ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            "V", AccessFlags.STATIC.value, null, null, empty,
        )
        assertThrows(PatchException::class.java) { MutableMethod(voidBridge).startLiveInteraction(plan) }
    }

    /** The extension's startInteraction(Object, Object) stub, `return false`, with the spare registers the patch adds. */
    private fun startStub(): Method = ImmutableMethod(
        LIVE_UNSEEN_EXTENSION,
        "startInteraction",
        listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null), ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
        "Z",
        AccessFlags.STATIC.value,
        null,
        null,
        ImmutableMethodImplementation(
            4,
            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)),
            null,
            null,
        ),
    )

    /** A goto widened by an insert still reads as a goto. */
    private fun kind(opcode: Opcode) = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private fun address(code: List<Instruction>, index: Int) = code.take(index).sumOf { it.codeUnits }

    private fun indexAt(code: List<Instruction>, address: Int): Int {
        var at = 0
        code.forEachIndexed { index, instruction ->
            if (at == address) return index
            at += instruction.codeUnits
        }
        error("no instruction at $address")
    }

    private fun targets(code: List<Instruction>): Set<Int> {
        val found = mutableSetOf<Int>()
        var at = 0
        for (instruction in code) {
            if (instruction is OffsetInstruction && instruction.opcode != Opcode.PACKED_SWITCH &&
                instruction.opcode != Opcode.SPARSE_SWITCH && instruction.opcode != Opcode.FILL_ARRAY_DATA
            ) {
                found += at + instruction.codeOffset
            }
            at += instruction.codeUnits
        }
        return found
    }
}
