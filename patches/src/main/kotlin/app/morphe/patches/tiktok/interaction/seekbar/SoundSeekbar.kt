/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Seek bar on sound previews (#185), part of Show the progress bar.
 *
 * A sound page plays a preview of the sound when you tap its cover. The cover is MusicCoverAssem,
 * one of the classes TikTok did not rename, and its onViewCreated hands over the view it built. Its
 * one getter returning MusicPlayViewModel, also a real name, is the page's preview model, and that
 * model's player is the one its onCleared releases. The player's members are renamed by each build,
 * so they are read off TikTok's own users of them: MusicDownloadPlayHelper.getDuration() asks the
 * player interface for the length, the interface's other int getter is the position, and its one
 * method taking an int and a callback is the seek. The sound the model last started is its one
 * MusicModel field, and the preview stops at that sound's audition length.
 *
 * TikTok counts the preview's play time itself, 60 ms a tick, and stops the preview when the count
 * reaches that length. The tick sets the count with setState and a lambda built from the new time
 * and a selector, so a seek builds the same lambda with the spot it seeks to and hands it to
 * setState too. The preview then still stops where TikTok means it to, and a pause picks up from
 * the new spot.
 *
 * The patch writes each of these into a bridge method in the extension's SoundSeekbar, whose own
 * body serves the tests.
 */

private const val SOUND_PATCH = "Show the progress bar"
internal const val SOUND_SEEKBAR = "Lapp/morphe/extension/tiktok/seekbar/SoundSeekbar;"

/** The sound page's cover, a real class name on every declared build. */
internal const val SOUND_COVER_ASSEM = "Lcom/ss/android/ugc/aweme/music/v2/assem/MusicCoverAssem;"
internal const val SOUND_PLAY_VIEW_MODEL = "Lcom/ss/android/ugc/aweme/music/v2/viewmodel/MusicPlayViewModel;"
internal const val SOUND_PLAY_HELPER = "Lcom/ss/android/ugc/aweme/music/ui/MusicDownloadPlayHelper;"
internal const val SOUND_MODEL = "Lcom/ss/android/ugc/aweme/shortvideo/model/MusicModel;"
internal const val SOUND_ASSEM_VIEW_MODEL = "Lcom/bytedance/assem/arch/viewModel/AssemViewModel;"
private const val SOUND_FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val SOUND_OBJECT = "Ljava/lang/Object;"
private const val SOUND_STRING = "Ljava/lang/String;"

/** The cover's onViewCreated, which hands over the view the cover built. */
internal object SoundCoverViewFingerprint : Fingerprint(
    definingClass = SOUND_COVER_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * The preview's tick: it logs the play time it counted ("playTime=") and, at the audition length,
 * "playCompleted", and pauses through the model. The play time update is read off it.
 */
internal object SoundPreviewTickFingerprint : Fingerprint(
    strings = listOf("playTime=", "playCompleted"),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            it.getReference<MethodReference>()?.definingClass == SOUND_PLAY_VIEW_MODEL
        } == true
    },
)

/** What the bridges call, each read off TikTok's own use of it. */
internal class SoundPreviewMembers(
    /** The cover's getter for its preview model. */
    val coverViewModel: String,
    /** The model's getter for its player, and the player's class. */
    val playerGetter: String,
    val player: String,
    val position: String,
    val duration: String,
    val seek: String,
    /** The seek's second parameter, the callback it is handed null for. */
    val seekCallback: String,
    /** The model's field holding the sound it last started. */
    val sound: String,
    /** The lambda the tick hands setState to set the play time, and its selector. */
    val playTime: String,
    val playTimeSelector: Int,
)

private fun Method.soundCalls(): List<MethodReference> =
    implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() } ?: emptyList()

private fun ClassDef.declaresPublic(name: String, parameters: List<String>, returnType: String) = methods.any {
    it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters && it.returnType == returnType &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
}

/**
 * Reads the members off TikTok's own users of them. [classOf] gives a class, or null when the
 * class isn't there; the patch reads the app with it, and the fixture test the APKs, so both run
 * this very code.
 */
