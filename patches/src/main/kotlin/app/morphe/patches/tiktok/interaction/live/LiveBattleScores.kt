/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.util.addInstructions
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val BATTLE_SCORE_EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveControls;"
private const val BATTLE_SCORE_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"

/**
 * A LIVE battle's high score rule as the server sends it (BattleInfoResponse.fuzzyDisplayConfigV2),
 * real-named on 47.9.3: whether viewers see big scores capped (normalControlApplied), from which
 * score (threshold) and who still sees them whole (originDisplayToUserList).
 */
internal const val HIGH_SCORE_CONTROL_CFG =
    "Lcom/bytedance/android/livesdkapi/depend/model/live/match/HighScoreControlCfg;"

/**
 * Where a battle's score bar takes the server's high score rule (X.18G8.LJIILL on 48.0.2). It
 * builds the small (capped, threshold) class every battle score view reads from it, the one that
 * turns a score past the threshold into "1M+" style text, and hands it to the co-host windows.
 */
internal object BattleHighScoreRuleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(HIGH_SCORE_CONTROL_CFG),
    strings = listOf("hit_high_score_modifications"),
)

/**
 * The notice a battle posts when a score gets very high (X.18Kk.LJIIIIZZ on 48.0.2). With the rule
 * applied it picks the notice text written for viewers, which leaves the number out, over the one
 * with it.
 */
internal object BattleHighScoreNoticeFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("handleBattleExtremeHighScoreNoticeMessage"),
    custom = { method, _ -> highScoreRuleRead(method) != null },
)

/**
 * The battle bar's score writer (X.18Kk.LIZ on 48.0.2): a score of a million or more is written
 * as "1M+" unless its last parameter says to show it whole, which TikTok sets for the host.
 */
internal object BattleScoreTextFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("J", "Landroid/widget/TextView;", "Z"),
    strings = listOf("1M+"),
)

/**
 * A co-host window's score writer (X.1869.z0 on 48.0.2): the same "1M+" from a million on,
 * unless the window's own flag, read first thing, says to show it whole.
 */
internal object CoHostScoreTextFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("J", BATTLE_SCORE_VIEW),
    strings = listOf("1M+"),
)

/** A boolean a hook rewrites: the register holding it at [index], where the hook goes in. */
internal data class BattleFlag(val index: Int, val register: Int)

private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

/** The type of the (capped, threshold) class the rule method builds with a (ZJ)V constructor. */
internal fun battleScoreCapType(rule: Method): String {
    val types = rule.code().mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_DIRECT) return@mapNotNull null
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        call.definingClass.takeIf {
            call.name == "<init>" && call.returnType == "V" &&
                call.parameterTypes.map(CharSequence::toString) == listOf("Z", "J")
        }
    }.distinct()
    return types.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one battle score cap in the high score rule, found ${types.size}")
}

/** The cap's (ZJ)V constructor in [cap]. */
internal fun battleScoreCapInit(cap: ClassDef): Method =
    cap.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "J")
    } ?: throw PatchException("LIVE controls: the battle score cap's constructor wasn't found in ${cap.type}")

/** The constructor's store of its capped flag: the hook goes right before it, on its register. */
internal fun battleScoreCapStore(init: Method): BattleFlag {
    val code = init.code()
    val stores = code.indices.filter { index ->
        code[index].opcode == Opcode.IPUT_BOOLEAN &&
            ((code[index] as ReferenceInstruction).reference as FieldReference).definingClass == init.definingClass
    }
    val at = stores.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one flag store in the battle score cap, found ${stores.size}")
    val register = (code[at] as TwoRegisterInstruction).registerA
    // The flag is the first parameter: p1, right after this.
    val first = init.implementation!!.registerCount - 4 + 1
    if (register != first) throw PatchException("LIVE controls: the battle score cap stores v$register, not its parameter")
    return checked(init, BattleFlag(at, register))
}

