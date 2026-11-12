/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.patch.patch
import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The search icon over the shrunken video while comments are open (#371), held to each declared
 * build: one show-or-hide method that decides on p1, nothing branching onto its first instruction,
 * and the switch's filter at the top handing its answer back in p1 with TikTok's code left as it was.
 */
class ShrinkVideoSearchIconAnchorsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `the icon's show-or-hide method takes the switch's answer in p1 at its top`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = patch(name = "comments video search icon probe") {
                execute {
                    val matches = ShrinkVideoSearchIconVisibilityFingerprint.matchAll()
                    assertEquals("$version: show-or-hide methods", 1, matches.size)
                    val stock = matches.single().originalMethod
                    assertFalse("$version: static, so p1 would not be the answer", AccessFlags.STATIC.isSet(stock.accessFlags))
                    val answer = stock.implementation!!.registerCount - 1
                    val stockCode = stock.implementation!!.instructions.toList()
                    assertTrue("$version: nothing decides on p1",
                        stockCode.any { (it.opcode == Opcode.IF_EQZ || it.opcode == Opcode.IF_NEZ) && (it as OneRegisterInstruction).registerA == answer })
                    assertFalse("$version: something branches onto the first instruction", 0 in branchTargets(stockCode))

                    ShrinkVideoSearchIconVisibilityFingerprint.clearMatch()
                    assertTrue("$version: the hook went in", hideShrinkVideoSearchIcon(version))
                    val code = ShrinkVideoSearchIconVisibilityFingerprint.method.implementation!!.instructions
                        .filter { it.opcode != Opcode.NOP }
                    val call = code[0] as FiveRegisterInstruction
                    assertEquals("$version: the filter", "shrinkVideoSearchIconShown", call.getReference<MethodReference>()?.name)
                    assertEquals("$version: the answer goes in", listOf(1, answer), listOf(call.registerCount, call.registerC))
                    assertEquals("$version: and comes back", Opcode.MOVE_RESULT, code[1].opcode)
                    assertEquals("$version: in p1", answer, (code[1] as OneRegisterInstruction).registerA)
                    assertEquals("$version: TikTok's own code", stockCode.filter { it.opcode != Opcode.NOP }.map(::kind),
                        code.drop(2).map(::kind))
                    checked = true
                }
            }
            ShrinkVideoSearchIconVisibilityFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            ShrinkVideoSearchIconVisibilityFingerprint.clearMatch()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    private fun branchTargets(code: List<Instruction>): Set<Int> {
        var address = 0
        val targets = mutableSetOf<Int>()
        for (instruction in code) {
            if (instruction is OffsetInstruction) targets += address + instruction.codeOffset
            address += instruction.codeUnits
        }
        return targets
    }

    private fun kind(instruction: Instruction): Opcode = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }
}
