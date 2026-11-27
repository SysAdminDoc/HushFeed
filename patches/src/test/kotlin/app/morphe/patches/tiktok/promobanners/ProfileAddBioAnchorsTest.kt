/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PATCH = "Hide profile shortcuts"

/** TikTok's popup tip window, whose real name is kept. */
private const val TUX_TOOLTIP_WINDOW = "Lcom/bytedance/tux/tooltip/popup/TuxTooltipPopupWindow;"

/** The message tip hook's length: its two checks and the return. */
private const val HOOK_SIZE = 10

/** The style entries the popover and action menu builders read in place of the message color. */
private val OTHER_TIP_COLORS = setOf("TuxTooltip__tux_tooltipPopoverBgColor", "TuxTooltip__tux_tooltipActionBgColor")

/**
 * What Hide tips on your profile hooks, held to each declared build: the Add bio component's one
 * bind, its one call that shows the prompt, and the hook right after it (#466), and the message
 * tip's builder, its view and both tip windows' show() with the hook at their top (#546).
 */
class ProfileAddBioAnchorsTest {
    @Test
    fun `the bind shows the prompt once and the hook follows that call`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = methodsOf(apk) { classDef, method -> ProfileAddBioBindFingerprint.takes(method, classDef) }
            assertEquals("$version: binds ${taken.map { it.second.name }}", 1, taken.size)
            val native = taken.single().second
            val before = native.implementation!!.instructions.toList()
            val show = native.addBioShow(PATCH)

            // The other visibility call hides the view for a profile with a bio, with View.GONE.
            val calls = before.withIndex().filter { (_, instruction) ->
                instruction.getReference<MethodReference>()?.parameterTypes?.map(CharSequence::toString) ==
                    listOf("Lcom/bytedance/tux/input/TuxTextView;", "I") && instruction is FiveRegisterInstruction
            }
            assertEquals("$version: visibility calls", 2, calls.size)
            val hide = calls.single { it.index != show.index }
            val gone = before[hide.index - 1] as? NarrowLiteralInstruction
            assertEquals("$version: the other call hides", 8, gone?.narrowLiteral)
            assertEquals(show.view, (before[show.index] as FiveRegisterInstruction).registerC)

            // Nothing jumps onto the instruction after the show, so the hook runs every time it does.
            assertFalse("$version: a branch lands right after the show", branchTargets(native).contains(show.index + 1))

