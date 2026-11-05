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
import com.android.tools.smali.dexlib2.iface.ClassDef
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
internal const val FULL_SCREEN_VIEW_MODEL = "Lcom/ss/android/ugc/aweme/feed/landscape/viewmodels/LandscapeFeedViewModel;"
internal const val FULL_SCREEN_FIRST_PAGE_TAG = "landscape_screen_item_soure"

/** The Full screen button's handler (Cq on 47.7.2), the one place the viewer's list is set. */
internal object FullScreenOpenFingerprint : Fingerprint(
    definingClass = FULL_SCREEN_ENTRANCE,
    returnType = "V",
    parameters = listOf("I", "I", "I", "Ljava/lang/String;"),
)

/** TikTok's own test of what its viewer can play (0A7q.LJJ on 47.7.2): no ads, photo posts or LIVE. */
internal object FullScreenPlayableFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(FULL_SCREEN_VIDEO),
    strings = listOf("landscape_override_exp"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** What the viewer takes as its first page off For You (n71 on 47.7.2): the first video of its list, alone. */
internal object FullScreenFirstPageFingerprint : Fingerprint(
    definingClass = FULL_SCREEN_VIEW_MODEL,
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
    strings = listOf(FULL_SCREEN_FIRST_PAGE_TAG),
)

/** Where the handler has just set the viewer's list: the next instruction, and what holds the video. */
internal class FullScreenListSet(val at: Int, val video: Int)

/**
 * Where the first page has just been built: the next instruction, what holds the page, the lower of
 * the two registers free for the list's read position, and the field holding the list.
 */
internal class FullScreenFirstPage(val at: Int, val page: Int, val free: Int, val list: FieldReference)

private fun Instruction.callsArrayList(name: String) =
    opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
        it.definingClass == "Ljava/util/ArrayList;" && it.name == name
    } == true

/**
 * Finds the list being set by shape: a clear and an addAll on one ArrayList. The video is what the
 * handler then stores as the viewer's entry video, so its register is still live at that point.
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
    if (video.registerA > 15) return null
    return FullScreenListSet(add + 1, video.registerA)
}

/**
 * Finds the first page by shape: a static call giving a List, then the state lambda being built from
 * it. That lambda and its switch number overwrite their two registers, so both are free before them.
 * The path with an empty list branches onto the same instruction, so it goes past the hook.
 */
internal fun Method.fullScreenFirstPage(): FullScreenFirstPage? {
    val code = implementation?.instructions?.toList() ?: return null
    val at = code.indices.firstOrNull { index ->
        index >= 2 && code[index].opcode == Opcode.NEW_INSTANCE &&
            code[index - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[index - 2].opcode == Opcode.INVOKE_STATIC &&
            code[index - 2].getReference<MethodReference>()?.returnType == "Ljava/util/List;"
    } ?: return null
    val page = (code[at - 1] as OneRegisterInstruction).registerA
    val made = (code[at] as OneRegisterInstruction).registerA
    val number = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.CONST_4 || it.opcode == Opcode.CONST_16 }
        as? OneRegisterInstruction ?: return null
    val free = minOf(made, number.registerA)
    if (maxOf(made, number.registerA) != free + 1 || page == made || page == number.registerA) return null
    if (page > 15 || free > 14) return null
    val list = code.firstOrNull { it.opcode == Opcode.SGET_OBJECT }?.getReference<FieldReference>()
        ?.takeIf { it.type == "Ljava/util/List;" } ?: return null
    return FullScreenFirstPage(at, page, free, list)
}

/** The list's read position, the one long on the class holding the list. */
internal fun ClassDef.fullScreenReadPosition(): String? =
    fields.singleOrNull { it.type == "J" }?.let { "$type->${it.name}:J" }

/**
 * Hands over the whole list, then moves the read position with it. Near the end of what it holds the
 * viewer asks the list for more, and from the start the list would answer with the first video again.
 */
internal fun fullScreenFirstPageHook(page: FullScreenFirstPage, position: String) = """
    invoke-static {v${page.page}}, $FULL_SCREEN_NEXT_VIDEOS->wholeList(Ljava/util/List;)Ljava/util/List;
    move-result-object v${page.page}
    sget-wide v${page.free}, $position
    invoke-static {v${page.free}, v${page.free + 1}}, $FULL_SCREEN_NEXT_VIDEOS->readFrom(J)J
    move-result-wide v${page.free}
    sput-wide v${page.free}, $position
"""

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
            "invoke-static {v${set.video}}, $FULL_SCREEN_NEXT_VIDEOS->fill(Ljava/lang/Object;)V",
        )
        val firstPage = FullScreenFirstPageFingerprint.method
        val page = firstPage.fullScreenFirstPage()
            ?: throw PatchException("$PATCH: the viewer's first page changed shape")
        val position = mutableClassDefBy(page.list.definingClass).fullScreenReadPosition()
            ?: throw PatchException("$PATCH: the viewer's list has no read position")
        firstPage.addInstructions(page.at, fullScreenFirstPageHook(page, position))
        mutableClassDefBy(FULL_SCREEN_NEXT_VIDEOS).swapPlayableStub(FullScreenPlayableFingerprint.method)
    }
}
