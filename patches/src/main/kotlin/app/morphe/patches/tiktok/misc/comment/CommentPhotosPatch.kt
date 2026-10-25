/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val COMMENT_PHOTOS_SWITCH = "Lapp/morphe/extension/tiktok/comment/CommentPhotos;->isOn()Z"
internal const val COMMENT_PHOTOS_PLUGIN_ENTRY =
    "Lapp/morphe/extension/tiktok/comment/CommentPhotos;->allowPluginEntry(Ljava/lang/String;)Z"

/**
 * What the album asks for on Android 13 and later. TikTok declares both; PineDrama 47.4.3 and 47.5.3
 * declare neither, so Android turned the request down without a prompt and the album only said it
 * couldn't access photos (#156).
 */
internal val COMMENT_PHOTOS_PERMISSIONS = listOf(
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
)

/** Declares the album's permissions the app lacks, leaving any it already declares alone. */
internal fun declareCommentPhotoPermissions(xml: Document) {
    val manifest = xml.documentElement
    val declared = (0 until xml.getElementsByTagName("uses-permission").length)
        .map { (xml.getElementsByTagName("uses-permission").item(it) as Element).getAttribute("android:name") }
        .toSet()
    COMMENT_PHOTOS_PERMISSIONS.filter { it !in declared }.forEach { permission ->
        val element = xml.createElement("uses-permission")
        element.setAttribute("android:name", permission)
        manifest.insertBefore(element, manifest.firstChild)
    }
}

internal val commentPhotosManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { xml -> declareCommentPhotoPermissions(xml) }
    }
}

/**
 * PineDrama's comment protocol, asked whether the comment box may build its gallery tab. The
 * caller passes bit 1 of the `comment_photo_publish` value; TikTok's protocol answers with it,
 * and PineDrama's answers with an unrelated creation check that is false on every account
 * (47.4.3, #156). It is the only `(Z)Z` the class declares.
 */
internal object DramaCommentPhotoGateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/appdiff/DramaCommentAppDiffProtocol;",
    returnType = "Z",
    parameters = listOf("Z"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * PineDrama's stand-in for the creation-tools plugin service, asked before the comment box opens
 * the album with `comment_photo_` plus the shoot-enter method. TikTok's service answers true when
 * the tools are built in; PineDrama's is `const/4 v0, 0x0; return v0` for every entry, so the
 * photo button did nothing once the tab was back (47.4.3, #156). It has 123 callers, which is why
 * the switch only answers for the comment photo entries.
 */
internal object DramaCreativePluginEntryFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/creativetools/DramaCreativeToolsPluginServiceImpl;",
    name = "ensureEntryPluginInstalled",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Z"),
)

@Suppress("unused")
val commentPhotosPatch = bytecodePatch(
    name = "Allow photo comments",
    description = "Lets PineDrama's comment box ask TikTok's server about photo comments, like TikTok " +
        "does. TikTok's server still decides whether your account can post one. Starts off. " +
        "Turn it on in Hushfeed settings > Comments.",
) {
    category("Comments")
    dependsOn(sharedExtensionPatch, settingsPatch, commentPhotosManifestPatch)
    compatibleWith(*AppCompatibilities.pineDrama())

    execute {
        DramaCommentPhotoGateFingerprint.method.installCommentPhotoSwitch()
        DramaCreativePluginEntryFingerprint.method.installCommentAlbumEntry()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentPhotos()V",
        )
    }
}

/**
 * With the switch on, the gate answers with the value it was handed, the way TikTok's does. The
 * answer goes in v0, so the frame needs a local, and the value is read from p1, so the method has
 * to be an instance `(Z)Z`: anything else is refused before a line is added.
 */
internal fun MutableMethod.installCommentPhotoSwitch() {
    if (returnType != "Z" || parameterTypes.map { it.toString() } != listOf("Z") || AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("Allow photo comments: $definingClass->$name is not an instance (Z)Z.")
    }
    guardAtEntry("Allow photo comments", "invoke-static {}, $COMMENT_PHOTOS_SWITCH", "return p1")
}

/**
 * With the switch on, the plugin service reports a `comment_photo_` entry as installed, so the
 * comment box goes on to open the album. The entry name is p2, so the method has to be an instance
 * `(Context, String, Z)Z`; anything else is refused before a line is added.
 */
internal fun MutableMethod.installCommentAlbumEntry() {
    val expected = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Z")
    if (returnType != "Z" || parameterTypes.map { it.toString() } != expected || AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("Allow photo comments: $definingClass->$name is not an instance (Context, String, Z)Z.")
    }
    guardAtEntry(
        "Allow photo comments",
        "invoke-static {p2}, $COMMENT_PHOTOS_PLUGIN_ENTRY",
        """
            const/4 v0, 0x1
            return v0
        """,
    )
}
