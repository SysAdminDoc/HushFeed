package app.morphe.patches.tiktok.misc.comment

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open the comments at full height (#191), held to every declared build: the sheet's height
 * method is one static method with a free local for the guard and one full height sibling, and
 * the panel sizing reads the same sheet mode into a register it writes again before reading.
 */
class CommentSheetHeightAnchorsTest {
    @Test
    fun `the height method, its full height sibling and the panel's mode read are each one`() {
        Fixtures.forEachDeclared { apk ->
            val methods = methodsOf(apk).toList()

            val heights = methods.filter { (classDef, method) -> CommentSheetHeightFingerprint.takes(method, classDef) }
            assertEquals("${apk.name}: height took ${heights.map { "${it.first.type}->${it.second.name}" }}", 1, heights.size)
            val (heightClass, height) = heights.single()
            assertTrue("${apk.name}: ${height.name} is not static", AccessFlags.STATIC.isSet(height.accessFlags))
            val body = height.implementation!!
            assertTrue("${apk.name}: ${height.name} has no local for the guard", body.registerCount - 1 >= 1)

            val sibling = fullHeightSibling(heightClass, height)
            assertTrue("${apk.name}: the sibling is the height method", sibling.name != height.name)
            assertEquals("I", sibling.returnType)

            val mode = sheetModeCall(height)
            assertTrue("${apk.name}: the mode call takes arguments", mode.parameterTypes.isEmpty())

            val panels = methods.filter { (classDef, method) -> CommentPanelSizeFingerprint.takes(method, classDef) }
            assertEquals("${apk.name}: panel took ${panels.map { it.second.name }}", 1, panels.size)
            val instructions = panels.single().second.implementation!!.instructions.toList()
            val modeIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_STATIC &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)?.let {
                        it.definingClass == mode.definingClass && it.name == mode.name &&
                            it.parameterTypes.isEmpty() && it.returnType == "I"
                    } == true
            }
            assertTrue("${apk.name}: the panel reads no sheet mode", modeIndex >= 0)
            val result = instructions[modeIndex + 1]
            assertEquals("${apk.name}: the mode's answer is not moved next", Opcode.MOVE_RESULT, result.opcode)
            val register = (result as OneRegisterInstruction).registerA
            // Only constants for other registers may sit between the move and the test (48.0.2
            // sets two there), so the lifted mode is what the test reads.
            val testIndex = (modeIndex + 2 until instructions.size).first { instructions[it].opcode != Opcode.CONST_4 }
            assertTrue(
                "${apk.name}: a constant between the mode and its test writes the mode's register",
                instructions.subList(modeIndex + 2, testIndex).all { (it as OneRegisterInstruction).registerA != register },
            )
            assertEquals("${apk.name}: the mode is not tested next", Opcode.IF_LEZ, instructions[testIndex].opcode)
            assertEquals(
                "${apk.name}: the test reads another register",
                register,
                (instructions[testIndex] as OneRegisterInstruction).registerA,
            )

            // The panel asks the height method by name once the mode passes.
            assertTrue(
                "${apk.name}: the panel never asks the height method",
                instructions.any { instruction ->
                    instruction.opcode == Opcode.INVOKE_STATIC &&
                        ((instruction as ReferenceInstruction).reference as? MethodReference)?.let {
                            it.definingClass == height.definingClass && it.name == height.name
                        } == true
                },
            )

            // #261: the second sizing pass is one method that writes one FrameLayout height, on
            // the params of a view it read them from, both in registers an invoke can name.
            val resizes = methods.filter { (classDef, method) -> CommentPanelResizeFingerprint.takes(method, classDef) }
            assertEquals("${apk.name}: resize took ${resizes.map { it.second.name }}", 1, resizes.size)
            val resize = resizes.single().second.implementation!!.instructions.toList()
            val heightWrites = resize.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.IPUT &&
                    ((instruction as ReferenceInstruction).reference as? FieldReference)?.let {
                        it.definingClass == "Landroid/widget/FrameLayout\$LayoutParams;" && it.name == "height"
                    } == true
            }
            assertEquals("${apk.name}: the resize writes ${heightWrites.size} panel heights", 1, heightWrites.size)
            val (heightIndex, heightWrite) = heightWrites.single()
            val paramsIndex = resize.subList(0, heightIndex).indexOfLast { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)?.name == "getLayoutParams"
            }
            assertTrue("${apk.name}: the resize reads no layout params", paramsIndex >= 0)
            val paramsRead = resize[paramsIndex + 1]
            assertEquals(Opcode.MOVE_RESULT_OBJECT, paramsRead.opcode)
            assertEquals(
                "${apk.name}: the height goes on other params than the panel's",
                (paramsRead as OneRegisterInstruction).registerA,
                (heightWrite as TwoRegisterInstruction).registerB,
            )
            assertTrue("${apk.name}: the height register is out of reach", heightWrite.registerA <= 15)
            assertTrue(
                "${apk.name}: the panel register is out of reach",
                (resize[paramsIndex] as FiveRegisterInstruction).registerC <= 15,
            )

            // #261: the input check is one method, and every height it writes goes on the
            // params of the fragment's panel, read just before, in registers an invoke can name.
            val checks = methods.filter { (classDef, method) -> CommentInputCheckFingerprint.takes(method, classDef) }
            assertEquals("${apk.name}: input check took ${checks.map { "${it.first.type}->${it.second.name}" }}", 1, checks.size)
            val check = checks.single().second
            val checkInstructions = check.implementation!!.instructions.toList()
            val writes = panelHeightWrites(check, "Landroid/view/ViewGroup\$LayoutParams;")
            assertEquals("${apk.name}: the input check writes ${writes.size} panel heights", 2, writes.size)
            for ((index, heightRegister, panelRegister) in writes) {
                assertTrue("${apk.name}: the height register is out of reach", heightRegister <= 15)
                assertTrue("${apk.name}: the panel register is out of reach", panelRegister <= 15)
                val panelRead = checkInstructions.subList(0, index).last { instruction ->
                    instruction.opcode == Opcode.IGET_OBJECT && (instruction as TwoRegisterInstruction).registerA == panelRegister
                }
                val field = (panelRead as ReferenceInstruction).reference as FieldReference
                assertEquals(
                    "${apk.name}: the input check sizes another view",
                    "Lcom/ss/android/ugc/aweme/commentv2/commentpage/commonvideo/DefaultCommentPageContainerFragment;",
                    field.definingClass,
                )
                assertEquals("Landroid/view/View;", field.type)
            }
        }
    }

    @Test
    fun `the height is guarded, the mode is lifted and the row's flag is set`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/comment/CommentSheetHeightPatch.kt").readText()
        assertTrue("the height is not guarded", source.contains("height.guardAtEntry("))
        assertTrue("the answer is not the switch's", source.contains("openAtFullHeight()Z"))
        assertTrue("the mode is not lifted", source.contains("heightMode(I)I"))
        assertTrue(
            "the full height is not kept below the status bar (#261)",
            source.contains("belowStatusBar(\${CONTEXT}I)I"),
        )
        assertTrue(
            "the second sizing pass keeps its share (#261)",
            source.contains("panelHeight(Landroid/view/View;I)I"),
        )
        assertTrue(
            "the input check keeps its share (#261)",
            source.contains("keepPanelHeight(inputCheck, "),
        )
        assertTrue("the row's flag is not set", source.contains("SettingsStatus;->enableCommentsFullHeight()V"))
    }

    private fun methodsOf(apk: File): Sequence<Pair<ClassDef, Method>> {
        val byType = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
        }
        return byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
