package app.morphe.patches.tiktok

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val PAID_INFO = "Lcom/ss/android/ugc/aweme/feed/model/PaidContentInfo;"

/**
 * #527: DramaEpisodes.locked reads a post's paid struct by these names, by reflection, to keep a
 * drama episode TikTok shows only a preview of from being fetched whole. A rename would read as
 * unlocked, so every declared build has to carry them.
 */
class DramaEpisodeLockAnchorsTest {
    @Test
    fun `each declared build names a post's paid struct and its preview flag as the episode lock reads them`() {
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
            assertTrue("${apk.name}: PaidContentInfo.isPartialPreview()Z", info!!.methods.any {
                it.name == "isPartialPreview" && it.parameterTypes.isEmpty() && it.returnType == "Z"
            })
            assertTrue("${apk.name}: PaidContentInfo.isPartialPreview:Z",
                info.fields.any { it.name == "isPartialPreview" && it.type == "Z" })
        }
    }
}
