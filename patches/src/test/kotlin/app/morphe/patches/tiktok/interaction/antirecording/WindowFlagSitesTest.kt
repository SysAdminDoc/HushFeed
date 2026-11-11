package app.morphe.patches.tiktok.interaction.antirecording

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.navigation.branchTargets
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Window flag calls `Allow screenshots and Circle to Search` rewrites: only one that could set
 * the secure flag, so a class that just keeps the screen on or goes full screen stays as it is (#339).
 */
class WindowFlagSitesTest {
    private val window = "Landroid/view/Window;"

    @Test
    fun aConstantWithoutTheSecureFlagIsLeftAlone() {
        assertFalse(secureFrom("const/16 v1, 0x80\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertFalse(secureFrom("const/high16 v1, 0x1000000\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertFalse(secureFrom("const/4 v1, 0x2\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertFalse(secureFrom("const v1, 0x10100\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertFalse(secureFrom(
            "const/16 v1, 0x400\nconst/16 v2, 0x2400\ninvoke-virtual {v0, v1, v2}, $window->setFlags(II)V"))
        assertFalse(secureFrom("const/16 v1, 0x80\ninvoke-virtual/range {v0 .. v1}, $window->addFlags(I)V"))
        // A write to another register in between doesn't change the flags.
        assertFalse(secureFrom("const/16 v1, 0x80\nconst/16 v2, 0x2000\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
    }

    @Test
    fun theSecureFlagOrAnUnknownValueIsRewritten() {
        assertTrue(secureFrom("const/16 v1, 0x2000\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertTrue(secureFrom("const/16 v1, 0x2080\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertTrue(secureFrom("const/4 v1, -0x1\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertTrue(secureFrom("const v1, 0x80002000\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertTrue(secureFrom(
            "const/16 v1, 0x2000\nconst/16 v2, 0x2000\ninvoke-virtual {v0, v1, v2}, $window->setFlags(II)V"))
        assertTrue(secureFrom(
            "invoke-static {}, Lcom/example/Flags;->get()I\nmove-result v1\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        assertTrue(secureFrom("const/16 v1, 0x80\nor-int/lit16 v1, v1, 0x2000\ninvoke-virtual {v0, v1}, $window->addFlags(I)V"))
        // Nothing in the method loads the flags.
        assertTrue(secureFrom("invoke-virtual {v0, v1}, $window->addFlags(I)V"))
    }

    @Test
    fun aConstantFromBeforeABranchTargetIsNotTrusted() {
        // The jump lands on the call with v1 still 0x2000 on that path.
        assertTrue(secureFrom("""
            const/16 v1, 0x2000
            if-eqz v2, :call
            const/16 v1, 0x80
            :call
            invoke-virtual {v0, v1}, $window->addFlags(I)V
        """.trimIndent()))
        assertTrue(secureFrom("""
            const/16 v1, 0x2000
            if-eqz v2, :load
            const/16 v1, 0x80
            :load
            const/4 v3, 0x0
            invoke-virtual {v0, v1}, $window->addFlags(I)V
        """.trimIndent()))
        // A branch target before the constant doesn't matter.
        assertFalse(secureFrom("""
            if-eqz v2, :load
            nop
            :load
            const/16 v1, 0x80
            invoke-virtual {v0, v1}, $window->addFlags(I)V
        """.trimIndent()))
    }

    /** [smali] ending in, or holding one, Window flag call, with v0 to v3 free. */
    private fun secureFrom(smali: String): Boolean {
        val method = MutableMethod(
            ImmutableMethod(
                "Lcom/example/Caller;", "call", listOf(), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali + "\nreturn-void") }
        val instructions = method.implementation!!.instructions.toList()
        val call = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == window
        }
        return mayAddSecure(instructions, call, method.branchTargets())
    }
}
