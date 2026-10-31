package app.morphe.patches.tiktok.misc.comment

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep recent stickers (#156) patches TikTok and PineDrama, and PineDrama 47.5.3 is built from TikTok
 * 47.5.3's code: the recently used store has the same add, reset and holder there (14gj.LJ,
 * 14gj.LIZIZ and 0mnM.LIZ on TikTok, Xf3 and Xf8 on PineDrama), and the basic Saved page builds
 * its rows the same way (emit$0 with 0zfW and 14gH on TikTok, emit$22 with Q1n and QRQ on
 * PineDrama), so TikTok's fixtures hold what the patch relies on.
 */
class StickerRecentsPatchTest {
    @Test
    fun `the add fingerprint takes one method in every retained fixture`() {
        fixtures { apk, classes ->
            assertEquals(apk, 1, adds(classes).size)
        }
    }

    @Test
    fun `the add and the reset write one holder, each right before a return`() {
        fixtures { apk, classes ->
            val (owner, add) = adds(classes).single()
            val write = add.recentStickersAddWrite()
            val reset = recentStickersReset(owner.methods, add, write.holder)
            assertTrue("$apk: ${reset.name} takes nothing", reset.parameterTypes.isEmpty())
            assertNotEquals(apk, add.name, reset.name)
            val resetWrites = reset.recentStickersWrites().filter { it.holder == write.holder }
            assertEquals(apk, 1, resetWrites.size)
            assertTrue("$apk: the holder ${write.holder} is the store's", classes.any { classDef ->
                classDef.fields.any { "${it.definingClass}->${it.name}:${it.type}" == write.holder }
            })
        }
    }

    @Test
    fun `save gets the add's new list and restore gets the reset's flow`() {
        fixtures { apk, classes ->
            val (owner, add) = adds(classes).single()
            val write = add.recentStickersAddWrite()
            val reset = recentStickersReset(owner.methods, add, write.holder)

            val hookedAdd = MutableMethod(add)
            hookedAdd.saveRecentStickers(hookedAdd.recentStickersAddWrite())
            assertHookBeforeReturn("$apk add", hookedAdd, add, "save", write.list)

            val hookedReset = MutableMethod(reset)
            hookedReset.restoreRecentStickers(hookedReset.recentStickersWrites().single { it.holder == write.holder })
            val resetWrite = reset.recentStickersWrites().single { it.holder == write.holder }
            assertHookBeforeReturn("$apk reset", hookedReset, reset, "restore", resetWrite.flow)
        }
    }

