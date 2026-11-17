/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comment bar half of AMOLED dark theme (#171), held to each declared build: the detail
 * page's bar keeps one ConstraintLayout, the row it found by id, collections reach the same setup
 * through super, and after the patch every return of that setup first hands the row and the
 * chosen color to the extension, with TikTok's own instructions in order.
 */
class AmoledInputBarTest {
    @Test
    fun `each declared build's comment bar row is handed to the extension before every return`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = mutableListOf<ClassDef>()
            for (entry in container.dexEntryNames) classes += container.getEntry(entry)!!.dexFile.classes

            val bar = classes.firstOrNull { it.type == DETAIL_BOTTOM_FAKE_INPUT }
            assertNotNull("$version: no $DETAIL_BOTTOM_FAKE_INPUT", bar)
            val setup = bar!!.methods.singleOrNull { it.detailBarRowField() != null }
            assertNotNull("$version: the bar's onViewCreated keeps no single ConstraintLayout", setup)
            val row = setup!!.detailBarRowField()!!

            // The row is a view the setup found by id, not one it built.
            val before = setup.implementation!!.instructions.toList()
            val store = before.indexOfFirst {
                it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>()?.name == row.name
            }
            assertEquals(Opcode.CHECK_CAST, before[store - 1].opcode)
            assertEquals("Landroidx/constraintlayout/widget/ConstraintLayout;",
                before[store - 1].getReference<TypeReference>()!!.type)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, before[store - 2].opcode)
            assertEquals("findViewById", before[store - 3].getReference<MethodReference>()!!.name)

            // A subclass that sets itself up goes through this setup first.
            classes.filter { it.superclass == DETAIL_BOTTOM_FAKE_INPUT }.forEach { subclass ->
                subclass.methods.filter { it.name == setup.name && it.parameterTypes == setup.parameterTypes }
                    .forEach { override ->
                        assertTrue("$version: ${subclass.type} sets itself up without the bar's own setup",
                            override.implementation!!.instructions.any {
                                it.opcode == Opcode.INVOKE_SUPER &&
                                    it.getReference<MethodReference>()?.definingClass == DETAIL_BOTTOM_FAKE_INPUT
                            })
                    }
            }

            val returns = before.count { it.opcode == Opcode.RETURN_VOID }
            assertTrue("$version: the setup never returns", returns > 0)
            val painted = MutableMethod(setup)
            painted.paintDetailBar(row, 0xff000000.toInt())
            val after = painted.implementation!!.instructions.toList()
            assertEquals("$version: four instructions per return", before.size + 4 * returns, after.size)
            val added = mutableSetOf<Int>()
            after.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.RETURN_VOID) return@forEachIndexed
                assertEquals(Opcode.MOVE_OBJECT_FROM16, after[index - 4].opcode)
                assertEquals(Opcode.IGET_OBJECT, after[index - 3].opcode)
                assertEquals(row.name, after[index - 3].getReference<FieldReference>()!!.name)
                assertEquals(-0x1000000, (after[index - 2] as NarrowLiteralInstruction).narrowLiteral)
                val call = after[index - 1].getReference<MethodReference>()!!
                assertEquals(AMOLED_INPUT_BAR_EXTENSION, call.definingClass)
                assertEquals("paint", call.name)
                added += (index - 4 until index)
            }
            assertEquals("$version: every return was hooked", 4 * returns, added.size)
            // A goto the added instructions push out of reach is widened, which is the same jump.
            fun Opcode.width() = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this
            assertEquals(
                "$version: TikTok's own instructions keep their order",
                before.map { it.opcode.width() },
                after.filterIndexed { index, _ -> index !in added }.map { it.opcode.width() },
            )
        }
    }

    @Test
    fun `the color is written as baksmali writes it`() {
        assertEquals("-0x1000000", smaliInt(0xff000000.toInt()))
        assertEquals("-0xe7e7db", smaliInt(0xff181825.toInt()))
        assertEquals("0x0", smaliInt(0))
    }
}
