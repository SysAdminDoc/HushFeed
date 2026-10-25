/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.sharesheet.SHARE_LIVE_QUALITY
import app.morphe.patches.tiktok.interaction.sharesheet.addOwnedShareAction
import app.morphe.patches.tiktok.interaction.sharesheet.ownedShareActionsPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.ControlFlow
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.findMutableMethodOf
import app.morphe.util.literalReads
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.BitSet

private const val EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveControls;"
private const val RECORD_EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveRecordChip;"
private const val PANEL_THEME = "Lapp/morphe/extension/tiktok/live/LivePanelTheme;"
private const val QUALITY_LIST_COLORS = "Lapp/morphe/extension/tiktok/live/LiveQualityListColors;"

/** A LIVE room page, one fragment per room in the room pager, real-named on 47.1.4. */
internal const val LIVE_PLAY_FRAGMENT = "Lcom/bytedance/android/livesdk/chatroom/ui/LivePlayFragment;"

/** The room model a LIVE page holds, with the creator and the stream addresses, real-named on 47.1.4. */
internal const val LIVE_ROOM_TYPE = "Lcom/bytedance/android/livesdkapi/depend/model/live/Room;"

/**
 * A LIVE room page coming to the front (#37). The pager resumes a page as its room is shown, so the
 * hook hands the page to the recording button, which finds the room (and with it the creator and the
 * stream addresses) in the page's single Room field when the viewer taps Record. The page also
 * names the room's activity, which is where the button goes. The hook only notes the page.
 */
internal object LivePlayFragmentResumeFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_FRAGMENT,
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

/**
 * The one field of [fragment] that holds its room. The recording button reads the room from the
 * page by that type, so a page with none or two would leave it recording the wrong LIVE or none.
 */
internal fun liveRoomField(fragment: ClassDef): String {
    val rooms = fragment.fields.filter { it.type == LIVE_ROOM_TYPE }
    return rooms.singleOrNull()?.name
        ?: throw PatchException("LIVE controls: expected one Room field in ${fragment.type}, found ${rooms.map { it.name }}")
}

/** The feed LIVE preview's guide, real-named on 47.0.3, 47.1.3 and 47.1.4. */
internal const val LIVE_PREVIEW_GUIDE_VM = "Lcom/ss/android/ugc/aweme/feed/adapter/widget/guide/LivePreviewGuideEnterVM;"

/**
 * Starts the countdown that takes a LIVE preview in the feed into its room: it checks the feed
 * (homepage_hot for For You, homepage_live for the LIVE tab behind
 * live_preview_page_enable_auto_entering_guide, and it returns on homepage_follow), then either
 * hands a smart countdown to LivePreviewEnterRoomGuideManager or arms a fixed one of
 * live_preview_page_auto_entering_request_delay seconds. When that fires, BottomTipsWidget runs
 * its AutoEnterProgressBar for live_preview_page_auto_entering_guide_duration seconds, and the
 * bar's listener enters the room with "enter_room". Renamed on every build (O63 on 47.0.3, E83 on
 * 47.1.3 and 47.1.4); the only method that loads the enable key. It already returns early on
 * the Following feed and once a countdown has started, so returning at entry is a state TikTok
 * handles: no countdown, no bar, and the preview stays a preview until it's tapped.
 */
internal object LivePreviewAutoEnterFingerprint : Fingerprint(
    definingClass = LIVE_PREVIEW_GUIDE_VM,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    strings = listOf("live_preview_page_enable_auto_entering_guide", "homepage_follow"),
)

/** TikTok's server setting for its newer LIVE quality picker, real-named on 47.1.4. */
internal const val LIVE_RESOLUTION_SELECTION_SETTING =
    "Lcom/bytedance/android/livesdk/livesetting/watchlive/LiveShowResolutionSelectionSetting;"

/**
 * Whether a room's menu offers the newer quality picker, LiveTalentDefinitionSelectionDialog.
 * The menu builder (X.0hG2.LIZ on 47.1.4) offers the older picker only when the stream itself asks
 * for it (LiveCoreSDKData.Options.showQualityButton), and the newer one only in a video room
 * whose stream doesn't, when this answers true. It answers the server's
 * enable_resolution_selection, false unless TikTok turns it on, which is why only some rooms
 * offer a quality at all (#57).
 */
internal object LiveResolutionSelectionFingerprint : Fingerprint(
    definingClass = LIVE_RESOLUTION_SELECTION_SETTING,
    name = "isEnable",
    returnType = "Z",
    parameters = listOf(),
)

/**
 * Whether a pick in that picker carries to the next room: when this answers true,
 * PullStreamService hands a video room the last quality picked instead of the stream's default.
 * It answers isEnable() and the server's persist_across_rooms.
 */
internal object LiveResolutionPersistFingerprint : Fingerprint(
    definingClass = LIVE_RESOLUTION_SELECTION_SETTING,
    name = "needPersistAcrossRooms",
    returnType = "Z",
    parameters = listOf(),
)

/** TikTok's server setting for the clear screen entry in a portrait LIVE room's menu, real-named on 47.1.4. */
internal const val LIVE_CLEAR_SCREEN_ENTRY_SETTING =
    "Lcom/bytedance/android/livesdk/setting/LiveClearScreenFeatureEntryCompletionSetting;"

/**
 * Whether a portrait LIVE room's menu offers clear screen, which hides the room's chat, gifts and
 * buttons and leaves the stream on its own (#57). It answers the server's
 * live_clear_screen_feature_entry_completion, false unless TikTok turns it on. The room's panel
 * builder (PortraitAudienceInteractionFragment.n00 on 47.1.4) adds the entry, labeled clear or
 * exit by IClearScreenService's state, only when this answers true, and the room's widget loader
 * reads it the same way. The entry turns clear screen on and off through that service.
 */
internal object LiveClearScreenEntryFingerprint : Fingerprint(
    definingClass = LIVE_CLEAR_SCREEN_ENTRY_SETTING,
    name = "getValue",
    returnType = "Z",
    parameters = listOf(),
)

/** The host side of the LIVE SDK's resources, real-named on 47.1.4. */
internal const val LIVE_HOST_RESOURCE = "Lcom/ss/android/ugc/aweme/live/livehostimpl/LiveHostResource;"

/**
 * Whether a LIVE panel takes the LIVE design system's dark theme (X.0Jz4.LIZ on 47.1.4, in the
 * theme provider the host hands the LIVE SDK). Every theme-aware LIVE fragment and dialog asks it
 * before picking the dark or the light overlay. It answers TikTok's dark mode and then the page
 * TikTok finds for the panel's context, and the LIVE room pages (LivePlayRootFragment,
 * BaseLiveSdkSceneFragment) and LIVE activities always say no, so the share menu, the fan club and
 * the first gift dialog opened light in a dark app (#159). The provider is the class holding a
 * LiveHostResource; this answer is the one whose first call is the provider's own dark mode check,
 * which tells it from the provider's other (Context)Z answer.
 */
internal object LiveDarkThemeFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(methodCall(opcode = Opcode.INVOKE_STATIC, definingClass = LIVE_HOST_RESOURCE)),
    custom = { method, classDef -> isLiveDarkThemeAnswer(method, classDef) },
)

