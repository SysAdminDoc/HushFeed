/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.absettings.APP_AB_SHAPES
import app.morphe.patches.tiktok.misc.absettings.APP_AB_TYPED_OBJECT
import app.morphe.patches.tiktok.misc.absettings.shape
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Discover tab (#98) rides on the App AB class's typed object getter, which the bottom bar's
 * layout gate comes back through. Held to every declared build: the class the patch resolves by
 * its getters carries that getter, and the hook lands on every return of the real method.
 */
class DiscoverTabAnchorTest {
    @Test
    fun `each declared build hands every typed object getter return to the Discover tab`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val appAb = load(apk).values.single { classDef ->
                val shapes = classDef.methods.map { it.shape() }.toSet()
                APP_AB_SHAPES.all { it in shapes }
            }
            val getters = appAb.methods.filter { it.shape() == APP_AB_TYPED_OBJECT }
            assertTrue("$version: no typed object getter on ${appAb.type}", getters.isNotEmpty())

            getters.forEach { getter ->
                // The hook reads the key from p1, which counts from `this`.
                assertFalse("$version: ${getter.name} is static", AccessFlags.STATIC.isSet(getter.accessFlags))
                val returns = getter.implementation!!.instructions.count { it.opcode == Opcode.RETURN_OBJECT }
                assertTrue("$version: ${getter.name} returns nothing", returns > 0)

                val patched = MutableMethod(getter).apply { offerDiscoverTab() }
                val code = patched.implementation!!.instructions.toList()
                val calls = code.withIndex().filter { (_, instruction) ->
                    instruction.getReference<MethodReference>()?.let {
                        it.definingClass == "Lapp/morphe/extension/tiktok/navigation/DiscoverTab;" && it.name == "apply"
                    } == true
                }
                assertEquals("$version: ${getter.name} hooks", returns, calls.size)
                calls.forEach { (index, _) ->
                    val result = code[index + 1]
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, result.opcode)
                    val back = code[index + 2]
                    assertEquals("$version: ${getter.name} after the hook", Opcode.RETURN_OBJECT, back.opcode)
                    assertEquals(
                        (result as OneRegisterInstruction).registerA,
                        (back as OneRegisterInstruction).registerA,
                    )
                }
            }
        }
    }

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
