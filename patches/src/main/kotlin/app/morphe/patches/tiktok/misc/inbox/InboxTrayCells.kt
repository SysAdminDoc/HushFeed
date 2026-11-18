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

internal const val INBOX_LIVE_CELL = "Lcom/ss/android/ugc/aweme/inbox/adapter/InboxLiveRVCell;"
internal const val INBOX_LIVE_DOUBLE_CELL = "Lcom/ss/android/ugc/aweme/inbox/adapter/InboxLiveDoubleRVCell;"
internal const val WIDGET_GUIDE_CELL = "Lcom/ss/android/ugc/aweme/story/widget/guide/StoryWidgetGuideInboxCell;"
internal const val STORY_INBOX_CELL = "Lcom/ss/android/ugc/aweme/story/inbox/view/StoryInboxCell;"

/**
 * The LIVE circles in the Inbox's stories tray (#449): one account going LIVE, or the cell that
 * stacks several. TikTok mixes in LIVEs from accounts the viewer doesn't follow, and its own item
 * answers that through `isUnfollow()`, so the extension asks the item rather than the user model.
 */
internal object InboxLiveBindFingerprint : Fingerprint(
    definingClass = INBOX_LIVE_CELL,
    name = "onBindItemView",
    returnType = "V",
)

internal object InboxLiveDoubleBindFingerprint : Fingerprint(
    definingClass = INBOX_LIVE_DOUBLE_CELL,
    name = "onBindItemView",
    returnType = "V",
)

/**
 * A story circle in the Inbox's tray (#462). Its item answers `isUnfollow()` like the LIVE ones, so
 * recommended stories from accounts the viewer doesn't follow can be told from the rest.
 */
internal object StoryInboxBindFingerprint : Fingerprint(
    definingClass = STORY_INBOX_CELL,
    name = "onBindItemView",
    returnType = "V",
)

/** The Widget circle at the end of the stories tray, which offers TikTok's home screen widget (#449). */
internal object StoryWidgetGuideBindFingerprint : Fingerprint(
    definingClass = WIDGET_GUIDE_CELL,
    name = "onBindItemView",
    returnType = "V",
)

/**
 * Hands the LIVE cell and the item it's about to show to the Inbox filter before TikTok binds it.
 * Both cell classes take the same call; a recycled cell gets asked again on every bind, so a
 * followed account's LIVE that lands in a cell a suggested one used gets its size back.
 */
internal fun MutableMethod.reportInboxLiveBind(patch: String) = addInstructions(
    0,
    callThroughLocals(
        patch,
        "invoke-static",
        "Lapp/morphe/extension/tiktok/inbox/InboxFilter;->onLiveCellBound(Ljava/lang/Object;Ljava/lang/Object;)V",
        objectIn("p0"),
        objectIn("p1"),
    ),
)

/** Hands a story cell and its item to the Inbox filter before TikTok binds it, the way the LIVE ones go. */
internal fun MutableMethod.reportInboxStoryBind(patch: String) = addInstructions(
    0,
    callThroughLocals(
        patch,
        "invoke-static",
        "Lapp/morphe/extension/tiktok/inbox/InboxFilter;->onStoryCellBound(Ljava/lang/Object;Ljava/lang/Object;)V",
        objectIn("p0"),
        objectIn("p1"),
    ),
)

/** Hands the Widget cell to the Inbox filter before TikTok binds it, the way On this day's is. */
internal fun MutableMethod.reportWidgetGuideBind(patch: String) = addInstructions(
    0,
    callThroughLocals(
        patch,
        "invoke-static",
        "Lapp/morphe/extension/tiktok/inbox/InboxFilter;->onWidgetGuideBound(Ljava/lang/Object;)V",
        objectIn("p0"),
    ),
)
