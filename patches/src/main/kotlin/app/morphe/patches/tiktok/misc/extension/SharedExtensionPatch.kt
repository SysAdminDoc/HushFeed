/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/extension/ExtensionPatch.kt
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/extension/Hooks.kt
 */

/*
 * By way of
 * https://github.com/RookieEnough/De-Vanced/blob/9ac979883a6b6eeab5cefb4fc5d955073f2f2bc2/patches/src/main/kotlin/app/morphe/patches/tiktok/misc/extension/SharedExtensionPatch.kt
 * (GPL-3.0) and
 * https://github.com/icysymmetra/tiktok-patches-for-morphe/blob/ebf6296845cb889608cdc6681348ccb2e550f2bc/patches/src/main/kotlin/app/morphe/patches/tiktok/misc/extension/SharedExtensionPatch.kt
 * The tiktok-patches-for-morphe copy adds a second Forked from line naming a ReVanced
 * SharedExtensionPatch.kt that never existed.
 */
package app.morphe.patches.tiktok.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.misc.extension.ExtensionHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch as extendShared
import app.morphe.patches.tiktok.misc.diagnostics.buildDetailsPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MAIN_ACTIVITY_CLASS = "Lcom/ss/android/ugc/aweme/main/MainActivity;"
private const val HOST_APPLICATION_CLASS = "Lcom/ss/android/ugc/aweme/app/host/AwemeHostApplication;"
private const val JATO_INIT_TASK_CLASS = "Lcom/ss/android/ugc/aweme/legoImp/task/JatoInitTask;"
private const val STORE_REGION_INIT_TASK_CLASS = "Lcom/ss/android/ugc/aweme/legoImp/task/StoreRegionInitTask;"
private const val MEDIA_CACHE_CLASS = "Lapp/morphe/extension/tiktok/download/MediaCache;"

internal object MainActivityOnCreateFingerprint : Fingerprint(
    definingClass = MAIN_ACTIVITY_CLASS,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

internal object HostApplicationAttachBaseContextFingerprint : Fingerprint(
    definingClass = HOST_APPLICATION_CLASS,
    name = "attachBaseContext",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object JatoInitTaskRunFingerprint : Fingerprint(
    definingClass = JATO_INIT_TASK_CLASS,
    name = "run",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object StoreRegionInitTaskRunFingerprint : Fingerprint(
    definingClass = STORE_REGION_INIT_TASK_CLASS,
    name = "run",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

// The Home tab's long press into Hushfeed's settings follows the main activity from here; it
// stays inert unless Feed tab navigation is in the bundle.
private val initHook = ExtensionHook(
    fingerprint = MainActivityOnCreateFingerprint,
    postContextClassDescriptor = "Lapp/morphe/extension/tiktok/navigation/HomeTabSettingsShortcut;",
    postContextMethodName = "install",
)

private val hostApplicationInitHook = ExtensionHook(
    fingerprint = HostApplicationAttachBaseContextFingerprint,
    insertIndexResolver = { method ->
        method.indexOfFirstInstructionOrThrow {
            getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Landroid/app/Application;" &&
                    reference.name == "attachBaseContext" &&
                    reference.parameterTypes == listOf("Landroid/content/Context;")
            } == true
        } + 1
    },
    contextRegisterResolver = { "p1" },
    postContextClassDescriptor = MEDIA_CACHE_CLASS,
    postContextMethodName = "reconcileAsync",
)

private val jatoInitHook = ExtensionHook(
    fingerprint = JatoInitTaskRunFingerprint,
    contextRegisterResolver = { "p1" },
)

private val storeRegionInitHook = ExtensionHook(
    fingerprint = StoreRegionInitTaskRunFingerprint,
    contextRegisterResolver = { "p1" },
)

private val tiktokExtensionPatch = extendShared(
    extensionName = "tiktok",
    isYouTubeOrYouTubeMusic = false,
    hostApplicationInitHook,
    initHook,
    jatoInitHook,
    storeRegionInitHook,
)

val sharedExtensionPatch = bytecodePatch {
    dependsOn(buildDetailsPatch, tiktokExtensionPatch)
}
