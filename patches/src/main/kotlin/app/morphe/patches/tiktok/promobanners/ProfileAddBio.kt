/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The header component that shows "+ Add bio" on your own profile. Its real name is kept on 47.9.2. */
internal const val PROFILE_ADD_BIO_COMPONENT =
    "Lcom/ss/android/ugc/profile/business/ur/platform/ProfileHeaderAddBioComponent;"

/** The key the bind reports the prompt under once it has decided whether it shows. */
internal const val ADD_BIO_REPORT_KEY = "HAS_ADD_BIO"

private const val TUX_TEXT_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"
private const val ADD_BIO = "Lapp/morphe/extension/tiktok/profile/ProfileAddBio;"

/**
 * The Add bio component's bind (#466). It reads your bio, and its view goes gone through
 * TikTok's visibility helper when there is one and visible through the same helper when the bio
 * is missing or empty. Then it reports the view's visibility under [ADD_BIO_REPORT_KEY] and styles
 * the pill. The base signature class has a bind of the same shape, so the class is named too.
 */
internal object ProfileAddBioBindFingerprint : Fingerprint(
    definingClass = PROFILE_ADD_BIO_COMPONENT,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/List;"),
    strings = listOf(ADD_BIO_REPORT_KEY),
)

/** The bind's one call that shows the prompt, and the register holding the prompt's view. */
internal class AddBioShow(val index: Int, val view: Int)

/** A static `(TuxTextView, int)` helper with no result: how the bind sets the view's visibility. */
private fun Instruction.visibilityCall(): MethodReference? = getReference<MethodReference>()?.takeIf {
    (this as? FiveRegisterInstruction)?.registerCount == 2 && it.returnType == "V" &&
        it.parameterTypes.map(CharSequence::toString) == listOf(TUX_TEXT_VIEW, "I")
}

/**
 * Finds the visibility call that shows the prompt: the one whose int comes from a literal 0
 * (View.VISIBLE), read from the nearest earlier write to that register. The other call is handed
 * 8 (View.GONE) for a profile that has a bio.
 */
internal fun Method.addBioShow(patch: String): AddBioShow {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$patch: $definingClass->$name has no implementation.")
    val calls = instructions.withIndex().filter { (_, instruction) -> instruction.visibilityCall() != null }
    val shows = calls.filter { (index, instruction) ->
        val visibility = (instruction as FiveRegisterInstruction).registerD
        val write = instructions.subList(0, index).lastOrNull { earlier ->
            earlier is OneRegisterInstruction && earlier.registerA == visibility &&
                earlier.opcode.setsRegister()
        }
        write is NarrowLiteralInstruction && write.narrowLiteral == 0
    }
    val show = shows.singleOrNull() ?: throw PatchException(
        "$patch: the Add bio bind sets its view visible ${shows.size} times out of ${calls.size} visibility calls, not once.",
    )
    return AddBioShow(show.index, (show.value as FiveRegisterInstruction).registerC)
}

/**
 * Right after TikTok shows the prompt, hands its view to the extension, which sets it gone again
 * when Hide Add bio is on. The bind then reads the view's visibility for its report and its guide
 * text, so both see a hidden prompt. No branch lands on the instruction after the call.
 */
internal fun MutableMethod.hideAddBio(show: AddBioShow) = addInstruction(
    show.index + 1,
    "invoke-static/range { v${show.view} .. v${show.view} }, $ADD_BIO->shown(Landroid/view/View;)V",
)
