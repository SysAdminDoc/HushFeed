/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.captions

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PATCH = "Subtitle tools"
private const val GUARD_SIZE = 6

/**
 * What Keep captions available (#217) hooks, held to each declared build: the one lambda that reads
 * where the Captions row goes, with the extension taking its answer right after the read, and the
 * one class that stores the caption controls group, whose one question gets the guard at entry.
 */
class CaptionsAvailableAnchorsTest {
    @Test
    fun `each build has one row read and one controls question, and both hooks fit`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val rows = mutableListOf<Method>()
            val initializers = mutableListOf<Pair<Method, ClassDef>>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (CaptionsRowFingerprint.takes(method, classDef)) rows += method
                        if (CaptionControlsGroupFingerprint.takes(method, classDef)) initializers += method to classDef
                    }
                }
            }
            assertEquals("$version: row reads ${rows.map { "${it.definingClass}->${it.name}" }}", 1, rows.size)
            assertEquals("$version: controls groups ${initializers.map { it.first.definingClass }}", 1, initializers.size)

            checkRowHook(version, rows.single())

            val (initializer, owner) = initializers.single()
            val group = initializer.experimentGroupField()
            assertNotNull("$version: the initializer stores the group", group)
            val question = captionControlsQuestion(owner.methods.toList(), group!!)
            assertEquals("$version: the question is static", true, AccessFlags.STATIC.isSet(question.accessFlags))
            checkControlsGuard(version, question)
        }
    }

    private fun checkRowHook(version: String, native: Method) {
        val before = native.implementation!!.instructions.toList()
        val answer = native.experimentReadAnswer()
        val register = (before[answer] as OneRegisterInstruction).registerA
        val patched = MutableMethod(native)
        patched.placeCaptionsRow()
        val after = patched.implementation!!.instructions.toList()

        assertEquals("$version: two instructions added", before.size + 2, after.size)
        assertEquals("$version: TikTok's code before the hook", before.take(answer + 1).map { it.opcode }, after.take(answer + 1).map { it.opcode })
        val call = after[answer + 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val target = call.getReference<MethodReference>()!!
        assertEquals(CAPTIONS_AVAILABLE, target.definingClass)
        assertEquals("captionsRow", target.name)
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

    private fun checkControlsGuard(version: String, native: Method) {
        val registers = native.implementation!!.registerCount
        assertTrue("$version: $registers registers leave no local", registers >= 1)
        val before = native.implementation!!.instructions.toList()
        val patched = MutableMethod(native)
        patched.keepCaptionControls(PATCH)
        val after = patched.implementation!!.instructions.toList()
        assertEquals(
            "$version: the guard",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN, Opcode.NOP),
            after.take(GUARD_SIZE).map { it.opcode },
        )
        val call = after[0].getReference<MethodReference>()!!
        assertEquals(CAPTIONS_AVAILABLE, call.definingClass)
        assertEquals("captionControls", call.name)
        assertTrue(call.parameterTypes.isEmpty())
        assertEquals("Z", call.returnType)
        assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
        assertEquals("$version: the branch tests the answer", 0, (after[2] as OneRegisterInstruction).registerA)
        assertEquals("$version: a yes answers true", 1, (after[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$version: and it is the one returned", 0, (after[4] as OneRegisterInstruction).registerA)
        assertEquals("$version: a no skips to TikTok's own code", GUARD_SIZE - 1, (after[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals(before.map { it.opcode }, after.drop(GUARD_SIZE).map { it.opcode })
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
    }

    @Test
    fun `only a read of an experiment answered as an int is taken`() {
        assertEquals("the read's move-result", 4, read(EXPERIMENT_READ).experimentReadAnswer())
        assertEquals("no key loaded", -1, read(EXPERIMENT_READ, loadsKey = false).experimentReadAnswer())
        assertEquals("a read answered as a boolean", -1,
            read(ImmutableMethodReference("LX/03Nn;", "LJIIJJI", listOf("I", "I", "Ljava/lang/String;", "Z"), "Z")).experimentReadAnswer())
        assertEquals("a read with other parameters", -1,
            read(ImmutableMethodReference("LX/03Nn;", "LJIIJJI", listOf("I", "Ljava/lang/String;"), "I")).experimentReadAnswer())
    }

    @Test
    fun `the controls question reads the stored group and nothing else is taken`() {
        val group = ImmutableFieldReference("LX/0BuN;", "LIZIZ", "I")
        assertTrue(question(group).asksCaptionControlsGroup(group))
        assertFalse("another field", question(ImmutableFieldReference("LX/0BuN;", "LJ", "I")).asksCaptionControlsGroup(group))
        assertFalse("takes a parameter", question(group, parameters = listOf("I")).asksCaptionControlsGroup(group))
        assertFalse("not static", question(group, static = false).asksCaptionControlsGroup(group))
    }

    private val EXPERIMENT_READ =
        ImmutableMethodReference("LX/03Nn;", "LJIIJJI", listOf("I", "I", "Ljava/lang/String;", "Z"), "I")

    /**
     * The row lambda cut down, as 47.4.3 has it: get the experiments, load the key into v3, read
     * into v0 (index 4), box and return it. Without the key, a class is loaded in its place.
     */
    private fun read(call: MethodReference, loadsKey: Boolean = true): Method {
        val key = if (loadsKey) {
            ImmutableInstruction21c(Opcode.CONST_STRING, 3, ImmutableStringReference(CAPTIONS_ROW_KEY))
        } else {
            ImmutableInstruction21c(Opcode.CONST_CLASS, 3, ImmutableTypeReference("LX/0MTe;"))
        }
        val instructions = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                ImmutableMethodReference("LX/03Nn;", "LJIIIZ", listOf(), "LX/03Nn;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
            key,
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 5, 4, 1, 0, 3, 2, call),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference("Ljava/lang/Integer;", "valueOf", listOf("I"), "Ljava/lang/Integer;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        return ImmutableMethod(
            "Lkotlin/jvm/internal/AFwS0;", "invoke\$17", listOf(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(5, instructions, null, null),
        )
    }

    /** The controls question cut down: read the group, answer whether it's set. */
    private fun question(
        field: ImmutableFieldReference,
        parameters: List<String> = listOf(),
        static: Boolean = true,
    ): Method = ImmutableMethod(
        "LX/0BuN;", "LIZ", parameters.map { ImmutableMethodParameter(it, null, null) }, "Z",
        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
        ImmutableMethodImplementation(
            1 + parameters.size + (if (static) 0 else 1),
            listOf(
                ImmutableInstruction21c(Opcode.SGET, 0, field),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
            null,
            null,
        ),
    )
}
