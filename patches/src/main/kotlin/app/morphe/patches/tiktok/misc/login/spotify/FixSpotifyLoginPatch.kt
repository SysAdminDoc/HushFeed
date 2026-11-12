/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.login.spotify

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.patches.tiktok.shared.guardAtEntry
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Fix Spotify login"
internal const val SPOTIFY_WEB_LOGIN = "Lapp/morphe/extension/tiktok/misc/SpotifyWebLogin;"

/**
 * TikTok's wrapper around `Activity.startActivityForResult(Intent, int)` (`X/03NM->LJIILJJIL` on
 * 47.7.3): static, the request code first, then the activity, the intent and the wrapper's call
 * context. It names the wrapped call in strings for its interceptor chain. The Spotify SDK in the
 * music feature dex sends its sign-in hand-off through it, and the feature dex itself is a zip
 * inside `lib/`, out of the patcher's reach, so this is the nearest point the patch can hold.
 * TikTok has wrappers of the same call for fragments and other callers, each with its own order of
 * parameters; this order is the activity one's.
 */
internal object ActivityStartForResultFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("I", "Landroid/app/Activity;", "Landroid/content/Intent;", "L"),
    strings = listOf("startActivityForResult", "(Landroid/content/Intent;I)V"),
)

@Suppress("unused")
val fixSpotifyLoginPatch = bytecodePatch(
    name = "Fix Spotify login",
    description = "Makes Add to Spotify work in the patched app. The Spotify app turns down a " +
        "patched TikTok, so you sign in on Spotify's own page instead. Works as soon as you " +
        "patch it in, with no switch.",
    default = true,
) {
    category("Settings")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Every other launch goes on as TikTok wrote it: the extension answers false for any
        // request that isn't Spotify's hand-off. p0 to p2 sit at the top of a 32-register frame.
        ActivityStartForResultFingerprint.method.guardAtEntry(
            PATCH,
            "invoke-static/range {p0 .. p2}, $SPOTIFY_WEB_LOGIN->openInstead(ILandroid/app/Activity;Landroid/content/Intent;)Z",
            "return-void",
        )
        registerForBuildDetails("enableSpotifyLoginFix")
    }
}
