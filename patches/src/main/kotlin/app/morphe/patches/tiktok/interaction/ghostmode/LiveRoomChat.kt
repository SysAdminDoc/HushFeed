/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.live.LIVE_PLAY_FRAGMENT
import app.morphe.patches.tiktok.interaction.live.LIVE_ROOM_TYPE
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

// The chat of a LIVE watched unseen (#513). A room whose enter Ghost mode held starts its own
// chat, host bar and close button, reading the room's messages over TikTok's LIVE preview
// channel: the one the feed's LIVE previews read without entering a room. The room's own channel
// never enters a hidden room, and what a viewer sends from the room fails on the phone in the
// same network hook as the enter (LiveUnseen.holdEnter).

internal const val MESSAGE_MANAGER = "Lcom/ss/ugc/live/sdk/message/interfaces/IMessageManager;"
internal const val MESSAGE_SERVICE = "Lcom/bytedance/android/message/IMessageService;"
internal const val IM_ENTER_ROOM = "Lcom/bytedance/android/livesdkapi/message/ImEnterRoom;"

/** The names TikTok's message channel kinds keep: a room's own channel and the LIVE preview's. */
internal const val CHANNEL_ROOM = "LIVE_ROOM"
internal const val CHANNEL_PREVIEW = "PREVIEW"

/**
 * The LIVE SDK's builder of its one message channel, static, taking the context, the room id,
 * whether you host the room, your user id, two reuse flags, the channel's kind (an obfuscated
 * enum whose LIVE_ROOM and PREVIEW keep their names) and one more flag. Room pages, the feed's
 * LIVE previews and the pre-enter paths all get their channel here. Its release log key and its
 * provide log key meet only here on 47.9.3.
 */
internal object LiveMessageChannelFingerprint : Fingerprint(
    returnType = MESSAGE_MANAGER,
    parameters = listOf("Landroid/content/Context;", "J", "Z", "J", "Z", "Z", "L", "Z"),
    strings = listOf("ttlive_message_room_release_exception", "message_manager_provide"),
)

/**
 * The message channel's websocket enter, which writes an ImEnterRoom frame for a room's own
 * channel and a PreviewRoom frame for a preview, and returns without sending when the frame is
 * null. The method keeps its SDK interface's name.
 */
internal object LiveImEnterRoomFingerprint : Fingerprint(
    name = "sendImEnterRoomMsg",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J", "J"),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == IM_ENTER_ROOM
        } == true
    },
)

/**
 * The LIVE room page's start, which asks the message service for a channel left over from the
 * last room before it makes one for this room. Its log key for the room's start time is only
 * here on 47.9.3.
 */
internal object LiveRoomStartFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_FRAGMENT,
    returnType = "V",
    parameters = listOf(),
    strings = listOf("param_live_enter_room_start_time"),
)

/**
 * The LIVE room page's startInteraction, which builds the chat, the host bar and the close button
 * once the page's state reads PREPARED; a successful room enter sets that state and calls it.
 */
internal object LiveStartInteractionFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_FRAGMENT,
    returnType = "V",
    parameters = listOf(LIVE_ROOM_TYPE),
    strings = listOf("startInteraction -> roomId="),
)

/**
 * Checks the channel builder takes the room id as p1-p2, whether you host as p3 and the channel's
 * kind as p8, and that the kind is an enum with LIVE_ROOM and PREVIEW. Returns the kind's type.
 * Throws by name otherwise, so a moved builder fails Ghost mode with nothing written.
 */
internal fun planMessageChannel(method: Method, classOf: (String) -> ClassDef?): String {
    val types = method.parameterTypes.map(CharSequence::toString)
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || types.size != 8 ||
        types.subList(0, 6) != listOf("Landroid/content/Context;", "J", "Z", "J", "Z", "Z")
    ) {
        throw PatchException("Ghost mode: the LIVE message channel builder isn't the static one taking the room id first.")
    }
    val kind = types[6]
    val kindClass = classOf(kind)
        ?: throw PatchException("Ghost mode: the LIVE message channel's kind $kind isn't in the app.")
    if (kindClass.superclass != "Ljava/lang/Enum;" ||
        listOf(CHANNEL_ROOM, CHANNEL_PREVIEW).any { name -> kindClass.staticFields.none { it.name == name && it.type == kind } }
    ) {
        throw PatchException("Ghost mode: the LIVE message channel's kind has no $CHANNEL_ROOM and $CHANNEL_PREVIEW.")
    }
    val implementation = method.implementation
        ?: throw PatchException("Ghost mode: the LIVE message channel builder has no body.")
    if (implementation.registerCount - method.numberOfParameterRegisters < 4) {
        throw PatchException("Ghost mode: the LIVE message channel builder has no room for its hook.")
    }
    return kind
}

