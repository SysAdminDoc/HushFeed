/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.util.addInstructions

internal const val ON_THIS_DAY_CELL = "Lcom/ss/android/ugc/aweme/story/inbox/view/StoryOnThisDayInboxCell;"

/**
 * The On this day cell at the front of the Inbox's stories tray (#358). TikTok's PowerList cells
 * keep their names, and the bind is the cell's own override of PowerCell's. Every path through
 * it ends in TikTok drawing a cover: a memory's video, the phone's own photos from this day, or
 * a stock picture for an account with nothing to look back on.
 */
internal object StoryOnThisDayBindFingerprint : Fingerprint(
    definingClass = ON_THIS_DAY_CELL,
    name = "onBindItemView",
    returnType = "V",
)

/**
 * Hands the cell to the Inbox filter before TikTok binds it. The filter collapses the cell's view
 * with the switch on and gives it back its size with the switch off; TikTok's bind runs either
 * way, straight-line, so nothing branches in a method the tray binds on every scroll.
 */
internal fun MutableMethod.reportOnThisDayBind(patch: String) = addInstructions(
    0,
    callThroughLocals(
        patch,
        "invoke-static",
        "Lapp/morphe/extension/tiktok/inbox/InboxFilter;->onOnThisDayBound(Ljava/lang/Object;)V",
        objectIn("p0"),
    ),
)
