/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val HOME_PAGE_UI_FRAME_SERVICE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/homepage/api/ui/HomePageUIFrameService;"

private object InflatesSearchIconFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Context;"),
    returnType = "Landroid/view/View;",
    filters = listOf(
        methodCall(
            definingClass = HOME_PAGE_UI_FRAME_SERVICE_DESCRIPTOR,
            name = "getInflatedSearchIcon",
            returnType = "Landroid/view/View;",
        ),
    ),
)

/**
 * The search icon over the shrunken video while comments are open (#371). Its one show-or-hide
 * method takes the answer in p1, checks the panel and teen mode, then shows the icon only when
 * p1 is non-zero. Nothing branches to its first instruction, so the hook goes in at index 0.
 */
internal object ShrinkVideoSearchIconVisibilityFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/entry/commenticon/assem/ShrinkVideoSearchIconAssem;",
    parameters = listOf("Z"),
    returnType = "V",
    strings = listOf("title_panel"),
)

/**
 * Hands the icon's show-or-hide answer to the switch before TikTok reads it. A declared build
 * without the icon fails the patch; any other build keeps the toolbar half and leaves this out.
 */
internal fun hideShrinkVideoSearchIcon(versionName: String): Boolean {
    val method = ShrinkVideoSearchIconVisibilityFingerprint.methodOrNull
    if (method == null) {
        if (versionName in declaredVersions()) {
            throw PatchException("Hide feed search button: no search icon over the comments video on $versionName.")
        }
        println("[Hide feed search button] Left out the search icon over the comments video on $versionName.")
        return false
    }
    method.addInstructions(
        0,
        """
            invoke-static {p1}, Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;->shrinkVideoSearchIconShown(I)I
            move-result p1
        """,
    )
    return true
}

private fun resolveSearchEnabledBoundary(): MutableMethod {
    val controllerSuperclasses = mutableSetOf<String>()
    for (match in InflatesSearchIconFingerprint.matchAll()) {
        val classDef = match.originalClassDef
        val hasVisibilityProtocol = classDef.methods.any { method ->
            method.parameterTypes == listOf("I") && method.returnType == "V"
        }
        if (hasVisibilityProtocol) {
            classDef.superclass?.let(controllerSuperclasses::add)
        }
    }

    if (controllerSuperclasses.size != 1) {
        throw PatchException(
            "Hide feed search button: expected one shared search protocol base, " +
                "found ${controllerSuperclasses.size}.",
        )
    }

    val superclass = controllerSuperclasses.single()
    val matches = mutableClassDefBy(superclass).methods.filter { method ->
        method.name == "enabled" && method.parameterTypes.isEmpty() && method.returnType == "Z"
    }
    if (matches.size != 1) {
        throw PatchException(
            "Hide feed search button: expected one search enabled boundary, found ${matches.size}.",
        )
    }
    return matches.single()
}

@Suppress("unused")
val hideFeedSearchButtonPatch = bytecodePatch(
    name = "Hide feed search button",
    description = "Removes the search button from the top right of the feed and the one over " +
        "the video while comments are open. Starts off. Turn it on in Hushfeed settings > Feed screen.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedSearchButton()V",
        )
        resolveSearchEnabledBoundary().overrideToolbarButtonEnabled(
            "hideFeedSearchButtonEnabled",
        )
        hideShrinkVideoSearchIcon(packageMetadata.versionName)
    }
}
