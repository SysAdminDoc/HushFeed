/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PATCH = "Hide inbox items"

/**
 * What Pin more chats (#385) hooks, held to each declared build: the one lambda that reads the cap
 * on pinned chats, with TikTok's default of 5, and the extension taking its answer right after the
 * read and before the boxing the pin check unwraps.
 */
class PinMoreChatsAnchorsTest {
    @Test
    fun `each build has one pin limit read with a default of 5, and the hook fits`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val reads = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (PinChatLimitFingerprint.takes(method, classDef)) reads += method
                    }
                }
            }
            assertEquals("$version: pin limit reads ${reads.map { "${it.definingClass}->${it.name}" }}", 1, reads.size)
            val native = reads.single()
            assertTrue("$version: the read is static", AccessFlags.STATIC.isSet(native.accessFlags))
            checkHook(version, native)
        }
    }

    private fun checkHook(version: String, native: Method) {
        val before = native.implementation!!.instructions.toList()
        val answer = native.pinChatLimitAnswer()
        // The read's second argument, the default, and the last instruction that set it.
        val defaultRegister = when (val read = before[answer - 1]) {
            is FiveRegisterInstruction -> read.registerD
            is RegisterRangeInstruction -> read.startRegister + 1
            else -> error("$version: ${read.opcode} isn't an invoke")
        }
        val default = before.take(answer - 1).last { it is OneRegisterInstruction && it.registerA == defaultRegister }
        assertEquals("$version: TikTok's default cap", 5, (default as NarrowLiteralInstruction).narrowLiteral)

        val register = (before[answer] as OneRegisterInstruction).registerA
        val patched = MutableMethod(native)
        patched.liftPinChatLimit(PATCH)
        val after = patched.implementation!!.instructions.toList()

        assertEquals("$version: two instructions added", before.size + 2, after.size)
        assertEquals("$version: TikTok's code before the hook", before.take(answer + 1).map { it.opcode }, after.take(answer + 1).map { it.opcode })
        val call = after[answer + 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val target = call.getReference<MethodReference>()!!
        assertEquals(PIN_MORE_CHATS, target.definingClass)
        assertEquals("pinLimit", target.name)
        assertEquals(listOf("I"), target.parameterTypes.map { it.toString() })
        assertEquals("I", target.returnType)
        assertEquals("$version: the read's answer goes in", register, (call as RegisterRangeInstruction).startRegister)
        assertEquals(1, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT, after[answer + 2].opcode)
        assertEquals("$version: and the extension's answer replaces it", register, (after[answer + 2] as OneRegisterInstruction).registerA)
        assertEquals("$version: TikTok's code after the hook", before.drop(answer + 1).map { it.opcode }, after.drop(answer + 3).map { it.opcode })
        assertEquals("$version: the boxing takes the hooked answer", Opcode.INVOKE_STATIC, after[answer + 3].opcode)
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
    }

    @Test
    fun `only a static read of the key answered as an int is taken`() {
        assertEquals("the read's move-result", 4, read(SETTINGS_READ).pinChatLimitAnswer())
        assertEquals("no key loaded", -1, read(SETTINGS_READ, loadsKey = false).pinChatLimitAnswer())
        assertEquals("a read answered as a boolean", -1,
            read(ImmutableMethodReference(SETTINGS_MANAGER, "LJ", listOf("Ljava/lang/String;", "Z"), "Z")).pinChatLimitAnswer())
        assertEquals("a read answered as a long", -1,
            read(ImmutableMethodReference(SETTINGS_MANAGER, "LJ", listOf("Ljava/lang/String;", "J"), "J")).pinChatLimitAnswer())
        assertEquals("a virtual read", -1, read(SETTINGS_READ, opcode = Opcode.INVOKE_VIRTUAL).pinChatLimitAnswer())
        assertEquals("an answer nobody takes", -1, read(SETTINGS_READ, takesAnswer = false).pinChatLimitAnswer())
    }

    private val SETTINGS_MANAGER = "Lcom/bytedance/ies/abmock/SettingsManager;"
    private val SETTINGS_READ = ImmutableMethodReference(SETTINGS_MANAGER, "LJ", listOf("Ljava/lang/String;", "I"), "I")

    /**
     * The lambda cut down, as 47.7.3 has it: load the key into v1 and the default 5 into v0, read
     * into v0 (index 4), box and return it. Without the key, a class is loaded in its place.
     */
    private fun read(
        call: MethodReference,
        loadsKey: Boolean = true,
        opcode: Opcode = Opcode.INVOKE_STATIC,
        takesAnswer: Boolean = true,
    ): Method {
        val key = if (loadsKey) {
            ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(PIN_CHAT_LIMIT_KEY))
        } else {
            ImmutableInstruction21c(Opcode.CONST_CLASS, 1, ImmutableTypeReference("LX/0MTe;"))
        }
        val instructions = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                ImmutableMethodReference(SETTINGS_MANAGER, "LIZLLL", listOf(), SETTINGS_MANAGER)),
            key,
            ImmutableInstruction11n(Opcode.CONST_4, 0, 5),
            ImmutableInstruction35c(opcode, 2, 1, 0, 0, 0, 0, call),
            if (takesAnswer) ImmutableInstruction11x(Opcode.MOVE_RESULT, 0) else ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference("Ljava/lang/Integer;", "valueOf", listOf("I"), "Ljava/lang/Integer;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        return ImmutableMethod(
            "Lkotlin/jvm/internal/AFwS158S0000000_33_I1;", "invoke\$714", listOf(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, instructions, null, null),
        )
    }
}
