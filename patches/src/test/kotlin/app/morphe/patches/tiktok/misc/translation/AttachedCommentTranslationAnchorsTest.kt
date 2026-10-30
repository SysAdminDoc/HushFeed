/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where auto translate meets TikTok's newer comment path (#282), held to each declared build: the
 * cell's onViewAttachedToWindow switches exactly one translation controller on, the hook lands
 * right after that call with the controller's register, and the cell's "no controller" branches
 * still land on TikTok's next instruction rather than on the hook.
 */
class AttachedCommentTranslationAnchorsTest {
    @Test
    fun `each declared build switches one translation controller on as a comment cell attaches and the hook follows it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk)
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            val cell = classes.firstOrNull { it.type == BASE_COMMENT_CELL }
            assertNotNull("$version: $BASE_COMMENT_CELL is missing", cell)
            val attach = cell!!.methods.single { it.name == "onViewAttachedToWindow" && it.parameterTypes.isEmpty() }
            val called = attach.implementation!!.instructions.mapNotNull {
                if (it.opcode == Opcode.INVOKE_INTERFACE) it.getReference<MethodReference>()?.definingClass else null
            }.toSet()
            val owners = classes.filter { it.type in called }.associateBy { it.type }

            val enable = attach.translationControllerEnable { owners[it] }
            val before = attach.implementation!!.instructions.toList()
            val call = before[enable].getReference<MethodReference>()!!
            assertTrue(
                "$version: ${call.definingClass} has no translate()",
                owners.getValue(call.definingClass).methods.any { it.name == "translate" && it.parameterTypes.isEmpty() },
            )
            val register = (before[enable] as FiveRegisterInstruction).registerC
            assertTrue("$version: the controller sits in v$register", register <= 15)

            val hooked = MutableMethod(attach)
            val targetsBefore = hooked.implementation!!.instructions
                .filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }
            val skips = targetsBefore.count { it == enable + 1 }
            assertTrue("$version: nothing skips the switch to the instruction after it", skips > 0)

            hooked.translateOnAttach(enable)
            val after = hooked.implementation!!.instructions.toList()
            val hook = after[enable + 1]
            assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
            val hookCall = hook.getReference<MethodReference>()!!
            assertEquals("Lapp/morphe/extension/tiktok/translation/CommentBatchTranslator;", hookCall.definingClass)
            assertEquals("onTranslationControllerAttached", hookCall.name)
            assertEquals(register, (hook as FiveRegisterInstruction).registerC)

            val targetsAfter = after.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }
            assertFalse("$version: a branch lands on the hook", enable + 1 in targetsAfter)
            assertEquals("$version: the no controller branches moved", skips, targetsAfter.count { it == enable + 2 })
            // TikTok's own instructions keep their order on both sides.
            assertEquals(
                before.map { it.opcode }.filter { it != Opcode.NOP },
                after.filterIndexed { i, _ -> i != enable + 1 }.map { it.opcode }.filter { it != Opcode.NOP },
            )
        }
    }

    @Test
    fun `only a call on an interface that can translate counts, and one is required`() {
        val controller = ImmutableClassDef(
            "Lfixture/Controller;", AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
            "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(),
            listOf(abstract("Lfixture/Controller;", "enable"), abstract("Lfixture/Controller;", "translate")),
        )
        val other = ImmutableClassDef(
            "Lfixture/Other;", AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
            "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(),
            listOf(abstract("Lfixture/Other;", "enable")),
        )
        val owners = mapOf(controller.type to controller, other.type to other)
        val classOf: (String) -> ClassDef? = { owners[it] }

        val enable = interfaceCall("Lfixture/Controller;", "enable")
        val translate = interfaceCall("Lfixture/Controller;", "translate")
        val unrelated = interfaceCall("Lfixture/Other;", "enable")

        assertEquals(2, attach(unrelated, translate, enable).translationControllerEnable(classOf))
        assertThrows(PatchException::class.java) { attach(unrelated, translate).translationControllerEnable(classOf) }
        assertThrows(PatchException::class.java) { attach(enable, enable).translationControllerEnable(classOf) }
        // An interface the patcher can't see is never taken for the controller.
        assertThrows(PatchException::class.java) { attach(enable).translationControllerEnable { null } }
    }

    private fun abstract(owner: String, name: String): Method = ImmutableMethod(
        owner, name, emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
    )

    private fun interfaceCall(owner: String, name: String) =
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(owner, name, emptyList(), "V"))

    private fun attach(vararg calls: ImmutableInstruction35c): Method = ImmutableMethod(
        BASE_COMMENT_CELL, "onViewAttachedToWindow", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(1, calls.toList() + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null),
    )
}