internal fun resolveSoundPreviewMembers(tick: Method, classOf: (String) -> ClassDef?): SoundPreviewMembers {
    fun need(type: String) = classOf(type) ?: throw PatchException("$SOUND_PATCH: $type isn't in this build.")

    val cover = need(SOUND_COVER_ASSEM)
    val coverViewModel = cover.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.parameterTypes.isEmpty() && it.returnType == SOUND_PLAY_VIEW_MODEL
    }.singleOrPatchException("$SOUND_PATCH: the sound cover's preview model getter")

    val model = need(SOUND_PLAY_VIEW_MODEL)
    if (model.superclass != SOUND_ASSEM_VIEW_MODEL) {
        throw PatchException("$SOUND_PATCH: $SOUND_PLAY_VIEW_MODEL extends ${model.superclass}, not $SOUND_ASSEM_VIEW_MODEL.")
    }
    if (!need(SOUND_ASSEM_VIEW_MODEL).declaresPublic("setState", listOf(SOUND_FUNCTION1), "V")) {
        throw PatchException("$SOUND_PATCH: $SOUND_ASSEM_VIEW_MODEL has no public setState($SOUND_FUNCTION1)V.")
    }
    val clearedCalls = model.methods.filter { it.name == "onCleared" && it.parameterTypes.isEmpty() }
        .singleOrPatchException("$SOUND_PATCH: the preview model's onCleared").soundCalls()
    val released = clearedCalls.filter { it.name == "release" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        .map { it.definingClass }.toSet()
    val playerGetter = clearedCalls.filter {
        it.definingClass == SOUND_PLAY_VIEW_MODEL && it.parameterTypes.isEmpty() && it.returnType in released
    }.distinctBy { it.toString() }.singleOrPatchException("$SOUND_PATCH: the player the preview model's onCleared releases")
    val player = need(playerGetter.returnType)
    if (!model.declaresPublic(playerGetter.name, emptyList(), player.type)) {
        throw PatchException("$SOUND_PATCH: $SOUND_PLAY_VIEW_MODEL->${playerGetter.name}() isn't public.")
    }
    // The bridges name these classes from the extension's package, so each has to be public.
    for (owner in listOf(cover, model, player)) {
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) throw PatchException("$SOUND_PATCH: ${owner.type} isn't public.")
    }

    val lengthCall = need(SOUND_PLAY_HELPER).methods
        .filter { it.name == "getDuration" && it.parameterTypes.isEmpty() && it.returnType == "I" }
        .singleOrPatchException("$SOUND_PATCH: MusicDownloadPlayHelper.getDuration()")
        .let { method -> method.implementation?.instructions?.filter { it.opcode == Opcode.INVOKE_INTERFACE } ?: emptyList() }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter { it.parameterTypes.isEmpty() && it.returnType == "I" }
        .singleOrPatchException("$SOUND_PATCH: the player length getDuration() asks for")
    if (lengthCall.definingClass !in player.interfaces) {
        throw PatchException("$SOUND_PATCH: ${player.type} doesn't implement ${lengthCall.definingClass}, the player getDuration() asks.")
    }
    val api = need(lengthCall.definingClass)
    val getters = api.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "I" }.map { it.name }
    if (getters.size != 2 || lengthCall.name !in getters) {
        throw PatchException("$SOUND_PATCH: ${api.type} has the int getters $getters, not the length and the position.")
    }
    val position = getters.single { it != lengthCall.name }
    val seek = api.methods.filter {
        val parameters = it.parameterTypes.map(CharSequence::toString)
        it.returnType == "V" && parameters.size == 2 && parameters[0] == "I" && parameters[1].startsWith("L")
    }.singleOrPatchException("$SOUND_PATCH: ${api.type}'s seek")
    val seekCallback = seek.parameterTypes[1].toString()

    // The bridges call the player's own class, so each member has to be its public method there.
    for ((name, parameters, returnType) in listOf(
        Triple(position, emptyList(), "I"),
        Triple(lengthCall.name, emptyList(), "I"),
        Triple(seek.name, listOf("I", seekCallback), "V"),
        Triple("isPlaying", emptyList(), "Z"),
    )) {
        if (!player.declaresPublic(name, parameters, returnType)) {
            throw PatchException("$SOUND_PATCH: ${player.type} has no public $name(${parameters.joinToString("")})$returnType.")
        }
    }

    val sound = model.fields.filter { it.type == SOUND_MODEL && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .singleOrPatchException("$SOUND_PATCH: the preview model's sound field")
    if (!AccessFlags.PUBLIC.isSet(sound.accessFlags)) {
        throw PatchException("$SOUND_PATCH: $SOUND_PLAY_VIEW_MODEL->${sound.name} isn't public.")
    }
    val soundModel = need(SOUND_MODEL)
    if (!AccessFlags.PUBLIC.isSet(soundModel.accessFlags)) throw PatchException("$SOUND_PATCH: $SOUND_MODEL isn't public.")
    for ((name, returnType) in listOf("getMusicId" to SOUND_STRING, "getPreciseAuditionDuration" to "I")) {
        if (!soundModel.declaresPublic(name, emptyList(), returnType)) {
            throw PatchException("$SOUND_PATCH: $SOUND_MODEL has no public $name()$returnType.")
        }
    }

    // The tick's play time update: a lambda built from the new time and a constant selector,
    // handed straight to setState.
    val code = tick.implementation?.instructions?.toList() ?: emptyList()
    val update = code.indices.filter { index ->
        val built = code[index].takeIf { it.opcode == Opcode.INVOKE_DIRECT }?.getReference<MethodReference>()
        val handed = code.getOrNull(index + 1)?.getReference<MethodReference>()
        built != null && built.name == "<init>" && built.parameterTypes.map(CharSequence::toString) == listOf("I", "I") &&
            handed != null && handed.name == "setState" &&
            handed.parameterTypes.map(CharSequence::toString) == listOf(SOUND_FUNCTION1) &&
            (handed.definingClass == SOUND_ASSEM_VIEW_MODEL || handed.definingClass == SOUND_PLAY_VIEW_MODEL)
    }.singleOrPatchException("$SOUND_PATCH: the preview tick's play time update")
    val built = code[update] as FiveRegisterInstruction
    val playTime = built.getReference<MethodReference>()!!.definingClass
    val selector = code.subList(0, update).lastOrNull { it.writes(built.registerE) }
        ?.takeIf { it.opcode in SOUND_CONSTANTS } as? NarrowLiteralInstruction
        ?: throw PatchException("$SOUND_PATCH: the preview tick's play time selector isn't a constant.")
    val lambda = need(playTime)
    if (SOUND_FUNCTION1 !in lambda.interfaces || !AccessFlags.PUBLIC.isSet(lambda.accessFlags)) {
        throw PatchException("$SOUND_PATCH: $playTime isn't a public Function1.")
    }
    if (!lambda.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I") && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        throw PatchException("$SOUND_PATCH: $playTime has no public <init>(II)V.")
    }

    return SoundPreviewMembers(
        coverViewModel = coverViewModel.name,
        playerGetter = playerGetter.name,
        player = player.type,
        position = position,
        duration = lengthCall.name,
        seek = seek.name,
        seekCallback = seekCallback,
        sound = sound.name,
        playTime = playTime,
        playTimeSelector = selector.narrowLiteral,
    )
}

