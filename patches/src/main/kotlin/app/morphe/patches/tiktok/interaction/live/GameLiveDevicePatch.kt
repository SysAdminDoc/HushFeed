/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.spoof.region.CommonParamsHandlerFingerprint
import app.morphe.patches.tiktok.misc.spoof.region.commonParamsFill
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.implementationOrPatchException

private const val PATCH = "Report a newer phone to game LIVE"
internal const val GAME_LIVE_DEVICE = "Lapp/morphe/extension/tiktok/live/GameLiveDevice;"

/**
 * A game LIVE's quality list comes from the server, in RoomCreateInfo.pushStreamInfoMap, which
 * /webcast/room/create_info/ sends back when the Go LIVE page opens (issue #459). That request
 * says nothing about the phone beyond TikTok's common parameters, whose builder puts
 * `Build.MODEL` as `device_type` and `Build.BRAND` as `device_brand` (GameLiveDeviceAnchorsTest).
 *
 * TTNet's common-parameter handler (the one Region spoof marks sign-in requests in) is the only
 * place that sees both a request's path and its parameters being built. Before it makes the map,
 * the request's path goes to GameLiveDevice.requestPath through the map's own register, which is
 * free until then. Right after the fill, the filled map goes to GameLiveDevice.requestParams,
 * before the handler copies it into the request. Region spoof's hook in the same method doesn't
 * get in the way in either order: each reads the method's shape as it is when it runs.
 */
internal fun MutableMethod.reportGameLiveSetup() {
    val fill = commonParamsFill(this) ?: throw PatchException(
        "$PATCH: the common-parameter handler no longer fills a new map and reads its request's path the way it did.",
    )
    val requestRegister = implementationOrPatchException(PATCH).registerCount - 2
    if (fill.mapRegister > 15 || requestRegister > 15) {
        throw PatchException("$PATCH: the common-parameter handler's registers are out of reach of the path read.")
    }
    val register = "v${fill.mapRegister}"
    // The later index first, so the map's index still points at the map.
    addInstruction(fill.fillAt + 1, "invoke-static { $register }, $GAME_LIVE_DEVICE->requestParams(Ljava/util/Map;)V")
    addInstructionsAtControlFlowLabel(
        fill.mapAt,
        """
            iget-object $register, p1, ${fill.requestField}
            iget-object $register, $register, ${fill.urlField}
            invoke-virtual { $register }, ${fill.pathGetter}
            move-result-object $register
            invoke-static { $register }, $GAME_LIVE_DEVICE->requestPath(Ljava/lang/String;)V
        """,
    )
}

@Suppress("unused")
val gameLiveDevicePatch = bytecodePatch(
    name = "Report a newer phone to game LIVE",
    description = "Tells TikTok's LIVE server you have a newer phone while you set up a LIVE, so a " +
        "game LIVE can offer qualities it holds back from some phones. Starts off. Turn it on in " +
        "Hushfeed settings > App.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        CommonParamsHandlerFingerprint.method.reportGameLiveSetup()

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableGameLiveDevice()V",
        )
    }
}
