/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.launcherOnCreates
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.releaseResourceTable
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Hushfeed's own settings screen, for apps without a TikTok screen to show the page in. */
internal const val OWN_SETTINGS_ACTIVITY = "app.morphe.extension.tiktok.settings.HushfeedSettingsActivity"

private const val LAUNCHER_SHORTCUT_CLASS = "Lapp/morphe/extension/tiktok/settings/LauncherSettingsShortcut;"

internal object OwnSettingsActivityFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/tiktok/settings/TikTokActivityHook;",
    name = "ownSettingsActivity",
    returnType = "Z",
    parameters = emptyList(),
)

/**
 * Declares [OWN_SETTINGS_ACTIVITY] in the manifest, unexported: only the app's own intents and
 * its launcher shortcut open it. Its theme is the device's own without an action bar, since the
 * page paints its own background and bars.
 */
internal fun declareOwnSettingsActivity(xml: Document) {
    val application = xml.getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("Settings: the manifest has no application")
    val activities = xml.getElementsByTagName("activity")
    val declared = (0 until activities.length)
        .map { activities.item(it) as Element }
        .any { it.getAttribute("android:name") == OWN_SETTINGS_ACTIVITY }
    if (declared) return
    val activity = xml.createElement("activity")
    activity.setAttribute("android:name", OWN_SETTINGS_ACTIVITY)
    activity.setAttribute("android:exported", "false")
    activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.NoActionBar")
    activity.setAttribute("android:windowSoftInputMode", "adjustResize")
    application.appendChild(activity)
}

/** Whether this APK is TikTok Lite (#48), whose settings page lives in [OWN_SETTINGS_ACTIVITY]. */
internal fun isTikTokLite(packageName: String): Boolean = packageName == AppCompatibilities.TIKTOK_LITE_PACKAGE

/** Declares the settings screen in TikTok Lite's manifest, and leaves TikTok's alone. */
internal val ownSettingsActivityPatch = patch {
    execute {
        if (!isTikTokLite(packageMetadata.packageName)) return@execute
        document("AndroidManifest.xml").use { xml -> declareOwnSettingsActivity(xml) }
        releaseResourceTable()
    }
}

/**
 * TikTok Lite's half of the Settings patch. Lite has none of the TikTok screens the page rides on
 * (the ad personalisation screen, Settings and privacy's Open Debug row, the For You tab), so the
 * page opens in Hushfeed's own screen, and the way in is "Open Hushfeed settings" on the app
 * icon's long-press menu, published as Lite's main screen opens (whichever of its two activities
 * that is). Settings registers after this returns, as execute's last statement for TikTok and
 * Lite alike, so a run that fails partway doesn't list it as applied.
 */
internal fun hostSettingsInOwnActivity() {
    OwnSettingsActivityFingerprint.method.returnEarly(true)
    launcherOnCreates(AppCompatibilities.TIKTOK_LITE_PACKAGE).forEach { onCreate ->
        onCreate.addInstruction(0, "invoke-static/range { p0 .. p0 }, $LAUNCHER_SHORTCUT_CLASS->publish(Landroid/content/Context;)V")
    }
    // Lite's start-up ShortCutTask clears every dynamic shortcut when its own are switched off,
    // which took Open Hushfeed settings off the icon a moment after it went on, and otherwise
    // pushes its own four ahead of it. Each method that clears, replaces or pushes them puts it
    // back on top on its way out.
    invokeSitesOf(SHORTCUT_CLEARS).map { it.owner.type to it.method }.distinct().forEach { (owner, method) ->
        val clearer = mutableClassDefBy(owner).findMutableMethodOf(method)
        clearer.findInstructionIndicesReversedOrThrow { opcode in RETURNS }.forEach { index ->
            clearer.addInstructionsAtControlFlowLabel(index, "invoke-static {}, $LAUNCHER_SHORTCUT_CLASS->republish()V")
        }
    }
}

/**
 * The ShortcutManager calls that take every dynamic shortcut away, Hushfeed's included, or push
 * one of the app's own ahead of it. The icon's menu shows the first four, and Lite has four.
 */
internal val SHORTCUT_CLEARS = setOf(
    "Landroid/content/pm/ShortcutManager;->removeAllDynamicShortcuts()V",
    "Landroid/content/pm/ShortcutManager;->setDynamicShortcuts(Ljava/util/List;)Z",
    "Landroid/content/pm/ShortcutManager;->pushDynamicShortcut(Landroid/content/pm/ShortcutInfo;)V",
)

private val RETURNS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT)
