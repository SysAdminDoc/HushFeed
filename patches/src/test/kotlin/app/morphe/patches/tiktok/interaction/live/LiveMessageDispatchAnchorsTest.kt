/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE name alerts (#424): each declared build has exactly one LIVE message dispatch, an instance
 * method whose one parameter is the message. Nothing in it jumps back to its first instruction,
 * so a hook there sees every message once, and the hook goes in with p1, the message, through the
 * range form, since the dispatch keeps its parameters above v15.
 */
class LiveMessageDispatchAnchorsTest {
    private val hook = "$LIVE_MENTIONS_EXTENSION->onMessage(Ljava/lang/Object;)V"

    @Test
    fun `each declared build has one LIVE message dispatch the name alert reads at entry`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation != null && LiveMessageDispatchFingerprint.takes(method, classDef)) {
                            taken += classDef to method
                        }
                    }
                }
            }
            val found = taken.distinctBy { "${it.first.type}->${it.second.name}" }
            assertEquals("${apk.name}: ${found.map { "${it.first.type}->${it.second.name}" }}", 1, found.size)
            val (classDef, dispatch) = found.single()
            assertTrue("${apk.name}: instance method, p1 is the message", !AccessFlags.STATIC.isSet(dispatch.accessFlags))
            assertTrue(
                "${apk.name}: ${classDef.type} is no longer a message manager",
                classDef.interfaces.isNotEmpty(),
            )

            val code = dispatch.implementation!!.instructions.toList()
            var address = 0
            val targets = mutableSetOf<Int>()
            for (instruction in code) {
                if (instruction is OffsetInstruction && instruction.opcode != Opcode.PACKED_SWITCH &&
                    instruction.opcode != Opcode.SPARSE_SWITCH && instruction.opcode != Opcode.FILL_ARRAY_DATA
                ) {
                    targets += address + instruction.codeOffset
                }
                address += instruction.codeUnits
            }
            assertTrue("${apk.name}: something jumps back to the dispatch's first instruction", 0 !in targets)

            val method = MutableMethod(dispatch)
            method.addInstruction(0, "invoke-static/range {p1 .. p1}, $hook")
            val patched = method.implementation!!
            val first = patched.instructions.first()
            assertEquals(Opcode.INVOKE_STATIC_RANGE, first.opcode)
            assertEquals(hook, ((first as ReferenceInstruction).reference as MethodReference).toString())
            val call = first as RegisterRangeInstruction
            assertEquals(1, call.registerCount)
            assertEquals("${apk.name}: the hook reads p1", patched.registerCount - 1, call.startRegister)
            assertEquals(
                code.map { it.opcode }.filter { it != Opcode.NOP },
                patched.instructions.drop(1).map { it.opcode }.filter { it != Opcode.NOP },
            )
        }
    }
}
