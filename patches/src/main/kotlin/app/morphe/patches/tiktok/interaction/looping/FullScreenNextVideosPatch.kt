/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.looping

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PATCH = "Keep the next videos in full screen"
internal const val FULL_SCREEN_NEXT_VIDEOS = "Lapp/morphe/extension/tiktok/interaction/FullScreenNextVideos;"
internal const val FULL_SCREEN_ENTRANCE = "Lcom/ss/android/ugc/aweme/feed/landscape/LandscapeEntranceAssem;"
internal const val FULL_SCREEN_VIDEO = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/** The Full screen button's handler (oq on 47.6.3), the one place the viewer's list is set. */
internal object FullScreenOpenFingerprint : Fingerprint(
    definingClass = FULL_SCREEN_ENTRANCE,
    returnType = "V",
    parameters = listOf("I", "I", "I", "Ljava/lang/String;"),
)

/** TikTok's own test of what its viewer can play (086z.LJIL on 47.6.3): no ads, photo posts or LIVE. */
internal object FullScreenPlayableFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(FULL_SCREEN_VIDEO),
    strings = listOf("landscape_override_exp"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** Where the handler has just set the viewer's list: the next instruction, and what holds the video and the list. */
internal class FullScreenListSet(val at: Int, val video: Int, val list: Int)

private fun Instruction.callsArrayList(name: String) =
    opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
        it.definingClass == "Ljava/util/ArrayList;" && it.name == name
    } == true

/**
 * Finds the list being set by shape: a clear and an addAll on one ArrayList. The video is what the
 * handler then stores as the viewer's entry video. Both registers are still live at that point.
 */
internal fun Method.fullScreenListSet(): FullScreenListSet? {
    val code = implementation?.instructions?.toList() ?: return null
    val add = code.indexOfFirst { it.callsArrayList("addAll") }
    if (add < 1 || !code[add - 1].callsArrayList("clear")) return null
    val list = (code[add] as FiveRegisterInstruction).registerC
    if ((code[add - 1] as FiveRegisterInstruction).registerC != list) return null
    val next = code.getOrNull(add + 1) ?: return null
    if (next.opcode == Opcode.MOVE_RESULT) return null
    val video = code.drop(add).firstOrNull {
        it.opcode == Opcode.SPUT_OBJECT && it.getReference<FieldReference>()?.type == FULL_SCREEN_VIDEO
    } as? OneRegisterInstruction ?: return null
    if (list > 15 || video.registerA > 15) return null
    return FullScreenListSet(add + 1, video.registerA, list)
}

/** The body FullScreenNextVideos' stub gets, keeping the stub's flags as R8 left them. */
internal fun fullScreenPlayableBridge(test: Method, accessFlags: Int) = MutableMethod(
    ImmutableMethod(
        FULL_SCREEN_NEXT_VIDEOS, "playsFullScreen", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
        "Z", accessFlags, null, null, ImmutableMethodImplementation(2, emptyList(), null, null),
    ),
).apply {
    addInstructions(
        0,
        """
            check-cast p0, $FULL_SCREEN_VIDEO
            invoke-static {p0}, ${test.definingClass}->${test.name}($FULL_SCREEN_VIDEO)Z
            move-result v0
            return v0
        """,
    )
}

private fun MutableClass.swapPlayableStub(test: Method) {
    val stub = methods.singleOrNull {
        it.name == "playsFullScreen" && it.returnType == "Z" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: FullScreenNextVideos has no playsFullScreen stub")
    if (!AccessFlags.STATIC.isSet(stub.accessFlags)) {
        throw PatchException("$PATCH: the playsFullScreen stub isn't static")
    }
    val bridge = fullScreenPlayableBridge(test, stub.accessFlags)
    methods.remove(stub)
    methods.add(bridge)
}

@Suppress("unused")
val fullScreenNextVideosPatch = bytecodePatch(
    name = "Keep the next videos in full screen",
    description = "From most pages, TikTok's full screen view opens with only the video you were on, " +
        "so there's nothing to swipe to and it can't move on by itself. This brings the videos after " +
        "it along. Starts off. Turn it on in Hushfeed settings > Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFullScreenNextVideos()V",
        )
        val open = FullScreenOpenFingerprint.method
        val set = open.fullScreenListSet()
            ?: throw PatchException("$PATCH: the Full screen button's handler changed shape")
        open.addInstruction(
            set.at,
            "invoke-static {v${set.video}, v${set.list}}, $FULL_SCREEN_NEXT_VIDEOS->fill(Ljava/lang/Object;Ljava/util/List;)V",
        )
        mutableClassDefBy(FULL_SCREEN_NEXT_VIDEOS).swapPlayableStub(FullScreenPlayableFingerprint.method)
    }
}
