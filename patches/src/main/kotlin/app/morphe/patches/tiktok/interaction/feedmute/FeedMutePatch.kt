/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedmute

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.PlayerPlayFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.awemeParameterRegister
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.interaction.blockauthor.resolveNativeFocus
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.cloneMutable
import app.morphe.util.singleOrPatchException
import app.morphe.util.writeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Mute feed videos"
private const val FEED_MUTE = "Lapp/morphe/extension/tiktok/playback/FeedMute;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * TikTok's members the extension's bridges call, all renamed by each build and read here off
 * TikTok's own code:
 * - the feed engine's player field and the player type that declares `getSourceId()`, from the
 *   first thing the engine's play() reads off itself and casts;
 * - TTVideoEngine's public setIsMute forwarder, the `(Z)V` that calls the impl logging it;
 * - the player session's focus request, the `()V` beside its give-up that calls the manager's
 *   request;
 * - the session's give-up: its own `()V` through 47.4.3, and on 47.5.3, where R8 inlined it into
 *   the player's release(), a copy of that block the patch adds to the session as
 *   [SESSION_ABANDON] ([sessionAbandonBody] holds it, null when the session's own method serves).
 */
internal class FeedMuteMembers(
    val engine: String,
    val playerField: FieldReference,
    val player: String,
    val setMute: MethodReference,
    val session: String,
    val sessionAbandon: String,
    val sessionRequest: String,
    val sessionAbandonBody: String? = null,
)

/** The name of the give-up the patch adds to the player session when R8 inlined TikTok's own. */
internal const val SESSION_ABANDON = "hushfeedAbandonFocus"
private const val HANDLER_THREAD = "Landroid/os/HandlerThread;"
private const val LOOPER = "Landroid/os/Looper;"
private const val HANDLER = "Landroid/os/Handler;"
private const val MESSAGE = "Landroid/os/Message;"

private fun FieldReference.text() = "$definingClass->$name:$type"

/**
 * The session's give-up as SimAudioFocusManager's checkStart callback sees it, read off the one
 * method that builds the callback with a session in hand: the session's own `()V` through 47.4.3,
 * or the player's release() that R8 inlined it into on 47.5.3. The block reads the session's
 * on-switch (a static boolean) and its busy flag, then its manager, listener and Handler, makes
 * the manager's checkStart Handler if it has none, posts message 2 with a record of the four, and
 * clears the session's held flag and both volumes to -1f.
 */
internal class SessionAbandonBlock(
    val session: String,
    val direct: Boolean,
    val flag: FieldReference,
    val guard: FieldReference,
    val manager: FieldReference,
    val listener: FieldReference,
    val handler: FieldReference,
    val check: FieldReference,
    val looper: MethodReference,
    val record: MethodReference,
    val send: MethodReference,
    val held: FieldReference,
    val volumes: List<FieldReference>,
)

internal fun readSessionAbandon(method: Method): SessionAbandonBlock {
    fun fail(why: String): Nothing =
        throw PatchException("$PATCH: the session give-up in ${method.definingClass}->${method.name}: $why")
    val body = method.implementation ?: fail("no body")
    val code = body.instructions.toList()
    fun field(index: Int) = (code[index] as ReferenceInstruction).reference as FieldReference
    fun call(index: Int) = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
    fun writer(register: Int, before: Int) = (before - 1 downTo 0).firstOrNull { code[it].writeRegister == register }
    fun literal(register: Int, before: Int): Int? =
        writer(register, before)?.let { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral }

    val start = code.indices.filter {
        code[it].opcode == Opcode.NEW_INSTANCE &&
            ((code[it] as ReferenceInstruction).reference as TypeReference).type == SIM_AUDIO_CHECK_START
    }.singleOrNull() ?: fail("no single checkStart callback")
    val checkRead = (start - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.IGET_OBJECT && field(it).type == SIM_AUDIO_CHECK_START
    } ?: fail("the manager's checkStart field isn't read before the callback")
    val check = field(checkRead)
    val managerRead = writer((code[checkRead] as TwoRegisterInstruction).registerB, checkRead)
        ?.takeIf { code[it].opcode == Opcode.IGET_OBJECT && field(it).type == check.definingClass }
        ?: fail("the manager ${check.definingClass} doesn't come off the session")
    val manager = field(managerRead)
    val session = manager.definingClass
    val sessionRegister = (code[managerRead] as TwoRegisterInstruction).registerB
    fun readsSession(index: Int, opcode: Opcode) = code[index].opcode == opcode &&
        (code[index] as TwoRegisterInstruction).registerB == sessionRegister && field(index).definingClass == session

    val guardRead = (managerRead - 1 downTo 0).firstOrNull { readsSession(it, Opcode.IGET_BOOLEAN) }
        ?.takeIf { code.getOrNull(it + 1)?.opcode == Opcode.IF_NEZ }
        ?: fail("no busy flag tested before the manager")
    val flagRead = (guardRead - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.SGET_BOOLEAN && field(it).definingClass == session
    } ?: fail("no on-switch tested before the busy flag")
    val flagRegister = (code[flagRead] as OneRegisterInstruction).registerA
    if ((flagRead + 1 until guardRead).none {
            code[it].opcode == Opcode.IF_EQZ && (code[it] as OneRegisterInstruction).registerA == flagRegister
        }
    ) fail("the on-switch isn't tested")

    val looper = (start - 1 downTo checkRead).mapNotNull { index ->
        call(index)?.takeIf {
            code[index].opcode == Opcode.INVOKE_STATIC && it.returnType == LOOPER &&
                it.parameterTypes.map(CharSequence::toString) == listOf(HANDLER_THREAD)
        }
    }.firstOrNull() ?: fail("no Looper taken off the HandlerThread")
    val made = "$SIM_AUDIO_CHECK_START-><init>(${check.definingClass}$LOOPER)V"
    if (code.drop(start).none { it.opcode == Opcode.INVOKE_DIRECT && (it as ReferenceInstruction).reference.toString() == made }) {
        fail("the callback isn't made as $made")
    }
    val recordAt = (start + 1 until code.size).firstOrNull { index ->
        call(index)?.let {
            code[index].opcode == Opcode.INVOKE_DIRECT && it.name == "<init>" &&
                it.parameterTypes.map(CharSequence::toString).let { types ->
                    types.size == 4 && types[0] == "I" && types[1] == "Ljava/lang/Object;" && types[3] == HANDLER
                }
        } == true
    } ?: fail("no (what, key, listener, Handler) record")
    val record = call(recordAt)!!
    val args = code[recordAt] as FiveRegisterInstruction
    if (args.registerCount != 5 || args.registerE != sessionRegister) fail("the record isn't keyed by the session")
    if (literal(args.registerD, recordAt) != 2) fail("the record isn't message 2")
    fun sessionField(register: Int, what: String) = writer(register, recordAt)
        ?.takeIf { readsSession(it, Opcode.IGET_OBJECT) }?.let(::field)
        ?: fail("the record's $what doesn't come off the session")
    val listener = sessionField(args.registerF, "listener")
    val handler = sessionField(args.registerG, "Handler")
    val sendAt = (recordAt + 1 until code.size).firstOrNull { index ->
        call(index)?.let {
            code[index].opcode == Opcode.INVOKE_STATIC && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(HANDLER, MESSAGE)
        } == true
    } ?: fail("the message isn't sent")
    val heldAt = (sendAt + 1 until code.size).firstOrNull { readsSession(it, Opcode.IPUT_BOOLEAN) }
        ?.takeIf { literal((code[it] as TwoRegisterInstruction).registerA, it) == 0 }
        ?: fail("the held flag isn't cleared after the send")
    val volumes = (heldAt + 1 until code.size).filter { readsSession(it, Opcode.IPUT) && field(it).type == "F" }.take(2)
    if (volumes.size != 2 || volumes.any { literal((code[it] as TwoRegisterInstruction).registerA, it) != (-1f).toRawBits() }) {
        fail("the two volumes aren't put back to -1f")
    }
    return SessionAbandonBlock(
        session = session,
        direct = method.definingClass == session && method.parameterTypes.isEmpty() && method.returnType == "V" &&
            sessionRegister == body.registerCount - 1 && AccessFlags.PUBLIC.isSet(method.accessFlags),
        flag = field(flagRead),
        guard = field(guardRead),
        manager = manager,
        listener = listener,
        handler = handler,
        check = check,
        looper = looper,
        record = record,
        send = call(sendAt)!!,
        held = field(heldAt),
        volumes = volumes.map(::field),
    )
}

/** TikTok's give-up rebuilt as a public `()V` on the session, register for register its own. */
internal fun SessionAbandonBlock.smali() = """
    sget-boolean v0, ${flag.text()}
    if-eqz v0, :done
    iget-boolean v0, p0, ${guard.text()}
    if-nez v0, :done
    iget-object v5, p0, ${manager.text()}
    iget-object v4, p0, ${listener.text()}
    iget-object v3, p0, ${handler.text()}
    iget-object v0, v5, ${check.text()}
    if-nez v0, :ready
    new-instance v1, $HANDLER_THREAD
    const-string v0, "SimAudioFocusManager"
    invoke-direct { v1, v0 }, $HANDLER_THREAD-><init>(Ljava/lang/String;)V
    invoke-virtual { v1 }, Ljava/lang/Thread;->start()V
    invoke-static { v1 }, $looper
    move-result-object v1
    new-instance v0, $SIM_AUDIO_CHECK_START
    invoke-direct { v0, v5, v1 }, $SIM_AUDIO_CHECK_START-><init>(${check.definingClass}$LOOPER)V
    iput-object v0, v5, ${check.text()}
    :ready
    invoke-static { }, $MESSAGE->obtain()$MESSAGE
    move-result-object v2
    const/4 v1, 0x2
    iput v1, v2, $MESSAGE->what:I
    new-instance v0, ${record.definingClass}
    invoke-direct { v0, v1, p0, v4, v3 }, $record
    iput-object v0, v2, $MESSAGE->obj:Ljava/lang/Object;
    iget-object v0, v5, ${check.text()}
    invoke-static { v0, v2 }, $send
    const/4 v0, 0x0
    iput-boolean v0, p0, ${held.text()}
    const/high16 v0, -0x40800000
    iput v0, p0, ${volumes[0].text()}
    iput v0, p0, ${volumes[1].text()}
    :done
    return-void
"""

/** The give-up [FeedMuteMembers.sessionAbandonBody] describes, as a method of the session. */
internal fun sessionAbandonMethod(session: String, body: String): MutableMethod =
    MutableMethod(ImmutableMethod(session, SESSION_ABANDON, emptyList(), "V",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value, null, null,
        ImmutableMethodImplementation(7, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, body)
    }

private fun Method.invokes(target: Method): Boolean =
    implementation?.instructions?.any { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == target.definingClass && called.name == target.name &&
            called.parameterTypes.map(CharSequence::toString) == target.parameterTypes.map(CharSequence::toString)
    } == true

private fun isInstance(method: Method) = !AccessFlags.STATIC.isSet(method.accessFlags)

private fun requirePublic(what: String, flags: Int) {
    if (!AccessFlags.PUBLIC.isSet(flags)) {
        throw PatchException("$PATCH: $what isn't public in this build, and the extension calls it from outside.")
    }
}

/**
 * Reads the members off the four fingerprinted methods. [classOf] gives a class or null; the
 * patch reads the app with it and the fixture test the APKs, so both run this code.
 */
internal fun resolveFeedMuteMembers(
    enginePlay: Method,
    setIsMuteImpl: Method,
    sessionAbandon: Method,
    focusRequest: Method,
    classOf: (String) -> ClassDef?,
): FeedMuteMembers {
    val body = enginePlay.implementation ?: throw PatchException("$PATCH: the engine's play() has no body.")
    val instructions = body.instructions.toList()
    val self = body.registerCount - 1
    val read = instructions.indexOfFirst {
        it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerB == self
    }
    if (read < 0) throw PatchException("$PATCH: ${enginePlay.definingClass}.play() reads no field of its own.")
    val field = (instructions[read] as ReferenceInstruction).reference as FieldReference
    val into = (instructions[read] as TwoRegisterInstruction).registerA
    val player = instructions.drop(read + 1).firstOrNull {
        it.opcode == Opcode.CHECK_CAST && (it as OneRegisterInstruction).registerA == into
    }?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
        ?: throw PatchException("$PATCH: ${enginePlay.definingClass}.play() doesn't cast its ${field.name}.")
    val playerClass = classOf(player) ?: throw PatchException("$PATCH: $player isn't in this build.")
    val sourceId = playerClass.methods.singleOrNull {
        it.name == "getSourceId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("$PATCH: $player, the engine's player, declares no getSourceId().")

    val engineBase = classOf(TT_VIDEO_ENGINE) ?: throw PatchException("$PATCH: $TT_VIDEO_ENGINE isn't in this build.")
    val forwarder = engineBase.methods.filter {
        isInstance(it) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.invokes(setIsMuteImpl)
    }.singleOrPatchException("$PATCH: TTVideoEngine's forwarder to ${setIsMuteImpl.definingClass}->${setIsMuteImpl.name}")
    val isMute = engineBase.methods.singleOrNull {
        it.name == "isMute" && it.parameterTypes.isEmpty() && it.returnType == "Z" && isInstance(it)
    } ?: throw PatchException("$PATCH: TTVideoEngine has no isMute().")

    val abandon = readSessionAbandon(sessionAbandon)
    val session = classOf(abandon.session)
        ?: throw PatchException("$PATCH: ${abandon.session} isn't in this build.")
    if (session.methods.any { it.name == SESSION_ABANDON }) {
        throw PatchException("$PATCH: ${session.type} already has a $SESSION_ABANDON.")
    }
    val request = session.methods.filter {
        isInstance(it) && it.returnType == "V" && it.parameterTypes.isEmpty() && it.invokes(focusRequest)
    }.singleOrPatchException("$PATCH: the player session's focus request in ${session.type}")

    val engine = classOf(enginePlay.definingClass)!!
    requirePublic("the engine ${engine.type}", engine.accessFlags)
    requirePublic("the player ${playerClass.type}", playerClass.accessFlags)
    requirePublic("${playerClass.type}->getSourceId()", sourceId.accessFlags)
    requirePublic("TTVideoEngine", engineBase.accessFlags)
    requirePublic("TTVideoEngine->${forwarder.name}(Z)", forwarder.accessFlags)
    requirePublic("TTVideoEngine->isMute()", isMute.accessFlags)
    requirePublic("the player session ${session.type}", session.accessFlags)
    requirePublic("${session.type}->${request.name}()", request.accessFlags)
    val declared = generateSequence(engine) { classOf(it.superclass ?: return@generateSequence null) }
        .firstNotNullOfOrNull { owner -> owner.fields.singleOrNull { it.name == field.name && it.type == field.type } }
        ?: throw PatchException("$PATCH: nothing above ${engine.type} declares ${field.name}.")
    requirePublic("${field.definingClass}->${field.name}", declared.accessFlags)

    return FeedMuteMembers(
        engine = engine.type,
        playerField = field,
        player = player,
        setMute = forwarder,
        session = session.type,
        sessionAbandon = if (abandon.direct) sessionAbandon.name else SESSION_ABANDON,
        sessionRequest = request.name,
        sessionAbandonBody = if (abandon.direct) null else abandon.smali(),
    )
}

@Suppress("unused")
val feedMutePatch = bytecodePatch(
    name = "Mute feed videos",
    description = "Mutes feed videos without changing your phone's volume, so music from " +
        "another app keeps playing. You can also add a mute button on videos, or keep the sound " +
        "and let other apps play until a video starts. Starts off. Turn it on in Hushfeed " +
        "settings > Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch, blockAuthorPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedMute()V",
        )
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $FEED_MUTE->install(Landroid/app/Activity;)V",
        )

        // Which video a PlayerController asks for, before the engine for it plays. The Aweme is
        // p1 on both declared builds; a build that moves it gets a patch error, not a guess.
        PlayerPlayFingerprint.method.apply {
            val aweme = awemeParameterRegister()
            if (aweme != 1) throw PatchException("$PATCH: the play method's Aweme is p$aweme, not p1.")
            addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $FEED_MUTE->onControllerPlay(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }

        val members = resolveFeedMuteMembers(
            FeedEnginePlayFingerprint.method,
            EngineSetIsMuteFingerprint.method,
            SimAudioSessionAbandonFingerprint.method,
            SimAudioFocusRequestFingerprint.method,
        ) { classDefByOrNull(it) }

        // Every start of a feed video goes through the engine's play(); start() is hooked as
        // well, since it costs one line and nothing on these engines calls it today.
        val engine = mutableClassDefBy(members.engine)
        engine.methods.filter {
            (it.name == "play" || it.name == "start") && it.parameterTypes.isEmpty() && it.returnType == "V"
        }.also { if (it.none { method -> method.name == "play" }) throw PatchException("$PATCH: the engine lost play().") }
            .forEach {
                it.addInstruction(0, "invoke-static/range { p0 .. p0 }, $FEED_MUTE->onEnginePlay(Ljava/lang/Object;)V")
            }

        // The player session's lasting grant and the page helper's transient one, turned down
        // while muted with a feed video in front.
        val session = mutableClassDefBy(members.session)
        members.sessionAbandonBody?.let { session.methods.add(sessionAbandonMethod(members.session, it)) }
        session.methods.single { it.name == members.sessionRequest && it.parameterTypes.isEmpty() }
            .guardAtEntry(PATCH, "invoke-static/range { p0 .. p0 }, $FEED_MUTE->holdSessionFocus(Ljava/lang/Object;)Z", "return-void")
        val page = resolveNativeFocus()
        requirePublic("the page focus helper ${page.helper}", classDefBy(page.helper).accessFlags)
        requirePublic("${page.helper}->${page.request.name}(Context)", page.request.accessFlags)
        requirePublic("${page.helper}->${page.abandon.name}(Context)", page.abandon.accessFlags)
        page.request.guardAtEntry(PATCH, "invoke-static/range { p0 .. p0 }, $FEED_MUTE->holdPageFocus(Ljava/lang/Object;)Z", "return-void")

        installFeedMuteBridges(members, page.helper, page.request.name, page.abandon.name)
    }
}

/** Writes TikTok's members into FeedMute's bridge methods, whose own bodies serve the tests. */
private fun BytecodePatchContext.installFeedMuteBridges(
    members: FeedMuteMembers,
    pageHelper: String,
    pageRequest: String,
    pageAbandon: String,
) {
    val feedMute = mutableClassDefBy(FEED_MUTE)
    fun rewrite(name: String, locals: Int, body: String) {
        val original = feedMute.methods.filter { it.name == name }
            .singleOrPatchException("$PATCH: FeedMute's $name bridge")
        val bridge = original.cloneMutable(additionalRegisters = locals)
        feedMute.methods.remove(original)
        feedMute.methods.add(bridge)
        bridge.addInstructions(0, body)
    }
    val field = members.playerField
    rewrite("engineSourceId", 2, """
        check-cast p0, ${members.engine}
        iget-object v0, p0, ${field.definingClass}->${field.name}:${field.type}
        instance-of v1, v0, ${members.player}
        if-eqz v1, :none
        check-cast v0, ${members.player}
        invoke-virtual { v0 }, ${members.player}->getSourceId()Ljava/lang/String;
        move-result-object v0
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    rewrite("engineIsMute", 1, """
        check-cast p0, $TT_VIDEO_ENGINE
        invoke-virtual { p0 }, $TT_VIDEO_ENGINE->isMute()Z
        move-result v0
        return v0
    """)
    rewrite("setEngineMute", 1, """
        check-cast p0, $TT_VIDEO_ENGINE
        invoke-virtual { p0, p1 }, $TT_VIDEO_ENGINE->${members.setMute.name}(Z)V
        return-void
    """)
    for ((name, member) in listOf("abandonSessionFocus" to members.sessionAbandon, "requestSessionFocus" to members.sessionRequest)) {
        rewrite(name, 1, """
            check-cast p0, ${members.session}
            invoke-virtual { p0 }, ${members.session}->$member()V
            return-void
        """)
    }
    for ((name, member) in listOf("abandonPageFocus" to pageAbandon, "requestPageFocus" to pageRequest)) {
        rewrite(name, 1, """
            check-cast p0, $pageHelper
            invoke-virtual { p0, p1 }, $pageHelper->$member($CONTEXT)V
            return-void
        """)
    }
}
