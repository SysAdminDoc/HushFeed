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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.misc.extension.ExtensionHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch as extendShared
import app.morphe.patches.tiktok.misc.diagnostics.buildDetailsPatch
import app.morphe.patches.tiktok.misc.diagnostics.ownClassesPatch
import app.morphe.patches.tiktok.misc.diagnostics.targetBuildPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.Method
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

/** TikTok Lite's application and launcher activity (#48): Lite starts up through its own classes. */
internal const val LITE_APPLICATION_CLASS = "Lcom/zhiliaoapp/musically/go/mini/MiniApplication;"
internal const val LITE_MAIN_ACTIVITY_CLASS = "Lcom/zhiliaoapp/musically/go/mini/MainActivity;"

internal object LiteApplicationAttachBaseContextFingerprint : Fingerprint(
    definingClass = LITE_APPLICATION_CLASS,
    name = "attachBaseContext",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object LiteMainActivityOnCreateFingerprint : Fingerprint(
    definingClass = LITE_MAIN_ACTIVITY_CLASS,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** The launcher activity's onCreate: TikTok's MainActivity, or Lite's (#48). */
internal fun launcherOnCreate(packageName: String): MutableMethod =
    if (packageName == AppCompatibilities.TIKTOK_LITE_PACKAGE) LiteMainActivityOnCreateFingerprint.method
    else MainActivityOnCreateFingerprint.method

private val TIKTOK_ONLY = setOf(AppCompatibilities.TIKTOK_PACKAGE)
private val LITE_ONLY = setOf(AppCompatibilities.TIKTOK_LITE_PACKAGE)

/** Right after the application's call to Application.attachBaseContext, so the base context is set. */
internal fun afterSuperAttachBaseContext(method: Method): Int = method.indexOfFirstInstructionOrThrow {
    getReference<MethodReference>()?.let { reference ->
        reference.definingClass == "Landroid/app/Application;" &&
            reference.name == "attachBaseContext" &&
            reference.parameterTypes == listOf("Landroid/content/Context;")
    } == true
} + 1

// The Home tab's long press into Hushfeed's settings follows the main activity from here; it
// stays inert unless Feed tab navigation is in the bundle.
private val initHook = ExtensionHook(
    fingerprint = MainActivityOnCreateFingerprint,
    postContextClassDescriptor = "Lapp/morphe/extension/tiktok/navigation/HomeTabSettingsShortcut;",
    postContextMethodName = "install",
    packageNames = TIKTOK_ONLY,
)

private val hostApplicationInitHook = ExtensionHook(
    fingerprint = HostApplicationAttachBaseContextFingerprint,
    insertIndexResolver = ::afterSuperAttachBaseContext,
    contextRegisterResolver = { "p1" },
    postContextClassDescriptor = MEDIA_CACHE_CLASS,
    postContextMethodName = "reconcileAsync",
    packageNames = TIKTOK_ONLY,
)

private val jatoInitHook = ExtensionHook(
    fingerprint = JatoInitTaskRunFingerprint,
    contextRegisterResolver = { "p1" },
    packageNames = TIKTOK_ONLY,
)

private val storeRegionInitHook = ExtensionHook(
    fingerprint = StoreRegionInitTaskRunFingerprint,
    contextRegisterResolver = { "p1" },
    packageNames = TIKTOK_ONLY,
)

// Lite has no Jato or store-region start-up tasks, and none of Hushfeed's saved-video cache
// yet, so the context is all its application and launcher hand over.
private val liteApplicationInitHook = ExtensionHook(
    fingerprint = LiteApplicationAttachBaseContextFingerprint,
    insertIndexResolver = ::afterSuperAttachBaseContext,
    contextRegisterResolver = { "p1" },
    packageNames = LITE_ONLY,
)

private val liteInitHook = ExtensionHook(
    fingerprint = LiteMainActivityOnCreateFingerprint,
    packageNames = LITE_ONLY,
)

private val tiktokExtensionPatch = extendShared(
    extensionName = "tiktok",
    isYouTubeOrYouTubeMusic = false,
    hostApplicationInitHook,
    initHook,
    jatoInitHook,
    storeRegionInitHook,
    liteApplicationInitHook,
    liteInitHook,
)

val sharedExtensionPatch = bytecodePatch {
    dependsOn(targetBuildPatch, buildDetailsPatch, ownClassesPatch, tiktokExtensionPatch)
}
