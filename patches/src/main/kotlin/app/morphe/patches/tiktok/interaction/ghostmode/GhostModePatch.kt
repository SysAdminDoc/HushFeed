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
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
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
        // The room's eye button, which lets the host of the room you're in see you.
        roomResume.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $LIVE_UNSEEN_BUTTON_EXTENSION->noteRoomFragment(Ljava/lang/Object;)V",
        )
    }
}
