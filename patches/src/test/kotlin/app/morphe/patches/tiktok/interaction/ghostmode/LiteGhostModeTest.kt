/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.live.LIVE_PLAY_FRAGMENT
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #502: Ghost mode in TikTok Lite 47.8.2. Lite's story view, profile visit, typing, play report
 * and activity status reporters are each found once, each takes its guard in front of the send
 * with nothing else moved, and LIVE enters, likes and the story ring stay TikTok's because Lite
 * has nothing to hook them in.
 */
class LiteGhostModeTest {
    private val classes: Map<String, ClassDef> by lazy {
        val container = Fixtures.dexContainer(Fixtures.liteApk())
        container.dexEntryNames.flatMap { name -> checkNotNull(container.getEntry(name)).dexFile.classes }
            .associateBy { it.type }
    }

    private fun taken(fingerprint: Fingerprint): List<Pair<ClassDef, Method>> =
        classes.values.flatMap { owner -> owner.methods.filter { fingerprint.takes(it, owner) }.map { owner to it } }

    private fun mutable(found: Pair<ClassDef, Method>): MutableMethod = MutableClass(found.first).methods.single {
        it.name == found.second.name && it.returnType == found.second.returnType &&
            it.parameterTypes.map(CharSequence::toString) == found.second.parameterTypes.map(CharSequence::toString)
    }

    private fun only(fingerprint: Fingerprint): MutableMethod {
        val found = taken(fingerprint)
        assertEquals("$fingerprint: ${found.map { "${it.first.type}->${it.second.name}" }}", 1, found.size)
        return mutable(found.single())
    }

