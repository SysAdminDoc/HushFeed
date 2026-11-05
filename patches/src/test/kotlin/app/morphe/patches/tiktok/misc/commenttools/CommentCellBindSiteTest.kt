package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.translation.BaseCommentCellBindFingerprint
import app.morphe.takes
import app.morphe.util.addInstructions
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Where Comment tools meets each comment row, held to each TikTok build the bundle declares.
 *
 * The hook used to follow the last write into the cell's state holder, the place Comment
 * translation hooks. From 47.5.3 that holder is filled only on the translation branch, so a
 * comment in the reader's own language never reached the hook and its row got none of the
 * controls. The hook now takes the comment the bind reads off the item it was handed, straight
 * after the PowerCell's own bind and ahead of every branch, in a register a plain invoke-static
 * can name.
 */
class CommentCellBindSiteTest {
    @Test
    fun `the row hook reads the bound item's comment ahead of every branch on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            val binds = classes.values.flatMap { classDef ->
                classDef.methods.filter { BaseCommentCellBindFingerprint.takes(it, classDef) }
            }
            assertEquals("$version: the cell bind fingerprint takes ${binds.map { "${it.definingClass}->${it.name}" }}",
                1, binds.size)
            val bind = binds.single()

            // Throws when the bind branches before reading the comment.
            val (index, register) = bind.commentBindSite()
            assertTrue("$version: the comment sits in v$register", register <= 15)
            // The row's view needs a low register of its own there, taken the way the patch takes it.
            bind.getFreeRegisterProvider(index, 1, listOf(register)).getFreeRegister4Bit()

            // The comment comes off the item this bind was handed, not off something the cell kept
            // from an earlier bind: the getter's receiver is the parameter or a copy of it.
            val instructions = bind.implementation!!.instructions.toList()
            val receiver = (instructions[index - 2] as FiveRegisterInstruction).registerC
            val item = bind.implementation!!.registerCount - 1
            val copies = instructions.take(index - 2).any {
                it.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
                    (it as TwoRegisterInstruction).registerA == receiver && it.registerB == item
            }
            assertTrue("$version: the comment is read off v$receiver, not the bound item v$item",
                receiver == item || copies)
        }
    }

    @Test
    fun `a bind that branches before it reads the comment is refused`() {
        assertEquals(3 to 0, bind(branchFirst = false).commentBindSite())
        try {
            bind(branchFirst = true).commentBindSite()
            fail("a branch ahead of the comment must refuse the patch")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), "branches" in refused.message.orEmpty())
        }
    }

    @Test
    fun `a bind that reads a comment the cell kept is refused`() {
        try {
            bind(branchFirst = false, receiver = "p0").commentBindSite()
            fail("a comment read off the cell must refuse the patch")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), "not the bound item" in refused.message.orEmpty())
        }
    }

    /** 47.6.3's bind in miniature: the PowerCell's bind, then the item's comment. */
    private fun bind(branchFirst: Boolean, receiver: String = "p1"): MutableMethod {
        val cell = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/BaseCommentCell;"
        val method = MutableMethod(
            ImmutableMethod(
                cell, "s7", listOf(ImmutableMethodParameter("LX/0H11;", null, null)), "V", AccessFlags.PUBLIC.value,
                null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        )
        method.addInstructions(
            (if (branchFirst) "if-eqz p1, :done\n" else "") +
                """
                    invoke-super {p0, p1}, Lcom/bytedance/ies/powerlist/PowerCell;->onBindItemView(LX/07FX;)V
                    invoke-virtual {$receiver}, LX/0H10;->LIZ()Lcom/ss/android/ugc/aweme/comment/model/Comment;
                    move-result-object v0
                    invoke-virtual {p0, v0}, $cell->P8(Lcom/ss/android/ugc/aweme/comment/model/Comment;)V
                    :done
                    return-void
                """,
        )
        return method
    }
}
