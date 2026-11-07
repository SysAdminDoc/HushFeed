/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The server value that places the Captions row on TikTok's Accessibility page: 0 out, 1 first, 2 last. */
internal const val CAPTIONS_ROW_KEY = "enable_captions_a11y_setting"

/** The server value that turns on the caption controls the row works with (fixed caption v2). */
internal const val CAPTION_CONTROLS_KEY = "fixed_caption_v2_consumer_group"

internal const val CAPTIONS_AVAILABLE = "Lapp/morphe/extension/tiktok/captions/CaptionsAvailable;"

/** The experiment read: (namespace, default, key, sticky) answered as an int. */
private val EXPERIMENT_READ_PARAMETERS = listOf("I", "I", "Ljava/lang/String;", "Z")

private fun MethodReference.isExperimentRead() =
    returnType == "I" && parameterTypes.map { it.toString() } == EXPERIMENT_READ_PARAMETERS

/**
 * The lazy that answers where the Captions row goes (#217): a static lambda body that reads
 * [CAPTIONS_ROW_KEY] from TikTok's experiments and boxes the answer. On 47.4.3 it is
 * `AFwS158S0000000_20_I1.invoke$17`, the value behind `X.0mZq.LIZ()`. The experiment registry
 * that lists the key returns nothing, so the return type leaves it out.
 */
internal object CaptionsRowFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf(CAPTIONS_ROW_KEY),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) && method.experimentReadAnswer() >= 0 },
)

/**
 * The class initializer that reads [CAPTION_CONTROLS_KEY] into a static int (`X.09Hb` on 47.4.3).
 * The class's one static `()Z` answers whether that int is set, and every caption control asks it.
 */
internal object CaptionControlsGroupFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    strings = listOf(CAPTION_CONTROLS_KEY),
    custom = { method, _ -> method.experimentGroupField() != null },
)

/**
 * The index of the `move-result` that takes this method's experiment read, the first after the
 * load of a key, or -1. The hook goes right after it.
 */
internal fun Method.experimentReadAnswer(): Int {
    val instructions = implementation?.instructions?.toList() ?: return -1
    val key = instructions.indexOfFirst {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            it.getReference<StringReference>() != null
    }
    if (key < 0) return -1
    for (index in key + 1 until instructions.size - 1) {
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
        if (call.getReference<MethodReference>()?.isExperimentRead() != true) continue
        return if (instructions[index + 1].opcode == Opcode.MOVE_RESULT) index + 1 else -1
    }
    return -1
}

/** The static int field the initializer stores its experiment read in, or null. */
internal fun Method.experimentGroupField(): FieldReference? {
    val answer = experimentReadAnswer()
    if (answer < 0) return null
    val instructions = implementation!!.instructions.toList()
    val register = (instructions[answer] as OneRegisterInstruction).registerA
    val store = instructions.getOrNull(answer + 1) ?: return null
    if (store.opcode != Opcode.SPUT || (store as OneRegisterInstruction).registerA != register) return null
    return store.getReference<FieldReference>()?.takeIf { it.definingClass == definingClass && it.type == "I" }
}

/**
 * Whether this is the question every caption control asks: static, no parameters, answers a
 * boolean, and reads [group], the int the class initializer stored.
 */
internal fun Method.asksCaptionControlsGroup(group: FieldReference): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.isNotEmpty() || returnType != "Z") return false
    return implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.SGET && instruction.getReference<FieldReference>()?.let {
            it.definingClass == group.definingClass && it.name == group.name && it.type == group.type
        } == true
    } == true
}

/** The one caption controls question among the class's [methods], failing closed on none or several. */
internal fun <M : Method> captionControlsQuestion(methods: Collection<M>, group: FieldReference): M {
    val asks = methods.filter { it.asksCaptionControlsGroup(group) }
    if (asks.size != 1) {
        throw PatchException(
            "Subtitle tools: expected one question about ${group.definingClass}->${group.name}, found ${asks.size}.",
        )
    }
    return asks.single()
}

/** The row's answer goes through the extension, which places a row the server left out. */
internal fun MutableMethod.placeCaptionsRow() {
    val answer = experimentReadAnswer()
    if (answer < 0) throw PatchException("Subtitle tools: $definingClass->$name doesn't take an experiment read.")
    val register = (implementation!!.instructions[answer] as OneRegisterInstruction).registerA
    addInstructions(
        answer + 1,
        """
            invoke-static/range { v$register .. v$register }, $CAPTIONS_AVAILABLE->captionsRow(I)I
            move-result v$register
        """,
    )
}

/** The question answers yes while the extension says so, and TikTok answers otherwise. */
internal fun MutableMethod.keepCaptionControls(patch: String) = guardAtEntry(
    patch,
    "invoke-static {}, $CAPTIONS_AVAILABLE->captionControls()Z",
    """
        const/4 v0, 0x1
        return v0
    """,
)

/**
 * Keep captions available (#217). Both parts are needed: the row's placement alone draws nothing
 * where the caption controls are off, and the controls alone leave the Accessibility page without
 * its switch.
 */
internal fun BytecodePatchContext.hookCaptionsAvailable(patch: String) {
    val row = CaptionsRowFingerprint.method
    val initializer = CaptionControlsGroupFingerprint.method
    val group = initializer.experimentGroupField()
        ?: throw PatchException("$patch: ${initializer.definingClass} doesn't store $CAPTION_CONTROLS_KEY.")
    val question = captionControlsQuestion(mutableClassDefBy(initializer.definingClass).methods, group)
    // The guard's one check that can fail, made before anything changes.
    question.requireLocals(patch, 1)

    row.placeCaptionsRow()
    question.keepCaptionControls(patch)
}
