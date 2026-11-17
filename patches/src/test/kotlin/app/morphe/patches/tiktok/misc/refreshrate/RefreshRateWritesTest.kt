package app.morphe.patches.tiktok.misc.refreshrate

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.InputStream
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The patch sends TikTok's stores to the window's preferredRefreshRate through the extension's
 * RefreshRate.apply, and apply makes that store itself. The extension is merged into the app
 * before the patch looks, so a search over every class finds apply's own store too. Replaced,
 * it became a call to apply from inside apply, and TikTok died of a StackOverflowError the
 * first time it asked for a rate, whether the switch was on or off. The probe merges the built
 * extension and runs the rewrite the patch runs, so the extension's classes are in the search.
 */
class RefreshRateWritesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `only TikTok's own stores are routed and apply keeps its store`() {
        assertTrue("no built extension on the test classpath", OWNER.getResource(EXTENSION) != null)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val stock = mutableListOf<String>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        val instructions = method.implementation?.instructions ?: continue
                        instructions.filter(::storesRate).forEach { stock += "${classDef.type}->${method.name}" }
                    }
                }
            }
            assertTrue("$version: TikTok asks for a rate somewhere", stock.isNotEmpty())

            var checked = false
            val probe = patch(name = "refresh rate probe") {
                extendWith(Supplier<InputStream> { OWNER.getResourceAsStream(EXTENSION)!! })
                execute {
                    RefreshRateWriteFingerprint.clearMatch()
                    val routed = routeRefreshRateWrites()
                    RefreshRateWriteFingerprint.clearMatch()
                    assertEquals("$version: the stores routed", stock.sorted(), routed.sorted())

                    val apply = mutableClassDefBy(EXTENSION_CLASS).methods.single { it.name == "apply" }
                    val code = apply.implementation!!.instructions.toList()
                    assertEquals("$version: apply's own store", 1, code.count(::storesRate))
                    val callsItself = code.any { instruction ->
                        instruction.opcode == Opcode.INVOKE_STATIC &&
                            instruction.getReference<MethodReference>()?.let {
                                it.definingClass == EXTENSION_CLASS && it.name == "apply"
                            } == true
                    }
                    assertTrue("$version: apply calls itself", !callsItself)
                    checked = true
                }
            }
            RefreshRateWriteFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            RefreshRateWriteFingerprint.clearMatch()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    private fun storesRate(instruction: Instruction) =
        instruction.opcode == Opcode.IPUT &&
            instruction.getReference<FieldReference>()?.let {
                it.definingClass == "Landroid/view/WindowManager\$LayoutParams;" && it.name == "preferredRefreshRate"
            } == true

    private companion object {
        const val EXTENSION = "/extensions/tiktok.mpe"
        const val EXTENSION_CLASS = "Lapp/morphe/extension/tiktok/misc/RefreshRate;"
        val OWNER = RefreshRateWritesTest::class.java
    }
}
