/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.feedfilter.LiveDrawRoomPageFingerprint
import app.morphe.patches.tiktok.feedfilter.liveRequestTypeGetter
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Swipe LIVEs in your Following row's order (#511) hands the Following row's LIVE opener's
 * EnterRoomConfig, tapped entry and row to the extension right before watchLive. Held to each
 * declared build: one opener, one watchLive in it, the config register the one the opener built
 * its EnterRoomConfig in, the tapped entry and the row its untouched second and third
 * parameters, and the hooked body TikTok's own code with the one call in front of watchLive.
 */
class LiveRowOrderAnchorsTest {
    @Test
    fun `the Following row's LIVE opener resolves on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val found = classes.flatMap { type ->
                type.methods.filter { FollowingRowLiveEnterFingerprint.takes(it, type) }
            }
            assertEquals("$version: openers ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)
            val opener = found.single()
            val enter = rowLiveEnter(opener)
            val code = opener.implementation!!.instructions.toList()

            // The config is the EnterRoomConfig the opener made itself.
            val made = code.filter {
                it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == ENTER_ROOM_CONFIG
            }
            assertEquals("$version: EnterRoomConfigs made", 1, made.size)
            assertEquals(enter.config, (made.single() as OneRegisterInstruction).registerA)
            // Static (Context, FollowingInterestUser, List, I, View, 3 Strings, J, J): 12 registers.
            val p0 = opener.implementation!!.registerCount - 12
            assertEquals(p0 + 1, enter.tapped)
            assertEquals(p0 + 2, enter.row)

            val mutable = MutableMethod(opener)
            mutable.hookRowLiveEnter(enter)
            val hooked = mutable.implementation!!.instructions.toList()
            val call = hooked[enter.index]
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            val reference = call.getReference<MethodReference>()!!
            assertEquals(ROW_ORDER_EXTENSION, reference.definingClass)
            assertEquals("seed", reference.name)
            val registers = call as FiveRegisterInstruction
            assertEquals(listOf(enter.config, enter.tapped, enter.row),
                listOf(registers.registerC, registers.registerD, registers.registerE))
            assertEquals("watchLive", hooked[enter.index + 1].getReference<MethodReference>()!!.name)
            assertEquals(code.map { it.opcode }, (hooked.take(enter.index) + hooked.drop(enter.index + 1)).map { it.opcode })
        }
    }

    @Test
    fun `the LIVE page handler hands its pager, page and request type over first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val found = classes.flatMap { type ->
                type.methods.filter { LiveDrawRoomPageFingerprint.takes(it, type) }.map { type to it }
            }
            assertEquals("$version: page handlers", 1, found.size)
            val (provider, handler) = found.single()
            val getter = handler.liveRequestTypeGetter(provider)
            val code = handler.implementation!!.instructions.toList()

            val mutable = MutableMethod(handler)
            mutable.hookRowOrderPage(getter)
            val hooked = mutable.implementation!!.instructions.toList()
            val p0 = handler.implementation!!.registerCount - handler.parameterTypes.size
            val call = hooked[4]
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            val reference = call.getReference<MethodReference>()!!
            assertEquals(ROW_ORDER_EXTENSION, reference.definingClass)
            assertEquals("page", reference.name)
            assertEquals(listOf(p0, p0 + 1), listOf((hooked[0] as TwoRegisterInstruction).registerB, (hooked[1] as TwoRegisterInstruction).registerB))
            assertEquals(getter, hooked[2].getReference<MethodReference>()!!.let { "${it.definingClass}->${it.name}()${it.returnType}" })
            // The answer is the page the handler goes on with.
            assertEquals(p0 + 1, (hooked[5] as OneRegisterInstruction).registerA)
            assertEquals(code.map { it.opcode }, hooked.drop(6).map { it.opcode })
        }
    }

    @Test
    fun `an opener of another shape stops the patch`() {
        val watch = ImmutableMethodReference(
            "LX/Outer;", "watchLive",
            listOf("Landroid/content/Context;", ENTER_ROOM_CONFIG, "Ljava/lang/String;"), "Z",
        )
        // Two parameters, the second written over before watchLive.
        val overwritten = opener(
            ImmutableInstruction11n(Opcode.CONST_4, 4, 0),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 4, 0, 2, 0, 3, 0, watch),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        assertThrows(PatchException::class.java) { rowLiveEnter(overwritten) }

        // No watchLive at all.
        val none = opener(ImmutableInstruction10x(Opcode.RETURN_VOID))
        assertThrows(PatchException::class.java) { rowLiveEnter(none) }
    }

    /** A static (Object, Object, List) opener in a frame of 5: p0 = v2, p1 = v3, p2 = v4. */
    private fun opener(vararg code: Instruction) = ImmutableMethod(
        "LX/Row;", "open",
        listOf(
            ImmutableMethodParameter("Landroid/content/Context;", null, null),
            ImmutableMethodParameter(FOLLOWING_INTEREST_USER, null, null),
            ImmutableMethodParameter("Ljava/util/List;", null, null),
        ),
        "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(5, code.toList(), null, null),
    )
}
