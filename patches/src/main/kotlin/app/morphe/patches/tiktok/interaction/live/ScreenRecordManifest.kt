/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.PatchException
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val SCREEN_RECORD_SERVICE = "app.morphe.extension.tiktok.live.ScreenRecordService"
internal const val SCREEN_RECORD_CONSENT = "app.morphe.extension.tiktok.live.ScreenRecordConsentActivity"

private val SCREEN_RECORD_PERMISSIONS = listOf(
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
)

/**
 * Declares what recording a LIVE's screen needs (#37): the foreground service of type
 * mediaProjection that Android requires while the screen is captured, the see-through screen that
 * asks for the viewer's consent, and the two permissions the service needs on Android 14 and later
 * (TikTok already holds both; they are added only when a build lacks them).
 */
internal fun declareScreenRecording(xml: Document) {
    val manifest = xml.documentElement
    val declared = (0 until xml.getElementsByTagName("uses-permission").length)
        .map { (xml.getElementsByTagName("uses-permission").item(it) as Element).getAttribute("android:name") }
        .toSet()
    SCREEN_RECORD_PERMISSIONS.filter { it !in declared }.forEach { permission ->
        val element = xml.createElement("uses-permission")
        element.setAttribute("android:name", permission)
        manifest.insertBefore(element, manifest.firstChild)
    }
    val application = xml.getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("LIVE screen recording: the manifest has no application")
    val named = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .map { it.getAttribute("android:name") }
        .toSet()
    if (SCREEN_RECORD_SERVICE !in named) {
        val service = xml.createElement("service")
        service.setAttribute("android:name", SCREEN_RECORD_SERVICE)
        service.setAttribute("android:exported", "false")
        service.setAttribute("android:foregroundServiceType", "mediaProjection")
        application.appendChild(service)
    }
    if (SCREEN_RECORD_CONSENT !in named) {
        val activity = xml.createElement("activity")
        activity.setAttribute("android:name", SCREEN_RECORD_CONSENT)
        activity.setAttribute("android:exported", "false")
        activity.setAttribute("android:excludeFromRecents", "true")
        application.appendChild(activity)
    }
}

internal val screenRecordManifestPatch = patch {
    execute {
        document("AndroidManifest.xml").use { xml -> declareScreenRecording(xml) }
    }
}
