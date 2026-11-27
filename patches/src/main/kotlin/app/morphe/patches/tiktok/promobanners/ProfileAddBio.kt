/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The header component that shows "+ Add bio" on your own profile. Its real name is kept on 47.9.3. */
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
 * when Hide tips on your profile is on. The bind then reads the view's visibility for its report
 * and its guide text, so both see a hidden prompt. No branch lands on the instruction after the call.
 */
internal fun MutableMethod.hideAddBio(show: AddBioShow) = addInstruction(
    show.index + 1,
    "invoke-static/range { v${show.view} .. v${show.view} }, $ADD_BIO->shown(Landroid/view/View;)V",
)

/** The style entry only the message tip's builder reads in its constructor. */
private const val MESSAGE_TIP_COLOR = "TuxTooltip__tux_tooltipMessageBgColor"

/**
 * The message tip's builder (#546), TikTok's plain bubble with a line of text pointing at a button
 * or a tab, like the ones on your own profile. Its constructor reads the message bubble's
 * background color from the Tux tooltip style. The popover and action menu builders (the comment
 * sort menu is one) read their own colors, and custom tips have no style, so none of them match.
 */
internal object TooltipMessageBuilderFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET,
            definingClass = "Lcom/ss/android/ugc/aweme/app/R\$styleable;",
            name = MESSAGE_TIP_COLOR,
        ),
    ),
)

private fun FieldReference.smali() = "$definingClass->$name:$type"

/** The two fields show() reads first: the window's config, and the config's view to point at. */
internal class TipAnchor(val config: FieldReference, val anchor: FieldReference, val register: Int)

/** One of the windows a tip opens in, and what its show() reads first. */
internal class TipWindow(val type: String, val show: Method, val anchor: TipAnchor)

/** The message tip's own view, the config field holding a tip's content, and the tip windows. */
internal class MessageTips(val view: String, val content: FieldReference, val windows: List<TipWindow>)

/**
 * Reads the message builder's build method: it makes the message view, puts it in the config as
 * the tip's content and hands the rest to its base builder, which opens the tip in one of its
 * windows (drawn inside a given ViewGroup, or a popup window). Every window's show() is read
 * too, and has to start from the same config the builder fills.
 */
internal fun ClassDef.messageTips(patch: String, classBy: (String) -> ClassDef?): MessageTips {
    val builds = methods.filter { method ->
        method.parameterTypes.isEmpty() && method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.INVOKE_SUPER &&
                instruction.getReference<MethodReference>()?.let { it.name == method.name && it.parameterTypes.isEmpty() } == true
        } == true
    }
    val build = builds.singleOrNull()
        ?: throw PatchException("$patch: the message tip builder has ${builds.size} build methods, not one.")
    val instructions = build.implementation!!.instructions.toList()
    val views = instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { it.getReference<TypeReference>()?.type }.distinct()
    val view = views.singleOrNull()
        ?: throw PatchException("$patch: the message tip builder makes ${views.size} kinds of view, not one.")
    val contents = instructions.filter { it.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.type == "Landroid/view/View;" }.distinctBy { it.smali() }
    val content = contents.singleOrNull()
        ?: throw PatchException("$patch: the message tip builder sets ${contents.size} view fields, not one.")
    val base = instructions.single { it.opcode == Opcode.INVOKE_SUPER }.getReference<MethodReference>()!!
    val baseBuild = classBy(base.definingClass)?.methods?.singleOrNull { it.name == base.name && it.parameterTypes.isEmpty() }
        ?: throw PatchException("$patch: the tip builder's base build ${base.definingClass}->${base.name} is missing.")
    val windowTypes = baseBuild.implementation?.instructions?.filter { it.opcode == Opcode.NEW_INSTANCE }
        ?.mapNotNull { it.getReference<TypeReference>()?.type }?.distinct() ?: emptyList()
    if (windowTypes.isEmpty()) throw PatchException("$patch: the tip builder's base build opens no window.")
    val windows = windowTypes.map { type ->
        val show = classBy(type)?.methods?.singleOrNull { it.name == "show" && it.parameterTypes.isEmpty() && it.returnType == "V" }
            ?: throw PatchException("$patch: the tip window $type has no show().")
        val anchor = show.tipAnchor(patch)
        if (anchor.config.type != content.definingClass) {
            throw PatchException("$patch: the tip window $type shows from ${anchor.config.type}, not the config the builder fills.")
        }
        TipWindow(type, show, anchor)
    }
    return MessageTips(view, content, windows)
}

/**
 * Reads show()'s first two instructions: the config off `this`, then the view off the config,
 * into the register its null check reads. The hook reads the same two fields into that register
 * before them, so it needs nothing the method didn't already write there.
 */
internal fun Method.tipAnchor(patch: String): TipAnchor {
    val body = implementation ?: throw PatchException("$patch: $definingClass->$name has no implementation.")
    val instructions = body.instructions.toList()
    val thisRegister = body.registerCount - 1
    val first = instructions.getOrNull(0) as? TwoRegisterInstruction
    val config = (first as? ReferenceInstruction)?.reference as? FieldReference
    if (first == null || config == null || first.opcode != Opcode.IGET_OBJECT ||
        first.registerB != thisRegister || config.definingClass != definingClass
    ) {
        throw PatchException("$patch: the tip window's show() doesn't start by reading its config.")
    }
    val second = instructions.getOrNull(1) as? TwoRegisterInstruction
    val anchor = (second as? ReferenceInstruction)?.reference as? FieldReference
    // The hook writes the view's register before show()'s own first read of `this`, so that
    // register can't be `this`.
    if (second == null || anchor == null || second.opcode != Opcode.IGET_OBJECT ||
        second.registerB != first.registerA || anchor.definingClass != config.type ||
        anchor.type != "Landroid/view/View;" || second.registerA == thisRegister
    ) {
        throw PatchException("$patch: the tip window's show() doesn't read the view to point at next.")
    }
    val check = instructions.getOrNull(2)
    if (check?.opcode != Opcode.IF_NEZ || (check as OneRegisterInstruction).registerA != second.registerA) {
        throw PatchException("$patch: the tip window's show() doesn't check its view for null.")
    }
    // Both reads are iget-object, whose registers fit 4 bits, so the hook's copies do too.
    return TipAnchor(config, anchor, second.registerA)
}

/**
 * Before a window's show() opens a tip, and only for a message tip (its content is the message
 * view), asks the extension about the view it points at, and returns without opening it when that
 * view is on your own profile and Hide tips on your profile is on. Popovers, action menus and
 * custom tips go on as before. The register is the one show() writes the view into first, so
 * nothing live is lost.
 */
internal fun MutableMethod.keepProfileTipsClosed(tips: MessageTips, tip: TipAnchor) {
    val register = "v${tip.register}"
    addInstructionsWithLabels(
        0,
        """
            iget-object $register, p0, ${tip.config.smali()}
            iget-object $register, $register, ${tips.content.smali()}
            instance-of $register, $register, ${tips.view}
            if-eqz $register, :show
            iget-object $register, p0, ${tip.config.smali()}
            iget-object $register, $register, ${tip.anchor.smali()}
            invoke-static { $register }, $ADD_BIO->skipTip(Landroid/view/View;)Z
            move-result $register
            if-eqz $register, :show
            return-void
        """,
        ExternalLabel("show", getInstruction(0)),
    )
}
