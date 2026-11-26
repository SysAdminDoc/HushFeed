/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.navigation.branchTargets
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val RAIL_HOLD_EXTENSION = "Lapp/morphe/extension/tiktok/interaction/RailHold;"
internal const val VIDEO_COMMENT_ASSEM = "Lcom/ss/android/ugc/aweme/feed/assem/videocomment/VideoCommentAssem;"
internal const val VIDEO_SHARE_ASSEM = "Lcom/ss/android/ugc/aweme/feed/assem/share/VideoShareAssem;"
internal const val COMMENT_ABILITY = "Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoCommentAbility;"
internal const val SHARE_ABILITY = "Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoShareAbility;"
internal const val FAVORITES_ABILITY = "Lcom/ss/android/ugc/feed/platform/cell/ability/VideoFavoriteAssemAbility;"
internal const val COMMENT_MENU_KEY = "video_comment_button_long_click"
internal const val FAVORITES_MENU_KEY = "video_favorite_button_long_click"
internal const val FAVORITES_MENU_EVENT = "long_press_favorite"
internal const val EDGE_SPEEDUP_ABILITY = "Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAbility;"
internal const val FAVORITES_TOUCH_LABEL = "getQuickFavoriteOnTouchDelegate"
internal const val VIDEO_FAVORITE_ASSEM = "Lcom/ss/android/ugc/aweme/feed/favorite/VideoFavoriteAssem;"
internal const val FAVORITES_BIND_LABEL = "VideoFavoriteAssem onBind"
private const val VIEW = "Landroid/view/View;"
private const val ON_CLICK = "Landroid/view/View\$OnClickListener;"
private const val ON_LONG_CLICK = "Landroid/view/View\$OnLongClickListener;"
private const val ON_TOUCH = "Landroid/view/View\$OnTouchListener;"
private const val HANDLER = "Landroid/os/Handler;"
private const val RUNNABLE = "Ljava/lang/Runnable;"
private const val FUNCTION2 = "Lkotlin/jvm/functions/Function2;"
private const val BOOLEAN = "Ljava/lang/Boolean;"

/** Whether the instruction is TikTok's own View.setOnLongClickListener call. */
internal fun Instruction.setsLongClick(): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
        it.definingClass == VIEW && it.name == "setOnLongClickListener" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(ON_LONG_CLICK)
    } == true

/**
 * The Comment button's long press, which opens the emoji row. TikTok reaches it two ways: a
 * long-click listener on the button, or, when a server flag says so, the button's touch listener
 * after a timer of its own that also keeps letting go from counting as a tap. On 47.0.3 the S22
 * gets the second, where no long-click listener is set at all. Both call this one method, which
 * hands the button's place to the emoji row under a key naming it, and that key picks the method
 * out of the class on every build.
 */
internal object CommentMenuFingerprint : Fingerprint(
    definingClass = VIDEO_COMMENT_ASSEM,
    returnType = "V",
    parameters = listOf(),
    strings = listOf(COMMENT_MENU_KEY),
)

/**
 * The Favorites button's long press, which offers a new collection. TikTok's touch listener on the
 * button runs it from a timer that first marks the press as a long one, so letting go doesn't save
 * the video. It's a lambda with an obfuscated name. What picks it out is that it both logs the
 * long press and hands the button's place to the offer under a key naming it, and only it does.
 */
internal object FavoritesMenuFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(FAVORITES_MENU_KEY, FAVORITES_MENU_EVENT),
)

/**
 * The timer TikTok's touch listener on Favorites posts as a press lands, which marks the press as
 * a long one and runs the collection offer. It names itself in a trace label
 * ("QuickFavoriteManager@2914.getQuickFavoriteOnTouchDelegate$1$action$1" on 47.9.3, the number
 * changing between builds), and its constructor takes the listener it belongs to.
 */
internal object FavoritesTouchTimerFingerprint : Fingerprint(
    name = "run",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string(FAVORITES_TOUCH_LABEL, StringComparisonType.CONTAINS)),
    custom = { _, classDef -> RUNNABLE in classDef.interfaces },
)

/**
 * The touch listener a Favorites [timer] belongs to: of the types its constructor takes, the one
 * [lookup] finds is a View.OnTouchListener.
 */
