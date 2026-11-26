/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #543: TikTok Lite 47.8.2's saved dark mode. The getter is found once, it is the one that reads
 * "dark_mode" from the dark mode repo with light as the default, and its answer goes through the
 * extension right before it returns, with nothing else moved.
 */
class LiteDarkModeTest {
    private val classes: Map<String, ClassDef> by lazy {
        val container = Fixtures.dexContainer(Fixtures.liteApk())
        container.dexEntryNames.flatMap { name -> checkNotNull(container.getEntry(name)).dexFile.classes }
            .associateBy { it.type }
    }

    private fun getter(): MutableMethod {
        val found = classes.values.flatMap { owner ->
            owner.methods.filter { LiteDarkModeFingerprint.takes(it, owner) }.map { owner to it }
        }
        assertEquals("dark mode getters: ${found.map { "${it.first.type}->${it.second.name}" }}", 1, found.size)
        val (owner, method) = found.single()
        return MutableClass(owner).methods.single { it.name == method.name && it.parameterTypes.isEmpty() }
    }

    private fun code(method: Method) = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }

    private fun strings(method: Method) = code(method).mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
    }

    @Test
    fun `Lite's saved dark mode is the Keva read with light as its default`() {
        val method = getter()
        assertEquals(listOf("dark_mode"), strings(method))
        val code = code(method)
        val read = code.indexOfFirst {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            call?.definingClass == "Lcom/bytedance/keva/Keva;" && call.name == "getInt"
        }
        assertTrue("it reads the saved int", read > 0)
        assertEquals("light (1) when nothing is saved", Opcode.CONST_4, code[read - 1].opcode)
        // The repo the getter reads is opened lazily, by a lambda its class makes as it loads.
        val owner = classes.getValue(method.definingClass)
        val made = owner.methods.filter { it.name == "<clinit>" }.flatMap(::code)
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }
        assertTrue("the class opens the dark mode repo: $made",
            made.mapNotNull(classes::get).any { lambda -> lambda.methods.any { "repo_dark_mode" in strings(it) } })
    }

    @Test
    fun `its answer goes through the extension right before it returns`() {
        val method = getter()
        val stock = code(method)
        val before = BridgeTypeFlow.check(method).map { it.message }
        assertEquals(1, method.hookLiteDarkMode())

        val code = code(method)
        val hooks = code.indices.filter {
            ((code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" == LITE_DARK_MODE_HOOK
            } == true
        }
        assertEquals(1, hooks.size)
        val hook = hooks.single()
        val returned = (code.last() as OneRegisterInstruction).registerA
        assertEquals(Opcode.RETURN, code.last().opcode)
        val call = code[hook] as RegisterRangeInstruction
        assertEquals("with the value it would have returned", returned to 1, call.startRegister to call.registerCount)
        assertEquals(Opcode.MOVE_RESULT, code[hook + 1].opcode)
        assertEquals("the answer is what it returns", returned, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("right before the return", code.size - 1, hook + 2)
        assertEquals("nothing else moves", stock.map { it.opcode },
            code.filterIndexed { index, _ -> index != hook && index != hook + 1 }.map { it.opcode })
        assertEquals("the hook adds no type problem", before, BridgeTypeFlow.check(method).map { it.message })
    }

    @Test
    fun `the switch is declared for TikTok Lite alone`() {
        val declared = liteDarkModePatch.compatibility.orEmpty().map { it.packageName }
        assertEquals(listOf(AppCompatibilities.TIKTOK_LITE_PACKAGE), declared)
    }
}
