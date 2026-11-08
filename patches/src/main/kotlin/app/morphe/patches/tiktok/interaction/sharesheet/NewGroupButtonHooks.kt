/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val NEW_GROUP = "Lapp/morphe/extension/tiktok/share/ShareNewGroupButton;"
private const val END_ICON = "setButtonEndIconDrawable"

/**
 * The share sheet's create-group bar shows or hides its "Send to new group" button in one (Z)V
 * method (47.7.3 X.0IF9.LIZJ), the only one of that shape that puts the picked people's avatars
 * on a button's end icon.
 */
internal fun isNewGroupButtonToggle(method: Method): Boolean =
    method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("Z") &&
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                instruction.getReference<MethodReference>()?.let {
                    it.name == END_ICON && it.parameterTypes.map(CharSequence::toString) ==
                        listOf("Landroid/graphics/drawable/Drawable;")
                } == true
        } == true

internal object NewGroupButtonToggleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    custom = { method, _ -> isNewGroupButtonToggle(method) },
)

/** Where the method reads the button off its holder, the register it lands in and the button's class. */
internal data class NewGroupButtonRead(val index: Int, val register: Int, val type: String)

/**
 * The method opens by reading the button and returning when it's null: an iget-object whose class
 * is the one the end icon call goes to, then an if-nez on that register that nothing else lands on.
 */
internal fun Method.newGroupButtonRead(): NewGroupButtonRead {
    val code = implementation?.instructions?.toList()
        ?: throw PatchException("Share sheet tools: the new group button method has no code.")
    val button = code.firstNotNullOfOrNull { instruction ->
        instruction.getReference<MethodReference>()?.takeIf { it.name == END_ICON }?.definingClass
    } ?: throw PatchException("Share sheet tools: the new group button method lost its end icon call.")
    val read = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT && instruction.getReference<FieldReference>()?.type == button
    }
    if (read < 0) throw PatchException("Share sheet tools: the new group button method no longer reads the button.")
    val register = (code[read] as TwoRegisterInstruction).registerA
    val check = code.getOrNull(read + 1)
    if (check?.opcode != Opcode.IF_NEZ || (check as OneRegisterInstruction).registerA != register) {
        throw PatchException("Share sheet tools: the new group button method no longer checks the button it read.")
    }
    if (code.getOrNull(read + 2)?.opcode != Opcode.RETURN_VOID) {
        throw PatchException("Share sheet tools: the new group button method no longer returns without a button.")
    }
    if (isBranchTarget(read + 1)) {
        throw PatchException("Share sheet tools: a branch lands on the new group button check.")
    }
    return NewGroupButtonRead(read, register, button)
}

/**
 * The button goes through the extension between the read and the null check. Straight-line: a
 * null answer takes TikTok's own early return. /range because the register may sit above v15.
 */
internal fun MutableMethod.hookNewGroupButton() {
    val read = newGroupButtonRead()
    addInstructions(
        read.index + 1,
        """
            invoke-static/range { v${read.register} .. v${read.register} }, $NEW_GROUP->filter(Landroid/view/View;)Landroid/view/View;
            move-result-object v${read.register}
            check-cast v${read.register}, ${read.type}
        """,
    )
}

internal fun BytecodePatchContext.hookNewGroupButtonToggle() {
    NewGroupButtonToggleFingerprint.method.hookNewGroupButton()
}
