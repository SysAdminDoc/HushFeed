/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The LIVE room's comment box on AMOLED dark theme (#267), held to each declared build: the
 * panel's one view setup is found, and after the hook its first instruction hands the root view
 * to the extension while TikTok's own instructions keep their order.
 */
class AmoledLiveInputTest {
    @Test
    fun `each declared build's LIVE comment panel setup is found once and hands over its root view first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val hits = ArrayList<Pair<String, app.morphe.patcher.util.proxy.mutableTypes.MutableMethod>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (!LiveCommentInputCreatedFingerprint.takes(method, classDef)) continue
                        hits.add(classDef.type to MutableMethod(method))
                    }
                }
            }
            assertEquals("$version: LIVE comment panel setups: ${hits.map { it.first }}", 1, hits.size)
            val setup = hits.single().second
            val before = setup.implementation!!.instructions.toList()
            setup.addInstruction(
                0,
                "invoke-static/range { p1 .. p1 }, $AMOLED_LIVE_INPUT_EXTENSION->onPanel(Landroid/view/View;)V",
            )
            val after = setup.implementation!!.instructions.toList()
            assertEquals(Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
            val call = after[0].getReference<MethodReference>()!!
            assertEquals(AMOLED_LIVE_INPUT_EXTENSION, call.definingClass)
            assertEquals("onPanel", call.name)
            assertEquals(before.map { it.opcode }.filter { it != Opcode.NOP }, after.drop(1).map { it.opcode }.filter { it != Opcode.NOP })
        }
    }
}
