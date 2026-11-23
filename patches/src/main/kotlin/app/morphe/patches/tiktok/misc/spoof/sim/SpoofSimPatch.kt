/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/spoof/sim/SpoofSimPatch.kt
 */

package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.isTikTokLite
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patches.tiktok.shared.requireLocals

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/spoof/sim/SpoofSimPatch;"
internal const val IMAGE_LOADS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/spoof/sim/ImageLoads;"

/** Where the IMAGE LOADS hooks go in Fresco's network producer, all of it found before a write. */
internal class ImageLoadHooks(
    val failure: MutableMethod,
    /** The instruction the fetch-state hook goes in front of, right after the state is made. */
    val stateIndex: Int,
    val stateRegister: Int,
)

private fun imageLoadSignature(name: String, parameters: List<CharSequence>, returnType: String) =
    name + parameters.joinToString("", "(", ")") + returnType

/**
 * Finds the producer's own failure method (the one in its class taking the fetch state and a
 * Throwable) and the spot right after the fetcher hands back a new fetch state. Throws when either
 * is gone or ambiguous, or when anything jumps to either spot, so a build that moved them fails
 * the patch by name with nothing written.
 */
internal fun planImageLoadHooks(produceResults: MutableMethod, producerClass: MutableClass): ImageLoadHooks {
    val instructions = produceResults.implementation?.instructions?.toList()
        ?: throw PatchException("SIM spoof: the network fetch producer has no code")
    val failures = instructions.mapNotNull { it.getReference<MethodReference>() }.filter {
        it.definingClass == producerClass.type && it.returnType == "V" &&
            it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == "Ljava/lang/Throwable;"
    }.map { imageLoadSignature(it.name, it.parameterTypes, it.returnType) }.distinct()
    val failureSignature = failures.singleOrNull()
        ?: throw PatchException("SIM spoof: expected one image fetch failure call, found ${failures.size}")
    val failure = producerClass.methods.singleOrNull {
        imageLoadSignature(it.name, it.parameterTypes, it.returnType) == failureSignature &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("SIM spoof: the image fetch failure method $failureSignature is gone")
    val failureCode = failure.implementation?.instructions
        ?: throw PatchException("SIM spoof: the image fetch failure method has no code")
    if (failureCode.first().location.labels.isNotEmpty()) {
        throw PatchException("SIM spoof: something jumps to the start of the image fetch failure method")
    }
    val stateType = failure.parameterTypes[0].toString()

    val made = instructions.indices.filter { index ->
        instructions[index].opcode in STATE_CALLS &&
            instructions[index].getReference<MethodReference>()?.returnType == stateType
    }.let {
        it.singleOrNull()
            ?: throw PatchException("SIM spoof: expected one image fetch state made, found ${it.size}")
    }
    val move = instructions.getOrNull(made + 1)
    if (move?.opcode != Opcode.MOVE_RESULT_OBJECT || instructions.getOrNull(made + 2) == null) {
        throw PatchException("SIM spoof: the image fetch state isn't kept in a register")
    }
    if (move.location.labels.isNotEmpty() || instructions[made + 2].location.labels.isNotEmpty()) {
        throw PatchException("SIM spoof: something jumps to where the image fetch state is made")
    }
    return ImageLoadHooks(failure, made + 2, (move as OneRegisterInstruction).registerA)
}

private val STATE_CALLS = setOf(
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
)

/** Writes both hooks: each fetch state as it's made, and each failure as the producer takes it. */
internal fun writeImageLoadHooks(produceResults: MutableMethod, hooks: ImageLoadHooks) {
    produceResults.addInstruction(
        hooks.stateIndex,
        "invoke-static/range { v${hooks.stateRegister} .. v${hooks.stateRegister} }, " +
            "$IMAGE_LOADS_DESCRIPTOR->requested(Ljava/lang/Object;)V",
    )
    // p1 and p2: this takes one register below them, and both parameters are references.
    val state = hooks.failure.implementation!!.registerCount - 2
    hooks.failure.addInstruction(
        0,
        "invoke-static/range { v$state .. v${state + 1} }, " +
            "$IMAGE_LOADS_DESCRIPTOR->failed(Ljava/lang/Object;Ljava/lang/Throwable;)V",
    )
}

private object SimSpoofTargetFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            methodCall(
                definingClass = "Landroid/telephony/TelephonyManager;",
                returnType = "Ljava/lang/String;",
            ),
            methodCall(
                name = "getMccString",
                returnType = "Ljava/lang/String;",
            ),
            methodCall(
                name = "getMncString",
                returnType = "Ljava/lang/String;",
            ),
        ),
    ),
)

