package app.morphe.patches.tiktok.feedfilter

import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #475: the home top bar's Tako icon. TakoTopIconAssem.onCreate (47.9.3: four registers,
 * `this` in v3) calls the assem's own onCreate, then adds its config callback to TikTok's A/B
 * config manager. The guard goes right after that super call, and the builds it refuses go without
 * it, loudly where the build is declared and quietly otherwise.
 */
class TakoTopIconHookTest {
    private val stock = """
        invoke-super {p0}, LX/02y2;->onCreate()V
        invoke-static {}, LX/05lo;->LIZJ()LX/05lo;
        move-result-object v2
        iget-object v0, p0, $TAKO_TOP_ICON_ASSEM->LL:LX/02ok;
        invoke-interface {v0}, LX/02ok;->getValue()Ljava/lang/Object;
        move-result-object v1
        if-nez v1, :add
        return-void
        :add
        iget-object v0, v2, LX/05lo;->LJFF:Ljava/util/concurrent/ConcurrentLinkedDeque;
        invoke-virtual {v0, v1}, Ljava/util/concurrent/ConcurrentLinkedDeque;->add(Ljava/lang/Object;)Z
        return-void
    """

    @Test
    fun `the switch is asked right after the super call and false runs the stock body`() {
        val method = onCreate(4, stock)
        val before = method.implementation!!.instructions.map { it.opcode }

        method.hideTakoTopIconAfterSuper(required = true)

        val code = method.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_SUPER, code[0].opcode)
        assertEquals(Opcode.INVOKE_STATIC, code[1].opcode)
        val asked = (code[1] as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/feedfilter/TakoAiFilter;", asked.definingClass)
        assertEquals("shouldHideTopIcon", asked.name)
        assertEquals("Z", asked.returnType)
        assertEquals(Opcode.MOVE_RESULT, code[2].opcode)
        assertEquals("the answer goes to v0", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[3].opcode)
        assertEquals(Opcode.RETURN_VOID, code[4].opcode)
        // false lands on the stock body's second instruction, the config manager lookup.
        val branch = code[3] as OffsetInstruction
        val offsets = code.runningFold(0) { at, it -> at + it.codeUnits }
        val target = offsets.indexOf(offsets[3] + branch.codeOffset)
        assertEquals("false lands on the stock second instruction", 5, target)
        assertEquals(Opcode.INVOKE_STATIC, code[target].opcode)
        assertEquals("LIZJ", ((code[target] as ReferenceInstruction).reference as MethodReference).name)
        // Everything after the guard is the stock body, in order.
        assertEquals(before.drop(1), code.drop(5).map { it.opcode })
    }

    @Test
    fun `a build whose onCreate no longer opens with the super call goes without the hook`() {
        val body = """
            invoke-static {}, LX/05lo;->LIZJ()LX/05lo;
            move-result-object v0
            invoke-super {p0}, LX/02y2;->onCreate()V
            return-void
        """
        val refused = assertThrows(PatchException::class.java) {
            onCreate(2, body).hideTakoTopIconAfterSuper(required = true)
        }
        assertTrue(refused.message, refused.message!!.contains("no longer opens with its super call"))
        assertLeftAlone(onCreate(2, body))
    }

    @Test
    fun `a build with no local for the answer goes without the hook`() {
        val body = """
            invoke-super {p0}, LX/02y2;->onCreate()V
            return-void
        """
        val refused = assertThrows(PatchException::class.java) {
            onCreate(1, body).hideTakoTopIconAfterSuper(required = true)
        }
        assertTrue(refused.message, refused.message!!.contains("has no local for the answer"))
        assertLeftAlone(onCreate(1, body))
    }

    /** A forced build: the method comes back exactly as it went in. */
    private fun assertLeftAlone(method: MutableMethod) {
        val before = method.implementation!!.instructions.map { it.opcode }
        method.hideTakoTopIconAfterSuper(required = false)
        assertEquals(before, method.implementation!!.instructions.map { it.opcode })
    }

    private fun onCreate(registers: Int, body: String) = MutableMethod(
        ImmutableMethod(
            TAKO_TOP_ICON_ASSEM,
            "onCreate",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body) }
}
