/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val ANOLE_MODEL = "Lcom/ss/android/ugc/aweme/commercialize/model/AnoleModel;"
internal const val ANOLE_INIT_LOG = "initComponent, type:"
internal const val INTEREST_BUTTON_GROUP = "interest_feedback_button_group"

/**
 * Where TikTok builds the Not interested and Interested buttons it sometimes lays over a feed
 * video (#293). They're a server-sent "Anole" component: the holder's constructor (X.1QgF on
 * 47.5.3) walks the AnoleModel's components, logs each one's type, and compares its container
 * type with every name it knows, skipping a type no name matches. The model keeps its real name,
 * and no other constructor logs that line.
 */
internal object InterestButtonGroupFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(ANOLE_INIT_LOG, INTEREST_BUTTON_GROUP),
    custom = { method, _ -> isAnoleComponentFactory(method) },
)

internal fun isAnoleComponentFactory(method: Method): Boolean =
    method.name == "<init>" && method.parameterTypes.any { it.toString() == ANOLE_MODEL }

/**
 * The index of the if-eqz that moves past the button group: right after the group's name is
 * loaded, TikTok's static equals runs, its answer is moved into a register, and the branch tests
 * that register. -1 when the check no longer has that shape.
 */
internal fun interestGroupBranch(instructions: List<Instruction>): Int {
    val load = instructions.indexOfFirst { it.getReference<StringReference>()?.string == INTEREST_BUTTON_GROUP }
    if (load < 0 || load + 3 >= instructions.size) return -1
    val equals = instructions[load + 1]
    val call = equals.getReference<MethodReference>() ?: return -1
    if (equals.opcode != Opcode.INVOKE_STATIC || call.returnType != "Z" || call.parameterTypes.size != 2) return -1
    val result = instructions[load + 2]
    val branch = instructions[load + 3]
    if (result.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_EQZ) return -1
    if ((result as OneRegisterInstruction).registerA != (branch as OneRegisterInstruction).registerA) return -1
    return load + 3
}

/**
 * Hands the button group's match to Hide surveys right before TikTok branches on it. With the
 * switch on the answer is false, so the holder tries the next names, matches none and skips the
 * component the way it skips a type it doesn't know: no buttons, and nothing recorded as shown.
 * Straight-line on purpose, since it runs for every video that carries components (#264).
 */
internal fun MutableMethod.dropInterestButtons(patch: String) {
    val branch = interestGroupBranch(implementation!!.instructions.toList())
    if (branch < 0) {
        throw PatchException("$patch: $definingClass->$name no longer checks $INTEREST_BUTTON_GROUP the way it did.")
    }
    // A jump keeps its target on the branch itself, so a path that jumped there would miss the hook.
    if (implementation!!.instructions.any { it is BuilderOffsetInstruction && it.target.location.index == branch }) {
        throw PatchException("$patch: $definingClass->$name jumps onto its $INTEREST_BUTTON_GROUP check.")
    }
    val register = (implementation!!.instructions[branch] as OneRegisterInstruction).registerA
    addInstructions(
        branch,
        """
            invoke-static/range { v$register .. v$register }, Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->interestButtonsShown(I)Z
            move-result v$register
        """,
    )
}