/**
 * Has the builder ask the extension which kind of channel to make, before it reads the kind: a
 * hidden room's own channel comes back as the preview kind. The four locals are free at the entry,
 * which every caller reaches and nothing jumps back to.
 */
internal fun MutableMethod.readHiddenRoomsAsPreview(kind: String) {
    addInstructions(
        0,
        """
            move-wide/from16 v0, p1
            move/from16 v2, p3
            move-object/from16 v3, p8
            invoke-static { v0, v1, v2, v3 }, $LIVE_UNSEEN_EXTENSION->messageMode(JILjava/lang/Object;)Ljava/lang/Object;
            move-result-object v3
            check-cast v3, $kind
            move-object/from16 p8, v3
        """,
    )
}

/** Where the websocket enter's hold goes, and the frame's and its bytes' registers there. */
internal class ImEnterHold(val index: Int, val frame: Int, val bytes: Int)

/**
 * Finds the room channel's ImEnterRoom frame in the websocket enter, the one encode of it to
 * bytes, and the register those bytes land in, and checks the method stops when they're null
 * before it sends anything. The hold goes right after the bytes are read. Throws by name when any
 * of that moved.
 */
internal fun planImEnterHold(method: Method): ImEnterHold {
    val code = method.implementation?.instructions?.toList()
        ?: throw PatchException("Ghost mode: the LIVE websocket enter has no body.")
    val made = code.indices.filter {
        code[it].opcode == Opcode.NEW_INSTANCE && code[it].getReference<TypeReference>()?.type == IM_ENTER_ROOM
    }
    val at = made.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one room enter frame in the LIVE websocket enter, found ${made.size}.")
    val frame = (code[at] as OneRegisterInstruction).registerA
    val encode = (at + 1 until code.size).firstOrNull { index ->
        val call = code[index]
        call.opcode == Opcode.INVOKE_STATIC && call.getReference<MethodReference>()?.returnType == "[B" &&
            (call as FiveRegisterInstruction).registerCount >= 1 && call.registerC == frame
    } ?: throw PatchException("Ghost mode: the LIVE websocket enter no longer encodes its room enter frame.")
    val read = code.getOrNull(encode + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
        ?: throw PatchException("Ghost mode: the LIVE websocket enter doesn't keep its encoded frame.")
    val bytes = read.registerA
    if (frame > 15 || bytes > 15) {
        throw PatchException("Ghost mode: the LIVE websocket enter's frame registers are out of a call's reach.")
    }
    val index = encode + 2
    if (index >= code.size || addressOf(code, index) in branchTargets(code)) {
        throw PatchException("Ghost mode: something jumps to where the LIVE websocket enter's hold goes.")
    }
    if (!checksForNull(code, index, bytes)) {
        throw PatchException("Ghost mode: the LIVE websocket enter no longer stops on a missing frame.")
    }
    return ImEnterHold(index, frame, bytes)
}

/** Hands the encoded room enter frame to the extension, which answers null for a hidden room. */
internal fun MutableMethod.holdImEnters(hold: ImEnterHold) {
    addInstructions(
        hold.index,
        """
            invoke-static { v${hold.frame}, v${hold.bytes} }, $LIVE_UNSEEN_EXTENSION->imEnter(Ljava/lang/Object;[B)[B
            move-result-object v${hold.bytes}
        """,
    )
}

/** Where the room start's channel check goes, and the channel's and the page's registers there. */
internal class ReuseCheck(val index: Int, val channel: Int, val page: Int)

/**
 * Finds the room start's one ask for a channel left over from the last room, the register its
 * answer lands in, and the page's register: the one the page's next read of its own field uses,
 * with nothing writing it in between. The check goes right after the answer is read. Throws by
 * name when any of that moved.
 */
internal fun planReuseCheck(method: Method): ReuseCheck {
    val code = method.implementation?.instructions?.toList()
        ?: throw PatchException("Ghost mode: the LIVE room start has no body.")
    val asks = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_INTERFACE && code[index].getReference<MethodReference>()?.let {
            it.definingClass == MESSAGE_SERVICE && it.name == "getReuse" && it.parameterTypes.isEmpty()
        } == true
    }
    val at = asks.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one ask for a reused LIVE channel in the room start, found ${asks.size}.")
    val read = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
        ?: throw PatchException("Ghost mode: the LIVE room start doesn't keep the reused channel.")
    val channel = read.registerA
    val pageRead = (at + 2 until minOf(code.size, at + 16)).firstOrNull { index ->
        code[index].opcode == Opcode.IGET_OBJECT && code[index].getReference<FieldReference>()?.definingClass == method.definingClass
    } ?: throw PatchException("Ghost mode: the LIVE room start no longer reads its page after the reused channel.")
    val page = (code[pageRead] as TwoRegisterInstruction).registerB
    for (index in at + 2 until pageRead) {
        val instruction = code[index]
        if (instruction.opcode in READS_ONLY) continue
        val written = (instruction as? OneRegisterInstruction)?.registerA ?: (instruction as? TwoRegisterInstruction)?.registerA
        if (written == page) throw PatchException("Ghost mode: the LIVE room start moves its page before reading it.")
    }
    if (channel > 15 || page > 15 || channel == page) {
        throw PatchException("Ghost mode: the LIVE room start's registers are out of a call's reach.")
    }
    val index = at + 2
    if (addressOf(code, index) in branchTargets(code)) {
        throw PatchException("Ghost mode: something jumps to where the LIVE room start's check goes.")
    }
    return ReuseCheck(index, channel, page)
}

