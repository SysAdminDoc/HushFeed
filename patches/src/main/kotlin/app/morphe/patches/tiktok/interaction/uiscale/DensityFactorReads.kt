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
 * The factor read in each of [manager]'s density rewrites, the methods that end in
 * Resources.updateConfiguration (LIZLLL(Context) and LJFF(Activity) on 47.9.3, each
 * `invoke-static LIZ()F; move-result v0` and then a div-float by v0). A manager with no rewrite,
 * a rewrite that reads the factor other than once, or one that uses it as anything but a float
 * is a [PatchException]: scaling a value that also went somewhere else would change that too.
 */
internal fun densityFactorReads(manager: ClassDef): List<FactorRead> {
    val rewrites = manager.methods.filter { method ->
        method.code().any { instruction ->
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == RESOURCES && it.name == "updateConfiguration"
            } == true
        }
    }
    if (rewrites.isEmpty()) missing("${manager.type} rewrites no density")
    return rewrites.map { method ->
        val where = "${manager.type}->${method.name}"
        val code = method.code()
        val reads = code.indices.filter { index ->
            code[index].opcode == Opcode.INVOKE_STATIC && code[index].getReference<MethodReference>()?.let {
                it.definingClass == manager.type && it.returnType == "F" && it.parameterTypes.isEmpty()
            } == true
        }
        val read = reads.singleOrNull() ?: missing("$where reads the factor ${reads.size} times")
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
