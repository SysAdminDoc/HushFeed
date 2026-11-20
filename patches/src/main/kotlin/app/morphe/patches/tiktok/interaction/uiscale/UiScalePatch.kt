/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.uiscale

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "UI scale"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/font/UiScale;"

/**
 * TikTok's text size manager's loader, which reads its saved mode at startup. Its class also
 * rewrites the app's display density from a factor; the name is obfuscated (X.04F8 on 47.7.1,
 * X.03aC on 47.9.2).
 */
private object DensityManagerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("dynamic_font_mode"),
)

@Suppress("unused")
val uiScalePatch = bytecodePatch(
    name = PATCH,
    description = "Makes TikTok's whole interface smaller, text and buttons alike, so more fits " +
        "on screen. Starts at 100 %, which changes nothing. Pick a size in Hushfeed settings > " +
        "App, then restart TikTok.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableUiScale()V",
        )

        // Each method that rewrites the density asks the manager for a factor first (an own
        // static ()F method), then calls Resources.updateConfiguration. The factor is scaled
        // right where it is read, so the text size patches that answer the factor stay unaware.
        val manager = DensityManagerFingerprint.classDef
        var hooked = 0
        manager.methods.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            val rewritesDensity = instructions.any { ins ->
                ins.getReference<MethodReference>()?.let {
                    it.definingClass == "Landroid/content/res/Resources;" && it.name == "updateConfiguration"
                } == true
            }
            if (!rewritesDensity) return@forEach
            instructions.indices.reversed().forEach { index ->
                val call = instructions[index].getReference<MethodReference>() ?: return@forEach
                if (instructions[index].opcode != Opcode.INVOKE_STATIC || call.definingClass != manager.type ||
                    call.returnType != "F" || call.parameterTypes.isNotEmpty()
                ) return@forEach
                val result = instructions.getOrNull(index + 1)
                    ?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
                    ?: return@forEach
                val register = result.registerA
                method.addInstructions(
                    index + 2,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION->scaleDensityFactor(F)F
                        move-result v$register
                    """,
                )
                hooked++
            }
        }
        if (hooked == 0) throw PatchException("$PATCH: TikTok's density rewrite reads no factor.")
    }
}
