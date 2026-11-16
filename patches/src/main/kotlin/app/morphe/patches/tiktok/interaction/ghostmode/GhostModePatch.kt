/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows eduardo3677-ai/tiktok-patches-for-morphe.
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.fieldAccess
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.live.LIVE_PLAY_FRAGMENT
import app.morphe.patches.tiktok.interaction.live.LivePlayFragmentResumeFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.util.findMutableMethodOf
import app.morphe.util.singleOrPatchException
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// The classes each reporter lives in, a suffix like /Name;, shared by its fingerprint and the
// narrowed search below.
internal const val STORY_API = "/StoryApi;"
internal const val PROFILE_VIEWER_API = "/ProfileViewerApiService;"
internal const val TYPING_STATUS_SENDER = "/TypingStatusSenderTimer;"

/**
 * Reports that a story was seen, opened or interacted with.
 *
 * <p>definingClass repeats what the custom block checks so the search reads only the classes it
 * names (matchAllInDefiningClasses); without it every method of TikTok was tried (#54).
 */
internal object StoryViewReportFingerprint : Fingerprint(
    definingClass = STORY_API,
    custom = { method, classDef ->
        classDef.endsWith(STORY_API) &&
            method.name in setOf("reportStoryViewed", "reportUserInteraction", "reportStoryReveal")
    },
)

/** Records a profile visit against the profile's viewer list. */
internal object ProfileViewReportFingerprint : Fingerprint(
    definingClass = PROFILE_VIEWER_API,
    custom = { method, classDef ->
        classDef.endsWith(PROFILE_VIEWER_API) && method.name == "reportView"
    },
)

/** Pushes the "typing…" indicator into a conversation. */
internal object TypingStatusSenderFingerprint : Fingerprint(
    definingClass = TYPING_STATUS_SENDER,
    custom = { method, classDef ->
        classDef.endsWith(TYPING_STATUS_SENDER) &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Ljava/lang/String;" &&
            method.returnType == "V"
    },
)

/**
 * TikTok's play report sender (/aweme/v1/aweme/stats/). Every story view sends one with the
 * story's aid, play_delta=1 and story_consumption_type, beside the StoryApi report above, so a
 * viewer list still filled with only that one blocked (#39). R8 names it on every build (47.0.3
 * LX/09gS.LIZIZ, 47.1.3 LX/09b8.LIZIZ); its monitor string and its shape find it.
 */
internal object AwemeStatsSenderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;", "I", "Ljava/lang/String;", "I",
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;", "Lkotlin/jvm/functions/Function1;",
    ),
    strings = listOf("aweme_stats_monitor"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal const val STORY_INBOX_ITEM = "Lcom/ss/android/ugc/aweme/story/inbox/StoryInboxItem;"
internal const val STORY_READS_EXTENSION = "Lapp/morphe/extension/tiktok/ghostmode/StoryReads;"

/**
 * An item of the inbox's story row. Its constructor asks TikTok's story status store how the
 * person's ring should look and keeps the answer in storyStatusForInbox (#427). Real-named class
 * with one constructor.
 */
internal object StoryInboxItemFingerprint : Fingerprint(
    definingClass = STORY_INBOX_ITEM,
    name = "<init>",
    returnType = "V",
    filters = listOf(fieldAccess(opcode = Opcode.IPUT, definingClass = STORY_INBOX_ITEM, name = "storyStatusForInbox", type = "I")),
)

/**
 * Hands the status the constructor just stored to [STORY_READS_EXTENSION], which marks the
 * stories Ghost mode kept unreported as watched, and stores its answer instead. It goes right
 * after TikTok's own store and uses that store's two registers, which a 4-bit iput already keeps
 * under v16.
 */
internal fun MutableMethod.keepWatchedStoriesWatched() {
    val code = implementation?.instructions?.toList()
        ?: throw PatchException("Ghost mode: the inbox story item has no constructor body.")
    val store = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IPUT &&
            ((instruction as ReferenceInstruction).reference as? FieldReference)?.let {
                it.definingClass == STORY_INBOX_ITEM && it.name == "storyStatusForInbox"
            } == true
    }
    if (store < 0) throw PatchException("Ghost mode: the inbox story item no longer stores its status.")
    if (isBranchTarget(store + 1)) {
        throw PatchException("Ghost mode: something branches past the inbox story item's status.")
    }
    val put = code[store] as TwoRegisterInstruction
    val status = put.registerA
    val item = put.registerB
    addInstructions(
        store + 1,
        """
            invoke-static {v$item, v$status}, $STORY_READS_EXTENSION->inboxStatus(Ljava/lang/Object;I)I
            move-result v$status
            iput v$status, v$item, $STORY_INBOX_ITEM->storyStatusForInbox:I
        """,
    )
}

