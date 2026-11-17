/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.interaction.publishdate.GridCellOpenFingerprint
import app.morphe.patches.tiktok.interaction.publishdate.ProfileTabCellOpenFingerprint
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Account cleanup"
internal const val QUICK_REMOVE = "Lapp/morphe/extension/tiktok/privacy/QuickRemove;"
internal const val REPOST_LIST_CELL = "Lcom/ss/android/ugc/aweme/upvote/detail/reposttab/RepostListCell;"
internal const val REPOST_LIST_VIEW_MODEL = "Lcom/ss/android/ugc/aweme/upvote/detail/reposttab/RepostListViewModel;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val VIEW = "Landroid/view/View;"

/**
 * The Reposts tab's cell tap (#401): a static click lambda holding the cell and its row, which
 * reads the row's video straight from the row it holds and opens it, logging "click". The other
 * clicks on a Reposts cell (its note and the note's guide bubble) get the row from the cell.
 */
internal object RepostCellOpenFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("click"),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 2 &&
            method.parameterTypes[1].toString() == VIEW && repostTapParts(method) != null
    },
)

/**
 * The Reposts view model's removal of one video by id, which TikTok runs when you take a repost
 * back while your own Reposts tab is open. It compares the tab's owner with the signed-in uid
 * first, which is where [repostOwnerField] reads the owner from.
 */
internal object RepostRemoveFingerprint : Fingerprint(
    definingClass = REPOST_LIST_VIEW_MODEL,
    returnType = "I",
    parameters = listOf("Ljava/lang/String;"),
    custom = { method, _ -> method.callsNamed("getCurUserId") && method.callsNamed("listRemoveItemAt") },
)

/** Where the Reposts cell tap keeps the cell and the row, and how it reaches the video. */
internal data class RepostTapParts(
    val cell: FieldReference,
    val viewModel: MethodReference,
    val row: FieldReference,
    val rowType: String,
    val video: FieldReference,
)

private fun Method.callsNamed(name: String) =
    implementation?.instructions?.any { it.getReference<MethodReference>()?.name == name } == true

/**
 * The cell, read from a field of the lambda and cast to [REPOST_LIST_CELL], whose view model
 * getter follows; and the row, read from another field of the lambda, cast, and its video read.
 * Null when the method has another shape.
 */
internal fun repostTapParts(method: Method): RepostTapParts? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun ownField(index: Int) = code.getOrNull(index)?.takeIf { it.opcode == Opcode.IGET_OBJECT }
        ?.getReference<FieldReference>()?.takeIf { it.definingClass == method.definingClass }
    var cell: FieldReference? = null
    var viewModel: MethodReference? = null
    var row: FieldReference? = null
    var rowType: String? = null
    var video: FieldReference? = null
    for (index in 1 until code.size - 1) {
        val instruction = code[index]
        if (instruction.opcode != Opcode.CHECK_CAST) continue
        val type = instruction.getReference<TypeReference>()?.type ?: continue
        val field = ownField(index - 1) ?: continue
        if (type == REPOST_LIST_CELL && cell == null) {
            val getter = code[index + 1].takeIf { it.opcode == Opcode.INVOKE_VIRTUAL }
                ?.getReference<MethodReference>() ?: continue
            if (getter.definingClass != REPOST_LIST_CELL || getter.parameterTypes.isNotEmpty() ||
                getter.returnType != REPOST_LIST_VIEW_MODEL) continue
            cell = field
            viewModel = getter
        } else if (type != REPOST_LIST_CELL && row == null) {
            val read = code[index + 1].takeIf { it.opcode == Opcode.IGET_OBJECT }
                ?.getReference<FieldReference>() ?: continue
            if (read.definingClass != type || read.type != AWEME) continue
            row = field
            rowType = type
            video = read
        }
    }
    if (cell == null || viewModel == null || row == null || rowType == null || video == null) return null
    if (cell == row) return null
    return RepostTapParts(cell, viewModel, row, rowType, video)
}

/** The tab owner's uid on the Reposts view model: the String field the removal compares first. */
internal fun repostOwnerField(remove: Method): FieldReference? =
    remove.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@firstNotNullOfOrNull null
        instruction.getReference<FieldReference>()
            ?.takeIf { it.definingClass == REPOST_LIST_VIEW_MODEL && it.type == "Ljava/lang/String;" }
    }

private fun FieldReference.smali() = "$definingClass->$name:$type"
private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Tap a video to remove it (#401): the profile grids' and the Reposts tab's cell taps ask the
 * extension first, and a tap it takes never opens the video. True when every hook went in; on
 * a build the patches don't declare, a missing tap leaves the switch out instead of failing.
 */
internal fun hookQuickRemove(versionName: String): Boolean {
    val declared = versionName in declaredVersions()
    var hooked = 0
    for (fingerprint in listOf(GridCellOpenFingerprint, ProfileTabCellOpenFingerprint)) {
        val open = if (declared) fingerprint.method else fingerprint.methodOrNull ?: continue
        open.guardAtEntry(
            PATCH,
            "invoke-static/range { p0 .. p2 }, $QUICK_REMOVE->tap(Ljava/lang/Object;Ljava/lang/Object;$VIEW)Z",
            "return-void",
        )
        hooked++
    }
    if (hookRepostTap(declared)) hooked++
    if (hooked != 3) println("[$PATCH] Tap a video to remove it is left out on $versionName: $hooked of 3 taps found.")
    return hooked == 3
}

private fun hookRepostTap(declared: Boolean): Boolean {
    val tap = if (declared) RepostCellOpenFingerprint.method else RepostCellOpenFingerprint.methodOrNull ?: return false
    val remove = if (declared) RepostRemoveFingerprint.method else RepostRemoveFingerprint.methodOrNull ?: return false
    val parts = repostTapParts(tap) ?: throw PatchException("$PATCH: the Reposts cell tap changed shape")
    val owner = repostOwnerField(remove) ?: throw PatchException("$PATCH: no owner uid on the Reposts view model")
    // v0 to v3 hold the view model, the row, its video and the owner; p1 is the tapped view.
    tap.requireLocals(PATCH, 4)
    if (tap.implementation!!.registerCount > 16) {
        throw PatchException("$PATCH: the Reposts cell tap's view sits above v15")
    }
    tap.guardAtEntry(
        PATCH,
        """
            iget-object v0, p0, ${parts.cell.smali()}
            check-cast v0, $REPOST_LIST_CELL
            invoke-virtual { v0 }, ${parts.viewModel.smali()}
            move-result-object v0
            iget-object v1, p0, ${parts.row.smali()}
            move-object v2, v1
            check-cast v2, ${parts.rowType}
            iget-object v2, v2, ${parts.video.smali()}
            iget-object v3, v0, ${owner.smali()}
            invoke-static { v0, v1, v2, v3, p1 }, $QUICK_REMOVE->repostTap(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;$VIEW)Z
        """,
        "return-void",
    )
    return true
}
