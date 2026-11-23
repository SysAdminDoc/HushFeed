/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

internal const val PROMO_SKIP = "Lapp/morphe/extension/tiktok/captions/PromoSkip;"

/**
 * TikTok's progress callback, several times a second while a video plays, with the video's id,
 * where it is and how long it is. Long-press controls, Block the author and Hide already seen
 * videos prepend to it as well.
 */
internal object PromoProgressFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;",
    name = "onPlayProgressChange",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
)

/**
 * Skip promo lines (#493): each tick goes to the extension first, which seeks past a caption line
 * holding a word from the Promo words list. p0 is the controller, p1 the id, then the position and
 * the length, a pair of registers each, so the range form reaches all six.
 */
internal fun MutableMethod.hookPromoSkip() {
    if (AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("Subtitle tools: $definingClass->$name is static, so it has no controller to seek.")
    }
    addInstruction(
        0,
        "invoke-static/range { p0 .. p5 }, $PROMO_SKIP->onProgress(Ljava/lang/Object;Ljava/lang/String;JJ)V",
    )
}
