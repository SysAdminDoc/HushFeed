package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #415: with SIM spoof on, a phone on a Bangladeshi network had 317 of 340 image fetches refused
 * until a VPN was on. TikTok's network stack (TTNet) asks the app for the carrier region, the app
 * region, the carrier codes and the init-region header, and routes by them. On every declared
 * build each of those getters is found, in TTNet's adapter, in the app's provider that reads the
 * SIM, and in Chromium's network library, and every answer goes through the extension with
 * nothing else moved.
 */
class NetworkStackSimAnchorsTest {
    private fun classes(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    private fun strings(method: com.android.tools.smali.dexlib2.iface.Method) =
        method.implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.orEmpty()

    /** TTNet's adapter by shape: the one abstract class implementing both of its app interfaces itself. */
    private fun adapterOf(classes: Map<String, ClassDef>): String = classes.values.single {
        it.accessFlags and 0x400 != 0 && it.accessFlags and 0x200 == 0 && it.interfaces.containsAll(listOf(
            "Lcom/bytedance/frameworks/baselib/network/http/cronet/ICronetDepend;",
            "Lcom/bytedance/frameworks/baselib/network/http/cronet/ICronetAppProvider;",
        ))
    }.type

    @Test
    fun `each declared build hands every TTNet region and carrier answer to the extension`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            assertEquals("${apk.name}: TikTok keeps the adapter's name", TTNET_ADAPTER, adapterOf(classes))
            checkHooks(apk.name, classes)
        }
    }

    /**
     * #502: TikTok Lite renames TTNet's adapter and leaves its region getters to the two classes
     * under it, so SIM spoof found no adapter there. The adapter is found by its two interfaces
     * instead, and every answer goes through the extension just as on TikTok.
     */
    @Test
    fun `TikTok Lite's renamed adapter and its providers hand every answer to the extension`() {
        val apk = Fixtures.liteApk()
        val classes = classes(apk)
        val adapter = adapterOf(classes)
        assertTrue("${apk.name}: Lite renames the adapter", adapter != TTNET_ADAPTER && classes[TTNET_ADAPTER] == null)
        val hooks = checkHooks(apk.name, classes)
        assertEquals("${apk.name}: the adapter answers the carrier codes itself",
            setOf("getNetworkOperator", "getSimOperator"),
            hooks.filter { it.method.definingClass == adapter }.map { it.method.name }.toSet())
    }

    private fun checkHooks(build: String, classes: Map<String, ClassDef>): List<NetworkStackHook> {
        val adapter = adapterOf(classes)
        val hooks = planNetworkStackHooks(classes.values.asSequence()) { classes[it] }
        val byName = hooks.groupBy { it.method.name }
        assertEquals("$build: every getter found", NETWORK_STACK_BRIDGES.keys, byName.keys)

        // The app's providers are what read the SIM: each one's init-region header starts
        // with the carrier region, and every class that writes that header is hooked.
        val writers = classes.values.filter { owner ->
            owner.methods.any { it.name == "getAppInitialRegionInfo" && "carrierregion=" in strings(it) }
        }.map { it.type }.toSet()
        assertTrue("$build: a provider writes the init-region header", writers.isNotEmpty())
        assertEquals("$build: every one of them is hooked", writers,
            byName.getValue("getAppInitialRegionInfo").map { it.method.definingClass }.filter { it in writers }.toSet())
        for (name in listOf("getCarrierRegion", "getRegion")) {
            assertTrue("$build: a provider's own $name is hooked",
                byName.getValue(name).any { it.method.definingClass != adapter })
        }
        assertEquals("$build: the network library's two carrier codes",
            setOf("getNetworkOperator", "getSimOperator"),
            hooks.filter { it.method.definingClass == TTNET_NETWORK_LIBRARY }.map { it.method.name }.toSet())
        assertTrue("$build: nothing outside TTNet's adapter tree and library",
            hooks.all { it.method.definingClass == TTNET_NETWORK_LIBRARY || it.method.definingClass == adapter ||
                generateSequence(classes[it.method.definingClass]?.superclass) { type -> classes[type]?.superclass }
                    .any { type -> type == adapter } })

        for (hook in hooks) {
            val owner = MutableClass(classes.getValue(hook.method.definingClass))
            val method = owner.methods.single {
                it.name == hook.method.name && it.parameterTypes.isEmpty() && it.returnType == hook.method.returnType
            }
            val label = "$build: ${hook.method.definingClass}->${hook.method.name}"
            val original = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            val returns = original.count { it.opcode == Opcode.RETURN_OBJECT }
            method.writeNetworkStackHook(hook.bridge)

            val code = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            val calls = code.indices.filter {
                ((code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == NETWORK_STACK_DESCRIPTOR
            }
            assertEquals("$label: one call per answer", returns, calls.size)
            for (call in calls) {
                val reference = (code[call] as ReferenceInstruction).reference as MethodReference
                assertEquals("$label: through ${hook.bridge}", hook.bridge, reference.name)
                val register = (code[call] as RegisterRangeInstruction).startRegister
                assertEquals("$label: takes the answer back", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
                assertEquals("$label: into the same register", register, (code[call + 1] as OneRegisterInstruction).registerA)
                assertEquals("$label: right before it's returned", Opcode.RETURN_OBJECT, code[call + 2].opcode)
                assertEquals("$label: the one returned", register, (code[call + 2] as OneRegisterInstruction).registerA)
            }
            val added = calls.flatMap { listOf(it, it + 1) }.toSet()
            assertEquals("$label: nothing else moves",
                original.map { it.opcode }, code.filterIndexed { index, _ -> index !in added }.map { it.opcode })
            assertEquals(label, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
        return hooks
    }

    @Test
    fun `a build without TTNet's adapter or library fails the patch by name`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            for (gone in listOf(TTNET_ADAPTER, TTNET_NETWORK_LIBRARY)) {
                val thrown = assertThrows(PatchException::class.java) {
                    planNetworkStackHooks(classes.values.asSequence().filter { it.type != gone }) {
                        if (it == gone) null else classes[it]
                    }
                }
                assertTrue(thrown.message, thrown.message!!.startsWith("SIM spoof: "))
            }
        }
    }
}
