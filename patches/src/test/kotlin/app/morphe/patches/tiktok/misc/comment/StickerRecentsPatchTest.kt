package app.morphe.patches.tiktok.misc.comment

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
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
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep recent stickers (#156) is a PineDrama patch, and PineDrama 47.5.3 is built from TikTok
 * 47.5.3's code: the recently used store has the same add, reset and holder there (14gj.LJ,
 * 14gj.LIZIZ and 0mnM.LIZ on TikTok, Xf3 and Xf8 on PineDrama), so TikTok's fixtures hold what the
 * patch relies on.
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
