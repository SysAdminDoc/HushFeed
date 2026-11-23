/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.isTikTokLite
import app.morphe.util.ControlFlow
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val LITE_ROW_EXTENSION = "Lapp/morphe/extension/tiktok/feed/LiteAuthorRow;"
private const val LITE_ROW_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val LITE_ROW_WHAT = "TikTok Lite's author row"
private val LITE_DATE_FORMATTER = listOf("J", "Landroid/content/Context;")

/**
 * TikTok Lite's feed author row bind (`LX/G4H;->LIILZ(Aweme)V` on 47.8.2), #502. It writes the
 * creator's name, then runs the video's createTime, times 1000, through a static (J, Context)
 * date formatter and puts the date after the name, past one `TextUtils.isEmpty` of the text.
 * Lite skips the formatter on For You and wherever one of its own checks says no, which is
 * why the date is missing there.
 */
internal object LiteAuthorRowBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(LITE_ROW_AWEME),
    filters = listOf(
        fieldAccess(definingClass = LITE_ROW_AWEME, name = "createTime"),
        methodCall(parameters = LITE_DATE_FORMATTER, returnType = "Ljava/lang/String;"),
        methodCall(definingClass = "Landroid/text/TextUtils;", name = "isEmpty"),
    ),
)

/**
 * Where Lite's author row is hooked: each [gates] entry is an if-eqz (index and register) that
 * sends the row past the formatter straight to the empty check, and [textAt] is the first
 * instruction past that check, with the date text in [textRegister] and the row's Aweme, the
 * method's own parameter, in [awemeRegister].
 */
internal class LitePostTimeSite(
    val gates: List<Pair<Int, Int>>,
    val textAt: Int,
    val textRegister: Int,
    val awemeRegister: Int,
)

private fun Instruction.liteWrites(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

private fun Instruction.liteCallRegisters(): List<Int> = (this as? FiveRegisterInstruction)?.let {
    listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
}.orEmpty()

/**
 * Finds the formatter, its empty check, the gates that skip it and the row's Aweme. Throws before
 * anything is written when one is gone or ambiguous, when the Aweme parameter is ever written,
 * when a register is out of a plain invoke's reach, or when something jumps straight to a place
 * a hook goes in front of, which would skip it.
 */
internal fun planLitePostTime(method: MutableMethod): LitePostTimeSite {
    val flow = ControlFlow.of(method)
    val code = flow.instructions
    val formats = code.indices.filter { index ->
        (code[index].opcode == Opcode.INVOKE_STATIC || code[index].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            code[index].getReference<MethodReference>()?.let {
                it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(CharSequence::toString) == LITE_DATE_FORMATTER
            } == true
    }
    val format = formats.singleOrNull()
        ?: throw PatchException("$LITE_ROW_WHAT: expected one date formatter in Lite's author row, found ${formats.size}.")
    val kept = code.getOrNull(format + 1)
    if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("$LITE_ROW_WHAT: Lite's author row doesn't keep the date it formats.")
    }
    val text = (kept as OneRegisterInstruction).registerA

    val checks = code.indices.filter { index ->
        index > format && code[index].getReference<MethodReference>()?.let {
            it.definingClass == "Landroid/text/TextUtils;" && it.name == "isEmpty"
        } == true && code[index].liteCallRegisters() == listOf(text)
    }
    val check = checks.singleOrNull()
        ?: throw PatchException("$LITE_ROW_WHAT: expected one empty check of Lite's row date, found ${checks.size}.")
    val answer = code.getOrNull(check + 1)
    val hides = code.getOrNull(check + 2)
    if (answer?.opcode != Opcode.MOVE_RESULT || hides?.opcode != Opcode.IF_NEZ ||
        (hides as OneRegisterInstruction).registerA != (answer as OneRegisterInstruction).registerA
    ) {
        throw PatchException("$LITE_ROW_WHAT: Lite's row no longer hides an empty date.")
    }
    val textAt = check + 3
    if (code.getOrNull(textAt) == null || method.implementation!!.instructions[textAt].location.labels.isNotEmpty()) {
        throw PatchException("$LITE_ROW_WHAT: something jumps past Lite's empty date check.")
    }

    val gates = code.indices.filter { index ->
        index < format && code[index].opcode == Opcode.IF_EQZ && flow.normal[index].firstOrNull() == check
    }.map { index -> index to (code[index] as OneRegisterInstruction).registerA }
    if (gates.isEmpty()) throw PatchException("$LITE_ROW_WHAT: Lite's row date has no check to answer.")
    gates.forEach { (index, register) ->
        if (method.implementation!!.instructions[index].location.labels.isNotEmpty()) {
            throw PatchException("$LITE_ROW_WHAT: something jumps straight to a check of Lite's row date.")
        }
        if (register > 15) throw PatchException("$LITE_ROW_WHAT: a check of Lite's row date is out of reach.")
    }

    val types = method.parameterTypes.map { it.toString() }
    if (types.count { it == LITE_ROW_AWEME } != 1) throw PatchException("$LITE_ROW_WHAT: Lite's author row no longer takes one Aweme.")
    val parameterRegisters = types.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt() +
        if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    val aweme = method.implementation!!.registerCount - parameterRegisters +
        (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) +
        types.take(types.indexOf(LITE_ROW_AWEME)).sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
    if (code.any { it.liteWrites(aweme) }) {
        throw PatchException("$LITE_ROW_WHAT: Lite's author row writes over its Aweme.")
    }
    if (text > 15 || aweme > 15) throw PatchException("$LITE_ROW_WHAT: Lite's row date or Aweme is out of reach.")
    return LitePostTimeSite(gates, textAt, text, aweme)
}

/**
 * The row's text first, so the gates' indices before it still hold: the date through the same
 * call TikTok's row uses (the exact time, the date options and the saved mark), then the country.
 * Then each gate's answer, with the row's video, so the row goes on to its text whenever the
 * date or the country is wanted.
 */
internal fun MutableMethod.hookLitePostTime(site: LitePostTimeSite) {
    addInstructions(
        site.textAt,
        """
            invoke-static { v${site.textRegister}, v${site.awemeRegister} }, $LITE_ROW_EXTENSION->text(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
            move-result-object v${site.textRegister}
        """,
    )
    site.gates.sortedByDescending { it.first }.forEach { (index, register) ->
        addInstructions(
            index,
            """
                invoke-static { v$register, v${site.awemeRegister} }, $LITE_ROW_EXTENSION->show(ILjava/lang/Object;)Z
                move-result v$register
            """,
        )
    }
}

/**
 * Hooks TikTok Lite's author row once for every switch that adds to it (#502): Always show
 * publish date's date and Show author region's country. Does nothing on TikTok, whose row each
 * of them decorates its own way.
 */
internal val liteAuthorRowPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        if (!isTikTokLite(packageMetadata.packageName)) return@execute
        val rows = LiteAuthorRowBindFingerprint.matchAllOrNull().orEmpty().map { it.method }
        val row = rows.singleOrNull()
            ?: throw PatchException("$LITE_ROW_WHAT: expected one in TikTok Lite, found ${rows.size}.")
        row.hookLitePostTime(planLitePostTime(row))
    }
}

/**
 * Always show publish date in TikTok Lite (#502): the date by the creator's name, on For You too,
 * with the date options, through [liteAuthorRowPatch]. Lite's profile grids are its own, so their
 * rows stay TikTok's.
 */
internal fun showPublishDateOnLite() {
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAlwaysShowPublishDate()V",
    )
}
