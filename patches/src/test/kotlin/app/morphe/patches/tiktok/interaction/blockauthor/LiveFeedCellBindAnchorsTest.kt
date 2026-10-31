package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LIVE cell holder's item setter, which is how a For You LIVE preview reaches the block button.
 *
 * <p>The player never names a LIVE and the video bind carries no author for one, so without this
 * hook the creator of the video before it stayed armed under a LIVE. The setter's name is
 * obfuscated; it is the one public final `(Aweme)V` of the holder base that opens by storing the item.
 */
class LiveFeedCellBindAnchorsTest {
    private val holder = "Lcom/ss/android/ugc/aweme/feed/adapter/BaseFeedLiveViewHolder;"

    @Test
    fun `the setter is one method on every declared fixture and stores the item first`() {
        Fixtures.forEachDeclared { apk ->
            val classDef = holderClass(apk)
            val taken = classDef.methods.filter { LiveFeedCellBindFingerprint.takes(it, classDef) }.toList()
            assertEquals("${taken.map { it.name }}", 1, taken.size)
            val setter = taken.single()
            val first = setter.implementation!!.instructions.first()
            assertEquals(Opcode.IPUT_OBJECT, first.opcode)
            // The hook goes in at index 0 with the holder and the item as the first two parameters,
            // so the method must be an instance method taking exactly the item.
            assertEquals(listOf(PLAYED_AWEME), setter.parameterTypes.map { it.toString() })
            assertTrue("the setter has no registers for this and the item", setter.implementation!!.registerCount >= 2)
        }
    }

    @Test
    fun `the patch hooks the setter with the holder and the item in range form`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/blockauthor/BlockAuthorPatch.kt").readText()
        assertTrue(source.contains("LiveFeedCellBindFingerprint.method.addInstruction("))
        assertTrue(source.contains("invoke-static/range { p0 .. p1 }"))
        assertTrue(source.contains("onLiveCellBound(Ljava/lang/Object;Ljava/lang/Object;)V"))
    }

    private fun holderClass(apk: File): ClassDef {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == holder }?.let { return it }
        }
        throw AssertionError("${apk.name}: $holder is gone")
    }
}
