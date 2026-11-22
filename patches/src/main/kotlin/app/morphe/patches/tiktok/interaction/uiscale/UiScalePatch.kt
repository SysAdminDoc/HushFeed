/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.uiscale

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.textsize.TextSizeModeLoadFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction

/**
 * UI scale (#477, ported from inxomnyaa's pull request).
 *
 * TikTok's text size manager rewrites the app's display density from its text size factor: at
 * startup for the app's resources (X.03aF's LIZLLL(Context) on 47.9.3, run by TuxInitTask) and
 * again for each activity (LJFF(Activity)). Both read the factor from LIZ()F and divide the
 * screen width in dp by it, so a smaller factor gives a smaller density and layouts, icons and
 * text all shrink together. UiScale scales the factor right where each rewrite reads it,
 * after Smaller text sizes has answered it, so the two compose.
 */
@Suppress("unused")
val uiScalePatch = bytecodePatch(
    name = "UI scale",
    description = "Makes TikTok's whole interface smaller, text and buttons alike, so more fits " +
        "on screen. Starts at 100%, which changes nothing. Pick a size in Hushfeed settings > " +
        "App, then restart TikTok.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Every rewrite's read is found before any is scaled, so a build missing one is left as
        // it was. On a declared build that's a failed patch; a forced build goes without.
        val reads = try {
            densityFactorReads(TextSizeModeLoadFingerprint.classDef)
        } catch (problem: PatchException) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            return@execute
        }
        reads.forEach { it.scale() }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableUiScale()V",
        )
    }
}
