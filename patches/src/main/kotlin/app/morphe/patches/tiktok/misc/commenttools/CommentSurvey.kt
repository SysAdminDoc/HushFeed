/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val COMMENT_SURVEY_ITEM = "Lcom/ss/android/ugc/aweme/comment/experiment/CommentSurveyDataItem;"
internal const val COMMENT_SURVEY_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CommentSurvey;"
internal const val COMMENT_LIST_SURVEY = "Lcom/ss/android/ugc/aweme/comment/model/CommentListSurvey;"
internal const val AWEME_COMMENT_CONFIG = "Lcom/ss/android/ugc/aweme/feed/model/AwemeCommentConfig;"
private const val SURVEY_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/**
 * TikTok's comment survey resolver: the one method that answers the survey a video's comment list
 * may show. From 48.0.2 the survey can come with the video (its comment config's commentListSurvey)
 * or, as before, from the server's comment_survey setting, and both paths end in this method, which
 * answers a holder of the survey item, its templates and its source. A holder with no item is its
 * own answer for no survey (a static instance of the holder's class), so every reader already takes
 * it. It is 0IRn.LIZLLL(Aweme) returning 1G1b on 48.0.2; up to 47.9.3 the survey was a static
 * parameterless getter that answered the item or null (0GlX.LIZIZ on 47.0.3, 0FPt.LIZIZ on
 * 47.1.3, 0FPx.LIZIZ on 47.1.4, 0H4C.LIZIZ on 47.9.3).
 */
internal object CommentSurveyConfigFingerprint : Fingerprint(
    parameters = listOf(SURVEY_AWEME),
    // It reads the video's own survey off its comment config, which no other method does.
    filters = listOf(
        fieldAccess(definingClass = AWEME_COMMENT_CONFIG, name = "hasCommentListSurveyResponse", type = "Z"),
        fieldAccess(definingClass = AWEME_COMMENT_CONFIG, name = "commentListSurvey", type = COMMENT_LIST_SURVEY),
    ),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
            method.returnType != COMMENT_SURVEY_ITEM
    },
)

/**
 * The resolver's own no-survey answer: the one static field of its holder type, of that same type,
 * that the resolver loads. It's what the resolver answers for a video without a survey.
 */
internal fun Method.noCommentSurveyAnswer(): FieldReference {
    val holder = returnType
    val loads = implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == holder && it.type == holder }
        .distinctBy { "${it.definingClass}->${it.name}:${it.type}" }
    return loads.singleOrNull() ?: throw PatchException(
        "Comment tools: expected the survey resolver $definingClass->$name to load one empty " +
            "$holder, found ${loads.size}",
    )
}
