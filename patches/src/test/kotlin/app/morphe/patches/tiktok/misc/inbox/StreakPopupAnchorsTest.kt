/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide streak pop-ups takes the in-app streak alert and the chat's streak message on every declared host. */
class StreakPopupAnchorsTest {
    @Test
    fun `each declared host has one in-app streak alert and one inline streak message, each with a spare local`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()
            val alerts = classes.flatMap { classDef ->
                classDef.methods.filter { StreakInAppPushFingerprint.takes(it, classDef) }.map { classDef to it }
            }
            assertEquals("$version in-app streak alert", 1, alerts.size)
            val (_, alert) = alerts.single()
            assertTrue("$version: the alert has a local",
                alert.implementation!!.registerCount > alert.numberOfParameterRegisters)

            val inline = classes.flatMap { classDef ->
                classDef.methods.filter { StreakInlineMessageFingerprint.takes(it, classDef) }.map { classDef to it }
            }
            assertEquals("$version inline streak message", 1, inline.size)
            val (_, message) = inline.single()
            assertTrue("$version: the message has a local",
                message.implementation!!.registerCount > message.numberOfParameterRegisters)
        }
    }

    @Test
    fun `each declared host has one DM banner update, and a null notice dismisses it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()
            val updates = classes.flatMap { classDef ->
                classDef.methods.filter { DmCommonBannerUpdateFingerprint.takes(it, classDef) }.map { classDef to it }
            }
            assertEquals("$version DM banner update", 1, updates.size)
            val (_, update) = updates.single()
            val body = update.implementation!!
            assertTrue("$version: the update has a local", body.registerCount > update.numberOfParameterRegisters)

            // #539 hands the update a null notice, so its own null test has to read p1 and take
            // the "notice is null" way out, which dismisses the banner.
            val p1 = body.registerCount - 1
            val code = body.instructions.toList()
            val copy = code.indexOfFirst {
                it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerB == p1
            }
            assertTrue("$version: the update copies its notice", copy >= 0)
            val held = (code[copy] as TwoRegisterInstruction).registerA
            val test = code.indexOfFirst { it.opcode == Opcode.IF_NEZ || it.opcode == Opcode.IF_EQZ }
            assertTrue("$version: the first branch follows the copy", test > copy)
            assertEquals("$version: the first branch tests the notice", Opcode.IF_NEZ, code[test].opcode)
            assertEquals("$version: on the notice's register", held, (code[test] as OneRegisterInstruction).registerA)
            assertEquals(
                "$version: and falls through to the null notice",
                "canShowNotice: notice is null",
                (code[test + 1].getReference<StringReference>())?.string,
            )
        }
    }
}