/** Has a hidden room make its own channel instead of taking the last room's. */
internal fun MutableMethod.keepHiddenRoomsOffReusedChannels(check: ReuseCheck) {
    addInstructions(
        check.index,
        """
            invoke-static { v${check.channel}, v${check.page} }, $LIVE_UNSEEN_EXTENSION->reuseFor(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v${check.channel}
            check-cast v${check.channel}, $MESSAGE_MANAGER
        """,
    )
}

/** The page's state field, read off startInteraction, and startInteraction itself. */
internal class LiveStart(val state: FieldReference, val start: MethodReference)

/**
 * Reads the page's state field off startInteraction (the one page field it reads and compares with
 * PREPARED) and checks the state is an enum with PREPARING and PREPARED. The extension's bridge
 * sets the state and calls startInteraction from its own package, so each piece has to be public.
 * Throws by name otherwise.
 */
internal fun planLiveStart(start: Method, classOf: (String) -> ClassDef?): LiveStart {
    val page = start.definingClass
    val code = start.implementation?.instructions?.toList()
        ?: throw PatchException("Ghost mode: the LIVE room's startInteraction has no body.")
    val states = code.indices.mapNotNull { index ->
        val read = code[index].takeIf { it.opcode == Opcode.IGET_OBJECT }?.getReference<FieldReference>()
            ?: return@mapNotNull null
        val compared = code.getOrNull(index + 1)?.takeIf { it.opcode == Opcode.SGET_OBJECT }
            ?.getReference<FieldReference>() ?: return@mapNotNull null
        read.takeIf {
            it.definingClass == page && compared.name == "PREPARED" &&
                compared.definingClass == it.type && compared.type == it.type
        }
    }.distinctBy { it.toString() }
    val state = states.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one room state read in the LIVE room's startInteraction, found ${states.size}.")
    fun refused(what: String): Nothing =
        throw PatchException("Ghost mode: the LIVE room's $what isn't public, so a hidden room can't start its chat.")
    fun Int.public() = AccessFlags.PUBLIC.isSet(this)
    val stateClass = classOf(state.type)
        ?: throw PatchException("Ghost mode: the LIVE room state ${state.type} isn't in the app.")
    if (stateClass.superclass != "Ljava/lang/Enum;") {
        throw PatchException("Ghost mode: the LIVE room state ${state.type} isn't an enum.")
    }
    for (name in listOf("PREPARING", "PREPARED")) {
        val constant = stateClass.staticFields.firstOrNull { it.name == name && it.type == state.type }
            ?: throw PatchException("Ghost mode: the LIVE room state has no $name.")
        if (!constant.accessFlags.public()) refused("state $name")
    }
    if (!stateClass.accessFlags.public()) refused("state")
    val pageClass = classOf(page) ?: throw PatchException("Ghost mode: the LIVE room page $page isn't in the app.")
    if (!pageClass.accessFlags.public()) refused("page")
    if (pageClass.fields.firstOrNull { it.name == state.name && it.type == state.type }?.accessFlags?.public() != true) {
        refused("state field")
    }
    if (!start.accessFlags.public() || AccessFlags.STATIC.isSet(start.accessFlags)) refused("startInteraction")
    return LiveStart(state, ImmutableMethodReference(page, start.name, start.parameterTypes, start.returnType))
}

