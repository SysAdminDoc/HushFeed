/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sensitivewarnings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The labels a shared video's chat card carries in its message data ("Caution", and a policy title). */
internal val CHAT_CARD_LABEL_KEYS = listOf("share_video_cover_caution_label", "share_video_cover_policy_title")
private const val EXTENSION = "Lapp/morphe/extension/tiktok/feed/SensitiveWarnings;"

/**
 * The cover callback of a video shared into a chat (AwemeCardAssem's image listener, 14te on
 * 47.5.3). Once the cover loads it reads both labels out of the message template's extras and, when
 * either is set, shows the "Caution · May contain inaccurate information" strip over the cover. The
 * sender's app writes them from the video's TnsImInfo when it shares, so the receiver can't clear
 * them from the video: the card has to be told they're empty. The only other method naming both
 * keys that runs is the one that writes them, which builds a send task instead of returning void.
 * 47.7.2 also ships the chat video stack's member card (0rT7->LJ()Z), which tests both labels for
 * emptiness, but nothing in that build calls it or the service that builds it.
 */
internal object ChatCardCautionFingerprint : Fingerprint(
    returnType = "V",
    strings = CHAT_CARD_LABEL_KEYS,
    custom = { method, _ -> isChatCardCaution(method) },
)

internal fun isChatCardCaution(method: Method): Boolean {
    if (method.returnType != "V") return false
    val code = method.implementation ?: return false
    return CHAT_CARD_LABEL_KEYS.all { key -> labelRead(code.instructions.toList(), key) != null }
}

/**
 * Where the card reads one label: the `Map.get` on that key and the register it lands in. Null
 * when the key isn't followed straight away by that get and its result.
 */
private fun labelRead(instructions: List<Instruction>, key: String): Pair<Int, Int>? {
    val at = instructions.indexOfFirst { it.getReference<StringReference>()?.string == key }
    if (at < 0 || at + 2 >= instructions.size) return null
    val get = instructions[at + 1].getReference<MethodReference>() ?: return null
    if (get.definingClass != "Ljava/util/Map;" || get.name != "get") return null
    val result = instructions[at + 2]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    return at + 2 to (result as OneRegisterInstruction).registerA
}

/**
 * Passes each label through SensitiveWarnings.chatCardLabel right after the card reads it, so with
 * Hide unverified content notices on both read as unset, which is what a share without a warning
 * has, and the card draws no strip. /range, since the label's register isn't held under v16.
 */
internal fun MutableMethod.hideChatCardLabels(patch: String) {
    // Both reads are found before either is touched, and the later one goes in first so the
    // earlier index still holds.
    val instructions = implementation!!.instructions.toList()
    val reads = CHAT_CARD_LABEL_KEYS.map { key ->
        labelRead(instructions, key)
            ?: throw PatchException("$patch: $definingClass->$name no longer reads \"$key\" from its extras.")
    }
    for ((index, register) in reads.sortedByDescending { it.first }) {
        addInstructions(
            index + 1,
            """
                invoke-static/range { v$register .. v$register }, $EXTENSION->chatCardLabel(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$register
            """,
        )
    }
}
