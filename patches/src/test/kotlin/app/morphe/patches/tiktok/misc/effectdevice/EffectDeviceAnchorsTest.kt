package app.morphe.patches.tiktok.misc.effectdevice

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/EffectDeviceSpoof;"

private const val EFFECT_CONFIGURATION_BUILDER = "Lcom/ss/android/ugc/effectmanager/EffectConfiguration\$Builder;"
private const val MODEL_CONFIGURATION_BUILDER = "Lcom/ss/android/ugc/effectmanager/DownloadableModelConfig\$Builder;"

/**
 * Report a newer phone to effects (#176), held to every declared build: each method that hands
 * `Build.MODEL` to the effect platform's config builders is found exactly once, nothing else
 * calls either builder's device type setter, and every model read gets a typed call and a
 * move-result in its register.
 */
class EffectDeviceAnchorsTest {
    private val expectedReads = mapOf(
        EffectConfigurationFactoryFingerprint to 1,
        ModelConfigBuilderFingerprint to 1,
    )

    @Test
    fun `each fingerprint takes one method and its model reads each get a typed swap`() {
        Fixtures.forEachDeclared { apk ->
            val methods = methodsOf(apk).toList()
            val taken = expectedReads.map { (fingerprint, reads) ->
                val label = "${apk.name}: ${fingerprint.javaClass.simpleName}"
                val matches = methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }
                assertEquals("$label took ${matches.map { "${it.first.type}->${it.second.name}" }}", 1, matches.size)
                val native = matches.single().second

                val before = native.implementation!!.instructions.toList()
                val readsAt = before.indices.filter { isModelRead(before[it]) }
                assertEquals("$label: Build.MODEL reads", reads, readsAt.size)
                // The hook goes in after each read, so nothing may jump onto the instruction after it.
                readsAt.forEach {
                    assertEquals("$label: a branch lands after the model read", 0, branchesOnto(before, it + 1))
                }

                val patched = MutableMethod(native)
                assertEquals("$label: reads swapped", reads, patched.swapModelReads())
                val after = patched.implementation!!.instructions.toList()
                val callsAt = after.indices.filter { after[it].getReference<MethodReference>()?.name == "deviceType" &&
                    after[it].getReference<MethodReference>()?.definingClass == EXTENSION }
                assertEquals("$label: calls", reads, callsAt.size)
                for (call in callsAt) {
                    val invoke = after[call] as FiveRegisterInstruction
                    assertEquals(Opcode.INVOKE_STATIC, invoke.opcode)
                    val read = after[call - 1]
                    assertTrue("$label: the call does not follow a model read", isModelRead(read))
                    val register = (read as OneRegisterInstruction).registerA
                    assertEquals("$label: call register", register, invoke.registerC)
                    val result = after[call + 1]
                    assertEquals("$label: no move-result", Opcode.MOVE_RESULT_OBJECT, result.opcode)
                    assertEquals("$label: result register", register, (result as OneRegisterInstruction).registerA)
                }
                // Everything else is TikTok's, in its own order (the payload's alignment nop can move).
                fun rest(code: List<Instruction>, skip: Set<Int>) =
                    code.filterIndexed { index, instruction -> index !in skip && instruction.opcode != Opcode.NOP }
                        .map { it.opcode }
                assertEquals(
                    "$label: TikTok's code moved",
                    rest(before, emptySet()),
                    rest(after, callsAt.flatMap { listOf(it, it + 1) }.toSet()),
                )
                assertEquals("$label: the hook's types", emptyList<Any>(), BridgeTypeFlow.check(patched))
                "${native.definingClass}->${native.name}"
            }
            assertEquals("${apk.name}: the fingerprints overlap $taken", taken.size, taken.toSet().size)
            // Every caller of either setter is one of those, so a build that adds a config path
            // (47.6.3's DefaultResourceProvider creator set both, and 47.7.2 dropped it) shows up here.
            val setters = methods.filter { (classDef, method) ->
                method.implementation?.instructions?.any { isDeviceTypeSetter(it, classDef.type) } == true
            }.map { (classDef, method) -> "${classDef.type}->${method.name}" }
            assertEquals("${apk.name}: methods handing the effect builders a device type", taken.sorted(), setters.sorted())
        }
    }

    private fun isDeviceTypeSetter(instruction: Instruction, caller: String): Boolean {
        val call = instruction.getReference<MethodReference>() ?: return false
        if (call.definingClass == caller) return false
        return (call.definingClass == EFFECT_CONFIGURATION_BUILDER && call.name == "deviceType") ||
            (call.definingClass == MODEL_CONFIGURATION_BUILDER && call.name == "setDeviceType")
    }

    @Test
    fun `the patch swaps each method and sets the row's flag`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/effectdevice/EffectDevicePatch.kt").readText()
        assertTrue(source.contains("EffectConfigurationFactoryFingerprint.method.swapExactly(1)"))
        assertTrue(source.contains("ModelConfigBuilderFingerprint.method.swapExactly(1)"))
        assertTrue(source.contains("SettingsStatus;->enableEffectDeviceSpoof()V"))
        assertTrue(source.contains("deviceType(Ljava/lang/String;)Ljava/lang/String;"))
    }

    private fun isModelRead(instruction: Instruction): Boolean {
        if (instruction.opcode != Opcode.SGET_OBJECT) return false
        val field = instruction.getReference<FieldReference>() ?: return false
        return field.definingClass == "Landroid/os/Build;" && field.name == "MODEL"
    }

    /** How many branches in [code] land on instruction [target]. */
    private fun branchesOnto(code: List<Instruction>, target: Int): Int {
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        return code.indices.count { index ->
            val branch = code[index] as? OffsetInstruction
            branch != null && code[index].opcode != Opcode.FILL_ARRAY_DATA &&
                addresses.indexOf(addresses[index] + branch.codeOffset) == target
        }
    }

    private fun methodsOf(apk: File): Sequence<Pair<ClassDef, Method>> {
        val byType = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
        }
        return byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
