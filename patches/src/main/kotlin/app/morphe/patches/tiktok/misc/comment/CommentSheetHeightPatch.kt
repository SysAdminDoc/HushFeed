/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val height = CommentSheetHeightFingerprint.method
        val sibling = fullHeightSibling(CommentSheetHeightFingerprint.classDef, height)
        val mode = sheetModeCall(height)

        // Switched on, the height is the sibling's, worked out fresh each time, so the cached
        // share is neither read nor written and switching off again gives TikTok's height back.
        height.guardAtEntry(
            PATCH,
            "invoke-static {}, $EXTENSION->openAtFullHeight()Z",
            """
                invoke-static {p0}, ${sibling.definingClass}->${sibling.name}($CONTEXT)I
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

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentsFullHeight()V",
        )
    }
}