/** The int constants a selector is loaded with. An add-int/lit8 carries a literal too, and is no selector. */
internal val SOUND_CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/** Whether this instruction writes [register] as its first one, the way a const or a move-result does. */
private fun Instruction.writes(register: Int): Boolean =
    this is OneRegisterInstruction && registerA == register && opcode.setsRegister()

/** The bridge methods in SoundSeekbar, by name: their parameters and return type. */
internal val SOUND_PREVIEW_BRIDGES: Map<String, Pair<List<String>, String>> = linkedMapOf(
    "coverViewModel" to (listOf(SOUND_OBJECT) to SOUND_OBJECT),
    "previewPlaying" to (listOf(SOUND_OBJECT) to "Z"),
    "previewPosition" to (listOf(SOUND_OBJECT) to "I"),
    "previewDuration" to (listOf(SOUND_OBJECT) to "I"),
    "previewLimit" to (listOf(SOUND_OBJECT) to "I"),
    "previewSound" to (listOf(SOUND_OBJECT) to SOUND_STRING),
    "seekPreview" to (listOf(SOUND_OBJECT, "I") to "Z"),
)

/** Registers the bridge bodies use below the parameters: v0 and v1. */
internal const val SOUND_BRIDGE_LOCALS = 2

/**
 * The bridge bodies, by name. Each casts its Object parameter first, and every register that
 * meets a label holds one kind on each way in, which BridgeTypeFlow holds them to.
 */
