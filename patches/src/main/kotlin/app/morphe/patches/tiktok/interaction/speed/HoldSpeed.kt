/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.getReference
import app.morphe.util.literalReads
import app.morphe.util.readsAfter
import app.morphe.util.readsRegisterAsFloat
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/speed/PlaybackSpeedPatch;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val STRING = "Ljava/lang/String;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/** Whether [instruction] is `Context.getString(int)`, the way TikTok reads its hold texts. */
internal fun isGetString(instruction: Instruction): Boolean =
    instruction.getReference<MethodReference>()?.let {
        it.definingClass == "Landroid/content/Context;" && it.name == "getString" &&
            it.parameterTypes == listOf("I") && it.returnType == "Ljava/lang/String;"
    } == true

/**
 * The 2.0f literals in the method, each as its index and the register it loads, once every
 * read of each is known to be a float read. A literal read as anything else would carry the
 * float [PlaybackSpeedPatch] hands back into an int slot and fail the verifier, so that
 * refuses here instead of crashing TikTok.
 */
internal fun Method.holdSpeedLiterals(where: String): List<Pair<Int, Int>> {
    val literals = speedLiterals(2.0f)
    if (literals.isEmpty()) throw PatchException("Playback speed: no 2x literal in $where.")
    literals.forEach { (index, register) -> requireFloatReads(index, register, "the 2x literal at $index of $where") }
    return literals
}

/**
 * The 1.0f literal the release method undoes the pull-down lock with, as its index and register:
 * TikTok hands it to the selection boundary (float, Aweme, String, String) with "Back to normal
 * speed" and keeps it as the gesture's speed. It is the 1x literal among whose reads that call
 * is, and like the 2x ones every read of it has to be a float read.
 */
internal fun Method.unlockSpeedLiteral(): Pair<Int, Int> {
    val instructions = implementation!!.instructions.toList()
    val (index, register) = speedLiterals(1.0f).filter { (index, _) ->
        literalReads(index).any { read ->
            instructions[read].getReference<MethodReference>()?.let {
                it.parameterTypes == listOf("F", AWEME, STRING, STRING) && it.returnType == "V"
            } == true
        }
    }.singleOrPatchException("Playback speed: the pull-down lock's undo speed")
    requireFloatReads(index, register, "the lock's 1x literal at $index of the release method")
    return index to register
}

/** The CONST_HIGH16 literals of [value], each as its index and the register it loads. */
private fun Method.speedLiterals(value: Float): List<Pair<Int, Int>> =
    implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        instruction.opcode == Opcode.CONST_HIGH16 &&
            (instruction as NarrowLiteralInstruction).narrowLiteral == value.toRawBits()
    }?.map { (index, instruction) -> index to (instruction as OneRegisterInstruction).registerA }.orEmpty()

private fun Method.requireFloatReads(index: Int, register: Int, what: String) {
    val reads = literalReads(index)
    val stray = reads.filterNot { readsRegisterAsFloat(it, register) }
    if (reads.isEmpty() || stray.isNotEmpty()) {
        throw PatchException(
            "Playback speed: $what is read as something other than a speed (at $stray), so it " +
                "cannot carry the hold speed.",
        )
    }
}

/** Each literal from [holdSpeedLiterals] replaced, right after it loads, by the hold speed. */
internal fun MutableMethod.routeThroughHoldSpeed(literals: List<Pair<Int, Int>>) =
    routeThrough(literals, "holdSpeed")

/** The literal from [unlockSpeedLiteral] replaced, right after it loads, by the speed undoing the lock plays. */
internal fun MutableMethod.routeThroughUnlockSpeed(literal: Pair<Int, Int>) =
    routeThrough(listOf(literal), "holdUnlockSpeed")

private fun MutableMethod.routeThrough(literals: List<Pair<Int, Int>>, helper: String) {
    literals.asReversed().forEach { (index, register) ->
        addInstructions(
            index + 1,
            """
                invoke-static/range {v$register .. v$register}, $EXTENSION->$helper(F)F
                move-result v$register
            """,
        )
    }
}

/**
 * Slide to change the hold speed shows its own padlock, so the lock banner is hidden as its show
 * method returns: that method sets it visible and nothing after it does. The banner is the
 * method's `this`, which has to reach every return unchanged.
 */
internal fun MutableMethod.hideHoldLockBannerOnShow() {
    val code = implementation!!
    val wide = parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
    val self = code.registerCount - parameterTypes.size - wide - 1
    val written = code.instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA?.let {
            it == self || (instruction.opcode.setsWideRegister() && it + 1 == self)
        } == true
    }.map { it.index }
    if (written.isNotEmpty()) {
        throw PatchException("Playback speed: the hold lock banner's show method writes its own register at $written.")
    }
    val returns = code.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("Playback speed: the hold lock banner's show method never returns.")
    returns.asReversed().forEach { index ->
        addInstructionsAtControlFlowLabel(
            index,
            "invoke-static/range {p0 .. p0}, $EXTENSION->holdLockBanner(Landroid/view/View;)V",
        )
    }
}

/**
 * The toast the release method raises after a pull-down lock, "Locked at 2× speed". The toast
 * builder takes the text as a string id and resolves it at once; each such call gets the
 * reworded text handed to the builder's CharSequence setter straight after, which overwrites
 * what the id gave. The id register carries the text across, so it has to be one nothing
 * reads again.
 */
