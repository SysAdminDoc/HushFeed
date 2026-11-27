/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.popups.writes
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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

/** Every section of your profile's menu is one of these. The class keeps its real name. */
internal const val PROFILE_SIDEBAR_SECTION = "Lcom/ss/android/ugc/aweme/sidebar/components/SideBarSectionComponent;"

/**
 * A section of your profile's menu making its view (#194): the rounded card around its cells, in
 * a slot with its own margins. A section whose only cell is hidden left that card behind empty.
 */
internal object ProfileMenuSectionViewFingerprint : Fingerprint(
    definingClass = PROFILE_SIDEBAR_SECTION,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    custom = { _, classDef -> classDef.type == PROFILE_SIDEBAR_SECTION },
)

/** Hands each section's view (p1) to the extension as it's made, so an emptied section collapses. */
internal fun MutableMethod.watchProfileMenuSections() = addInstruction(
    0,
    "invoke-static/range { p1 .. p1 }, $PROFILE_MENU_CELLS->section(Landroid/view/View;)V",
)

/** Each cell list in your profile's menu is one of these. The class keeps its real name. */
internal const val PROFILE_SIDEBAR_LIST = "Lcom/ss/android/ugc/aweme/sidebar/components/list/SideBarListComponent;"
private const val PROFILE_SIDEBAR_COMPONENT_CONFIG = "Lcom/ss/android/ugc/aweme/sidebar/model/SideBarComponentConfig;"

/**
 * A cell list of your profile's menu being filled (#194), with its view (p1) and its config. It
 * asks the cell question about each cell in turn and keeps the ones answered true.
 */
internal object ProfileMenuListFillFingerprint : Fingerprint(
    definingClass = PROFILE_SIDEBAR_LIST,
    returnType = "V",
    parameters = listOf("Landroid/view/View;", PROFILE_SIDEBAR_COMPONENT_CONFIG),
    custom = { method, classDef -> classDef.type == PROFILE_SIDEBAR_LIST && asksCellQuestion(method) },
)

/** Whether [method] asks a static (cell data, id) yes or no question, the shape of the cell question. */
internal fun asksCellQuestion(method: Method): Boolean = method.implementation?.instructions?.any {
    (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE) &&
        it.getReference<MethodReference>()?.let { called ->
            called.returnType == "Z" && called.parameterTypes.size == 2 && called.parameterTypes[1].toString() == "Ljava/lang/String;"
        } == true
} == true

/**
 * Tells the extension how a list of the menu was filled (#194): right after the list reads the
 * [question]'s answer for a cell, the answer, and before every return the list's view (p1). A list
 * left with nothing because its only cell was hidden then collapses its section, and every other
 * section is left alone. TikTok's own code runs as it was.
 */
internal fun MutableMethod.reportProfileMenuListFill(patch: String, question: MethodReference) {
    val code = implementation ?: throw PatchException("$patch: $definingClass->$name has no implementation")
    val view = code.registerCount - 2
    if (code.instructions.any { it.writes(view) }) {
        throw PatchException("$patch: $definingClass->$name writes over its view parameter")
    }
    val asks = code.instructions.withIndex().filter { (_, instruction) ->
        instruction.getReference<MethodReference>()?.let {
            it.definingClass == question.definingClass && it.name == question.name &&
                it.parameterTypes.map { type -> type.toString() } == question.parameterTypes.map { type -> type.toString() } && it.returnType == question.returnType
        } == true
    }.map { it.index }
    if (asks.size != 1) throw PatchException("$patch: $definingClass->$name asks the cell question ${asks.size} times")
    val read = code.instructions.elementAtOrNull(asks.single() + 1)
    if (read?.opcode != Opcode.MOVE_RESULT) throw PatchException("$patch: $definingClass->$name doesn't read the cell's answer")
    val answer = (read as OneRegisterInstruction).registerA
    val report = if (answer < 16) "invoke-static { v$answer }" else "invoke-static/range { v$answer .. v$answer }"
    addInstruction(asks.single() + 2, "$report, $PROFILE_MENU_CELLS->cellAnswered(Z)V")

    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("$patch: $definingClass->$name has no return to hook")
    returns.asReversed().forEach { index ->
        addInstructionsAtControlFlowLabel(
            index,
            "invoke-static/range { p1 .. p1 }, $PROFILE_MENU_CELLS->listFilled(Landroid/view/View;)V",
        )
    }
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
