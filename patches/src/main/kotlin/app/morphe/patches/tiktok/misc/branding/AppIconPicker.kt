/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.branding

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import java.io.File
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * TikTok's launcher entry, an alias of MainActivity. Deep links, notification taps and TikTok's
 * own start-up checks name it, so it stays on whatever icon is picked.
 */
internal const val TIKTOK_LAUNCHER = "com.ss.android.ugc.aweme.splash.SplashActivity"

/** The screen every icon in the picker opens. It shows nothing and hands the tap to [TIKTOK_LAUNCHER]. */
internal const val ICON_LAUNCH_ACTIVITY = "app.morphe.extension.tiktok.misc.AppIconLaunchActivity"

/** Each icon is a launcher alias named this plus its key, as the extension's AppIcons reads them. */
internal const val ICON_ALIAS_PREFIX = "app.morphe.extension.tiktok.misc.AppIcon."

/** Each icon's drawable is `@mipmap/` this plus its key. */
internal const val ICON_RESOURCE_PREFIX = "hushfeed_icon_"

/** The icon the options drew with a picture or a background color of their own. */
internal const val CUSTOM_ICON_KEY = "custom"

private const val MAIN_ACTION = "android.intent.action.MAIN"
private const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"

/** A style key as a name part: `white-on-black` is `white_on_black`. */
internal fun iconId(key: String) = key.replace('-', '_')

internal fun iconAliasName(key: String) = ICON_ALIAS_PREFIX + iconId(key)

internal fun iconResourceName(key: String) = ICON_RESOURCE_PREFIX + iconId(key)

/**
 * The icons the picker offers: every style drawn from TikTok's own icon, then the one the options
 * drew when they set a picture or a background color, which is then the one that starts on.
 */
internal fun pickerIconKeys(custom: Boolean): List<String> =
    IconStyle.entries.map { it.key } + listOfNotNull(CUSTOM_ICON_KEY.takeIf { custom })

/**
 * TikTok's one launcher entry, checked before anything is written so a refusal changes nothing.
 * The picker only knows how to stand in for [TIKTOK_LAUNCHER].
 */
internal fun pickerLauncherEntry(manifest: Document): Element {
    val entries = (manifest.getElementsByTagName("activity").elements() +
        manifest.getElementsByTagName("activity-alias").elements()).filter { it.isLauncherEntry() }
    val names = entries.map { it.getAttribute("android:name") }
    if (names != listOf(TIKTOK_LAUNCHER)) {
        throw PatchException(
            "Custom launcher icon: the icon picker expects TikTok's only home screen entry to be " +
                "$TIKTOK_LAUNCHER, and this TikTok lists ${names.ifEmpty { listOf("none") }.joinToString()}. " +
                "Nothing was changed. Patch with Pick the icon in Hushfeed off.",
        )
    }
    return entries.single()
}

/**
 * Lets Hushfeed's settings switch the home screen icon (Discussion #114).
 *
 * <p>Android lists an app on the home screen once for each enabled activity with the launcher
 * category, so every icon gets an alias of its own and only [enabled] starts on. TikTok's
 * [TIKTOK_LAUNCHER] can't be the one switched off: deep links, notification taps and its start-up
 * code name it, and a component that's off starts nothing. It stays on and only leaves the
 * launcher's list. Each alias opens [ICON_LAUNCH_ACTIVITY] rather than MainActivity, because
 * TikTok's start-up compares the launching component with [TIKTOK_LAUNCHER] and MainActivity and
 * treats anything else as a restore it routes to a fresh main screen. That screen sends the same
 * tap on to [TIKTOK_LAUNCHER], so TikTok sees the start it always did.
 */
internal fun addIconPicker(manifest: Document, keys: List<String>, enabled: String) {
    require(enabled in keys) { "$enabled isn't one of $keys" }
    val launcher = pickerLauncherEntry(manifest)
    val application = manifest.getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("Custom launcher icon: TikTok's manifest has no application element.")
    for (filter in launcher.getElementsByTagName("intent-filter").elements()) {
        filter.getElementsByTagName("category").elements()
            .filter { it.getAttribute("android:name") == LAUNCHER_CATEGORY }
            .forEach { filter.removeChild(it) }
    }
    val activity = manifest.createElement("activity")
    activity.setAttribute("android:name", ICON_LAUNCH_ACTIVITY)
    activity.setAttribute("android:exported", "false")
    activity.setAttribute("android:theme", "@android:style/Theme.NoDisplay")
    application.appendChild(activity)
    for (key in keys) {
        val alias = manifest.createElement("activity-alias")
        alias.setAttribute("android:name", iconAliasName(key))
        alias.setAttribute("android:targetActivity", ICON_LAUNCH_ACTIVITY)
        alias.setAttribute("android:exported", "true")
        alias.setAttribute("android:enabled", (key == enabled).toString())
        alias.setAttribute("android:icon", "@mipmap/${iconResourceName(key)}")
        alias.setAttribute("android:roundIcon", "@mipmap/${iconResourceName(key)}")
        launcher.getAttribute("android:label").takeIf { it.isNotEmpty() }?.let { alias.setAttribute("android:label", it) }
        val filter = manifest.createElement("intent-filter")
        filter.appendChild(manifest.createElement("action").apply { setAttribute("android:name", MAIN_ACTION) })
        filter.appendChild(manifest.createElement("category").apply { setAttribute("android:name", LAUNCHER_CATEGORY) })
        alias.appendChild(filter)
        application.appendChild(alias)
    }
}

/**
 * Where each picker icon goes beside one of TikTok's adaptive icons: the same folder, named for
 * the icon's key. Android 8 and up read these.
 */
internal fun pickerIconFile(adaptiveIcon: File, key: String): File =
    adaptiveIcon.parentFile.resolve("${iconResourceName(key)}.xml")

/**
 * Android 6 and 7 have no adaptive icons, so there each picker icon is TikTok's own picture,
 * through a one-layer drawable in the plain mipmap folder that names [icon].
 */
internal fun legacyPickerIcon(icon: String): String =
    "<layer-list xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
        "    <item android:drawable=\"$icon\" />\n" +
        "</layer-list>\n"

/**
 * The picker icon for [key], built from a fresh read of TikTok's [original] adaptive icon. Each
 * style draws TikTok's note as the patch does, and [CUSTOM_ICON_KEY] is the options' own icon:
 * the [chosen] style with their [picture] and [background].
 */
internal fun pickerIcon(
    original: File,
    layers: IconLayers,
    key: String,
    chosen: IconStyle,
    picture: String?,
    background: String?,
): Document {
    val icon = readXml(original)
    if (key == CUSTOM_ICON_KEY) {
        restyleAdaptiveIcon(icon, layers, chosen, picture, background)
    } else {
        restyleAdaptiveIcon(icon, layers, IconStyle.entries.single { it.key == key })
    }
    return icon
}

/** Writes a picker icon built in memory to [file], a file the patch adds. */
internal fun writeXml(document: Document, file: File) {
    file.parentFile.mkdirs()
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "utf-8")
    file.outputStream().use { transformer.transform(DOMSource(document), StreamResult(it)) }
}

/**
 * Turns on the picker's settings row, and brings in the screen the icons open. Only the picker
 * option runs it.
 */
internal fun appIconPickerPatch(active: () -> Boolean) = bytecodePatch {
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        if (!active()) return@execute
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAppIconPicker()V",
        )
    }
}
