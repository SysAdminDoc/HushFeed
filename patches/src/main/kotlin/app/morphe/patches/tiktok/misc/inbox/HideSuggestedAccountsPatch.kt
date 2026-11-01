/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows hxreborn/hxreborn-tiktok-patches (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.getFreeRegisterProvider

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/inbox/InboxControls;"

/**
 * Answers the same `hide_inbox_suggested_accounts` switch as the row hiding in
 * `Hide inbox items`, one layer earlier: the widget is never built, so nothing flashes,
 * and the Activity and New followers pages are covered as well as the Inbox tab.
 */
@Suppress("unused")
val hideSuggestedAccountsPatch = bytecodePatch(
    name = "Hide suggested accounts",
    description = "Hides TikTok's People you may like suggestions in the Inbox, on profiles, " +
        "on the Friends tab and in the feed. It uses the same switch as Hide inbox items. Starts " +
        "off. Turn it on in Hushfeed settings > Inbox.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideSuggestedAccounts()V",
        )

        listOf(
            ActivityRecommendUserWidgetEnableFingerprint,
            NewFollowersRecommendUserWidgetEnableFingerprint,
            NewFollowersRecommendUserSkeletonEnableFingerprint,
            InboxRecommendUserWidgetEnableFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.hideInboxWidget("shouldShowSuggestedAccounts")
        }

        collapseSuggestionCells()
    }
}

private const val CELLS_EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/inbox/SuggestedAccountCells;"
private const val CELL_PACKAGE = "/relation/usercard/impl/cell/"
private const val CELL_SUFFIX = "RecUserCell;"
private const val TITLE_CELL = "/TitleAreaCell;"

private object SuggestionListCellBindFingerprint : Fingerprint(
    name = "onBindItemView",
    returnType = "V",
    custom = { method, classDef -> isSuggestionListCell(classDef.type) && method.implementation != null },
)

/**
 * Whether a class in TikTok's user card lists is one of the suggestion cells, or the title
 * cell that heads such a list. With only the cells collapsed, Profile > Find people on
 * 47.4.3 kept its "Suggested accounts" title over an empty list.
 */
internal fun isSuggestionListCell(type: String) =
    type.contains(CELL_PACKAGE) && (type.endsWith(CELL_SUFFIX) || type.endsWith(TITLE_CELL))

/**
 * Collapses every suggestion cell at its bind, whichever list it sits in.
 *
 * <p>The inbox gates above stop three lists from being built. The profile header, the
 * Friends tab and the feed's account cards each build their own list from a different
 * component with no shared source to anchor on, but every one of them is made of cells that
 * extend the real-named `AbsRecUserCell`, and every cell's `onBindItemView` bridge is a
 * PowerCell method with a real name. The list's `TitleAreaCell` is hooked the same way, so
 * no title is left over an empty list. Each bridge in that family is hooked, so a build that
 * adds a cell class is covered as long as it keeps the package and the suffix, and a build
 * that moves the cells fails here, title cell or not, rather than shipping a switch that does
 * nothing.
 *
 * @return how many bind methods were hooked.
 */
internal fun BytecodePatchContext.collapseSuggestionCells(): Int {
    val matches = SuggestionListCellBindFingerprint.matchAll()
    requireSuggestionCellBinds(matches.map { it.originalClassDef.type })
    matches.forEach { match ->
        match.method.collapseSuggestionCellAtBind()
    }
    return matches.size
}

/**
 * Fails the patch when no suggestion cell's bind was found, so the switch never ships doing
 * nothing. A title cell alone doesn't count: it would hide the heading and leave the people.
 */
internal fun requireSuggestionCellBinds(types: List<String>) {
    if (types.none { it.contains(CELL_PACKAGE) && it.endsWith(CELL_SUFFIX) }) {
        throw PatchException(
            "Hide suggested accounts: no suggestion cell binds under $CELL_PACKAGE; the cells moved.",
        )
    }
}

/**
 * Hands the cell to the extension as TikTok's bind returns, so what the bind did to the item
 * view is what gets collapsed. The Rect cells write their own top margin inside the bind; a
 * call ahead of it would have been undone. Every return is covered, in range form so a
 * register above v15 on some later build assembles. The call takes over each return's label:
 * TitleAreaCell's bind branches to its last return from six places, and a call put in front
 * of that label was jumped over, so the title stayed.
 */
internal fun MutableMethod.collapseSuggestionCellAtBind() {
    val implementation = implementation ?: return
    val self = implementation.registerCount - (parameters.size + 1)
    val returns = implementation.instructions.withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_VOID }
        .map { it.index }
    returns.sortedDescending().forEach { index ->
        addInstructionsAtControlFlowLabel(
            index,
            "invoke-static/range {v$self .. v$self}, " +
                "$CELLS_EXTENSION_CLASS_DESCRIPTOR->onBind(Ljava/lang/Object;)V",
        )
    }
}

/** A disabled hide switch must leave the native rollout and app-availability checks intact. */
internal fun MutableMethod.hideInboxWidget(extensionMethod: String) {
    val answerRegister = getFreeRegisterProvider(0, 1).getFreeRegister4Bit()
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->$extensionMethod()Z
            move-result v$answerRegister
            if-nez v$answerRegister, :native_widget_gate
            return v$answerRegister
        """,
        ExternalLabel("native_widget_gate", getInstruction(0)),
    )
}
