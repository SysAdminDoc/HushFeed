/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.looping

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val DETAIL = "Lapp/morphe/extension/tiktok/interaction/DetailAutoAdvance;"
internal const val SCOPED_REGISTRAR =
    "Lcom/ss/android/ugc/aweme/backgroundplay/scoped/feed/ScopedBackgroundPlayPanelComponentRegistrar;"
internal const val SCOPED_COMPONENT =
    "Lcom/ss/android/ugc/aweme/backgroundplay/scoped/feed/ScopedBackgroundPlayPanelComponent;"
internal const val LIST_PANEL = "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;"
internal const val BOTTOM_COLLECTION_BAR =
    "Lcom/ss/android/ugc/aweme/comment/detailpage/bottombarv2/assem/BottomCollectionBarAssem;"
private const val SCENE = "Lcom/ss/android/ugc/aweme/backgroundplay/ScopedBgPlayScene;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val STRING = "Ljava/lang/String;"
internal const val OWNER_ON_SELECT = "StrategyScene_ON_PAGE_SELECTED"
internal const val OWNER_ON_PLAY = "isAppBackground"
internal const val FOREGROUND_NEXT_SKIPPED = "foreground_auto_next_skipped"
internal const val QUEUE_MODE_KEY = "background_play_collection_enable"
internal const val QUEUE_END = "play_completed"
internal const val QUEUE_END_STATE = "hasNext="
internal const val QUEUE_SESSION_OFF = "session_disabled"
internal const val QUEUE_ARMED = "result=armed, scene="
internal const val QUEUE_SESSION_STATE = "Lcom/ss/android/ugc/aweme/backgroundplay/ScopedBgPlaySessionState;"
internal const val BACKGROUND_SCOPE = "hushfeedBackgroundScope"
internal const val FOREGROUND_SCOPE = "hushfeedForegroundScope"
private const val PROFILE_UID = "getFromProfileUid"
internal const val PAGE_UID = "getUid"
private val SCOPE_READS = setOf("getVideoCollectionId", PROFILE_UID, "isOwnCollection")

/** Where the player records which page owns the video it selects (09j5.LJ on 47.9.1). */
internal object QueueOwnerOnSelectFingerprint : Fingerprint(strings = listOf(OWNER_ON_SELECT))

/** Where a play request checks which page asked for it (05pf.LIZ on 47.9.1). */
internal object QueueOwnerOnPlayFingerprint : Fingerprint(strings = listOf(OWNER_ON_PLAY))

/** TikTok's own move to the next video on screen, on a page with a background queue. */
internal object QueueForegroundNextFingerprint : Fingerprint(strings = listOf(FOREGROUND_NEXT_SKIPPED))

/** The queue's end of a video off screen, where it decides whether to move on. */
internal object QueueEndFingerprint : Fingerprint(strings = listOf(QUEUE_END, QUEUE_END_STATE))

/** The lazy read of the queue's mode: 0 off, 1 your collections in turn, 2 the same with a bar. */
internal object QueueModeFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf(QUEUE_MODE_KEY),
)

/**
 * TikTok's trigger as the app leaves the screen (BackgroundPlayServiceImpl.LJII on 47.9.1). On a
 * page with a queue key it arms that queue and skips the one-video session, but only once the
 * queue's session was turned on, which TikTok's own switch does for your collections alone.
 */
internal object QueueTriggerFingerprint : Fingerprint(strings = listOf(QUEUE_SESSION_OFF, QUEUE_ARMED))

/** Where the trigger asks whether the page's queue session is on, and the register holding that session. */
internal class QueueSessionCheck(val at: Int, val session: Int, val sessionType: String)

internal fun Method.queueSessionCheck(): QueueSessionCheck? {
    val code = implementation?.instructions?.toList() ?: return null
    val off = code.indexOfFirst {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            it.getReference<StringReference>()?.string == QUEUE_SESSION_OFF
    }
    if (off < 0) return null
    // The last yes-or-no question before the skip is the session's own "am I on".
    val check = code.take(off).indexOfLast { call ->
        call.opcode == Opcode.INVOKE_VIRTUAL && call.getReference<MethodReference>()?.let {
            it.returnType == "Z" && it.parameterTypes.isEmpty()
        } == true
    }
    if (check < 0) return null
    val session = (code[check] as FiveRegisterInstruction).registerC
    if (session > 15) return null
    return QueueSessionCheck(check, session, code[check].getReference<MethodReference>()!!.definingClass)
}

