package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #415: covers and avatars stayed grey with SIM spoof on, and a report couldn't say why. The
 * IMAGE LOADS report counts every fetch and failure of Fresco's network producer, so on every
 * declared build the producer keeps both strings it's found by, makes exactly one fetch state
 * through its fetcher and keeps it in a register, and calls one failure method of its own with
 * that state and a Throwable. Both hooks go in with nothing else moved.
 */
class ImageLoadsAnchorsTest {
    private val expired = "The expired url does not initiate the request"

    private fun classes(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    private fun Method.loads(string: String) = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == string
    } == true

    private fun called(instruction: Instruction) =
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)

    @Test
    fun `each declared build hands every image fetch and failure to the IMAGE LOADS report`() {
        Fixtures.forEachDeclared { apk ->
            val producers = classes(apk).values.flatMap { owner ->
                owner.methods.filter { it.returnType == "V" && it.loads(expired) && it.loads("NetworkFetchProducer") }
                    .map { owner to it }
            }
            assertEquals("${apk.name}: one network fetch producer, $producers", 1, producers.size)
            val (owner, found) = producers.single()
            val producer = MutableClass(owner)
            val produce = producer.methods.single {
                it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameterTypes.map(CharSequence::toString)
            }
            val produceOriginal = produce.implementation!!.instructions.filter { it.opcode != Opcode.NOP }

            val hooks = planImageLoadHooks(produce, producer)
            val failure = hooks.failure
            val failureOriginal = failure.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            assertEquals("${apk.name}: the failure takes the fetch state and a Throwable",
                "Ljava/lang/Throwable;", failure.parameterTypes[1].toString())
            writeImageLoadHooks(produce, hooks)

            val code = produce.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            val marks = code.indices.filter { called(code[it])?.name == "requested" }
            assertEquals("${apk.name}: one fetch counted per produce", 1, marks.size)
            val mark = marks.single()
            val request = code[mark] as RegisterRangeInstruction
            assertEquals("${apk.name}: the extension's", IMAGE_LOADS_DESCRIPTOR, called(code[mark])!!.definingClass)
            assertEquals("${apk.name}: right after the state is kept", Opcode.MOVE_RESULT_OBJECT, code[mark - 1].opcode)
            assertEquals("${apk.name}: with that state",
                listOf((code[mark - 1] as OneRegisterInstruction).registerA, 1), listOf(request.startRegister, request.registerCount))
            assertEquals("${apk.name}: which the fetcher made", failure.parameterTypes[0].toString(), called(code[mark - 2])!!.returnType)
            assertEquals("${apk.name}: nothing else in the producer moves",
                produceOriginal.map { it.opcode }, code.filterIndexed { index, _ -> index != mark }.map { it.opcode })
            assertTrue("${apk.name}: the expired URL still goes to the failure method",
                code.any { called(it)?.name == failure.name && called(it)?.definingClass == owner.type })

            val failCode = failure.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            val failed = failCode[0] as RegisterRangeInstruction
            assertEquals("${apk.name}: the failure is counted first", "failed", called(failCode[0])!!.name)
            assertEquals("${apk.name}: with the state and the Throwable",
                listOf(failure.implementation!!.registerCount - 2, 2), listOf(failed.startRegister, failed.registerCount))
            assertEquals("${apk.name}: nothing else in the failure moves",
                failureOriginal.map { it.opcode }, failCode.drop(1).map { it.opcode })

            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(produce))
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(failure))
        }
    }

    @Test
    fun `a producer method without the fetch state or failure call fails the patch by name`() {
        Fixtures.forEachDeclared { apk ->
            val owner = classes(apk).values.single { c -> c.methods.any { it.loads(expired) } }
            val producer = MutableClass(owner)
            val constructor = producer.methods.first { it.name == "<init>" }
            val thrown = assertThrows(PatchException::class.java) { planImageLoadHooks(constructor, producer) }
            assertTrue(thrown.message, thrown.message!!.startsWith("SIM spoof: "))
        }
    }
}
