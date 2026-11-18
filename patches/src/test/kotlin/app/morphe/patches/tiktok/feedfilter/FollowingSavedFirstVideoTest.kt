package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Following feed's restore of last run's saved list (#226): every retained build has one
 * refreshList that reads the saved cache from its static getter and checks its updateTime, and the
 * hook replaces what that read returned with the extension's answer, which is no cache at all when
 * the switch is on.
 */
class FollowingSavedFirstVideoTest {
    private val extension = "Lapp/morphe/extension/tiktok/feedfilter/FollowingLatestFirst;"

    @Test
    fun `every retained fixture has one restore and the hook can drop the saved cache`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val restores = load(apk).values.flatMap { it.methods }.filter { it.isRestore() }
            assertEquals("${apk.name}: restores ${restores.map { "${it.definingClass}->${it.name}" }}", 1, restores.size)
            val method = MutableMethod(restores.single())
            method.skipSavedFollowingFirstVideo()

            val instructions = method.implementation!!.instructions.toList()
            val read = instructions.indexOfFirst { it.isCacheRead() }
            assertTrue("${apk.name}: the cache read", read >= 0)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[read + 1].opcode)
            val cache = (instructions[read + 1] as OneRegisterInstruction).registerA

            val hook = instructions[read + 2]
            val call = (hook as ReferenceInstruction).reference as MethodReference
            assertEquals(extension, call.definingClass)
            assertEquals("restoredCache", call.name)
            assertEquals(cache, (hook as FiveRegisterInstruction).registerC)

            assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[read + 3].opcode)
            assertEquals(cache, (instructions[read + 3] as OneRegisterInstruction).registerA)
            val cast = instructions[read + 4]
            assertEquals(Opcode.CHECK_CAST, cast.opcode)
            assertEquals(cache, (cast as OneRegisterInstruction).registerA)
            assertEquals(FOLLOWING_CACHE_DESCRIPTOR, ((cast as ReferenceInstruction).reference as TypeReference).type)

            // TikTok's own null check is what turns the dropped cache into a normal first load.
            assertEquals("${apk.name}: the null check", Opcode.IF_EQZ, instructions[read + 5].opcode)
            assertEquals(cache, (instructions[read + 5] as OneRegisterInstruction).registerA)
        }
    }

    @Test
    fun `a method that never reads the saved cache stops the patch`() {
        val method = MutableMethod(
            ImmutableMethod(
                "LX/Restore;",
                "refreshList",
                listOf(ImmutableMethodParameter("[Ljava/lang/Object;", null, null)),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
            ),
        )
        val error = assertThrows(PatchException::class.java) { method.skipSavedFollowingFirstVideo() }
        assertTrue(error.message!!, error.message!!.contains("reads the saved cache 0 times"))
    }

    private fun Instruction.isCacheRead(): Boolean =
        opcode == Opcode.INVOKE_STATIC && ((this as ReferenceInstruction).reference as? MethodReference)?.let {
            it.returnType == FOLLOWING_CACHE_DESCRIPTOR && it.parameterTypes.isEmpty()
        } == true

    private fun Method.isRestore(): Boolean =
        name == "refreshList" &&
            returnType == "V" &&
            parameterTypes.map { it.toString() } == listOf("[Ljava/lang/Object;") &&
            implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                    it.definingClass == FOLLOWING_CACHE_DESCRIPTOR && it.name == "updateTime"
                } == true
            } == true

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
