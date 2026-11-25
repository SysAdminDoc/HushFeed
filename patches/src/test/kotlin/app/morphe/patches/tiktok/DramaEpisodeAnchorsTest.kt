package app.morphe.patches.tiktok

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val PAID_INFO = "Lcom/ss/android/ugc/aweme/feed/model/PaidContentInfo;"

/**
 * #444: DramaEpisodes.collectionId reads a post's paid struct and its drama id by these names, by
 * reflection, to tell an episode of a drama from any other post. A rename would hide Save
 * episodes on every episode, so every declared build has to carry them.
 */
class DramaEpisodeAnchorsTest {
    @Test
    fun `each declared build names a post's paid struct and its drama id as the episode list reads them`() {
        Fixtures.forEachDeclared { apk ->
            val classes = Fixtures.dexContainer(apk, Opcodes.getDefault()).let { container ->
                container.dexEntryNames.asSequence()
                    .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                    .filter { it.type == AWEME || it.type == PAID_INFO }.associateBy { it.type }
            }
            val aweme = classes[AWEME]
            assertTrue("${apk.name}: no $AWEME", aweme != null)
            assertTrue("${apk.name}: Aweme.mPaidContentInfo",
                aweme!!.fields.any { it.name == "mPaidContentInfo" && it.type == PAID_INFO })
            val info = classes[PAID_INFO]
            assertTrue("${apk.name}: no $PAID_INFO", info != null)
            assertTrue("${apk.name}: PaidContentInfo.getPaidCollectionId() or paidCollectionId",
                info!!.methods.any { it.name == "getPaidCollectionId" && it.parameterTypes.isEmpty() } ||
                    info.fields.any { it.name == "paidCollectionId" })
        }
    }
}
