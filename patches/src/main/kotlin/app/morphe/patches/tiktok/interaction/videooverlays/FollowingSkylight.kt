/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val SKYLIGHT_VIEW_MODEL = "Lcom/ss/android/ugc/aweme/base/vm/FeedSkylightContainerViewModel;"
internal const val SKYLIGHT_TOGGLE_LOG = "toggleSkylightListShowStatus: isShow: "

/**
 * The one place the Following tab's LIVE and stories row (TikTok's "skylight") opens or folds:
 * a static `(viewModel, isShow, enterMethod, needAnim, isRefresh, toggleCleanMode, String, mask)Z`
 * on the row's view model, called when the row's data arrives and from every swipe and clear
 * display toggle. The view model keeps its real name, the method is renamed on every build (Y23
 * on 47.5.3, H23 on 47.6.3, which also started returning a boolean), and it logs its own isShow,
 * which nothing else in the class does.
 */
internal object FollowingSkylightToggleFingerprint : Fingerprint(
    definingClass = SKYLIGHT_VIEW_MODEL,
    returnType = "Z",
    parameters = listOf(SKYLIGHT_VIEW_MODEL, "Z", "Ljava/lang/String;", "Z", "Z", "Z", "Ljava/lang/String;", "I"),
    strings = listOf(SKYLIGHT_TOGGLE_LOG),
    custom = { method, _ -> isSkylightToggle(method) },
)

internal fun isSkylightToggle(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.definingClass == SKYLIGHT_VIEW_MODEL &&
        method.returnType == "Z" && method.parameterTypes.map(CharSequence::toString) ==
        listOf(SKYLIGHT_VIEW_MODEL, "Z", "Ljava/lang/String;", "Z", "Z", "Z", "Ljava/lang/String;", "I")

/**
 * Hands isShow (p1) to the Following row switch before the toggle reads it. With the switch on
 * the row is always told to fold, which is how TikTok leaves it when there is nothing to show,
 * so no empty band stays behind; with it off isShow comes back as it went in. Straight-line on
 * purpose: this runs on every swipe (#264).
 */
internal fun MutableMethod.keepFollowingSkylightFolded(patch: String) {
    if (!AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.getOrNull(1)?.toString() != "Z") {
        throw PatchException("$patch: $definingClass->$name no longer takes isShow second.")
    }
    addInstructions(
        0,
        """
            invoke-static {p1}, Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->skylightShown(Z)Z
            move-result p1
        """,
    )
}
