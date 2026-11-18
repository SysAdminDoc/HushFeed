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
internal const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val TRANSLATED_VIDEO = "Lcom/ss/android/ugc/aweme/feed/model/video/TranslatedVideo;"

/**
 * A post's AI dub tracks (TtsInfos, one per language). The feed model keeps its real name on
 * every build, and every reader of the list goes through this getter: the dub decision made
 * before a video plays (it needs a track in the app's language), the player's audio track list,
 * the "AI dubbed" label and the switch to the dubbed track on a playing video. Nothing else reads
 * the field. This is one of a dub's two routes; [TranslatedVideoFingerprint] is the other.
 */
internal object DubTracksFingerprint : Fingerprint(
    definingClass = AUDIO,
    name = "getTtsInfos",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

/**
 * A post's dubbed copy, a whole second video with the dub already in its sound (#463). The dub
 * decision takes this route even for a post with no dub tracks, and while it is chosen
 * `Aweme.getVideo` answers with this copy's video. Every reader goes through this getter except
 * the model converter, so a post whose getter answers null plays its own video.
 */
internal object TranslatedVideoFingerprint : Fingerprint(
    definingClass = AWEME,
    name = "getTranslatedVideo",
    returnType = TRANSLATED_VIDEO,
    parameters = emptyList(),
)

/**
 * Runs [smali] for the returned register right before each return of a getter. Calls made from
 * it use a range because the returned register isn't guaranteed to be below v16.
 */
private fun MutableMethod.beforeEachReturn(getter: String, smali: (Int) -> String) {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) throw PatchException("$PATCH: the $getter getter has no return.")
    for ((index, instruction) in returns.sortedByDescending { it.index }) {
        addInstructions(index, smali((instruction as OneRegisterInstruction).registerA))
    }
}

/** Hands the dub track list to the extension right before it's returned. */
internal fun MutableMethod.passDubTracksThroughExtension() = beforeEachReturn("dub track") { register ->
    """
        invoke-static/range {v$register .. v$register}, $ORIGINAL_AUDIO->dubTracks(Ljava/util/List;)Ljava/util/List;
        move-result-object v$register
    """
}

/** Hands the dubbed copy to the extension, which takes it as a plain object, before it's returned. */
internal fun MutableMethod.passTranslatedVideoThroughExtension() = beforeEachReturn("translated video") { register ->
    """
        invoke-static/range {v$register .. v$register}, $ORIGINAL_AUDIO->translatedVideo(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v$register
        check-cast v$register, $TRANSLATED_VIDEO
    """
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
        val dubTracks = DubTracksFingerprint.method
        val translatedVideo = TranslatedVideoFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableOriginalAudio()V",
        )
        dubTracks.passDubTracksThroughExtension()
        translatedVideo.passTranslatedVideoThroughExtension()
    }
}