    @Test
    fun `a branch onto the add's return reaches the hook too`() {
        val method = MutableMethod(
            ImmutableMethod(
                "LX/Store;", "add", listOf(ImmutableMethodParameter(STICKER_ITEM_TYPE, null, null)),
                "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        )
        method.addInstructions(
            0,
            """
                if-eqz p1, :done
                iget-object v0, p0, LX/Holder;->flow:LX/Flow;
                invoke-virtual { v0, p1 }, LX/Flow;->setValue(Ljava/lang/Object;)V
                :done
                return-void
            """,
        )
        method.saveRecentStickers(method.recentStickersAddWrite())
        val code = method.implementation!!.instructions.toList()
        val branch = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        val target = branch.target.location.index
        assertHook("branch", code[target], "save", 3)
        assertEquals(Opcode.RETURN_VOID, code[target + 1].opcode)
    }

    @Test
    fun `a list read from anything but a holder field isn't a write`() {
        val method = MutableMethod(
            ImmutableMethod(
                "LX/Store;", "add", listOf(ImmutableMethodParameter(STICKER_ITEM_TYPE, null, null)),
                "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        )
        method.addInstructions(
            0,
            """
                sget-object v0, LX/Holder;->flow:LX/Flow;
                invoke-virtual { v0, p1 }, LX/Flow;->setValue(Ljava/lang/Object;)V
                return-void
            """,
        )
        assertEquals(emptyList<RecentStickersWrite>(), method.recentStickersWrites())
        val refused = assertThrows(PatchException::class.java) { method.recentStickersAddWrite() }
        assertEquals("Keep recent stickers: 0 list writes in the add, expected 1", refused.message)
    }

    @Test
    fun `the rows fingerprint takes the Saved page's one collector in every retained fixture`() {
        fixtures { apk, classes ->
            val collectors = classes.flatMap { classDef -> classDef.methods.filter { RecentStickerRowsFingerprint.takes(it, classDef) } }
            assertEquals(apk, 1, collectors.size)
            val rows = collectors.single().recentStickerRows()!!
            val byType = classes.associateBy { it.type }
            for (type in listOf(rows.pageType, rows.title, rows.item)) {
                assertTrue("$apk: $type is public", AccessFlags.PUBLIC.isSet(byType.getValue(type).accessFlags))
            }
            assertTrue("$apk: the page's registers fit a plain invoke", rows.page <= 15 && rows.rows <= 15)
        }
    }

    @Test
    fun `the row bridges call what the Saved page reaches and pass the verifier's typing`() {
        val lines = mapOf("recentStickers" to 8, "recentTitle" to 3, "recentItem" to 4)
        fixtures { apk, classes ->
            val byType = classes.associateBy { it.type }
            val rows = rowsCollector(classes).recentStickerRows()!!
            val store = recentStickerStore(byType.getValue(rows.pageType)) { byType[it] }
            val methods = classes.flatMap { classDef -> classDef.methods.map { it.recentSignature() } }.toSet()
            val bridges = recentRowBridges(rows, store)
            assertEquals(apk, lines.keys, bridges.map { it.name }.toSet())
            for (bridge in bridges) {
                assertEquals("$apk: ${bridge.name} keeps every line", lines.getValue(bridge.name), bridge.implementation!!.instructions.count())
                assertEquals("$apk: ${bridge.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
                for (reference in bridge.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }) {
                    assertTrue("$apk: ${bridge.name} calls ${reference.recentSignature()}", reference.recentSignature() in methods)
                }
            }
            assertTrue("$apk: the store getter ${store.getter}", store.getter.startsWith(rows.pageType + "->"))
            assertEquals(apk, "invoke-interface", store.storeInvoke)
        }
    }

    @Test
    fun `addRow gets the page and its rows right before the page takes them`() {
        fixtures { apk, classes ->
            val stock = rowsCollector(classes)
            val rows = stock.recentStickerRows()!!
            val hooked = MutableMethod(stock)
            hooked.addRecentStickerRow(rows)
            val code = hooked.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            assertEquals("$apk gains one line", stock.implementation!!.instructions.count { it.opcode != Opcode.NOP } + 1, code.size)
            val added = code.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC && (it as ReferenceInstruction).reference.toString().contains("StickerRecents") }
            val target = (code[added] as ReferenceInstruction).reference as MethodReference
            assertEquals(apk, "Lapp/morphe/extension/tiktok/comment/StickerRecents;->addRow(Ljava/lang/Object;Ljava/lang/Object;)V", target.recentSignature())
            val registers = code[added] as FiveRegisterInstruction
            assertEquals(apk, listOf(rows.page, rows.rows), listOf(registers.registerC, registers.registerD))
            val call = (code[added + 1] as ReferenceInstruction).reference as MethodReference
            assertEquals("$apk: the page takes its rows next", "${rows.pageType}(Ljava/util/List;)V", "${call.definingClass}(${call.parameterTypes.joinToString("")})${call.returnType}")
            assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }

    /** The shipped dex has R8's public static stubs, not the source's package-private ones. */
    @Test
    fun `the row bridges replace public static stubs with their flags kept and refuse an instance stub`() {
        val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        fixtures { apk, classes ->
            val byType = classes.associateBy { it.type }
            val rows = rowsCollector(classes).recentStickerRows()!!
            val store = recentStickerStore(byType.getValue(rows.pageType)) { byType[it] }
            val stubs = recentRowBridges(rows, store) { publicStatic }
            val extension = MutableClass(stickerRecents(stubs))
            extension.swapRecentRowStubs(rows, store)
            assertEquals(apk, stubs.map { it.name }.toSet(), extension.methods.map { it.name }.toSet())
            for (bridge in extension.methods) {
                assertEquals("$apk: ${bridge.name}", publicStatic, bridge.accessFlags)
                assertTrue("$apk: ${bridge.name} is the bridge", stubs.none { stub -> stub === bridge })
            }

            val instance = recentRowBridges(rows, store) { name -> if (name == "recentItem") AccessFlags.PUBLIC.value else publicStatic }
            val refused = assertThrows(PatchException::class.java) {
                MutableClass(stickerRecents(instance)).swapRecentRowStubs(rows, store)
            }
            assertEquals(apk, "Keep recent stickers: the recentItem stub isn't static", refused.message)
        }
    }

    @Test
    fun `a rows call past v15 is refused rather than dropped`() {
        val method = MutableMethod(
            ImmutableMethod(
                "LX/Rows;", "emit", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null, null, ImmutableMethodImplementation(1, emptyList(), null, null),
            ),
        )
        method.addInstructions(0, "return-void")
        val refused = assertThrows(PatchException::class.java) {
            method.addRecentStickerRow(RecentStickerRows(0, 16, 3, "LX/Page;", "LX/Title;", "LX/Item;"))
        }
        assertEquals("Keep recent stickers: the rows call uses v16 and v3, past v15", refused.message)
    }

    private fun rowsCollector(classes: List<ClassDef>): Method =
        classes.flatMap { classDef -> classDef.methods.filter { RecentStickerRowsFingerprint.takes(it, classDef) } }.single()

    private fun stickerRecents(methods: List<MutableMethod>) = ImmutableClassDef(
        "Lapp/morphe/extension/tiktok/comment/StickerRecents;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(), methods,
    )

    private fun MethodReference.recentSignature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun assertHookBeforeReturn(what: String, hooked: MutableMethod, stock: Method, hook: String, register: Int) {
        val code = hooked.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
        val before = stock.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
        assertEquals("$what gains one line", before.size + 1, code.size)
        val added = code.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC_RANGE && (it as ReferenceInstruction).reference.toString().contains("StickerRecents") }
        assertHook(what, code[added], hook, register)
        assertEquals("$what returns right after", Opcode.RETURN_VOID, code[added + 1].opcode)
        val setValue = code[added - 1]
        assertEquals("$what follows the list write", "setValue", ((setValue as ReferenceInstruction).reference as MethodReference).name)
        assertTrue("$what passes the write's register", register in (setValue as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals(what, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
    }

    private fun assertHook(what: String, hook: Instruction, name: String, register: Int) {
        val target = (hook as ReferenceInstruction).reference as MethodReference
        val range = hook as RegisterRangeInstruction
        assertEquals(what, Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals(what, "Lapp/morphe/extension/tiktok/comment/StickerRecents;->$name(Ljava/lang/Object;)V",
            "${target.definingClass}->${target.name}(${target.parameterTypes.joinToString("")})${target.returnType}")
        assertEquals(what, register to 1, range.startRegister to range.registerCount)
    }

    private fun adds(classes: List<ClassDef>): List<Pair<ClassDef, Method>> =
        classes.flatMap { classDef -> classDef.methods.filter { RecentStickerAddFingerprint.takes(it, classDef) }.map { classDef to it } }

    private fun fixtures(check: (String, List<ClassDef>) -> Unit) {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            check(apk.name, container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes })
        }
    }

    private companion object {
        const val STICKER_ITEM_TYPE = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"
    }
}
