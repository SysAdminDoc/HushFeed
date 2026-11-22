/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.navigation.branchTargets
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONTROLS = "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;"

/**
 * What Hide the pause icon hooks (#489), held to each declared build. A tap that pauses a video
 * makes the cell's play icon VISIBLE at alpha 0 and fades it in to an alpha from one of two play
 * button classes: the component reads its own `()F` getter, the stand-in fades in to 0.3. Both
 * fades and both getters go through FeedOverlayControls; nothing else in either class changes.
 */
class PauseIconAnchorsTest {
    @Test
    fun `each play button class has one fade-in and one alpha getter`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            for (type in PLAY_BUTTON_CLASSES) {
                val classDef = classOf(apk, type)
                assertNotNull("$version: $type is gone", classDef)
                val getters = classDef!!.methods.filter { pauseIconAlphaReturns(it) != null }
                assertEquals("$version: $type alpha getters ${getters.map { it.name }}", 1, getters.size)
                val fades = classDef.methods.filter { pauseIconFadeIn(it) != null }
                assertEquals("$version: $type fade-ins ${fades.map { it.name }}", 1, fades.size)
                val fade = fades.single()
                val code = fade.implementation!!.instructions.toList()
                val at = pauseIconFadeIn(fade)!!
                val alpha = (code[at] as FiveRegisterInstruction).registerD
                // The icon is animated from its view, so a zero fade keeps it shown, unseen.
                assertTrue("$version: $type ${fade.name} animates the icon view", code.take(at).any {
                    it.getReference<MethodReference>()?.let { call -> call.definingClass == "Landroid/view/View;" && call.name == "animate" } == true
                })
                if (type == PLAY_BUTTON_COMPONENT) {
                    // The component fades in to its own getter's answer.
                    val getter = getters.single()
                    val read = code[at - 2].getReference<MethodReference>()
                    assertEquals("$version: the fade reads the getter", getter.name, read?.name)
                    assertEquals(Opcode.MOVE_RESULT, code[at - 1].opcode)
                    assertEquals(alpha, (code[at - 1] as OneRegisterInstruction).registerA)
                } else {
                    // The stand-in fades in to TikTok's 0.3, a float constant.
                    val literal = code[at - 1] as? NarrowLiteralInstruction
                    assertNotNull("$version: the stand-in's alpha is no constant", literal)
                    assertEquals(0.3f, Float.fromBits(literal!!.narrowLiteral), 0f)
                    assertEquals(alpha, (literal as OneRegisterInstruction).registerA)
                }
            }
        }
    }

    @Test
    fun `the hooks pass each alpha through the switch and leave TikTok's code as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            for (type in PLAY_BUTTON_CLASSES) {
                val native = classOf(apk, type)!!
                val before = native.methods.associate { signature(it.name, it.parameterTypes) to it.implementation?.instructions?.toList() }
                val hooked = MutableClass(native)
                hooked.pauseIconHooks("Hide video overlays").hide()
                for (method in hooked.methods) {
                    val after = method.implementation?.instructions?.toList() ?: continue
                    val stock = before.getValue(signature(method.name, method.parameterTypes))!!
                    val hooks = after.indices.filter { isHook(after[it]) }
                    val targets = method.branchTargets()
                    // Every hook is the call and its move-result on one register, and nothing
                    // jumps past it onto the instruction it guards.
                    for (i in hooks) {
                        val register = (after[i] as RegisterRangeInstruction).startRegister
                        assertEquals(1, (after[i] as RegisterRangeInstruction).registerCount)
                        assertEquals("$version: $type ${method.name}", Opcode.MOVE_RESULT, after[i + 1].opcode)
                        assertEquals(register, (after[i + 1] as OneRegisterInstruction).registerA)
                        assertFalse("$version: $type ${method.name} jumps past the hook at $i", i + 2 in targets)
                        val next = after[i + 2]
                        if (next.opcode == Opcode.RETURN) {
                            assertEquals(register, (next as OneRegisterInstruction).registerA)
                        } else {
                            val call = next.getReference<MethodReference>()
                            assertEquals("$version: $type ${method.name} hook before ${call?.name}", "alpha", call?.name)
                            assertEquals(register, (next as FiveRegisterInstruction).registerD)
                        }
                    }
                    val kept = after.filterIndexed { i, _ -> hooks.none { it == i || it + 1 == i } }
                    assertEquals("$version: $type ${method.name} changed", codeOf(stock), codeOf(kept))
                    val expected = when {
                        pauseIconAlphaReturns(method) != null -> stock.count { it.opcode == Opcode.RETURN }
                        pauseIconFadeIn(method) != null -> 1
                        else -> 0
                    }
                    assertEquals("$version: $type ${method.name} hooks", expected, hooks.size)
                    if (hooks.isNotEmpty()) {
                        assertEquals("$version: $type ${method.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
                    }
                }
            }
        }
    }

    @Test
    fun `a branch onto a return lands on the hook in front of it`() {
        val getter = method("F", 2, """
            invoke-static {}, LX/Gate;->on()Z
            move-result v0
            const/high16 v1, 0x3f800000
            if-eqz v0, :done
            const v1, 0x3e99999a
            :done
            return v1
        """)
        hidePauseIconAlpha(getter)
        val code = getter.implementation!!.instructions.toList()
        val hook = code.indexOfFirst(::isHook)
        assertEquals(Opcode.RETURN, code[hook + 2].opcode)
        assertTrue("the if-eqz skips the hook", hook in getter.branchTargets())
        assertFalse(hook + 2 in getter.branchTargets())
    }

    @Test
    fun `a fade-in something jumps onto is left alone`() {
        val straight = fadeIn(jumpsOntoAlpha = false)
        assertEquals("the same fade with no jump is found", 5, pauseIconFadeIn(straight))
        assertNull(pauseIconFadeIn(fadeIn(jumpsOntoAlpha = true)))
    }

    /** TikTok's fade-in, `animate().alpha(x).scaleX(1f)`, with or without a branch onto alpha. */
    private fun fadeIn(jumpsOntoAlpha: Boolean) = method("V", 3, """
        invoke-virtual {p0}, Landroid/view/View;->animate()Landroid/view/ViewPropertyAnimator;
        move-result-object v0
        const/4 v1, 0x0
        ${if (jumpsOntoAlpha) "if-eqz v1, :fade" else "nop"}
        const v1, 0x3e99999a
        :fade
        invoke-virtual {v0, v1}, Landroid/view/ViewPropertyAnimator;->alpha(F)Landroid/view/ViewPropertyAnimator;
        move-result-object v0
        const/high16 v1, 0x3f800000
        invoke-virtual {v0, v1}, Landroid/view/ViewPropertyAnimator;->scaleX(F)Landroid/view/ViewPropertyAnimator;
        return-void
    """)

    private fun method(returnType: String, registers: Int, smali: String) = MutableMethod(
        ImmutableMethod(
            PLAY_BUTTON_COMPONENT, "example", emptyList(), returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers + 1, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun signature(name: String, parameters: List<CharSequence>) = name + parameters.joinToString("") { it.toString() }

    private fun isHook(instruction: Instruction) = instruction.opcode == Opcode.INVOKE_STATIC_RANGE &&
        instruction.getReference<MethodReference>()?.let {
            it.definingClass == CONTROLS && it.name == "pauseIconAlpha" && it.returnType == "F"
        } == true

    /** Opcodes with payload alignment nops left out and a widened goto read as a goto. */
    private fun codeOf(instructions: List<Instruction>): List<Opcode> = instructions
        .filter { it.opcode != Opcode.NOP }
        .map {
            when (it.opcode) {
                Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
                else -> it.opcode
            }
        }

    private fun classOf(apk: File, type: String): ClassDef? {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }
}
