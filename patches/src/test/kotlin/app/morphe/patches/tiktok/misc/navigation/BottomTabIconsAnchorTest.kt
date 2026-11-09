/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom bar's icon set (#285) is one experiment TikTok reads through one lazy getter. Held
 * to every declared build: exactly one method answers it, and a pick returns before TikTok's own
 * read while no pick falls through to it untouched.
 */
class BottomTabIconsAnchorTest {
    private fun taken(apk: java.io.File): List<Method> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val found = ArrayList<Method>()
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) {
                    if (BottomTabIconVersionFingerprint.takes(method, classDef)) found += method
                }
            }
        }
        return found
    }

    @Test
    fun `each declared build has one getter for the icon experiment and a pick returns before it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val getter = taken(apk).singleOrNull()
                ?: throw AssertionError("$version: wanted one icon experiment getter, found ${taken(apk).size}")
            val stockFirst = getter.implementation!!.instructions.first()

            val code = MutableMethod(getter).apply { offerBottomTabIcons() }.implementation!!.instructions.toList()

            val ask = code[0].getReference<MethodReference>()!!
            assertEquals("$version: first call", "Lapp/morphe/extension/tiktok/navigation/BottomTabIcons;", ask.definingClass)
            assertEquals("version", ask.name)
            assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
            assertEquals(Opcode.IF_LTZ, code[2].opcode)
            assertEquals("valueOf", code[3].getReference<MethodReference>()!!.name)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[4].opcode)
            assertEquals(Opcode.RETURN_OBJECT, code[5].opcode)

            val branchTo = code.take(2).sumOf { it.codeUnits } + code[2].codeUnits +
                code.subList(3, 6).sumOf { it.codeUnits }
            val from = code.take(2).sumOf { it.codeUnits }
            assertEquals(
                "$version: no pick goes on to TikTok's read",
                branchTo - from,
                (code[2] as OffsetInstruction).codeOffset,
            )
            assertEquals(stockFirst.opcode, code[6].opcode)
            assertTrue("$version: the read is still there", code.size > 6)
        }
    }

    @Test
    fun `a merged lambda group holding the read is not taken`() {
        // What R8 leaves when it folds the read into a group: one invoke() for every lambda in
        // it, opening on the lambda's number. A pick answered at its top would go to all of them.
        val group = "LX/0Group;"
        fun invoke(code: String) = MutableMethod(ImmutableMethod(group, "invoke", emptyList(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(2, emptyList(), null, null)))
            .apply { addInstructionsWithLabels(0, code) }
        val own = invoke(
            """
                const-string v0, "new_bottom_tab_icon2"
                return-object v0
            """,
        )
        val merged = invoke(
            """
                iget v0, v1, $group->${'$'}t:I
                packed-switch v0, :numbers
                const/4 v0, 0x0
                return-object v0
                :icons
                const-string v0, "new_bottom_tab_icon2"
                return-object v0
                :numbers
                .packed-switch 0x3
                    :icons
                .end packed-switch
            """,
        )
        fun holder(method: Method) = ImmutableClassDef(group, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(method))

        assertTrue("a lambda of its own is the getter", BottomTabIconVersionFingerprint.takes(own, holder(own)))
        assertFalse("a group's entry point is not", BottomTabIconVersionFingerprint.takes(merged, holder(merged)))
    }
}
