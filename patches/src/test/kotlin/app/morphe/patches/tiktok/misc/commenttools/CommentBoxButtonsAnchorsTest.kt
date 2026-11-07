package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Hide comment box buttons finds the photo, @ and gift buttons, held to every declared build.
 *
 * <p>Every comment input extends BaseInputAssem, which stores its four icon buttons in fields whose
 * names move from build to build (the photo button is LLLF on 47.0.3 and LLLFF on 47.1.4). The
 * "Add comment..." bar gives each one a click listener, and what the listener does names the
 * button: the photo one logs click_comment_photo, the others open the MENTION, GIFT or EMOJI panel.
 */
class CommentBoxButtonsAnchorsTest {
    @Test
    fun `the photo, @ and gift buttons are three different icon fields, each stored once, on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val setup = build.single(CommentInputViewCreatedFingerprint)
            val bar = build.single(FakeInputViewCreatedFingerprint)
            val fields = commentBoxButtonFields(bar) { build.byType[it] }
            assertEquals(CommentBoxButton.entries.toSet(), fields.keys)
            assertEquals("two buttons share a field: $fields", 3, fields.values.map { it.name }.toSet().size)
            fields.forEach { (button, field) ->
                val index = commentBoxButtonStore(setup, field)
                val store = setup.implementation!!.instructions.elementAt(index)
                assertEquals("$button", Opcode.IPUT_OBJECT, store.opcode)
                val before = setup.implementation!!.instructions.toList().subList(maxOf(0, index - 6), index)
                assertTrue("$button: the stored button isn't one the setup just found by id", before.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name == "findViewById"
                })
            }
        }
    }

    @Test
    fun `the row over the keyboard wires and stores its own photo, @ and gift buttons, each once`() {
        // 47.4.3's KeyboardIconGroupAssem isn't a BaseInputAssem, so the bar's fields never reached it.
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val setup = build.single(KeyboardIconGroupViewCreatedFingerprint)
            val fields = commentBoxButtonFields(setup, KEYBOARD_ICON_GROUP_ASSEM) { build.byType[it] }
            assertEquals(CommentBoxButton.entries.toSet(), fields.keys)
            assertEquals("two buttons share a field: $fields", 3, fields.values.map { it.name }.toSet().size)
            val instructions = setup.implementation!!.instructions.toList()
            fields.forEach { (button, field) ->
                assertEquals("$button", KEYBOARD_ICON_GROUP_ASSEM, field.definingClass)
                val index = commentBoxButtonStore(setup, field)
                val before = instructions.subList(maxOf(0, index - 6), index)
                assertTrue("$button: the stored button isn't one the setup just found by id", before.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name == "findViewById"
                })
                // Each listener carries its own button's mark and none of the others', and none opens the emoji panel.
                // 47.7.3 merges listeners into one class, so the mark is read off the body this one's number reaches.
                val listener = listenerOf(instructions, field, build)
                val marks = marksOf(listener)
                val own = when (button) {
                    CommentBoxButton.PHOTO -> "photo"
                    CommentBoxButton.MENTION -> "mention"
                    CommentBoxButton.GIFT -> "gift"
                }
                assertEquals("$button: ${listener.map { "${it.definingClass}->${it.name}" }} carries $marks", setOf(own), marks)
            }
        }
    }

    @Test
    fun `the plus panel's tiles and the row's plus check each look a button up once and skip a null`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            listOf(CommentPlusPanelRefreshFingerprint, KeyboardIconGroupPlusFingerprint).forEach { fingerprint ->
                val method = build.single(fingerprint)
                val instructions = method.implementation!!.instructions.toList()
                val (index, register) = plusPanelLookups(method).single()
                // The enum's constant names are what the extension reads, so they must still be its names.
                val map = instructions[index - 2].let { ((it as ReferenceInstruction).reference as FieldReference) }
                val buttons = build.byType.getValue(map.definingClass)
                val names = buttons.fields.filter { it.type == buttons.type }.map { it.name }.toSet()
                assertTrue("$fingerprint: ${buttons.type} has $names", names.containsAll(setOf("PHOTO", "MENTION", "GIFT", "EMOJI")))
                val constructed = buttons.methods.single { it.name == "<clinit>" }.implementation!!.instructions
                    .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
                assertTrue("$fingerprint: the enum names its constants $constructed", constructed.containsAll(setOf("PHOTO", "MENTION", "GIFT")))
                val after = instructions.subList(index + 1, minOf(index + 3, instructions.size))
                assertTrue("$fingerprint: a null button isn't skipped right after the lookup: ${after.map { it.opcode }}", after.any {
                    it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == register
                })
            }
        }
    }

    private fun listenerOf(instructions: List<Instruction>, field: FieldReference, build: Build): List<Method> {
        val read = instructions.indices.first { index ->
            instructions[index].opcode == Opcode.IGET_OBJECT &&
                ((instructions[index] as ReferenceInstruction).reference as FieldReference).name == field.name &&
                instructions.subList(index + 1, minOf(index + 4, instructions.size)).any { it.opcode == Opcode.NEW_INSTANCE }
        }
        val created = (read + 1 until minOf(read + 4, instructions.size)).first { instructions[it].opcode == Opcode.NEW_INSTANCE }
        val body = listenerBody(instructions, created) { build.byType[it] }
        assertTrue("${field.name}: no body for the listener created at $created", !body.isNullOrEmpty())
        return body!!
    }

    private fun marksOf(listener: List<Method>): Set<String> {
        val references = listener.flatMap { it.implementation?.instructions ?: emptyList() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference }
        val strings = references.filterIsInstance<StringReference>().map { it.string }
        val panels = references.filterIsInstance<FieldReference>().filter { it.type == it.definingClass }.map { it.name }
        return buildSet {
            if ("click_comment_photo" in strings) add("photo")
            if ("click_comment_at" in strings || "MENTION" in panels) add("mention")
            if ("GIFT" in panels || references.any {
                    (it as? FieldReference)?.definingClass == GIFT_VIEW_MODEL || (it as? MethodReference)?.returnType == GIFT_VIEW_MODEL
                }) add("gift")
            if ("EMOJI" in panels) add("emoji")
        }
    }

    @Test
    fun `the emoji button's listener names no button the switch hides`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val bar = build.single(FakeInputViewCreatedFingerprint)
            val listeners = bar.implementation!!.instructions.toList().let { instructions ->
                instructions.indices.filter { index ->
                    instructions[index].opcode == Opcode.IGET_OBJECT &&
                        ((instructions[index] as ReferenceInstruction).reference as FieldReference).let {
                            it.definingClass == BASE_INPUT_ASSEM && it.type == TUX_ICON_VIEW
                        }
                }
            }
            assertEquals("the bar no longer wires four icon buttons", 4, listeners.size)
            val instructions = bar.implementation!!.instructions.toList()
            val listenerClasses = listeners.map { index ->
                val created = instructions.subList(index + 1, minOf(index + 4, instructions.size))
                    .first { it.opcode == Opcode.NEW_INSTANCE }
                build.byType.getValue(((created as ReferenceInstruction).reference as TypeReference).type)
            }
            val left = listenerClasses.filter { commentBoxButtonOf(it) == null }
            assertEquals("one icon button should stay: ${listenerClasses.map { it.type }}", 1, left.size)
            val opensEmoji = left.single().methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.SGET_OBJECT &&
                        ((instruction as ReferenceInstruction).reference as FieldReference).let {
                            it.name == "EMOJI" && it.type == it.definingClass
                        }
                } == true
            }
            assertTrue("the button that stays isn't the emoji one", opensEmoji)
        }
    }

    @Test
    fun `each button reaches the extension right after the input stores it`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val patch = File(root, "app/morphe/patches/tiktok/misc/commenttools/CommentToolsPatch.kt").readText()
        assertTrue("the buttons are not resolved", patch.contains(
            "commentBoxButtonFields(FakeInputViewCreatedFingerprint.method) { classDefByOrNull(it) }"))
        assertTrue("the input's setup is not hooked", patch.contains(
            "CommentInputViewCreatedFingerprint.method.resolveCommentBoxButtons(fields)"))
        assertTrue("the row over the keyboard is not hooked", patch.contains(
            "commentBoxButtonFields(setup, KEYBOARD_ICON_GROUP_ASSEM) { classDefByOrNull(it) }"))
        assertTrue("the plus panel's tiles are not filtered", patch.contains(
            "CommentPlusPanelRefreshFingerprint.method.resolvePlusPanelTiles()"))
        assertTrue("the row's plus check is not filtered", patch.contains(
            "KeyboardIconGroupPlusFingerprint.method.resolvePlusPanelTiles()"))
        val hook = File(root, "app/morphe/patches/tiktok/misc/commenttools/CommentBoxButtons.kt").readText()
        assertTrue("the hook does not go after the store", hook.contains("addInstruction(\n                index + 1,"))
    }

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        fun single(fingerprint: app.morphe.patcher.Fingerprint): Method {
            val taken = byType.values.asSequence().flatMap { classDef ->
                classDef.methods.asSequence().filter { fingerprint.takes(it, classDef) }
            }.toList()
            assertEquals(taken.map { "${it.definingClass}->${it.name}" }.toString(), 1, taken.size)
            return taken.single()
        }
    }
}
