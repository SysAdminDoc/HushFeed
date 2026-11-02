/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONTROLS = "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;"

/**
 * What Hide the repost chip hooks (#319), held to each declared build: the one method of TikTok's
 * repost trigger that takes a video and asks whether it gets the chip. The hook rewrites that answer
 * with no branch of its own (#264).
 */
class RepostChipAnchorsTest {
    @Test
    fun `each declared build has one trigger and the fingerprint takes exactly it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byFingerprint = methodsOf(apk) { classDef, method -> RepostChipTriggerFingerprint.takes(method, classDef) }
            assertEquals("$version: the fingerprint took ${byFingerprint.map { it.second.name }}", 1, byFingerprint.size)
            val (classDef, method) = byFingerprint.single()
            assertEquals(REPOST_CHIP_TRIGGER, classDef.type)
            assertTrue("$version: the trigger's answer", repostChipAnswer(method.implementation!!.instructions.toList()) > 0)
        }
    }

    @Test
    fun `the hook rewrites TikTok's answer and leaves the rest as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val native = methodsOf(apk) { classDef, method -> RepostChipTriggerFingerprint.takes(method, classDef) }.single().second
            val before = native.implementation!!.instructions.toList()
            val answer = repostChipAnswer(before)
            val register = (before[answer] as OneRegisterInstruction).registerA

            val hooked = MutableMethod(native)
            hooked.dropRepostChip("Hide video overlays")
            val after = hooked.implementation!!.instructions.toList()

            val call = after[answer + 1]
            assertEquals("$version: the hook", listOf(Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT),
                after.subList(answer, answer + 3).map { it.opcode })
            val target = call.getReference<MethodReference>()!!
            assertEquals(CONTROLS, target.definingClass)
            assertEquals("repostChipShown", target.name)
            assertEquals(listOf("I"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", target.returnType)
            assertEquals(1, (call as RegisterRangeInstruction).registerCount)
            assertEquals(register, call.startRegister)
            assertEquals(register, (after[answer + 2] as OneRegisterInstruction).registerA)
            val kept = after.filterIndexed { index, _ -> index != answer + 1 && index != answer + 2 }
            assertEquals(before.map { it.opcode }.filter { it != Opcode.NOP }, kept.map { it.opcode }.filter { it != Opcode.NOP })
        }
    }

    @Test
    fun `the bar's one visibility point gets the rewrite first, and that value reaches the view`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = methodsOf(apk, REPOST_BAR_ASSEM) { classDef, method -> RepostBarVisibilityFingerprint.takes(method, classDef) }
            assertEquals("$version: the fingerprint took ${found.map { it.second.name }}", 1, found.size)
            val native = found.single().second
            assertEquals("I", native.parameterTypes.first().toString())

            val before = native.implementation!!.instructions.toList()
            // p1 in a method whose parameters are one word each.
            val visibility = native.implementation!!.registerCount - native.parameterTypes.size
            val toView = before.filter { instruction ->
                instruction.getReference<MethodReference>()?.parameterTypes?.map(CharSequence::toString) == listOf("I", "Landroid/view/View;")
            }
            assertEquals("$version: one call sets the bar's visibility", 1, toView.size)
            assertEquals("$version: and it's handed the parameter", visibility, (toView.single() as FiveRegisterInstruction).registerC)

            val hooked = MutableMethod(native)
            hooked.dropRepostBar()
            val after = hooked.implementation!!.instructions.toList()
            val target = after[0].getReference<MethodReference>()!!
            assertEquals(CONTROLS, target.definingClass)
            assertEquals("repostBarVisibility", target.name)
            assertEquals(listOf("I"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("I", target.returnType)
            assertEquals(visibility, (after[0] as FiveRegisterInstruction).registerC)
            assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
            assertEquals(visibility, (after[1] as OneRegisterInstruction).registerA)
            assertEquals(before.map { it.opcode }, after.drop(2).map { it.opcode })
        }
    }

    @Test
    fun `a trigger with no such call stops the patch untouched`() {
        val moved = MutableMethod(
            ImmutableMethod(
                REPOST_CHIP_TRIGGER, "Np",
                listOf(ImmutableMethodParameter("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;", null, null)), "Z",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(
                    3,
                    listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                        ImmutableInstruction11x(Opcode.RETURN, 0),
                    ),
                    null, null,
                ),
            ),
        )
        val before = moved.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { moved.dropRepostChip("Hide video overlays") }
        assertEquals(before, moved.implementation!!.instructions.toList())
    }

    /** Every method of one build the filter takes, walked once and never held (the dex has millions). */
    private fun methodsOf(apk: File, type: String = REPOST_CHIP_TRIGGER, accept: (ClassDef, Method) -> Boolean): List<Pair<ClassDef, Method>> {
        val found = mutableListOf<Pair<ClassDef, Method>>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type != type) continue
                for (method in classDef.methods) {
                    if (accept(classDef, method)) found += classDef to method
                }
            }
        }
        return found
    }
}
