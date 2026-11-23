package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** TTNet's app provider: what it answers here is what the native network stack routes by. */
internal const val TTNET_ADAPTER = "Lcom/bytedance/ttnet/cronet/AbsCronetDependAdapter;"

/** Chromium's telephony bridge inside TTNet, which native code calls for the carrier code. */
internal const val TTNET_NETWORK_LIBRARY = "Lcom/ttnet/org/chromium/net/AndroidNetworkLibrary;"

internal const val NETWORK_STACK_DESCRIPTOR = "Lapp/morphe/extension/tiktok/spoof/sim/NetworkStackSim;"

private const val STRING = "Ljava/lang/String;"

/**
 * Each TTNet getter whose answer comes from the SIM, and the extension method that puts the
 * real SIM back into it. The system region is the phone's language setting, so it stays out.
 */
internal val NETWORK_STACK_BRIDGES = mapOf(
    "getCarrierRegion" to "region",
    "getRegion" to "region",
    "getAppInitialRegionInfo" to "initRegion",
    "getNetworkOperator" to "networkOperator",
    "getSimOperator" to "simOperator",
)

private val LIBRARY_GETTERS = setOf("getNetworkOperator", "getSimOperator")

internal class NetworkStackHook(val method: Method, val bridge: String)

private fun Method.isStringGetter() =
    parameterTypes.isEmpty() && returnType == STRING && implementation != null &&
        !AccessFlags.ABSTRACT.isSet(accessFlags)

private fun extendsAdapter(definition: ClassDef, classOf: (String) -> ClassDef?): Boolean {
    val seen = mutableSetOf<String>()
    var type = definition.superclass
    while (type != null && seen.add(type)) {
        if (type == TTNET_ADAPTER) return true
        type = classOf(type)?.superclass
    }
    return false
}

/**
 * #415: with SIM spoof on, a phone on one country's network told TikTok's network stack it was on
 * another's. TTNet reads the carrier region, the app region (the carrier region first), the
 * carrier code and the init-region header from these getters, sends them to its routing config
 * server and keeps the store region that comes back, so the image and API hosts it picked were the
 * preset country's. Every answer of every one of them, in the adapter and each class under it,
 * goes through the extension, which puts the real SIM back while the rest of the app keeps the
 * preset. Throws before anything is written when TTNet's shape moved.
 */
internal fun planNetworkStackHooks(classes: Sequence<ClassDef>, classOf: (String) -> ClassDef?): List<NetworkStackHook> {
    val adapter = classOf(TTNET_ADAPTER)
        ?: throw PatchException("SIM spoof: TTNet's app provider adapter is gone")
    val library = classOf(TTNET_NETWORK_LIBRARY)
        ?: throw PatchException("SIM spoof: TTNet's network library is gone")

    val owners = listOf(adapter) + classes.filter { it.type != TTNET_ADAPTER && extendsAdapter(it, classOf) }
    val providerHooks = owners.flatMap { owner ->
        owner.methods.filter {
            it.isStringGetter() && it.name in NETWORK_STACK_BRIDGES && !AccessFlags.STATIC.isSet(it.accessFlags)
        }.map { NetworkStackHook(it, NETWORK_STACK_BRIDGES.getValue(it.name)) }
    }
    val libraryHooks = library.methods.filter {
        it.isStringGetter() && it.name in LIBRARY_GETTERS && AccessFlags.STATIC.isSet(it.accessFlags)
    }.map { NetworkStackHook(it, NETWORK_STACK_BRIDGES.getValue(it.name)) }

    val adapterNames = providerHooks.filter { it.method.definingClass == TTNET_ADAPTER }.map { it.method.name }.toSet()
    val missing = NETWORK_STACK_BRIDGES.keys - adapterNames
    if (missing.isNotEmpty()) {
        throw PatchException("SIM spoof: TTNet's adapter no longer has ${missing.sorted()}")
    }
    // The adapter's own region getters answer "". The app's provider is what reads the SIM.
    for (name in listOf("getCarrierRegion", "getRegion", "getAppInitialRegionInfo")) {
        if (providerHooks.none { it.method.name == name && it.method.definingClass != TTNET_ADAPTER }) {
            throw PatchException("SIM spoof: no TTNet provider answers $name")
        }
    }
    if (libraryHooks.map { it.method.name }.toSet() != LIBRARY_GETTERS) {
        throw PatchException("SIM spoof: TTNet's network library no longer has $LIBRARY_GETTERS")
    }
    val hooks = providerHooks + libraryHooks
    hooks.firstOrNull { hook -> hook.method.implementation!!.instructions.none { it.opcode == Opcode.RETURN_OBJECT } }
        ?.let { throw PatchException("SIM spoof: ${it.method.definingClass}->${it.method.name} has no answer to hook") }
    return hooks
}

/** Hands every answer of a TTNet getter to the extension, which may swap the real SIM back in. */
internal fun MutableMethod.writeNetworkStackHook(bridge: String) {
    findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $NETWORK_STACK_DESCRIPTOR->$bridge($STRING)$STRING
                move-result-object v$register
            """,
        )
    }
}
