/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val AMOLED_INPUT_BAR_EXTENSION = "Lapp/morphe/extension/tiktok/theme/AmoledInputBar;"

/** The "Add comment..." bar under a post opened from search, a profile or a link. */
internal const val DETAIL_BOTTOM_FAKE_INPUT =
    "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/DetailBottomFakeInput;"

private const val CONSTRAINT_LAYOUT = "Landroidx/constraintlayout/widget/ConstraintLayout;"

/**
 * The field the detail bar's onViewCreated keeps its row in: the one ConstraintLayout it stores,
 * the row holding the "Add comment..." pill. Null unless there is exactly one.
 */
internal fun Method.detailBarRowField(): FieldReference? {
    if (name != "onViewCreated" || returnType != "V" ||
        parameterTypes.map { it.toString() } != listOf("Landroid/view/View;")
    ) return null
    return implementation?.instructions
        ?.filter { it.opcode == Opcode.IPUT_OBJECT }
        ?.mapNotNull { it.getReference<FieldReference>() }
        ?.filter { it.definingClass == definingClass && it.type == CONSTRAINT_LAYOUT }
        ?.distinctBy { it.name }
        ?.singleOrNull()
}

internal object DetailBottomBarFingerprint : Fingerprint(
    definingClass = DETAIL_BOTTOM_FAKE_INPUT,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    custom = { method, _ -> method.detailBarRowField() != null },
)

/** Before each return, hands the bar's row and [argb] to the extension. */
internal fun MutableMethod.paintDetailBar(row: FieldReference, argb: Int) {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("AMOLED dark theme: the comment bar's setup never returns.")
    // v0 and v1 are locals of the method, free once it is returning. p0 sits past v15 on 47.5.3,
    // out of iget-object's reach, so it is copied down first.
    returns.reversed().forEach { index ->
        addInstructionsAtControlFlowLabel(
            index,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, ${row.definingClass}->${row.name}:${row.type}
                const v1, ${smaliInt(argb)}
                invoke-static { v0, v1 }, $AMOLED_INPUT_BAR_EXTENSION->paint(Landroid/view/View;I)V
            """,
        )
    }
}

/** An int literal the way baksmali writes one: an opaque color is negative, so `-0x1000000` for black. */
internal fun smaliInt(value: Int): String =
    if (value < 0) "-0x" + (-value.toLong()).toString(16) else "0x" + value.toString(16)

/**
 * The comment bar under a post opened from search, a profile or a link, painted [color] in dark
 * mode (#171).
 *
 * TikTok leaves that bar see-through: a video there stops above it, but a photo post runs on
 * under it and showed through. The bar's colors come from the transparent `dv` and `v9`, which
 * hundreds of other views share, so the resource side can't fill it. This hands the bar's row to
 * the extension once TikTok has set it up, and the extension fills it when the page is dark.
 * Collections use a subclass whose setup runs this one. It runs only as part of AMOLED dark theme,
 * with that patch's color, and refuses an unread build the way the resource side does.
 */
internal fun amoledInputBarPatch(color: () -> String?) = bytecodePatch {
    dependsOn(sharedExtensionPatch)
    execute {
        darkBackgroundColors(packageMetadata.versionName)
        val argb = composeArgb(amoledColor(color())).toInt()
        DetailBottomBarFingerprint.method.paintDetailBar(
            DetailBottomBarFingerprint.originalMethod.detailBarRowField()!!,
            argb,
        )
    }
}
