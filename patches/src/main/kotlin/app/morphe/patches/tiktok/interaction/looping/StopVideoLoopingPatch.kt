/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.looping

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.interaction.resume.FeedPlayCompletedFingerprint
import app.morphe.patches.tiktok.interaction.resume.FeedPlayProgressFingerprint

private const val EXTENSION_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/interaction/StopVideoLoopingPatch;"
private const val STOPPED_VIDEO_SCREEN =
    "Lapp/morphe/extension/tiktok/interaction/StoppedVideoScreen;"

@Suppress("unused")
val stopVideoLoopingPatch = bytecodePatch(
    name = "Stop video looping",
    description = "Stops a video at its end instead of playing it again. Starts off. Turn it " +
        "on in Hushfeed settings > Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableStopVideoLooping()V",
        )

        VideoEngineSetLoopingFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $EXTENSION_DESCRIPTOR->overrideLooping(Z)Z
                move-result p1
            """,
        )

        // TikTok keeps the screen on while the feed is up, playing or not, which never mattered
        // while every video looped. A video stopped at its end lets the screen time out again,
        // and the hold comes back as soon as the player moves (#414).
        FeedPlayCompletedFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $STOPPED_VIDEO_SCREEN->onPlayCompleted(Ljava/lang/String;)V",
        )
        FeedPlayProgressFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p5 }, $STOPPED_VIDEO_SCREEN->onPlayProgress(Ljava/lang/String;JJ)V",
        )
    }
}
