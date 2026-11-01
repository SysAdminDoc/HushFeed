/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Open the comments at full height"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/comment/CommentSheetHeight;"
private const val CONTEXT = "Landroid/content/Context;"

/** 0.68f and 0.73f, the two fixed shares of the screen TikTok's sheet modes 1 and 2 give it. */
private const val SHARE_68 = 0x3f2e147b
private const val SHARE_73 = 0x3f3ae148

private fun Method.loadsLiteral(value: Int) = implementation?.instructions?.any {
    (it.opcode == Opcode.CONST || it.opcode == Opcode.CONST_HIGH16) &&
        (it as NarrowLiteralInstruction).narrowLiteral == value
} == true

private fun Method.isStaticContextToInt() =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" &&
        parameterTypes.map(CharSequence::toString) == listOf(CONTEXT)

/**
 * The comment sheet's height in pixels (`LX/0FRY;->LIZ(Context)I` on 47.4.3). It reads the
 * sheet's mode from TikTok's server config and turns it into 68 or 73 percent of the content
 * view's height, a ratio from the config, or a fixed height from TikTok's resources, and keeps
 * the answer in a static field. The two shares are named nowhere else.
 */
internal object CommentSheetHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf(CONTEXT),
    // Both calls are in the method; naming them lets the patcher read only the classes that make
    // them before the literals are looked at.
    filters = listOf(
        methodCall(definingClass = "Landroid/view/View;", name = "findViewById"),
        methodCall(definingClass = "Landroid/content/res/Resources;", name = "getDimension"),
    ),
    custom = { method, _ -> method.loadsLiteral(SHARE_68) && method.loadsLiteral(SHARE_73) },
)

/**
 * Where the comment sheet sizes its panel (`DefaultCommentPageContainerFragment.gq1(IZ)V` on
 * 47.4.3). It asks the config for the mode, and when the mode is above 0 it sets the panel's
 * height from the method above and lets the list fill it by weight. A mode of 0 takes a fixed
 * height instead and never asks.
 */
internal object CommentPanelSizeFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/commentv2/commentpage/commonvideo/DefaultCommentPageContainerFragment;",
    returnType = "V",
    parameters = listOf("I", "Z"),
    filters = listOf(fieldAccess(definingClass = "Landroid/widget/LinearLayout\$LayoutParams;", name = "weight")),
)

/**
 * The sheet's second sizing pass (`DefaultCommentPageContainerFragment.YX()V` on 47.5.3). It runs
 * when the sheet has no comment source or opens with the video's description on top, the first
 * video after a cold start among them, and sets the same panel to 68 or 73 percent of the screen
 * after the method above gave it the full height (#261). The only method in the class that loads
 * the 68 percent share.
 */
internal object CommentPanelResizeFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/commentv2/commentpage/commonvideo/DefaultCommentPageContainerFragment;",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(fieldAccess(definingClass = "Landroid/widget/FrameLayout\$LayoutParams;", name = "height")),
    custom = { method, _ -> method.loadsLiteral(SHARE_68) },
)

/**
 * TikTok's comment input check (`LX/0FD7;->LIZ(LX/0FDd;)V` on 47.5.3). Once the sheet is up it
 * looks at the panel again, and a panel taller than its limit goes back to 68 or 73 percent of
 * the screen, logged as "height was set to". That is the two thirds the first video after a cold
 * start still opened at once the two passes above gave the full height (#261).
 */
internal object CommentInputCheckFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("height was set to "),
        fieldAccess(definingClass = "Landroid/view/ViewGroup\$LayoutParams;", name = "height"),
    ),
    custom = { method, _ -> method.loadsLiteral(SHARE_68) },
)

/** Where [method] writes [paramsClass]'s height, each with the register of the view whose params it is. */
internal fun panelHeightWrites(method: Method, paramsClass: String): List<Triple<Int, Int, Int>> {
    val instructions = method.implementation!!.instructions.toList()
    return instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IPUT &&
            instruction.getReference<FieldReference>()?.let {
                it.definingClass == paramsClass && it.name == "height"
            } == true
    }.map { (index, instruction) ->
        val paramsCall = instructions.subList(0, index).indexOfLast {
            it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.name == "getLayoutParams"
        }
        if (paramsCall < 0) throw PatchException("$PATCH: ${method.definingClass}->${method.name} writes a height on no view")
        Triple(
            index,
            (instruction as TwoRegisterInstruction).registerA,
            (instructions[paramsCall] as FiveRegisterInstruction).registerC,
        )
    }
}

