package app.morphe

import app.morphe.patcher.CheckCastFilter
import app.morphe.patcher.FieldAccessFilter
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstanceOfFilter
import app.morphe.patcher.MethodCallFilter
import app.morphe.patches.tiktok.feedfilter.FollowingCacheRestoreFingerprint
import app.morphe.patches.tiktok.interaction.cleardisplay.ClearModeLogStateFingerprint
import app.morphe.patches.tiktok.interaction.downloads.StickerPreviewSourceFingerprint
import app.morphe.patches.tiktok.interaction.gesture.CoordinateLongPressCallbackFingerprint
import app.morphe.patches.tiktok.interaction.ghostmode.ProfileViewReportFingerprint
import app.morphe.patches.tiktok.interaction.ghostmode.StoryViewReportFingerprint
import app.morphe.patches.tiktok.interaction.ghostmode.TypingStatusSenderFingerprint
import app.morphe.patches.tiktok.interaction.resume.FeedProgressContinueGateFingerprint
import app.morphe.patches.tiktok.interaction.speed.EdgeSpeedupTouchFingerprint
import app.morphe.patches.tiktok.interaction.videofit.SlideContainerCropFingerprint
import app.morphe.patches.tiktok.interaction.videofit.SlideScaleTypeFingerprint
import app.morphe.patches.tiktok.misc.comment.RecentStickerAddFingerprint
import app.morphe.patches.tiktok.misc.comment.RecentStickerRowsFingerprint
import app.morphe.patches.tiktok.misc.comment.SavedStickersRefreshFingerprint
import app.morphe.patches.tiktok.misc.comment.StickerUploadConfigFingerprint
import app.morphe.patches.tiktok.misc.comment.clipboardTextHelperFingerprint
import app.morphe.patches.tiktok.misc.comment.commentClipDataBuilderFingerprint
import app.morphe.patches.tiktok.misc.commenttools.CommentPageSurpriseFingerprint
import app.morphe.patches.tiktok.misc.inbox.ChatRequestSendWithHeadersFingerprint
import app.morphe.patches.tiktok.misc.login.disablerequirement.MandatoryLoginService2Fingerprint
import app.morphe.patches.tiktok.misc.login.disablerequirement.MandatoryLoginServiceFingerprint
import app.morphe.patches.tiktok.misc.refreshrate.RefreshRateWriteFingerprint
import app.morphe.patches.tiktok.misc.shortcuts.ShortcutPublishFingerprint
import app.morphe.patches.tiktok.misc.translation.CommentListLoadedFingerprint
import app.morphe.patches.tiktok.promobanners.ProfileMenuCellFingerprint
import app.morphe.patches.tiktok.promobanners.ProfileRewardsIconBinderFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fingerprints #54 narrowed took a definingClass, a name or one indexed instruction filter
 * that their custom block already implied, so the patcher searches the few classes that can
 * hold them instead of every method of TikTok. The --validate timing pass on 47.6.3 narrowed
 * nine more the same way, with a call, a field read, an instanceof or a cast, and a second pass
 * two with the field access their job needs (the request's headers, the Message's number), and
 * a third pass more with the edge speed-up ability call its custom block looks for, the sticker
 * preview's read of the item it was handed and the coordinate listeners' feed parameters.
 * Implied is the claim, and this is its check: on every retained fixture each one takes exactly
 * the methods it takes without the addition.
 */
class NarrowedFingerprintsTest {
    private class Narrowed(val label: String, val fingerprint: Fingerprint, val addedClassAndName: Boolean = false)

