/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction

internal const val LIVE_COMMENT_BOX_EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveCommentBox;"

/** The comment panel a viewer types in inside a LIVE room, real-named on 47.5.3. */
internal const val LIVE_COMMENT_INPUT_DIALOG = "Lcom/bytedance/android/livesdk/chatroom/ui/LiveEmojiInputDialogFragment;"

/** The same text box in the panel a subscriber space post is written in, real-named on 47.6.3. */
internal const val SUB_SPACE_INPUT_DIALOG = "Lcom/bytedance/android/livesdk/subcription/subspaceeditor/SubEmojiInputDialogFragment;"

/** The comment panel's view setup, the one onViewCreated of the fragment. */
internal object LiveCommentInputCreatedFingerprint : Fingerprint(
    definingClass = LIVE_COMMENT_INPUT_DIALOG,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/** The subscriber space panel's view setup, the one onViewCreated of the fragment. */
internal object SubSpaceInputCreatedFingerprint : Fingerprint(
    definingClass = SUB_SPACE_INPUT_DIALOG,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * A LIVE panel's text box, readable under Dark LIVE panels (#267, #323).
 *
 * The switch gives the panel's fill the dark theme, but the typed text comes from a color every
 * TikTok theme sets to the same dark ink, so a viewer typed dark text on a dark bar. This hands the
 * panel's root view to the extension as TikTok builds it, and the extension sets the box's text and
 * hint light once the room is dark. A room the switch leaves light keeps TikTok's colors.
 */
internal fun hookLiveCommentBox(setup: MutableMethod) {
    if (setup.implementation == null) throw PatchException("LIVE controls: a LIVE text panel has no setup code.")
    // First instruction, while p1 still holds the root view. The extension waits for the view
    // to finish its own setup before it recolors, so TikTok's colors can't land on top.
    setup.addInstruction(
        0,
        "invoke-static/range { p1 .. p1 }, $LIVE_COMMENT_BOX_EXTENSION->onPanel(Landroid/view/View;)V",
    )
}
