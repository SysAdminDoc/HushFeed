/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A scan reads a mutable class's methods through [readOnly] so the patcher doesn't build and keep
 * an editable copy of every body it looks at (#497). The view has to read what the dex will hold
 * and stay the same method to every lookup and edit.
 */
class ReadOnlyMethodsTest {
    private val stock = ImmutableMethod(
        "Lapp/Host;", "run", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(
            2,
            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction10x(Opcode.RETURN_VOID)),
            null,
            null,
        ),
    )

    @Test
    fun `reading through the view builds no body`() {
        val method = MutableMethod(stock)
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN_VOID), method.readOnly().implementation!!.instructions.map { it.opcode })
        assertNotNull("this patcher doesn't say whether a body was built, so the view is off", bodyIsBuilt(method))
        assertEquals(false, bodyIsBuilt(method))
    }

    @Test
    fun `the view reads an edit made after it was taken`() {
        val method = MutableMethod(stock)
        val view = method.readOnly()
        assertEquals(2, view.implementation!!.instructions.count())
        method.addInstruction(0, "const/4 v1, 0x0")
        assertEquals(listOf(Opcode.CONST_4, Opcode.CONST_4, Opcode.RETURN_VOID), view.implementation!!.instructions.map { it.opcode })
    }

    @Test
    fun `the view is the same method to lookups and edits`() {
        val owner = MutableClass(
            ImmutableClassDef("Lapp/Host;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(stock)),
        )
        val method = owner.methods.single()
        val view = method.readOnly()
        assertEquals(method, view)
        assertEquals(method.hashCode(), view.hashCode())
        assertEquals(method.toString(), view.toString())
        assertSame(method, owner.findMutableMethodOf(view))
        assertEquals(false, bodyIsBuilt(method))
    }

    @Test
    fun `a method that isn't mutable is read as it is`() {
        assertSame(stock, stock.readOnly())
    }
}
