/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Fix notifications on OPPO, OnePlus and realme rests on, held to each declared build (#496):
 * TikTok's push SDK still names the OPPO adapter as the "op" channel, the adapter's availability
 * answer is HeyTap's own check, and both guarded methods have a local for the guard's answer.
 */
class OppoPushAnchorsTest {
    @Test
    fun `the OPPO push adapter is TikTok's op channel and both guards fit, on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val adapter = classes.filter { it.type == OPPO_PUSH_ADAPTER }
            assertEquals("$version: OpPushAdapter classes", 1, adapter.distinctBy { it.type }.size)
            val adapterClass = adapter.first()

            val named = classes.asSequence().flatMap { it.methods }
                .flatMap { it.implementation?.instructions?.asSequence().orEmpty() }
                .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                .any { it == "com.coloros.OpPushAdapter" }
            assertTrue("$version: TikTok's push SDK no longer loads the OPPO adapter by name", named)

            for (fingerprint in listOf(OppoPushAvailableFingerprint, OppoPushRegisterFingerprint)) {
                val taken = adapterClass.methods.filter { fingerprint.takes(it, adapterClass) }
                assertEquals("$version ${fingerprint.javaClass.simpleName}", 1, taken.size)
                val body = taken.single().implementation!!
                // An instance method taking a Context and an int: three parameter registers.
                assertTrue("$version: ${taken.single().name} has no local for the guard", body.registerCount - 3 >= 1)
            }

            val available = adapterClass.methods.single { OppoPushAvailableFingerprint.takes(it, adapterClass) }
            val asks = available.implementation!!.instructions.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call -> "${call.definingClass}->${call.name}" }
            }
            assertTrue("$version: isPushAvailable no longer asks HeyTap: $asks",
                "Lcom/heytap/msp/push/HeytapPushManager;->isSupportPush" in asks)
        }
    }
}
