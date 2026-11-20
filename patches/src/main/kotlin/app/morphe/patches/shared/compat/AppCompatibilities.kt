/*
 * Forked from:
 * https://github.com/RookieEnough/De-Vanced/blob/9ac979883a6b6eeab5cefb4fc5d955073f2f2bc2/patches/src/main/kotlin/app/morphe/patches/shared/compat/AppCompatibilities.kt
 * (GPL-3.0), by way of
 * https://github.com/icysymmetra/tiktok-patches-for-morphe/blob/ebf6296845cb889608cdc6681348ccb2e550f2bc/patches/src/main/kotlin/app/morphe/patches/shared/compat/AppCompatibilities.kt
 * That copy cites a ReVanced path that never held this file; `AppTarget` is Morphe's own API.
 *
 * Central Morphe `Compatibility` metadata so Morphe Manager shows human-readable app names.
 */
package app.morphe.patches.shared.compat

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object AppCompatibilities {
    private const val TIKTOK_COLOR = 0xFE2C55

    /**
     * SHA-256 of TikTok's signing certificate (CN=musical.ly): the "certificate SHA-256 digest"
     * that `apksigner verify --print-certs` prints for every retained vendor build. Morphe Manager
     * holds an APK picked from storage to it and warns before patching one another key signed,
     * which is the only check a user gets against a repackaged "TikTok mod".
     */
    const val TIKTOK_SIGNER_SHA256 = "9041803e91bcb814b4b4399fb5c85a91640b755e5e8ba76813814bf4cf2ab5ba"

    /** TikTok 47.9.2's version code. APKMirror carries its universal APK and a split bundle. */
    const val TIKTOK_4792_VERSION_CODE = 2024709020

    /** The version codes of every declared TikTok build. */
    val TIKTOK_VERSION_CODES = setOf(TIKTOK_4792_VERSION_CODE)

    /** TikTok's package name. */
    const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"

    /**
     * TikTok Lite's package name (#48). Lite is its own app, built from a thin copy of TikTok's
     * code with its own start-up, feed and screens, so a patch is declared for it only once it
     * has been made to work there.
     */
    const val TIKTOK_LITE_PACKAGE = "com.zhiliaoapp.musically.go"

    /** SHA-256 of TikTok Lite's signing certificate, as `apksigner verify --print-certs` prints it. */
    const val TIKTOK_LITE_SIGNER_SHA256 = "d7811ec4166fea6cc720ba66699dc84b584ac9e6986613a76d4e43d8cbe32b27"

    /** TikTok Lite 47.8.2's version code. APKMirror carries it as one universal APK. */
    const val TIKTOK_LITE_4782_VERSION_CODE = 470802

    /**
     * Targets: the TikTok global package, 47.9.2 only. Only the newest stable build is declared:
     * when a newer one is supported, the one before it is dropped in the same release, so every
     * patch is read against one fixture.
     */
    fun tiktok(): Array<Compatibility> = arrayOf(
        Compatibility(
            name = "TikTok",
            packageName = TIKTOK_PACKAGE,
            appIconColor = TIKTOK_COLOR,
            signatures = setOf(TIKTOK_SIGNER_SHA256),
            targets = listOf(
                AppTarget(version = "47.9.2", versionCode = TIKTOK_4792_VERSION_CODE),
            ),
        ),
    )

    /** TikTok Lite, 47.8.2 only, under the same newest-stable-build rule as TikTok. */
    fun tiktokLite(): Compatibility = Compatibility(
        name = "TikTok Lite",
        packageName = TIKTOK_LITE_PACKAGE,
        appIconColor = TIKTOK_COLOR,
        signatures = setOf(TIKTOK_LITE_SIGNER_SHA256),
        targets = listOf(
            AppTarget(version = "47.8.2", versionCode = TIKTOK_LITE_4782_VERSION_CODE),
        ),
    )

    /** TikTok and TikTok Lite, for the patches that work in both. */
    fun tiktokAndLite(): Array<Compatibility> = tiktok() + tiktokLite()

    /** Every app the bundle declares, for the checks that cover all of them. */
    fun declared(): List<Compatibility> = tiktok().toList() + tiktokLite()
}
