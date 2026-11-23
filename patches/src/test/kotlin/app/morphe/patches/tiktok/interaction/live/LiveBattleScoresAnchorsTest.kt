/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show exact battle scores (#495) hands four of TikTok's own booleans to the extension: the
 * battle score cap's "capped" flag as its constructor stores it, the high score notice's read of
 * the server rule, and the two score writers' "show it whole" flags. Held to each declared build:
 * every fingerprint takes exactly one method, every hook site has the shape the hook writes
 * around with no branch landing on it, and the hooked bodies hand the right register over and
 * keep TikTok's own instructions.
 */
class LiveBattleScoresAnchorsTest {
    private fun ClassDef.taken(fingerprint: Fingerprint) = methods.filter { fingerprint.takes(it, this) }

    private fun only(classes: List<ClassDef>, fingerprint: Fingerprint, version: String): Method {
        val found = classes.flatMap { it.taken(fingerprint) }
        assertEquals("$version: methods $fingerprint takes: ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)
        return found.single()
    }

    @Test
    fun `every battle score hook site resolves on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val byType = classes.associateBy { it.type }

            val rule = only(classes, BattleHighScoreRuleFingerprint, version)
            val capType = battleScoreCapType(rule)
            val cap = byType.getValue(capType)
            // The cap is a plain value class: one boolean and one long, nothing else stored.
            assertEquals("$version: cap fields", listOf("J", "Z"), cap.fields.map { it.type }.sorted())
            val init = battleScoreCapInit(cap)
            val store = battleScoreCapStore(init)
            val initCode = init.implementation!!.instructions.toList()
            assertEquals(Opcode.IPUT_BOOLEAN, initCode[store.index].opcode)
            assertHooks(init, store, "battleScoreCap")

            val notice = only(classes, BattleHighScoreNoticeFingerprint, version)
            val read = highScoreRuleRead(notice)
            assertNotNull(read)
            val noticeCode = notice.implementation!!.instructions.toList()
            val field = (noticeCode[read!!.index - 1] as ReferenceInstruction).reference as FieldReference
            assertEquals(HIGH_SCORE_CONTROL_CFG, field.definingClass)
            assertEquals("normalControlApplied", field.name)
            assertTrue("$version: a branch lands after the notice's rule read", !notice.isBranchTarget(read.index))
            assertHooks(notice, read, "battleScoreCap")

            val bar = only(classes, BattleScoreTextFingerprint, version)
            assertTrue("$version: the bar writer is static", AccessFlags.STATIC.isSet(bar.accessFlags))
            val whole = battleScoreWholeParameter(bar)
            // Static (J, TextView, Z): the flag is the frame's last register.
            assertEquals(bar.implementation!!.registerCount - 1, whole.register)
            assertHooks(bar, whole, "battleScoreWhole")

            val coHost = only(classes, CoHostScoreTextFingerprint, version)
            val coHostWhole = coHostScoreWholeRead(coHost)
            assertEquals(1, coHostWhole.index)
            assertHooks(coHost, coHostWhole, "battleScoreWhole")

            // The two writers are the only places that write "1M+" for a score, so none is left.
            val writers = classes.flatMap { type ->
                type.methods.filter { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.CONST_STRING &&
                            (instruction as ReferenceInstruction).reference.toString() == "1M+"
                    } == true && method.parameterTypes.map(CharSequence::toString).firstOrNull() == "J"
                }
            }
            assertEquals("$version: score writers with 1M+: ${writers.map { "${it.definingClass}->${it.name}" }}",
                setOf(bar, coHost).map { "${it.definingClass}->${it.name}" }.toSet(),
                writers.map { "${it.definingClass}->${it.name}" }.toSet())
        }
    }

    /** The hooked body: the call on the flag's register, the result back in it, then TikTok's code. */
    private fun assertHooks(method: Method, flag: BattleFlag, call: String) {
        val stock = method.implementation!!.instructions.toList()
        val mutable = MutableMethod(method)
        mutable.hookBattleFlag(flag, call)
        val hooked = mutable.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hooked[flag.index].opcode)
        val reference = hooked[flag.index].getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/live/LiveControls;", reference.definingClass)
        assertEquals(call, reference.name)
        assertEquals(listOf("I"), reference.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", reference.returnType)
        val range = hooked[flag.index] as RegisterRangeInstruction
        assertEquals(flag.register, range.startRegister)
        assertEquals(1, range.registerCount)
        assertEquals(Opcode.MOVE_RESULT, hooked[flag.index + 1].opcode)
        assertEquals(flag.register, (hooked[flag.index + 1] as OneRegisterInstruction).registerA)
        assertEquals(stock.map { it.opcode }, (hooked.take(flag.index) + hooked.drop(flag.index + 2)).map { it.opcode })
    }

    @Test
    fun `a site of another shape stops the patch`() {
        val cap = "LX/Cap;"
        val flag = ImmutableFieldReference(cap, "a", "Z")
        // The flag store from a register that isn't the parameter.
        val stray = ImmutableMethod(
            cap, "<init>", listOf(ImmutableMethodParameter("Z", null, null), ImmutableMethodParameter("J", null, null)),
            "V", AccessFlags.CONSTRUCTOR.value or AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(5, listOf<Instruction>(
                ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 0, 1, flag),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), null, null),
        )
        assertThrows(PatchException::class.java) { battleScoreCapStore(stray) }

        // A writer whose flag read is a loop's landing.
        val looped = ImmutableMethod(
            cap, "w", listOf(ImmutableMethodParameter("J", null, null), ImmutableMethodParameter(BATTLE_VIEW, null, null)),
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(6, listOf<Instruction>(
                ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 2, flag),
                ImmutableInstruction10t(Opcode.GOTO, 0),
            ), null, null),
        )
        assertThrows(PatchException::class.java) { coHostScoreWholeRead(looped) }

        // A writer that doesn't read its own flag first.
        val other = ImmutableMethod(
            cap, "w", listOf(ImmutableMethodParameter("J", null, null), ImmutableMethodParameter(BATTLE_VIEW, null, null)),
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(6, listOf<Instruction>(
                ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 2, ImmutableFieldReference("LX/Other;", "a", "Z")),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), null, null),
        )
        assertThrows(PatchException::class.java) { coHostScoreWholeRead(other) }
        assertTrue((other.implementation!!.instructions.first() as TwoRegisterInstruction).registerA == 0)
    }

    private companion object {
        const val BATTLE_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"
    }
}
