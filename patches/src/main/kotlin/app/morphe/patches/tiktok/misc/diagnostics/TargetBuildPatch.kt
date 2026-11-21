/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.inbox.TOKEN_RESULT
import com.android.tools.smali.dexlib2.iface.ClassDef

/**
 * Obfuscated methods that patches call by name, as the supported compile of TikTok 47.9.3 names
 * them. TikTok can publish a second, separately compiled build under the same version code:
 * APKMirror added an arm64-v8a-only 47.7.3 APK on 2026-11-12 whose R8 names differ almost
 * everywhere, so a handful of patches failed on it and others would have wired to the wrong
 * methods (#402). A retarget swaps these for methods the new compile's patches call by name.
 */
internal val TARGET_BUILD_ANCHORS = listOf(
    TOKEN_RESULT,
    "LX/03cM;->LJIIIIZZ(IILjava/lang/String;Ljava/lang/String;)V",
    "LX/036Y;->LIZJ()Lcom/ss/android/ugc/aweme/ILoginService;",
)

/** The APKMirror files that carry the supported compile, as the refusal names them. */
internal const val TARGET_BUILD_FILES = "the bundle (.apkm) or the APK marked arm64-v8a + armeabi-v7a"

/**
 * The anchors for one app. TikTok Lite (#48) is compiled apart from TikTok, so TikTok's names
 * mean nothing there, and no Lite patch calls a method by its obfuscated name yet: a Lite build
 * has nothing to check until one does.
 */
internal fun targetBuildAnchors(packageName: String): List<String> =
    if (packageName == AppCompatibilities.TIKTOK_LITE_PACKAGE) emptyList() else TARGET_BUILD_ANCHORS

/** The TikTok anchors this APK doesn't define, by class and exact method signature. */
internal fun missingTargetAnchors(lookup: (String) -> ClassDef?): List<String> =
    missingTargetAnchors(TARGET_BUILD_ANCHORS, lookup)

/** The [anchors] this APK doesn't define, by class and exact method signature. */
internal fun missingTargetAnchors(anchors: List<String>, lookup: (String) -> ClassDef?): List<String> =
    anchors.filter { anchor ->
        val owner = anchor.substringBefore("->")
        val member = anchor.substringAfter("->")
        lookup(owner)?.methods?.none { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == member } ?: true
    }

internal fun checkTargetBuild(version: String?, lookup: (String) -> ClassDef?) =
    checkTargetBuild(version, TARGET_BUILD_ANCHORS, lookup)

internal fun checkTargetBuild(version: String?, anchors: List<String>, lookup: (String) -> ClassDef?) {
    if (missingTargetAnchors(anchors, lookup).isEmpty()) return
    val release = version?.let { " $it" }.orEmpty()
    throw PatchException(
        "This TikTok$release file is a different build from the one Hushfeed was made for, and its patches " +
            "would land in the wrong places. APKMirror can list more than one file per release. " +
            "Patch $TARGET_BUILD_FILES instead."
    )
}

/** Runs first under the shared extension, so the wrong compile stops before any patch touches it. */
internal val targetBuildPatch = patch {
    execute {
        checkTargetBuild(packageMetadata.versionName, targetBuildAnchors(packageMetadata.packageName)) {
            classDefByOrNull(it)
        }
    }
}
