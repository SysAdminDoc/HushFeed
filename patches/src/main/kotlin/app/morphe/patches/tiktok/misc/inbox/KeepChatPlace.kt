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
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val KEEP_CHAT_PLACE = "Lapp/morphe/extension/tiktok/inbox/KeepChatPlace;"

/** What the extension reads a chat event's name with (`KeepChatPlace.eventName`). */
internal const val CHAT_EVENT_NAME = "getName"

/**
 * A chat event's `toString`, which opens with the event's name and its first property. 47.9.3's
 * events also answered the name from `getName()`, which R8 dropped from 48.0.2 as unused, so the
 * patch gives it back to each event the extension tells apart, found by this.
 */
internal class ChatEventToStringFingerprint(opening: String) : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf(opening),
)

/** The events the extension tells apart by name, each with its `toString` on 48.0.2. */
internal val ChatEventFingerprints = mapOf(
    "OnSendMessageEvent" to ChatEventToStringFingerprint("OnSendMessageEvent(tracer="),
    "OnGetMessageEvent" to ChatEventToStringFingerprint("OnGetMessageEvent(list="),
    "OnQueryMessageEvent" to ChatEventToStringFingerprint("OnQueryMessageEvent(list="),
)

/** A chat event class, the name the extension knows it by, and whether it needs its `getName()` added. */
internal data class ChatEventName(val type: String, val name: String, val add: Boolean)

/** The string a `getName()` written as a const-string and its return answers, or null. */
internal fun Method.constantName(): String? {
    val code = implementation?.instructions?.toList() ?: return null
    if (code.size != 2 || code[0].opcode != Opcode.CONST_STRING || code[1].opcode != Opcode.RETURN_OBJECT) return null
    return code[0].getReference<StringReference>()?.string
}

/**
 * Each of [events], the class behind each name, checked against [eventType], the decider's event
 * parameter. A class that already has a `getName()` keeps it, which then has to answer its name.
 */
internal fun chatEventNames(patch: String, eventType: String, events: Map<String, ClassDef>) =
    events.map { (name, event) ->
        if (eventType !in event.interfaces) throw PatchException("$patch: ${event.type}, TikTok's $name, isn't a $eventType.")
        val own = event.methods.singleOrNull { it.name == CHAT_EVENT_NAME && it.parameterTypes.isEmpty() }
        if (own != null && own.constantName() != name) {
            throw PatchException("$patch: ${event.type}->$CHAT_EVENT_NAME() no longer answers $name.")
        }
        ChatEventName(event.type, name, own == null)
    }

/** The `getName()` [event] gets back, answering its name the way 47.9.3's events did. */
internal fun chatEventNameMethod(event: ChatEventName): MutableMethod =
    MutableMethod(ImmutableMethod(event.type, CHAT_EVENT_NAME, emptyList(), "Ljava/lang/String;",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(2, emptyList(), null, null))).apply { returnEarly(event.name) }

/** The chat's message list ability. It keeps its name on 47.9.3. */
internal const val SUBMIT_LIST_ABILITY = "Lcom/ss/android/ugc/aweme/im/messagelist/ui/MessageListSubmitListAbilityImpl;"

/** The chat list's layout manager, laid out from the bottom so position 0 is the newest message. */
internal const val CHAT_LAYOUT_MANAGER = "Lcom/ss/android/ugc/aweme/im/ui/recyclerview/ChatLinearLayoutManager;"

/** What the list consumer logs when the ability's own interceptor throws. */
internal const val CHAT_SUBMIT_LOG = "MessageListSubmitListAbilityImpl [IMessageEventProtocol][interceptMessageEvent]"

/**
 * Where every change to a chat's message list is decided (#503). The ability's `subscribeToStreams`
 * consumer (`LX/0Tdj.accept` on 48.0.2) hands each event to the ability's own `LJIILL(event)`,
 * which asks a decider, `LX/0O8Q.LIZ(event, list, state, flag)`, whether the list moves to the
 * newest message. A yes queues a timestamp, and the list commit that follows sees it and calls
 * `scrollToPositionWithOffset(0, 0)` on the chat's layout manager. This is the one caller of that
 * decider, and its `p0` is the ability the hook reaches the list through. 47.9.3 had the body
 * inline in the consumer (`LX/0YgJ.accept`), reading the ability from a field.
 */
internal object ChatScrollDecisionFingerprint : Fingerprint(
    definingClass = SUBMIT_LIST_ABILITY,
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf(CHAT_SUBMIT_LOG),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && method.chatScrollDecision() != null },
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
    val layoutManager: ImmutableMethodReference,
    /** The decider's event parameter, which every event the extension names implements. */
    val eventType: String,
)

/** [ability] is the class that holds [method], the list ability itself. */
internal fun chatScrollHook(patch: String, method: Method, ability: ClassDef): ChatScrollHook {
    val decision = method.chatScrollDecision()
        ?: throw PatchException("$patch: ${method.definingClass}->${method.name} has no single scroll decider call to hook.")
    if (ability.type != SUBMIT_LIST_ABILITY || method.definingClass != SUBMIT_LIST_ABILITY) {
        throw PatchException("$patch: the scroll decision left $SUBMIT_LIST_ABILITY.")
    }
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
        ImmutableMethodReference(getter.definingClass, getter.name, emptyList(), getter.returnType),
        method.implementation!!.instructions.toList()[decision.answer - 1].getReference<MethodReference>()!!
            .parameterTypes[0].toString(),
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
            invoke-virtual { v${hook.self} }, ${hook.layoutManager}
            move-result-object v${hook.scratch}
            invoke-static { v$answer, v${hook.scratch}, v${hook.decision.eventRegister} }, $KEEP_CHAT_PLACE->scrollToNewest(ILjava/lang/Object;Ljava/lang/Object;)Z
            move-result v$answer
        """,
    )
}
