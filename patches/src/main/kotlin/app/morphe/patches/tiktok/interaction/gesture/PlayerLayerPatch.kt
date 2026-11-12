/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.valueIn
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

internal const val PLAYER_SURFACE = "Lcom/ss/ttm/player/TTPlayerSurface;"
internal const val LIVE_MANAGER = "Lcom/ss/videoarch/liveplayer/VideoLiveManager;"
private const val SURFACE_CONTROL = "Landroid/view/SurfaceControl;"
private const val SURFACE = "Landroid/view/Surface;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/download/PlayerLayers;"
private const val PATCH = "Player layers"

/**
 * The player's static helper that hangs a TTPlayerSurface's layer under a parent layer, with the
 * buffer's width and height and whether the layer shows, then logs what it did. The helper's class
 * is ProGuard's, so it's found by that log line and TTPlayerSurface, which kept its name.
 */
internal object PlayerLayerReparentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf(PLAYER_SURFACE, SURFACE_CONTROL, "I", "I", "Z", "J"),
    strings = listOf("reparent DONE: target: "),
)

/** The same for Android 13 and later, waiting for the commit; the layer always shows. */
internal object PlayerLayerShownFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf(PLAYER_SURFACE, SURFACE_CONTROL, "I", "I", "J", "L", "L"),
    strings = listOf("reparentV33 DONE: target: "),
)

/** And the helper that takes the layer down again. */
internal object PlayerLayerDetachFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf("J", PLAYER_SURFACE),
    strings = listOf("recycle: "),
)

/**
 * The LIVE player's setSurfaceControl. It builds a layer of its own (Live-n, or its
 * SurfaceManager's), hangs it under the parent it's handed at the parent's size, and points the
 * stream at that layer's Surface. The method's name is ProGuard's; the class and the log line kept
 * theirs.
 */
internal object LiveLayerFingerprint : Fingerprint(
    definingClass = LIVE_MANAGER,
    returnType = "V",
    parameters = listOf(SURFACE_CONTROL, "I", "I"),
    strings = listOf("setSurfaceControl parent:"),
)

/** One place the LIVE hook goes: before the call at [index], with the registers that hold the parent, the Surface and the size there. */
internal data class LiveLayerCall(val index: Int, val parent: Int, val surface: Int, val width: Int, val height: Int)

/**
 * Where the LIVE player hands the stream its layer's Surface: each call taking a long, a Surface
 * and a boolean, one per branch (the SurfaceManager's and its own). The parent and the size are
 * the method's parameters, read from the locals it copies them into, and only while nothing
 * between that copy and the call writes the local again. That's judged over the instructions in
 * order, which counts a write on any path, so a doubt drops the call rather than pass a wrong value.
 */
internal fun liveLayerCalls(method: Method): List<LiveLayerCall> {
    val body = method.implementation ?: return emptyList()
    val instructions = body.instructions.toList()
    val p0 = body.registerCount - MethodUtil.getParameterRegisterCount(method)
    val moves = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

    fun writes(from: Int, until: Int, register: Int) = (from until until).any { i ->
        val instruction = instructions[i]
        val opcode = instruction.opcode
        instruction is OneRegisterInstruction && opcode.setsRegister() &&
            (instruction.registerA == register || (opcode.setsWideRegister() && instruction.registerA + 1 == register))
    }

    // The register holding parameter [n] (p1 the parent, p2 and p3 the size) at [call].
    fun held(n: Int, call: Int): Int? {
        val parameter = p0 + n
        val copy = (0 until call).firstOrNull { i ->
            val instruction = instructions[i]
            instruction.opcode in moves && (instruction as TwoRegisterInstruction).registerB == parameter
        }
        if (copy != null) {
            val local = (instructions[copy] as TwoRegisterInstruction).registerA
            return local.takeUnless { writes(copy + 1, call, it) }
        }
        return parameter.takeUnless { writes(0, call, it) }
    }

    return instructions.indices.mapNotNull { index ->
        val call = instructions[index]
        val reference = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (reference.returnType != "V" || reference.parameterTypes.map(CharSequence::toString) != listOf("J", SURFACE, "Z")) {
            return@mapNotNull null
        }
        // The Surface follows the receiver, if there is one, and the long's two registers.
        val slot = (if (call.opcode == Opcode.INVOKE_STATIC || call.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1) + 2
        val surface = when (call) {
            is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)[slot]
            is RegisterRangeInstruction -> call.startRegister + slot
            else -> return@mapNotNull null
        }
        LiveLayerCall(
            index,
            held(1, index) ?: return@mapNotNull null,
            surface,
            held(2, index) ?: return@mapNotNull null,
            held(3, index) ?: return@mapNotNull null,
        )
    }
}

/**
 * Tells the frame save and the glass blur where TikTok's player draws. On 47.5.3 the S22's feed
 * plays into a layer the player builds under the cell's SurfaceView, and the SurfaceView itself
 * never gets a frame, so a read has to go to the layer (2026-10-23). The player calls these helpers
 * directly, past TTPlayerSurface's own nativeReparent, so the hooks sit in the helpers. The LIVE
 * player builds its layer itself, so its hook sits where it hands the stream that layer's Surface
 * (#259). The gesture patches that offer the frame save depend on this.
 */
internal val playerLayerPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        // p0 is the TTPlayerSurface, p1 the parent SurfaceControl, then width, height, visible.
        PlayerLayerReparentFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p4 }, " +
                "$EXTENSION->reparented(Ljava/lang/Object;Ljava/lang/Object;IIZ)V",
        )
        PlayerLayerShownFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p3 }, $EXTENSION->shown(Ljava/lang/Object;Ljava/lang/Object;II)V",
        )
        // p0 and p1 hold the player's long id, p2 the TTPlayerSurface.
        PlayerLayerDetachFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p2 .. p2 }, $EXTENSION->detached(Ljava/lang/Object;)V",
        )

        LiveLayerFingerprint.method.apply {
            val calls = liveLayerCalls(this)
            if (calls.isEmpty()) {
                throw PatchException("$PATCH: no call in $definingClass->$name hands the LIVE stream its Surface")
            }
            // From the last, so the earlier indexes still point where they did. A jump to the call
            // itself skips the hook, which is the path where no layer was built.
            calls.sortedByDescending { it.index }.forEach { call ->
                addInstructions(
                    call.index,
                    callThroughLocals(
                        PATCH,
                        "invoke-static",
                        "$EXTENSION->live(Ljava/lang/Object;Landroid/view/Surface;II)V",
                        false,
                        objectIn("v${call.parent}"),
                        objectIn("v${call.surface}"),
                        valueIn("v${call.width}"),
                        valueIn("v${call.height}"),
                    ),
                )
            }
        }
    }
}