internal const val CALL_SERVER_INTERCEPTOR = "Lcom/bytedance/retrofit2/CallServerInterceptor;"
/** TikTok's lancet around every request its network layer sends, the static form with the interceptor first. */
internal const val NETWORK_EXECUTE_CALL =
    "com_bytedance_retrofit2_CallServerInterceptor_com_ss_android_ugc_aweme_feed_lancet_NetworkUtilsLancet_executeCall"
/** The interceptor's own send, which the lancet calls inside its try. */
internal const val NETWORK_EXECUTE_CALL_TWIN = "com_bytedance_retrofit2_CallServerInterceptor__executeCall\$___twin___"
internal const val LIVE_UNSEEN_EXTENSION = "Lapp/morphe/extension/tiktok/ghostmode/LiveUnseen;"
internal const val LIVE_UNSEEN_BUTTON_EXTENSION = "Lapp/morphe/extension/tiktok/ghostmode/LiveUnseenButton;"

/**
 * The LIVE room page's leave (#420), which takes the reason (an obfuscated enum: ENTER_FAILED,
 * USER_CLOSE_CLICK, PLAYER_ERROR and the rest keep their names), a message and a code. A failed
 * room enter reaches it as ENTER_FAILED, and it then shows the "Unstable connection" toast and
 * slides on to the next LIVE. Its log lines for a paid room and the jump to another room are
 * only in this method on 47.7.3.
 */
internal object LiveRoomLeaveFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_FRAGMENT,
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/String;", "I"),
    strings = listOf("paid room fail to enter the room", "jump to other room"),
)

/**
 * The LIVE room page's room enter (#420), which takes the room id and whether a tap opened it.
 * It makes the enter's callback from the page and the room id, keeps it on the page, and hands
 * it to the page's send. That callback's success builds the room's chat, host bar and close
 * button. Its log keys for the enter's start time and the pre-enter cache meet only here on 47.7.3.
 */
internal object LiveRoomEnterFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_FRAGMENT,
    returnType = "V",
    parameters = listOf("J", "Z"),
    strings = listOf("param_live_enter_room_api_start_time", "pre_enter_cache"),
)

/** The room page's reset of an enter still pending, which cancels it and logs the room. */
internal object LiveRoomEnterResetFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_FRAGMENT,
    returnType = "V",
    parameters = listOf(),
    strings = listOf("resetEnterRoom -> roomId="),
)

/** What the eye's second enter is made of, all read off the page's own room enter (#420). */
internal class LiveReEnter(
    /** The page's reset of a pending enter, so its send doesn't stop at the held one. */
    val reset: MethodReference,
    /** The enter callback's constructor, which takes the page and the room id. */
    val callback: MethodReference,
    /** The page field the room enter keeps that callback in. */
    val kept: FieldReference,
    /** The page's send, which takes the room id and the callback. */
    val send: MethodReference,
)

/**
 * Reads the eye's second enter off the page's room enter: the one callback it makes from the page
 * and the room id, the one page field it keeps that callback in, and the one page call taking the
 * room id and an interface the callback implements. The extension calls each piece from its own
 * package, so every one has to be public. Throws by name when any is gone, doubled or hidden, so
 * a moved room enter fails Ghost mode with nothing written.
 */
