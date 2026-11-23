/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
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
 * #502: TikTok Lite 47.8.2's author row, which Always show publish date and Show author region
 * share. The row is found once, both of its checks that leave the date out (For You, and one of
 * Lite's own) go through the extension with the row's video, and the text goes through it too,
 * with the row's own Aweme and nothing else moved.
 */
class LitePublishDateTest {
    private val classes: Map<String, ClassDef> by lazy {
        val container = Fixtures.dexContainer(Fixtures.liteApk())
        container.dexEntryNames.flatMap { name -> checkNotNull(container.getEntry(name)).dexFile.classes }
            .associateBy { it.type }
    }

    private fun row(): MutableMethod {
        val found = classes.values.flatMap { owner ->
            owner.methods.filter { LiteAuthorRowBindFingerprint.takes(it, owner) }.map { owner to it }
        }
        assertEquals("author rows: ${found.map { "${it.first.type}->${it.second.name}" }}", 1, found.size)
        val (owner, method) = found.single()
        return MutableClass(owner).methods.single {
            it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }
    }

    private fun code(method: Method) = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }

    private fun called(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference as? MethodReference

    private fun registers(instruction: Instruction) = (instruction as FiveRegisterInstruction).let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    }

    private fun extension(instruction: Instruction, name: String) = called(instruction)?.let {
        it.definingClass == "Lapp/morphe/extension/tiktok/feed/LiteAuthorRow;" && it.name == name
    } == true

    @Test
    fun `Lite's author row shows its date wherever the switch says and through the extension`() {
        val method = row()
        val stock = code(method)
        val before = BridgeTypeFlow.check(method).map { it.message }
        val site = planLitePostTime(method)
        assertEquals("the For You check and Lite's own", 2, site.gates.size)
        val aweme = method.implementation!!.registerCount - 1
        assertEquals("the row's own Aweme, p1", aweme, site.awemeRegister)
        method.hookLitePostTime(site)

        val code = code(method)
        val asks = code.indices.filter { extension(code[it], "show") }
        assertEquals("one answer per check", 2, asks.size)
        for (ask in asks) {
            val (register, video) = registers(code[ask])
            assertEquals("with the row's video", aweme, video)
            assertEquals(Opcode.MOVE_RESULT, code[ask + 1].opcode)
            assertEquals(register, (code[ask + 1] as OneRegisterInstruction).registerA)
            assertEquals("right before the check it answers", Opcode.IF_EQZ, code[ask + 2].opcode)
            assertEquals(register, (code[ask + 2] as OneRegisterInstruction).registerA)
        }
        val posts = code.indices.filter { extension(code[it], "text") }
        assertEquals(1, posts.size)
        val post = posts.single()
        assertEquals("with the date and the row's Aweme", listOf(site.textRegister, aweme), registers(code[post]))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[post + 1].opcode)
        assertEquals(site.textRegister, (code[post + 1] as OneRegisterInstruction).registerA)
        assertEquals("just past the empty check", Opcode.IF_NEZ, code[post - 1].opcode)
        assertEquals("isEmpty", called(code[post - 3])?.name)
        assertTrue("which came after the formatter", asks.all { it < post })

        val added = (asks.flatMap { listOf(it, it + 1) } + listOf(post, post + 1)).toSet()
        assertEquals("nothing else moves", stock.map { it.opcode },
            code.filterIndexed { index, _ -> index !in added }.map { it.opcode })
        assertEquals("the hooks add no type problem", before, BridgeTypeFlow.check(method).map { it.message })
    }

    @Test
    fun `a method without the row date fails the patch by name`() {
        val method = row()
        val constructor = MutableClass(classes.getValue(method.definingClass)).methods.first { it.name == "<init>" }
        val thrown = assertThrows(PatchException::class.java) { planLitePostTime(constructor) }
        assertTrue(thrown.message, thrown.message!!.startsWith("TikTok Lite's author row: "))
    }

    /** What the rest of Always show publish date hooks on TikTok, none of which Lite has. */
    @Test
    fun `Lite has no author info view model or profile grid bind`() {
        val none = classes.values.flatMap { owner ->
            owner.methods.filter { VideoAuthorInfoStateFingerprint.takes(it, owner) }
        }
        assertEquals(emptyList<Method>(), none)
        assertEquals(null, classes["Lcom/ss/android/ugc/aweme/feed/assem/videoauthorinfo/VideoAuthorInfoVM;"])
    }
}