/** The other static `(Context)I` in the height method's class: the content view's full height. */
internal fun fullHeightSibling(classDef: ClassDef, height: Method): Method {
    val siblings = classDef.methods.filter {
        it.isStaticContextToInt() && it.name != height.name && !it.loadsLiteral(SHARE_68)
    }
    return siblings.singleOrNull()
        ?: throw PatchException("$PATCH: ${classDef.type} has ${siblings.size} full height methods, not one")
}

/** The no-argument `()I` the height method calls first: the sheet's mode from TikTok's config. */
internal fun sheetModeCall(height: Method): MethodReference =
    height.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null
        instruction.getReference<MethodReference>()?.takeIf { it.parameterTypes.isEmpty() && it.returnType == "I" }
    } ?: throw PatchException("$PATCH: ${height.definingClass}->${height.name} asks for no sheet mode")

@Suppress("unused")
val commentSheetHeightPatch = bytecodePatch(
    name = "Open the comments at full height",
    description = "Opens the comment sheet at the full height of the screen instead of part " +
        "way up. Starts off. Turn it on in Hushfeed settings > Comments.",
) {
    category("Comments")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val height = CommentSheetHeightFingerprint.method
        val sibling = fullHeightSibling(CommentSheetHeightFingerprint.classDef, height)
        val mode = sheetModeCall(height)

        // Switched on, the height is the sibling's, worked out fresh each time, so the cached
        // share is neither read nor written and switching off again gives TikTok's height back.
        // The sibling's answer can take in the status bar, so it is trimmed to the room between
        // the window's system bars before the sheet gets it (#261).
        height.guardAtEntry(
            PATCH,
            "invoke-static {}, $EXTENSION->openAtFullHeight()Z",
            """
                invoke-static {p0}, ${sibling.definingClass}->${sibling.name}($CONTEXT)I
                move-result v0
                invoke-static {p0, v0}, $EXTENSION->belowStatusBar(${CONTEXT}I)I
                move-result v0
                return v0
            """,
        )

        // The panel only asks for that height when the mode is above 0, so the mode it reads is
        // lifted from 0 while the switch is on. The register is the panel method's own scratch,
        // written again before anything else reads it.
        val panel = CommentPanelSizeFingerprint.method
        val instructions = panel.implementation!!.instructions.toList()
        val modeIndex = instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_STATIC &&
                instruction.getReference<MethodReference>()?.let {
                    it.definingClass == mode.definingClass && it.name == mode.name &&
                        it.parameterTypes.isEmpty() && it.returnType == "I"
                } == true
        }
        val result = instructions.getOrNull(modeIndex + 1)
        if (modeIndex < 0 || result?.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("$PATCH: ${panel.definingClass}->${panel.name} reads no sheet mode")
        }
        val register = (result as OneRegisterInstruction).registerA
        panel.addInstructions(
            modeIndex + 2,
            """
                invoke-static {v$register}, $EXTENSION->heightMode(I)I
                move-result v$register
            """,
        )

        // The second sizing pass writes its share over that height, so the height it writes is
        // swapped for the room between the system bars while the switch is on (#261). The panel
        // is the view whose layout params get the height.
        // The input check shrinks it again later on, so both of its writes get the same swap.
        // Writes are hooked last first so the earlier indexes still hold.
        fun keepPanelHeight(method: MutableMethod, writes: List<Triple<Int, Int, Int>>) {
            if (writes.isEmpty()) throw PatchException("$PATCH: ${method.definingClass}->${method.name} writes no panel height")
            for ((index, heightRegister, panelRegister) in writes.sortedByDescending { it.first }) {
                if (heightRegister > 15 || panelRegister > 15) {
                    throw PatchException("$PATCH: ${method.definingClass}->${method.name} panel registers out of reach")
                }
                method.addInstructions(
                    index,
                    """
                        invoke-static {v$panelRegister, v$heightRegister}, $EXTENSION->panelHeight(Landroid/view/View;I)I
                        move-result v$heightRegister
                    """,
                )
            }
        }
        val resize = CommentPanelResizeFingerprint.method
        keepPanelHeight(resize, panelHeightWrites(resize, "Landroid/widget/FrameLayout\$LayoutParams;").take(1))
        val inputCheck = CommentInputCheckFingerprint.method
        keepPanelHeight(inputCheck, panelHeightWrites(inputCheck, "Landroid/view/ViewGroup\$LayoutParams;"))

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentsFullHeight()V",
        )
    }
}