/** The notice's read of the rule's normalControlApplied: the hook goes right after it. */
internal fun highScoreRuleRead(method: Method): BattleFlag? {
    val code = method.code()
    val reads = code.indices.filter { index ->
        code[index].opcode == Opcode.IGET_BOOLEAN &&
            ((code[index] as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == HIGH_SCORE_CONTROL_CFG && it.name == "normalControlApplied"
            }
    }
    val at = reads.singleOrNull() ?: return null
    return BattleFlag(at + 1, (code[at] as TwoRegisterInstruction).registerA)
}

/** The bar writer's "show it whole" parameter, its last: the hook goes first thing. */
internal fun battleScoreWholeParameter(writer: Method): BattleFlag {
    if (writer.parameterTypes.lastOrNull()?.toString() != "Z") {
        throw PatchException("LIVE controls: the battle score writer has no trailing flag")
    }
    return checked(writer, BattleFlag(0, writer.implementation!!.registerCount - 1))
}

/** The co-host writer's first instruction, its read of its own "show it whole" flag. */
internal fun coHostScoreWholeRead(writer: Method): BattleFlag {
    val first = writer.code().firstOrNull()
    val field = (first as? ReferenceInstruction)?.reference as? FieldReference
    if (first?.opcode != Opcode.IGET_BOOLEAN || field?.definingClass != writer.definingClass) {
        throw PatchException("LIVE controls: the co-host score writer doesn't start with its flag")
    }
    return checked(writer, BattleFlag(1, (first as TwoRegisterInstruction).registerA))
}

private fun checked(method: Method, flag: BattleFlag): BattleFlag {
    if (method.isBranchTarget(flag.index)) {
        throw PatchException("LIVE controls: a branch lands where the battle score hook goes in ${method.name}")
    }
    return flag
}

/**
 * Hands the boolean in [flag]'s register to the extension's [call], which answers the value the
 * method goes on with. The range form takes any register; the result goes back in the same one.
 */
internal fun MutableMethod.hookBattleFlag(flag: BattleFlag, call: String) {
    addInstructions(
        flag.index,
        """
            invoke-static/range {v${flag.register} .. v${flag.register}}, $BATTLE_SCORE_EXTENSION->$call(I)Z
            move-result v${flag.register}
        """,
    )
}

/**
 * Show exact battle scores (#495). A LIVE battle's server rule caps big scores for viewers: the
 * bar, the co-host windows and the supporter chips show "1M+" (or the threshold with a "+") while
 * the host sees the number. Each of TikTok's own answers is handed to the extension before it's
 * used: whether the cap applies (the cap class's constructor and the high score notice), and
 * whether a writer shows the score whole. Off, every answer comes back as TikTok gave it.
 * Everything is found and checked before anything is written.
 */
internal fun installBattleScoreHooks() {
    val rule = BattleHighScoreRuleFingerprint.originalMethod
    val capType = battleScoreCapType(rule)
    val cap = mutableClassDefBy(capType)
    val capInit = battleScoreCapInit(cap)
    val capStore = battleScoreCapStore(capInit)
    val notice = BattleHighScoreNoticeFingerprint.originalMethod
    val noticeRead = highScoreRuleRead(notice)
        ?: throw PatchException("LIVE controls: the high score notice's rule read wasn't found")
    checked(notice, noticeRead)
    val barWriter = BattleScoreTextFingerprint.originalMethod
    val barWhole = battleScoreWholeParameter(barWriter)
    val coHostWriter = CoHostScoreTextFingerprint.originalMethod
    val coHostWhole = coHostScoreWholeRead(coHostWriter)

    cap.findMutableMethodOf(capInit).hookBattleFlag(capStore, "battleScoreCap")
    BattleHighScoreNoticeFingerprint.method.hookBattleFlag(noticeRead, "battleScoreCap")
    BattleScoreTextFingerprint.method.hookBattleFlag(barWhole, "battleScoreWhole")
    CoHostScoreTextFingerprint.method.hookBattleFlag(coHostWhole, "battleScoreWhole")
}
