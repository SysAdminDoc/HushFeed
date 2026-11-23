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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Skip promo lines (#493) moves the player from TikTok's own progress callback. On every declared
 * build that callback is found once, an instance method taking the video's id, its position and
 * its length, and the extension gets all of it, the controller included, ahead of TikTok's code.
 */
class PromoSkipAnchorsTest {
    @Test
    fun `each build hands every progress tick to the promo skip first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (PromoProgressFingerprint.takes(method, classDef)) found += method
                    }
                }
            }
            assertEquals("$version: progress callbacks ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)

            val native = found.single()
            val before = native.implementation!!.instructions.toList()
            val patched = MutableMethod(native)
            patched.hookPromoSkip()
            val after = patched.implementation!!.instructions.toList()

            val call = after[0]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            val target = call.getReference<MethodReference>()!!
            assertEquals(PROMO_SKIP, target.definingClass)
            assertEquals("onProgress", target.name)
            assertEquals(listOf("Ljava/lang/Object;", "Ljava/lang/String;", "J", "J"), target.parameterTypes.map { it.toString() })
            val registers = native.implementation!!.registerCount
            assertEquals("$version: from p0, the controller", registers - 6, (call as RegisterRangeInstruction).startRegister)
            assertEquals("$version: through p5, the length's second half", 6, call.registerCount)
            assertEquals("$version: TikTok's callback after it is unchanged", before.map { it.opcode }, after.drop(1).map { it.opcode })
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
        }
    }
}
