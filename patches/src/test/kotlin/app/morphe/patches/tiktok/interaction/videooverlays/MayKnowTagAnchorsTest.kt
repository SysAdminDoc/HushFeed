/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val CONTROLS = "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;"
private const val TAG_PACKAGE = "Lcom/ss/android/ugc/feed/platform/cell/interact/info/horiontag/tag/"

/**
 * What the people you may know switch hooks (#462), held to each declared build: the one gate both
 * of the tag's triggers ask, with the switch's question in a local and TikTok's body after it.
 */
class MayKnowTagAnchorsTest {
    @Test
    fun `each declared build has one gate, the fingerprint takes it and both triggers ask it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byShape = methodsOf(apk) { _, method -> isMayKnowTagGate(method) }
            val byFingerprint = methodsOf(apk) { classDef, method -> MayKnowTagGateFingerprint.takes(method, classDef) }
            assertEquals("$version: gates ${byShape.map { it.first.type }}", 1, byShape.size)
            assertEquals(
                "$version: the fingerprint took ${byFingerprint.map { it.first.type }}",
                byShape.map { it.first.type to it.second.name },
                byFingerprint.map { it.first.type to it.second.name },
            )
            val gate = byShape.single().second
            assertTrue("$version: the gate is not static", AccessFlags.STATIC.isSet(gate.accessFlags))
            assertTrue("$version: no local for the guard", gate.implementation!!.registerCount >= 2)

            val callers = methodsOf(apk) { _, method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.getReference<MethodReference>()?.let {
                        it.definingClass == gate.definingClass && it.name == gate.name && it.returnType == "Z" &&
                            it.parameterTypes.map(CharSequence::toString) == listOf(AWEME)
                    } == true
                } == true
            }.map { it.first.type }
            for (trigger in listOf("FeedSocialMafTagTrigger;", "FeedSocialMafTagTriggerV2;")) {
                assertTrue("$version: $trigger doesn't ask the gate, callers $callers", TAG_PACKAGE + trigger in callers)
            }
        }
    }

    @Test
    fun `the guard goes in front of the gate and leaves its body as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val native = methodsOf(apk) { _, method -> isMayKnowTagGate(method) }.single().second
            val before = native.implementation!!.instructions.toList()

            val guarded = MutableMethod(native)
            guarded.hideMayKnowTag("Hide video overlays")
            val after = guarded.implementation!!.instructions.toList()

            assertEquals(
                "$version: the guard",
                listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                after.take(5).map { it.opcode },
            )
            val call = after[0].getReference<MethodReference>()!!
            assertEquals(CONTROLS, call.definingClass)
            assertEquals("shouldHideMayKnowTag", call.name)
            assertEquals("Z", call.returnType)
            assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
            assertEquals(0, (after[4] as OneRegisterInstruction).registerA)
            assertEquals(before.map { it.opcode }, after.takeLast(before.size).map { it.opcode })
        }
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
