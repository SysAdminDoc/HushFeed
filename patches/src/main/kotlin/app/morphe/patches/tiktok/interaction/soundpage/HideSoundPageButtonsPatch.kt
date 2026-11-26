/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.soundpage

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.MusicDetailActivityOnResumeFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction

private const val SOUND_PAGE_BUTTONS =
    "Lapp/morphe/extension/tiktok/feed/SoundPageButtons;"

@Suppress("unused")
val hideSoundPageButtonsPatch = bytecodePatch(
    name = "Hide sound page buttons",
    description = "Adds a switch that hides Jam Sound and Use sound at the bottom of a sound's " +
        "page, the one the spinning record on a video opens. Off until you turn it on in " +
        "Hushfeed settings > Feed screen.",
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // The same front-of-onResume hook as Block sound on sound pages: the buttons are found
        // from a layout listener, so nothing here has to come after TikTok's code.
        MusicDetailActivityOnResumeFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $SOUND_PAGE_BUTTONS->onResume(Landroid/app/Activity;)V",
        )

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideSoundPageButtons()V",
        )
    }
}
