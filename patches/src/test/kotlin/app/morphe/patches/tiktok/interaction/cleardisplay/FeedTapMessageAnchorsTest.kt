package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap brings the controls back (#501) answers the feed panel's tap message before TikTok pauses
 * the video for it. Held to every declared build: one instance handleMsg(Message) on the panel,
 * with a local below the parameters for the hook to use, and the hook asking the extension with
 * the message's what and falling through to TikTok's own first instruction on a no.
 */
class FeedTapMessageAnchorsTest {
    @Test
    fun `the panel's tap message is one instance method the hook answers first`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk)
            val taken = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.flatMap { classDef ->
                    classDef.methods.filter { FeedTapMessageFingerprint.takes(it, classDef) }
                }
            }
            assertEquals("${apk.name}: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val stock = taken.single()
            assertFalse("${apk.name}: handleMsg is static", AccessFlags.STATIC.isSet(stock.accessFlags))
            val registers = stock.implementation!!.registerCount
            assertTrue("${apk.name}: v0 is a parameter", registers > 2)

            val method = MutableMethod(stock)
            val before = method.implementation!!.instructions.map { it.opcode }
            method.hookFeedTap()
            val after = method.implementation!!.instructions.toList()
            assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), after.take(6).map { it.opcode })
            val message = after[0] as TwoRegisterInstruction
            assertEquals(0, message.registerA)
            assertEquals("${apk.name}: the hook didn't read p1", registers - 1, message.registerB)
            assertEquals("Landroid/os/Message;->what:I", after[1].getReference<FieldReference>().toString())
            assertEquals("Lapp/morphe/extension/tiktok/cleardisplay/RememberClearDisplayPatch;->onFeedTap(I)Z",
                after[2].getReference<MethodReference>().toString())
            assertEquals(0, (after[4] as OneRegisterInstruction).registerA)
            assertEquals("${apk.name}: a no doesn't reach TikTok's own code", 6,
                (after[4] as BuilderOffsetInstruction).target.location.index)
            assertEquals("${apk.name}: TikTok's own code changed", before, after.drop(6).map { it.opcode })
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }
}
