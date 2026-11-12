/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patches.tiktok.interaction.systemfont.hookWebPageCallbacks
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly

internal const val AMOLED_WEB_PAGES_EXTENSION = "Lapp/morphe/extension/tiktok/theme/AmoledWebPages;"

/**
 * TikTok's web pages (Activity center, Watch history, Comment history) in [color] wherever their
 * dark scheme is gray (#263).
 *
 * Those pages are web pages in a WebView, so the resource side never reaches them, and Activity
 * center's card kept TikTok's dark gray. This bakes the color into the extension and hands it
 * every TikTok page as it finishes loading, the way Use system font hands it the page for its
 * font. It runs only as part of AMOLED dark theme, with that patch's color.
 */
internal fun amoledWebPagesPatch(color: () -> String?) = bytecodePatch {
    dependsOn(sharedExtensionPatch)
    execute {
        val argb = composeArgb(amoledColor(color())).toInt()
        val extension = mutableClassDefBy(AMOLED_WEB_PAGES_EXTENSION)
        val stub = extension.methods.single { it.name == "color" && it.parameterTypes.isEmpty() }
        val filled = stub.cloneMutable(additionalRegisters = 1)
        extension.methods.remove(stub)
        extension.methods.add(filled)
        filled.returnEarly(argb)
        hookWebPageCallbacks(
            "AMOLED dark theme",
            "$AMOLED_WEB_PAGES_EXTENSION->onPage(Landroid/webkit/WebView;Ljava/lang/String;)V",
        )
    }
}
