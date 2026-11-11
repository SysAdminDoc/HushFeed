package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loading further ahead on Following (#226): on every retained build the pager's checkLoadMore
 * takes its threshold from one int getter of its own class and subtracts it from the item count,
 * and the hook hands that threshold through the extension right before the subtraction. The
 * Following panel's two list deliveries report each read to the extension first thing.
 */
class FollowingLoadAheadTest {
    private val extension = "Lapp/morphe/extension/tiktok/feedfilter/FollowingLatestFirst;"

    @Test
    fun `every retained fixture raises the load-more threshold right before the subtraction`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val panel = load(apk).getValue(FULL_FEED_FRAGMENT_PANEL_DESCRIPTOR)
            val method = MutableMethod(panel.methods.single { it.name == "checkLoadMore" && it.parameterTypes.isEmpty() })
            method.loadFollowingFurtherAhead()

            val instructions = method.implementation!!.instructions.toList()
            val hook = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "loadAhead"
            }
            assertTrue("${apk.name}: no loadAhead call", hook > 1)
            val call = instructions[hook] as FiveRegisterInstruction
            assertEquals(extension, ((call as ReferenceInstruction).reference as MethodReference).definingClass)
            val getterResult = instructions[hook - 1] as OneRegisterInstruction
            assertEquals(Opcode.MOVE_RESULT, getterResult.opcode)
            assertEquals(Opcode.INVOKE_STATIC, instructions[hook - 2].opcode)
            assertEquals(2, call.registerCount)
            assertEquals("${apk.name}: the receiver", method.implementation!!.registerCount - 1, call.registerC)
            assertEquals(getterResult.registerA, call.registerD)
            assertEquals(Opcode.MOVE_RESULT, instructions[hook + 1].opcode)
            assertEquals(getterResult.registerA, (instructions[hook + 1] as OneRegisterInstruction).registerA)
            val subtract = instructions[hook + 2] as TwoRegisterInstruction
            assertEquals(Opcode.SUB_INT_2ADDR, instructions[hook + 2].opcode)
            assertEquals(getterResult.registerA, subtract.registerB)
        }
    }

    @Test
    fun `every retained fixture reports both Following reads to the extension first`() {
        Fixtures.apks().forEach { apk ->
            val panel = load(apk).getValue(FOLLOW_FEED_FRAGMENT_PANEL_DESCRIPTOR)
            val deliveries = followingReadDeliveries(panel.methods.map { MutableMethod(it) })
            deliveries.forEach { method ->
                // Each one hands its read to TikTok's own delivery, which is what the hook stands for.
                assertTrue("${apk.name}: ${method.name} does not call its own delivery",
                    method.implementation!!.instructions.any {
                        it.opcode == Opcode.INVOKE_SUPER &&
                            ((it as ReferenceInstruction).reference as MethodReference).let { target ->
                                target.definingClass == FULL_FEED_FRAGMENT_PANEL_DESCRIPTOR && target.name == method.name
                            }
                    })
                method.reportFollowingRead()
                val call = method.implementation!!.instructions.first() as RegisterRangeInstruction
                assertEquals(Opcode.INVOKE_STATIC_RANGE, method.implementation!!.instructions.first().opcode)
                assertEquals("delivered", ((call as ReferenceInstruction).reference as MethodReference).name)
                assertEquals(3, call.registerCount)
                assertEquals("${apk.name}: p0", method.implementation!!.registerCount - 3, call.startRegister)
            }
        }
    }

    @Test
    fun `a check with no threshold getter stops the patch`() {
        val method = checkLoadMore(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)))
        val error = assertThrows(PatchException::class.java) { method.loadFollowingFurtherAhead() }
        assertTrue(error.message!!, error.message!!.contains("calls 0 int getters"))
    }

    @Test
    fun `a threshold that is not taken off the item count stops the patch`() {
        val method = checkLoadMore(
            2,
            listOf(
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, threshold),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val error = assertThrows(PatchException::class.java) { method.loadFollowingFurtherAhead() }
        assertTrue(error.message!!, error.message!!.contains("not taken off the item count"))
    }

    @Test
    fun `a receiver past v15 stops the patch`() {
        val method = checkLoadMore(
            20,
            listOf(
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, threshold),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction12x(Opcode.SUB_INT_2ADDR, 1, 0),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val error = assertThrows(PatchException::class.java) { method.loadFollowingFurtherAhead() }
        assertTrue(error.message!!, error.message!!.contains("v19 or v0 is past"))
    }

    @Test
    fun `a Following panel without both deliveries stops the patch`() {
        val one = ImmutableMethod(
            FOLLOW_FEED_FRAGMENT_PANEL_DESCRIPTOR,
            "Vk",
            listOf(ImmutableMethodParameter("Ljava/util/List;", null, null), ImmutableMethodParameter("Z", null, null)),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        val error = assertThrows(PatchException::class.java) { followingReadDeliveries(listOf(one)) }
        assertTrue(error.message!!, error.message!!.contains("found 1"))
    }

    private val threshold = ImmutableMethodReference(FULL_FEED_FRAGMENT_PANEL_DESCRIPTOR, "Vf", listOf(), "I")

    private fun checkLoadMore(registers: Int, instructions: List<Instruction>) = MutableMethod(
        ImmutableMethod(
            FULL_FEED_FRAGMENT_PANEL_DESCRIPTOR,
            "checkLoadMore",
            listOf(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(registers, instructions, null, null),
        ),
    )

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
