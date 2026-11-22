package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #486: Hide playable games drops a feed post whose aweme type is 260, with no patch of its own.
 * That number is TikTok's: the Playable tag on a For You post shows when a static Aweme check
 * says yes, and that check is getAwemeType() == 260 and nothing else. A build that moves the
 * games to another type, or adds a second condition, fails here.
 */
class PlayableGameTypeAnchorsTest {
    private val trigger = "Lcom/ss/android/ugc/aweme/socialgame/impl/feed/vh/PlayableFeedVideoTagTrigger;"
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    private val videoItemParams = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"
    private val branches = setOf(
        Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
        Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
        Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH,
    )

    /** The type FeedItemsFilter's PlayableGameFilter compares with (AWEME_TYPE_PLAYABLE). */
    private val playableType = 260

    @Test
    fun `the Playable tag shows for aweme type 260 and nothing else`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val methods = HashMap<String, Method>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        methods["${method.definingClass}->${method.name}${method.parameterTypes.joinToString("")}"] = method
                    }
                }
            }
            val shows = methods.values.filter { method ->
                method.definingClass == trigger && method.returnType == "Z" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf(videoItemParams) &&
                    method.implementation != null
            }
            assertTrue("${apk.name}: $trigger has no VideoItemParams check", shows.isNotEmpty())
            // Every one of the trigger's checks asks the same static Aweme check of the post.
            val checks = shows.map { show ->
                val calls = show.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_STATIC }
                    .mapNotNull { it.getReference<MethodReference>() }
                    .filter { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(aweme) }
                assertEquals("${apk.name}: ${show.name} asks one Aweme check", 1, calls.size)
                calls.single().let { "${it.definingClass}->${it.name}$aweme" }
            }.toSet()
            assertEquals("${apk.name}: the trigger's checks ask $checks", 1, checks.size)
            val check = methods[checks.single()]
            assertNotNull("${apk.name}: ${checks.single()} is not in the APK", check)
            assertTrue("${apk.name}: the check is static", AccessFlags.STATIC.isSet(check!!.accessFlags))

            // null is no; otherwise getAwemeType() against one literal, and yes only when equal.
            val code = check.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.toList()
            val reads = code.withIndex().filter { (_, it) ->
                it.getReference<MethodReference>()?.let { call ->
                    call.definingClass == aweme && call.name == "getAwemeType"
                } == true
            }
            assertEquals("${apk.name}: the check reads getAwemeType once", 1, reads.size)
            val readAt = reads.single().index
            assertEquals(Opcode.MOVE_RESULT, code[readAt + 1].opcode)
            val type = (code[readAt + 1] as OneRegisterInstruction).registerA
            val literal = code[readAt + 2]
            assertTrue("${apk.name}: a literal follows the read", literal is NarrowLiteralInstruction)
            assertEquals("${apk.name}: the playable type", playableType, (literal as NarrowLiteralInstruction).narrowLiteral)
            val compare = code[readAt + 3]
            assertEquals("${apk.name}: the type is tested for equality", Opcode.IF_NE, compare.opcode)
            val compared = (compare as TwoRegisterInstruction).let { setOf(it.registerA, it.registerB) }
            assertEquals(setOf(type, (literal as OneRegisterInstruction).registerA), compared)
            // No other test of any kind: the only other branch is the null check before the read.
            val tests = code.filter { it.opcode in branches }
            assertEquals("${apk.name}: the null check and the type test", 2, tests.size)
            assertEquals(Opcode.IF_EQZ, tests.first().opcode)
        }
    }
}
