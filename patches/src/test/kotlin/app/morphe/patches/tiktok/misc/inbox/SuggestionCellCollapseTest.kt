package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hook at a suggestion cell's bind, on the bridge shape TikTok 46.2.3 gives it. */
class SuggestionCellCollapseTest {
    @Test
    fun `the cell is handed to the extension as TikTok's bind returns`() {
        val method = bindBridge()
        val original = method.implementation!!.instructions.toList()

        method.collapseSuggestionCellAtBind()

        val code = method.implementation!!.instructions.toList()
        assertEquals(original.size + 1, code.size)
        // TikTok's own bind first, untouched: the Rect cells write a margin inside it, and a
        // call ahead of that would have been undone.
        original.dropLast(1).forEachIndexed { index, instruction -> assertSame(instruction, code[index]) }
        val call = code[code.size - 2]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val range = call as RegisterRangeInstruction
        // p0 is v0 on a two-register method with one parameter: the cell itself.
        assertEquals(1, range.registerCount)
        assertEquals(0, range.startRegister)
        val target = (call as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/inbox/SuggestedAccountCells;", target.definingClass)
        assertEquals("onBind", target.name)
        assertEquals(listOf("Ljava/lang/Object;"), target.parameterTypes.map(CharSequence::toString))
        assertEquals("V", target.returnType)
        assertSame(original.last(), code.last())
        assertEquals(Opcode.RETURN_VOID, code.last().opcode)
    }

    @Test
    fun `a branch to the bind's return runs the hook too`() {
        // TitleAreaCell's bind on 47.4.3 jumps to its last return from six places. A hook put
        // ahead of that return without its label was jumped over, and the title stayed.
        val method = branchingBind()

        method.collapseSuggestionCellAtBind()

        val code = method.implementation!!.instructions.toList()
        assertEquals(4, code.size)
        val branch = code.first() as BuilderOffsetInstruction
        val landing = code[branch.target.location.index]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, landing.opcode)
        assertEquals("onBind", ((landing as ReferenceInstruction).reference as MethodReference).name)
        // The cast still falls through into the same call.
        assertEquals(Opcode.CHECK_CAST, code[1].opcode)
        assertSame(landing, code[2])
        assertEquals(Opcode.RETURN_VOID, code.last().opcode)
    }

    @Test
    fun `a suggestion list's title is collapsed with its cells`() {
        val cells = "Lcom/ss/android/ugc/aweme/relation/usercard/impl/cell/"
        assertTrue(isSuggestionListCell("${cells}AbsRecUserCell;"))
        assertTrue(isSuggestionListCell("${cells}BaseRectRecUserCell;"))
        // Profile > Find people on 47.4.3 kept its "Suggested accounts" title over an empty list.
        assertTrue(isSuggestionListCell("${cells}TitleAreaCell;"))
        // The Facebook and contacts cards, the entry rows and the loading row stay.
        assertFalse(isSuggestionListCell("${cells}AbsAuthCell;"))
        assertFalse(isSuggestionListCell("${cells}EntryAreaCell;"))
        assertFalse(isSuggestionListCell("${cells}AdapterLoadingCell;"))
        assertFalse(isSuggestionListCell("${cells}FixedCell;"))
        assertFalse(isSuggestionListCell("Lcom/ss/android/ugc/aweme/profile/TitleAreaCell;"))
    }

    @Test
    fun `a build whose suggestion cells moved fails even when the title cell stayed`() {
        val cells = "Lcom/ss/android/ugc/aweme/relation/usercard/impl/cell/"
        requireSuggestionCellBinds(listOf("${cells}TitleAreaCell;", "${cells}AbsRecUserCell;"))
        assertThrows(PatchException::class.java) {
            requireSuggestionCellBinds(listOf("${cells}TitleAreaCell;"))
        }
        assertThrows(PatchException::class.java) { requireSuggestionCellBinds(emptyList()) }
    }

    /** `AbsRecUserCell.onBindItemView(LX/0lOS;)V` on 46.2.3: a check-cast, the typed bind, return. */
    private fun bindBridge() = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/aweme/relation/usercard/impl/cell/AbsRecUserCell;",
            "onBindItemView",
            listOf(ImmutableMethodParameter("LX/0cY2;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference("LX/0l3W;")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        ),
    )

    /** `TitleAreaCell.onBindItemView` on 47.4.3, cut down: a branch straight to its return. */
    private fun branchingBind() = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/aweme/relation/usercard/impl/cell/TitleAreaCell;",
            "onBindItemView",
            listOf(ImmutableMethodParameter("LX/07SV;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    // if-eqz p0 is 2 code units and the cast 2 more, so +4 is the return.
                    ImmutableInstruction21t(Opcode.IF_EQZ, 1, 4),
                    ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference("LX/0O5B;")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        ),
    )
}
