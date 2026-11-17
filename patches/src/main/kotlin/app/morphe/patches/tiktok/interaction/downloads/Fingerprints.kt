/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/downloads/Fingerprints.kt
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal object AclCommonShareFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/ACLCommonShare;",
    name = "getCode",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
)

internal object AclCommonShare2Fingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/ACLCommonShare;",
    name = "getShowType",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
)

internal object AclCommonShare3Fingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/ACLCommonShare;",
    name = "getTranscode",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
)

// The five below name their class and not their method. An obfuscated method name is a
// counter-assigned label that the next TikTok build is free to reshuffle, and it was the only
// thing here that would have moved. Each is unique on 46.2.3 without it, checked method by method
// over the whole class on 2026-09-09: of the three (Context, String) methods returning a Uri, one
// holds "video/mp4" and one "video/*" and the third neither; of the two (Context, String, String)
// ones, one holds "/Camera" and the other "image/*"; and the six-parameter copy is the only
// method of its shape on its class at all. What stays is the class, the signature and the strings,
// which is what every other fingerprint in this tree is anchored on.

/**
 * The media-store helper the download patch works through. Its class was written into all six of
 * the fingerprints below as `/0L4Q;`, and it is `LX/0LWq;` on 46.7.3 and `LX/0LEM;` on 46.8.3.
 * Its own method names have not moved, and each of the six is named by its signature together
 * with the SQL selection, the media type or the `MediaStore` table it works on, every one of
 * which resolves to a single method on all three builds without the class.
 *
 * <p>Where a downloaded video is written.
 */
internal object VideoDownloadUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("/", "/Camera", "/Camera/", "video/mp4"),
)

internal object AwemeGetVideoFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
    name = "getVideo",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/ss/android/ugc/aweme/feed/model/Video;",
    parameters = emptyList(),
)

internal fun Method.drawsCommentImageWatermark(): Boolean =
    implementation?.instructions?.any { instruction ->
        val reference = instruction.getReference<MethodReference>() ?: return@any false
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            reference.definingClass == "Landroid/graphics/Canvas;" &&
            reference.name == "drawBitmap" &&
            reference.parameterTypes == listOf(
                "Landroid/graphics/Bitmap;",
                "F",
                "F",
                "Landroid/graphics/Paint;",
            ) && reference.returnType == "V"
    } == true

internal object CommentImageWatermarkFingerprint : Fingerprint(
    strings = listOf("[tiktok_logo]"),
    parameters = listOf("Landroid/graphics/Bitmap;"),
    returnType = "V",
    custom = { method, _ -> method.drawsCommentImageWatermark() },
)

/**
 * Strings match by substring, so "/", "/Camera" and "/Camera/" are all satisfied by the
 * lookup method's own "/Camera/" and told the two apart only by which the patcher visited
 * first. The lookup is the one that queries by relative path and MIME type; this one must not.
 */
internal object PhotoDownloadUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("/", "/Camera", "/Camera/"),
    custom = { method, _ ->
        method.implementation?.instructions?.none { instruction ->
            val string = instruction.getReference<StringReference>()?.string ?: return@none false
            string == "image/*" || string.contains("relative_path=?")
        } == true
    },
)

internal object VideoLookupUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("/Camera/", "video/*", "(relative_path=? OR relative_path=?) AND _display_name=?"),
)

internal object PhotoLookupUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("/Camera/", "image/*", "(relative_path=? OR relative_path=?) AND _display_name=?"),
)

internal object VideoMediaStoreInsertFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(definingClass = "Landroid/provider/MediaStore\$Video\$Media;", name = "getContentUri"),
    ),
)

internal object PhotoMediaStoreInsertFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(definingClass = "Landroid/provider/MediaStore\$Images\$Media;", name = "getContentUri"),
    ),
)

/**
 * The copy that puts a saved image or video into the gallery.
 *
 * <p>It was `LX/0Mqc;` and it delegated to `LX/05w6;`, both written here. They are `LX/0N0m;` and
 * `LX/0LEM;` on 46.8.3. The six-parameter signature and the three media types together name one
 * method on all three builds without either, and what is left of the delegation check is that the
 * two MediaStore inserts it calls are on one class, whatever that class is called.
 */
