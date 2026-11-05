/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcodes
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
}
