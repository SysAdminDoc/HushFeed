/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val REPOST_CHIP_TRIGGER = "Lcom/ss/android/ugc/feed/platform/cell/interact/info/UpvoteVideoTrigger;"
internal const val REPOST_BAR_ASSEM = "Lcom/ss/android/ugc/aweme/upvote/detail/whitebar/UpvoteVideoAssemNew;"
private const val REPOST_CONTROLS = "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;"
private const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"
private const val CHIP_VIDEO = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val PAGE_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;"

/**
 * The trigger that decides whether a video gets the chip saying someone you follow reposted it
 * (#319), TikTok's repost "white bar". A cell trigger asks this one method and only builds its
 * component on true, which is the name and the shape it keeps from build to build.
 */
internal object RepostChipTriggerFingerprint : Fingerprint(
    definingClass = REPOST_CHIP_TRIGGER,
    returnType = "Z",
    parameters = listOf(VIDEO_ITEM_PARAMS),
    custom = { method, _ -> isRepostChipTrigger(method) },
)

/**
 * The one method that shows or hides that white bar. The trigger above only covers the chip: the
 * bar's assem also shows the bar by itself as its prompt to repost, after a loop or a like, and
 * that comes through here with a reason of its own (#319). The string is its log line.
 */
internal object RepostBarVisibilityFingerprint : Fingerprint(
    definingClass = REPOST_BAR_ASSEM,
    returnType = "V",
    strings = listOf(", changeContainerViewVisibility: "),
)

/** Rewrites the visibility as it comes in, with no branch of its own (#264). */
internal fun MutableMethod.dropRepostBar() {
    addInstructions(
        0,
        """
            invoke-static {p1}, $REPOST_CONTROLS->repostBarVisibility(I)I
            move-result p1
        """,
    )
}

internal fun isRepostChipTrigger(method: Method): Boolean =
    !AccessFlags.ABSTRACT.isSet(method.accessFlags) &&
        method.parameterTypes.map(CharSequence::toString) == listOf(VIDEO_ITEM_PARAMS)

/**
 * The index of the move-result holding TikTok's answer: the one call handed the video and its page,
 * answering a boolean. -1 when the trigger no longer has that shape.
 */
internal fun repostChipAnswer(instructions: List<Instruction>): Int {
    val calls = instructions.withIndex().filter { (_, instruction) ->
        instruction.getReference<MethodReference>()?.let { call ->
            call.returnType == "Z" &&
                call.parameterTypes.map(CharSequence::toString) == listOf(CHIP_VIDEO, PAGE_PARAMS)
        } == true
    }
    val answer = calls.singleOrNull()?.index?.plus(1) ?: return -1
    return if (instructions.getOrNull(answer)?.opcode == Opcode.MOVE_RESULT) answer else -1
}

/**
 * Hands TikTok's answer to the extension right after it lands. With the switch on it comes back
 * false, which is what a video nobody reposted gets: the trigger builds nothing and the slot stays
 * empty. Straight-line on purpose, since it runs for every video in the feed (#264).
 */
internal fun MutableMethod.dropRepostChip(patch: String) {
    val answer = repostChipAnswer(implementation!!.instructions.toList())
    if (answer < 0) {
        throw PatchException("$patch: $definingClass->$name no longer asks about the repost chip the way it did.")
    }
    // A jump keeps its target on the original instruction, so a path that jumped there would miss the hook.
    if (implementation!!.instructions.any { it is BuilderOffsetInstruction && it.target.location.index == answer + 1 }) {
        throw PatchException("$patch: $definingClass->$name jumps past its repost chip answer.")
    }
    val register = (implementation!!.instructions[answer] as OneRegisterInstruction).registerA
    addInstructions(
        answer + 1,
        """
            invoke-static/range { v$register .. v$register }, Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->repostChipShown(I)Z
            move-result v$register
        """,
    )
}
