package app.morphe.patches.tiktok.interaction.searchsuggestions

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show only the search bar on Discover (#338), held to every declared build: the Discover tab's
 * page is found by its real name, and the hook hands the extension the page's own view (the
 * method's first parameter) ahead of TikTok's code, which follows unchanged.
 */
class DiscoverSearchBarOnlyHookTest {
    @Test
    fun `the Discover page hands its view to the extension before its own work on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val taken = methods(apk).filter { (classDef, method) ->
                DiscoverViewCreatedFingerprint.takes(method, classDef)
            }.toList()
            assertEquals("${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val (page, stock) = taken.single()
            assertEquals(DISCOVER_PAGE, page.type)
            assertFalse("onViewCreated is an instance method", AccessFlags.STATIC.isSet(stock.accessFlags))
            val superCall = stock.implementation!!.instructions.first()
            assertEquals("the page no longer starts by calling its super", Opcode.INVOKE_SUPER, superCall.opcode)

            val hooked = MutableMethod(stock).apply { hookDiscoverPage() }
            val code = hooked.implementation!!.instructions.toList()
            val call = code.first()
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            val reference = (call as ReferenceInstruction).reference as MethodReference
            assertEquals("Lapp/morphe/extension/tiktok/search/DiscoverSearchBarOnly;", reference.definingClass)
            assertEquals("onViewCreated", reference.name)
            assertEquals(listOf("Landroid/view/View;"), reference.parameterTypes.map(CharSequence::toString))
            assertEquals("V", reference.returnType)
            val registers = stock.implementation!!.registerCount
            // p0 is the fragment, p1 the page's view, p2 the saved state.
            val view = registers - 3 + 1
            call as RegisterRangeInstruction
            assertEquals("the hook is handed something other than the page's view", view, call.startRegister)
            assertEquals(1, call.registerCount)
            assertEquals("the hook needs registers the method doesn't have", registers, hooked.implementation!!.registerCount)
            assertEquals("TikTok's own code changed under the hook",
                stock.implementation!!.instructions.shape(), code.drop(1).shape())
            // Inside a try, the handler would meet locals the entry hasn't set yet.
            assertTrue("the hook would sit inside a try block",
                stock.implementation!!.tryBlocks.none { it.startCodeAddress == 0 })
        }
    }

    private fun Iterable<Instruction>.shape(): List<String> =
        filter { it.opcode != Opcode.NOP }.map { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference?.toString().orEmpty()
            "${instruction.opcode} $reference"
        }

    private fun methods(apk: java.io.File): Sequence<Pair<ClassDef, Method>> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val seen = HashSet<String>()
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .filter { it.type == DISCOVER_PAGE && seen.add(it.type) }
            .flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
