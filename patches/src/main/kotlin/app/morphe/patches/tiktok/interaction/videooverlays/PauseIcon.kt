/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.navigation.branchTargets
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The feed cell's play button, the icon TikTok fades in over a video a tap has paused (#489). */
internal const val PLAY_BUTTON_COMPONENT = "Lcom/ss/android/ugc/feed/platform/panel/playbutton/PlayButtonComponent;"

/** Its stand-in on builds where the component is not ready yet; it hands over to the component when it is. */
internal const val PLAY_BUTTON_COMPONENT_TEMP = "Lcom/ss/android/ugc/feed/platform/panel/playbutton/PlayButtonComponentTemp;"

internal val PLAY_BUTTON_CLASSES = listOf(PLAY_BUTTON_COMPONENT, PLAY_BUTTON_COMPONENT_TEMP)

private const val VIEW_PROPERTY_ANIMATOR = "Landroid/view/ViewPropertyAnimator;"
private const val PAUSE_ICON_ALPHA =
    "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->pauseIconAlpha(F)F"

/**
 * Where one play button class answers its icon's alpha: the class's one `()F` method, which the
 * pause animation, Clear display and the mix page all read, and its `return` instructions.
 */
internal fun pauseIconAlphaReturns(method: Method): List<Int>? {
    if (method.returnType != "F" || method.parameterTypes.isNotEmpty()) return null
    if (AccessFlags.STATIC.isSet(method.accessFlags) || AccessFlags.ABSTRACT.isSet(method.accessFlags)) return null
    val code = method.implementation ?: return null
    if (code.tryBlocks.isNotEmpty()) return null
    val returns = code.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
    return returns.ifEmpty { null }
}

/**
 * Where one play button class shows the icon on a pause: the `()V` method that fades it in with
 * `animate().alpha(x).scaleX(1f)`, and the index of that `alpha(F)` call, whose float is the
 * icon's alpha. Hide is a separate method with a boolean, so it never matches.
 */
internal fun pauseIconFadeIn(method: Method): Int? {
    if (method.returnType != "V" || method.parameterTypes.isNotEmpty()) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val fades = code.indices.filter { i ->
        val alpha = code[i].getReference<MethodReference>() ?: return@filter false
        // TikTok chains scaleX(1f) straight on: move-result-object, the 1f, then the call.
        val scales = (i + 1..i + 3).any { at ->
            code.getOrNull(at)?.getReference<MethodReference>()?.let {
                it.definingClass == VIEW_PROPERTY_ANIMATOR && it.name == "scaleX"
            } == true
        }
        code[i].opcode == Opcode.INVOKE_VIRTUAL && alpha.definingClass == VIEW_PROPERTY_ANIMATOR &&
            alpha.name == "alpha" && alpha.parameterTypes.map(CharSequence::toString) == listOf("F") && scales
    }
    // The hook goes in front of the call, so nothing may jump straight to it.
    return fades.singleOrNull()?.takeIf { it !in method.branchTargets() }
}

/** One play button class's fade-in and alpha getter, found before anything is hooked. */
internal class PauseIconHooks(private val fadeIn: MutableMethod, private val getter: MutableMethod) {
    /**
     * Both pass the alpha through FeedOverlayControls, which answers zero while the switch is on.
     * The icon is still made VISIBLE, so TikTok's checks that read whether it shows (and so
     * whether the video is paused) answer as before, and a tap plays the video again.
     */
    fun hide() {
        hidePauseIconFadeIn(fadeIn)
        hidePauseIconAlpha(getter)
    }
}

/** Finds where one play button class fades its pause icon in and answers its alpha. */
internal fun MutableClass.pauseIconHooks(patch: String): PauseIconHooks {
    val getter = methods.singleOrNull { pauseIconAlphaReturns(it) != null }
        ?: throw PatchException("$patch: $type has no one alpha getter for its play icon.")
    val show = methods.singleOrNull { pauseIconFadeIn(it) != null }
        ?: throw PatchException("$patch: $type no longer fades its play icon in where it did.")
    return PauseIconHooks(show, getter)
}

internal fun hidePauseIconFadeIn(method: MutableMethod) {
    val at = pauseIconFadeIn(method)!!
    val alpha = (method.implementation!!.instructions.elementAt(at) as FiveRegisterInstruction).registerD
    method.addInstructions(
        at,
        """
            invoke-static/range {v$alpha .. v$alpha}, $PAUSE_ICON_ALPHA
            move-result v$alpha
        """,
    )
}

internal fun hidePauseIconAlpha(method: MutableMethod) {
    // Latest first, so the earlier indexes still point where they did.
    pauseIconAlphaReturns(method)!!.asReversed().forEach { at ->
        val alpha = (method.implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA
        method.addInstructionsAtControlFlowLabel(
            at,
            """
                invoke-static/range {v$alpha .. v$alpha}, $PAUSE_ICON_ALPHA
                move-result v$alpha
            """,
        )
    }
}
