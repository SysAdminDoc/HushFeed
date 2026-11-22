/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BACK_EXIT = "Lapp/morphe/extension/tiktok/navigation/BackExit;"

/**
 * What Exit with one Back hooks (#489), held to each declared build: TikTok's press-twice-to-quit
 * check in the main activity helper, which quits on a Back inside its gap and otherwise keeps the
 * time and shows "Tap again to exit" unless the activity is finishing.
 */
class BackAgainAnchorsTest {
    @Test
    fun `the check is one method on every declared build, quitting inside the gap and toasting past it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val build = Build(apk)
            val (helper, method) = checkOf(build, version)
            val back = method.backAgain()!!
            val code = method.implementation!!.instructions.toList()

            // The main activity's Back asks this check first, through the interface the helper's
            // base class implements, and ends there on a yes.
            val mainBack = build.methods.single { (classDef, candidate) -> BackPressFingerprint.takes(candidate, classDef) }.second
            val asked = mainBack.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
                .first { it.name == "onBackPressed" && it.returnType == "Z" && it.parameterTypes.isEmpty() }
            assertTrue("$version: ${helper.superclass} doesn't implement ${asked.definingClass}",
                asked.definingClass in build.byType.getValue(helper.superclass!!).interfaces)

            // Inside the gap: the helper quits the app and the check answers yes.
            val quitEnd = (back.firstBackAt + 1..code.lastIndex).first { code[it].opcode == Opcode.RETURN }
            val quit = code.subList(back.firstBackAt + 1, quitEnd).mapNotNull { it.getReference<MethodReference>() }
            assertTrue("$version: no quit on the helper in $quit", quit.any {
                it.definingClass == TIKTOK_BASE_MAIN_HELPER && it.returnType == "V" && it.parameterTypes.isEmpty()
            })
            val yes = code[quitEnd - 1]
            assertEquals("$version: the quit answers yes", Opcode.CONST_4, yes.opcode)
            assertEquals(1, (yes as NarrowLiteralInstruction).narrowLiteral)
            assertEquals((code[quitEnd] as OneRegisterInstruction).registerA, (yes as OneRegisterInstruction).registerA)

            // Past it: the time of this Back is kept in the field the gap was measured from,
            // before the isFinishing test.
            val first = targetOf(code, back.firstBackAt)
            assertTrue("$version: the first Back starts after the quit", first > quitEnd && first < back.toastAt)
            val since = code.first { it.opcode == Opcode.IGET_WIDE }.getReference<FieldReference>()!!
            assertTrue("$version: the first Back keeps no time", code.subList(first, back.toastAt).any {
                it.opcode == Opcode.IPUT_WIDE && it.getReference<FieldReference>()?.toString() == since.toString()
            })
            // A finishing activity returns past the toast; any other builds the toast's runnable.
            assertEquals("$version: the finishing test skips to a return", Opcode.RETURN, code[targetOf(code, back.toastAt)].opcode)
            val toast = code.subList(back.toastAt + 1, minOf(back.toastAt + 16, code.size))
                .firstOrNull { it.opcode == Opcode.NEW_INSTANCE }?.getReference<TypeReference>()?.type
            assertNotNull("$version: nothing built past the finishing test", toast)
            val toastStrings = build.byType.getValue(toast!!).methods.flatMap { it.implementation?.instructions?.strings() ?: emptyList() }
            assertTrue("$version: $toast is no Tap again toast: $toastStrings", toastStrings.any { "showBackPressedAgainToast" in it })
        }
    }

    @Test
    fun `the hooks answer both tests through BackExit and leave TikTok's code as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val clean = checkOf(Build(apk), version).second
            val back = clean.backAgain()!!
            val stock = clean.implementation!!.instructions.toList()
            val method = MutableMethod(clean)
            method.leaveOnFirstBack()
            val code = method.implementation!!.instructions.toList()

