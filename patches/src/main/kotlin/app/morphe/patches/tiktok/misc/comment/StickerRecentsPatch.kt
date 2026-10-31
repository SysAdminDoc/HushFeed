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
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STICKER_RECENTS = "Lapp/morphe/extension/tiktok/comment/StickerRecents;"
private const val RECENT_STICKER_ITEM = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"

/**
 * The recently used list's add: takes a sticker, returns nothing and caps the list at the page
 * size config's refreshCount (Xf3.LJ on PineDrama 47.5.3, 14gj.LJ on TikTok 47.5.3). The saved
 * stickers refresh reads refreshCount too, but takes no sticker.
 */
internal object RecentStickerAddFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(RECENT_STICKER_ITEM),
    custom = { method, _ ->
        method.implementation?.instructions?.any { it.getReference<FieldReference>()?.isStickerRefreshCount() == true } == true
    },
)

/**
 * A new recently used list going out: setValue(Object) on the flow just read from the store's
 * holder field, right before a return-void. [flow] and [list] are the call's two registers.
 */
internal data class RecentStickersWrite(val returnIndex: Int, val flow: Int, val list: Int, val holder: String)

private fun FieldReference.recentDescriptor() = "$definingClass->$name:$type"

internal fun Method.recentStickersWrites(): List<RecentStickersWrite> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.mapNotNull { index ->
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL || instructions.getOrNull(index + 1)?.opcode != Opcode.RETURN_VOID) {
            return@mapNotNull null
        }
        val setValue = call.getReference<MethodReference>() ?: return@mapNotNull null
        if (setValue.name != "setValue" || setValue.returnType != "V" ||
            setValue.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Object;")
        ) return@mapNotNull null
        val registers = call as FiveRegisterInstruction
        if (registers.registerCount != 2) return@mapNotNull null
        // The flow comes from the holder at most a couple of instructions earlier.
        val read = instructions.subList(maxOf(0, index - 3), index).lastOrNull {
            (it as? OneRegisterInstruction)?.registerA == registers.registerC
        } ?: return@mapNotNull null
        if (read.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val holder = read.getReference<FieldReference>()?.takeIf { it.type == setValue.definingClass } ?: return@mapNotNull null
        RecentStickersWrite(index + 1, registers.registerC, registers.registerD, holder.recentDescriptor())
    }
}

/** The add's one write of the list, the one that gets the save hook. */
internal fun Method.recentStickersAddWrite(): RecentStickersWrite =
    recentStickersWrites().singleOrNull()
        ?: throw PatchException("Keep recent stickers: ${recentStickersWrites().size} list writes in the add, expected 1")

/**
 * The store's reset: the other `()V` of the add's class that writes the same holder. PineDrama
 * calls it the first time the store is used in each process and on every sign in, sign out and
 * switch, which is what leaves Recently used empty after a restart.
 */
internal fun <M : Method> recentStickersReset(methods: Iterable<M>, add: Method, holder: String): M {
    val resets = methods.filter {
        it.name != add.name && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            it.recentStickersWrites().any { write -> write.holder == holder }
    }
    return resets.singleOrNull()
        ?: throw PatchException("Keep recent stickers: ${resets.size} resets beside ${add.definingClass}->${add.name}, expected 1")
}

private fun MutableMethod.addRecentStickersHook(write: RecentStickersWrite, register: Int, hook: String) {
    if (implementation?.instructions?.toList()?.getOrNull(write.returnIndex)?.opcode != Opcode.RETURN_VOID) {
        throw PatchException("Keep recent stickers: $name has no return after its list write")
    }
    addInstructionsAtControlFlowLabel(
        write.returnIndex,
        "invoke-static/range { v$register .. v$register }, $STICKER_RECENTS->$hook(Ljava/lang/Object;)V",
    )
}

/** Hands each new list to StickerRecents.save as a sticker goes on it. */
internal fun MutableMethod.saveRecentStickers(write: RecentStickersWrite) = addRecentStickersHook(write, write.list, "save")

/** Hands the emptied flow to StickerRecents.restore, which puts this account's list back. */
internal fun MutableMethod.restoreRecentStickers(write: RecentStickersWrite) = addRecentStickersHook(write, write.flow, "restore")

@Suppress("unused")
val keepRecentStickersPatch = bytecodePatch(
    name = "Keep recent stickers",
    description = "Keeps Recently used in PineDrama's comment sticker panel when you close and reopen the app. " +
        "PineDrama otherwise empties that row every time it starts. Works as soon as you patch it in, " +
        "with no switch.",
    default = true,
) {
    category("Comments")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.pineDrama())

    execute {
        val add = RecentStickerAddFingerprint.method
        val addWrite = add.recentStickersAddWrite()
        val reset = recentStickersReset(RecentStickerAddFingerprint.classDef.methods, add, addWrite.holder)
        val resetWrite = reset.recentStickersWrites().singleOrNull { it.holder == addWrite.holder }
            ?: throw PatchException("Keep recent stickers: the reset writes its list more than once")
        add.saveRecentStickers(addWrite)
        reset.restoreRecentStickers(resetWrite)
        registerForBuildDetails("enableStickerRecents")
    }
}
