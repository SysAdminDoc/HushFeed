/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.util.addInstructions
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val KEEP_CHAT_PLACE = "Lapp/morphe/extension/tiktok/inbox/KeepChatPlace;"

/** The chat's message list ability. It keeps its name on 47.9.3. */
internal const val SUBMIT_LIST_ABILITY = "Lcom/ss/android/ugc/aweme/im/messagelist/ui/MessageListSubmitListAbilityImpl;"

/** The chat list's layout manager, laid out from the bottom so position 0 is the newest message. */
internal const val CHAT_LAYOUT_MANAGER = "Lcom/ss/android/ugc/aweme/im/ui/recyclerview/ChatLinearLayoutManager;"

/** What the list consumer logs when the ability's own interceptor throws. */
internal const val CHAT_SUBMIT_LOG = "MessageListSubmitListAbilityImpl [IMessageEventProtocol][interceptMessageEvent]"

/**
 * The consumer every change to a chat's message list goes through (#503), `LX/0YgJ.accept` on
 * 47.9.3, built by the ability's `subscribeToStreams`. It takes the event behind the change and
 * asks a decider, `LX/0N3v.LIZ(event, list, state, flag)`, whether the list moves to the newest
 * message. A yes queues a timestamp, and the list commit that follows sees it and calls
 * `scrollToPositionWithOffset(0, 0)` on the chat's layout manager. This is the one caller of that
 * decider. Its class holds the ability in a field, which is how the hook reaches the list.
 */
internal object ChatScrollDecisionFingerprint : Fingerprint(
    name = "accept",
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(CHAT_SUBMIT_LOG),
    custom = { method, classDef -> abilityField(classDef) != null && method.chatScrollDecision() != null },
)

/** Where the decider's answer is taken, and the registers that hold it and the event. */
internal data class ChatScrollDecision(val answer: Int, val answerRegister: Int, val eventRegister: Int)

private fun MethodReference.isScrollDecider() =
    returnType == "Z" && parameterTypes.size == 4 &&
        parameterTypes[1].toString() == "Ljava/util/List;" && parameterTypes[3].toString() == "Z"

/**
 * The single decider call and the `move-result` right after it, or null. The event is the call's
 * first argument after the decider itself. The hook goes right after the `move-result`, which
 * nothing may branch to, or a path landing there would skip it.
 */
internal fun Method.chatScrollDecision(): ChatScrollDecision? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val calls = instructions.indices.filter { index ->
        val opcode = instructions[index].opcode
        (opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_VIRTUAL) &&
            instructions[index].getReference<MethodReference>()?.isScrollDecider() == true
    }
    val call = calls.singleOrNull() ?: return null
    val invoke = instructions[call] as FiveRegisterInstruction
    if (invoke.registerCount != 5) return null
    val move = instructions.getOrNull(call + 1) ?: return null
    if (move.opcode != Opcode.MOVE_RESULT) return null
    if (isBranchTarget(call + 2)) return null
    return ChatScrollDecision(call + 1, (move as OneRegisterInstruction).registerA, invoke.registerD)
}

/** The consumer's field that holds the list ability, or null unless there is exactly one. */
internal fun abilityField(consumer: ClassDef) =
    consumer.fields.singleOrNull { it.type == SUBMIT_LIST_ABILITY && !AccessFlags.STATIC.isSet(it.accessFlags) }

/** The ability's getter for the chat's layout manager, or null unless there is exactly one. */
internal fun layoutManagerGetter(ability: ClassDef) = ability.methods.singleOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == CHAT_LAYOUT_MANAGER
}

/** Everything the hook writes with, found and checked before anything is written. */
internal class ChatScrollHook(
    val method: Method,
    val decision: ChatScrollDecision,
    val self: Int,
    val scratch: Int,
    val ability: ImmutableFieldReference,
    val layoutManager: ImmutableMethodReference,
)

internal fun chatScrollHook(patch: String, method: Method, consumer: ClassDef, classOf: (String) -> ClassDef?): ChatScrollHook {
    val decision = method.chatScrollDecision()
        ?: throw PatchException("$patch: ${method.definingClass}->${method.name} has no single scroll decider call to hook.")
    val field = abilityField(consumer)
        ?: throw PatchException("$patch: ${consumer.type} holds no single $SUBMIT_LIST_ABILITY.")
    val ability = classOf(SUBMIT_LIST_ABILITY) ?: throw PatchException("$patch: $SUBMIT_LIST_ABILITY is gone.")
    val getter = layoutManagerGetter(ability)
        ?: throw PatchException("$patch: $SUBMIT_LIST_ABILITY has no single getter for $CHAT_LAYOUT_MANAGER.")
    val self = method.implementation!!.registerCount - 2
    val scratch = method.getFreeRegisterProvider(decision.answer + 1, 1, decision.answerRegister, decision.eventRegister, self)
        .getFreeRegister()
    if (maxOf(self, scratch, decision.answerRegister, decision.eventRegister) > 15) {
        throw PatchException("$patch: the scroll decision sits in registers past v15.")
    }
    return ChatScrollHook(
        method,
        decision,
        self,
        scratch,
        ImmutableFieldReference(field.definingClass, field.name, field.type),
        ImmutableMethodReference(getter.definingClass, getter.name, emptyList(), getter.returnType),
    )
}

/**
 * Hands the decider's answer to the extension with the chat's layout manager and the event, and
 * takes the extension's answer in its place. Straight-line, with only a register nothing reads
 * written besides the answer, so TikTok's code after it runs as it always did.
 */
internal fun MutableMethod.keepChatPlace(hook: ChatScrollHook) {
    val answer = hook.decision.answerRegister
    addInstructions(
        hook.decision.answer + 1,
        """
            iget-object v${hook.scratch}, v${hook.self}, ${hook.ability}
            invoke-virtual { v${hook.scratch} }, ${hook.layoutManager}
            move-result-object v${hook.scratch}
            invoke-static { v$answer, v${hook.scratch}, v${hook.decision.eventRegister} }, $KEEP_CHAT_PLACE->scrollToNewest(ILjava/lang/Object;Ljava/lang/Object;)Z
            move-result v$answer
        """,
    )
}
