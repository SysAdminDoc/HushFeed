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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PATCH = "Hide profile shortcuts"

/**
 * What Hide Add bio (#466) hooks, held to each declared build: the Add bio component's one bind,
 * its one call that shows the prompt, and the hook right after it.
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