internal fun favoritesTouchListenerType(timer: ClassDef, lookup: (String) -> ClassDef?): String {
    val listeners = timer.methods.filter { it.name == "<init>" }
        .flatMap { it.parameterTypes.map(CharSequence::toString) }
        .distinct()
        .filter { type -> lookup(type)?.interfaces?.contains(ON_TOUCH) == true }
    if (listeners.size != 1) {
        throw PatchException("Long-press controls: the Favorites timer ${timer.type} belongs to ${listeners.size} touch listeners, not one.")
    }
    return listeners.single()
}

/**
 * The indexes in the Favorites listener's onTouch where the answer of TikTok's icon check is
 * unboxed into a register: a Function2 call, its Boolean taken out with booleanValue and moved.
 * The index is the move's.
 */
internal fun Method.favoritesPressChecks(): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.INVOKE_INTERFACE &&
            instructions[index].getReference<MethodReference>()?.let {
                it.definingClass == FUNCTION2 && it.name == "invoke"
            } == true &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            instructions.getOrNull(index + 2)?.let {
                it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == BOOLEAN
            } == true &&
            instructions.getOrNull(index + 3)?.let {
                it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { call ->
                    call.definingClass == BOOLEAN && call.name == "booleanValue"
                } == true
            } == true &&
            instructions.getOrNull(index + 4)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 4 }
}

/**
 * Hands the answer of TikTok's icon check on Favorites to the extension (#499). Accounts in
 * TikTok's tt_interaction_area_ux_opt test at 3 or more only start the long press when a press
 * lands on the icon or its count, so a hold beside them did nothing at all. The extension answers
 * yes for any press on the button while Hold TikTok's save button to download is on.
 */
internal fun MutableMethod.letFavoritesPressAnywhere() {
    val checks = favoritesPressChecks()
    if (checks.size != 1) {
        throw PatchException("Long-press controls: $definingClass->$name asks ${checks.size} press checks, not one.")
    }
    val moveAt = checks.single()
    val register = (implementation!!.instructions[moveAt] as OneRegisterInstruction).registerA
    addInstructions(
        moveAt + 1,
        """
            invoke-static/range {v$register .. v$register}, $RAIL_HOLD_EXTENSION->favoritesPressCounts(I)Z
            move-result v$register
        """,
    )
}

/**
 * Favorites' bind, run each time the button is put on a video (A4 on 47.9.3). It takes TikTok's
 * touch listener off the button, then gives it one of two things. Signed in, outside the
 * landscape feed and on a post that's out, it's the touch listener whose timer starts the long
 * press. Otherwise it's only a click listener of the assem's own, and there's no long press at
 * all (#499). The method names itself in a trace label.
 */
internal object FavoritesBindFingerprint : Fingerprint(
    definingClass = VIDEO_FAVORITE_ASSEM,
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(FAVORITES_BIND_LABEL),
)

/** Whether the instruction is a View.setOnTouchListener call. */
internal fun Instruction.setsTouch(): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
        it.definingClass == VIEW && it.name == "setOnTouchListener" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(ON_TOUCH)
    } == true

/**
 * The two places in Favorites' bind the hold save hooks, each an index with the button's register
 * there: [clearAt] is where TikTok takes its touch listener off the button, [plainClickAt] is where
 * it gives the button only its plain click.
 */
internal data class FavoritesBindSites(val clearAt: Int, val clearButton: Int, val plainClickAt: Int, val plainClickButton: Int)

/**
 * Reads [FavoritesBindSites] from the bind. The clear is the touch listener set to a register just
 * loaded with null. The plain click is the static call taking a click listener and the button,
 * whose listener was built from the assem the instruction before. Both are refused unless there's
 * exactly one, and unless nothing jumps onto the instruction a hook goes in front of.
 */