    private fun code(method: Method) = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }

    private fun called(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference as? MethodReference

    /** Checks the guard at the top of [method] asks [guard] and leaves through [exit], and returns what follows it. */
    private fun guardOf(method: Method, guard: String, exit: List<Opcode>): List<Instruction> {
        val code = code(method)
        assertEquals("${method.definingClass}: asks the extension first", GHOST_MODE_EXTENSION, called(code[0])?.definingClass)
        assertEquals("${method.definingClass}: asks $guard", guard, called(code[0])?.name)
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals("${method.definingClass}: leaves through", exit, code.subList(3, 3 + exit.size).map { it.opcode })
        return code.drop(3 + exit.size)
    }

    @Test
    fun `the story view lambda answers null before it reports while held`() {
        val method = only(LiteStoryViewReportFingerprint)
        assertTrue("a Function0", classes.getValue(method.definingClass).interfaces.contains("Lkotlin/jvm/functions/Function0;"))
        val stock = code(method).map { it.opcode }
        planLiteStoryViewSkip(method)
        method.skipLiteStoryView()
        assertEquals("nothing else moves", stock,
            guardOf(method, "shouldBlockStoryView", listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT)).map { it.opcode })
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
    }

    @Test
    fun `the profile visit call answers Unit before it records while held`() {
        val method = only(LiteProfileViewReportFingerprint)
        assertTrue("a Callable", classes.getValue(method.definingClass).interfaces.contains("Ljava/util/concurrent/Callable;"))
        val stock = code(method).map { it.opcode }
        val unit = planLiteProfileViewSkip(method)
        assertTrue(unit, unit.startsWith("Lkotlin/Unit;->") && unit.endsWith(":Lkotlin/Unit;"))
        method.skipLiteProfileView(unit)
        val rest = guardOf(method, "shouldBlockProfileView", listOf(Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT))
        val answer = (code(method)[3] as ReferenceInstruction).reference as FieldReference
        assertEquals("the Unit the call answers anyway", unit, "${answer.definingClass}->${answer.name}:${answer.type}")
        assertEquals("nothing else moves", stock, rest.map { it.opcode })
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
    }

    @Test
    fun `the typing sender returns before it launches the send while held`() {
        val senders = taken(TypingStatusSenderFingerprint).filter { it.second.implementation != null }
        assertEquals(1, senders.size)
        val method = mutable(senders.single())
        val stock = code(method).map { it.opcode }
        assertTrue(method.returnBeforeReporting("shouldBlockTypingStatus"))
        assertEquals("nothing else moves", stock,
            guardOf(method, "shouldBlockTypingStatus", listOf(Opcode.RETURN_VOID)).map { it.opcode })
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
    }

    @Test
    fun `the play report sender asks about the Aweme before it builds the report`() {
        val method = only(LiteStoryStatsSenderFingerprint)
        assertTrue("static", AccessFlags.STATIC.isSet(method.accessFlags))
        val stock = code(method).map { it.opcode }
        val aweme = planLiteStoryStatsGuard(method)
        method.guardLiteStoryStats(aweme)
        val rest = guardOf(method, "shouldBlockStoryStats", listOf(Opcode.RETURN_VOID))
        val ask = code(method)[0] as RegisterRangeInstruction
        val awemeRegister = method.implementation!!.registerCount - 6 + method.parameterTypes.map { it.toString() }.indexOf(AWEME)
        assertEquals("with the Aweme parameter", listOf(awemeRegister, 1), listOf(ask.startRegister, ask.registerCount))
        assertEquals("nothing else moves", stock, rest.map { it.opcode })
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
    }

    @Test
    fun `the activity status report takes no API object while held`() {
        val method = only(LiteOnlineStatusReportFingerprint)
        val stock = code(method)
        val before = BridgeTypeFlow.check(method).map { it.message }
        val gate = planLiteOnlineStatusGate(method) { classes[it] }
        method.gateLiteOnlineStatus(gate)

        val code = code(method)
        val hooks = code.indices.filter { called(code[it])?.name == "onlineStatusReporter" }
        assertEquals(1, hooks.size)
        val hook = hooks.single()
        val register = (code[hook] as RegisterRangeInstruction).startRegister
        assertEquals("read from its field just before", gate.apiType, ((code[hook - 1] as ReferenceInstruction).reference as FieldReference).type)
        assertEquals(register, (code[hook - 1] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals(register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CHECK_CAST, code[hook + 2].opcode)
        assertEquals("cast back to the API", gate.apiType, ((code[hook + 2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals(register, (code[hook + 2] as OneRegisterInstruction).registerA)
        assertEquals("then Lite's own null check", Opcode.IF_EQZ, code[hook + 3].opcode)
        assertEquals(register, (code[hook + 3] as OneRegisterInstruction).registerA)
        assertEquals("nothing else moves", stock.map { it.opcode },
            code.filterIndexed { index, _ -> index !in hook..hook + 2 }.map { it.opcode })
        assertTrue("the report is still made on that API", code.drop(hook + 3).any {
            called(it)?.definingClass == gate.apiType && it.opcode == Opcode.INVOKE_INTERFACE
        })
        assertEquals("the hook adds no type problem", before, BridgeTypeFlow.check(method).map { it.message })
    }

    @Test
    fun `a method that isn't the reporter fails Ghost mode by name`() {
        val story = only(LiteStoryViewReportFingerprint)
        val profile = only(LiteProfileViewReportFingerprint)
        val status = only(LiteOnlineStatusReportFingerprint)
        fun constructor(of: Method) = MutableClass(classes.getValue(of.definingClass)).methods.first { it.name == "<init>" }
        for (thrown in listOf(
            assertThrows(PatchException::class.java) { planLiteStoryViewSkip(constructor(story)) },
            assertThrows(PatchException::class.java) { planLiteProfileViewSkip(constructor(profile)) },
            assertThrows(PatchException::class.java) { planLiteOnlineStatusGate(constructor(status)) { classes[it] } },
            assertThrows(PatchException::class.java) { planLiteOnlineStatusGate(status) { null } },
        )) assertTrue(thrown.message, thrown.message!!.startsWith("Ghost mode: "))
    }

    /** What the rest of Ghost mode hooks on TikTok, none of which Lite has. */
    @Test
    fun `Lite has no network lancet, LIVE page or story ring to hook`() {
        assertNull(classes[CALL_SERVER_INTERCEPTOR])
        assertNull(classes[LIVE_PLAY_FRAGMENT])
        assertEquals(emptyList<Pair<ClassDef, Method>>(), taken(StoryStatusComputeFingerprint))
        assertEquals(emptyList<Pair<ClassDef, Method>>(), taken(OnlineStatusRegularReportFingerprint))
    }
}
