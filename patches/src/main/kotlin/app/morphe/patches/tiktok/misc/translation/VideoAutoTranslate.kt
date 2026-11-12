/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

/** The Keva key TikTok keeps the Language page's Always translate switch under, once the user has flipped it. */
internal const val MASTER_TRANSLATION_KEY = "key_is_master_translation_toggle_on"

/** The Keva key of TikTok's own comment translation switch, which falls back to Always translate while unset. */
internal const val COMMENT_TRANSLATION_KEY = "key_is_comment_translation_on"

internal const val VIDEO_AUTO_TRANSLATE = "Lapp/morphe/extension/tiktok/translation/VideoAutoTranslate;"

/** The state's answer: whether Always translate is on, whichever way TikTok decided it. */
private const val IS_ON = "isOn"

private const val KEVA = "Lcom/bytedance/keva/Keva;"

/** The published state's constructor: the value, then where the change came from. */
private val PUBLISHED_STATE_PARAMETERS = listOf("Z", "Ljava/lang/String;")

private fun Instruction.loads(string: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        getReference<StringReference>()?.string == string

private fun MethodReference.sameAs(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/**
 * Whether this is TikTok's Always translate state: a static, argument-free getter returning the
 * state object, which reads [MASTER_TRANSLATION_KEY] (the user's own choice) before falling back to
 * the server's default. The key's other use is the setter, which takes the new value and returns
 * nothing, so it isn't this.
 */
internal fun Method.isMasterTranslationState(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.isNotEmpty()) return false
    if (!returnType.startsWith("L")) return false
    val instructions = implementation?.instructions ?: return false
    return instructions.any { it.loads(MASTER_TRANSLATION_KEY) }
}

/** The index of the `invoke-static` that takes [state] and is answered by its `isOn()`, or -1. */
private fun List<Instruction>.indexOfIsOn(state: MethodReference): Int =
    (0 until size - 2).firstOrNull { index ->
        val call = this[index]
        if (call.opcode != Opcode.INVOKE_STATIC) return@firstOrNull false
        val target = call.getReference<MethodReference>() ?: return@firstOrNull false
        if (target.definingClass != state.definingClass || target.name != state.name ||
            target.returnType != state.returnType || target.parameterTypes.isNotEmpty()
        ) {
            return@firstOrNull false
        }
        val move = this[index + 1]
        if (move.opcode != Opcode.MOVE_RESULT_OBJECT) return@firstOrNull false
        val answer = this[index + 2]
        if (answer.opcode != Opcode.INVOKE_VIRTUAL) return@firstOrNull false
        val isOn = answer.getReference<MethodReference>() ?: return@firstOrNull false
        isOn.definingClass == state.returnType && isOn.name == IS_ON && isOn.returnType == "Z" &&
            isOn.parameterTypes.isEmpty() &&
            (answer as FiveRegisterInstruction).registerC == (move as OneRegisterInstruction).registerA
    } ?: -1

/**
 * Whether this is the read every video translation decision asks: a static `()Z` that takes the
 * [state] and answers its `isOn()`, past a cached copy of the published state. On 47.4.3 the
 * caption Keva service hands it to `TranslationServiceImpl`'s shouldAutoTranslate, the captions
 * and the translatability checks. Inside its own class the comment translation switch asks it too,
 * for its default; [readStoredSwitch] moves those asks off it.
 */
internal fun Method.isMasterTranslationRead(state: MethodReference): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.isNotEmpty() || returnType != "Z") return false
    val instructions = implementation?.instructions?.toList() ?: return false
    return instructions.indexOfIsOn(state) >= 0
}

/** The `isOn()` the read asks of the state, the reference [readStoredSwitch] asks in its place. */
internal fun Method.isOnAskedBy(state: MethodReference): MethodReference {
    val instructions = implementation!!.instructions.toList()
    val index = instructions.indexOfIsOn(state)
    if (index < 0) throw PatchException("Translate comments: $definingClass->$name doesn't ask $IS_ON of the state.")
    return instructions[index + 2].getReference<MethodReference>()!!
}