internal fun queueArmHook(check: QueueSessionCheck) =
    "invoke-static {v${check.session}}, $DETAIL->armProfileQueue(Ljava/lang/Object;)V"

private fun Instruction.isStaticCall() = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE

/**
 * TikTok's key for a page's background queue (0ACn.LJJI on 47.9.1): static, the page's
 * parameters in, and a String out made from the page's collection, profile and own-collection
 * reads. It answers only for your own collections.
 */
internal fun Method.isQueueScope(): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == STRING && parameterTypes.size == 1 &&
        (implementation?.instructions ?: emptyList())
            .mapNotNull { it.getReference<MethodReference>()?.name }.toSet().containsAll(SCOPE_READS)

/** The key, as the registrar reads it before it lets the queue's component onto a page. */
internal fun queueScope(registrar: ClassDef, classOf: (String) -> ClassDef?): Method? =
    registrar.methods.asSequence()
        .flatMap { (it.implementation?.instructions ?: emptyList()).asSequence() }
        .filter { it.isStaticCall() }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter { it.returnType == STRING && it.parameterTypes.size == 1 }
        .mapNotNull { call -> classOf(call.definingClass)?.methods?.firstOrNull { it.toString() == call.toString() } }
        .firstOrNull { it.isQueueScope() }

/** Where a method calls the key. */
internal fun Method.queueScopeCalls(scope: MethodReference): List<Int> =
    (implementation?.instructions ?: emptyList()).withIndex()
        .filter { (_, call) -> call.isStaticCall() && call.getReference<MethodReference>()?.toString() == scope.toString() }
        .map { it.index }

/**
 * Whether a method hands the key on to TikTok's owner token, the page a video is recorded
 * against. The queue looks the playing video up by that token, so it has to be made from the
 * same key the queue was opened with.
 */
internal fun Method.handsScopeToOwnerToken(scope: MethodReference): Boolean =
    queueScopeCalls(scope).isNotEmpty() && (implementation?.instructions ?: emptyList()).any { call ->
        call.isStaticCall() && call.getReference<MethodReference>()?.let {
            it.definingClass == scope.definingClass && it.returnType == STRING &&
                it.parameterTypes.map(CharSequence::toString) == listOf(FRAGMENT, STRING, STRING)
        } == true
    }

private fun scopeWrapper(scope: Method, name: String, registers: Int) = MutableMethod(
    ImmutableMethod(
        scope.definingClass,
        name,
        listOf(ImmutableMethodParameter(scope.parameterTypes.single().toString(), null, null)),
        STRING,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        ImmutableMethodImplementation(registers, emptyList(), null, null),
    ),
)

/** The page parameters' read of the page's user, which a profile grid's page fills. */
internal fun Method.isPageUidRead(): Boolean =
    name == PAGE_UID && returnType == STRING && parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(accessFlags)

