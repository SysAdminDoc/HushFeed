package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Following request's count (#226): TikTok's helper calls FollowFeedApiV2.followFeedPost (and
 * followFeedGet before 47.7.3 dropped it) with the count held in a register it filled with the
 * literal 20, and the hook hands the cursor, level and count to the extension just before each call.
 */
class FollowFeedFirstReadCountTest {
    private val api = "Lcom/ss/android/ugc/aweme/follow/api/FollowFeedApiV2;"
    private val extension = "Lapp/morphe/extension/tiktok/feedfilter/FollowingLatestFirst;"

    @Test
    fun `every retained fixture has one helper that sets the count to 20 and the hook reaches each call`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val helpers = load(apk).values.flatMap { it.methods }.filter { "followFeedPost" in it.apiCalls() }
            assertEquals("${apk.name}: helpers calling the POST ${helpers.map { it.name }}", 1, helpers.size)
            val expected = helpers.single().apiCalls().size
            val method = MutableMethod(helpers.single())
            method.raiseFollowFeedFirstReadCount()

            val instructions = method.implementation!!.instructions.toList()
            val hooks = instructions.withIndex().filter { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference.let {
                    it is MethodReference && it.definingClass == extension && it.name == "requestCount"
                }
            }
            assertEquals("${apk.name}: hooks", expected, hooks.size)
            hooks.forEach { (index, hook) ->
                val range = hook as RegisterRangeInstruction
                assertEquals("${apk.name}: cursor, level and count", 4, range.registerCount)
                val result = instructions[index + 1] as OneRegisterInstruction
                assertEquals(Opcode.MOVE_RESULT, result.opcode)
                assertEquals("${apk.name}: the count is written back", range.startRegister + 3, result.registerA)
                val call = (instructions[index + 2] as ReferenceInstruction).reference as MethodReference
                assertEquals(api, call.definingClass)
                // The call's range starts at the receiver: request index, cursor (two), level, count.
                assertEquals(range.startRegister - 2, (instructions[index + 2] as RegisterRangeInstruction).startRegister)
            }
        }
    }

    @Test
    fun `a count that is not the literal 20 stops the patch`() {
        val method = MutableMethod(helperSource(literal = 30))
        val error = assertThrows(PatchException::class.java) { method.raiseFollowFeedFirstReadCount() }
        assertTrue(error.message!!, error.message!!.contains("not the literal 20"))
    }

    @Test
    fun `a helper without the POST or with two of them stops the patch`() {
        assertThrows(PatchException::class.java) { MutableMethod(helperSource(20, "followFeedGet")).raiseFollowFeedFirstReadCount() }
        assertThrows(PatchException::class.java) {
            MutableMethod(helperSource(20, "followFeedPost", "followFeedPost")).raiseFollowFeedFirstReadCount()
        }
        val postOnly = MutableMethod(helperSource(20, "followFeedPost"))
        postOnly.raiseFollowFeedFirstReadCount()
        assertEquals(1, postOnly.implementation!!.instructions.count {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "requestCount"
        })
    }

    private fun Method.apiCalls(): List<String> = implementation?.instructions
        ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        ?.filter { it.definingClass == api && (it.name == "followFeedGet" || it.name == "followFeedPost") }
        ?.map { it.name }
        .orEmpty()

    /** A helper shaped like TikTok's: the count register v7 is filled, then GET and POST are called. */
    private fun helperSource(literal: Int, vararg requests: String = arrayOf("followFeedGet", "followFeedPost")): Method {
        fun call(name: String) = ImmutableInstruction3rc(
            Opcode.INVOKE_INTERFACE_RANGE,
            2,
            6,
            ImmutableMethodReference(api, name, listOf("I", "J", "I", "I"), "V"),
        )
        return ImmutableMethod(
            "LX/Helper;",
            "helper",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                8,
                listOf(ImmutableInstruction21s(Opcode.CONST_16, 7, literal)) + requests.map { call(it) } +
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                null,
                null,
            ),
        )
    }

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
