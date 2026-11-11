package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.patch.PatchException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnClassesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `the list names the app classes of both extension dex files and nothing else`() {
        val output = temporary.root.resolve(OWN_CLASSES_ASSET)
        OwnClasses.write(output) { path -> javaClass.getResourceAsStream("/$path") }
        val names = output.readText(Charsets.ISO_8859_1).removeSuffix("\n").split('\n')
        // shared.mpe holds the shared library and the R class, tiktok.mpe the TikTok extension.
        listOf(
            "Lapp/morphe/extension/shared/Utils;",
            "Lapp/morphe/extension/shared/diagnostics/OtherBundles;",
            "Lapp/morphe/extension/tiktok/feedfilter/KeywordRules;",
            "Lapp/morphe/extension/R;",
        ).forEach { assertTrue(it, it in names) }
        assertEquals(names.toSortedSet().toList(), names)
        assertTrue(names.all { it.startsWith("Lapp/") && it.endsWith(";") })
        // The MP3 encoder ships under its own name and is matched by package in the report.
        assertFalse(names.any { it.startsWith("Lde/") })
    }

    @Test fun `a bundle missing an extension dex file stops the patch`() {
        val output = temporary.root.resolve(OWN_CLASSES_ASSET)
        assertThrows(PatchException::class.java) {
            OwnClasses.write(output) { path -> if (path.endsWith("tiktok.mpe")) null else javaClass.getResourceAsStream("/$path") }
        }
        assertFalse(output.exists())
    }

    @Test fun `a file that is not a dex is refused`() {
        assertThrows(PatchException::class.java) { OwnClasses.appClasses(ByteArray(0x70)) }
    }
}
