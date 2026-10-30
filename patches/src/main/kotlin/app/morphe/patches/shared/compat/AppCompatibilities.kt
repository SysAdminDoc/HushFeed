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

    /** TikTok 47.5.3's version code. APKMirror carries its universal APK and a split bundle. */
    const val TIKTOK_4753_VERSION_CODE = 2024705030

    /** The version codes of every declared TikTok build. */
    val TIKTOK_VERSION_CODES = setOf(TIKTOK_4753_VERSION_CODE)

    /**
     * Targets: the TikTok global package, 47.5.3 only. Only the newest stable build is declared:
     * when a newer one is supported, the one before it is dropped in the same release, so every
     * patch is read against one fixture.
     */
    fun tiktok(): Array<Compatibility> = arrayOf(
        Compatibility(
            name = "TikTok",
            packageName = "com.zhiliaoapp.musically",
            appIconColor = TIKTOK_COLOR,
            signatures = setOf(TIKTOK_SIGNER_SHA256),
            targets = listOf(
                AppTarget(version = "47.5.3", versionCode = TIKTOK_4753_VERSION_CODE),
            ),
        ),
    )

    /** PineDrama's package, TikTok's drama app. */
    const val PINEDRAMA_PACKAGE = "com.ss.android.ttmd.video"

    /**
     * SHA-256 of PineDrama's signing certificate (CN=TikTok Drama), as `apksigner verify
     * --print-certs` prints it for the APKMirror 47.1.4, 47.4.3 and 47.5.3 bundles.
     */
    const val PINEDRAMA_SIGNER_SHA256 = "e97ca0c8835c5a9e4f7702a0382b103ede9c5d51e5194c8db54f4c8833f54e18"

    /**
     * PineDrama 47.5.3's version code, the same as TikTok 47.5.3's. It is PineDrama's newest stable
     * build on APKMirror (2026-10-23), and like TikTok's it is the only one declared.
     */
    const val PINEDRAMA_4753_VERSION_CODE = 2024705030

    /** The version codes of every declared build of either app, for the patches that read one off the manifest. */
    val DECLARED_VERSION_CODES = TIKTOK_VERSION_CODES + PINEDRAMA_4753_VERSION_CODE

    /**
     * PineDrama 47.5.3 (#156). It's built from TikTok's 47.5.3 code, so the patches whose anchors it
     * keeps apply to it. Only those declare it: the rest anchor on TikTok's feed, LIVE, inbox or
     * resources, which PineDrama changed or dropped. Each of those patches has to match both
     * TikTok's declared build and this one.
     */
    fun pineDrama(): Array<Compatibility> = arrayOf(
        Compatibility(
            name = "PineDrama",
            packageName = PINEDRAMA_PACKAGE,
            appIconColor = TIKTOK_COLOR,
            signatures = setOf(PINEDRAMA_SIGNER_SHA256),
            targets = listOf(
                AppTarget(version = "47.5.3", versionCode = PINEDRAMA_4753_VERSION_CODE),
            ),
        ),
    )

    /** For a patch checked on both apps: TikTok's targets and PineDrama's. */
    fun tiktokAndPineDrama(): Array<Compatibility> = tiktok() + pineDrama()
}
