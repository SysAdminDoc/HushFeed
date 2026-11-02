/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LIVE text panels under Dark LIVE panels (#267, #323), held to each declared build: each
 * panel's one view setup is found, and after the hook its first instruction hands the root view to
 * the extension while TikTok's own instructions keep their order. The extension finds the box by
 * looking for an EditText, so the box's class has to stay one.
 */
class LiveCommentBoxTest {
    private val setups: List<Fingerprint> = listOf(LiveCommentInputCreatedFingerprint, SubSpaceInputCreatedFingerprint)

    @Test
    fun `each declared build's LIVE text panel setups are found once and hand over their root view first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (fingerprint in setups) {
                val hits = ArrayList<Pair<String, MutableMethod>>()
                for (entry in container.dexEntryNames) {
                    for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                        for (method in classDef.methods) {
                            if (!fingerprint.takes(method, classDef)) continue
                            hits.add(classDef.type to MutableMethod(method))
                        }
                    }
                }
                assertEquals("$version: setups: ${hits.map { it.first }}", 1, hits.size)
                val setup = hits.single().second
                val before = setup.implementation!!.instructions.toList()
                hookLiveCommentBox(setup)
                val after = setup.implementation!!.instructions.toList()
                assertEquals(Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
                val call = after[0].getReference<MethodReference>()!!
                assertEquals(LIVE_COMMENT_BOX_EXTENSION, call.definingClass)
                assertEquals("onPanel", call.name)
                assertEquals(
                    before.map { it.opcode }.filter { it != Opcode.NOP },
                    after.drop(1).map { it.opcode }.filter { it != Opcode.NOP },
                )
            }
        }
    }

    @Test
    fun `each declared build's LIVE text box is an EditText`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = HashMap<String, ClassDef>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes[classDef.type] = classDef
            }
            val chain = generateSequence("Lcom/bytedance/android/live/design/widget/LiveEditText;") { classes[it]?.superclass }.toList()
            assertTrue("$version: the box's classes: $chain", "Landroid/widget/EditText;" in chain)
        }
    }
}
