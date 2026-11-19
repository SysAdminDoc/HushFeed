package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LIVE page handler the feed filter hooks (issue #57), held to the fixtures with the patch's
 * own fingerprint, and the request type the hook reads off its provider (#212).
 *
 * <p>The handler fills every LIVE list TikTok shows, the Following tab's row and the Inbox's LIVE
 * line among them, and the provider names each request. The hook calls the provider's getter on
 * p0 and hands the answer to the filter with the page, through v1 and v2, so the handler has to
 * have three locals and a getter of its own class that the handler compares the request type
 * through. The request types TikTok uses sit in one class initializer, which says whether the
 * names the filter leaves to the block list are still real.
 */
class LiveDrawRoomPageTest {
    private val loaded = "following_window_live_cell_loadmore"
    private val feed = "toplive_user_host_room_refresh"

    @Test
    fun `each declared build has one page handler with the provider's request-type getter in reach`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val (provider, handler) = build.single(LiveDrawRoomPageFingerprint)
            val body = handler.implementation!!
            val parameterRegisters = handler.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
            val locals = body.registerCount - parameterRegisters
            assertTrue("${apk.name}: the hook writes v1 and v2, the handler has $locals local(s)", locals >= 3)
            assertEquals("${apk.name}: the list is the second parameter", "Ljava/util/List;", handler.parameterTypes[1].toString())

            // Through 47.7.3 the handler compared the request type itself. 47.8.3 moved that into
            // a method of the provider (LX/0Juo;->LJLJJL(I)V) the handler calls on p0, so the
            // comparison is looked for there, and the call has to be on the handler's own p0.
            val check = requestTypeCheck(apk.name, provider, handler, locals)
            val instructions = check.implementation!!.instructions.toList()
            val load = instructions.indexOfFirst { it.loads(loaded) }
            assertTrue("${apk.name}: ${check.name} never loads $loaded", load >= 0)
            val call = (load until instructions.size).firstOrNull { at ->
                val instruction = instructions[at]
                (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                    (instruction as ReferenceInstruction).reference.let { it is MethodReference && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
            }
            assertTrue("${apk.name}: no String getter is called after the load", call != null)
            val getter = (instructions[call!!] as ReferenceInstruction).reference as MethodReference
            assertEquals("${apk.name}: the getter is called on another class than the provider", provider.type, getter.definingClass)
            val declared = provider.methods.filter { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            assertEquals("${apk.name}: the provider declares ${getter.name}", 1, declared.size)
            assertFalse("${apk.name}: ${getter.name} is static, so invoke-virtual on p0 is wrong", AccessFlags.STATIC.isSet(declared.single().accessFlags))
            assertTrue("${apk.name}: ${getter.name} has no body", declared.single().implementation != null)

            // The getter answers the request type: what it returns is compared with the string
            // just loaded, so the register the string went to is read by the comparison that
            // follows the call.
            val stringRegister = (instructions[load] as OneRegisterInstruction).registerA
            val compare = (call + 1 until minOf(call + 6, instructions.size)).firstOrNull { at ->
                val instruction = instructions[at]
                instruction.opcode == Opcode.INVOKE_STATIC && (instruction as ReferenceInstruction).reference.let {
                    it is MethodReference && it.name == "equals" && it.definingClass == "Landroid/text/TextUtils;"
                }
            }
            assertTrue("${apk.name}: the getter's answer is not compared with $loaded", compare != null)
            assertTrue(
                "${apk.name}: the comparison after the getter does not read v$stringRegister",
                (instructions[compare!!] as FiveRegisterInstruction).let {
                    stringRegister in listOf(it.registerC, it.registerD)
                },
            )
        }
    }

    /**
     * The request types the extension leaves to the block list are real names on every declared
     * build, and the feed's own name is not among them. The list is read from the extension's
     * source, so a renamed row fails here rather than in a user's Inbox.
     */
    @Test
    fun `the rows the filter leaves to the block list are request types TikTok still uses`() {
        val root = File("../extensions").takeIf { it.isDirectory } ?: File("extensions")
        val source = File(root, "tiktok/src/main/java/app/morphe/extension/tiktok/feedfilter/LiveFeedFilter.java").readText()
        val rows = Regex("""FOLLOW_ROWS = \{([^}]*)}""").find(source)!!.groupValues[1]
            .let { Regex("\"([^\"]+)\"").findAll(it).map { match -> match.groupValues[1] }.toList() }
        assertTrue("the extension names no rows", rows.isNotEmpty())

        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val requestTypes = build.methods
                .filter { (_, method) -> method.name == "<clinit>" && method.loadsAll(loaded, "tiktok_message_inner_loadmore") }
                .map { (_, method) -> method.strings() }
                .toList()
            assertEquals("${apk.name}: class initializers listing the request types", 1, requestTypes.size)
            val names = requestTypes.single()
            for (row in rows) {
                assertTrue("${apk.name}: no request type starts with $row: $names", names.any { it.startsWith(row) })
            }
            assertTrue("${apk.name}: the feed's own request type is gone: $names", feed in names)
            assertTrue("${apk.name}: a row prefix takes the feed's request type", rows.none { feed.startsWith(it) })
        }
    }

    /**
     * The method that compares the provider's request type with [loaded]: the [handler] when it
     * loads the string, else the one method of the [provider] the handler invokes virtually on its
     * p0 (or a plain copy of it) that does.
     */
    private fun requestTypeCheck(apk: String, provider: ClassDef, handler: Method, locals: Int): Method {
        val instructions = handler.implementation!!.instructions.toList()
        if (instructions.any { it.loads(loaded) }) return handler
        val p0 = locals
        val calls = instructions.withIndex().mapNotNull { (at, instruction) ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
            val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
            if (reference.definingClass != provider.type) return@mapNotNull null
            val target = provider.methods.singleOrNull {
                it.name == reference.name && it.returnType == reference.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
            } ?: return@mapNotNull null
            if (!target.loadsAll(loaded, "tiktok_message_inner_loadmore")) return@mapNotNull null
            Triple(at, instruction, target)
        }
        assertEquals("${apk}: provider methods the handler calls that compare the request type", 1, calls.size)
        val (at, call, target) = calls.single()
        assertFalse("${apk}: ${target.name} is static", AccessFlags.STATIC.isSet(target.accessFlags))
        val receiver = when (call) {
            is FiveRegisterInstruction -> call.registerC
            is RegisterRangeInstruction -> call.startRegister
            else -> error("${apk}: ${call.opcode} names no receiver")
        }
        if (receiver != p0) {
            val write = (at - 1 downTo 0).first { index ->
                instructions[index].opcode.setsRegister() && (instructions[index] as? OneRegisterInstruction)?.registerA == receiver
            }
            val copy = instructions[write]
            assertTrue(
                "${apk}: ${target.name} is called on v$receiver, last written by ${copy.opcode}, not a copy of p0 (v$p0)",
                copy.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
                    (copy as TwoRegisterInstruction).registerB == p0,
            )
        }
        return target
    }

    private fun Method.loadsAll(vararg wanted: String): Boolean = strings().containsAll(wanted.toList())

    private fun Method.strings(): List<String> = implementation?.instructions?.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
    } ?: emptyList()

    private fun Instruction.loads(string: String): Boolean =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            ((this as ReferenceInstruction).reference as StringReference).string == string

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

        fun single(fingerprint: Fingerprint): Pair<ClassDef, Method> {
            val taken = methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }.toList()
            assertEquals("${fingerprint.javaClass.simpleName} takes: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            return taken.single()
        }
    }
}