            val patched = MutableMethod(native)
            patched.hideAddBio(show)
            val after = patched.implementation!!.instructions.toList()
            val hook = after[show.index + 1]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            val call = hook.getReference<MethodReference>()!!
            assertEquals("Lapp/morphe/extension/tiktok/profile/ProfileAddBio;", call.definingClass)
            assertEquals("shown", call.name)
            assertEquals(listOf("Landroid/view/View;"), call.parameterTypes.map(CharSequence::toString))
            assertEquals(show.view, (hook as RegisterRangeInstruction).startRegister)
            assertEquals(1, hook.registerCount)
            // TikTok's own instructions are all still there, in their own order.
            assertEquals(
                before.filter { it.opcode != Opcode.NOP }.map { it.opcode },
                (after.take(show.index + 1) + after.drop(show.index + 2)).filter { it.opcode != Opcode.NOP }.map { it.opcode },
            )
        }
    }

    @Test
    fun `the base signature bind of the same shape is not taken`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val sameShape = methodsOf(apk) { classDef, method ->
                classDef.superclass != null && method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/util/List;") &&
                    classDef.type.contains("/bio/business/signature/base/")
            }
            assertTrue("$version: no base bind to compare with", sameShape.isNotEmpty())
            for ((classDef, method) in sameShape) {
                assertFalse("$version: ${classDef.type}", ProfileAddBioBindFingerprint.takes(method, classDef))
            }
        }
    }

    @Test
    fun `message tips open in both tip windows and each window checks the message view first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            val builders = classes.values.filter { cls -> cls.methods.any { TooltipMessageBuilderFingerprint.takes(it, cls) } }
            assertEquals("$version: message builders ${builders.map { it.type }}", 1, builders.size)
            val tips = builders.single().messageTips(PATCH) { classes[it] }

            // The message view is the bubble with its optional picture, and the content field is
            // on the config both windows read.
            assertTrue(
                "$version: ${tips.view} is the message view",
                classes.getValue(tips.view).methods.any { it.name == "setCustomImage" },
            )
            val windows = tips.windows.map { it.type }
            assertEquals("$version: tip windows $windows", 2, windows.size)
            assertTrue("$version: $windows", TUX_TOOLTIP_WINDOW in windows)
            val shared = classes.getValue(TUX_TOOLTIP_WINDOW).interfaces.toSet()
            for (window in tips.windows) {
                assertTrue("$version: ${window.type} shares the popup's tip interface", classes.getValue(window.type).interfaces.any { it in shared })
                assertEquals(tips.content.definingClass, window.anchor.config.type)

                val before = window.show.implementation!!.instructions.toList()
                val patched = MutableMethod(window.show)
                patched.keepProfileTipsClosed(tips, window.anchor)
                val after = patched.implementation!!.instructions.toList()
                assertEquals(
                    "$version: ${window.type} hook",
                    listOf(
                        Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INSTANCE_OF, Opcode.IF_EQZ,
                        Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                        Opcode.IF_EQZ, Opcode.RETURN_VOID,
                    ),
                    after.take(HOOK_SIZE).map { it.opcode },
                )
                assertEquals(tips.content.name, after[1].getReference<FieldReference>()!!.name)
                assertEquals(tips.view, after[2].getReference<TypeReference>()!!.type)
                assertEquals(window.anchor.anchor.name, after[5].getReference<FieldReference>()!!.name)
                val call = after[6].getReference<MethodReference>()!!
                assertEquals("Lapp/morphe/extension/tiktok/profile/ProfileAddBio;->skipTip", "${call.definingClass}->${call.name}")
                assertEquals(listOf("Landroid/view/View;"), call.parameterTypes.map(CharSequence::toString))
                assertEquals("Z", call.returnType)
                // Both checks fall through to TikTok's own first instruction when the tip may show.
                for (branch in listOf(3, 8)) {
                    assertEquals("$version: branch $branch", HOOK_SIZE, (after[branch] as BuilderOffsetInstruction).target.location.index)
                }
                // The hook's register is written by show() itself before it is read, and never `this`.
                val register = window.anchor.register
                assertEquals(register, (before[1] as TwoRegisterInstruction).registerA)
                assertFalse(register == (before[0] as TwoRegisterInstruction).registerB)
                for (index in 0 until HOOK_SIZE - 1) {
                    val used = when (val line = after[index]) {
                        is FiveRegisterInstruction -> line.registerC.also { assertEquals(1, line.registerCount) }
                        else -> (line as OneRegisterInstruction).registerA
                    }
                    assertEquals("$version: hook line $index", register, used)
                }
                assertEquals(
                    before.filter { it.opcode != Opcode.NOP }.map { it.opcode },
                    after.drop(HOOK_SIZE).filter { it.opcode != Opcode.NOP }.map { it.opcode },
                )
            }
        }
    }

    @Test
    fun `the popover and action menu builders are not the message builder`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            val others = classes.values.filter { cls ->
                cls.methods.any { method ->
                    method.name == "<init>" && method.implementation?.instructions?.any { instruction ->
                        instruction.getReference<FieldReference>()?.name in OTHER_TIP_COLORS
                    } == true
                }
            }
            assertTrue("$version: no popover or action builders", others.size >= 2)
            for (cls in others) {
                assertFalse("$version: ${cls.type}", cls.methods.any { TooltipMessageBuilderFingerprint.takes(it, cls) })
            }
        }
    }

    private fun classesOf(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    /** The instruction indexes any branch or switch of the method jumps to. */
    private fun branchTargets(method: Method): Set<Int> {
        val instructions = method.implementation!!.instructions.toList()
        val addresses = mutableListOf<Int>()
        var address = 0
        for (instruction in instructions) {
            addresses += address
            address += instruction.codeUnits
        }
        val indexAt = addresses.withIndex().associate { (index, at) -> at to index }
        val targets = mutableSetOf<Int>()
        instructions.forEachIndexed { index, instruction ->
            if (instruction is OffsetInstruction) {
                val target = addresses[index] + instruction.codeOffset
                indexAt[target]?.let { targets += it }
                // A switch's offset names its payload, whose own offsets count from the switch.
                (instructions.getOrNull(indexAt[target] ?: -1) as? SwitchPayload)?.switchElements?.forEach {
                    indexAt[addresses[index] + it.offset]?.let { case -> targets += case }
                }
            }
        }
        return targets
    }

    /** Every method of one build the filter takes, walked once and never held. */
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
