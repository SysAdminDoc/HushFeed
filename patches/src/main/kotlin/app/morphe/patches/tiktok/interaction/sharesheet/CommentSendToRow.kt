/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val SEND_TO_ROW = "Lapp/morphe/extension/tiktok/share/CommentSendToRow;"
internal const val SEND_TO_ROW_SUPERCLASS = "Landroid/widget/FrameLayout;"
private const val SEND_TO_ROW_LOG_TAG = "CommentIShareView"

/** The Send to row's load callback, the one (List)V on it that logs under the row's own tag. */
internal fun isCommentSendToLoad(method: Method): Boolean =
    method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;") &&
        method.implementation?.instructions?.any { instruction ->
            (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                instruction.getReference<StringReference>()?.string == SEND_TO_ROW_LOG_TAG
        } == true

internal object CommentSendToLoadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    strings = listOf("onLoadComplete: list: ", SEND_TO_ROW_LOG_TAG),
    custom = { method, classDef -> classDef.superclass == SEND_TO_ROW_SUPERCLASS && isCommentSendToLoad(method) },
)

/** Where the row calls its superclass's onAttachedToWindow, -1 when it doesn't start that way. */
internal fun sendToRowAttachIndex(method: Method): Int =
    method.implementation?.instructions?.toList().orEmpty().indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_SUPER &&
            instruction.getReference<MethodReference>()?.name == "onAttachedToWindow"
    }

/**
 * The people go through the extension before the row reads the list. /range because a parameter
 * register can sit above v15.
 */
internal fun MutableMethod.hookCommentSendToLoad() {
    addInstructions(
        0,
        """
            invoke-static/range { p1 .. p1 }, $SEND_TO_ROW->people(Ljava/util/List;)Ljava/util/List;
            move-result-object p1
        """,
    )
}

/**
 * Hands the row over right after the superclass call. A RecyclerView measures a child after adding
 * it, so a collapse here is what that first measure sees.
 */
internal fun MutableMethod.hookCommentSendToAttach(patch: String) {
    val attach = sendToRowAttachIndex(this)
    if (attach < 0) throw PatchException("$patch: $definingClass->$name no longer calls the superclass.")
    val row = (implementation!!.instructions.toList()[attach] as FiveRegisterInstruction).registerC
    addInstruction(attach + 1, "invoke-static {v$row}, $SEND_TO_ROW->onAttached(Landroid/view/View;)V")
}

internal fun hookCommentSendToRow() {
    CommentSendToLoadFingerprint.method.hookCommentSendToLoad()
    val attach = CommentSendToLoadFingerprint.classDef.methods.firstOrNull {
        it.name == "onAttachedToWindow" && it.parameterTypes.isEmpty()
    } ?: throw PatchException("Share sheet tools: the comment menu's Send to row has no onAttachedToWindow.")
    attach.hookCommentSendToAttach("Share sheet tools")
}
