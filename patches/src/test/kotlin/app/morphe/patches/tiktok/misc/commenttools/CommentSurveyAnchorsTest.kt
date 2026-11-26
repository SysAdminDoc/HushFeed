package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Hide comment surveys answers, held to every declared build.
 *
 * <p>A comment list asks one resolver for the survey a video shows. It reads the survey the video
 * brought in its comment config, or the arrays the server's comment_survey setting filled, and
 * answers a holder; a video without a survey gets the holder class's own empty instance, which is
 * what every account without a survey sees. The patch answers that same empty holder first while
 * the switch is on, so the callers need nothing they don't already handle.
 */
class CommentSurveyAnchorsTest {
    @Test
    fun `the survey resolver is one method on every declared build, and its empty holder is already one of its answers`() {
        Fixtures.forEachDeclared { apk ->
            val taken = methodsOf(apk).filter { (classDef, method) -> CommentSurveyConfigFingerprint.takes(method, classDef) }.toList()
            assertEquals(taken.map { "${it.first.type}->${it.second.name}" }.toString(), 1, taken.size)
            val resolver = taken.single().second
            assertTrue("the resolver became static", !AccessFlags.STATIC.isSet(resolver.accessFlags))
            val locals = resolver.implementation!!.registerCount - (1 + resolver.parameterTypes.size)
            assertTrue("the resolver has no local for the answer", locals >= 1)

            val none = resolver.noCommentSurveyAnswer()
            val instructions = resolver.implementation!!.instructions.toList()
            val answersNone = instructions.zipWithNext().any { (load, ret) ->
                load.opcode == Opcode.SGET_OBJECT &&
                    ((load as ReferenceInstruction).reference as FieldReference).name == none.name &&
                    ret.opcode == Opcode.RETURN_OBJECT &&
                    (ret as OneRegisterInstruction).registerA == (load as OneRegisterInstruction).registerA
            }
            assertTrue("the resolver never answers its empty holder, so its callers may not expect it", answersNone)
        }
    }

    @Test
    fun `the patch answers no survey first while the switch is on`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/commenttools/CommentToolsPatch.kt").readText()
        assertTrue("the resolver is not hooked", source.contains("val method = CommentSurveyConfigFingerprint.method"))
        assertTrue("the resolver does not ask the switch first", source.contains(
            "method.guardAtEntry(\n                        \"Comment tools\",\n" +
                "                        \"invoke-static {}, \$COMMENT_SURVEY_CLASS_DESCRIPTOR->hide()Z\",\n" +
                "                        \"sget-object v0, \${none.definingClass}->\${none.name}:\${none.type}\\nreturn-object v0\","))
    }

    /** Every method of one build, walked on each ask and never held (the dex has millions). */
    private fun methodsOf(apk: File): Sequence<Pair<ClassDef, Method>> {
        val byType = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
        }
        return byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
