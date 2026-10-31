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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONTROLS = "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;"

/**
 * What Hide surveys hooks for the Not interested and Interested buttons (#293), held to each
 * declared build: the Anole component holder's constructor, the one that logs each component's
 * type and compares the container type with the button group's name. The hook rewrites that
 * comparison's answer in front of TikTok's own branch, with no branch of its own (#264).
 */
class InterestButtonsAnchorsTest {
    @Test
    fun `each declared build has one component factory and the fingerprint takes exactly it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byShape = methodsOf(apk) { _, method -> isAnoleComponentFactory(method) && loads(method, ANOLE_INIT_LOG) }
            val byFingerprint = methodsOf(apk) { classDef, method -> InterestButtonGroupFingerprint.takes(method, classDef) }
            assertEquals("$version: factories ${byShape.map { it.first.type }}", 1, byShape.size)
            assertEquals(
                "$version: the fingerprint took ${byFingerprint.map { it.first.type + it.second.name }}",
                byShape.map { it.first.type to it.second.name },
                byFingerprint.map { it.first.type to it.second.name },
            )
            val instructions = byShape.single().second.implementation!!.instructions.toList()
            assertTrue("$version: the button group's check", interestGroupBranch(instructions) > 0)
        }
    }

    @Test
    fun `the hook rewrites the group's match in front of TikTok's branch and leaves the rest as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val native = methodsOf(apk) { _, method -> isAnoleComponentFactory(method) && loads(method, INTEREST_BUTTON_GROUP) }.single().second
            val before = native.implementation!!.instructions.toList()
            val branch = interestGroupBranch(before)
            val matched = (before[branch] as OneRegisterInstruction).registerA

            val hooked = MutableMethod(native)
            hooked.dropInterestButtons("Hide video overlays")
            val after = hooked.implementation!!.instructions.toList()

            val call = after[branch]
            assertEquals("$version: the hook", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ),
                after.subList(branch, branch + 3).map { it.opcode })
            val target = call.getReference<MethodReference>()!!
            assertEquals(CONTROLS, target.definingClass)
            assertEquals("interestButtonsShown", target.name)
            assertEquals(listOf("I"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", target.returnType)
            assertEquals(1, (call as RegisterRangeInstruction).registerCount)
            assertEquals(matched, call.startRegister)
            assertEquals(matched, (after[branch + 1] as OneRegisterInstruction).registerA)
            assertEquals(matched, (after[branch + 2] as OneRegisterInstruction).registerA)
            // TikTok's own code around it, in its own order. A payload's alignment nop may move.
            val kept = after.filterIndexed { index, _ -> index != branch && index != branch + 1 }
            assertEquals(before.map { it.opcode }.filter { it != Opcode.NOP }, kept.map { it.opcode }.filter { it != Opcode.NOP })
        }
    }

    @Test
    fun `a factory whose check moved stops the patch untouched`() {
        val moved = MutableMethod(
            ImmutableMethod(
                "LX/1QgF;", "<init>",
                listOf(ImmutableMethodParameter(ANOLE_MODEL, null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(
                    3,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(INTEREST_BUTTON_GROUP)),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            ),
        )
        val before = moved.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { moved.dropInterestButtons("Hide video overlays") }
        assertEquals(before, moved.implementation!!.instructions.toList())
    }

    private fun loads(method: Method, text: String) = method.implementation?.instructions?.any {
        it.getReference<StringReference>()?.string == text
    } == true

    /** Every method of one build the filter takes, walked once and never held (the dex has millions). */
    private fun methodsOf(apk: File, accept: (ClassDef, Method) -> Boolean): List<Pair<ClassDef, Method>> {
        val found = mutableListOf<Pair<ClassDef, Method>>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) {
                    if (accept(classDef, method)) found += classDef to method
                }
            }
        }
        return found
    }
}