/** The indexes of this method's calls to [read]. */
internal fun Method.callsTo(read: MethodReference): List<Int> =
    implementation?.instructions?.withIndex()
        ?.filter { (_, it) -> it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.sameAs(read) == true }
        ?.map { it.index }
        .orEmpty()

/**
 * Whether this is TikTok's own comment translation switch asking the read for its default: it
 * loads [COMMENT_TRANSLATION_KEY] and calls [read], taking each answer straight into a register.
 * On 47.4.3 that is the switch's getter, which
 * answers the stored comment choice or else Always translate, and the reset that publishes the
 * same default after the comment choice is erased.
 */
internal fun Method.isCommentTranslationDefault(read: MethodReference): Boolean {
    val instructions = implementation?.instructions?.toList() ?: return false
    val calls = callsTo(read)
    return instructions.any { it.loads(COMMENT_TRANSLATION_KEY) } && calls.isNotEmpty() &&
        // [readStoredSwitch] answers into the register each call's move-result names.
        calls.all { instructions.getOrNull(it + 1)?.opcode == Opcode.MOVE_RESULT }
}

/**
 * Whether this is Always translate's setter: static, takes where the change came from and the new
 * value, stores the value under [MASTER_TRANSLATION_KEY] and publishes a new state built from it.
 */
internal fun Method.isMasterTranslationSetter(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "V") return false
    if (parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;", "Z")) return false
    val instructions = implementation?.instructions ?: return false
    return instructions.any { it.loads(MASTER_TRANSLATION_KEY) } &&
        instructions.any {
            it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { call ->
                call.definingClass == KEVA && call.name == "storeBoolean"
            } == true
        }
}

/** The class of the state the setter publishes: the one it builds from the value and the source. */
internal fun Method.publishedStateType(): String? =
    implementation?.instructions
        ?.filter { it.opcode == Opcode.INVOKE_DIRECT }
        ?.mapNotNull { it.getReference<MethodReference>() }
        ?.filter { it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == PUBLISHED_STATE_PARAMETERS }
        ?.map { it.definingClass }
        ?.distinct()
        ?.singleOrNull()

/** The read answers false, as TikTok's own Always translate off would, while the extension says so. */
internal fun MutableMethod.keepVideosOriginal() {
    guardAtEntry(
        "Translate comments",
        "invoke-static {}, $VIDEO_AUTO_TRANSLATE->keepOriginal()Z",
        """
            const/4 v0, 0x0
            return v0
        """,
    )
}

/**
 * Every published state is built through the extension, which answers off while videos stay in
 * their own language. The state is what TikTok caches for the read and streams to everything that
 * watches Always translate change.
 */
internal fun MutableMethod.publishStateThroughExtension() {
    addInstructions(
        0,
        """
            invoke-static/range {p1 .. p1}, $VIDEO_AUTO_TRANSLATE->publishedState(Z)Z
            move-result p1
        """,
    )
}

/** What the comment switch's default asks after the stored switch: the last state TikTok published, unfiltered. */
private val COMMENT_DEFAULT = ImmutableMethodReference(VIDEO_AUTO_TRANSLATE, "commentDefault", listOf("Z"), "Z")

/**
 * Each of this method's calls to [read] becomes the switch as the user stored it, [state]'s
 * `isOn()`, handed to the extension's commentDefault. That answers the last state TikTok published,
 * before the filter, and the stored switch until one exists, the order the read answers in when
 * nothing is filtered. TikTok's own comment translation keeps the default it always had while videos
 * stay original, including while the switch is being flipped, when TikTok rebuilds the comment
 * default after publishing the new state but before storing it.
 */
