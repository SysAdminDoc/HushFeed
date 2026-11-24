/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.feedfilter.LiveDrawRoomPageFingerprint
import app.morphe.patches.tiktok.feedfilter.liveRequestTypeGetter
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val ENTER_ROOM_CONFIG = "Lcom/bytedance/android/livesdkapi/session/EnterRoomConfig;"
internal const val FOLLOWING_INTEREST_USER = "Lcom/ss/android/ugc/aweme/feed/module/FollowingInterestUser;"
internal const val ROW_ORDER_EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveRowOrder;"

/**
 * Where a tap on a LIVE in the row at the top of Following opens the room (X.1J8a.LJIIL on
 * 47.9.3): (Context, the tapped FollowingInterestUser, the row's list of them, position, view,
 * enter_from_merge, enter_method, skylight_data_from, two times). It builds the room's
 * EnterRoomConfig, room ids in the row's order included, and hands it to watchLive.
 */
internal object FollowingRowLiveEnterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;", FOLLOWING_INTEREST_USER, "Ljava/util/List;", "I",
        "Landroid/view/View;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "J", "J",
    ),
    strings = listOf("skylight_data_from"),
)

/** The watchLive call at [index] and the registers the hook hands over: the config, the tapped entry, the row. */
internal data class RowLiveEnter(val index: Int, val config: Int, val tapped: Int, val row: Int)

/**
 * The room's watchLive(Context, EnterRoomConfig, String) call in [method] and the registers the
 * hook reads there. The tapped entry and the row are the method's second and third parameters,
 * which nothing in it writes over; all three must fit a plain invoke.
 */
internal fun rowLiveEnter(method: Method): RowLiveEnter {
    val code = method.implementation?.instructions?.toList()
        ?: throw PatchException("LIVE controls: the Following row's LIVE opener has no code")
    val calls = code.indices.filter { index ->
        val instruction = code[index]
        instruction.opcode == Opcode.INVOKE_INTERFACE &&
            ((instruction as ReferenceInstruction).reference as MethodReference).let {
                it.name == "watchLive" && it.parameterTypes.map(CharSequence::toString) ==
                    listOf("Landroid/content/Context;", ENTER_ROOM_CONFIG, "Ljava/lang/String;")
            }
    }
    val at = calls.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one watchLive in the Following row's LIVE opener, found ${calls.size}")
    val config = (code[at] as FiveRegisterInstruction).registerE
    // Static: the parameters fill the frame's last registers, the two longs taking two each.
    val parameterRegisters = method.parameterTypes.sumOf { type ->
        val wide = type.toString() == "J" || type.toString() == "D"
        if (wide) 2 else 1
    }
    val first = method.implementation!!.registerCount - parameterRegisters
    val enter = RowLiveEnter(at, config, first + 1, first + 2)
    for (instruction in code) {
        if (instruction !is OneRegisterInstruction) continue
        val written = when {
            instruction.opcode.setsWideRegister() -> setOf(instruction.registerA, instruction.registerA + 1)
            instruction.opcode.setsRegister() -> setOf(instruction.registerA)
            else -> emptySet()
        }
        if (enter.tapped in written || enter.row in written) {
            throw PatchException("LIVE controls: the Following row's LIVE opener writes over its parameters")
        }
    }
    if (listOf(enter.config, enter.tapped, enter.row).any { it > 15 }) {
        throw PatchException("LIVE controls: the Following row's LIVE opener keeps its config or row past v15")
    }
    if (method.isBranchTarget(at)) {
        throw PatchException("LIVE controls: a branch lands on the Following row's watchLive")
    }
    return enter
}

/** Hands the config, the tapped entry and the row to the extension right before the room opens. */
internal fun MutableMethod.hookRowLiveEnter(enter: RowLiveEnter) {
    addInstructions(
        enter.index,
        "invoke-static { v${enter.config}, v${enter.tapped}, v${enter.row} }, " +
            "$ROW_ORDER_EXTENSION->seed(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;)V",
    )
}

/**
 * Hands the LIVE page handler's pager (p0), page (p1) and the pager's request type to the
 * extension at the top of the handler, and puts the page it answers in p1. [requestType] is the
 * provider's getter, written for an invoke ([liveRequestTypeGetter]).
 */
internal fun MutableMethod.hookRowOrderPage(requestType: String) {
    requireLocals("LIVE controls", 3)
    addInstructions(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            invoke-virtual/range { p0 .. p0 }, $requestType
            move-result-object v2
            invoke-static { v0, v1, v2 }, $ROW_ORDER_EXTENSION->page(Ljava/lang/Object;Ljava/util/List;Ljava/lang/String;)Ljava/util/List;
            move-result-object p1
        """,
    )
}

/**
 * Swipe LIVEs in the Following row's order (#511). The pager a LIVE from the row opens in fills
 * itself from the server after the first room unless the config names the rooms to come next,
 * so the extension names the row's other LIVEs there, in the row's order. The server's pages
 * that follow can hold those LIVEs again, and the pager only drops a repeat of the room it
 * opened on, so each of its pages goes through the extension too, which takes the row's rooms
 * back out of them.
 */
internal fun installRowOrderHook() {
    val enter = rowLiveEnter(FollowingRowLiveEnterFingerprint.originalMethod)
    FollowingRowLiveEnterFingerprint.method.hookRowLiveEnter(enter)
    val requestType = LiveDrawRoomPageFingerprint.originalMethod
        .liveRequestTypeGetter(LiveDrawRoomPageFingerprint.originalClassDef)
    LiveDrawRoomPageFingerprint.method.hookRowOrderPage(requestType)
}
