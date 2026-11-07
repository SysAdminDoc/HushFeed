package app.morphe.patches.tiktok.feedfilter

import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook in the search card holder's constructor, on a nine-register frame (two locals, then p0
 * as v2, the view p1 as v3 and the protocol p4 as v6), and the builds it refuses: one that writes
 * over either, one whose protocol sits past v15, and one with no return, loudly where the build is
 * declared and quietly otherwise.
 */
class SearchAnswerCardHookTest {
    @Test
    fun `the holder's view and protocol are handed to the extension at every return`() {
        val method = constructor(
            """
                if-eqz p1, :no_view
                const/4 v0, 0x0
                return-void
                :no_view
                return-void
            """,
        )

        method.hideSearchAnswerCardAtReturns(required = true)

        val code = method.implementation!!.instructions.toList()
        assertEquals(6, code.size)
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertEquals(2, returns.size)
        returns.forEach { index ->
            val call = code[index - 1]
            assertEquals("ahead of the return at $index", Opcode.INVOKE_STATIC, call.opcode)
            val registers = call as FiveRegisterInstruction
            assertEquals(2, registers.registerCount)
            assertEquals("the view, p1", 3, registers.registerC)
            assertEquals("the protocol, p4", 6, registers.registerD)
            val target = (call as ReferenceInstruction).reference as MethodReference
            assertEquals("Lapp/morphe/extension/tiktok/feedfilter/TakoAiFilter;", target.definingClass)
            assertEquals("hideSearchAnswerCard", target.name)
            assertEquals(listOf("Landroid/view/View;", "Ljava/lang/Object;"), target.parameterTypes.map(CharSequence::toString))
        }
    }

    @Test
    fun `a build that writes over the view or the protocol goes without the hook`() {
        // Plain writes to p1 and p4, and a wide one to p3 whose second half is p4.
        listOf("const/4 p1, 0x0", "const/4 p4, 0x0", "const-wide p3, 0x0").forEach { write ->
            val body = """
                $write
                return-void
            """
            val refused = assertThrows(PatchException::class.java) {
                constructor(body).hideSearchAnswerCardAtReturns(required = true)
            }
            assertTrue(refused.message, refused.message!!.contains("writes over its view or protocol parameter"))
            assertLeftAlone(constructor(body))
        }
    }

    @Test
    fun `a build whose protocol sits past v15 goes without the hook`() {
        val body = """
            return-void
        """
        val refused = assertThrows(PatchException::class.java) {
            constructor(body, registers = 19).hideSearchAnswerCardAtReturns(required = true)
        }
        assertTrue(refused.message, refused.message!!.contains("keeps its parameters past v15"))
        assertLeftAlone(constructor(body, registers = 19))
    }

    @Test
    fun `a build with no return to hook goes without it`() {
        val body = """
            const/4 v0, 0x0
            throw v0
        """
        val refused = assertThrows(PatchException::class.java) {
            constructor(body).hideSearchAnswerCardAtReturns(required = true)
        }
        assertTrue(refused.message, refused.message!!.contains("has no return to hook"))
        assertLeftAlone(constructor(body))
    }

    /** A forced build: the method comes back exactly as it went in. */
    private fun assertLeftAlone(method: MutableMethod) {
        val before = method.implementation!!.instructions.map { it.opcode }
        method.hideSearchAnswerCardAtReturns(required = false)
        assertEquals(before, method.implementation!!.instructions.map { it.opcode })
    }

    private fun constructor(body: String, registers: Int = 9) = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/aweme/search/arch/v2/protocol/card/parser/SearchCardViewHolder;",
            "<init>",
            listOf(
                "Landroid/view/View;",
                "Landroidx/lifecycle/LifecycleOwner;",
                "LX/0H4T;",
                SEARCH_CARD_PROTOCOL_CLASS_DESCRIPTOR,
                "Lkotlin/jvm/functions/Function0;",
                "Lcom/ss/android/ugc/aweme/search/arch/v2/services/signal/impl/SearchContextAbilityImpl;",
            ).map { ImmutableMethodParameter(it, null, null) },
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body) }
}
