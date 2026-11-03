/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** What each cell of your profile's menu is asked through. The interface keeps its real name. */
internal const val PROFILE_SIDEBAR_CELL_PROVIDER =
    "Lcom/ss/android/ugc/aweme/sidebar/spi/IProfileSidebarCellBusinessProvider;"

private const val PROFILE_MENU_CELLS = "Lapp/morphe/extension/tiktok/profile/ProfileMenuCells;"

/**
 * The question your profile's menu asks about each cell (#194), by the cell's id, while it builds
 * the list. It looks the id up among the cells' providers and asks the one it finds whether to
 * show, and the list leaves out a cell it answers false for. The first parameter is the cell's
 * server data, a Gson object whose class is renamed on every build.
 */
internal object ProfileMenuCellFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "Ljava/lang/String;"),
    // The provider call the custom check needs, so the patcher's index finds the callers.
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            definingClass = PROFILE_SIDEBAR_CELL_PROVIDER,
            returnType = "Z",
            parameters = emptyList(),
        ),
    ),
    custom = { method, _ -> isProfileMenuCellQuestion(method) },
)

internal fun isProfileMenuCellQuestion(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return false
    return method.implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.INVOKE_INTERFACE && instruction.getReference<MethodReference>()?.let {
            it.definingClass == PROFILE_SIDEBAR_CELL_PROVIDER && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } == true
    } == true
}

/**
 * Asks the extension about the cell's id (p1) before TikTok does. A hidden cell is answered false,
 * which is how the menu leaves out a cell TikTok doesn't offer, and every other cell goes on to
 * TikTok's own question.
 */
internal fun MutableMethod.hideProfileMenuCells(patch: String) = guardAtEntry(
    patch,
    "invoke-static/range { p1 .. p1 }, $PROFILE_MENU_CELLS->hide(Ljava/lang/String;)Z",
    """
        const/4 v0, 0x0
        return v0
    """,
)
