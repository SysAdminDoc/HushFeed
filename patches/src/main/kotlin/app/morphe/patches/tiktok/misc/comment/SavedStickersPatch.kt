/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SAVED_STICKERS = "Lapp/morphe/extension/tiktok/comment/SavedStickers;"
internal const val STICKER_PAGE_SIZE_CONFIG = "Lcom/ss/android/ugc/aweme/exp/IMStickerFavPageSizeConfig\$Config;"
internal const val STICKER_API = "Lcom/ss/android/ugc/aweme/stickerstore/api/StickerApiV2;"

/** The page size config's refreshCount, the size of the first read of the saved stickers. */
internal fun FieldReference.isStickerRefreshCount() =
    definingClass == STICKER_PAGE_SIZE_CONFIG && name == "refreshCount" && type == "J"

/**
 * The saved stickers read: StickerApiV2's suspend call taking the cursor and the count (two
 * longs), a flag and a string. R8 renames it (LJIIIIZZ on TikTok 47.5.3). The refresh and load
 * more both call it.
 */
internal fun MethodReference.isSavedStickersRead(): Boolean {
    val parameters = parameterTypes.map { it.toString() }
    return definingClass == STICKER_API && returnType == "Ljava/lang/Object;" && parameters.size == 5 &&
        parameters.subList(0, 4) == listOf("J", "J", "Z", "Ljava/lang/String;")
}

/**
 * The saved stickers refresh (#214): the coroutine that reads refreshCount and asks for that many
 * saved stickers from cursor 0 (14gn on TikTok 47.5.3). Load more calls
 * the same read with loadMoreCount, and the recently used list reads refreshCount as its length
 * cap without calling it, so neither matches.
 */
internal fun Method.isSavedStickersRefresh(): Boolean {
    val references = implementation?.instructions
        ?.mapNotNull { (it as? ReferenceInstruction)?.reference }
        ?: return false
    return references.any { it is FieldReference && it.isStickerRefreshCount() } &&
        references.any { it is MethodReference && it.isSavedStickersRead() }
}

internal object SavedStickersRefreshFingerprint : Fingerprint(
    name = "invokeSuspend",
    custom = { method, _ -> method.isSavedStickersRefresh() },
)

/**
 * Hands the refresh's count to the extension right after the coroutine reads it, so the one
 * request the comment sticker panel makes asks for every saved sticker.
 */
internal fun MutableMethod.raiseSavedStickersFirstRead() {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("Load all saved stickers: the refresh has no code")
    val reads = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET_WIDE && instruction.getReference<FieldReference>()?.isStickerRefreshCount() == true
    }
    if (reads.size != 1) {
        throw PatchException("Load all saved stickers: ${reads.size} refreshCount reads in the refresh, expected 1")
    }
    val (index, read) = reads.single()
    val calledAfter = instructions.drop(index + 1).any { it.getReference<MethodReference>()?.isSavedStickersRead() == true }
    if (!calledAfter) throw PatchException("Load all saved stickers: no saved stickers request follows the refreshCount read")
    val count = (read as TwoRegisterInstruction).registerA
    addInstructions(
        index + 1,
        """
            invoke-static/range { v$count .. v${count + 1} }, $SAVED_STICKERS->refreshCount(J)J
            move-result-wide v$count
        """,
    )
}

@Suppress("unused")
val loadAllSavedStickersPatch = bytecodePatch(
    name = "Load all saved stickers",
    description = "Loads every sticker you saved in one go when you open the sticker panel, " +
        "instead of the first 100. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Comments")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SavedStickersRefreshFingerprint.method.raiseSavedStickersFirstRead()
        registerForBuildDetails("enableSavedStickers")
    }
}
