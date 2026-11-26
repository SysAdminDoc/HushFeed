/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction

private const val SOUND_PAGE_BUTTON =
    "Lapp/morphe/extension/tiktok/blockauthor/SoundPageBlockButton;"

/**
 * A sound's own page, the one the spinning record on a video opens (and a sound in search). Its
 * class keeps its name in every build, and onResume is its own, so the hook needs no reading of
 * TikTok's code. The page reads the sound's id from its intent's "id" extra in init().
 */
internal object MusicDetailActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/music/ui/MusicDetailActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

@Suppress("unused")
val soundPageBlockPatch = bytecodePatch(
    name = "Block sound on sound pages",
    description = "Adds a Block sound button under Save on a sound's page, the one the spinning " +
        "record on a video opens, so you can skip every video using that sound from the sound " +
        "itself. Needs Feed filter. On by default. Turn it off in Hushfeed settings > Feed screen.",
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // At the front, before the page's own resume: the button goes in from a layout listener
        // once Save is built, so nothing here has to come after TikTok's code.
        MusicDetailActivityOnResumeFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $SOUND_PAGE_BUTTON->onResume(Landroid/app/Activity;)V",
        )

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSoundPageBlock()V",
        )
    }
}
