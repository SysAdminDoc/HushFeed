package app.morphe.patches.tiktok.misc.comment

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved stickers refresh (#214): one coroutine reads the page size config's refreshCount and
 * asks StickerApiV2 for that many saved stickers. TikTok's fixtures hold the shape the patch
 * relies on.
 */
class SavedStickersRefreshTest {
    private val extension = "Lapp/morphe/extension/tiktok/comment/SavedStickers;"

    @Test
    fun `every retained fixture has one refresh and the count reaches the extension before the request`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val refreshes = methods(apk).filter { it.name == "invokeSuspend" && it.isSavedStickersRefresh() }
            assertEquals("${apk.name}: saved stickers refreshes", 1, refreshes.size)
            val method = MutableMethod(refreshes.single())
            method.raiseSavedStickersFirstRead()

            val instructions = method.implementation!!.instructions.toList()
            val read = instructions.indexOfFirst {
                it.opcode == Opcode.IGET_WIDE &&
                    ((it as ReferenceInstruction).reference as FieldReference).isStickerRefreshCount()
            }
            val count = (instructions[read] as TwoRegisterInstruction).registerA
            val hook = instructions[read + 1] as RegisterRangeInstruction
            val target = (hook as ReferenceInstruction).reference as MethodReference
            assertEquals("${apk.name}: the hook", extension to "refreshCount", target.definingClass to target.name)
            assertEquals("${apk.name}: the hook takes the count's register pair", count to 2, hook.startRegister to hook.registerCount)
            val result = instructions[read + 2] as OneRegisterInstruction
            assertEquals(Opcode.MOVE_RESULT_WIDE, result.opcode)
            assertEquals("${apk.name}: the count is written back", count, result.registerA)
            assertTrue(
                "${apk.name}: the request still follows",
                instructions.drop(read + 3).any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.isSavedStickersRead() == true
                },
            )
        }
    }

    @Test
    fun `a refreshCount read with no request after it stops the patch`() {
        val config = ImmutableFieldReference(STICKER_PAGE_SIZE_CONFIG, "refreshCount", "J")
        val method = MutableMethod(
            ImmutableMethod(
                "LX/Refresh;",
                "invokeSuspend",
                emptyList(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                ImmutableMethodImplementation(
                    3,
                    listOf(ImmutableInstruction22c(Opcode.IGET_WIDE, 0, 2, config), ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ),
        )
        val error = assertThrows(PatchException::class.java) { method.raiseSavedStickersFirstRead() }
        assertTrue(error.message!!, error.message!!.contains("no saved stickers request"))
    }

    private fun methods(apk: java.io.File): List<Method> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.flatMap { it.methods }
        }
    }
}
