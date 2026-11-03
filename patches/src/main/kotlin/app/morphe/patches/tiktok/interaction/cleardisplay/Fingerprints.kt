/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/cleardisplay/Fingerprints.kt
 */
package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val MESSAGE = "Landroid/os/Message;"

internal object OnClearDisplayEventFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/feed/platform/panel/clearmode/ClearModePanelComponent;",
    name = "onClearModeEvent",
)

internal object ClearDisplayCellFingerprint : Fingerprint(
    definingClass = "/feed/adapter/VideoViewCell;",
    returnType = "V",
    parameters = listOf("Z", "Z"),
    strings = listOf("exit_clearmode", "scene_key_", "event_enter_clear_mode"),
)

/**
 * The body R8 outlined from PlayerController.onRenderFirstFrame: the static method on the same
 * class taking the controller and the render event. onRenderFirstFrame keeps its name, so the
 * event type is read off it, and there is exactly one such static on 46.2.3 (`LLILZIL`), 46.7.3
 * (`LJL`) and 46.8.3 (`LLJIJIL`). The name was once written here and it is different on all three.
 */
internal object OnRenderFirstFrameBodyFingerprint : Fingerprint(
    definingClass = "/feed/controller/PlayerController;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    custom = { method, classDef ->
        val event = classDef.methods
            .firstOrNull { it.name == "onRenderFirstFrame" && it.parameterTypes.size == 1 }
            ?.parameterTypes?.single()?.toString()
        event != null &&
            method.parameterTypes.map(CharSequence::toString) == listOf(classDef.type, event)
    },
)

/**
 * The one clear mode event this can still find by signature alone.
 *
 * On 46.2.3 exactly one method out of 1,872,612 matches, and its class exists only to log clear
 * mode: every caller is an onClearModeEvent, an onPageSelected or an onPageUnSelected. The other
 * two events are found inside that class rather than by their own bare signature, because that is
 * what the core and playtime fingerprints did and both had stopped matching anything at all.
 */
internal object ClearModeLogStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lcom/bytedance/common/utility/collection/WeakHandler;",
        "Z",
        "Ljava/lang/String;",
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
        "J",
        "I",
        "I",
    ),
    // The handler it takes is how it waits: it numbers a Message (101 on 47.6.3) and posts it a
    // second later. Message is Android's, so the write is named in full and the patcher's index
    // finds the methods that make it.
    filters = listOf(fieldAccess(definingClass = MESSAGE, name = "what", opcode = Opcode.IPUT)),
)

/**
 * TikTok's video pager telling its listeners a new video is picked (`LJIJJLI` on 47.5.3), which it
 * does as a swipe lets go and the pager starts settling. The boolean says whether it tells them at
 * all. Its own log line names it.
 */
internal object FeedPagerPageSelectedFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/common/widget/VerticalViewPager;",
    returnType = "V",
    parameters = listOf("I", "Z"),
    strings = listOf("VerticalViewPagercheckMultiInvokePageSelected>>>pos:"),
)