internal fun planLiveReEnter(enter: Method, reset: Method, classOf: (String) -> ClassDef?): LiveReEnter {
    val page = enter.definingClass
    val code = enter.implementation?.instructions?.toList()
        ?: throw PatchException("Ghost mode: the LIVE room enter has no body.")
    fun <T> List<T>.one(what: String): T = distinctBy { it.toString() }.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one $what in the LIVE room enter, found ${distinctBy { it.toString() }.size}.")
    fun refused(what: String): Nothing =
        throw PatchException("Ghost mode: the LIVE room's $what isn't public, so the eye can't enter the room again.")

    val callback = code.filter { it.opcode == Opcode.INVOKE_DIRECT }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(page, "J") }
        .one("enter callback made from the page and the room id")
    val kept = code.filter { it.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == page && it.type == callback.definingClass }
        .one("page field keeping the enter callback")
    val callbackClass = classOf(callback.definingClass)
        ?: throw PatchException("Ghost mode: the LIVE enter callback ${callback.definingClass} isn't in the app.")
    val send = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter {
            it.definingClass == page && it.returnType == "V" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0].toString() == "J" && it.parameterTypes[1].toString() in callbackClass.interfaces
        }
        .one("send taking the room id and the enter callback")

    val pageClass = classOf(page) ?: throw PatchException("Ghost mode: the LIVE room page $page isn't in the app.")
    fun Int.public() = AccessFlags.PUBLIC.isSet(this)
    fun ClassDef.method(reference: MethodReference) = methods.firstOrNull {
        it.name == reference.name && it.returnType == reference.returnType &&
            it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
    }
    if (!pageClass.accessFlags.public()) refused("page")
    if (!callbackClass.accessFlags.public() || callbackClass.method(callback)?.accessFlags?.public() != true) {
        refused("enter callback")
    }
    if (pageClass.fields.firstOrNull { it.name == kept.name && it.type == kept.type }?.accessFlags?.public() != true) {
        refused("enter callback field")
    }
    if (pageClass.method(send)?.accessFlags?.public() != true) refused("send")
    if (reset.definingClass != page || !reset.accessFlags.public() || AccessFlags.STATIC.isSet(reset.accessFlags)) {
        refused("enter reset")
    }
    return LiveReEnter(reset, callback, kept, send)
}

/**
 * Fills the extension's enterAgain(Object, long) stub with the page's own room enter: the pending
 * enter reset, a new callback kept where the room enter keeps it, and the send. Answers true once
 * the enter is on its way; the callback's success then builds the rest of the room.
 */
internal fun MutableMethod.enterLiveRoomAgain(plan: LiveReEnter) {
    if (returnType != "Z" || parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;", "J")) {
        throw PatchException("Ghost mode: the extension's LIVE re-enter bridge isn't (Object, long) to boolean.")
    }
    val page = plan.send.definingClass
    addInstructions(
        0,
        """
            check-cast p0, $page
            invoke-virtual { p0 }, ${plan.reset.definingClass}->${plan.reset.name}()V
            new-instance v0, ${plan.callback.definingClass}
            invoke-direct { v0, p0, p1, p2 }, ${plan.callback}
            iput-object v0, p0, ${plan.kept}
            invoke-virtual { p0, p1, p2, v0 }, ${plan.send}
            const/4 v0, 0x1
            return v0
        """,
    )
}

/** Where the LIVE enter hold goes: in front of the lancet's send, with the send's two registers. */
internal class LiveEnterHold(val index: Int, val interceptor: Int, val chain: Int)

/**
 * Finds the one call to the interceptor's own send in the lancet, and checks that a try covers
 * it whose handler takes an Exception, so the IOException the hold throws reaches TikTok's own
 * failed-request path rather than crashing the call (#420). Throws by name when the send is gone,
 * doubled, or outside such a try, so a moved lancet fails Ghost mode with nothing written.
 */
internal fun planLiveEnterHold(method: Method): LiveEnterHold {
    val implementation = method.implementation
        ?: throw PatchException("Ghost mode: TikTok's network call has no body.")
    val code = implementation.instructions.toList()
    val sends = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL &&
            code[index].getReference<MethodReference>()?.name == NETWORK_EXECUTE_CALL_TWIN
    }
    val index = sends.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one network send to hold LIVE enters at, found ${sends.size}.")
    val send = code[index] as FiveRegisterInstruction
    if (send.registerCount != 2) {
        throw PatchException("Ghost mode: the network send takes ${send.registerCount} registers, not the interceptor and its chain.")
    }
    var address = 0
    for (i in 0 until index) address += code[i].codeUnits
    val covered = implementation.tryBlocks.any { block ->
        address >= block.startCodeAddress && address < block.startCodeAddress + block.codeUnitCount &&
            block.exceptionHandlers.any {
                it.exceptionType in setOf(null, "Ljava/lang/Exception;", "Ljava/io/IOException;", "Ljava/lang/Throwable;")
            }
    }
    if (!covered) {
        throw PatchException("Ghost mode: the network send is no longer inside a try that takes a failed request.")
    }
    return LiveEnterHold(index, send.registerC, send.registerD)
}

/**
 * Writes the hold. It takes the send's labels and its place at the start of the try, so it runs
 * on every path that reaches the send, and what it throws goes to the same handler.
 */