internal fun isLiveDarkThemeAnswer(method: Method, classDef: ClassDef): Boolean {
    if (classDef.fields.none { it.type == LIVE_HOST_RESOURCE }) return false
    val instructions = method.implementation?.instructions?.toList() ?: return false
    val first = instructions.firstOrNull() ?: return false
    val own = (first as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return first.opcode == Opcode.INVOKE_VIRTUAL && own.definingClass == classDef.type &&
        own.returnType == "Z" && own.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
        instructions.any {
            it.opcode == Opcode.INVOKE_STATIC &&
                ((it as ReferenceInstruction).reference as MethodReference).definingClass == LIVE_HOST_RESOURCE
        }
}

/** The base of every themed LIVE dialog (LiveDialogFragment and the rest), real-named on 47.1.4. */
internal const val LIVE_THEMED_DIALOG = "Lcom/bytedance/android/live/design/resource/theme/LiveThemeAwareDialogFragment;"

/** The base of LIVE's sheet pages (LiveSheetFragment) and of the room's own fragments, real-named on 47.1.4. */
internal const val LIVE_THEMED_FRAGMENT = "Lcom/bytedance/android/live/design/resource/theme/LiveThemeAwareFragment;"

/** LIVE's sheet page, the fan club sheets and most newer LIVE panels, real-named on 47.1.4. */
internal const val LIVE_SHEET_FRAGMENT = "Lcom/bytedance/android/live/design/view/sheet/LiveSheetFragment;"

/**
 * The layout inflater a themed LIVE dialog or fragment hands its views (#159). In a dark app it
 * inflates through TikTok's light LIVE theme, with force, when the page says light, and through
 * its own unwrapped inflater when the page says dark, which leaves the room's light colors in
 * place: TikTok only adds the dark theme to the room's theme without force. Every return hands
 * its inflater to the extension, which wraps it in the panel's own (dark) overlay while Dark LIVE
 * panels is on.
 */
internal object LiveThemedDialogInflaterFingerprint : Fingerprint(
    definingClass = LIVE_THEMED_DIALOG,
    name = "onGetLayoutInflater",
    returnType = "Landroid/view/LayoutInflater;",
    parameters = listOf("Landroid/os/Bundle;"),
)

/**
 * [LiveThemedDialogInflaterFingerprint] for LIVE's fragments; the extension touches sheet pages
 * and the pages under the daily ranking, and leaves the room's own fragments alone.
 */
internal object LiveThemedFragmentInflaterFingerprint : Fingerprint(
    definingClass = LIVE_THEMED_FRAGMENT,
    name = "onGetLayoutInflater",
    returnType = "Landroid/view/LayoutInflater;",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** The androidx fragment base every fragment builds on, real-named on 47.4.3. */
internal const val ANDROIDX_FRAGMENT = "Landroidx/fragment/app/Fragment;"

/**
 * Where every fragment gets its layout inflater: onGetLayoutInflater's answer, stored and returned
 * (#159). A plain fragment takes the room activity's inflater even under a LIVE panel, so the
 * daily ranking's lists (GiftListFragment, a plain androidx fragment under each ranking page)
 * took the room's dark app theme and read white on the ranking's white sheet.
 */
internal object FragmentInflaterFingerprint : Fingerprint(
    definingClass = ANDROIDX_FRAGMENT,
    name = "performGetLayoutInflater",
    returnType = "Landroid/view/LayoutInflater;",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** The older LIVE quality picker, Share > Video quality in rooms whose stream asks for it, real-named on 47.1.4. */
internal const val LIVE_DEFINITION_DIALOG = "Lcom/bytedance/android/livesdk/definition/LiveDefinitionSelectionDialog;"

/** The quality list's view being set up, which the extension watches so its rows stay readable in dark mode. */
internal object LiveDefinitionListFingerprint : Fingerprint(
    definingClass = LIVE_DEFINITION_DIALOG,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/** The newer LIVE quality picker, a room menu's Video quality on 47.5.3, real-named. */
internal const val LIVE_TALENT_DEFINITION_DIALOG =
    "Lcom/bytedance/android/livesdk/definition/talent/LiveTalentDefinitionSelectionDialog;"

/**
 * The newer picker's view being set up. Its rows sit on a fixed white card inside the dark sheet,
 * and their text follows the room's theme, so in dark mode they read light on white (S22,
 * 2026-10-24, #57).
 */
internal object LiveTalentDefinitionListFingerprint : Fingerprint(
    definingClass = LIVE_TALENT_DEFINITION_DIALOG,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * Every return-object in [method], last first, with the register it returns. A return that
 * isn't a plain v0..v15 register, or a method whose `this` sits above v15, can't take the
 * four-bit call the hook adds, so it's refused instead of written.
 */
internal fun inflaterReturns(method: Method): List<Pair<Int, Int>> {
    val body = method.implementation ?: throw PatchException("LIVE controls: ${method.definingClass} has no inflater body")
    if (body.registerCount - 2 > 15) throw PatchException("LIVE controls: ${method.definingClass}'s inflater keeps this above v15")
    val returns = body.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
        .reversed()
    if (returns.isEmpty() || returns.any { it.second > 15 }) {
        throw PatchException("LIVE controls: ${method.definingClass}'s inflater returns don't fit the hook: $returns")
    }
    return returns
}

/** Hands every inflater [inflater] returns to the extension first, on each return's own label. */
internal fun hookPanelInflater(inflater: MutableMethod) {
    inflaterReturns(inflater).forEach { (index, register) ->
        inflater.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static {p0, v$register}, $PANEL_THEME->darkLivePanel(Ljava/lang/Object;Landroid/view/LayoutInflater;)Landroid/view/LayoutInflater;
                move-result-object v$register
            """,
        )
    }
}

/**
 * Hands the inflater onGetLayoutInflater answers to the extension right after the call, before
 * the fragment stores it, so the stored copy its views inflate with is the same one. A shape
 * that doesn't fit the four-bit call is refused instead of written.
 */
internal fun hookFragmentInflater(method: MutableMethod) {
    val body = method.implementation ?: throw PatchException("LIVE controls: the fragment inflater has no body")
    val code = body.instructions.toList()
    val call = code.indexOfFirst {
        it.opcode == Opcode.INVOKE_VIRTUAL &&
            ((it as ReferenceInstruction).reference as MethodReference).name == "onGetLayoutInflater"
    }
    val result = code.getOrNull(call + 1) as? OneRegisterInstruction
    if (call < 0 || code[call + 1].opcode != Opcode.MOVE_RESULT_OBJECT || result == null ||
        result.registerA > 15 || body.registerCount - 2 > 15
    ) {
        throw PatchException("LIVE controls: the fragment inflater's shape changed")
    }
    method.addInstructions(
        call + 2,
        """
            invoke-static {p0, v${result.registerA}}, $PANEL_THEME->darkLivePage(Ljava/lang/Object;Landroid/view/LayoutInflater;)Landroid/view/LayoutInflater;
            move-result-object v${result.registerA}
        """,
    )
}

/** The daily ranking's tab model, real-named on 47.4.3. */
internal const val SUB_RANK_TAB = "Lcom/bytedance/android/livesdk/rank/model/SubRankTabInfo;"

/**
 * The daily ranking's tab strip coloring a tab (#159, the anonymous X.1GYJ on 47.4.3): the picked
 * tab when [field] is selectedTextColor, the others for defaultTextColor. The colors the server
 * leaves out come from LIVE's color lookup on the ranking page's getContext(), the room activity,
 * so over the room the switch darkened the unpicked tab's gray pill took the dark theme's white
 * on the ranking's white sheet (Login2, 2026-10-19).
 */
internal class RankTabColorFingerprint(field: String) : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = SUB_RANK_TAB, name = field)),
    custom = { method, _ -> rankTabContext(method, field) != null },
)

internal val RankTabPickedFingerprint = RankTabColorFingerprint("selectedTextColor")
internal val RankTabUnpickedFingerprint = RankTabColorFingerprint("defaultTextColor")

/** The page's getContext() at [call], the register holding the page and the one its context moves into. */
internal class RankTabContext(val call: Int, val page: Int, val context: Int)

/**
 * In [method], the one getContext() it asks of a page read from a field of its own class, with
 * the register holding the page and the one the context moves into, when [method] reads the
 * tab's [field] and hands that context to a static (int, Context) color call. Null when [method]
 * isn't the tab strip's, and null too for a shape the hook can't take safely: the context moved
 * over the page's own register (the hook would hand the extension the context as the page), or
 * any later read of the context that isn't a null test or a Context parameter (a cast to Activity
 * would throw on the wrapper the extension hands back).
 */
internal fun rankTabContext(method: Method, field: String): RankTabContext? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun ref(at: Int) = (code[at] as? ReferenceInstruction)?.reference
    val reads = code.indices.any { at ->
        val read = ref(at) as? FieldReference
        code[at].opcode == Opcode.IGET_OBJECT && read?.definingClass == SUB_RANK_TAB && read.name == field
    }
    if (!reads) return null
    val call = code.indices.filter { at ->
        val asked = ref(at) as? MethodReference
        code[at].opcode == Opcode.INVOKE_VIRTUAL && asked?.name == "getContext" && asked.parameterTypes.isEmpty() &&
            asked.returnType == "Landroid/content/Context;"
    }.singleOrNull() ?: return null
    if (code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val page = (code[call] as FiveRegisterInstruction).registerC
    val context = (code[call + 1] as OneRegisterInstruction).registerA
    if (page == context) return null
    if (!(call + 2 until code.size).all { contextReadIsSafe(code[it], context) }) return null
    val read = (call - 1 downTo 0).firstOrNull {
        code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == page
    } ?: return null
    if (code[read].opcode != Opcode.IGET_OBJECT || (ref(read) as FieldReference).definingClass != method.definingClass) return null
    val colors = code.indices.any { at ->
        val color = ref(at) as? MethodReference
        code[at].opcode == Opcode.INVOKE_STATIC && color?.returnType == "I" &&
            color.parameterTypes.map(CharSequence::toString) == listOf("I", "Landroid/content/Context;") &&
            (code[at] as FiveRegisterInstruction).registerD == context
    }
    return if (colors) RankTabContext(call, page, context) else null
}

/**
 * Whether [instruction] leaves [register] alone, tests it for null, or hands it on only where a
 * Context is declared. Asked of every instruction after the getContext() to the method's end,
 * past any later write too, so a branch around a write can't hide a read.
 */
private fun contextReadIsSafe(instruction: Instruction, register: Int): Boolean {
    if (register !in registersRead(instruction)) return true
    if (instruction.opcode == Opcode.IF_EQZ || instruction.opcode == Opcode.IF_NEZ) return true
    val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val arguments = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        else -> return false
    }
    val static = instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE
    val slots = (if (static) emptyList() else listOf(called.definingClass)) +
        called.parameterTypes.map(CharSequence::toString)
    var slot = 0
    for (type in slots) {
        val width = if (type == "J" || type == "D") 2 else 1
        if (register in arguments.subList(minOf(slot, arguments.size), minOf(slot + width, arguments.size)) &&
            type != "Landroid/content/Context;"
        ) {
            return false
        }
        slot += width
    }
    return true
}

/** The registers [instruction] reads, wide halves included only as their first register. */
private fun registersRead(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    is ThreeRegisterInstruction -> if (instruction.opcode.setsRegister()) {
        listOf(instruction.registerB, instruction.registerC)
    } else {
        listOf(instruction.registerA, instruction.registerB, instruction.registerC)
    }
    is TwoRegisterInstruction -> if (instruction.opcode.setsRegister()) {
        listOf(instruction.registerB)
    } else {
        listOf(instruction.registerA, instruction.registerB)
    }
    is OneRegisterInstruction -> when {
        // check-cast reads the register it narrows; the rest that write one read nothing.
        instruction.opcode == Opcode.CHECK_CAST -> listOf(instruction.registerA)
        instruction.opcode.setsRegister() -> emptyList()
        else -> listOf(instruction.registerA)
    }
    else -> emptyList()
}

/**
 * [parameter] and every local below [locals] that only ever holds a copy of it, or nothing when the
 * method writes [parameter] itself. A local starts unset, so the verifier proves each read of one
 * written only by these copies holds what the parameter came in with.
 */
private fun holdersOf(code: List<Instruction>, parameter: Int, locals: Int): Set<Int> {
    if (code.any { writesRegister(it, parameter) }) return emptySet()
    val copies = { instruction: Instruction, register: Int ->
        instruction.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
            (instruction as TwoRegisterInstruction).registerA == register && instruction.registerB == parameter
    }
    return setOf(parameter) + (0 until locals).filter { register ->
        code.any { copies(it, register) } && code.all { !writesRegister(it, register) || copies(it, register) }
    }
}

/** Whether [instruction] writes [register], as itself or as the high half of a wide pair. */
private fun writesRegister(instruction: Instruction, register: Int): Boolean {
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return (instruction.opcode.setsRegister() && target == register) ||
        (instruction.opcode.setsWideRegister() && target + 1 == register)
}

/**
 * Hands the page and its context to the extension right after the tab strip asks for it, so every
 * color the tab reads resolves through the context the extension hands back. A branch landing
 * after the call would skip the hook, and registers above v15 don't fit its four-bit call, so
 * either shape is refused instead of written.
 */
internal fun hookRankTab(method: MutableMethod, field: String) {
    val tab = rankTabContext(method, field)
        ?: throw PatchException("LIVE controls: the ranking tab's context for $field wasn't found")
    val next = method.implementation!!.instructions[tab.call + 2]
    if (tab.page > 15 || tab.context > 15 || next.location.labels.isNotEmpty()) {
        throw PatchException("LIVE controls: the ranking tab's context for $field doesn't fit the hook")
    }
    method.addInstructions(
        tab.call + 2,
        """
            invoke-static {v${tab.page}, v${tab.context}}, $PANEL_THEME->stockContext(Ljava/lang/Object;Landroid/content/Context;)Landroid/content/Context;
            move-result-object v${tab.context}
        """,
    )
}

/** The daily ranking's team list, the trending tab among its lists, real-named on 47.4.3. */
internal const val TEAM_RANK_LIST = "Lcom/bytedance/android/live/rank/impl/list/fragment/list/TeamRankListFragment;"

/** The base every ranking list builds on, the team list included, real-named on 47.4.3. */
internal const val RANK_LIST = "Lcom/bytedance/android/live/rank/impl/list/fragment/list/RankListFragment;"

/**
 * The team list's view being set up (#159). It colors its bottom bar's texts through LIVE's color
 * lookup on its own getContext(), the room activity, so over the room the switch darkened the
 * bar's name and count took the dark theme's light colors on the ranking's white sheet (Login2,
 * 2026-10-19).
 */
internal object TeamRankListViewFingerprint : Fingerprint(
    definingClass = TEAM_RANK_LIST,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * The list's bottom bar getting its tag (#159, RX on 47.4.3). Every ranking list builds on it, the
 * team list included, and it tints the tag's icon through LIVE's color lookup on its own
 * getContext(), the room activity, so over the room the switch darkened the icon would take the
 * dark theme's white on the ranking's white sheet.
 */
internal object RankListBottomTagFingerprint : Fingerprint(
    definingClass = RANK_LIST,
    returnType = "V",
    parameters = listOf("Lcom/bytedance/android/livesdk/rank/list/model/RankListV2Response\$BottomBarTag;"),
)

/** The page holding the daily ranking's tabs and lists, real-named on 47.4.3. */
internal const val RANK_PAGE = "Lcom/bytedance/android/live/rank/impl/list/fragment/page/RankPageFragment;"

/**
 * The page filling its bottom row for a host outside the top 20 (#159, Nd on 47.4.3, the one
 * method naming "reach_top20"). It loads and tints the chevron after the row's text through LIVE's
 * lookups on its own getContext(), the room activity, so over the room the switch darkened the
 * chevron would take the dark theme's white on the ranking's white sheet.
 */
internal object RankPageReachTopFingerprint : Fingerprint(
    definingClass = RANK_PAGE,
    returnType = "V",
    strings = listOf("reach_top20"),
)

/** A ranking list's gap, the bottom row's word on how far the host is from the next place, real-named on 47.4.3. */
internal const val RANK_GAP = "Lcom/bytedance/android/livesdk/rank/list/model/RankListV2Response\$Gap;"

/** The text view LIVE's ranking rows show their text in, as TikTok's own code names it. */
private const val APPCOMPAT_TEXT_VIEW = "Landroidx/appcompat/widget/AppCompatTextView;"

/**
 * A ranking list's bottom row putting the info icon after a host's gap text (#159, the anonymous
 * X.1GYD.LJII on 47.4.3, handed the list's context, its controller, the row's text and the gap).
 * It loads and tints the icon through LIVE's lookups on that context, the room activity, so over
 * the room the switch darkened the icon after the trending tab's "99+" row went white on the
 * ranking's white sheet (Login2, 2026-10-19).
 */
internal object RankGapInfoIconFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Lcom/bytedance/ies/sdk/datachannel/DataChannel;",
        "Lcom/bytedance/android/live/rank/impl/list/controller/RankListController;",
        "L",
        RANK_GAP,
    ),
    filters = listOf(fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = RANK_GAP, name = "bottomBarFaq")),
    custom = { method, _ -> gapInfoIcon(method) != null },
)

/**
 * In [method], the registers of its Context parameter and of the row text it's handed, when it
 * reads the gap's FAQ, asks that text what it shows, and hands the context to a static (int,
 * Context) lookup. Null for any other method, and for a shape the hook can't take safely: no local
 * to copy the text through, a context above v15 or written again, or the context read anywhere it
 * isn't a null test or a Context parameter (a cast to Activity would throw on the wrapper the
 * extension hands back). The text call only anchors the row text: the hook hands it on as an
 * Object, and the extension tests it's a View itself.
 */
internal fun gapInfoIcon(method: Method): Pair<Int, Int>? {
    val body = method.implementation ?: return null
    if (method.parameterTypes.size != 5) return null
    val code = body.instructions.toList()
    val self = body.registerCount - 6
    val context = self + 1
    val text = self + 4
    fun ref(at: Int) = (code[at] as? ReferenceInstruction)?.reference
    val faq = code.indices.any { at ->
        val read = ref(at) as? FieldReference
        code[at].opcode == Opcode.IGET_OBJECT && read?.definingClass == RANK_GAP && read.name == "bottomBarFaq"
    }
    val texts = holdersOf(code, text, self)
    val shown = code.indices.any { at ->
        val asked = ref(at) as? MethodReference
        code[at].opcode == Opcode.INVOKE_VIRTUAL && asked?.definingClass == APPCOMPAT_TEXT_VIEW &&
            asked.name == "getText" && (code[at] as FiveRegisterInstruction).registerC in texts
    }
    val lookups = code.indices.any { at ->
        val lookup = ref(at) as? MethodReference
        code[at].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && lookup != null &&
            lookup.parameterTypes.map(CharSequence::toString) == listOf("I", "Landroid/content/Context;") &&
            registersRead(code[at]).getOrNull(1) == context
    }
    val fits = self >= 1 && context <= 15 && code.none { writesRegister(it, context) } &&
        code.all { contextReadIsSafe(it, context) }
    return if (faq && shown && lookups && fits) context to text else null
}

/**
 * Hands the row's text and the context its list handed over to the extension before anything
 * else runs, so the icon loads and tints through the context the extension hands back: the row
 * text's own light one under the ranking, the list's anywhere else. The text goes through v0,
 * still unset at the start, since it sits above v15.
 */
internal fun hookGapInfoIcon(method: MutableMethod) {
    val (context, text) = gapInfoIcon(method)
        ?: throw PatchException("LIVE controls: the ranking row's info icon context wasn't found")
    method.addInstructions(
        0,
        """
            move-object/from16 v0, v$text
            invoke-static {v0, v$context}, $PANEL_THEME->stockViewContext(Ljava/lang/Object;Landroid/content/Context;)Landroid/content/Context;
            move-result-object v$context
        """,
    )
}

/**
 * Every getContext() [method] asks of its own `this` and hands to a static (int, Context) lookup
 * of a color or a drawable, as the index of each call's move-result and the register it moves
 * into. `this` is p0 or a local that only ever holds a copy of it: with p0 above v15 the page's
 * reach_top20 row moves it into v7 first and asks v7. Refused instead of hooked: none at all, a
 * context that moves into `this` or above v15, a `this` above v15, or a context read anywhere it
 * isn't a null test or a Context parameter, on any path before it's written again (a cast to
 * Activity would throw on the wrapper the extension hands back).
 */
internal fun pageContexts(method: Method): List<Pair<Int, Int>> {
    val body = method.implementation ?: throw PatchException("LIVE controls: ${method.definingClass}.${method.name} has no body")
    val code = body.instructions.toList()
    val self = body.registerCount - method.parameterTypes.size - 1
    val pages = holdersOf(code, self, self)
    val calls = code.indices.filter { at ->
        val asked = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_VIRTUAL && asked?.name == "getContext" && asked.parameterTypes.isEmpty() &&
            asked.returnType == "Landroid/content/Context;" && (code[at] as FiveRegisterInstruction).registerC in pages
    }
    val contexts = calls.map { call ->
        val result = code.getOrNull(call + 1)
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException("LIVE controls: ${method.definingClass}'s getContext() at $call keeps no result")
        }
        call + 1 to (result as OneRegisterInstruction).registerA
    }
    val fits = contexts.isNotEmpty() && calls.all { (code[it] as FiveRegisterInstruction).registerC <= 15 } &&
        contexts.all { (result, context) ->
            val reads = method.literalReads(result)
            context !in pages && context <= 15 && result + 1 < code.size &&
                reads.all { contextReadIsSafe(code[it], context) } &&
                reads.any { at ->
                    val lookup = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
                    code[at].opcode == Opcode.INVOKE_STATIC && lookup != null &&
                        (lookup.returnType == "I" || lookup.returnType.startsWith("L")) &&
                        lookup.parameterTypes.map(CharSequence::toString) == listOf("I", "Landroid/content/Context;")
                }
        }
    if (!fits) throw PatchException("LIVE controls: ${method.definingClass}'s contexts don't fit the hook: $contexts")
    return contexts
}

/**
 * Hands `this` and each context [pageContexts] finds to the extension right after its move-result,
 * the same answer the ranking's tab strip gets: the page's light context under the ranking, its
 * own context anywhere else. `this` goes as the register the call asked, p0 or its copy. A branch
 * landing right after a move-result would skip its hook, so that shape is refused. Written last
 * first, so each index still points at its move-result and the call before it.
 */
internal fun hookPageContexts(method: MutableMethod) {
    val contexts = pageContexts(method)
    if (contexts.any { (result, _) -> method.implementation!!.instructions[result + 1].location.labels.isNotEmpty() }) {
        throw PatchException("LIVE controls: a branch lands after one of ${method.definingClass}'s contexts")
    }
    contexts.reversed().forEach { (result, context) ->
        val page = (method.implementation!!.instructions[result - 1] as FiveRegisterInstruction).registerC
        method.addInstructions(
            result + 1,
            """
                invoke-static {v$page, v$context}, $PANEL_THEME->stockContext(Ljava/lang/Object;Landroid/content/Context;)Landroid/content/Context;
                move-result-object v$context
            """,
        )
    }
}

/**
 * The base of LIVE's list row binders making a row (#159, the anonymous X.136J on 47.4.3). It
 * skips the inflater its list hands it and inflates the row through LIVE's shared inflater, whose
 * context is the app or the room, so over the room the switch darkened the daily ranking's trending
 * rows took the dark theme and their names read white on its white sheet (Login2, 2026-10-19). Its
 * one (int, Boolean) constructor keeps the row's layout and whether it attaches, and every way it
 * makes the row ends in its own (View, ViewGroup) holder call on the list.
 */
internal object LiveRowBinderFingerprint : Fingerprint(
    returnType = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;",
    parameters = listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;"),
    filters = listOf(fieldAccess(definingClass = LIVE_ROW_PRELOAD)),
    custom = { method, classDef -> rowBinder(method, classDef) != null },
)

/** A row binder's layout and attach fields and the holder call it makes rows with. */
internal class RowBinder(val layout: FieldReference, val attach: FieldReference, val holder: MethodReference)

/** LIVE's list performance settings, whose row preload switch the row binders' base reads; real-named on 47.4.3. */
internal const val LIVE_ROW_PRELOAD = "Lcom/bytedance/android/livesdk/livesetting/performance/LiveRecyclerviewPerformanceOptSwitchSetting;"

/**
 * [method]'s row binder parts: the int and Boolean fields its class's one (int, Boolean)
 * constructor stores its arguments in, when [method] reads that layout field and LIVE's row
 * preload settings too, and the one (View, ViewGroup) holder method of its own class it calls on
 * `this` and the list. Null for anything else, a lookalike base outside LIVE included.
 */
internal fun rowBinder(method: Method, classDef: ClassDef): RowBinder? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val made = classDef.directMethods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "Ljava/lang/Boolean;")
    }?.implementation ?: return null
    val madeThis = made.registerCount - 3
    fun stored(opcode: Opcode, argument: Int, type: String) = made.instructions.mapNotNull {
        val field = (it as? ReferenceInstruction)?.reference as? FieldReference
        val store = it as? TwoRegisterInstruction
        if (it.opcode == opcode && store?.registerA == madeThis + argument && store.registerB == madeThis &&
            field?.definingClass == classDef.type && field.type == type
        ) field else null
    }.singleOrNull()
    val layout = stored(Opcode.IPUT, 1, "I") ?: return null
    val attach = stored(Opcode.IPUT_OBJECT, 2, "Ljava/lang/Boolean;") ?: return null
    val reads = code.any {
        val field = (it as? ReferenceInstruction)?.reference as? FieldReference
        it.opcode == Opcode.IGET && field?.definingClass == layout.definingClass && field.name == layout.name &&
            field.type == layout.type
    }
    val live = code.any { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == LIVE_ROW_PRELOAD }
    if (!reads || !live) return null
    val self = method.implementation!!.registerCount - 3
    val holder = code.filter {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == classDef.type &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "Landroid/view/ViewGroup;") &&
            call.returnType == method.returnType && (it as FiveRegisterInstruction).registerCount == 3 &&
            it.registerC == self && it.registerE == self + 2
    }.map { (it as ReferenceInstruction).reference as MethodReference }.distinct().singleOrNull() ?: return null
    return RowBinder(layout, attach, holder)
}

/**
 * Asks the extension for the row first, with the list and the binder's layout and attach fields:
 * a row it inflates through the list's own light context goes straight to the binder's holder call
 * and back, and null runs TikTok's code untouched. The added code takes v0 and v1 before TikTok
 * writes anything, and the list must fit its four-bit calls, so a shape where either doesn't hold
 * is refused instead of written. [rowBinder]'s four-bit holder call on the list already keeps the
 * list under v16; the check stays for a shape that loosens it. So does a binder that does anything
 * to the holder before handing it back, which the hook would skip.
 */
internal fun hookRowBinder(method: MutableMethod, classDef: ClassDef) {
    val binder = rowBinder(method, classDef)
        ?: throw PatchException("LIVE controls: the LIVE row binder's parts weren't found")
    val registers = method.implementation!!.registerCount
    if (registers - 1 > 15 || registers - 3 < 2) {
        throw PatchException("LIVE controls: the LIVE row binder's registers don't fit the hook")
    }
    val code = method.implementation!!.instructions.toList()
    val handedBack = code.indices.filter {
        code[it].opcode == Opcode.INVOKE_VIRTUAL && (code[it] as ReferenceInstruction).reference == binder.holder
    }.all {
        val result = code.getOrNull(it + 1)
        val back = code.getOrNull(it + 2)
        result?.opcode == Opcode.MOVE_RESULT_OBJECT && back?.opcode == Opcode.RETURN_OBJECT &&
            (result as OneRegisterInstruction).registerA == (back as OneRegisterInstruction).registerA
    }
    if (!handedBack) {
        throw PatchException("LIVE controls: the LIVE row binder does more with its holder than hand it back")
    }
    method.addInstructionsWithLabels(
        0,
        """
            iget v0, p0, ${binder.layout}
            iget-object v1, p0, ${binder.attach}
            invoke-static {p2, v0, v1}, $PANEL_THEME->stockRow(Landroid/view/ViewGroup;ILjava/lang/Boolean;)Landroid/view/View;
            move-result-object v0
            if-eqz v0, :morphe_tiktok_row
            invoke-virtual {p0, v0, p2}, ${binder.holder}
            move-result-object v0
            return-object v0
        """,
        ExternalLabel("morphe_tiktok_row", method.getInstruction(0)),
    )
}

/** The LIVE room activity, real-named on 47.4.3. */
internal const val LIVE_PLAY_ACTIVITY = "Lcom/ss/android/ugc/aweme/live/LivePlayActivity;"

/**
 * The LIVE room activity's setTheme (#159). It ignores the theme it's handed and builds the
 * room's own: a base room theme, then styles for how the room was entered and its color scheme,
 * each applied with force and none of them dark. So TikTok's generic sheets over a room, Share
 * among them, open white in a dark app, which the LIVE panel overlay can't reach.
 */
internal object LivePlayActivityThemeFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_ACTIVITY,
    name = "setTheme",
    returnType = "V",
    parameters = listOf("I"),
)

/** The base of TikTok's screens, real-named on 47.4.3. */
internal const val BASE_ACTIVITY = "Lcom/bytedance/ies/foundation/activity/BaseActivity;"

/**
 * TikTok's dark mode for its screens (X.060v.LIZ on 47.4.3): in dark mode, a screen that says it
 * supports dark mode gets TikTok's dark app theme through setTheme. The room says no. The style
 * is read from here at patch time.
 */
internal object AppDarkThemeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(BASE_ACTIVITY),
    filters = listOf(methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "isDarkModeSupported", parameters = listOf(), returnType = "Z")),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) && appDarkTheme(method) != null },
)

/**
 * The style [method] hands setTheme after it asks the screen isDarkModeSupported, read from the
 * constant last written to setTheme's argument; null when [method] isn't TikTok's dark mode.
 */
internal fun appDarkTheme(method: Method): Int? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    fun calls(at: Int, name: String, parameters: List<String>, returnType: String): Boolean {
        val call = (instructions[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return instructions[at].opcode == Opcode.INVOKE_VIRTUAL && call.name == name && call.returnType == returnType &&
            call.parameterTypes.map(CharSequence::toString) == parameters
    }
    val asked = instructions.indices.firstOrNull { calls(it, "isDarkModeSupported", listOf(), "Z") } ?: return null
    val set = (asked + 1 until instructions.size).firstOrNull { calls(it, "setTheme", listOf("I"), "V") } ?: return null
    val argument = (instructions[set] as FiveRegisterInstruction).registerD
    val written = (set - 1 downTo asked + 1).map { instructions[it] }
        .firstOrNull { it is OneRegisterInstruction && it.registerA == argument && it.opcode.setsRegister() }
    return (written as? NarrowLiteralInstruction)?.narrowLiteral?.takeIf { it ushr 24 == 0x7f }
}

/**
 * Puts the room hook right after the room's last setTheme, at the branch join a skipped cold start
 * style lands on too. Every room theme setTheme hands over sits on TikTok's light app theme and
 * re-applies all of it, so the dark app theme has to come after the last of them. The room's color
 * scheme styles still apply after the hook, so its own accents stay.
 */
internal fun hookRoomTheme(setTheme: MutableMethod) {
    val instructions = setTheme.implementation?.instructions?.toList()
        ?: throw PatchException("LIVE controls: the room's setTheme has no body")
    val join = roomThemeJoin(instructions)
    if (join < 0) throw PatchException("LIVE controls: the room's setTheme never sets a room theme")
    setTheme.addInstructionsAtControlFlowLabel(
        join,
        "invoke-static/range {p0 .. p0}, $PANEL_THEME->darkLiveRoom(Landroid/app/Activity;)V",
    )
}

/**
 * Where the room's own themes are done: right after the last super setTheme the code falls
 * through from. One room theme sits at the method's end and jumps back up (on 47.4.3 the one for
 * a room entered without the smooth transition), so it reaches this point through the join.
 */
internal fun roomThemeJoin(instructions: List<Instruction>): Int {
    val falls = instructions.indices.filter { at ->
        val call = (instructions[at] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[at].opcode == Opcode.INVOKE_SUPER && call?.name == "setTheme" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("I") &&
            instructions.getOrNull(at + 1)?.opcode !in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    }
    return falls.lastOrNull()?.plus(1) ?: -1
}

/**
 * The LIVE room activity's onConfigurationChanged (#159). The room handles a dark mode flip itself
 * (uiMode is in its configChanges), so androidx's AppCompat reworks its theme inside the super
 * call: it moves the room's resources to the new night mode, calls setTheme with the room's last
 * theme, which reaches the room hook, then applies that same theme again with force, putting the
 * room's light style back over the dark app theme the hook just added. A room left open while
 * the system theme flipped opened Share white (S22, 2026-10-23).
 */
internal object LivePlayActivityConfigurationFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_ACTIVITY,
    name = "onConfigurationChanged",
    returnType = "V",
    parameters = listOf("Landroid/content/res/Configuration;"),
)

/** Hands the room to the room hook again right after its super onConfigurationChanged returns. */
internal fun hookRoomConfiguration(changed: MutableMethod) {
    val instructions = changed.implementation?.instructions?.toList()
        ?: throw PatchException("LIVE controls: the room's onConfigurationChanged has no body")
    val parent = instructions.indexOfFirst {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode == Opcode.INVOKE_SUPER && call?.name == "onConfigurationChanged"
    }
    if (parent < 0) throw PatchException("LIVE controls: the room's onConfigurationChanged never calls its super")
    changed.addInstruction(
        parent + 1,
        "invoke-static/range {p0 .. p0}, $PANEL_THEME->darkLiveRoom(Landroid/app/Activity;)V",
    )
}

/**
 * Spark's light or dark decision for one page, SparkContext.LJIIZILJ(Context) on 47.5.3 (#159).
 * It takes the page schema's force_theme_style first, then asks TikTok's theme name lookup with
 * the page's own fragment, popup or activity context, and the page's container color (LJJJJI)
 * and the theme its Lynx page reads (LJJJJ) both follow its answer. Over a room the room hook
 * darkened, a shop room's viewer ranking with its Top customers tab drew dark with its native
 * tab titles still dark (S22, 2026-10-20). SparkContext keeps its name; the decision is its one
 * Context method reading the fix_spark_issues_when_recreate switch, and its answer's enum, whose
 * constants keep their names, is renamed each build (X.0Grb on 47.4.3, X.0HD4 on 47.5.3).
 */
internal object SparkPageThemeFingerprint : Fingerprint(
    definingClass = SPARK_CONTEXT,
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("fix_spark_issues_when_recreate"),
)

internal const val SPARK_CONTEXT = "Lcom/bytedance/hybrid/spark/SparkContext;"

/**
 * Asks the extension first whether the page stays stock, and answers the theme enum's LIGHT for
 * it before Spark decides; every other page goes on to Spark's own decision.
 */
internal fun hookSparkPage(decision: MutableMethod, theme: ClassDef) {
    val implementation = decision.implementation
    if (AccessFlags.STATIC.isSet(decision.accessFlags) || implementation == null ||
        decision.definingClass != SPARK_CONTEXT ||
        decision.parameterTypes.map(CharSequence::toString) != listOf("Landroid/content/Context;") ||
        implementation.registerCount < 3
    ) {
        throw PatchException("LIVE controls: ${decision.definingClass}->${decision.name} isn't Spark's page theme decision")
    }
    if (theme.type != decision.returnType || theme.fields.none {
            it.name == "LIGHT" && it.type == theme.type && AccessFlags.STATIC.isSet(it.accessFlags)
        }
    ) {
        throw PatchException("LIVE controls: ${theme.type} has no LIGHT for Spark's page theme")
    }
    decision.addInstructionsWithLabels(
        0,
        """
            invoke-static/range {p0 .. p1}, $PANEL_THEME->sparkKeepsStock(Ljava/lang/Object;Landroid/content/Context;)Z
            move-result v0
            if-eqz v0, :morphe_spark_decides
            sget-object v0, ${theme.type}->LIGHT:${theme.type}
            return-object v0
        """,
        ExternalLabel("morphe_spark_decides", decision.getInstruction(0)),
    )
}

/**
 * Hands the quality list's view to the extension as the dialog sets it up. The method keeps the
 * view above v15 on 47.1.4, so the call takes the range form.
 */
internal fun hookQualityList(viewCreated: MutableMethod) {
    viewCreated.addInstruction(
        0,
        "invoke-static/range {p1 .. p1}, $QUALITY_LIST_COLORS->readableQualityList(Landroid/view/View;)V",
    )
}

/** The LIVE profile card's header cell, the bio among it, real-named on 47.4.3. */
internal const val LIVE_PROFILE_HEADER = "Lcom/bytedance/android/livesdk/profile/cell/HeaderCell;"

private const val COLOR_SPAN = "Landroid/text/style/ForegroundColorSpan;"

/**
 * The profile card's bio being shown (HeaderCell.LJIJJLI on 47.4.3, #159). A translated bio ends
 * in a "See original" link. Its ForegroundColorSpan gets the right color from the card's theme,
 * but the link also carries a replacement span (X.0Y3Y), which Android draws without color spans,
 * in the view's own text color: the light theme's dark one, on the dark card.
 */
internal object LiveProfileBioFingerprint : Fingerprint(
    definingClass = LIVE_PROFILE_HEADER,
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ -> bioLinkColor(method) != null },
)

/** The link color call at [call], the register its result moves into, and the one holding the bio view. */
internal class BioLinkColor(val call: Int, val color: Int, val view: Int)

/**
 * In [method], the static (int, Context) color call feeding the bio link's ForegroundColorSpan, the
 * register its result moves into and the span takes, and the register holding the bio view: the
 * receiver of the setSuffixText the link text goes through, read from a field of the view's type
 * before the call and written by nothing up to setSuffixText, with no branch, switch case or
 * handler landing between the read and the instruction after the result, where one would skip
 * the hook or reach it with other registers. Null when [method] isn't the bio.
 */
internal fun bioLinkColor(method: Method): BioLinkColor? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun ref(at: Int) = (code[at] as? ReferenceInstruction)?.reference
    fun params(at: Int) = (ref(at) as? MethodReference)?.parameterTypes?.map(CharSequence::toString)
    fun writes(at: Int, register: Int): Boolean {
        val written = code[at] as? OneRegisterInstruction ?: return false
        return (code[at].opcode.setsRegister() && written.registerA == register) ||
            (code[at].opcode.setsWideRegister() && written.registerA + 1 == register)
    }
    val span = code.indices.firstOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && (ref(it) as? TypeReference)?.type == COLOR_SPAN
    } ?: return null
    val made = (span + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.INVOKE_DIRECT && (ref(it) as? MethodReference)?.definingClass == COLOR_SPAN
    } ?: return null
    val call = (made - 1 downTo span + 1).firstOrNull {
        code[it].opcode == Opcode.INVOKE_STATIC && (ref(it) as? MethodReference)?.returnType == "I" &&
            params(it) == listOf("I", "Landroid/content/Context;")
    } ?: return null
    val suffix = (made + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.INVOKE_VIRTUAL && (ref(it) as? MethodReference)?.name == "setSuffixText" &&
            params(it) == listOf("Ljava/lang/CharSequence;")
    } ?: return null
    if (code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT) return null
    val color = (code[call + 1] as OneRegisterInstruction).registerA
    val init = code[made] as? FiveRegisterInstruction ?: return null
    if (init.registerCount != 2 || init.registerD != color || (call + 2 until made).any { writes(it, color) }) return null
    val view = (code[suffix] as? FiveRegisterInstruction)?.registerC ?: return null
    val read = (call - 1 downTo 0).firstOrNull { writes(it, view) } ?: return null
    if (code[read].opcode != Opcode.IGET_OBJECT ||
        (ref(read) as? FieldReference)?.type != (ref(suffix) as MethodReference).definingClass
    ) return null
    if ((call + 1 until suffix).any { writes(it, view) }) return null
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val targets = landings(method, code, addresses)
    if ((read + 1..call + 2).any { addresses[it] in targets }) return null
    return BioLinkColor(call, color, view)
}

/**
 * Hands the bio link's color and the bio view to the extension right after the color is picked.
 * While Dark LIVE panels is on in dark mode, the extension makes it the view's text color, which
 * the link's replacement span draws with. The hook writes no register. The color and the view
 * are both named by non-range invokes (the span's init and setSuffixText), so they're v0..v15
 * and fit the hook's call.
 */
internal fun hookBioLink(bio: MutableMethod) {
    val link = bioLinkColor(bio) ?: throw PatchException("LIVE controls: the profile card's bio link color wasn't found")
    bio.addInstructions(
        link.call + 2,
        "invoke-static {v${link.color}, v${link.view}}, $PANEL_THEME->bioLinkColor(ILjava/lang/Object;)V",
    )
}

/** The profile card's label model, real-named on 47.4.3. */
internal const val PROFILE_CARD_LABEL = "Lcom/bytedance/android/livesdkapi/depend/model/live/ProfileCardLabel;"

/**
 * The profile card setting up its labels, the shop rating and sold count chips among them
 * (HeaderCell.LJIIJ on 47.4.3, #159). The server sends each label's colors as they look on the
 * light card, a faint see-through chip under dark text and a dark icon, so they read dark on the
 * dark card (Login2, 2026-10-19).
 */
internal object LiveProfileLabelsFingerprint : Fingerprint(
    definingClass = LIVE_PROFILE_HEADER,
    returnType = "Z",
    custom = { method, _ -> cardLabels(method) != null },
)

/** The labels' iterator() at [call], the register holding the label list and the one holding the label strip. */
internal class CardLabels(val call: Int, val labels: Int, val strip: Int)

/**
 * In [method], the iterator() over the list whose items it casts to the card's label model, the
 * register holding that list, and the register holding the view whose getContext() it asks last
 * before the iterator(): the strip that draws the labels. Null when [method] isn't the card's
 * labels or the iterator() isn't the one whose next() feeds the cast, and null too for a shape
 * the hook can't take safely: the strip's register written between its getContext() and the
 * iterator(), or a branch, switch case or handler landing after the getContext() up to the
 * iterator(), where one would skip the hook or reach it with the strip's register holding
 * something else, or either call in the range form. Both take the non-range form, so both
 * registers fit the hook's four-bit call.
 */
internal fun cardLabels(method: Method): CardLabels? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun ref(at: Int) = (code[at] as? ReferenceInstruction)?.reference
    val cast = code.indices.firstOrNull {
        code[it].opcode == Opcode.CHECK_CAST && (ref(it) as? TypeReference)?.type == PROFILE_CARD_LABEL
    } ?: return null
    fun asks(at: Int, type: String, name: String) = (ref(at) as? MethodReference)?.let {
        it.definingClass == type && it.name == name && it.parameterTypes.isEmpty()
    } == true
    // Found in either form and then held to the non-range one, so a range call is refused rather
    // than passed over for an earlier call on some other list or view.
    val call = (cast - 1 downTo 0).firstOrNull { asks(it, "Ljava/lang/Iterable;", "iterator") } ?: return null
    val context = (call - 1 downTo 0).firstOrNull { asks(it, "Landroid/view/View;", "getContext") } ?: return null
    if (code[call].opcode != Opcode.INVOKE_INTERFACE || code[context].opcode != Opcode.INVOKE_VIRTUAL) return null
    val labels = (code[call] as FiveRegisterInstruction).registerC
    val strip = (code[context] as FiveRegisterInstruction).registerC
    // The iterator() has to be the one the cast's loop walks, not some other list's: its result
    // taken straight off, and the last next() before the cast asked of that iterator.
    val walker = code.getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }
        ?.let { (it as OneRegisterInstruction).registerA } ?: return null
    val next = (cast - 1 downTo call + 2).firstOrNull { asks(it, "Ljava/util/Iterator;", "next") } ?: return null
    val walked = (code[next] as? FiveRegisterInstruction)?.registerC ?: (code[next] as? RegisterRangeInstruction)?.startRegister
    if (walked != walker) return null
    val written = (context + 1 until call).any {
        val write = code[it] as? OneRegisterInstruction ?: return@any false
        (code[it].opcode.setsRegister() && write.registerA == strip) ||
            (code[it].opcode.setsWideRegister() && write.registerA + 1 == strip)
    }
    if (written) return null
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val targets = landings(method, code, addresses)
    if ((context + 1..call).any { addresses[it] in targets }) return null
    return CardLabels(call, labels, strip)
}

/**
 * Every code address a branch, switch case or exception handler in [method] lands on, from its
 * [code] and the [addresses] each instruction starts at.
 */
private fun landings(method: Method, code: List<Instruction>, addresses: List<Int>): Set<Int> {
    val byAddress = code.indices.associateBy { addresses[it] }
    // A switch's offset points at its payload, whose cases are offsets from the switch itself.
    return code.indices.flatMap { at ->
        val target = (code[at] as? OffsetInstruction)?.let { addresses[at] + it.codeOffset } ?: return@flatMap emptyList()
        val payload = byAddress[target]?.let { code[it] as? SwitchPayload }
        payload?.switchElements?.map { addresses[at] + it.offset } ?: listOf(target)
    }.toSet() + method.implementation!!.tryBlocks.flatMap { block -> block.exceptionHandlers.map { it.handlerCodeAddress } }
}

/**
 * Hands the card's labels and their strip to the extension right before the card walks them.
 * While Dark LIVE panels is on in dark mode, the extension gives each label drawn on a color that
 * color as it shows over the light card, so the server's dark text and icon stay readable on
 * it. The hook writes no register.
 */
internal fun hookCardLabels(header: MutableMethod) {
    val labels = cardLabels(header) ?: throw PatchException("LIVE controls: the profile card's labels weren't found")
    header.addInstructions(
        labels.call,
        "invoke-static {v${labels.labels}, v${labels.strip}}, $PANEL_THEME->cardLabels(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
}

internal const val LIVE_USER = "Lcom/bytedance/android/live/base/model/user/User;"

/**
 * The profile card's upcoming event module binding the host's next event (X.0wEX.LJIIJ on 47.4.3,
 * #159). Its box (id/hwn) is a fixed white rounded shape with no night variant (drawable/e9l over
 * color/ao), while its title, time and chevron take the card theme's colors, so on the dark card
 * the "LIVE event" line read light on white (Login2, 2026-10-20).
 */
internal object LiveProfileEventFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(LIVE_USER, "Ljava/lang/Object;"),
    strings = listOf("livesdk_live_event_user_entrance_show"),
    custom = { method, _ -> eventBox(method) != null },
)

/** Where the event box hook goes, right after the box's read, and the register holding the box. */
internal class EventBox(val at: Int, val box: Int)

/**
 * In [method], the read of the event box: the first read of a ViewGroup field of [method]'s own
 * class, in a method that reads the host's upcoming events. Null when [method] reads no events or
 * no such field, when the read isn't followed straight by the null check of its register (the box
 * the module goes on to bind), or when a branch, switch case or handler lands on that check, which
 * would skip the hook. The read names its register in four bits, so it fits the hook's call.
 */
internal fun eventBox(method: Method): EventBox? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun field(at: Int) = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
    val events = code.indices.any {
        code[it].opcode == Opcode.IGET_OBJECT && field(it)?.let { read ->
            read.definingClass == LIVE_USER && read.name == "upcomingEventList"
        } == true
    }
    if (!events) return null
    val read = code.indices.firstOrNull {
        code[it].opcode == Opcode.IGET_OBJECT && field(it)?.let { box ->
            box.definingClass == method.definingClass && box.type == "Landroid/view/ViewGroup;"
        } == true
    } ?: return null
    val box = (code[read] as TwoRegisterInstruction).registerA
    val check = code.getOrNull(read + 1) ?: return null
    if (check.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != box) return null
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    if (addresses[read + 1] in landings(method, code, addresses)) return null
    return EventBox(read + 1, box)
}

/**
 * Hands the event box to the extension right after the module reads it, before its null check.
 * While Dark LIVE panels is on in dark mode and the box's text came out light, the extension
 * gives the box's shape a see-through light fill, so its text reads on the dark card. The hook
 * writes no register.
 */
internal fun hookEventBox(bind: MutableMethod) {
    val event = eventBox(bind) ?: throw PatchException("LIVE controls: the profile card's event box wasn't found")
    bind.addInstructions(event.at, "invoke-static {v${event.box}}, $PANEL_THEME->eventBox(Ljava/lang/Object;)V")
}

internal const val LIVE_BADGE = "Lcom/bytedance/android/live/base/model/user/BadgeStruct;"
private const val COMBINE_BADGE = "Lcom/bytedance/android/live/base/model/user/CombineBadgeStruct;"
private const val SET_BACKGROUND = "Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V"

/**
 * The profile card's badge tile bind (X.0wLz.LJIJI on 47.4.3, #159): it reads its tile, an image
 * view, from a lazy field and gives it a rounded shape drawn from the badge's server background
 * (a border around the server's art, a server color, or a border alone). The server draws that
 * art and the text over it for the white card, the low levels' "Lv." tile see-through, so on the
 * dark card the tile and its text went dark gray together (Login2, 2026-10-20).
 */
internal object LiveProfileBadgeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(LIVE_BADGE),
    filters = listOf(fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = COMBINE_BADGE, name = "background")),
    custom = { method, _ -> badgeTileCalls(method) != null },
)

/**
 * In [method], every `setBackground` on the badge tile: the tile is the one value cast to View
 * right after a lazy field of [method]'s own class hands it over, with the combined badge's
 * background read next. Each call has to take that value as its receiver on every path that
 * reaches it, so a register the method reuses for another view is never handed over as the
 * tile. Null when there's no such tile or no call, or when any call on the tile's register
 * could see another value there. The calls are replaced where they stand, so a branch landing on
 * one still runs the hook.
 */
internal fun badgeTileCalls(method: Method): List<Int>? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun reference(at: Int) = (code.getOrNull(at) as? ReferenceInstruction)?.reference
    val casts = (3 until code.size - 1).filter { at ->
        if (code[at].opcode != Opcode.CHECK_CAST || (reference(at) as? TypeReference)?.type != "Landroid/view/View;") {
            return@filter false
        }
        val tile = (code[at] as OneRegisterInstruction).registerA
        val lazy = reference(at - 3) as? FieldReference
        val value = reference(at - 2) as? MethodReference
        val background = reference(at + 1) as? FieldReference
        code[at - 1].opcode == Opcode.MOVE_RESULT_OBJECT && (code[at - 1] as OneRegisterInstruction).registerA == tile &&
            code[at - 2].opcode == Opcode.INVOKE_INTERFACE && value?.name == "getValue" && value.returnType == "Ljava/lang/Object;" &&
            code[at - 3].opcode == Opcode.IGET_OBJECT && lazy?.definingClass == method.definingClass &&
            code[at + 1].opcode == Opcode.IGET_OBJECT && background?.definingClass == COMBINE_BADGE && background.name == "background"
    }
    val cast = casts.singleOrNull() ?: return null
    val tile = (code[cast] as OneRegisterInstruction).registerA
    fun receiver(at: Int) = when (val call = code[at]) {
        is FiveRegisterInstruction -> call.registerC
        is RegisterRangeInstruction -> call.startRegister
        else -> null
    }
    val calls = method.literalReads(cast).filter { at ->
        code[at].opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) && (reference(at) as? MethodReference)?.let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SET_BACKGROUND
        } == true && receiver(at) == tile
    }
    if (calls.isEmpty() || calls.any { code[it].opcode != Opcode.INVOKE_VIRTUAL || !onlyReaches(method, cast, it, tile) }) return null
    return calls
}

/**
 * Whether [register] can hold only what the instruction at [definition] wrote when the
 * instruction at [use] runs: walking back from [use] along every path, the first write to
 * [register] on each is [definition], and no path gets back to the method's start without one.
 * An instruction that throws never writes its destination, so a handler's path walks on past it.
 */
private fun onlyReaches(method: Method, definition: Int, use: Int, register: Int): Boolean {
    val flow = ControlFlow.of(method)
    val code = flow.instructions
    val normal = Array(code.size) { mutableListOf<Int>() }
    val exceptional = Array(code.size) { mutableListOf<Int>() }
    code.indices.forEach { from ->
        flow.normal[from].forEach { normal[it] += from }
        flow.exceptional[from].forEach { exceptional[it] += from }
    }
    val seen = BitSet()
    val pending = ArrayDeque(listOf(use))
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == 0) return false
        normal[at].forEach { from ->
            if (writesRegister(code[from], register)) {
                if (from != definition) return false
            } else if (!seen[from]) {
                seen.set(from)
                pending += from
            }
        }
        exceptional[at].forEach { from -> if (!seen[from]) { seen.set(from); pending += from } }
    }
    return true
}

/**
 * Hands each of the badge tile's backgrounds to the extension in place of TikTok's own call. While
 * Dark LIVE panels is on in dark mode, the extension gives a solid light tile the dark fill and
 * lays a see-through one over a dark copy of its shape, then lights the text over the tile. The
 * call takes the same two registers, so nothing else in the method moves.
 */
internal fun hookBadgeTiles(bind: MutableMethod) {
    val calls = badgeTileCalls(bind) ?: throw PatchException("LIVE controls: the profile card's badge tile wasn't found")
    calls.forEach { at ->
        val call = bind.getInstruction<FiveRegisterInstruction>(at)
        bind.replaceInstruction(
            at,
            "invoke-static {v${call.registerC}, v${call.registerD}}, " +
                "$PANEL_THEME->badgeTile(Landroid/view/View;Landroid/graphics/drawable/Drawable;)V",
        )
    }
}

/** The host side of LIVE's share calls, real-named on 47.5.3. */
internal const val LIVE_HOST_SHARE = "Lcom/ss/android/ugc/aweme/live/livehostimpl/LiveHostShare;"

/**
 * A viewer's long-press menu in a room (share, report and the rest) being built (#159): the
 * plain dialog a video's long press opens, which the room shows as soon as this returns it.
 * The menu isn't one of LIVE's themed panels, so nothing reached its window and its navigation
 * bar stayed light under the dark menu (S22, 2026-10-24). Its share sheet controller gets a null
 * dialog from both of TikTok's menus, so a hook reading that never saw a window.
 */
internal object LiveLongPressMenuFingerprint : Fingerprint(
    definingClass = LIVE_HOST_SHARE,
    name = "getLongPressShareDialog",
    returnType = "Landroid/app/Dialog;",
)

/**
 * Hands the menu each of the factory's returns gives the room to the extension first, on the
 * return's own label and register, so nothing else in the factory moves.
 */
internal fun hookLongPressMenu(factory: MutableMethod) {
    val body = factory.implementation ?: throw PatchException("LIVE controls: the long-press menu's factory has no body")
    val returns = body.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.reversed()
    if (returns.isEmpty()) {
        throw PatchException("LIVE controls: the long-press menu's factory doesn't return")
    }
    returns.forEach { (at, instruction) ->
        val menu = (instruction as OneRegisterInstruction).registerA
        if (menu > 15) {
            throw PatchException("LIVE controls: the long-press menu's factory returns from v$menu")
        }
        factory.addInstructionsAtControlFlowLabel(
            at,
            "invoke-static {v$menu}, $PANEL_THEME->liveMenu(Landroid/app/Dialog;)V",
        )
    }
}

private const val SET_BACKGROUND_RESOURCE = "Landroid/view/View;->setBackgroundResource(I)V"

/**
 * The profile card's group chat row bind (X.0wJT.LJIIJ on 47.4.3, #159): the row puts a fixed
 * white shape on itself, in the bind for a host with no group yet and in a helper for the rest,
 * under a title and count in the card theme's colors, light on white on the dark card (Login2,
 * 2026-10-20).
 */
internal object LiveProfileGroupChatFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(LIVE_USER, "Ljava/lang/Object;"),
    strings = listOf("group_chat", "profile_card_entrance_click"),
    custom = { _, classDef -> classDef.methods.any { groupChatBackgrounds(it).isNotEmpty() } },
)

/**
 * In [method], every `setBackgroundResource` on a ViewGroup field of [method]'s own class: the
 * call's receiver has to hold that field's read on every path that reaches it, so a register the
 * method reuses for another view is never handed over as the row.
 */
internal fun groupChatBackgrounds(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    fun reference(at: Int) = (code[at] as? ReferenceInstruction)?.reference
    val reads = code.indices.filter { at ->
        code[at].opcode == Opcode.IGET_OBJECT && (reference(at) as? FieldReference)?.let {
            it.definingClass == method.definingClass && it.type == "Landroid/view/ViewGroup;"
        } == true
    }
    return code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL && (reference(at) as? MethodReference)?.let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SET_BACKGROUND_RESOURCE
        } == true && (code[at] as FiveRegisterInstruction).registerC.let { row ->
            reads.any { read -> (code[read] as TwoRegisterInstruction).registerA == row && onlyReaches(method, read, at, row) }
        }
    }
}

/**
 * Hands the group chat row to the extension right after each white shape goes on it, in every
 * method of the bind's class. While Dark LIVE panels is on in dark mode and the row's text came out
 * light, the extension gives the shape the event box's see-through light fill. The hook goes
 * after the call, so a branch over the call skips it with the call, and it writes no register.
 */
internal fun hookGroupChat(methods: Iterable<MutableMethod>) {
    var hooked = 0
    methods.forEach { method ->
        groupChatBackgrounds(method).asReversed().forEach { at ->
            val row = method.getInstruction<FiveRegisterInstruction>(at).registerC
            method.addInstructions(at + 1, "invoke-static {v$row}, $PANEL_THEME->eventBox(Ljava/lang/Object;)V")
            hooked++
        }
    }
    if (hooked == 0) throw PatchException("LIVE controls: the profile card's group chat row wasn't found")
}

internal const val PULL_STREAM_SERVICE ="Lcom/bytedance/android/live/livepullstream/PullStreamService;"
private const val LIVE_MODE = "Lcom/bytedance/android/livesdkapi/depend/model/live/LiveMode;"

/**
 * The pull stream service's starting quality for a room that's created ahead (rs2 on 47.1.4):
 * when [LiveResolutionPersistFingerprint] answers true it hands a video room TikTok's last
 * quality pick, a static string that lives only until TikTok closes, and the stream's default
 * while that's still null.
 */
internal object LiveStartQualityFingerprint : Fingerprint(
    definingClass = PULL_STREAM_SERVICE,
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", LIVE_MODE, "Z"),
    custom = { method, _ -> method.calls(LIVE_RESOLUTION_SELECTION_SETTING, "needPersistAcrossRooms") },
)

/**
 * The LIVE player's start (X.0K9X.LJJJZ on 47.1.4). When [LiveResolutionPersistFingerprint]
 * answers true it lists the stream's quality keys and starts at the last pick only when the
 * stream has it, at the stream's default otherwise.
 */
internal object LivePlayerStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Z", "Ljava/lang/Long;"),
    strings = listOf(" setDataSource()", "streamData is null when get video resolution"),
)

/**
 * TikTok's last LIVE quality pick (#57): the static [field] the two starts read, and the one
 * service method ([setter], vJ1 on 47.1.4) the picker writes it through.
 */
internal data class LiveQualityPick(val field: String, val setter: Method)

private fun FieldReference.key() = "$definingClass->$name:$type"

/** Every `sget-object` of [field] in this method. */
internal fun Method.readsOf(field: String): List<Int> {
    val instructions = implementation?.instructions?.toList().orEmpty()
    return instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.SGET_OBJECT &&
            ((instructions[index] as ReferenceInstruction).reference as FieldReference).key() == field
    }
}

/**
 * The pick and its setter, from the start [quality] answer's single static string read. Throws
 * unless the service has exactly one method that only stores its argument there: the pick is
 * saved at that call, so a second writer would leave a pick unsaved.
 */
internal fun liveQualityPick(service: ClassDef, quality: Method): LiveQualityPick {
    val instructions = quality.implementation?.instructions?.toList().orEmpty()
    val fields = instructions.filter { it.opcode == Opcode.SGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.type == "Ljava/lang/String;" }
        .map { it.key() }.distinct()
    val field = fields.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one quality pick read in ${quality.name}, found $fields")
    val setters = service.methods.filter { method ->
        val body = method.implementation?.instructions?.toList().orEmpty()
        val store = body.firstOrNull()
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
            body.size == 2 && store?.opcode == Opcode.SPUT_OBJECT &&
            ((store as ReferenceInstruction).reference as FieldReference).key() == field &&
            (store as OneRegisterInstruction).registerA == method.implementation!!.registerCount - 1 &&
            body[1].opcode == Opcode.RETURN_VOID
    }
    val setter = setters.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one quality pick setter in ${service.type}, found ${setters.map { it.name }}")
    return LiveQualityPick(field, setter)
}

/**
 * Where the player start's game and PC LIVE branch ends, and what its regular branch switches a
 * room's quality with (#57). Rooms that LiveMode.isWatchGameLiveOrPCLive answers true for set their
 * stream up in a branch of their own (when TikTok's game quality setting is on or the room's
 * options say so) whose last call, on a Pair, is followed by a goto past the pick read, so such a
 * room opens at the stream's default even with a pick. [end] is that goto.
 * After the pick read the regular branch stores the picked key in [quality] and calls
 * [setter] on [player] with [data] and that key.
 */
internal class GameRoomStart(
    val end: Int,
    val quality: FieldReference,
    val data: FieldReference,
    val player: FieldReference,
    val setter: MethodReference,
) {
    fun quality() = quality.key()
    fun data() = data.key()
    fun player() = player.key()
    fun setter() = "${setter.definingClass}->${setter.name}(${setter.parameterTypes.joinToString("")})${setter.returnType}"
}

internal fun gameRoomStart(start: Method, pickRead: Int): GameRoomStart {
    val instructions = start.implementation?.instructions?.toList().orEmpty()
    fun refuse(what: String): Nothing = throw PatchException("LIVE controls: the LIVE player start has $what")
    val store = (pickRead until instructions.size).firstOrNull { index ->
        val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[index].opcode == Opcode.IPUT_OBJECT && field?.definingClass == start.definingClass &&
            field.type == "Ljava/lang/String;"
    } ?: refuse("no store of the picked quality after the pick read")
    val switchAt = (store until instructions.size).firstOrNull { index ->
        val call = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[index].opcode == Opcode.INVOKE_INTERFACE && call?.returnType == "V" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;")
    } ?: refuse("no quality switch after the picked quality's store")
    val switch = instructions[switchAt] as FiveRegisterInstruction
    fun fieldInto(register: Int): FieldReference = (switchAt - 1 downTo store + 1).asSequence()
        .map { instructions[it] }
        .firstOrNull { it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerA == register }
        ?.let { (it as ReferenceInstruction).reference as FieldReference }
        ?: refuse("no field read for v$register before its quality switch")
    val quality = fieldInto(switch.registerE)
    if (quality.key() != (((instructions[store] as ReferenceInstruction).reference as FieldReference).key())) {
        refuse("a quality switch that doesn't use the stored pick")
    }
    val end = (0 until pickRead).firstOrNull { index ->
        val call = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[index].opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == start.definingClass &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Lkotlin/Pair;") &&
            instructions.getOrNull(index + 1)?.opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    } ?: refuse("no game LIVE branch ending before the pick read")
    return GameRoomStart(
        end + 1,
        quality,
        fieldInto(switch.registerD),
        fieldInto(switch.registerC),
        (instructions[switchAt] as ReferenceInstruction).reference as MethodReference,
    )
}

/**
 * Where the player start's regular branch leaves LIVE Studio rooms out of the quality pick (#57):
 * it compares the room's mode with LiveMode.LIVE_STUDIO and jumps past the pick read when they
 * match. LIVE Studio is TikTok's PC streaming app, and on the S22 the gaming rooms from the LIVE
 * button were LIVE Studio rooms whose game branch was off, so they opened at AUTO whatever was
 * picked. Returns the index of that LIVE_STUDIO read; the if-eq follows it.
 */
internal fun liveStudioSkip(start: Method, from: Int, pickRead: Int): Int {
    val instructions = start.implementation?.instructions?.toList().orEmpty()
    return (from until pickRead).singleOrNull { index ->
        val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[index].opcode == Opcode.SGET_OBJECT && field?.definingClass == LIVE_MODE &&
            field.name == "LIVE_STUDIO" && instructions.getOrNull(index + 1)?.opcode == Opcode.IF_EQ
    } ?: throw PatchException("LIVE controls: the LIVE player start no longer leaves LIVE Studio rooms out of the pick")
}

internal const val PUSH_STREAM_QUALITY ="Lcom/bytedance/android/live/broadcast/model/PushStreamInfo\$Quality;"
private const val AUDIENCE_SHARE_SCREEN_DEFINITION =
    "Lcom/bytedance/android/livesdk/dataChannel/AudienceShareScreenDefinition;"

/**
 * A pick in the older LIVE quality picker going through (#57). Share > Video quality opens
 * LiveDefinitionSelectionDialog when the stream asks for a quality button
 * (LiveCoreSDKData.Options.showQualityButton), and the newer picker only in rooms whose stream
 * doesn't. Its list adapter asks the player to switch, and the switch's callback posts this with
 * the result (X.14HP.LIZ on 47.1.4, traced as LiveDefinitionSelectionDialog's
 * handleAudienceItemSelect). When the switch went through it copies the picked row's sdk key into
 * a PushStreamInfo.Quality for the room's AudienceShareScreenDefinition. It never calls the pull
 * stream service's setter the newer picker writes, so its picks were never saved: picking
 * Original there on the S22 left the saved pick at hd.
 */
internal object LiveDefinitionPickAppliedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("sdk_key"),
    custom = { method, _ ->
        method.references().any {
            it is FieldReference && it.definingClass == PUSH_STREAM_QUALITY && it.name == "sdkKey"
        } && method.references().any { it.toString() == AUDIENCE_SHARE_SCREEN_DEFINITION }
    },
)

/**
 * Where the older picker's pick is known: the first store of the picked sdk key, at [index], from
 * [register]. Throws unless that store comes after the method's check of its own result flag, so
 * only a switch that went through is saved, and before anything writes the register again.
 */
internal data class DefinitionPickStore(val index: Int, val register: Int)

internal fun definitionPickStore(applied: Method): DefinitionPickStore {
    val instructions = applied.implementation?.instructions?.toList().orEmpty()
    val store = instructions.indexOfFirst {
        it.opcode == Opcode.IPUT_OBJECT &&
            ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                field.definingClass == PUSH_STREAM_QUALITY && field.name == "sdkKey"
            }
    }
    if (store < 0) throw PatchException("LIVE controls: ${applied.definingClass}->${applied.name} stores no sdk key")
    // The result flag: a boolean field of the runnable itself, read and branched on before the store.
    val flag = instructions.take(store).indexOfFirst {
        it.opcode == Opcode.IGET_BOOLEAN &&
            ((it as ReferenceInstruction).reference as FieldReference).definingClass == applied.definingClass
    }
    val flagRegister = (instructions.getOrNull(flag) as? OneRegisterInstruction)?.registerA
    val checked = flag >= 0 && instructions.subList(flag + 1, store).any {
        it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == flagRegister
    }
    if (!checked) {
        throw PatchException(
            "LIVE controls: ${applied.definingClass}->${applied.name} no longer checks its result before storing the pick",
        )
    }
    return DefinitionPickStore(store, (instructions[store] as TwoRegisterInstruction).registerA)
}

/** The For You LIVE preview's Service+ row ("Service+ | Ask me anything!"), real-named on 47.1.4. */
internal const val SERVICE_PLUS_ROW =
    "Lcom/ss/android/ugc/aweme/feed/adapter/widget/serviceplus/PreviewServicePlusBottomInfoWidget;"
internal const val LIVE_ROOM_STRUCT = "Lcom/ss/android/ugc/aweme/feed/model/live/LiveRoomStruct;"
internal const val SMB_LIVE_PREVIEW = "Lcom/ss/android/ugc/aweme/feed/model/live/SMBLivePreview;"
private const val BASE_WIDGET = "Lcom/bytedance/android/widget/Widget;"

/**
 * Where the Service+ row takes a LIVE (#94). The row's bind ([method], f0 on 47.1.4) reads the
 * room's smbLivePreview, keeps it in a field of its own ([store] is that iput-object, [preview]
 * the register it stores and [row] the row itself) and shows it with the next call. That show
 * returns without hiding the row when the room has no Service+ item, and only the row's [hide]
 * (V0, run as a preview leaves the screen) takes it away, so a preview bound to the next LIVE
 * without leaving first kept the last LIVE's row.
 */
internal data class ServicePlusBind(
    val method: Method,
    val store: Int,
    val preview: Int,
    val row: Int,
    val hide: Method,
)

private fun Method.references() = implementation?.instructions?.toList().orEmpty()
    .mapNotNull { (it as? ReferenceInstruction)?.reference }

private fun Method.calls(owner: String, name: String) = references()
    .any { it is MethodReference && it.definingClass == owner && it.name == name }

/**
 * The row's bind and hide, matched by shape. Throws when either isn't there exactly once, or the
 * bind no longer stores the room's Service+ preview right before a plain show and a return: the
 * hook writes the preview's register after the store, which is only safe when nothing reads it
 * again.
 */
internal fun servicePlusBind(row: ClassDef): ServicePlusBind {
    val instance = row.methods.filter {
        it.implementation != null && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.isEmpty() && it.returnType == "V"
    }
    val binds = instance.filter { method ->
        method.references().any { it is FieldReference && it.definingClass == LIVE_ROOM_STRUCT && it.name == "smbLivePreview" }
    }
    val bind = binds.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one Service+ row bind, found ${binds.map { it.name }}")
    val instructions = bind.implementation!!.instructions.toList()
    val stores = instructions.indices.filter { index ->
        val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[index].opcode == Opcode.IPUT_OBJECT && field != null &&
            field.definingClass == row.type && field.type == SMB_LIVE_PREVIEW
    }
    val store = stores.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one Service+ preview store in ${bind.name}, found ${stores.size}")
    val put = instructions[store] as TwoRegisterInstruction
    val self = bind.implementation!!.registerCount - 1
    val show = instructions.getOrNull(store + 1)
    val showCall = (show as? ReferenceInstruction)?.reference as? MethodReference
    val plainShow = show is FiveRegisterInstruction && showCall != null &&
        show.opcode == Opcode.INVOKE_VIRTUAL && showCall.definingClass == row.type &&
        showCall.parameterTypes.isEmpty() && showCall.returnType == "V" &&
        show.registerCount == 1 && show.registerC == self
    if (put.registerB != self || put.registerA == self || !plainShow ||
        instructions.getOrNull(store + 2)?.opcode != Opcode.RETURN_VOID
    ) {
        throw PatchException("LIVE controls: the Service+ row bind ${bind.name} has an unexpected shape")
    }
    // Called with invoke-virtual, so a private one wouldn't verify.
    val hides = instance.filter { method ->
        method !== bind && !AccessFlags.PRIVATE.isSet(method.accessFlags) &&
            method.calls("Landroid/os/Handler;", "removeCallbacksAndMessages") &&
            method.calls(BASE_WIDGET, "hide") && !method.calls(BASE_WIDGET, "show")
    }
    val hide = hides.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one Service+ row hide, found ${hides.map { it.name }}")
    return ServicePlusBind(bind, store, put.registerA, self, hide)
}

/** The LIVE room's top-right viewer count, real-named on 47.0.3, 47.1.3 and 47.1.4. */
internal const val ONLINE_AUDIENCE_RANK_WIDGET = "Lcom/bytedance/android/livesdk/rank/impl/widget/OnlineAudienceRankWidget;"

/** How many places the widget turns the viewer count into text; the same on all three builds. */
internal const val VIEWER_COUNT_SITES = 4

/**
 * The viewer count in a programmed LIVE (a scheduled show with a lineup), collapsed, expanded and
 * in landscape; real-named on 47.0.3, 47.1.3 and 47.1.4. Each formats its count once, through the
 * same abbreviator as [ONLINE_AUDIENCE_RANK_WIDGET].
 */
internal val PROGRAMMED_AUDIENCE_WIDGETS = listOf(
    "Lcom/bytedance/android/livesdk/programmedlive/ui/ProgrammedLiveOnlineAudienceCollapseWidget;",
    "Lcom/bytedance/android/livesdk/programmedlive/ui/ProgrammedLiveOnlineAudienceExpandWidget;",
    "Lcom/bytedance/android/livesdk/programmedlive/ui/ProgrammedLiveOnlineAudienceLandscapeWidget;",
)

/**
 * One place a widget formats the viewer count: `int-to-long vLow, vCount`, a static (J)String
 * formatter called on the pair (vLow, vLow + 1) and `move-result-object vResult`. Then either
 * `sget-object Locale.ENGLISH` and `toUpperCase` on vResult (the room's top-right count) or a
 * (String)String wrap of vResult (the programmed widgets' text direction wrap). [formatIndex] is
 * the formatter call.
 */
internal data class ViewerCountSite(
    val method: Method,
    val formatIndex: Int,
    val pairLow: Int,
    val result: Int,
) {
    /** The formatter, as `class->name`. */
    val formatter: String
        get() = ((method.implementation!!.instructions.toList()[formatIndex] as ReferenceInstruction).reference as MethodReference)
            .let { "${it.definingClass}->${it.name}" }
}

/**
 * Every call in [widget] to a static (J)Ljava/lang/String; helper that isn't the JDK's, or only
 * those to [formatter] when given. That helper is the "K+", "M+" and "B+" abbreviator (X.0LE4.LIZJ
 * on 47.0.3, X.0LDD on 47.1.3, X.0LDH on 47.1.4), renamed per build, so the shape is matched, not
 * the name. Throws if any such call has a shape the hook can't take: a site the patch skipped would
 * keep showing the rounded count with nothing to say so.
 */
internal fun viewerCountSites(widget: ClassDef, formatter: String? = null): List<ViewerCountSite> {
    val sites = mutableListOf<ViewerCountSite>()
    for (method in widget.methods) {
        val instructions = method.implementation?.instructions?.toList() ?: continue
        instructions.forEachIndexed { index, instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@forEachIndexed
            if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
                return@forEachIndexed
            }
            if (call.returnType != "Ljava/lang/String;" ||
                call.parameterTypes.map(CharSequence::toString) != listOf("J")
            ) {
                return@forEachIndexed
            }
            if (call.definingClass.startsWith("Ljava/")) return@forEachIndexed
            if (formatter != null && "${call.definingClass}->${call.name}" != formatter) return@forEachIndexed
            val where = "${widget.type}->${method.name} at $index"
            val invoke = instruction as? FiveRegisterInstruction
                ?: throw PatchException("LIVE controls: viewer count call is not a plain invoke at $where")
            val low = invoke.registerC
            val widened = instructions.getOrNull(index - 1)
            val moved = instructions.getOrNull(index + 1)
            val result = (moved as? OneRegisterInstruction)?.registerA
            val shaped = invoke.registerCount == 2 && invoke.registerD == low + 1 &&
                widened?.opcode == Opcode.INT_TO_LONG &&
                (widened as TwoRegisterInstruction).registerA == low &&
                moved?.opcode == Opcode.MOVE_RESULT_OBJECT && result != null &&
                (upperCased(instructions, index + 2, result) || wrapped(instructions, index + 2, result))
            if (!shaped) throw PatchException("LIVE controls: viewer count call has an unexpected shape at $where")
            sites += ViewerCountSite(method, index, low, result!!)
        }
    }
    return sites
}

/** `sget-object Locale.ENGLISH`, then `toUpperCase` on [result]. */
private fun upperCased(instructions: List<Instruction>, at: Int, result: Int): Boolean {
    val localeGet = instructions.getOrNull(at)
    val locale = (localeGet as? ReferenceInstruction)?.reference as? FieldReference
    val upper = instructions.getOrNull(at + 1)
    val upperCall = (upper as? ReferenceInstruction)?.reference as? MethodReference
    return localeGet?.opcode == Opcode.SGET_OBJECT &&
        locale?.definingClass == "Ljava/util/Locale;" && locale?.name == "ENGLISH" &&
        upper?.opcode == Opcode.INVOKE_VIRTUAL && upperCall?.name == "toUpperCase" &&
        (upper as? FiveRegisterInstruction)?.registerC == result
}

/** An instance (String)String call that takes [result] as its argument. */
private fun wrapped(instructions: List<Instruction>, at: Int, result: Int): Boolean {
    val wrap = instructions.getOrNull(at) as? FiveRegisterInstruction ?: return false
    val wrapCall = (wrap as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return wrap.opcode == Opcode.INVOKE_VIRTUAL && wrapCall.returnType == "Ljava/lang/String;" &&
        wrapCall.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
        wrap.registerCount == 2 && wrap.registerD == result
}

@Suppress("unused")
val liveControlsPatch = bytecodePatch(
    name = "LIVE controls",
    description = "Stops a LIVE in the feed from opening by itself after a countdown. It can " +
        "show a LIVE's exact viewer count instead of a rounded one, offer a quality choice and " +
        "clear screen in more LIVE rooms, and keep your quality pick after TikTok restarts. " +
        "Starts off. Turn it on in Hushfeed settings > Playback. " +
        "It also keeps a LIVE in the feed from showing the Service+ row of the LIVE before it, " +
        "and can add a Record button to a LIVE room that saves what you watch from when you tap it.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch, ownedShareActionsPatch, screenRecordManifestPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val guide = LivePreviewAutoEnterFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveControls()V",
        )
        guide.guardAtEntry(
            "LIVE controls",
            "invoke-static {}, $EXTENSION->skipAutoEnter()Z",
            "return-void",
        )
        // Both answer true while the switch is on; every other reader of the setting goes
        // through these two.
        listOf(LiveResolutionSelectionFingerprint, LiveResolutionPersistFingerprint).forEach {
            it.method.guardAtEntry(
                "LIVE controls",
                "invoke-static {}, $EXTENSION->offerQualityEverywhere()Z",
                "const/4 v0, 0x1\nreturn v0",
            )
        }
        // Answers true while its switch is on, so every LIVE room's menu offers clear screen.
        LiveClearScreenEntryFingerprint.method.guardAtEntry(
            "LIVE controls",
            "invoke-static {}, $EXTENSION->offerClearScreen()Z",
            "const/4 v0, 0x1\nreturn v0",
        )
        // Answers true while its switch is on and the panel's context is in dark mode, so LIVE
        // panels take TikTok's dark LIVE theme in rooms too (#159).
        LiveDarkThemeFingerprint.method.guardAtEntry(
            "LIVE controls",
            "invoke-static {p1}, $EXTENSION->darkLivePanels(Landroid/content/Context;)Z",
            "const/4 v0, 0x1\nreturn v0",
        )
        // That answer alone left the panels white (S25, 2026-10-14): TikTok adds the dark theme
        // to the room's light one without force. Each themed dialog's and sheet page's inflater
        // goes through the extension, which applies the panel's dark overlay with force while
        // the switch is on in dark mode. The hook sits on each return's own label, so a branch
        // onto the return runs it too.
        listOf(LiveThemedDialogInflaterFingerprint, LiveThemedFragmentInflaterFingerprint).forEach {
            hookPanelInflater(it.method)
        }
        // A plain fragment under a LIVE panel skips both: it takes the room activity's inflater,
        // so the daily ranking's lists read white on its white sheet (Login2, 2026-10-19). Every
        // fragment's inflater passes the extension once, which only wraps one under the ranking.
        hookFragmentInflater(FragmentInflaterFingerprint.method)
        // Its tab strip reads the colors the server leaves out through the page's getContext(),
        // the room activity, so the unpicked tab's gray pill read white on white (Login2,
        // 2026-10-19). Both tab states hand that context to the extension, which gives back the
        // page's light one under the ranking.
        hookRankTab(RankTabPickedFingerprint.method, "selectedTextColor")
        hookRankTab(RankTabUnpickedFingerprint.method, "defaultTextColor")
        // Its team list colors the bottom bar the same way, so the bar's name and count read faint
        // on the white sheet (Login2, 2026-10-19), and each of those contexts gets the same answer.
        hookPageContexts(TeamRankListViewFingerprint.method)
        // The page tints the chevron after its reach_top20 row that way too, and every list's bar
        // tints its tag's icon the same way.
        hookPageContexts(RankPageReachTopFingerprint.method)
        hookPageContexts(RankListBottomTagFingerprint.method)
        // A list's bottom row tints the info icon after a host's gap text through the context the
        // list hands it, so on the trending tab's "99+" row it went white on white (Login2,
        // 2026-10-19). The row asks the extension first, which hands back the row text's own light
        // context under the ranking.
        hookGapInfoIcon(RankGapInfoIconFingerprint.method)
        // LIVE's list rows don't take the list's inflater at all: every row binder inflates through
        // LIVE's shared inflater, the app's or the room's, so the trending tab's rows took the dark
        // theme on that sheet too (Login2, 2026-10-19). The binders' base asks the extension for
        // the row first, which inflates one for a list under the ranking through the list's own
        // light context and leaves every other list to TikTok.
        hookRowBinder(LiveRowBinderFingerprint.method, LiveRowBinderFingerprint.originalClassDef)
        // TikTok's generic sheets over a room (Share) take the room activity's own light theme,
        // which the overlay can't reach. The room's setTheme hands the activity to the extension
        // after its last room theme, and the extension puts TikTok's dark app theme on it
        // with force while the switch is on in dark mode. The style comes from TikTok's own dark
        // mode code, so it follows each build's resource ids.
        val darkTheme = appDarkTheme(AppDarkThemeFingerprint.method)
            ?: throw PatchException("LIVE controls: TikTok's dark app theme wasn't found")
        mutableClassDefBy(PANEL_THEME).methods.single {
            it.name == "darkAppTheme" && it.returnType == "I" && it.parameterTypes.isEmpty()
        }.returnEarly(darkTheme)
        hookRoomTheme(LivePlayActivityThemeFingerprint.method)
        // A dark mode flip with a room open doesn't recreate it: AppCompat re-applies the room's
        // last light theme with force after setTheme, so the room hook runs again once the
        // change is through.
        hookRoomConfiguration(LivePlayActivityConfigurationFingerprint.method)
        // TikTok's Spark pages read light or dark from the theme name of the context they open
        // in, so over the darkened room they draw dark, which suits them, except a shop room's
        // viewer ranking with a Top customers tab: its native tab titles kept their light colors
        // on the dark sheet (S22, 2026-10-20). Spark's per-page decision asks the extension first,
        // which answers light for that page alone, found by its schema.
        val sparkPage = SparkPageThemeFingerprint.method
        hookSparkPage(sparkPage, classDefBy(sparkPage.returnType))
        // A translated bio's "See original" link read dark on the dark profile card (Login2,
        // 2026-10-18): its replacement span draws it in the bio view's own light-theme text
        // color, skipping the link color. The extension makes the link color the view's text
        // color while the switch is on in dark mode.
        hookBioLink(LiveProfileBioFingerprint.method)
        // Its labels, the shop rating and sold count chips among them, come with the server's
        // colors for the light card: dark text and icons on a faint see-through chip, dark on
        // the dark card (Login2, 2026-10-19). The extension makes each chip's color what it is
        // over the light card while the switch is on in dark mode.
        hookCardLabels(LiveProfileLabelsFingerprint.method)
        // Its upcoming event box is a fixed white shape under text in the card theme's colors,
        // light on white on the dark card (Login2, 2026-10-20). The extension gives the box a
        // see-through light fill while the switch is on in dark mode and its text came out light.
        hookEventBox(LiveProfileEventFingerprint.method)
        // Its badge tiles carry server art and text drawn for the white card, the low levels'
        // tile see-through, so both went dark gray on the dark card (Login2, 2026-10-20). The
        // extension puts each tile on a dark copy of its shape while the switch is on in dark mode.
        hookBadgeTiles(LiveProfileBadgeFingerprint.method)
        // A viewer's long-press menu is a plain dialog, so its navigation bar stayed light under
        // the dark menu (S22, 2026-10-24). The factory the room gets it from hands it over.
        hookLongPressMenu(LiveLongPressMenuFingerprint.method)
        // Its group chat row is the same kind of fixed white shape under text in the card theme's
        // colors (Login2, 2026-10-20), so the extension gives it the event box's fill.
        hookGroupChat(mutableClassDefBy(LiveProfileGroupChatFingerprint.method.definingClass).methods)
        // The older quality list's rows can read white on white in dark mode, switch or no
        // switch; the extension gives poorly contrasted text a readable color there.
        hookQualityList(LiveDefinitionListFingerprint.method)
        // The newer picker puts its rows on a white card whose text follows the dark room, so
        // the same check keeps them readable (#57).
        hookQualityList(LiveTalentDefinitionListFingerprint.method)

        // The room page coming to the front: where the Record button goes and which room it records (#37).
        liveRoomField(classDefByOrNull(LIVE_PLAY_FRAGMENT)
            ?: throw PatchException("LIVE controls: $LIVE_PLAY_FRAGMENT not found"))
        LivePlayFragmentResumeFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $RECORD_EXTENSION->noteRoomFragment(Ljava/lang/Object;)V",
        )

        // The formatter's result lands in the upper half of the long it was given, so the count
        // can't be read back after the call and the hook needs no spare register: the count is
        // handed over before the call and TikTok's text after it, on the main thread in one
        // straight line of code, and the extension answers with the exact number or TikTok's
        // text. Both added calls use only registers the original instructions just wrote: the
        // first reads the pair int-to-long filled, the second reads and rewrites the register
        // move-result-object filled. No frame grows.
        val widget = classDefByOrNull(ONLINE_AUDIENCE_RANK_WIDGET)
            ?: throw PatchException("LIVE controls: $ONLINE_AUDIENCE_RANK_WIDGET not found")
        val sites = viewerCountSites(widget)
        if (sites.size != VIEWER_COUNT_SITES) {
            throw PatchException("LIVE controls: expected $VIEWER_COUNT_SITES viewer count calls, found ${sites.size}")
        }
        val formatter = sites.map { it.formatter }.distinct().singleOrNull()
            ?: throw PatchException("LIVE controls: the viewer count calls use more than one formatter")
        hookViewerCounts(ONLINE_AUDIENCE_RANK_WIDGET, sites)

        // A programmed LIVE shows its count in its own widgets, through the same formatter.
        PROGRAMMED_AUDIENCE_WIDGETS.forEach { type ->
            val programmed = classDefByOrNull(type) ?: throw PatchException("LIVE controls: $type not found")
            val found = viewerCountSites(programmed, formatter)
            if (found.size != 1) {
                throw PatchException("LIVE controls: expected one viewer count call in $type, found ${found.size}")
            }
            hookViewerCounts(type, found)
        }

        // A LIVE preview bound to a LIVE with no Service+ item runs the row's own hide first
        // when it's still showing the last LIVE's row (#94). Matched and checked in full before
        // anything is written.
        val serviceRow = classDefByOrNull(SERVICE_PLUS_ROW)
            ?: throw PatchException("LIVE controls: $SERVICE_PLUS_ROW not found")
        val serviceBind = servicePlusBind(serviceRow)
        mutableClassDefBy(SERVICE_PLUS_ROW).findMutableMethodOf(serviceBind.method).apply {
            val at = serviceBind.store + 1
            addInstructionsWithLabels(
                at,
                """
                    invoke-static {v${serviceBind.row}, v${serviceBind.preview}}, $EXTENSION->hideStaleServicePlus(Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v${serviceBind.preview}
                    if-eqz v${serviceBind.preview}, :morphe_keep_service_plus_row
                    invoke-virtual {v${serviceBind.row}}, $SERVICE_PLUS_ROW->${serviceBind.hide.name}()V
                """,
                ExternalLabel("morphe_keep_service_plus_row", getInstruction(at)),
            )
        }

        // A LIVE quality pick that outlives TikTok closing (#57): the picker's setter saves each
        // pick, and both starts that read TikTok's last pick get the saved one while TikTok's is
        // still null after a cold start. The player start only uses it when the stream has that
        // quality, the same check it makes of a pick from this session. All four places are found
        // and checked before anything is written.
        val startQuality = LiveStartQualityFingerprint.method
        val pick = liveQualityPick(mutableClassDefBy(PULL_STREAM_SERVICE), startQuality)
        val playerStart = LivePlayerStartFingerprint.method
        val playerPickRead = playerStart.readsOf(pick.field).singleOrNull()
            ?: throw PatchException("LIVE controls: expected one quality pick read in ${playerStart.name}")
        val gameRoom = gameRoomStart(playerStart, playerPickRead)
        val studioSkip = liveStudioSkip(playerStart, gameRoom.end, playerPickRead)
        val studioMode = (playerStart.getInstruction(studioSkip) as OneRegisterInstruction).registerA
        val readers = listOf(startQuality, playerStart).map { reader ->
            val reads = reader.readsOf(pick.field)
            val read = reads.singleOrNull()
                ?: throw PatchException("LIVE controls: expected one quality pick read in ${reader.name}, found ${reads.size}")
            Triple(reader, read, (reader.getInstruction(read) as OneRegisterInstruction).registerA)
        }
        readers.forEach { (reader, read, register) ->
            reader.addInstructions(
                read + 1,
                """
                    invoke-static/range {v$register .. v$register}, $EXTENSION->liveQualityPick(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """,
            )
        }
        // LIVE Studio rooms skip the pick read in the regular branch. With Offer LIVE quality on the
        // mode they're compared with becomes null, so they read the pick and check the stream has
        // it like other rooms. Audio rooms stay left out. It sits between the game branch's end and
        // the pick read, so it goes in after the read's hook and before the game end's.
        playerStart.addInstructions(
            studioSkip + 1,
            """
                invoke-static {v$studioMode}, $EXTENSION->liveStudioPickSkip(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$studioMode
            """,
        )
        // A game or PC LIVE room's start jumps past the pick read, so its end asks for the pick
        // and switches the room the way the regular branch does. It sits before the pick read, so
        // the hook above is already in. v0 to v2 are written again after the goto before any read.
        playerStart.addInstructionsWithLabels(
            gameRoom.end,
            """
                sget-object v0, ${pick.field}
                iget-object v1, p0, ${gameRoom.data()}
                iget-object v2, p0, ${gameRoom.quality()}
                invoke-static {v0, v1, v2}, $EXTENSION->gameRoomQuality(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :morphe_game_room_keep
                iput-object v0, p0, ${gameRoom.quality()}
                iget-object v2, p0, ${gameRoom.player()}
                invoke-interface {v2, v1, v0}, ${gameRoom.setter()}
            """,
            ExternalLabel("morphe_game_room_keep", playerStart.getInstruction(gameRoom.end)),
        )
        // Rooms from the LIVE button logged neither start's pick read on the S22, so with debug on
        // every player start says whether it had stream data and which LIVE mode it was. Added last
        // so the indices above still hold; v0 and v1 are written before they're read.
        val liveMode = playerStart.implementation!!.instructions.firstNotNullOfOrNull {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)
                ?.takeIf { field -> it.opcode == Opcode.IGET_OBJECT && field.type == LIVE_MODE && field.definingClass == playerStart.definingClass }
        } ?: throw PatchException("LIVE controls: the LIVE player start reads no LIVE mode")
        playerStart.addInstructions(
            0,
            """
                iget-object v0, p0, ${gameRoom.data()}
                iget-object v1, p0, ${liveMode.key()}
                invoke-static {v0, v1}, $EXTENSION->notePlayerStart(Ljava/lang/String;Ljava/lang/Object;)V
            """,
        )
        mutableClassDefBy(PULL_STREAM_SERVICE).findMutableMethodOf(pick.setter).addInstruction(
            0,
            "invoke-static/range {p1 .. p1}, $EXTENSION->noteLiveQualityPick(Ljava/lang/String;)V",
        )
        // The older picker, which Share > Video quality opens in rooms whose stream asks for a
        // quality button, never goes through that setter. Its pick is saved where it has gone
        // through, right after the picked key is stored (#57).
        val applied = LiveDefinitionPickAppliedFingerprint.method
        val stored = definitionPickStore(applied)
        applied.addInstruction(
            stored.index + 1,
            "invoke-static/range {v${stored.register} .. v${stored.register}}, " +
                "$EXTENSION->noteLiveQualityPick(Ljava/lang/String;)V",
        )
        // Gaming (LIVE Studio) and low-latency rooms leave the newer Video quality entry out of
        // their Share sheet; with Offer LIVE quality on, both blocks answer no (#37).
        LiveSheetActionsFingerprint.method.hookQualityEntryBlocks(qualityEntryBlocks(LiveSheetActionsFingerprint.method))
        // A LIVE room's share sheet is the regular one with itemType "live", so the recording
        // quality row rides the same finished-row hook Block this creator and Save cover use (#203).
        addOwnedShareAction(SHARE_LIVE_QUALITY)
    }
}

/**
 * Hands each site's count to the extension before the formatter and swaps TikTok's text for the
 * extension's after it.
 */
private fun BytecodePatchContext.hookViewerCounts(type: String, sites: List<ViewerCountSite>) {
    val mutableWidget = mutableClassDefBy(type)
    for ((method, group) in sites.groupBy { it.method }) {
        val mutable = mutableWidget.findMutableMethodOf(method)
        // Last site first, so an earlier site's index is still right when it is edited.
        for (site in group.sortedByDescending { it.formatIndex }) {
            mutable.hookViewerCount(site)
        }
    }
}

/** The two calls around one site; both use only the registers the site's own instructions write. */
internal fun MutableMethod.hookViewerCount(site: ViewerCountSite) {
    addInstructions(
        site.formatIndex + 2,
        "invoke-static/range {v${site.result} .. v${site.result}}, $EXTENSION->viewerCountText(Ljava/lang/String;)" +
            "Ljava/lang/String;\nmove-result-object v${site.result}",
    )
    addInstruction(
        site.formatIndex,
        "invoke-static/range {v${site.pairLow} .. v${site.pairLow + 1}}, $EXTENSION->noteViewerCount(J)V",
    )
}

/**
 * The provider of a LIVE room's share sheet entries (X.0qEC.LIZ on 47.5.3, provideLiveSheetActions
 * in its own log line). It adds Video quality, the older picker when the stream asks for a quality
 * button and the newer one beside the resolution selection setting, and it leaves the newer one out
 * of two kinds of room: LIVE Studio rooms (streamed from TikTok's PC app, most gaming rooms), whose
 * mode check is a static (Room)Z call after the AUDIO read, and rooms that support low latency,
 * which TikTok's LiveTalentDefinitionBlockLowLatencySetting blocks (#37).
 */
internal object LiveSheetActionsFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf(LIVE_MODE, LIVE_ROOM_TYPE, "Lcom/bytedance/ies/sdk/datachannel/DataChannel;", "Z"),
    strings = listOf("provideLiveSheetActions(). enable=", "LiveSheetActionProvider"),
)

/** The two answers in the sheet provider that keep the newer quality entry out of a room (#37). */
internal data class QualityEntryBlocks(val studioResult: Int, val lowLatencyResult: Int)

/**
 * Finds both blocks in [method]: the move-result of the Room mode check that follows the sget of
 * LiveMode.AUDIO (true for LIVE Studio), and the move-result of Room.isSupportLowLatency. Each is a
 * boolean the entry's branch compares with one, so the hook can hand it back as zero.
 */
internal fun qualityEntryBlocks(method: Method): QualityEntryBlocks {
    val instructions = method.implementation?.instructions?.toList().orEmpty()
    fun resultAfter(call: Int): Int {
        if (instructions.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("LIVE controls: the sheet provider's call at $call has no boolean result")
        }
        return call + 1
    }
    val audio = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.SGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as? FieldReference).let {
                it?.definingClass == LIVE_MODE && it.name == "AUDIO"
            }
    }
    if (audio < 0) throw PatchException("LIVE controls: the sheet provider no longer reads LiveMode.AUDIO")
    val studio = (audio + 1 until instructions.size).firstOrNull { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.INVOKE_STATIC &&
            ((instruction as ReferenceInstruction).reference as? MethodReference).let {
                it != null && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(LIVE_ROOM_TYPE)
            }
    } ?: throw PatchException("LIVE controls: the sheet provider has no room mode check after the AUDIO read")
    val lowLatency = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            ((instruction as ReferenceInstruction).reference as? MethodReference).let {
                it?.definingClass == LIVE_ROOM_TYPE && it.name == "isSupportLowLatency"
            }
    }.map { it.index }.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one isSupportLowLatency read in the sheet provider")
    return QualityEntryBlocks(resultAfter(studio), resultAfter(lowLatency))
}

/**
 * Hands each block's boolean to the extension, which answers zero while Offer LIVE quality is on
 * and the flag otherwise. Both go in after their move-result, later one first so the earlier index
 * still holds; nothing branches onto the instruction after either.
 */
internal fun MutableMethod.hookQualityEntryBlocks(blocks: QualityEntryBlocks) {
    for (result in listOf(blocks.studioResult, blocks.lowLatencyResult).sortedDescending()) {
        val register = (getInstruction(result) as OneRegisterInstruction).registerA
        addInstructions(
            result + 1,
            """
                invoke-static {v$register}, $EXTENSION->qualityEntryBlocked(I)I
                move-result v$register
            """,
        )
    }
}
