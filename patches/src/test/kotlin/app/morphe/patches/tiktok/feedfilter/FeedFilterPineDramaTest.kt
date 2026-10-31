package app.morphe.patches.tiktok.feedfilter

import app.morphe.patches.shared.compat.AppCompatibilities
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PineDrama's feed is TikTok's, ads included, so the feed filter is declared for it (#294). The
 * hooks PineDrama's build lacks (Friends tab, Tako entrances outside search, Lemon8 card, play-lag
 * cache insert) are asked for through `requiredWhenDeclared`, which is optional on any build whose
 * name isn't a declared TikTok version, and PineDrama's never is.
 */
class FeedFilterPineDramaTest {

    @Test
    fun `the feed filter is declared for TikTok and PineDrama`() {
        assertEquals(
            listOf("com.zhiliaoapp.musically", AppCompatibilities.PINEDRAMA_PACKAGE),
            feedFilterPatch.compatibility.orEmpty().map { it.packageName },
        )
    }

    @Test
    fun `the hooks PineDrama lacks are optional off a declared build`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/feedfilter/FeedFilterPatch.kt").readText()
        val missingOnPineDrama = listOf(
            "FriendsFeedAwemeList", "FriendsFeedSuccess", "FriendsFeedResponse", "InsertCacheWhenPlayLag",
            "TakoAskBarBind", "TakoAskBarTriggerBind", "TakoCommentTopBarCanShow", "Lemon8CardRequest",
        )
        for (name in missingOnPineDrama) {
            assertFalse("$name is still required on PineDrama", source.contains("${name}Fingerprint.method"))
            assertTrue("$name isn't asked for through requiredWhenDeclared", source.contains("requiredWhenDeclared(${name}Fingerprint)?."))
        }
        val rightBottom = Regex("""TakoRightBottomInCellEntranceBindFingerprint,[\s\S]*?TakoRightBottomInCellEntranceCreatedFingerprint,[\s\S]*?\.method""")
            .find(source)?.value.orEmpty()
        assertTrue("the Tako right-bottom entrance block moved", rightBottom.isNotEmpty())
        assertFalse("the Tako right-bottom entrances are still required", Regex("""\b(it|fingerprint)\.method\b""").containsMatchIn(rightBottom))
        assertTrue("declaredBuild no longer reads the declared versions", source.contains("val declaredBuild = packageMetadata.buildName in declaredVersions()"))
    }
}