/** The key for the places that start the queue: TikTok's, or a profile page's own when it has none. */
internal fun backgroundScopeWrapper(scope: Method): MutableMethod {
    val uid = (scope.implementation?.instructions ?: emptyList()).firstOrNull {
        it.getReference<MethodReference>()?.name == PROFILE_UID
    } ?: throw PatchException("Auto advance: the background queue key no longer reads $PROFILE_UID.")
    val invoke = if (uid.opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"
    val profileUid = uid.getReference<MethodReference>()!!
    // A page opened from a profile's grid leaves the profile uid empty and names the profile as
    // its user, the "userid" extra (#292 on the S22: every key read there had no profile uid).
    val pageUid = "${profileUid.definingClass}->$PAGE_UID()$STRING"
    return scopeWrapper(scope, BACKGROUND_SCOPE, 4).apply {
        addInstructions(0, """
            invoke-static {p0}, $scope
            move-result-object v0
            const/4 v1, 0x0
            const/4 v2, 0x0
            if-eqz p0, :read
            $invoke {p0}, $profileUid
            move-result-object v1
            $invoke {p0}, $pageUid
            move-result-object v2
            :read
            invoke-static {v0, v1, v2}, $DETAIL->backgroundScope(${STRING}${STRING}${STRING})$STRING
            move-result-object v0
            return-object v0
        """)
    }
}

/** The key for TikTok's own move to the next video on screen, which Auto-advance makes instead. */
internal fun foregroundScopeWrapper(scope: Method): MutableMethod =
    scopeWrapper(scope, FOREGROUND_SCOPE, 2).apply {
        addInstructions(0, """
            invoke-static {p0}, $scope
            move-result-object v0
            invoke-static {v0}, $DETAIL->foregroundScope($STRING)$STRING
            move-result-object v0
            return-object v0
        """)
    }

/** Points each of a method's calls to the key at a wrapper of the same shape; how many it moved. */
internal fun MutableMethod.repointQueueScope(scope: MethodReference, wrapper: MethodReference): Int {
    val sites = queueScopeCalls(scope)
    for (index in sites) {
        val call = getInstruction(index)
        replaceInstruction(
            index,
            if (call is RegisterRangeInstruction) {
                "invoke-static/range {v${call.startRegister} .. v${call.startRegister}}, $wrapper"
            } else {
                "invoke-static {v${(call as FiveRegisterInstruction).registerC}}, $wrapper"
            },
        )
    }
    return sites.size
}

/**
 * Where the queue's end of a video has read whether a next one exists ([hasNext]), the session
 * that queue belongs to ([queue]) and the id of the video that ended ([id]), as registers.
 */
internal class QueueEndRead(val at: Int, val hasNext: Int, val queue: Int, val id: Int)

/** The last instruction before [end] that casts [register] to [type], with nothing writing it after. */
private fun List<Instruction>.castKept(register: Int, type: String, end: Int): Boolean {
    val cast = take(end).indexOfLast {
        it.opcode == Opcode.CHECK_CAST && (it as OneRegisterInstruction).registerA == register
    }
    if (cast < 0 || this[cast].getReference<TypeReference>()?.type != type) return false
    return subList(cast + 1, end).none {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
    }
}

internal fun Method.queueEndRead(): QueueEndRead? {
    val code = implementation?.instructions?.toList() ?: return null
    val read = code.withIndex().singleOrNull { it.value.opcode == Opcode.IGET_BOOLEAN } ?: return null
    val sceneRead = code.firstOrNull {
        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.type == SCENE
    } ?: return null
    val queue = (sceneRead as TwoRegisterInstruction).registerB
    val queueType = sceneRead.getReference<FieldReference>()!!.definingClass
    // The lambda casts its session and the id it was handed once at the top and keeps both.
    val id = implementation!!.registerCount - 1
    if (!code.castKept(queue, queueType, read.index) || !code.castKept(id, STRING, read.index)) return null
    val hasNext = (read.value as TwoRegisterInstruction).registerA
    if (listOf(hasNext, queue, id).any { it > 15 }) return null
    return QueueEndRead(read.index + 1, hasNext, queue, id)
}

internal fun queueEndHook(read: QueueEndRead) = """
    invoke-static {v${read.hasNext}, v${read.queue}, v${read.id}}, $DETAIL->backgroundNext(ZLjava/lang/Object;$STRING)Z
    move-result v${read.hasNext}
"""

/**
 * Where the queue's mode read hands its answer on to be boxed, and the hook that raises it. The
 * server's answer and TikTok's two early outs (kill switches for its newer collection features)
 * meet there, so the hook goes in on that label and a profile page gets the queue either way.
 */
internal fun Method.queueModeHook(): Pair<Int, String> {
    val code = implementation?.instructions?.toList()
        ?: throw PatchException("Auto advance: the background queue's mode read has no code.")
    val key = code.indexOfFirst {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            it.getReference<StringReference>()?.string == QUEUE_MODE_KEY
    }
    if (key < 0) throw PatchException("Auto advance: $QUEUE_MODE_KEY is gone from its read.")
    val box = code.withIndex().drop(key).firstOrNull { (_, call) ->
        call.opcode == Opcode.INVOKE_STATIC &&
            call.getReference<MethodReference>()?.toString() == "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"
    } ?: throw PatchException("Auto advance: the background queue's mode is no longer boxed after its read.")
    val register = (box.value as FiveRegisterInstruction).registerC
    if (register > 15) throw PatchException("Auto advance: the background queue's mode sits in v$register.")
    return box.index to """
        invoke-static {v$register}, $DETAIL->collectionMode(I)I
        move-result v$register
    """
}

/**
 * Plays a profile page's videos in turn with the screen off (#292). TikTok has two background
 * sessions: one that holds a single video, which rewinds and pauses at its end, and a queue that
 * moves through a page's videos. The queue is given only to your own collections, through the
 * key [isQueueScope] reads, and only while its mode is on. A page opened from a profile gets a key
 * of its own at the places that set the queue up, and the mode is raised, both only while
 * Auto-advance and Background play are on. The places that put up the collection's bottom bar
 * keep TikTok's answer, and TikTok's own move to the next video on screen is turned away while
 * Auto-advance makes that move. The queue's end of a video asks the extension too, so the limit
 * and the holds stop it the way reaching its last video does. TikTok arms a queue as the app
 * leaves the screen only when its session was turned on, which its switch does for your
 * collections alone, so the trigger's check turns a profile page's session on first.
 */
internal fun queueProfilePagesInBackground() {
    val registrar = mutableClassDefBy(SCOPED_REGISTRAR)
    val scope = queueScope(registrar) { classDefByOrNull(it) }
        ?: throw PatchException("Auto advance: $SCOPED_REGISTRAR no longer reads the background queue key.")
    val owner = mutableClassDefBy(scope.definingClass)
    val background = backgroundScopeWrapper(scope).also { owner.methods.add(it) }
    val params = scope.parameterTypes.single().toString()
    if (classDefByOrNull(params)?.methods?.any { it.isPageUidRead() } != true) {
        throw PatchException("Auto advance: the page parameters no longer have $PAGE_UID.")
    }
    val foreground = foregroundScopeWrapper(scope).also { owner.methods.add(it) }

    var backgroundSites = 0
    for (type in listOf(SCOPED_REGISTRAR, SCOPED_COMPONENT, LIST_PANEL)) {
        val moved = mutableClassDefBy(type).methods.sumOf { it.repointQueueScope(scope, background) }
        if (moved == 0) throw PatchException("Auto advance: $type no longer reads the background queue key.")
        backgroundSites += moved
    }
    for ((label, fingerprint) in listOf(OWNER_ON_SELECT to QueueOwnerOnSelectFingerprint, OWNER_ON_PLAY to QueueOwnerOnPlayFingerprint)) {
        val tokens = fingerprint.matchAll().filter { it.originalMethod.handsScopeToOwnerToken(scope) }
        if (tokens.isEmpty()) {
            throw PatchException("Auto advance: no method holding \"$label\" hands the queue key to the owner token.")
        }
        tokens.forEach {
            backgroundSites += mutableClassDefBy(it.originalClassDef.type).findMutableMethodOf(it.originalMethod)
                .repointQueueScope(scope, background)
        }
    }
    val foregroundSites = QueueForegroundNextFingerprint.method.repointQueueScope(scope, foreground)
    if (foregroundSites == 0) throw PatchException("Auto advance: TikTok's own next video no longer reads the queue key.")

    val end = QueueEndFingerprint.method
    val read = end.queueEndRead()
        ?: throw PatchException("Auto advance: the background queue's end of a video changed shape.")
    end.addInstructions(read.at, queueEndHook(read))

    val mode = QueueModeFingerprint.method
    val (at, hook) = mode.queueModeHook()
    mode.addInstructionsAtControlFlowLabel(at, hook)

    val trigger = QueueTriggerFingerprint.method
    val check = trigger.queueSessionCheck()
        ?: throw PatchException("Auto advance: TikTok's background trigger no longer asks its queue session before \"$QUEUE_SESSION_OFF\".")
    if (classDefByOrNull(check.sessionType)?.fields?.any { it.type == QUEUE_SESSION_STATE } != true) {
        throw PatchException("Auto advance: ${check.sessionType} no longer holds the queue session's state.")
    }
    trigger.addInstructionsAtControlFlowLabel(check.at, queueArmHook(check))
    println("[Automatic video advance] Profile pages use the background queue: $backgroundSites starting reads, " +
        "$foregroundSites on screen, the end of a video, the mode read and the trigger's session check.")
}
