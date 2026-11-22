/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.downloads.VIEW_HOLDER
import app.morphe.patches.tiktok.interaction.downloads.boundPostField
import app.morphe.patches.tiktok.interaction.downloads.gridPickFieldProblem
import app.morphe.patches.tiktok.interaction.downloads.gridPickProblem
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val GRID_CORNERS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feed/GridCorners;"

/**
 * Hands a profile grid cell's root view to Round the corners on profile grids (#487) as the
 * cell's holder binds its next post. The bind is ProfileGridHolderBindFingerprint's, which every
 * profile, Liked and Favorites cell runs through, and the spot is the top of it, where the grid
 * picker reads the same view. [original] is the bind before any patch touched it, held to the
 * picker's shape: an instance bind of a RecyclerView.ViewHolder with locals to spare. The view
 * goes through a local, so it works whatever register `this` sits in.
 */
internal fun MutableMethod.hookGridCorners(original: Method, superclassOf: (String) -> String?) {
    original.gridPickProblem(superclassOf)?.let { throw PatchException("Always show publish date: $it.") }
    addInstructions(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $VIEW_HOLDER->itemView:Landroid/view/View;
            invoke-static { v0 }, $GRID_CORNERS_CLASS_DESCRIPTOR->cell(Landroid/view/View;)V
        """,
    )
}

/**
 * Where a Favorites collection folder's cell bind (CollectionGridHolderBindFingerprint's, X/0hmm.r0
 * on 47.9.3) takes the corners hook: right after its opening read of the post off the holder,
 * before the null check on it. Advanced downloads matches the bind by that opening read and reads
 * the post's field from it, so the read stays first. The read is told from Advanced downloads'
 * own copy of it, when that patch went first, by the `if-nez` on its register that follows it.
 * Null when the bind has no such pair.
 */
internal fun Method.collectionCornersIndex(post: FieldReference): Int? {
    val code = implementation?.instructions?.toList() ?: return null
    val self = implementation!!.registerCount - 1
    return code.indices.firstOrNull { at ->
        val read = code[at]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        val check = code.getOrNull(at + 1)
        read.opcode == Opcode.IGET_OBJECT && field != null &&
            field.definingClass == post.definingClass && field.name == post.name && field.type == post.type &&
            (read as TwoRegisterInstruction).registerB == self &&
            check?.opcode == Opcode.IF_NEZ && (check as OneRegisterInstruction).registerA == read.registerA
    }?.plus(1)
}

/**
 * Hands a Favorites collection folder's cell holder to Round the corners on profile grids (#487)
 * as it binds its post, at [collectionCornersIndex]. [original] is the bind before any patch
 * touched it. The holder goes over as `this` through a range call, so no register is borrowed.
 */
internal fun MutableMethod.hookCollectionGridCorners(original: Method, superclassOf: (String) -> String?) {
    original.gridPickFieldProblem(superclassOf)?.let { throw PatchException("Always show publish date: $it.") }
    val post = original.boundPostField()!!
    val at = collectionCornersIndex(post)
        ?: throw PatchException("Always show publish date: the collection folder bind's post check wasn't found.")
    addInstructions(at, "invoke-static/range { p0 .. p0 }, $GRID_CORNERS_CLASS_DESCRIPTOR->collectionCell(Ljava/lang/Object;)V")
}
