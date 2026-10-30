/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The comment cell both of TikTok's comment paths bind through. The name survives obfuscation. */
internal const val BASE_COMMENT_CELL = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/BaseCommentCell;"

private const val ATTACHED_TRANSLATION_HOOK =
    "Lapp/morphe/extension/tiktok/translation/CommentBatchTranslator;->onTranslationControllerAttached(Ljava/lang/Object;)V"

/**
 * The index of the call in the cell's onViewAttachedToWindow that switches the comment's
 * translation controller on (#282): the one interface call taking and returning nothing whose
 * interface also declares `translate()V`. Found by shape, not by R8's names for the interface or
 * the call. Anything but exactly one such call stops the patch.
 */
internal fun Method.translationControllerEnable(classOf: (String) -> ClassDef?): Int {
    val instructions = implementation?.instructions?.toList() ?: emptyList()
    val sites = instructions.indices.filter { index ->
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@filter false
        val call = instruction.getReference<MethodReference>() ?: return@filter false
        call.name != "translate" && call.parameterTypes.isEmpty() && call.returnType == "V" &&
            classOf(call.definingClass)?.methods?.any {
                it.name == "translate" && it.parameterTypes.isEmpty() && it.returnType == "V"
            } == true
    }
    if (sites.size != 1) {
        throw PatchException(
            "Translate comments: expected one translation controller switch in $definingClass->$name, found ${sites.size}.",
        )
    }
    return sites.single()
}

/**
 * Hands the controller to the extension right after the cell switches it on, so the extension can
 * ask it to translate (#282). Inserted after the call, not at the instruction that follows it:
 * the cell's "no controller" branches land on that instruction, and a jump keeps its target on
 * the original instruction, so those paths skip the hook and only the one that just switched a
 * controller on runs it.
 */
internal fun MutableMethod.translateOnAttach(enableIndex: Int) {
    val register = (implementation!!.instructions.elementAt(enableIndex) as FiveRegisterInstruction).registerC
    if (register > 15) {
        throw PatchException("Translate comments: the translation controller sits in v$register of $definingClass->$name.")
    }
    addInstructions(enableIndex + 1, "invoke-static {v$register}, $ATTACHED_TRANSLATION_HOOK")
}

/**
 * Auto translate on TikTok's newer comment path (#282). Accounts in that experiment bind comment
 * cells through a per-comment translation controller instead of the manager the batch reads, so
 * their comments never reached it. The controller is switched on when the cell attaches, and
 * ignores a translate before that.
 */
internal fun BytecodePatchContext.hookAttachedCommentTranslation() {
    val attach = mutableClassDefBy(BASE_COMMENT_CELL).methods.singleOrNull {
        it.name == "onViewAttachedToWindow" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: throw PatchException("Translate comments: $BASE_COMMENT_CELL has no onViewAttachedToWindow.")
    attach.translateOnAttach(attach.translationControllerEnable(::classDefByOrNull))
}
