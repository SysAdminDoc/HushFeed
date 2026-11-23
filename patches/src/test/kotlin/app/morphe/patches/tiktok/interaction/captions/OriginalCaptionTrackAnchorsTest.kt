/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.captions

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Test

private const val ENTRY_SIZE = 6

/**
 * #217: with the Captions row back, every video toasted that it had no captions. TikTok shows
 * captions only from the track whose language id matches the video's original caption language,
 * and asks one static lookup for it everywhere. On every declared build that lookup is found once,
 * and the extension answers in front of it: a track it picks is returned, and null runs TikTok's
 * own code unchanged.
 */
class OriginalCaptionTrackAnchorsTest {
    @Test
    fun `each build has one original caption track lookup and the extension answers first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (OriginalCaptionTrackFingerprint.takes(method, classDef)) found += method
                    }
                }
            }
            assertEquals("$version: lookups ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)

            val native = found.single()
            val before = native.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            val patched = MutableMethod(native)
            patched.keepOriginalCaptionTrack("Subtitle tools")
            val after = patched.implementation!!.instructions.toList()

            assertEquals(
                "$version: the extension's answer comes first",
                listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.RETURN_OBJECT, Opcode.NOP),
                after.take(ENTRY_SIZE).map { it.opcode },
            )
            val call = after[0].getReference<MethodReference>()!!
            assertEquals(CAPTIONS_AVAILABLE, call.definingClass)
            assertEquals("originalTrack", call.name)
            assertEquals("$version: with the Aweme TikTok was asked about",
                native.implementation!!.registerCount - 1, (after[0] as RegisterRangeInstruction).startRegister)
            assertEquals(1, (after[0] as RegisterRangeInstruction).registerCount)
            assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
            assertEquals("$version: as TikTok's own type", CAPTION_ITEM, (after[3].getReference<TypeReference>())!!.type)
            assertEquals("$version: and it is the one returned", 0, (after[4] as OneRegisterInstruction).registerA)
            assertEquals("$version: null runs TikTok's lookup", ENTRY_SIZE - 1,
                (after[2] as BuilderOffsetInstruction).target.location.index)
            assertEquals("$version: TikTok's lookup is untouched",
                before.map { it.opcode }, after.drop(ENTRY_SIZE).filter { it.opcode != Opcode.NOP }.map { it.opcode })
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
        }
    }
}