internal fun MutableMethod.readStoredSwitch(read: MethodReference, state: MethodReference, isOn: MethodReference) {
    val body = implementation!!
    // Last first, so each index still names the instruction it was found at.
    for (index in callsTo(read).asReversed()) {
        val answer = body.instructions[index + 1]
        if (answer.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("Translate comments: $definingClass->$name doesn't take the answer of ${read.name} at $index.")
        }
        val register = (answer as OneRegisterInstruction).registerA
        body.replaceInstruction(
            index,
            BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ImmutableMethodReference(
                state.definingClass, state.name, listOf(), state.returnType,
            )),
        )
        addInstructions(
            index + 1,
            listOf(
                BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register),
                BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, register, 1, isOn),
            ),
        )
        // Past TikTok's own move-result, now at index + 3.
        addInstructions(
            index + 4,
            listOf(
                BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, COMMENT_DEFAULT),
                BuilderInstruction11x(Opcode.MOVE_RESULT, register),
            ),
        )
    }
}

/** What [hookVideoAutoTranslate] changes in the class that owns Always translate. */
internal class AlwaysTranslateHooks<M : Method>(
    val read: M,
    val isOn: MethodReference,
    val commentDefaults: List<M>,
    val stateType: String,
)

/**
 * Picks the read, the comment switch's calls to it and the published state's class out of the
 * [methods] of the class that owns Always translate. Fails closed: one read, at least one caller and
 * every caller about comments, and one setter building one kind of state.
 */
internal fun <M : Method> alwaysTranslateHooks(methods: Collection<M>, state: MethodReference): AlwaysTranslateHooks<M> {
    val reads = methods.filter { it.isMasterTranslationRead(state) }
    if (reads.size != 1) {
        throw PatchException(
            "Translate comments: expected one read of Always translate in ${state.definingClass}, found ${reads.size}.",
        )
    }
    val read = reads.single()

    val callers = methods.filter { it !== read && it.callsTo(read).isNotEmpty() }
    val unknown = callers.filterNot { it.isCommentTranslationDefault(read) }
    if (callers.isEmpty() || unknown.isNotEmpty()) {
        throw PatchException(
            "Translate comments: expected only comment translation to call ${read.name} inside " +
                "${state.definingClass}, found ${callers.size} caller(s), ${unknown.map { it.name }} not about comments.",
        )
    }

    val setters = methods.filter { it.isMasterTranslationSetter() }
    if (setters.size != 1) {
        throw PatchException("Translate comments: expected one setter of Always translate, found ${setters.size}.")
    }
    val stateType = setters.single().publishedStateType()
        ?: throw PatchException("Translate comments: the Always translate setter doesn't build one kind of state.")
    return AlwaysTranslateHooks(read, read.isOnAskedBy(state), callers, stateType)
}

/** The published state's one constructor taking the value and the source, out of its class's [methods]. */
internal fun <M : Method> publishedStateConstructor(methods: Collection<M>, stateType: String): M {
    val constructors = methods.filter {
        it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == PUBLISHED_STATE_PARAMETERS
    }
    if (constructors.size != 1) {
        throw PatchException(
            "Translate comments: expected one constructor of $stateType taking the value and the source, found ${constructors.size}.",
        )
    }
    return constructors.single()
}

/**
 * Lets the reader keep videos in the language they were made in while TikTok's Always translate is
 * on (#194). Three parts, all needed: the published state is built off, the read answers off before
 * a state is cached, and the comment switch's default asks the last state TikTok published, before
 * the filter, or the stored switch, instead of either.
 */
internal fun hookVideoAutoTranslate() {
    val state = MasterTranslationStateFingerprint.method
    val hooks = alwaysTranslateHooks(mutableClassDefBy(state.definingClass).methods, state)
    val constructor = publishedStateConstructor(mutableClassDefBy(hooks.stateType).methods, hooks.stateType)
    // The guard's one check that can fail, made before anything changes, so a miss leaves the class as it was.
    hooks.read.requireLocals("Translate comments", 1)

    hooks.commentDefaults.forEach { it.readStoredSwitch(hooks.read, state, hooks.isOn) }
    constructor.publishStateThroughExtension()
    hooks.read.keepVideosOriginal()
}
