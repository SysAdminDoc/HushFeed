package app.morphe.patches.tiktok.interaction.resume

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a video starts again, held to each declared build: the fingerprint takes one method, and
 * that method keeps the event type check the patch overrides (47.8.3 renamed the method, gave it
 * a third parameter and moved the landscape tags it used to be found by into a helper).
 */
class ResumePositionAnchorsTest {
    private fun classes(apk: java.io.File): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
    }

    @Test
    fun `the resume position method is found and keeps its event type check`() {
        Fixtures.forEachDeclared { apk ->
            val taken = classes(apk).flatMap { owner ->
                owner.methods.filter { FeedProgressResumePositionFingerprint.takes(it, owner) }
            }
            assertEquals("taken: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val code = taken.single().implementation!!.instructions.toList()
            val list = code.indexOfFirst { it.getReference<FieldReference>()?.name == "event_type_list" }
            assertTrue("no read of event_type_list", list >= 0)
            val contains = (list until code.size).firstOrNull {
                code[it].getReference<MethodReference>()?.let { m -> m.definingClass == "Ljava/util/List;" && m.name == "contains" } == true
            }
            assertTrue("no List.contains after event_type_list", contains != null)
            val answer = code[contains!! + 1]
            assertEquals(Opcode.MOVE_RESULT, answer.opcode)
            assertTrue("the answer is above v15", (answer as OneRegisterInstruction).registerA <= 15)
        }
    }
}
