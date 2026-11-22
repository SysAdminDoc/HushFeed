/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.uiscale

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import app.morphe.util.literalReads
import app.morphe.util.readsRegisterAsFloat
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val UI_SCALE = "Lapp/morphe/extension/tiktok/font/UiScale;"
private const val RESOURCES = "Landroid/content/res/Resources;"

/** The text size factor one density rewrite reads into [register], scaled at instruction [index]. */
internal data class FactorRead(val method: Method, val index: Int, val register: Int)

private fun missing(why: String): Nothing = throw PatchException("UI scale: $why")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

/**
 * Every read of the text size factor that becomes a display density.
 *
 * [manager]'s startup rewrites are the methods that end in Resources.updateConfiguration
 * (LIZLLL(Context) and LJFF(Activity) on 47.9.3). Each reads the factor once,
 * `invoke-static LIZ()F; move-result v0`, and divides by it, and that read names the factor.
 * Activities don't take their density from those: each activity's context is rebuilt from the
 * same factor somewhere else (X.03Ji.invoke on 47.9.3, which reads it twice and hands the result
 * to Context.createConfigurationContext). So every method in [manager] or [classes] that reads
 * the factor and hands Android a configuration counts. With only the manager's reads scaled,
 * nothing on screen changed (#477).
 *
 * Each of these is a [PatchException]: a manager with no startup rewrite, one that reads a factor
 * other than once, rewrites that read different factors, no activity context built from the
 * factor, or a read whose value goes anywhere but float math (scaling a value that also went
 * somewhere else would change that too).
 */
internal fun densityFactorReads(manager: ClassDef, classes: Sequence<ClassDef>): List<FactorRead> {
    val startup = manager.methods.filter { method -> method.code().any { it.updatesResources() } }
    if (startup.isEmpty()) missing("${manager.type} rewrites no density")
    val factors = startup.map { method ->
        val code = method.code()
        val reads = code.indices.filter { index ->
            code[index].opcode == Opcode.INVOKE_STATIC && code[index].getReference<MethodReference>()?.let {
                it.definingClass == manager.type && it.returnType == "F" && it.parameterTypes.isEmpty()
            } == true
        }
        val read = reads.singleOrNull() ?: missing("${manager.type}->${method.name} reads the factor ${reads.size} times")
        code[read].getReference<MethodReference>()!!.name
    }
    val factor = factors.distinct().singleOrNull() ?: missing("${manager.type}'s rewrites read different factors $factors")
    val readsFactor = { instruction: Instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let {
            it.definingClass == manager.type && it.name == factor && it.returnType == "F" && it.parameterTypes.isEmpty()
        } == true
    }

    val rewrites = (sequenceOf(manager) + classes.filter { it.type != manager.type })
        .flatMap { it.methods.asSequence() }
        .filter { method ->
            val instructions = method.implementation?.instructions ?: return@filter false
            instructions.any(readsFactor) && instructions.any { it.updatesResources() || it.createsConfigurationContext() }
        }
        .toList()
    if (rewrites.none { method -> method.code().any { it.createsConfigurationContext() } }) {
        missing("no activity builds its context from ${manager.type}->$factor")
    }
    return rewrites.flatMap { method ->
        val where = "${method.definingClass}->${method.name}"
        val code = method.code()
        code.indices.filter { readsFactor(code[it]) }.map { read ->
            val kept = code.getOrNull(read + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
                ?: missing("$where doesn't keep the factor it reads")
            val register = kept.registerA
            val uses = method.literalReads(read + 1)
            if (uses.isEmpty() || uses.any { !method.readsRegisterAsFloat(it, register) }) {
                missing("$where uses the factor as more than a float")
            }
            FactorRead(method, read + 2, register)
        }
    }
}

private fun Instruction.updatesResources(): Boolean = getReference<MethodReference>()?.let {
    it.definingClass == RESOURCES && it.name == "updateConfiguration"
} == true

/** Context.createConfigurationContext(Configuration), whatever Context subclass the call names. */
private fun Instruction.createsConfigurationContext(): Boolean = getReference<MethodReference>()?.let {
    it.name == "createConfigurationContext" && it.returnType == "Landroid/content/Context;" &&
        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/res/Configuration;")
} == true

/**
 * Scales every read, each in the method [mutable] gives for it, last read first in each method so
 * an insert never moves a read still to come.
 */
internal fun List<FactorRead>.scaleAll(mutable: (Method) -> MutableMethod) {
    sortedByDescending { it.index }.forEach { read -> read.copy(method = mutable(read.method)).scale() }
}

/** Scales the read factor by the UI size, before anything uses it. */
internal fun FactorRead.scale() {
    (method as MutableMethod).addInstructions(
        index,
        """
            invoke-static/range {v$register .. v$register}, $UI_SCALE->scaleDensityFactor(F)F
            move-result v$register
        """,
    )
}