internal fun soundPreviewBridgeBodies(members: SoundPreviewMembers): Map<String, String> {
    val model = SOUND_PLAY_VIEW_MODEL
    val player = members.player
    val getPlayer = "invoke-virtual {p0}, $model->${members.playerGetter}()$player"
    fun read(name: String, returnType: String) = """
        check-cast p0, $model
        $getPlayer
        move-result-object v0
        invoke-virtual {v0}, $player->$name()$returnType
        move-result v0
        return v0
    """
    return mapOf(
        "coverViewModel" to """
            check-cast p0, $SOUND_COVER_ASSEM
            invoke-virtual {p0}, $SOUND_COVER_ASSEM->${members.coverViewModel}()$model
            move-result-object v0
            return-object v0
        """,
        "previewPlaying" to read("isPlaying", "Z"),
        "previewPosition" to read(members.position, "I"),
        "previewDuration" to read(members.duration, "I"),
        "previewLimit" to """
            check-cast p0, $model
            iget-object v0, p0, $model->${members.sound}:$SOUND_MODEL
            if-eqz v0, :no_sound
            invoke-virtual {v0}, $SOUND_MODEL->getPreciseAuditionDuration()I
            move-result v0
            return v0
            :no_sound
            const/4 v0, 0x0
            return v0
        """,
        "previewSound" to """
            const/4 v1, 0x0
            check-cast p0, $model
            iget-object v0, p0, $model->${members.sound}:$SOUND_MODEL
            if-eqz v0, :no_sound
            invoke-virtual {v0}, $SOUND_MODEL->getMusicId()$SOUND_STRING
            move-result-object v1
            :no_sound
            return-object v1
        """,
        "seekPreview" to """
            check-cast p0, $model
            $getPlayer
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, p1, v1}, $player->${members.seek}(I${members.seekCallback})V
            new-instance v0, ${members.playTime}
            const v1, ${members.playTimeSelector}
            invoke-direct {v0, p1, v1}, ${members.playTime}-><init>(II)V
            invoke-virtual {p0, v0}, $SOUND_ASSEM_VIEW_MODEL->setState($SOUND_FUNCTION1)V
            const/4 v0, 0x1
            return v0
        """,
    )
}

/**
 * Checks the extension's bridge methods and returns the rewrite of each into its body, writing
 * nothing yet. A bridge gets two more registers for v0 and v1, and its parameters have to stay
 * within v15, since the seek's call names p1 in a four-bit form.
 */
internal fun soundPreviewBridges(members: SoundPreviewMembers): () -> Unit {
    val bodies = soundPreviewBridgeBodies(members)
    val owner = mutableClassDefBy(SOUND_SEEKBAR)
    val originals = SOUND_PREVIEW_BRIDGES.map { (name, signature) ->
        val (parameters, returnType) = signature
        val original = owner.methods.filter {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters && it.returnType == returnType &&
                AccessFlags.STATIC.isSet(it.accessFlags)
        }.singleOrPatchException("$SOUND_PATCH: the sound seek bar's $name bridge")
        val registers = (original.implementation?.registerCount ?: 0) + SOUND_BRIDGE_LOCALS
        if (registers > 16) throw PatchException("$SOUND_PATCH: the $name bridge would need $registers registers.")
        original
    }
    return {
        for (original in originals) {
            val bridge = original.cloneMutable(additionalRegisters = SOUND_BRIDGE_LOCALS)
            owner.methods.remove(original)
            owner.methods.add(bridge)
            bridge.addInstructionsWithLabels(0, bodies.getValue(original.name))
        }
    }
}

/** Hands the extension the view the sound cover built, before the cover sets it up. */
internal fun MutableMethod.soundCoverViewHook(): () -> Unit {
    requireInstanceParameters(listOf("Landroid/view/View;"))
    return { addInstruction(0, "invoke-static/range {p0 .. p1}, $SOUND_SEEKBAR->onCoverView(Ljava/lang/Object;Landroid/view/View;)V") }
}
