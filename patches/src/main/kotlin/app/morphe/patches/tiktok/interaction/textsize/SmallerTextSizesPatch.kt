/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.textsize

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction

/**
 * Smaller text sizes (#476, ported from inxomnyaa's pull request).
 *
 * TikTok's Text size page (Settings and privacy > Accessibility) has a slider of four modes, 0 for
 * its smallest and default size to 3. The mode becomes a factor (1.0, 1.05, 1.1, 1.1 from X.03aF's
 * LIZ()F on 47.9.3) that the startup density rewrite (LIZLLL(Context), from TuxInitTask) and the
 * per-activity one (LJFF(Activity)) scale the whole interface by. This adds three positions at
 * the slider's left end: the page and its confirm callback go through SmallerTextSizes, which
 * hands TikTok mode 0 for each of them and keeps which one was picked, and both factor methods
 * answer that stop's factor while TikTok is on mode 0.
 */
@Suppress("unused")
val smallerTextSizesPatch = bytecodePatch(
    name = "Smaller text sizes",
    description = "Adds three stops below the smallest size on TikTok's own Text size slider, " +
        "for smaller text and more on screen. Starts off. Turn it on in Hushfeed settings > App, " +
        "then pick a stop in Settings and privacy > Accessibility > Text size and restart TikTok.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Every hook point is found before any is written, so a build missing one is left as
        // it was. On a declared build that's a failed patch; a forced build goes without.
        val sites = try {
            textSizeSites(
                manager = TextSizeModeLoadFingerprint.classDef,
                page = TextSizePageCreateFingerprint.classDef,
                create = TextSizePageCreateFingerprint.method,
                viewCreated = TextSizePageViewCreatedFingerprint.method,
                confirm = TextSizeConfirmFingerprint.method,
                classBy = { mutableClassDefBy(it) },
            )
        } catch (problem: PatchException) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            return@execute
        }

        sites.factors.forEach { (it as MutableMethod).answerExtraStop(sites.mode) }
        // Highest index first, so an earlier insert never moves a later site.
        sites.sliderTop.sortedByDescending { it.index }.forEach { it.mapThrough("sliderMax") }
        sites.pageMode.mapThrough("modeToPosition")
        sites.preview.mapThrough("positionToMode")
        sites.store.mapThrough("commitPosition")

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSmallerTextSizes()V",
        )
    }
}
