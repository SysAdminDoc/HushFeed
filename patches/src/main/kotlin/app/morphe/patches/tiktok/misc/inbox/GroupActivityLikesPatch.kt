/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags

internal const val NOTIFICATION_RESULT_VM_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationResultViewModel;"
internal const val NOTICE_LISTS_RESPONSE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/notice/repo/list/bean/NoticeListsResponse;"
private const val LIKE_BUNDLER_DESCRIPTOR = "Lapp/morphe/extension/tiktok/inbox/ActivityLikeBundler;"

/**
 * The view model's page handler (47.7.3 `i53`): it takes the parsed notice/multi response and
 * the load reason, an enum whose LOAD_MORE appends a page and whose other values rebuild the
 * list. It is the class's only void method of (response, object) and runs before the rows are
 * deduplicated, wrapped into list items and published.
 */
internal object NotificationPageHandlerFingerprint : Fingerprint(
    definingClass = NOTIFICATION_RESULT_VM_DESCRIPTOR,
    returnType = "V",
    parameters = listOf(NOTICE_LISTS_RESPONSE_DESCRIPTOR, "L"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

@Suppress("unused")
val groupActivityLikesPatch = bytecodePatch(
    name = "Group likes per comment",
    description = "Shows one row in the Inbox's Likes list for each comment or video people " +
        "liked, with everyone counted, instead of a new row every few likes. Starts off. Turn it " +
        "on in Hushfeed settings > Inbox.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableBundleActivityLikes()V",
        )
        NotificationPageHandlerFingerprint.method.bundleLikesBeforePage()
        // A later page can add likes to a row already on screen; the bundler needs that row's
        // list view to redraw it, which the cell's bind hands over with its item.
        InboxNotificationNormalBind.method.apply {
            check(parameterTypes.size == 1 && returnType == "V") {
                "Group likes per comment: the notification cell's bind changed shape"
            }
            addInstructions(
                0,
                "invoke-static/range { p0 .. p1 }, $LIKE_BUNDLER_DESCRIPTOR->" +
                    "onRowBound(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }
    }
}

/** Hands (view model, response, reason) to the bundler before the native handler reads the page. */
internal fun MutableMethod.bundleLikesBeforePage() {
    check(definingClass == NOTIFICATION_RESULT_VM_DESCRIPTOR && returnType == "V" &&
        parameterTypes.map(CharSequence::toString).let { it.size == 2 && it[0] == NOTICE_LISTS_RESPONSE_DESCRIPTOR }
    ) { "Group likes per comment: the page handler changed shape" }
    // p0..p2 are consecutive, so the range form reaches them at any register count.
    addInstructions(
        0,
        "invoke-static/range { p0 .. p2 }, $LIKE_BUNDLER_DESCRIPTOR->" +
            "onPage(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
    )
}