internal fun BytecodePatchContext.rewordHoldSpeedToasts(release: MutableMethod) {
    val calls = release.implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val reference = instruction.getReference<MethodReference>() ?: return@mapNotNull null
        if (reference.parameterTypes != listOf("I") || reference.returnType != "V") return@mapNotNull null
        val builder = classDefByOrNull(reference.definingClass) ?: return@mapNotNull null
        val byId = builder.methods.firstOrNull {
            it.name == reference.name && it.parameterTypes == listOf("I") && it.returnType == "V"
        } ?: return@mapNotNull null
        if (byId.implementation?.instructions?.any(::isGetString) != true) return@mapNotNull null
        val byText = builder.textSetter() ?: return@mapNotNull null
        Triple(index, instruction as FiveRegisterInstruction, byText)
    }
    if (calls.isEmpty()) {
        throw PatchException("Playback speed: the hold gesture's release method sets no toast text from an id.")
    }
    calls.asReversed().forEachIndexed { n, (index, call, byText) ->
        val builder = call.registerC
        val id = call.registerD
        if (builder > 15 || id > 15) {
            throw PatchException("Playback speed: the lock toast's registers no longer fit a short call.")
        }
        val later = release.readsAfter(index, id)
        if (later.isNotEmpty()) {
            throw PatchException("Playback speed: the lock toast's id register v$id is read again at $later.")
        }
        release.addInstructionsWithLabels(
            index + 1,
            """
                invoke-static {v$id}, $EXTENSION->holdSpeedToast(I)$CHAR_SEQUENCE
                move-result-object v$id
                if-eqz v$id, :hold_speed_toast_kept_$n
                invoke-virtual {v$builder, v$id}, $byText
            """,
            ExternalLabel("hold_speed_toast_kept_$n", release.getInstruction(index + 1)),
        )
    }
}

/** The builder's one text setter taking a CharSequence, as a smali method reference. */
private fun ClassDef.textSetter(): String? = methods.singleOrNull {
    it.parameterTypes == listOf(CHAR_SEQUENCE) && it.returnType == "V"
}?.let { "${it.definingClass}->${it.name}($CHAR_SEQUENCE)V" }

/**
 * The banners TikTok shows during a hold. The receiver of the press method's event shows the
 * plain one ("Speed: 2x") when the lock is off and hands the lock banner the speed before the
 * hold and the guide flag when it is on. The lock banner reads its texts with getString, each
 * of which is reworded, and compares the speed before the hold with a 2.0f of its own to pick
 * "for normal speed" over "to lock 2x speed", which goes through the hold speed like the
 * gesture's own. The plain banner builds its label once, so it is reworded as it is shown and
 * hidden. Returns the lock banner's show method, for [hideHoldLockBannerOnShow].
 */
internal fun BytecodePatchContext.rewordHoldSpeedBanners(receiver: Method): MutableMethod {
    val calls = receiver.implementation!!.instructions.mapNotNull { instruction ->
        instruction.getReference<MethodReference>()?.let { instruction.opcode to it }
    }

    val lockCall = calls.filter { (opcode, reference) ->
        opcode == Opcode.INVOKE_INTERFACE && reference.parameterTypes == listOf("F", "Z") && reference.returnType == "V"
    }.map { it.second }.distinctBy { it.toString() }.singleOrPatchException("Playback speed: hold lock banner call")
    val implementors = mutableListOf<String>()
    classDefForEach { if (lockCall.definingClass in it.interfaces) implementors += it.type }
    val lockBanner = mutableClassDefBy(implementors.singleOrPatchException("Playback speed: hold lock banner"))
    val lockShow = lockBanner.methods.filter {
        it.name == lockCall.name && it.parameterTypes == listOf("F", "Z") && it.returnType == "V"
    }.singleOrPatchException("Playback speed: hold lock banner show")
    lockShow.routeThroughHoldSpeed(lockShow.holdSpeedLiterals("the hold lock banner"))

    var reworded = 0
    lockBanner.methods.forEach { method ->
        val sites = method.implementation?.instructions?.toList().orEmpty().let { instructions ->
            instructions.indices.filter { index ->
                isGetString(instructions[index]) &&
                    instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
            }.map { index -> index + 1 to (instructions[index + 1] as OneRegisterInstruction).registerA }
        }
        sites.asReversed().forEach { (index, register) ->
            method.addInstructions(
                index + 1,
                """
                    invoke-static/range {v$register .. v$register}, $EXTENSION->holdSpeedText(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """,
            )
            reworded++
        }
    }
    if (reworded == 0) throw PatchException("Playback speed: the hold lock banner reads no text with getString.")

    val plain = calls.filter { (opcode, reference) ->
        opcode == Opcode.INVOKE_VIRTUAL && reference.parameterTypes.isEmpty() && reference.returnType == "V" &&
            reference.definingClass != receiver.definingClass &&
            classDefByOrNull(reference.definingClass)?.superclass == "Landroid/widget/LinearLayout;"
    }.map { it.second }.distinctBy { it.toString() }
    val plainBanner = mutableClassDefBy(
        plain.map { it.definingClass }.distinct().singleOrPatchException("Playback speed: plain hold banner"),
    )
    if (plain.size != 2) {
        throw PatchException("Playback speed: the plain hold banner is shown and hidden by ${plain.size} calls, not two.")
    }
    plain.forEach { reference ->
        plainBanner.methods.filter {
            it.name == reference.name && it.parameterTypes.isEmpty() && it.returnType == "V"
        }.singleOrPatchException("Playback speed: plain hold banner ${reference.name}").addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $EXTENSION->holdSpeedBanner(Landroid/view/ViewGroup;)V",
        )
    }
    return lockShow
}
