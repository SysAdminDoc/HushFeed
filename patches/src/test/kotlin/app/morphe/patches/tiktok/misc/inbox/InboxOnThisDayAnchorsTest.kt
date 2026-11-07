/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FILTER = "Lapp/morphe/extension/tiktok/inbox/InboxFilter;"

/**
 * What the Hide On this day switch hooks, held to each declared build (#358): the bind of the
 * memory cell at the front of the Inbox's stories tray. The cell goes to the extension before
 * TikTok binds it, and TikTok's own bind follows untouched.
 */
class InboxOnThisDayAnchorsTest {
    @Test
    fun `each declared build has the On this day cell and the fingerprint takes exactly its bind`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = cellOf(apk)
            assertTrue(
                "$version: the cell is no longer a PowerList cell (${classDef.superclass})",
                classDef.superclass == "Lcom/bytedance/ies/powerlist/PowerCell;",
            )
            val taken = classDef.methods.filter { StoryOnThisDayBindFingerprint.takes(it, classDef) }
            assertEquals("$version: binds matched ${taken.map { it.name }}", 1, taken.size)
            val bind = taken.single()
            assertEquals("onBindItemView", bind.name)
            assertFalse("$version: the bind is static", AccessFlags.STATIC.isSet(bind.accessFlags))
            assertEquals(1, bind.parameterTypes.size)
        }
    }

    @Test
    fun `the cell goes to the extension first and the bind keeps its body`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = cellOf(apk)
            val native = classDef.methods.single { StoryOnThisDayBindFingerprint.takes(it, classDef) }
            val before = native.implementation!!.instructions.filter { it.opcode != Opcode.NOP }

            val hooked = MutableMethod(native)
            hooked.reportOnThisDayBind("Hide inbox items")
            val after = hooked.implementation!!.instructions.filter { it.opcode != Opcode.NOP }

            val first = after.first()
            assertEquals("$version: the hook", Opcode.INVOKE_STATIC, first.opcode)
            val call = first.getReference<MethodReference>()!!
            assertEquals(FILTER, call.definingClass)
            assertEquals("onOnThisDayBound", call.name)
            assertEquals(listOf("Ljava/lang/Object;"), call.parameterTypes.map(CharSequence::toString))
            assertEquals("V", call.returnType)
            val body = native.implementation!!
            val thisRegister = body.registerCount - 2
            assertEquals("$version: the hook hands over the cell", 1, (first as FiveRegisterInstruction).registerCount)
            assertEquals(thisRegister, first.registerC)
            assertEquals(before.size + 1, after.size)
            assertEquals(before.map { it.opcode }, after.drop(1).map { it.opcode })
        }
    }

    /** The build's one On this day cell, found by walking the dex once. */
    private fun cellOf(apk: java.io.File): ClassDef {
        val found = mutableListOf<ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type == ON_THIS_DAY_CELL) found += classDef
            }
        }
        assertEquals("cells named $ON_THIS_DAY_CELL", 1, found.size)
        return found.single()
    }
}
