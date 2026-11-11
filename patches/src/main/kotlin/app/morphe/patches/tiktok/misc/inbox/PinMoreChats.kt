/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The server setting that caps how many chats the Inbox lets you pin, 5 unless the server says otherwise. */
internal const val PIN_CHAT_LIMIT_KEY = "tt_im_inbox_pin_chat_limit"

internal const val PIN_MORE_CHATS = "Lapp/morphe/extension/tiktok/inbox/PinMoreChats;"

/** The settings read: (key, default) answered as an int. */
private val SETTINGS_INT_READ_PARAMETERS = listOf("Ljava/lang/String;", "I")

private fun MethodReference.isSettingsIntRead() =
    returnType == "I" && parameterTypes.map { it.toString() } == SETTINGS_INT_READ_PARAMETERS

/**
 * The lazy that answers how many chats may be pinned (#385): a static lambda body that reads
 * [PIN_CHAT_LIMIT_KEY] from TikTok's settings with a default of 5 and boxes the answer. On 47.7.3
 * it is `AFwS158S0000000_33_I1.invoke$714`, the value behind `X.1B46.LIZ`, and the one reader of
 * that lazy is `IMChatListImpl`'s pin check, which counts the pinned chats in both Inbox tabs and
 * refuses the pin with TikTok's "pin up to" toast once the count reaches the cap. Every pin entry
 * (the chat's settings switch and the Inbox's long press) goes through that one check. The key's
 * registry entry returns nothing, so the return type leaves it out.
 */
internal object PinChatLimitFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf(PIN_CHAT_LIMIT_KEY),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) && method.pinChatLimitAnswer() >= 0 },
)

/**
 * The index of the `move-result` that takes the static settings read after the key's load, or -1.
 * The hook goes right after it.
 */
internal fun Method.pinChatLimitAnswer(): Int {
    val instructions = implementation?.instructions?.toList() ?: return -1
    val key = instructions.indexOfFirst {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            it.getReference<StringReference>()?.string == PIN_CHAT_LIMIT_KEY
    }
    if (key < 0) return -1
    for (index in key + 1 until instructions.size - 1) {
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) continue
        if (call.getReference<MethodReference>()?.isSettingsIntRead() != true) continue
        return if (instructions[index + 1].opcode == Opcode.MOVE_RESULT) index + 1 else -1
    }
    return -1
}

/**
 * The cap goes through the extension right after TikTok reads it, straight-line: the extension
 * hands back TikTok's own number while the switch is off.
 */
internal fun MutableMethod.liftPinChatLimit(patch: String) {
    val answer = pinChatLimitAnswer()
    if (answer < 0) throw PatchException("$patch: $definingClass->$name doesn't read $PIN_CHAT_LIMIT_KEY.")
    val register = (implementation!!.instructions[answer] as OneRegisterInstruction).registerA
    addInstructions(
        answer + 1,
        """
            invoke-static/range { v$register .. v$register }, $PIN_MORE_CHATS->pinLimit(I)I
            move-result v$register
        """,
    )
}