            fun asks(at: Int, name: String, register: Int, test: Opcode) {
                val call = code[at]
                assertEquals("$version: $name at $at", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                val target = call.getReference<MethodReference>()!!
                assertEquals(BACK_EXIT, target.definingClass)
                assertEquals(name, target.name)
                assertEquals("I", target.returnType)
                assertEquals(listOf("I"), target.parameterTypes.map { it.toString() })
                assertEquals(register, (call as RegisterRangeInstruction).startRegister)
                assertEquals(1, call.registerCount)
                assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
                assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
                assertEquals(test, code[at + 2].opcode)
                assertEquals(register, (code[at + 2] as OneRegisterInstruction).registerA)
            }
            asks(back.firstBackAt, "pastTheGap", back.compared, Opcode.IF_GTZ)
            asks(back.toastAt + 2, "skipAgainToast", back.finishing, Opcode.IF_NEZ)
            val targets = method.branchTargets()
            assertFalse("$version: a jump skips pastTheGap", back.firstBackAt + 2 in targets)
            assertFalse("$version: a jump skips skipAgainToast", back.toastAt + 4 in targets)

            val hooks = setOf(back.firstBackAt, back.firstBackAt + 1, back.toastAt + 2, back.toastAt + 3)
            assertEquals("$version: TikTok's code changed", codeOf(stock), codeOf(code.filterIndexed { i, _ -> i !in hooks }))
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }

    @Test
    fun `a synthetic check shaped like TikTok's gives both tests and their registers`() {
        val back = checkShape().backAgain()
        assertNotNull("the synthetic check was not read", back)
        assertEquals(8, back!!.firstBackAt)
        assertEquals(4, back.compared)
        assertEquals(16, back.toastAt)
        assertEquals(4, back.finishing)
    }

    @Test
    fun `a jump onto the gap test or a write to the compare before it is refused`() {
        assertNull("a jump lands on the if-gtz, so a Back would skip pastTheGap", checkShape(jumpOntoTest = true).backAgain())
        assertNull("the if-gtz tests something other than the compare", checkShape(overwriteCompare = true).backAgain())
    }

    /** TikTok's check cut down: the time since the last Back against a widened gap, then quit or toast. */
    private fun checkShape(jumpOntoTest: Boolean = false, overwriteCompare: Boolean = false): Method {
        val helper = TIKTOK_BASE_MAIN_HELPER
        return MutableMethod(
            ImmutableMethod(
                "LX/Helper;", "onBackPressed", emptyList(), "Z",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(7, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                    move-result-wide v0
                    iget-wide v2, p0, $helper->LIZJ:J
                    sub-long/2addr v0, v2
                    const/16 v4, 0x7d0
                    int-to-long v2, v4
                    cmp-long v4, v0, v2
                    ${if (overwriteCompare) "const/4 v4, 0x1" else "const-string v5, \"gapTime\""}
                    ${if (jumpOntoTest) "if-eqz v5, :test" else ""}
                    :test
                    if-gtz v4, :first
                    invoke-virtual {p0}, $helper->LIZJ()V
                    const/4 v4, 0x1
                    return v4
                    :first
                    iput-wide v0, p0, $helper->LIZJ:J
                    iget-object v5, p0, $helper->LIZ:Landroid/app/Activity;
                    invoke-virtual {v5}, Landroid/app/Activity;->isFinishing()Z
                    move-result v4
                    if-nez v4, :no
                    nop
                    :no
                    const/4 v4, 0x0
                    return v4
                """,
            )
        }
    }

    private fun checkOf(build: Build, version: String): Pair<ClassDef, Method> {
        val taken = build.methods.filter { (classDef, method) -> BackAgainFingerprint.takes(method, classDef) }.toList()
        assertEquals("$version: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
        return taken.single()
    }

    private fun targetOf(code: List<Instruction>, at: Int): Int {
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        return addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    /** Opcodes with payload alignment nops left out and a widened goto read as a goto. */
    private fun codeOf(instructions: List<Instruction>): List<Opcode> = instructions
        .filter { it.opcode != Opcode.NOP }
        .map {
            when (it.opcode) {
                Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
                else -> it.opcode
            }
        }

    private fun Iterable<Instruction>.strings(): List<String> =
        mapNotNull { it.getReference<StringReference>()?.string }

    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
