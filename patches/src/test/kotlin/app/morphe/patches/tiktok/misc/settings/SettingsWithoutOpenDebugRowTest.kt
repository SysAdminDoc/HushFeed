/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #460: a run where TikTok's Open Debug row couldn't be found failed Settings, and with it every
 * patch that depends on Settings. The row is one way into the page, so losing it costs only the row.
 */
class SettingsWithoutOpenDebugRowTest {
    private val body: String = run {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/settings/SettingsPatch.kt").readText()
        source.substring(source.indexOf("execute {", source.indexOf("val settingsPatch")))
    }

    private val guard = body.indexOf("OpenDebugCellVmDefaultStateFingerprint.methodOrNull == null")

    @Test
    fun `a missing row ends the row work without failing the patch`() {
        assertTrue("nothing checks for the row", guard >= 0)
        val leaves = body.indexOf("return@execute", guard)
        assertTrue("the check doesn't leave the patch quietly", leaves > guard)
        val check = body.substring(guard, leaves)
        assertTrue("the check does other work first", "fun " !in check && ".apply" !in check)
        assertTrue("the check throws", "PatchException" !in check)
    }

    @Test
    fun `nothing needs the row before the check`() {
        val required = Regex("""OpenDebugCellVmDefaultStateFingerprint\.method\b""").find(body)?.range?.first
        assertTrue("the row is required before the check", required == null || required > guard)
    }

    @Test
    fun `the page and its other ways in are set up before the check`() {
        listOf(
            "AdPersonalizationActivityOnCreateFingerprint.method",
            "AdPersonalizationActivityOnBackPressedFingerprint.method",
            "hookForYouLongPress()",
            "registerForBuildDetails(\"enableSettings\")",
        ).forEach { step ->
            val at = body.indexOf(step)
            assertTrue("$step is missing or comes after the check", at in 0 until guard)
        }
    }
}
