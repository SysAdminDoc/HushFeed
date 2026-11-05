/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.seekbar

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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the seek bar on sound previews rests on (#185), held to each TikTok build the bundle
 * declares through the same fingerprints and the same resolver the patch runs.
 *
 * <p>The sound cover has one onViewCreated(View), and one method logs the preview's play time and
 * its end. The resolver reads the preview model, its player, the player's position, length and
 * seek, the sound field and the play time update off TikTok's code; on 47.4.3 each is pinned to
 * the member it was read as by hand. Every bridge body passes the verifier's typing, both with
 * stand-in names and with the names each build resolves to.
 */
class SoundSeekbarAnchorsTest {
    @Test
    fun `the sound cover's view and the preview tick each resolve to one method`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            for ((label, fingerprint) in listOf("cover view" to SoundCoverViewFingerprint, "preview tick" to SoundPreviewTickFingerprint)) {
                val taken = build.methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }.toList()
                assertEquals("the $label: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            }
            val view = build.methods.single { (classDef, method) -> SoundCoverViewFingerprint.takes(method, classDef) }.second
            assertFalse("the cover's onViewCreated is static", AccessFlags.STATIC.isSet(view.accessFlags))
        }
    }

    @Test
    fun `the preview's members resolve to the player TikTok's own code uses`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val tick = build.tick()
            val members = resolveSoundPreviewMembers(tick) { build.byType[it] }

            if (Fixtures.versionOf(apk) == "47.4.3") {
                assertEquals("ir", members.coverViewModel)
                assertEquals("i33", members.playerGetter)
                assertEquals("LX/0Etz;", members.player)
                assertEquals("LJ", members.position)
                assertEquals("S2", members.duration)
                assertEquals("Ig", members.seek)
                assertEquals("LX/1HhK;", members.seekCallback)
                assertEquals("LLJJLIIIJLLLLLLLZ", members.sound)
                assertEquals("Lkotlin/jvm/internal/AwS25S0001000_26_I1;", members.playTime)
                assertEquals(1, members.playTimeSelector)
            }

            // The play time update is built from the counted time and the selector, in that
            // order: the time is no constant, so a seek's spot goes where the tick puts its count.
            val code = tick.implementation!!.instructions.toList()
            val update = code.indexOfFirst {
                it.opcode == Opcode.INVOKE_DIRECT && it.getReference<MethodReference>()?.definingClass == members.playTime
            }
            val built = code[update] as FiveRegisterInstruction
            val time = code.subList(0, update).lastOrNull { it is OneRegisterInstruction && it.registerA == built.registerD && it.opcode.setsRegister() }
            assertFalse("the play time is a constant: ${time?.opcode}", time?.opcode in SOUND_CONSTANTS)

            // The player is the one the model's onCleared releases.
            val model = build.byType.getValue(SOUND_PLAY_VIEW_MODEL)
            val cleared = model.methods.single { it.name == "onCleared" && it.parameterTypes.isEmpty() }.calls()
            assertTrue(cleared.any { it.name == members.playerGetter && it.definingClass == SOUND_PLAY_VIEW_MODEL })
            assertTrue(cleared.any { it.name == "release" && it.definingClass == members.player })

            for ((name, body) in soundPreviewBridgeBodies(members)) {
                assertEquals("$name on ${Fixtures.versionOf(apk)}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge(name, body)))
            }
        }
    }

    @Test
    fun `every bridge body passes the verifier's typing`() {
        val members = SoundPreviewMembers(
            coverViewModel = "a", playerGetter = "b", player = "LX/Player;", position = "c", duration = "d", seek = "e",
            seekCallback = "LX/Callback;", sound = "f", playTime = "LX/PlayTime;", playTimeSelector = 3,
        )
        val bodies = soundPreviewBridgeBodies(members)
        assertEquals(SOUND_PREVIEW_BRIDGES.keys, bodies.keys)
        for ((name, body) in bodies) {
            val method = bridge(name, body)
            assertEquals(name, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
            val code = method.implementation!!.instructions.toList()
            assertEquals("$name casts its parameter first", Opcode.CHECK_CAST,
                code.first { it.opcode != Opcode.CONST_4 }.opcode)
        }

        // The seek moves the player with no callback and hands setState the play time at the
        // spot it seeks to, with the tick's selector.
        val seek = bridge("seekPreview", bodies.getValue("seekPreview")).implementation!!.instructions.toList()
        val calls = seek.mapNotNull { it.getReference<MethodReference>() }.map { "${it.definingClass}->${it.name}" }
        assertEquals(listOf("$SOUND_PLAY_VIEW_MODEL->b", "LX/Player;->e", "LX/PlayTime;-><init>", "$SOUND_ASSEM_VIEW_MODEL->setState"), calls)
        assertTrue(seek.any { it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == 3 })
    }

    @Test
    fun `the patch hooks the sound cover and writes the bridges`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt").readText()
        assertTrue("the cover's view is not hooked", source.contains("SoundCoverViewFingerprint.method.soundCoverViewHook()"))
        assertTrue("the bridges are not written", source.contains("soundPreviewBridges(members)"))
        assertTrue("the switch is not shown", source.contains("\"enableSoundSeekbar\""))
    }

    /** A bridge as the patch leaves it: static, with its parameters and two registers below them. */
    private fun bridge(name: String, body: String): MutableMethod {
        val (parameters, returnType) = SOUND_PREVIEW_BRIDGES.getValue(name)
        return MutableMethod(ImmutableMethod(SOUND_SEEKBAR, name, parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType, AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(SOUND_BRIDGE_LOCALS + parameters.size, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, body)
        }
    }

    private fun Method.calls(): List<MethodReference> =
        implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() } ?: emptyList()

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
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

        fun tick(): Method = methods.single { (classDef, method) -> SoundPreviewTickFingerprint.takes(method, classDef) }.second
    }
}
