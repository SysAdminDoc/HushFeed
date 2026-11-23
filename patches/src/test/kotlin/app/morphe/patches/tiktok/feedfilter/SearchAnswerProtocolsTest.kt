package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The extension knows the AI answer card in search by its protocol's class name. Those classes are
 * in a plugin TikTok downloads, but the base app's service list loads them by name, so each name
 * the extension keeps is in the base app on every declared build.
 */
class SearchAnswerProtocolsTest {
    @Test
    fun `every declared build names each AI answer protocol the extension hides`() =
        assertNamedOnEveryBuild("SEARCH_ANSWER_PROTOCOLS")

    /** The search brand block (#484) is found the same way, by its protocols' class names. */
    @Test
    fun `every declared build names each brand block protocol the extension hides`() =
        assertNamedOnEveryBuild("SEARCH_BRAND_ZONE_PROTOCOLS")

    private fun assertNamedOnEveryBuild(setName: String) {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val source = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/feedfilter/TakoAiFilter.java").readText()
        val set = Regex("""$setName = Set\.of\(([^)]*)\)""").find(source)?.groupValues?.get(1)
            ?: error("TakoAiFilter no longer keeps $setName")
        val names = Regex("\"([^\"]+)\"").findAll(set).map { it.groupValues[1] }.toList()
        assertTrue("no protocol names in $setName", names.isNotEmpty())
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val strings = container.dexEntryNames.flatMapTo(HashSet()) { container.getEntry(it)!!.dexFile.stringSection }
            names.forEach { name ->
                assertTrue(
                    "${apk.name}: the base app no longer names $name",
                    name in strings || "L${name.replace('.', '/')};" in strings,
                )
            }
        }
    }
}