/**
 * Fills the extension's startInteraction(Object, Object) stub: on a page still PREPARING, which
 * is where a held enter leaves it, the state moves on to PREPARED, as a successful enter moves it,
 * and the page's startInteraction builds the room's chat, host bar and close button. Any other
 * state answers false and changes nothing. Needs two locals.
 */
internal fun MutableMethod.startLiveInteraction(plan: LiveStart) {
    if (returnType != "Z" || parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;", "Ljava/lang/Object;")) {
        throw PatchException("Ghost mode: the extension's LIVE start bridge isn't (Object, Object) to boolean.")
    }
    if ((implementation?.registerCount ?: 0) - numberOfParameterRegisters < 2) {
        throw PatchException("Ghost mode: the extension's LIVE start bridge has no room for its locals.")
    }
    val state = plan.state.type
    addInstructions(
        0,
        """
            check-cast p0, ${plan.start.definingClass}
            check-cast p1, $LIVE_ROOM_TYPE
            iget-object v0, p0, ${plan.state}
            sget-object v1, $state->PREPARING:$state
            if-ne v0, v1, :not_starting
            sget-object v1, $state->PREPARED:$state
            iput-object v1, p0, ${plan.state}
            invoke-virtual { p0, p1 }, ${plan.start}
            const/4 v0, 0x1
            return v0
            :not_starting
            const/4 v0, 0x0
            return v0
        """,
    )
}

/** Opcodes that only read their first register. */
private val READS_ONLY = setOf(
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IPUT, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_WIDE,
    Opcode.SPUT, Opcode.SPUT_OBJECT, Opcode.SPUT_BOOLEAN, Opcode.SPUT_WIDE,
    Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT, Opcode.THROW,
)

private fun addressOf(code: List<Instruction>, index: Int) = code.take(index).sumOf { it.codeUnits }

/** Every address a branch or goto in [code] lands on. Switches are judged by their payloads elsewhere. */
private fun branchTargets(code: List<Instruction>): Set<Int> {
    val targets = mutableSetOf<Int>()
    var address = 0
    for (instruction in code) {
        if (instruction is OffsetInstruction && instruction.opcode != Opcode.PACKED_SWITCH &&
            instruction.opcode != Opcode.SPARSE_SWITCH && instruction.opcode != Opcode.FILL_ARRAY_DATA
        ) {
            targets += address + instruction.codeOffset
        }
        address += instruction.codeUnits
    }
    return targets
}

/**
 * Whether the code from [index] tests [register] against null before anything else, following a
 * goto or two to the test.
 */
private fun checksForNull(code: List<Instruction>, index: Int, register: Int): Boolean {
    var at = index
    repeat(3) {
        val instruction = code.getOrNull(at) ?: return false
        when (instruction.opcode) {
            Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> {
                val target = addressOf(code, at) + (instruction as OffsetInstruction).codeOffset
                var address = 0
                at = code.indices.firstOrNull { i -> (address == target).also { address += code[i].codeUnits } }
                    ?: return false
            }
            Opcode.IF_EQZ, Opcode.IF_NEZ -> return (instruction as OneRegisterInstruction).registerA == register
            else -> return false
        }
    }
    return false
}