internal fun MutableMethod.holdLiveEnters(hold: LiveEnterHold) {
    addInstructionsAtControlFlowLabel(
        hold.index,
        "invoke-static {v${hold.interceptor}, v${hold.chain}}, " +
            "$LIVE_UNSEEN_EXTENSION->holdEnter(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
}

/**
 * The two coroutine bodies that send TikTok's activity status report (/tiktok/v1/activity_status/
 * report, the call that lights the green dot and "Active now" for your friends). The regular
 * poll runs in doReport$1, and the reportAdditional and cancelPolling paths go through
 * doReport$2. Each is the only caller of the report service in its dex. R8 renames every class,
 * but the trace tag each body opens with names the original class and lambda, and it is the
 * same on 47.0.3, 47.1.3 and 47.1.4.
 */
internal const val ONLINE_STATUS_REGULAR_TAG = "ActivityStatusReporter@261e.doReport\$1"
internal const val ONLINE_STATUS_IRREGULAR_TAG = "ActivityStatusReporter@261e.doReport\$2"

internal object OnlineStatusRegularReportFingerprint : Fingerprint(
    name = "invokeSuspend",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(ONLINE_STATUS_REGULAR_TAG),
)

internal object OnlineStatusIrregularReportFingerprint : Fingerprint(
    name = "invokeSuspend",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(ONLINE_STATUS_IRREGULAR_TAG),
)

/**
 * Returns from a reporter before it sends anything, when the extension says to.
 *
 * <p>Only a reporter that returns nothing can take this. One that hands something back is
 * handing back a lazy `Call`, `Observable` or `Single` that its caller goes on to `enqueue` or
 * `subscribe`, and the only value this could put there is a null. That null was the crash on
 * opening a story with Ghost mode on, and the reason other people's profiles showed no
 * follower counts. Those reporters are suppressed where they are called instead, by stepping
 * over the whole send: see [skipReportsAtEveryCallSite].
 *
 * @return true when the guard was injected; false for an abstract method, one with no local
 *         register to hold the answer, or one that returns anything at all.
 */
internal fun MutableMethod.returnBeforeReporting(guard: String): Boolean {
    if (returnType != "V") return false
    val implementation = implementation ?: return false
    if (implementation.registerCount - numberOfParameterRegisters < 1) return false

    guardAtEntry(
        "Ghost mode",
        "invoke-static {}, $GHOST_MODE_EXTENSION->$guard()Z",
        "return-void",
    )
    return true
}

/**
 * Stops the reports that tell other people what you looked at: story views, profile
 * views and the typing indicator. It suppresses the client's own
 * reporting only; nothing here changes what the server already knows.
 */
@Suppress("unused")
val ghostModePatch = bytecodePatch(
    name = "Ghost mode",
    description = "Stops TikTok telling people you viewed their story or profile, or that " +
        "you're typing. What TikTok already recorded stays. Each of the three has its own switch " +
        "under it, and separate ones hide your online status and let you watch LIVEs unseen. " +
        "Starts off. Turn it on in Hushfeed settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Worked out before the first write, so a build that moved TikTok's network lancet
        // fails Ghost mode by name with nothing written.
        val lancets = classDefByOrNull(CALL_SERVER_INTERCEPTOR)?.methods
            ?.filter { it.name == NETWORK_EXECUTE_CALL && it.implementation != null }.orEmpty()
        val lancet = lancets.singleOrNull()
            ?.let { mutableClassDefBy(it.definingClass).findMutableMethodOf(it) }
            ?: throw PatchException("Ghost mode: expected one network call to hold LIVE enters in, found ${lancets.size}.")
        val liveEnterHold = planLiveEnterHold(lancet)
        val roomResume = LivePlayFragmentResumeFingerprint.method
        val roomLeave = LiveRoomLeaveFingerprint.method
        val roomEnters = LiveRoomEnterFingerprint.matchAllOrNull().orEmpty().map { it.method }
        val roomResets = LiveRoomEnterResetFingerprint.matchAllOrNull().orEmpty().map { it.method }
        if (roomEnters.size != 1 || roomResets.size != 1) {
            throw PatchException(
                "Ghost mode: expected one LIVE room enter and one enter reset, found ${roomEnters.size} and ${roomResets.size}.",
            )
        }
        val reEnter = planLiveReEnter(roomEnters.single(), roomResets.single()) { classDefByOrNull(it) }
        val unseen = mutableClassDefBy(LIVE_UNSEEN_EXTENSION)
        val reEnterStub = unseen.methods.filter { it.name == "enterAgain" }
            .singleOrPatchException("Ghost mode: extension bridge enterAgain")

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableGhostMode()V",
        )
        // So the export carries the ghost mode family even on a run where no reporter is
        // reached. Without it a family that is simply absent says both "this build has no
        // ghost mode" and "nothing called it", and an export taken while a profile showed no
        // follower counts could not tell those apart.
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $GHOST_MODE_EXTENSION->installed()V",
        )

        listOf(
            StoryViewReportFingerprint to "shouldBlockStoryView",
            ProfileViewReportFingerprint to "shouldBlockProfileView",
            TypingStatusSenderFingerprint to "shouldBlockTypingStatus",
        ).forEach { (fingerprint, guard) ->
            // Retrofit declarations have no body. Every concrete reporting method is mandatory.
            val matches = fingerprint.matchAll().filter { it.originalMethod.implementation != null }
            val reporters = matches.map { it.method }
            if (reporters.isEmpty()) {
                throw PatchException("Ghost mode: no concrete reporter for $guard.")
            }
            val (silent, lazy) = reporters.partition { it.returnType == "V" }
            if (silent.any { !it.returnBeforeReporting(guard) }) {
                throw PatchException("Ghost mode: could not install every $guard hook.")
            }
            if (lazy.isNotEmpty()) skipReportsAtEveryCallSite(lazy, guard)
        }

        // The activity status report. Each body is a launched coroutine whose result nobody
        // reads, so a null return ends it before it sends; the guard answers false unless
        // Ghost mode and Hide online status are both on, and the answer is read on every
        // report, so the switch needs no restart.
        listOf(OnlineStatusRegularReportFingerprint, OnlineStatusIrregularReportFingerprint).forEach { fingerprint ->
            val senders = fingerprint.matchAllOrNull().orEmpty().map { it.method }
            if (senders.size != 1) {
                throw PatchException("Ghost mode: expected one activity status sender, found ${senders.size}.")
            }
            senders.single().guardAtEntry(
                "Ghost mode",
                "invoke-static {}, $GHOST_MODE_EXTENSION->shouldBlockOnlineStatus()Z",
                "const/4 v0, 0x0\nreturn-object v0",
            )
        }

        // The play report a story view also sends. The guard asks about the Aweme (p4) and lets
        // every feed video's report through; it returns before the report is built, so the
        // callback that stamps the story's consumption type never runs either.
        val statsSenders = AwemeStatsSenderFingerprint.matchAllOrNull().orEmpty().map { it.method }
        if (statsSenders.size != 1) {
            throw PatchException("Ghost mode: expected one play report sender, found ${statsSenders.size}.")
        }
        statsSenders.single().guardAtEntry(
            "Ghost mode",
            "invoke-static/range {p4 .. p4}, " +
                "$GHOST_MODE_EXTENSION->shouldBlockStoryStats(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z",
            "return-void",
        )

        // A story whose report is held back above comes back unwatched from the server on the
        // next launch. The inbox's story row keeps it watched (#427).
        StoryInboxItemFingerprint.method.keepWatchedStoriesWatched()

        // Watch LIVEs unseen (#420): a room enter, the request that puts you on the host's
        // viewer list, fails on the phone while it's held. Other patches hook this lancet too
        // and each finds its own anchors by name when it runs.
        lancet.holdLiveEnters(liveEnterHold)
        // TikTok takes the held enter as a failed one and leaves the room for the next LIVE.
        // The page stays when the failed enter was one Ghost mode held for its room.
        roomLeave.guardAtEntry(
            "Ghost mode",
            "invoke-static/range {p0 .. p1}, " +
                "$LIVE_UNSEEN_EXTENSION->keepRoom(Ljava/lang/Object;Ljava/lang/Object;)Z",
            "return-void",
        )
        // The eye has the page enter the room again through its own enter, whose success builds
        // the chat, host bar and close button the held enter never brought.
        val reEnterBridge = reEnterStub.cloneMutable(additionalRegisters = 1)
        unseen.methods.remove(reEnterStub)
        unseen.methods.add(reEnterBridge)
        reEnterBridge.enterLiveRoomAgain(reEnter)
        // The room's eye button, which lets the host of the room you're in see you.
        roomResume.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $LIVE_UNSEEN_BUTTON_EXTENSION->noteRoomFragment(Ljava/lang/Object;)V",
        )
    }
}
