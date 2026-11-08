/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The package the update card's Update button opens (UpdateNotice.MANAGER in the extension). */
internal const val MORPHE_MANAGER_PACKAGE = "app.morphe.manager"

/**
 * Lets the patched TikTok see Morphe Manager (#377). TikTok targets Android 11 or later, so the
 * system hides every app its manifest doesn't name in a queries element: the update card's Update
 * button gets no launch intent for an installed Manager and falls back to the release page.
 * TikTok's manifest already has several queries elements; this adds one more,
 * before the application, unless a build already names Manager.
 */
internal fun declareManagerQuery(xml: Document) {
    val packages = xml.getElementsByTagName("package")
    val named = (0 until packages.length)
        .map { packages.item(it) as Element }
        .filter { (it.parentNode as? Element)?.tagName == "queries" }
        .any { it.getAttribute("android:name") == MORPHE_MANAGER_PACKAGE }
    if (named) return
    val manifest = xml.documentElement
    val application = xml.getElementsByTagName("application").item(0)
        ?: throw PatchException("Settings: the manifest has no application")
    val queries = xml.createElement("queries")
    val manager = xml.createElement("package")
    manager.setAttribute("android:name", MORPHE_MANAGER_PACKAGE)
    queries.appendChild(manager)
    manifest.insertBefore(queries, application)
}

internal val managerQueryPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { xml -> declareManagerQuery(xml) }
    }
}