internal object ImagePostMediaCopyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/String;",
        "I",
    ),
    strings = listOf("/Camera/", "video/mp4", "image/jpeg"),
    custom = { method, _ ->
        val inserts = method.implementation?.instructions
            ?.mapNotNull { it.getReference<MethodReference>() }
            ?.filter {
                it.returnType == "Landroid/net/Uri;" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(
                        "Landroid/content/Context;",
                        "Ljava/lang/String;",
                        "Ljava/lang/String;",
                        "Ljava/lang/String;",
                    )
            }
            .orEmpty()
        val distinct = inserts.distinctBy { "${it.definingClass}->${it.name}" }
        distinct.size >= 2 && distinct.map { it.definingClass }.distinct().size == 1
    },
)

/**
 * TikTok's save of a comment photo: an R8-named class and method, but the one method in the app
 * that asks for storage under the "bpea-comment_photo_save_request_storage" policy. It unboxes
 * the tapped photo's index, reads the comment, and hands its downloader the still's UrlModel
 * alone, which is where a live photo's clip is lost (upstream #169).
 */
internal object CommentPhotoSaveFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("bpea-comment_photo_save_request_storage"),
)

private const val PREVIEW_STICKER_ITEM = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"
private const val PREVIEW_COMMENT_STICKER = "Lcom/ss/android/ugc/aweme/comment/model/CommentStickerStruct;"

/**
 * Where the tapped comment sticker's StickerItem comes from in the preview coroutine: the
 * coroutine's own CommentStickerStruct field, read right into the static converter that builds
 * the item (0hUa.LLJJIJIIJIL and 0Hgs.LIZ on TikTok 47.8.3). The converter only reads the
 * struct, so calling it again before each bind hands the extension the same sticker.
 */
internal data class StickerPreviewSource(val struct: FieldReference, val convert: MethodReference)

internal fun Method.stickerPreviewSource(): StickerPreviewSource? {
    val instructions = implementation?.instructions?.toList() ?: return null
    return instructions.indices.mapNotNull { index ->
        val call = instructions[index].takeIf { it.opcode == Opcode.INVOKE_STATIC } as? FiveRegisterInstruction
            ?: return@mapNotNull null
        val convert = call.getReference<MethodReference>()?.takeIf {
            it.returnType == PREVIEW_STICKER_ITEM && it.parameterTypes.map(CharSequence::toString) == listOf(PREVIEW_COMMENT_STICKER)
        } ?: return@mapNotNull null
        val read = instructions.getOrNull(index - 1)?.takeIf { it.opcode == Opcode.IGET_OBJECT } as? TwoRegisterInstruction
            ?: return@mapNotNull null
        val struct = read.getReference<FieldReference>()?.takeIf {
            it.definingClass == definingClass && it.type == PREVIEW_COMMENT_STICKER && read.registerA == call.registerC
        } ?: return@mapNotNull null
        StickerPreviewSource(struct, convert)
    }.distinct().singleOrNull()
}

internal object StickerPreviewSourceFingerprint : Fingerprint(
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    // TikTok 47.8.3 inlined the sticker preview (`LX/0MG8;->LJI(String, StickerItem, View, ...)`
    // on 47.7.3) into the comment sticker tap's coroutine, CommentImageComponent's
    // bindListStickerOnClick (`LX/0hUa;` there). The StickerItem is no longer a parameter: the
    // coroutine builds it from its CommentStickerStruct (see [stickerPreviewSource]). What holds
    // is that it calls the preview bind, by that bind's shape rather than by its name.
    //
    // Instance, because the source hook reads the struct out of p0.
    //
    // It reads the item's StickerBase for the sticker type, and the patcher can look that read
    // up, so it reads only the classes that make it rather than every class of TikTok.
    filters = listOf(fieldAccess(definingClass = PREVIEW_STICKER_ITEM, name = "stickerBase")),
    custom = { method, _ ->
        AccessFlags.STATIC.value and method.accessFlags == 0 &&
            method.implementation?.instructions?.any { instruction ->
                instruction.getReference<MethodReference>()?.isStickerPreviewBind() == true
            } == true &&
            method.stickerPreviewSource() != null
    },
)

internal object DownloadSuccessCoroutineFingerprint : Fingerprint(
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(
        "DownloadAction@71a5.startDownload\$globalListener\$1\$onSuccess\$1",
        "filePath",
        "fileExist",
    ),
)

/**
 * The Share to sheet TikTok opens a few seconds after its own downloader saves a video. The
 * fragment's show method builds the sheet, logs this event name and hands it to the fragment
 * manager; it is the only method of the fragment holding the string on 47.5.3
 * (PostDownloadShareSheetAnchorsTest). Its two parameters are obfuscated types, so the
 * class, the string and the void return are what it is known by.
 */
internal object PostDownloadShareSheetFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/internalshare/impl/fragment/DownloadAndShareFragment;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf("after_video_saved_share_to_nscreen"),
)
