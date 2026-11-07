/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PATCH = "Hide profile shortcuts"
private const val BALANCE = "content_section_cell_balance"
private const val MINIS_ICONS = "content_section_cell_tiktok_minis_rich_card"
private const val GUARD_SIZE = 6

/**
 * What Hide Balance in the profile menu (#194) hooks, held to each declared build: the one static
 * question the menu asks about each cell by id, guarded at entry with the id handed to the
 * extension, and the Balance and Minis icon card ids the extension compares against, present in
 * the build.
 */
class ProfileMenuCellsAnchorsTest {
    @Test
    fun `the menu asks one question per cell and the guard hands it the cell id`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = mutableListOf<Method>()
            val idsLoaded = mutableSetOf<String>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (ProfileMenuCellFingerprint.takes(method, classDef)) taken += method
                        method.implementation?.instructions?.forEach {
                            val loaded = it.getReference<StringReference>()?.string
                            if (loaded != null && (loaded == BALANCE || loaded == MINIS_ICONS)) idsLoaded += loaded
                        }
                    }
                }
            }
            assertEquals("$version: cell questions ${taken.map { it.definingClass }}", 1, taken.size)
            assertTrue("$version: no method loads the Balance cell id $BALANCE", BALANCE in idsLoaded)
            assertTrue("$version: no method loads the Minis icon card id $MINIS_ICONS (#230)", MINIS_ICONS in idsLoaded)

            val native = taken.single()
            val registers = native.implementation!!.registerCount
            // Static (cell data, id): two parameter registers with the id last, and v0 a local.
            assertTrue("$version: $registers registers leave no local", registers - 2 >= 1)
            val before = native.implementation!!.instructions.toList()
            val patched = MutableMethod(native)
            patched.hideProfileMenuCells(PATCH)
            val after = patched.implementation!!.instructions.toList()
            assertEquals(
                "$version: the guard",
                listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN, Opcode.NOP),
                after.take(GUARD_SIZE).map { it.opcode },
            )
            val call = after[0].getReference<MethodReference>()!!
            assertEquals("Lapp/morphe/extension/tiktok/profile/ProfileMenuCells;", call.definingClass)
            assertEquals("hide", call.name)
            assertEquals(listOf("Ljava/lang/String;"), call.parameterTypes.map { it.toString() })
            assertEquals("Z", call.returnType)
            val id = after[0] as RegisterRangeInstruction
            assertEquals("$version: the id is p1", registers - 1, id.startRegister)
            assertEquals(1, id.registerCount)
            assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
            assertEquals("$version: the branch tests the answer", 0, (after[2] as OneRegisterInstruction).registerA)
            assertEquals("$version: a hidden cell answers false", 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$version: and it is the one returned", 0, (after[4] as OneRegisterInstruction).registerA)
            assertEquals("$version: a no skips to TikTok's own code", GUARD_SIZE - 1, (after[2] as BuilderOffsetInstruction).target.location.index)
            assertEquals(before.map { it.opcode }, after.drop(GUARD_SIZE).map { it.opcode })
        }
    }

    @Test
    fun `only a static question that asks a cell provider is taken`() {
        assertTrue("the question", isProfileMenuCellQuestion(question(static = true, asks = PROFILE_SIDEBAR_CELL_PROVIDER)))
        assertFalse("not static", isProfileMenuCellQuestion(question(static = false, asks = PROFILE_SIDEBAR_CELL_PROVIDER)))
        assertFalse("asks something else", isProfileMenuCellQuestion(question(static = true, asks = "LX/0Other;")))
        assertFalse("asks a provider for something other than a yes or no",
            isProfileMenuCellQuestion(question(static = true, asks = PROFILE_SIDEBAR_CELL_PROVIDER, returns = "I")))
    }

    /** The cell question cut down to its provider call: `provider.LIZIZ()`, its answer returned. */
    private fun question(static: Boolean, asks: String, returns: String = "Z"): Method {
        val parameters = listOf("LX/0Gson;", "Ljava/lang/String;")
        return ImmutableMethod(
            "LX/0SMk;", "LIZ", parameters.map { ImmutableMethodParameter(it, null, null) }, "Z",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(
                1 + parameters.size + (if (static) 0 else 1),
                listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference(asks, "LIZIZ", listOf(), returns)),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                null,
                null,
            ),
        )
    }
}
