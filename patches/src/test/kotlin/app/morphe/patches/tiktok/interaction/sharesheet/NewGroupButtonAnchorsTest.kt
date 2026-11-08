/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private const val NEW_GROUP = "Lapp/morphe/extension/tiktok/share/ShareNewGroupButton;"

/**
 * The share sheet's Send to new group button (#375), held to each declared build: one (Z)V method
 * sets the button's end icon, it opens by reading the button and returning without one, and the
 * hook hands that read through the extension before the null check.
 */
class NewGroupButtonAnchorsTest {
    @Test
    fun `one method shows the button and the hook takes its read before the null check`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val toggles = toggles(apk)
            assertEquals("$version: toggles ${toggles.map { "${it.definingClass}->${it.name}" }}", 1, toggles.size)
            val toggle = toggles.single()
            val read = toggle.newGroupButtonRead()

            val hooked = MutableMethod(toggle)
            hooked.hookNewGroupButton()
            val code = hooked.implementation!!.instructions.toList()
            val call = code[read.index + 1]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            val reference = call.getReference<MethodReference>()!!
            assertEquals(NEW_GROUP, reference.definingClass)
            assertEquals("filter", reference.name)
            assertEquals(read.register, (call as RegisterRangeInstruction).startRegister)
            assertEquals(1, call.registerCount)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[read.index + 2].opcode)
            assertEquals(read.register, (code[read.index + 2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.CHECK_CAST, code[read.index + 3].opcode)
            assertEquals(read.type, code[read.index + 3].getReference<TypeReference>()!!.type)
            assertEquals(Opcode.IF_NEZ, code[read.index + 4].opcode)
            assertEquals(Opcode.RETURN_VOID, code[read.index + 5].opcode)

            val stock = toggle.implementation!!.instructions.map { it.opcode }.filter { it != Opcode.NOP }
            val kept = code.filterIndexed { at, _ -> at !in read.index + 1..read.index + 3 }
                .map { it.opcode }.filter { it != Opcode.NOP }
            assertEquals("$version: TikTok's own code moved", stock, kept)
        }
    }

    @Test
    fun `a toggle that stopped checking the button stops the patch untouched`() {
        val moved = MutableMethod(toggleOf(checked = false))
        val before = moved.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { moved.hookNewGroupButton() }
        assertEquals(before, moved.implementation!!.instructions.toList())
    }

    @Test
    fun `the shape the patch expects passes on its own`() {
        val method = MutableMethod(toggleOf(checked = true))
        method.hookNewGroupButton()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, method.implementation!!.instructions.toList()[1].opcode)
    }

    /** iget-object v1, p0 button; if-nez v1 (or a plain nop); return-void; then the end icon call. */
    private fun toggleOf(checked: Boolean): Method {
        val button = "LX/button;"
        val read = ImmutableInstruction22c(
            Opcode.IGET_OBJECT, 1, 2, ImmutableFieldReference("LX/bar;", "button", button),
        )
        // The read is two code units, if-nez two and return-void one, so +3 lands on the icon call.
        val check = if (checked) ImmutableInstruction21t(Opcode.IF_NEZ, 1, 3)
        else ImmutableInstruction10x(Opcode.NOP)
        val icon = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0,
            ImmutableMethodReference(button, "setButtonEndIconDrawable", listOf("Landroid/graphics/drawable/Drawable;"), "V"),
        )
        val code = listOf(read, check, ImmutableInstruction10x(Opcode.RETURN_VOID), icon, ImmutableInstruction10x(Opcode.RETURN_VOID))
        return ImmutableMethod(
            "LX/bar;", "toggle", listOf(ImmutableMethodParameter("Z", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(4, code, null, null),
        )
    }

    private fun toggles(apk: File): List<Method> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.flatMap { classDef ->
                classDef.methods.filter(::isNewGroupButtonToggle)
            }
        }
    }
}
