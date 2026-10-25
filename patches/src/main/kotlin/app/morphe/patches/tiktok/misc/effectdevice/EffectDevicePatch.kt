/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.effectdevice

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Report a newer phone to effects"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/EffectDeviceSpoof;"
private const val BUILD = "Landroid/os/Build;"
private const val EFFECT_CONFIGURATION_BUILDER = "Lcom/ss/android/ugc/effectmanager/EffectConfiguration\$Builder;"
private const val MODEL_CONFIGURATION_BUILDER = "Lcom/ss/android/ugc/effectmanager/DownloadableModelConfig\$Builder;"

/**
 * TikTok's effect platform builds two configs that carry the phone's model as `device_type`:
 * EffectConfiguration (the effect list and download requests, where the server answers 2003 for
 * a phone it won't serve) and DownloadableModelConfig (the effect models). Each build reads
 * `Build.MODEL` and hands it straight to the builder's setter. Three methods do that on 47.5.3.
 */

/** `EffectPlatformFactory.createEffectConfigurationBuilder(...)`, the class keeps its name. */
internal object EffectConfigurationFactoryFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/effectplatform/EffectPlatformFactory;",
    name = "createEffectConfigurationBuilder",
    filters = listOf(
        fieldAccess(definingClass = BUILD, name = "MODEL"),
        methodCall(definingClass = EFFECT_CONFIGURATION_BUILDER, name = "deviceType"),
    ),
)

/**
 * The effect manager's creator (`LX/1JpD;->LIZ()`, obfuscated): reads the model once for the
 * model config and once for the effect config.
 */
internal object EffectManagerCreateFingerprint : Fingerprint(
    returnType = "Lcom/ss/android/ugc/effectmanager/EffectManager;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(definingClass = BUILD, name = "MODEL"),
        methodCall(definingClass = MODEL_CONFIGURATION_BUILDER, name = "setDeviceType"),
        fieldAccess(definingClass = BUILD, name = "MODEL"),
        methodCall(definingClass = EFFECT_CONFIGURATION_BUILDER, name = "deviceType"),
    ),
)

/**
 * A second model config builder (`Lx2j/q;->LJ()V`, obfuscated), static and with no parameters,
 * which reads the model once for `setDeviceType`.
 */
internal object ModelConfigBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(definingClass = BUILD, name = "MODEL"),
        methodCall(definingClass = MODEL_CONFIGURATION_BUILDER, name = "setDeviceType"),
    ),
)

internal val effectDeviceFingerprints: List<Fingerprint>
    get() = listOf(
        EffectConfigurationFactoryFingerprint,
        EffectManagerCreateFingerprint,
        ModelConfigBuilderFingerprint,
    )

/**
 * Puts the extension's answer in `Build.MODEL`'s register, right after each read of it in the
 * method. The read's register is the one the builder call takes next, so the swapped string is
 * what goes to the setter. Each read is a `sget-object`, so a `move-result-object` into the same
 * register follows the call. Returns how many reads it covered.
 */
internal fun MutableMethod.swapModelReads(): Int {
    val reads = implementation!!.instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.SGET_OBJECT && instruction.getReference<FieldReference>()?.let {
            it.definingClass == BUILD && it.name == "MODEL"
        } == true
    }
    // Last read first, so the earlier indexes stay where they were.
    for ((index, read) in reads.reversed()) {
        val register = (read as OneRegisterInstruction).registerA
        addInstructions(
            index + 1,
            "invoke-static { v$register }, $EXTENSION->deviceType(Ljava/lang/String;)Ljava/lang/String;\n" +
                "move-result-object v$register",
        )
    }
    return reads.size
}

/** Swaps every model read in [this] and fails the patch unless there were [expected] of them. */
private fun MutableMethod.swapExactly(expected: Int) {
    val covered = swapModelReads()
    if (covered != expected) {
        throw PatchException("$PATCH: $definingClass->$name reads Build.MODEL $covered times, not $expected.")
    }
}

@Suppress("unused")
val effectDevicePatch = bytecodePatch(
    name = "Report a newer phone to effects",
    description = "Tells TikTok's effects server you have a newer phone, so effects it turns down " +
        "on older phones get another chance. Starts off. Turn it on in Hushfeed settings > App.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)

    // PineDrama has the first and third of these but not the effect manager creator.
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        EffectConfigurationFactoryFingerprint.method.swapExactly(1)
        EffectManagerCreateFingerprint.method.swapExactly(2)
        ModelConfigBuilderFingerprint.method.swapExactly(1)

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableEffectDeviceSpoof()V",
        )
    }
}
