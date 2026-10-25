/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

internal const val PLAYER_SURFACE = "Lcom/ss/ttm/player/TTPlayerSurface;"
private const val SURFACE_CONTROL = "Landroid/view/SurfaceControl;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/download/PlayerLayers;"

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
 * Tells the frame save where TikTok's player draws. On 47.5.3 the S22's feed plays into a layer
 * the player builds under the cell's SurfaceView, and the SurfaceView itself never gets a frame,
 * so the save has to read the layer (2026-10-23). The player calls these helpers directly, past
 * TTPlayerSurface's own nativeReparent, so the hooks sit in the helpers. The gesture patches that
 * offer the frame save depend on this.
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
    }
}
