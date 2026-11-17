/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.originalaudio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Play original audio"
internal const val ORIGINAL_AUDIO = "Lapp/morphe/extension/tiktok/playback/OriginalAudio;"
internal const val AUDIO = "Lcom/ss/android/ugc/aweme/feed/model/Audio;"

/**
 * A post's AI dub tracks (TtsInfos, one per language). The feed model keeps its real name on
 * every build, and every reader of the list goes through this getter: the dub decision made
 * before a video plays (it needs a track in the app's language), the player's audio track list,
 * the "AI dubbed" label and the switch to the dubbed track on a playing video. Nothing else reads
 * the field, so a post whose getter answers null has no dub anywhere in TikTok.
 */
internal object DubTracksFingerprint : Fingerprint(
    definingClass = AUDIO,
    name = "getTtsInfos",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

/**
 * Hands the getter's answer to the extension right before it's returned. A range call is used
 * because the returned register isn't guaranteed to be below v16.
 */
internal fun MutableMethod.passDubTracksThroughExtension() {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) throw PatchException("$PATCH: the dub track getter has no return.")
    for ((index, instruction) in returns.sortedByDescending { it.index }) {
        val register = (instruction as OneRegisterInstruction).registerA
        addInstructions(
            index,
            """
                invoke-static/range {v$register .. v$register}, $ORIGINAL_AUDIO->dubTracks(Ljava/util/List;)Ljava/util/List;
                move-result-object v$register
            """,
        )
    }
}

@Suppress("unused")
val originalAudioPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Play original audio",
    description = "Plays videos and mini dramas with their original audio instead of TikTok's " +
        "AI dub in your app language. Captions and translations stay as they are. Starts off. " +
        "Turn it on in Hushfeed settings > Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val getter = DubTracksFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableOriginalAudio()V",
        )
        getter.passDubTracksThroughExtension()
    }
}
