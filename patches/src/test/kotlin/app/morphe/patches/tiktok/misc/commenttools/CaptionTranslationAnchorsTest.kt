package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the comments' caption row follows TikTok's translation (#488), held to every fixture.
 *
 * <p>The row's bind takes the video first and draws its caption from it. The patch swaps the video
 * at the bind's first instruction, so nothing may jump there past the swap. The feed caption's two
 * translation checks are read for TikTok's own references: its translation service and target
 * language, and the two description repos with their cached values. The extension reaches every
 * one of them from its own package, so each must be public, and the rich value's protocol numbers
 * must still be TikTok's.
 */
class CaptionTranslationAnchorsTest {
    @Test
    fun `the caption row's bind is one method on every fixture, the video first and nothing jumping to its start`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> CaptionRowViewBindFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val method = taken.single().second
            assertFalse("${apk.name}: the bind is static, so p1 isn't the video", AccessFlags.STATIC.isSet(method.accessFlags))
            assertEquals("${apk.name}: the video isn't first", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
                method.parameterTypes.first().toString())
            assertFalse("${apk.name}: something jumps to the first instruction, past the swap",
                0 in method.implementation!!.targetIndexes())
        }
    }

    @Test
    fun `the feed caption's two translation checks resolve on every fixture to public members`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val rich = build.methods.filter { (classDef, method) -> CaptionRichTranslationFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: rich ${rich.map { "${it.first.type}->${it.second.name}" }}", 1, rich.size)
            val plain = build.methods.filter { (classDef, method) -> CaptionPlainTranslationFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: plain ${plain.map { "${it.first.type}->${it.second.name}" }}", 1, plain.size)
            val native = captionTranslationNative(rich.single().second, plain.single().second) { build.byType[it] }

            for (reads in listOf(native.rich, native.plain)) {
                for (field in listOfNotNull(reads.service, reads.repo)) {
                    build.assertPublic(apk, field, static = true)
                }
                for (field in listOfNotNull(reads.text, reads.wrapper)) build.assertPublic(apk, field, static = false)
                for (call in listOf(reads.decision, reads.language, reads.cacheGet)) build.assertPublic(apk, call)
                build.assertPublicClass(apk, reads.value)
            }
            assertEquals("${apk.name}: the plain repo's value carries a ClientTextWrapper", null, native.plain.wrapper)
            assertEquals("${apk.name}: the rich value's wrapper", CLIENT_TEXT_WRAPPER, native.rich.wrapper!!.type)
        }
    }

    @Test
    fun `TikTok's translation protocols are still plain text at 0 and ClientText at 2`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val wrapper = build.byType[CLIENT_TEXT_WRAPPER]
            assertNotNull("${apk.name}: no ClientTextWrapper", wrapper)
            val getter = wrapper!!.methods.single { it.name == "getTranslationProtocol" }
            val protocols = build.byType[getter.returnType]
            assertNotNull("${apk.name}: no ${getter.returnType}", protocols)
            val statuses = protocols!!.methods.single { it.name == "<clinit>" }.enumStatuses(protocols.type)
            assertEquals("${apk.name}: $statuses", 0, statuses["TRANSLATION_PROTOCOL_PLAIN_TEXT"])
            assertEquals("${apk.name}: $statuses", 2, statuses["TRANSLATION_PROTOCOL_V2"])
        }
    }

    @Test
    fun `Comment tools swaps the row's video and writes the bridges`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/commenttools/CommentToolsPatch.kt").readText()
        assertTrue("the row's video is not swapped", source.contains("CaptionRowViewBindFingerprint.method.resolveCaptionRowTranslation()"))
        assertTrue("the bridges are not written", source.contains("installCaptionTranslationBridges(native)"))
    }

    /** Each enum constant's name and the status its constructor is handed last. */
    private fun Method.enumStatuses(type: String): Map<String, Int> {
        val strings = HashMap<Int, String>()
        val ints = HashMap<Int, Int>()
        val found = LinkedHashMap<String, Int>()
        for (instruction in implementation!!.instructions) {
            when {
                instruction.opcode == Opcode.CONST_STRING ->
                    strings[(instruction as OneRegisterInstruction).registerA] =
                        ((instruction as ReferenceInstruction).reference as StringReference).string
                instruction is NarrowLiteralInstruction && instruction is OneRegisterInstruction ->
                    ints[instruction.registerA] = instruction.narrowLiteral
                instruction.opcode == Opcode.INVOKE_DIRECT -> {
                    val call = (instruction as ReferenceInstruction).reference as MethodReference
                    if (call.definingClass != type || call.name != "<init>") continue
                    val registers = instruction as FiveRegisterInstruction
                    if (registers.registerCount != 4) continue
                    val name = strings[registers.registerD] ?: continue
                    val status = ints[registers.registerF] ?: continue
                    found[name] = status
                }
            }
        }
        return found
    }

    /** Every instruction index a branch, a switch case or a catch handler lands on. */
    private fun MethodImplementation.targetIndexes(): Set<Int> {
        val instructions = instructions.toList()
        val addresses = IntArray(instructions.size + 1)
        for (index in instructions.indices) addresses[index + 1] = addresses[index] + instructions[index].codeUnits
        fun indexAt(address: Int) = addresses.indexOfFirst { it == address }.takeIf { it in instructions.indices }
        val targets = mutableSetOf<Int>()
        instructions.forEachIndexed { index, instruction ->
            if (instruction !is OffsetInstruction) return@forEachIndexed
            val target = addresses[index] + instruction.codeOffset
            if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                val payload = indexAt(target)?.let { instructions[it] } as? SwitchPayload ?: return@forEachIndexed
                payload.switchElements.forEach { element -> indexAt(addresses[index] + element.offset)?.let(targets::add) }
            } else {
                indexAt(target)?.let(targets::add)
            }
        }
        tryBlocks.forEach { block -> block.exceptionHandlers.forEach { handler -> indexAt(handler.handlerCodeAddress)?.let(targets::add) } }
        return targets
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

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }

        fun assertPublicClass(apk: File, type: String) {
            val classDef = byType[type]
            assertNotNull("${apk.name}: no $type", classDef)
            assertTrue("${apk.name}: $type isn't public", AccessFlags.PUBLIC.isSet(classDef!!.accessFlags))
        }

        fun assertPublic(apk: File, field: FieldReference, static: Boolean) {
            assertPublicClass(apk, field.definingClass)
            val declared = byType.getValue(field.definingClass).fields.singleOrNull { it.name == field.name && it.type == field.type }
            assertNotNull("${apk.name}: $field isn't declared where it's read", declared)
            assertTrue("${apk.name}: $field isn't public", AccessFlags.PUBLIC.isSet(declared!!.accessFlags))
            assertEquals("${apk.name}: $field static", static, AccessFlags.STATIC.isSet(declared.accessFlags))
        }

        fun assertPublic(apk: File, call: MethodReference) {
            assertPublicClass(apk, call.definingClass)
            val declared = byType.getValue(call.definingClass).methods.singleOrNull {
                it.name == call.name && it.returnType == call.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
            }
            assertNotNull("${apk.name}: $call isn't declared where it's called", declared)
            assertTrue("${apk.name}: $call isn't public", AccessFlags.PUBLIC.isSet(declared!!.accessFlags))
            assertFalse("${apk.name}: $call is static", AccessFlags.STATIC.isSet(declared.accessFlags))
        }
    }
}
