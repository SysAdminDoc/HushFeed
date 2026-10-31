/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstruction

internal const val AMOLED_LIVE_INPUT_EXTENSION = "Lapp/morphe/extension/tiktok/theme/AmoledLiveInput;"

/** The comment panel a viewer types in inside a LIVE room, real-named on 47.5.3. */
internal const val LIVE_COMMENT_INPUT_DIALOG = "Lcom/bytedance/android/livesdk/chatroom/ui/LiveEmojiInputDialogFragment;"

/**
 * The comment panel's view setup: the one onViewCreated of the fragment, the only one of the fragment.
 */
internal object LiveCommentInputCreatedFingerprint : Fingerprint(
    definingClass = LIVE_COMMENT_INPUT_DIALOG,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * The LIVE room's comment box, readable on AMOLED dark theme (#267).
 *
 * The black fill of the panel is what the palette rewrite gives the dark theme, but the typed
 * text and the hint inside the box keep a dark color of their own, so a viewer typed dark text on
 * black. This hands the panel's root view to the extension as TikTok builds it, and the extension
 * sets the box's text and hint light once the page is dark. Light mode keeps TikTok's colors. It
 * runs only as part of AMOLED dark theme.
 */
internal fun amoledLiveInputPatch() = bytecodePatch {
    dependsOn(sharedExtensionPatch)
    execute {
        val setup = LiveCommentInputCreatedFingerprint.method
        if (setup.implementation == null) throw PatchException("AMOLED dark theme: the LIVE comment panel has no setup code.")
        // First instruction, while p1 still holds the root view. The extension waits for the view
        // to finish its own setup before it recolors, so TikTok's colors can't land on top.
        setup.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $AMOLED_LIVE_INPUT_EXTENSION->onPanel(Landroid/view/View;)V",
        )
    }
}
