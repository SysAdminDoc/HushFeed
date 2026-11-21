/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry

internal const val SHARE_ANY_COMMENT_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/ShareAnyComment;"
internal const val SHARE_BLOCK_COMMENT = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
internal const val SHARE_BLOCK_ACL = "Lcom/ss/android/ugc/aweme/comment/model/CommentShareACL;"

/**
 * A comment's "can't be sent to a chat" answer from TikTok's server, 0 when it can be. The menu a
 * long press opens and the comment's action sheet ask it (47.9.3 `LX/17ly.LIZ`) after the comment
 * status check, so a deleted or held comment stays out whatever it answers. The model keeps its
 * names because Gson fills it.
 */
internal object CommentDmShareBlockFingerprint : Fingerprint(
    definingClass = SHARE_BLOCK_COMMENT,
    name = "getDisableShareToDmReason",
    returnType = "I",
    parameters = emptyList(),
)

/**
 * The code of a comment's share ACL, 0 when it can be shared outside TikTok. TikTok's comment
 * share check (`ShareExtServiceImpl`'s Comment to Z method) and the long-press panel's check on
 * the share info it fetches again before sharing both read it here. The link that gets shared is
 * the video's own link with the comment's id added, built on the phone.
 */
internal object CommentShareAclCodeFingerprint : Fingerprint(
    definingClass = SHARE_BLOCK_ACL,
    name = "getCode",
    returnType = "I",
    parameters = emptyList(),
)

/** Answers 0, TikTok's "can share", while Share any comment is on (#481). */
internal fun MutableMethod.answerShareable() {
    guardAtEntry(
        "Comment tools",
        "invoke-static {}, $SHARE_ANY_COMMENT_CLASS_DESCRIPTOR->allow()Z",
        "const/4 v0, 0x0\nreturn v0",
    )
}
