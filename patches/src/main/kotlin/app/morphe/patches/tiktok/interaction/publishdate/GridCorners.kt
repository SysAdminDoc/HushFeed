/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.downloads.VIEW_HOLDER
import app.morphe.patches.tiktok.interaction.downloads.gridPickProblem
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.iface.Method

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