    private val narrowed = listOf(
        Narrowed("StoryViewReport", StoryViewReportFingerprint, addedClassAndName = true),
        Narrowed("ProfileViewReport", ProfileViewReportFingerprint, addedClassAndName = true),
        Narrowed("TypingStatusSender", TypingStatusSenderFingerprint, addedClassAndName = true),
        Narrowed("MandatoryLoginService", MandatoryLoginServiceFingerprint, addedClassAndName = true),
        Narrowed("MandatoryLoginService2", MandatoryLoginService2Fingerprint, addedClassAndName = true),
        Narrowed("RefreshRateWrite", RefreshRateWriteFingerprint),
        Narrowed("ShortcutPublish", ShortcutPublishFingerprint),
        Narrowed("ProfileRewardsIconBinder", ProfileRewardsIconBinderFingerprint),
        Narrowed("FeedProgressContinueGate", FeedProgressContinueGateFingerprint),
        Narrowed("CommentPageSurprise", CommentPageSurpriseFingerprint),
        Narrowed("CommentListLoaded", CommentListLoadedFingerprint),
        Narrowed("clipboardTextHelper", clipboardTextHelperFingerprint),
        Narrowed("commentClipDataBuilder", commentClipDataBuilderFingerprint),
        Narrowed("FollowingCacheRestore", FollowingCacheRestoreFingerprint),
        Narrowed("SavedStickersRefresh", SavedStickersRefreshFingerprint),
        Narrowed("RecentStickerAdd", RecentStickerAddFingerprint),
        Narrowed("RecentStickerRows", RecentStickerRowsFingerprint),
        Narrowed("StickerUploadConfig", StickerUploadConfigFingerprint),
        Narrowed("SlideScaleType", SlideScaleTypeFingerprint),
        Narrowed("SlideContainerCrop", SlideContainerCropFingerprint),
        Narrowed("ProfileMenuCell", ProfileMenuCellFingerprint),
        Narrowed("ChatRequestSendWithHeaders", ChatRequestSendWithHeadersFingerprint),
        Narrowed("ClearModeLogState", ClearModeLogStateFingerprint),
        Narrowed("EdgeSpeedupTouch", EdgeSpeedupTouchFingerprint),
        Narrowed("StickerPreviewSource", StickerPreviewSourceFingerprint),
        Narrowed("CoordinateLongPressCallback", CoordinateLongPressCallbackFingerprint),
    )

    /** The fingerprint as it was before #54: the same fields, less what was added. */
    private fun Narrowed.unnarrowed() = Fingerprint(
        definingClass = if (addedClassAndName) null else fingerprint.declaredDefiningClass,
        name = if (addedClassAndName) null else fingerprint.declaredName,
        accessFlags = fingerprint.declaredAccessFlags?.let { AccessFlags.getAccessFlagsForMethod(it).toList() },
        returnType = fingerprint.declaredReturnType,
        parameters = fingerprint.declaredParameters,
        filters = null,
        strings = fingerprint.strings,
        custom = fingerprint.custom,
    )

    @Test
    fun `each narrowed fingerprint takes what it took before on every fixture`() {
        val declared = Fixtures.declaredVersions().toSet()
        val wide = narrowed.associateWith { it.unnarrowed() }
        for (apk in Fixtures.apks()) {
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = narrowed.associateWith { mutableListOf<String>() }
            val narrowTaken = narrowed.associateWith { mutableListOf<String>() }
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        for (item in narrowed) {
                            // Adding a condition can only take methods away, so the narrowed
                            // fingerprint is judged on what the wide one took.
                            if (!wide.getValue(item).takes(method, classDef)) continue
                            val id = "${classDef.type}->${method.name}${method.parameterTypes}${method.returnType}"
                            taken.getValue(item) += id
                            if (item.fingerprint.takes(method, classDef)) narrowTaken.getValue(item) += id
                        }
                    }
                }
            }
            for (item in narrowed) {
                assertEquals("${apk.name}: ${item.label}", taken.getValue(item), narrowTaken.getValue(item))
                if (version in declared) {
                    assertTrue("${apk.name}: ${item.label} takes nothing on a declared build", taken.getValue(item).isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `a narrowed filter is one the patcher can look up`() {
        for (item in narrowed.filter { !it.addedClassAndName }) {
            val filter = item.fingerprint.filters.orEmpty().single()
            val type = when (filter) {
                is MethodCallFilter -> filter.definingClass
                is FieldAccessFilter -> filter.definingClass
                is InstanceOfFilter -> filter.type()
                is CheckCastFilter -> filter.type()
                else -> null
            }
            // The patcher indexes a call, a field access, an instanceof or a cast by an exact
            // class, `L...;` (a call or field access by its member name too). Each of these
            // names the class, the narrower of the two keys.
            assertTrue("${item.label}: ${filter.javaClass.simpleName} on $type", type != null && type.startsWith("L") && type.endsWith(";"))
        }
        for (item in narrowed.filter { it.addedClassAndName }) {
            val suffix = item.fingerprint.declaredDefiningClass
            assertTrue("${item.label}: $suffix", suffix != null && suffix.startsWith("/") && suffix.endsWith(";"))
        }
    }
}
