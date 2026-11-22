/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.uiscale

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.textsize.TextSizeModeLoadFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.ClassDef

/**
 * UI scale (#477, ported from inxomnyaa's pull request).
 *
 * TikTok's text size manager rewrites the app's display density from its text size factor: at
 * startup for the app's resources (X.03aF's LIZLLL(Context) on 47.9.3, run by TuxInitTask), on a
 * LIVE room's configuration change (LJFF(Activity)), and in the wrapper every activity's context
 * goes through (X.03Ji.invoke, which builds the activity's own configuration). Each reads the
 * factor from LIZ()F and divides the screen width in dp by it, so a smaller factor gives a smaller
 * density and layouts, icons and text all shrink together. The activity wrapper is what sizes the
 * screen, so scaling only the manager's reads changed nothing. UiScale scales the factor right
 * where each of them reads it, after Smaller text sizes has answered it, so the two compose.
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
        val classes = ArrayList<ClassDef>()
        classDefForEach { classes += it }
        val reads = try {
            densityFactorReads(TextSizeModeLoadFingerprint.classDef, classes.asSequence())
        } catch (problem: PatchException) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            return@execute
        }
        reads.scaleAll { mutableClassDefBy(it.definingClass).findMutableMethodOf(it) }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableUiScale()V",
        )
    }
}
