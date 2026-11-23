/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.authorregion

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #502: Show author region on TikTok Lite 47.8.2. Lite's feed JSON carries each video's region,
 * which Lite's Aweme has no field for, so Gson skips it. Gson's obfuscated JsonReader methods are
 * told apart by what they do, both of Lite's adapters hand the model they make to the extension,
 * and skipValue asks the extension before skipping.
 */
class LiteVideoRegionTest {
    private val classes: Map<String, ClassDef> by lazy {
        val container = Fixtures.dexContainer(Fixtures.liteApk())
        container.dexEntryNames.flatMap { name -> checkNotNull(container.getEntry(name)).dexFile.classes }
            .associateBy { it.type }
    }

    private val reader by lazy { liteGsonReader { classes[it] } }

    private fun code(method: Method) = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }

    private fun called(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference as? MethodReference

    private fun registers(instruction: Instruction) = (instruction as FiveRegisterInstruction).let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    }

    private fun method(owner: String, match: (Method) -> Boolean): MutableMethod =
        MutableClass(classes.getValue(owner)).methods.single(match)

    private fun read(adapter: String) = method(adapter) {
        it.name == "read" && it.parameterTypes.map(CharSequence::toString) == listOf(reader.type)
    }

    @Test
    fun `Gson's reader methods are told apart by what they do`() {
        assertEquals("LX/1Rk;", reader.type)
        assertEquals("getPath", "LIIIIZZ", reader.path.name)
        assertEquals("peek", "LILII", reader.peek.name)
        assertEquals("nextString", "LIJJLLII", reader.nextString.name)
        assertEquals("skipValue", "LILLII", reader.skipValue.name)
    }

    @Test
    fun `both of Lite's Gson adapters hand the model they make and the reader to the extension`() {
        for (adapter in listOf(GSON_REFLECTIVE_ADAPTER, LITE_GSONOPT_ADAPTER)) {
            val read = read(adapter)
            val stock = code(read)
            val before = BridgeTypeFlow.check(read).map { it.message }
            read.hookLiteMade(liteMadeSite(read))

            val code = code(read)
            val hooks = code.indices.filter { called(code[it])?.name == "made" }
            assertEquals(adapter, 1, hooks.size)
            val hook = hooks.single()
            val kept = code[hook - 1]
            assertEquals("right after the model is kept", Opcode.MOVE_RESULT_OBJECT, kept.opcode)
            assertEquals(
                "the model and read's reader",
                listOf((kept as OneRegisterInstruction).registerA, read.implementation!!.registerCount - 1),
                registers(code[hook]),
            )
            val enters = code.indices.firstOrNull { index ->
                called(code[index])?.let { it.definingClass == reader.type && it.parameterTypes.isEmpty() && it.returnType == "V" } == true &&
                    index > hook
            }
            assertTrue("before the reader enters the object", enters != null)
            assertEquals("nothing else moves", stock.map { it.opcode }, code.filterIndexed { index, _ -> index != hook }.map { it.opcode })
            assertEquals("the hook adds no type problem", before, BridgeTypeFlow.check(read).map { it.message })
        }
    }

    @Test
    fun `skipValue asks the extension first and returns when it read the value`() {
        val skip = method(reader.type) {
            it.name == reader.skipValue.name && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        val stock = code(skip)
        skip.hookLiteSkip()

        val code = code(skip)
        assertEquals("skipping", called(code[0])?.name)
        assertEquals("with the reader", listOf(skip.implementation!!.registerCount - 1), registers(code[0]))
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(Opcode.RETURN_VOID, code[3].opcode)
        assertEquals("Gson's own skip follows untouched", stock.map { it.opcode }, code.drop(4).map { it.opcode })
    }

    @Test
    fun `a read that makes no model fails the patch by name`() {
        // Excluder's adapter only hands the reader on to the real one.
        val delegate = read("Lcom/google/gson/internal/Excluder\$1;")
        val thrown = assertThrows(PatchException::class.java) { liteMadeSite(delegate) }
        assertTrue(thrown.message, thrown.message!!.startsWith("Show author region: "))
    }
}