internal fun Method.favoritesBindSites(): FavoritesBindSites {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("Long-press controls: $definingClass->$name has no code.")
    val clears = instructions.indices.filter { index ->
        val set = instructions[index]
        set.setsTouch() && instructions.getOrNull(index - 1)?.let { load ->
            load.opcode == Opcode.CONST_4 && (load as NarrowLiteralInstruction).narrowLiteral == 0 &&
                (load as OneRegisterInstruction).registerA == (set as FiveRegisterInstruction).registerD
        } == true
    }
    val plainClicks = instructions.indices.filter { index ->
        val call = instructions[index]
        call.opcode == Opcode.INVOKE_STATIC && call.getReference<MethodReference>()?.let {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(ON_CLICK, VIEW)
        } == true && instructions.getOrNull(index - 1)?.let { built ->
            built.opcode == Opcode.INVOKE_DIRECT && built.getReference<MethodReference>()?.let {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(VIDEO_FAVORITE_ASSEM)
            } == true && (built as FiveRegisterInstruction).registerC == (call as FiveRegisterInstruction).registerC
        } == true
    }
    if (clears.size != 1) {
        throw PatchException("Long-press controls: $definingClass->$name takes the touch listener off ${clears.size} times, not once.")
    }
    if (plainClicks.size != 1) {
        throw PatchException("Long-press controls: $definingClass->$name gives the plain click ${plainClicks.size} times, not once.")
    }
    val clearAt = clears.single()
    val plainClickAt = plainClicks.single()
    val targets = branchTargets()
    if (clearAt + 1 in targets || plainClickAt in targets) {
        throw PatchException("Long-press controls: a jump in $definingClass->$name lands where the hold save would go.")
    }
    return FavoritesBindSites(
        clearAt, (instructions[clearAt] as FiveRegisterInstruction).registerC,
        plainClickAt, (instructions[plainClickAt] as FiveRegisterInstruction).registerD,
    )
}

/**
 * Gives Favorites a long press of Hushfeed's own where TikTok's bind leaves it only a click (#499):
 * signed out, in the landscape feed, on a post still publishing or scheduled. The extension puts
 * its listener on the button at the plain click and takes it off again right after TikTok clears
 * its touch listener, so a button bound to TikTok's own long press next never has both. Inserted
 * last one first so the indexes found hold.
 */
internal fun MutableMethod.giveFavoritesItsOwnHold() {
    val sites = favoritesBindSites()
    listOf(
        sites.plainClickAt to "invoke-static/range {v${sites.plainClickButton} .. v${sites.plainClickButton}}, " +
            "$RAIL_HOLD_EXTENSION->favoritesClickOnly($VIEW)V",
        sites.clearAt + 1 to "invoke-static/range {v${sites.clearButton} .. v${sites.clearButton}}, " +
            "$RAIL_HOLD_EXTENSION->favoritesBound($VIEW)V",
    ).sortedByDescending { it.first }.forEach { (at, smali) -> addInstructions(at, smali) }
}

/** Whether the instruction is a Handler.removeCallbacks(Runnable) call. */
internal fun Instruction.dropsCallbacks(): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
        it.definingClass == HANDLER && it.name == "removeCallbacks" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(RUNNABLE)
    } == true

/** Whether the instruction asks the hold ability's (FF)Z check. */
internal fun Instruction.asksHoldCheck(): Boolean =
    opcode == Opcode.INVOKE_INTERFACE && getReference<MethodReference>()?.let {
        it.definingClass == EDGE_SPEEDUP_ABILITY && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("F", "F")
    } == true

/**
 * TikTok's hold gesture's touch listener. As a press lands it runs the hold check and, when the
 * check lets the press through, posts the hold's start for 300 ms later. It drops that post with
 * removeCallbacks when the finger moves off the spot, when a second finger lands and when the
 * press ends. The class is obfuscated. It is the only touch listener that asks the hold ability's
 * check.
 */
internal object HoldTouchFingerprint : Fingerprint(
    name = "onTouch",
    returnType = "Z",
    parameters = listOf(VIEW, "Landroid/view/MotionEvent;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            definingClass = EDGE_SPEEDUP_ABILITY,
            returnType = "Z",
            parameters = listOf("F", "F"),
        ),
    ),
    custom = { method, classDef ->
        "Landroid/view/View\$OnTouchListener;" in classDef.interfaces &&
            method.implementation?.instructions?.any { it.asksHoldCheck() } == true &&
            method.implementation?.instructions?.any { it.dropsCallbacks() } == true
    },
)

/** The Share button's view setup, which gives it and two of its children their long presses. */
internal object ShareViewCreatedFingerprint : Fingerprint(
    definingClass = VIDEO_SHARE_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf(VIEW),
    custom = { method, _ -> method.implementation?.instructions?.any { it.setsLongClick() } == true },
)

/**
 * The indexes in TikTok's hold check of the asks to [ability] whether a press is on its button:
 * an interface call taking the press's two floats and answering Z, its answer moved straight
 * into a register. The index is the move's.
 */
