/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LINEAR_LAYOUT_MANAGER = "Landroidx/recyclerview/widget/LinearLayoutManager;"

/**
 * What Keep your place in chats (#503) hooks, held to each declared build: the list ability's one
 * method that handles a chat's list changes with its one call to the scroll decider, the
 * ability's one getter for the chat's layout manager, and the hook going in right after the
 * decider's answer with TikTok's code around it unchanged. It also holds what the extension reads
 * off TikTok's objects by name: the layout manager's `findFirstVisibleItemPosition()`, the event
 * names it tells apart, and the send event's one message field with `getReferenceInfo()`.
 */
class KeepChatPlaceAnchorsTest {
    private val patch = "Hide inbox items"

    @Test
    fun `each build has one list consumer with one scroll decision, and the hook fits`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val taken = classes.values.flatMap { classDef ->
                classDef.methods.filter { ChatScrollDecisionFingerprint.takes(it, classDef) }.map { it to classDef }
            }
            assertEquals("$version: list consumers ${taken.map { "${it.first.definingClass}->${it.first.name}" }}", 1, taken.size)
            val (method, ability) = taken.single()
            val hook = chatScrollHook(patch, method, ability)

            checkDecision(version, method, hook)
            checkLayoutManager(version, hook, classes)
            checkEvents(version, method, hook, classes)
            checkHook(version, method, hook)
        }
    }

    private fun checkDecision(version: String, native: Method, hook: ChatScrollHook) {
        val instructions = native.implementation!!.instructions.toList()
        val call = instructions[hook.decision.answer - 1]
        assertEquals("$version: the decider is an interface call", Opcode.INVOKE_INTERFACE, call.opcode)
        val decider = call.getReference<MethodReference>()!!
        assertEquals("$version: ${decider.definingClass}->${decider.name} answers a boolean", "Z", decider.returnType)
        assertEquals("$version: the event is the method's own parameter", native.implementation!!.registerCount - 1, hook.decision.eventRegister)
        assertTrue(
            "$version: nothing before the decider puts anything but the event in its register",
            instructions.take(hook.decision.answer - 1).none { it.writes(hook.decision.eventRegister) },
        )
        assertEquals("$version: p0 is the ability", native.implementation!!.registerCount - 2, hook.self)
    }

    private fun checkLayoutManager(version: String, hook: ChatScrollHook, classes: Map<String, ClassDef>) {
        assertEquals("$version: the getter answers the chat's layout manager", CHAT_LAYOUT_MANAGER, hook.layoutManager.returnType)
        val chain = generateSequence(CHAT_LAYOUT_MANAGER) { classes[it]?.superclass }.take(8).toList()
        assertTrue("$version: $CHAT_LAYOUT_MANAGER descends from $LINEAR_LAYOUT_MANAGER: $chain", LINEAR_LAYOUT_MANAGER in chain)
        val linear = classes[LINEAR_LAYOUT_MANAGER] ?: error("$version: $LINEAR_LAYOUT_MANAGER is missing")
        assertTrue(
            "$version: the extension calls findFirstVisibleItemPosition()I by name",
            linear.methods.any {
                it.name == "findFirstVisibleItemPosition" && it.returnType == "I" && it.parameterTypes.isEmpty() &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags)
            },
        )
    }

    /**
     * The event names the extension tells apart, each found by its event's toString among the
     * decider's event classes and answered by that class's `getName()`, its own or the patch's.
     */
    private fun checkEvents(version: String, native: Method, hook: ChatScrollHook, classes: Map<String, ClassDef>) {
        val decider = native.implementation!!.instructions.toList()[hook.decision.answer - 1].getReference<MethodReference>()!!
        assertEquals("$version: the hook reads the decider's event type", decider.parameterTypes[0].toString(), hook.eventType)
        val found = ChatEventFingerprints.mapValues { (name, fingerprint) ->
            val taken = classes.values.filter { classDef -> classDef.methods.any { fingerprint.takes(it, classDef) } }
            assertEquals("$version: $name's toString ${taken.map { it.type }}", 1, taken.size)
            taken.single()
        }
        val events = chatEventNames(patch, hook.eventType, found)
        assertEquals(listOf("OnSendMessageEvent", "OnGetMessageEvent", "OnQueryMessageEvent"), events.map { it.name })
        for (event in events) {
            val getName = if (event.add) chatEventNameMethod(event) else found.getValue(event.name).methods.single { it.name == CHAT_EVENT_NAME }
            assertEquals("$version: ${event.type}->getName()", event.name, getName.constantName())
            assertEquals(event.type, getName.definingClass)
            assertTrue("$version: the extension reaches ${event.type}->getName()", AccessFlags.PUBLIC.isSet(getName.accessFlags))
            assertTrue(!AccessFlags.STATIC.isSet(getName.accessFlags) && getName.parameterTypes.isEmpty())
            assertEquals("Ljava/lang/String;", getName.returnType)
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(getName))
        }
        val send = found.getValue("OnSendMessageEvent")
        val messages = send.fields.filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) &&
                classes[field.type]?.methods?.any { it.name == "getReferenceInfo" && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") } == true
        }
        assertEquals("$version: ${send.type}'s message fields ${messages.map { it.name }}", 1, messages.size)
    }

    @Test
    fun `an event that already answers its name keeps it, and one answering another name stops the patch`() {
        fun event(vararg methods: Method) = ImmutableClassDef("LX/0aG2;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            listOf("LX/0O6h;"), null, null, null, methods.toList())
        val named = chatEventNameMethod(ChatEventName("LX/0aG2;", "OnSendMessageEvent", true))
        assertEquals(listOf(ChatEventName("LX/0aG2;", "OnSendMessageEvent", false)),
            chatEventNames(patch, "LX/0O6h;", mapOf("OnSendMessageEvent" to event(named))))
        assertEquals(listOf(ChatEventName("LX/0aG2;", "OnSendMessageEvent", true)),
            chatEventNames(patch, "LX/0O6h;", mapOf("OnSendMessageEvent" to event())))
        assertThrows(PatchException::class.java) { chatEventNames(patch, "LX/0O6h;", mapOf("OnGetMessageEvent" to event(named))) }
        assertThrows(PatchException::class.java) { chatEventNames(patch, "LX/0O6i;", mapOf("OnSendMessageEvent" to event())) }
    }

    private fun checkHook(version: String, native: Method, hook: ChatScrollHook) {
        val before = native.implementation!!.instructions.toList()
        val at = hook.decision.answer + 1
        val patched = MutableMethod(native)
        patched.keepChatPlace(hook)
        val after = patched.implementation!!.instructions.toList()

        assertEquals("$version: four instructions added", before.size + 4, after.size)
        // The rebuilt method writes its backward gotos as goto/16, the same jump in a wider form.
        fun List<Instruction>.shape() = map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode }
        assertEquals("$version: TikTok's code up to the answer", before.take(at).shape(), after.take(at).shape())
        assertEquals("$version: TikTok's code after the hook", before.drop(at).shape(), after.drop(at + 4).shape())
        assertEquals(
            listOf(Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT),
            after.subList(at, at + 4).map { it.opcode },
        )
        val getter = after[at] as FiveRegisterInstruction
        assertEquals("$version: the layout manager comes off the ability in p0", hook.self, getter.registerC)
        assertEquals(1, getter.registerCount)
        assertEquals(SUBMIT_LIST_ABILITY, hook.layoutManager.definingClass)
        assertEquals(hook.layoutManager.toString(), after[at].getReference<MethodReference>().toString())
        assertEquals(hook.scratch, (after[at + 1] as OneRegisterInstruction).registerA)
        val call = after[at + 2] as FiveRegisterInstruction
        assertEquals("$KEEP_CHAT_PLACE->scrollToNewest(ILjava/lang/Object;Ljava/lang/Object;)Z", after[at + 2].getReference<MethodReference>().toString())
        assertEquals(3, call.registerCount)
        assertEquals("$version: TikTok's answer goes in", hook.decision.answerRegister, call.registerC)
        assertEquals("$version: with the layout manager", hook.scratch, call.registerD)
        assertEquals("$version: and the event", hook.decision.eventRegister, call.registerE)
        assertEquals("$version: and the extension's answer replaces it", hook.decision.answerRegister, (after[at + 3] as OneRegisterInstruction).registerA)
        assertTrue(
            "$version: the scratch register is none of the ones the hook reads",
            hook.scratch !in setOf(hook.self, hook.decision.answerRegister, hook.decision.eventRegister),
        )
        assertTrue(
            "$version: TikTok writes the scratch register before it reads it again",
            before.drop(at).firstOrNull { it.reads(hook.scratch) || it.writes(hook.scratch) }?.writes(hook.scratch) == true,
        )
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
    }

    @Test
    fun `only one decider call whose answer is taken and not branched past is a decision`() {
        assertEquals(ChatScrollDecision(1, 0, 7), accept(decide(), answer(), nanoTime(), returnVoid()).chatScrollDecision())
        assertNull("the answer isn't taken", accept(decide(), ImmutableInstruction10x(Opcode.NOP), nanoTime(), returnVoid()).chatScrollDecision())
        assertNull("two deciders", accept(decide(), answer(), decide(), answer(), returnVoid()).chatScrollDecision())
        assertNull(
            "a decider answering an int",
            accept(decide(ImmutableMethodReference(DECIDER.definingClass, DECIDER.name, DECIDER.parameterTypes, "I")), answer(), returnVoid())
                .chatScrollDecision(),
        )
        // if-eqz (2 code units) jumps over the decider (3) and its move-result (1) to nanoTime at 6.
        assertNull(
            "a branch landing where the hook goes",
            accept(ImmutableInstruction21t(Opcode.IF_EQZ, 3, 6), decide(), answer(), nanoTime(), returnVoid()).chatScrollDecision(),
        )
    }

    private val DECIDER = ImmutableMethodReference("LX/0O8Q;", "LIZ", listOf("LX/0O6h;", "Ljava/util/List;", "LX/0Tdk;", "Z"), "Z")

    private fun decide(reference: MethodReference = DECIDER) = ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 5, 2, 7, 5, 1, 0, reference)

    private fun answer() = ImmutableInstruction11x(Opcode.MOVE_RESULT, 0)

    private fun nanoTime() = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
        ImmutableMethodReference("Ljava/lang/System;", "nanoTime", listOf(), "J"))

    private fun returnVoid() = ImmutableInstruction10x(Opcode.RETURN_VOID)

    /** The ability's list change handler cut down, as 48.0.2 has it: eight registers, the event in p1 (v7). */
    private fun accept(vararg code: Instruction): Method = ImmutableMethod(
        SUBMIT_LIST_ABILITY, "LJIILL", listOf(ImmutableMethodParameter("LX/0O6h;", null, null)), "V",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(8, code.toList(), null, null),
    )

    private fun Instruction.writes(register: Int): Boolean {
        if (this !is OneRegisterInstruction || !opcode.setsRegister() || opcode == Opcode.CHECK_CAST) return false
        return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
    }

    private fun Instruction.reads(register: Int): Boolean = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount).contains(register)
        is TwoRegisterInstruction -> registerB == register || (!opcode.setsRegister() && registerA == register)
        is OneRegisterInstruction -> !opcode.setsRegister() && registerA == register
        else -> false
    }
}
