/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions

internal const val IM_NOTICE = "Lcom/ss/android/ugc/aweme/im/common/model/IMNoticeMsgStruct;"

/**
 * The Inbox's DM banner, DMCommonBannerAssem's update. Every DM common banner the server sends
 * comes through it with its IMNoticeMsgStruct (the Streak Pet invite, "Grow a Streak Pet
 * together!", as `streak_pet_invite_banner`, next to the AI emoji and photo sticker ones), and a
 * null notice is its own way out: it logs "notice is null" and posts DISMISS to the Inbox's banner
 * slot. The class and the struct keep their names, the method doesn't (yI on 48.0.2), so it's the
 * one void method there taking the struct that logs its entry.
 */
internal object DmCommonBannerUpdateFingerprint : Fingerprint(
    definingClass = "/DMCommonBannerAssem;",
    returnType = "V",
    parameters = listOf(IM_NOTICE),
    strings = listOf("updateBanner onCall, notice: "),
)

/**
 * Hands the update the extension's answer in place of its notice: the notice itself, or null for a
 * streak banner while Hide streak pop-ups is on, so the banner dismisses rather than shows. A
 * dismiss also takes down a streak banner that was already up. p1 sits high in a 44 register frame,
 * so the call is a range and the copy back a from16.
 */
internal fun MutableMethod.dismissStreakBanners(patch: String) {
    requireLocals(patch, 1)
    addInstructions(
        0,
        """
            invoke-static/range { p1 .. p1 }, Lapp/morphe/extension/tiktok/inbox/NotificationControls;->dmBannerNotice(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $IM_NOTICE
            move-object/from16 p1, v0
        """,
    )
}