internal fun Method.railHitTests(ability: String): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.filter { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.INVOKE_INTERFACE && instruction.getReference<MethodReference>()?.let {
            it.definingClass == ability && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("F", "F")
        } == true && instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 1 }
}

/**
 * Hands each Comment, Share and Favorites ask in TikTok's hold check to the extension, which
 * answers "not on it" for a button a hold plays at the hold speed on. Inserted after each answer's
 * move, last one first so the indexes found hold.
 */
internal fun MutableMethod.answerRailHitTests() {
    val asks = railHitTests(COMMENT_ABILITY).map { it to "commentStopsHold" } +
        railHitTests(SHARE_ABILITY).map { it to "shareStopsHold" } +
        railHitTests(FAVORITES_ABILITY).map { it to "favoritesStopsHold" }
    for (answer in listOf("commentStopsHold", "shareStopsHold", "favoritesStopsHold")) {
        if (asks.none { it.second == answer }) {
            throw PatchException("Long-press controls: the hold check no longer asks the button $answer answers for.")
        }
    }
    asks.sortedByDescending { it.first }.forEach { (moveAt, answer) ->
        val register = (implementation!!.instructions[moveAt] as OneRegisterInstruction).registerA
        addInstructions(
            moveAt + 1,
            """
                invoke-static/range {v$register .. v$register}, $RAIL_HOLD_EXTENSION->$answer(Z)Z
                move-result v$register
            """,
        )
    }
}

/** Returns from the Comment button's long press before the emoji row when the press is held. */
internal fun MutableMethod.skipCommentMenuWhenHeld() =
    guardAtEntry("Long-press controls", "invoke-static {}, $RAIL_HOLD_EXTENSION->skipCommentMenu()Z", "return-void")

/**
 * Leaves the Favorites long press before it logs or offers anything when the press is held. The
 * lambda answers Kotlin's Unit, whose field name is obfuscated too, so the skip jumps to where
 * the lambda itself loads Unit and returns it.
 */
internal fun MutableMethod.skipFavoritesMenuWhenHeld() {
    val instructions = implementation!!.instructions.toList()
    val unit = instructions.indices.lastOrNull { index ->
        instructions[index].opcode == Opcode.SGET_OBJECT &&
            instructions[index].getReference<FieldReference>()?.type == "Lkotlin/Unit;" &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.RETURN_OBJECT &&
            (instructions[index] as OneRegisterInstruction).registerA ==
            (instructions[index + 1] as OneRegisterInstruction).registerA
    } ?: throw PatchException("Long-press controls: $definingClass->$name no longer returns Unit at its end.")
    requireLocals("Long-press controls", 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $RAIL_HOLD_EXTENSION->skipFavoritesMenu()Z
            move-result v0
            if-nez v0, :rail_hold_unit
        """,
        ExternalLabel("rail_hold_unit", getInstruction(unit)),
    )
}

/**
 * Sends each of the hold listener's drops through the extension, which drops the post the same
 * way and forgets every pass, so a press TikTok won't hold opens its button's menu as before.
 */
internal fun MutableMethod.routeHoldDrops() {
    val sites = implementation!!.instructions.withIndex().filter { it.value.dropsCallbacks() }.map { it.index }
    if (sites.isEmpty()) throw PatchException("Long-press controls: $definingClass->$name no longer drops its hold.")
    sites.forEach { index ->
        val call = implementation!!.instructions[index] as FiveRegisterInstruction
        replaceInstruction(
            index,
            "invoke-static {v${call.registerC}, v${call.registerD}}, $RAIL_HOLD_EXTENSION->holdDropped($HANDLER$RUNNABLE)V",
        )
    }
}

/** Sends each of the method's long-click sets through [hook], which wraps TikTok's listener. */
internal fun MutableMethod.routeLongClicks(hook: String) {
    val sites = implementation!!.instructions.withIndex().filter { it.value.setsLongClick() }.map { it.index }
    if (sites.isEmpty()) throw PatchException("Long-press controls: $definingClass->$name sets no long press.")
    sites.forEach { index ->
        val call = implementation!!.instructions[index] as FiveRegisterInstruction
        replaceInstruction(
            index,
            "invoke-static {v${call.registerC}, v${call.registerD}}, $RAIL_HOLD_EXTENSION->$hook($VIEW$ON_LONG_CLICK)V",
        )
    }
}