@Suppress("unused")
val simSpoofPatch = bytecodePatch(
    name = "SIM spoof",
    description = "Makes TikTok see the SIM country and carrier you choose. It may not change " +
        "your region, since TikTok also goes by your internet connection and account. Starts off. " +
        "Turn it on in Hushfeed settings > Region.",
    default = true,
) {
    category("Settings")
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(*AppCompatibilities.tiktokAndLite())

    execute {
        // TikTok Lite (#502) has no startup SIM change task, so there's no report to skip.
        val simChangeTask = if (isTikTokLite(packageMetadata.packageName)) null else CheckSimChangeTaskFingerprint.method
        // Checked before the first write: the SIM change task's injection writes v0 and then
        // falls into TikTok's own first instruction, so v0 has to be a local.
        simChangeTask?.requireLocals("SIM spoof", 1)
        // The IMAGE LOADS report's two hooks are worked out before the first write as well, so
        // a build that moved Fresco's network producer fails SIM spoof by name with nothing
        // written. They sit here because grey covers with a spoofed SIM are what they're for.
        val produceResults = NetworkFetchProducerFingerprint.method
        val imageLoads = planImageLoadHooks(produceResults, mutableClassDefBy(produceResults.definingClass))
        // TTNet routes by the SIM it's told about, so it keeps the real one (#415). Found before
        // the first write too, and before the extension's own classes could be counted.
        val allClasses = mutableListOf<ClassDef>()
        classDefForEach { allClasses += it }
        val networkStack = planNetworkStackHooks(allClasses.asSequence()) { classDefByOrNull(it) }

        val replacements = mapOf(
            "getSimCountryIso" to "getCountryIso",
            "getNetworkCountryIso" to "getCountryIso",
            "getSimOperator" to "getOperator",
            "getNetworkOperator" to "getOperator",
            "getSimOperatorName" to "getOperatorName",
            "getNetworkOperatorName" to "getOperatorName",
        )

        val patchesByMethod = linkedMapOf<Method, ArrayDeque<Pair<Int, String>>>()
        val matches = SimSpoofTargetFingerprint.matchAll()
            .filterNot { it.originalClassDef.type.startsWith("Lapp/morphe/extension/") }
        for (match in matches) {
            val method = match.originalMethod
            val implementation = method.implementation ?: continue
            implementation.instructions.forEachIndexed { index, instruction ->
                if (
                    instruction.opcode != Opcode.INVOKE_VIRTUAL &&
                    instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE
                ) return@forEachIndexed

                val methodReference = instruction.getReference<MethodReference>()
                    ?: return@forEachIndexed
                val replacement = when {
                    methodReference.definingClass == "Landroid/telephony/TelephonyManager;" ->
                        replacements[methodReference.name]
                    methodReference.definingClass.startsWith("Landroid/telephony/CellIdentity") &&
                        methodReference.name == "getMccString" -> "getMcc"
                    methodReference.definingClass.startsWith("Landroid/telephony/CellIdentity") &&
                        methodReference.name == "getMncString" -> "getMnc"
                    else -> null
                }
                if (replacement == null || methodReference.returnType != "Ljava/lang/String;") {
                    return@forEachIndexed
                }
                if (implementation.instructions.elementAtOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) {
                    patchesByMethod.getOrPut(method) { ArrayDeque() }.add(index to replacement)
                }
            }
        }

        patchesByMethod.forEach { (method, patches) ->
            val mutableMethod = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
            while (patches.isNotEmpty()) {
                val (index, replacement) = patches.removeLast()
                val resultRegister = mutableMethod.getInstruction<OneRegisterInstruction>(index + 1).registerA

                mutableMethod.addInstructions(
                    index + 2,
                    """
                        invoke-static/range { v$resultRegister .. v$resultRegister }, $EXTENSION_CLASS_DESCRIPTOR->$replacement(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$resultRegister
                    """,
                )
            }
        }

        // TikTok's startup SIM check reports a change to its own telemetry: which way the SIM
        // went, how long since the last check, how many SIMs the phone has, and the country and
        // carrier before and after. What decides that something changed is the subscription id,
        // an int the spoof does not touch, so the event fires on a real SIM swap or a change of
        // which SIM carries data. The country and carrier in it read as the preset, but the SIM
        // count does not: it comes from SubscriptionManager rather than TelephonyManager, and
        // the event still says the hardware moved. Nothing else in the app reads what the task
        // stores, so with the preset on it does not run.
        simChangeTask?.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldSkipSimChangeReport()Z
                    move-result v0
                    if-eqz v0, :run_sim_check
                    return-void
                """,
                ExternalLabel("run_sim_check", getInstruction(0)),
            )
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSimSpoof()V",
        )

        writeImageLoadHooks(produceResults, imageLoads)
        networkStack.forEach { hook ->
            mutableClassDefBy(hook.method.definingClass).findMutableMethodOf(hook.method)
                .writeNetworkStackHook(hook.bridge)
        }
    }
}
