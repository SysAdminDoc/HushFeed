/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.authorregion

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.BuilderSwitchPayload
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val WHAT = "Show author region"
private const val LITE_REGION = "Lapp/morphe/extension/tiktok/feed/LiteVideoRegion;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

internal const val GSON_TYPE_ADAPTER = "Lcom/google/gson/TypeAdapter;"
internal const val GSON_REFLECTIVE_ADAPTER = "Lcom/google/gson/internal/bind/ReflectiveTypeAdapterFactory\$Adapter;"

/** TikTok Lite's own generated Gson adapters (gsonopt), which every opted-in model goes through. */
internal const val LITE_GSONOPT_ADAPTER = "Lcom/ss/android/ugc/aweme/gsonopt/BaseAdapter;"

/**
 * Gson's JsonReader in TikTok Lite (`LX/1Rk;` on 47.8.2), whose method names are obfuscated, so
 * each method is told by what it does: getPath starts its path with "$", skipValue is the one
 * public void method that writes "null" as the skipped name, nextString is the one that turns a
 * long into text, and peek answers the JsonToken enum.
 */
internal class LiteGsonReader(
    val type: String,
    val path: Method,
    val peek: Method,
    val nextString: Method,
    val skipValue: Method,
)

private fun Method.strings(): List<String> = implementation?.instructions?.toList().orEmpty()
    .mapNotNull { it.getReference<StringReference>()?.string }

private fun Method.calls(): List<MethodReference> = implementation?.instructions?.toList().orEmpty()
    .mapNotNull { it.getReference<MethodReference>() }

internal fun liteGsonReader(classDefByOrNull: (String) -> ClassDef?): LiteGsonReader {
    val adapter = classDefByOrNull(GSON_TYPE_ADAPTER)
        ?: throw PatchException("$WHAT: TikTok Lite has no Gson TypeAdapter.")
    val reads = adapter.methods.filter {
        it.name == "read" && it.returnType == OBJECT && it.parameterTypes.size == 1
    }
    val type = reads.singleOrNull()?.parameterTypes?.single()?.toString()
        ?: throw PatchException("$WHAT: expected one read on Gson's TypeAdapter, found ${reads.size}.")
    val reader = classDefByOrNull(type)
        ?: throw PatchException("$WHAT: Gson's JsonReader $type isn't in TikTok Lite.")
    val plain = reader.methods.filter {
        it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    }

    fun one(what: String, found: List<Method>) = found.singleOrNull()
        ?: throw PatchException("$WHAT: expected one $what on Gson's JsonReader, found ${found.size}.")

    val path = one("getPath", plain.filter { it.returnType == STRING && "\$" in it.strings() })
    val skipValue = one("skipValue", plain.filter {
        it.returnType == "V" && AccessFlags.PUBLIC.isSet(it.accessFlags) && "null" in it.strings()
    })
    val nextString = one("nextString", plain.filter { method ->
        method.returnType == STRING &&
            method.calls().any { it.definingClass == "Ljava/lang/Long;" && it.name == "toString" }
    })
    val peek = one("peek", plain.filter { method ->
        val token = classDefByOrNull(method.returnType)
        token != null && token.superclass == "Ljava/lang/Enum;" &&
            token.fields.any { it.name == "STRING" && it.type == method.returnType }
    })
    return LiteGsonReader(type, path, peek, nextString, skipValue)
}

/**
 * Where a Gson adapter's read makes the model it fills: one call with no arguments answering an
 * Object (Gson's ObjectConstructor in the reflective adapter, the model's own factory in Lite's
 * gsonopt adapter), kept by the next instruction in [modelRegister], before the reader enters
 * the object. [readerRegister] is read's JsonReader parameter, which read never writes.
 */
internal class LiteMadeSite(val after: Int, val modelRegister: Int, val readerRegister: Int)

private val CALLS = setOf(
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
)

internal fun liteMadeSite(method: MutableMethod): LiteMadeSite {
    val where = "${method.definingClass}->${method.name}"
    if (method.parameterTypes.size != 1 || AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("$WHAT: $where no longer takes just the reader.")
    }
    val code = method.implementation!!.instructions.toList()
    val made = code.indices.filter { index ->
        code[index].opcode in CALLS && code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[index].getReference<MethodReference>()?.let {
                it.parameterTypes.isEmpty() && it.returnType == OBJECT
            } == true
    }
    val at = made.singleOrNull()
        ?: throw PatchException("$WHAT: expected $where to make one model, found ${made.size}.")
    val after = at + 2
    // Gson opens its try block right there, which only labels the spot; a branch, switch or
    // catch landing on it would skip the hook.
    val implementation = method.implementation!!
    val landings = implementation.instructions.flatMap { instruction ->
        when (instruction) {
            is BuilderOffsetInstruction -> listOf(instruction.target)
            is BuilderSwitchPayload -> instruction.switchElements.map { it.target }
            else -> emptyList()
        }
    } + implementation.tryBlocks.flatMap { block -> block.exceptionHandlers.map { it.handler } }
    if (code.getOrNull(after) == null || landings.any { it.location === implementation.instructions[after].location }) {
        throw PatchException("$WHAT: something jumps past where $where makes its model.")
    }
    val model = (code[at + 1] as OneRegisterInstruction).registerA
    val reader = method.implementation!!.registerCount - 1
    val writesReader = code.any { instruction ->
        val target = (instruction as? OneRegisterInstruction)?.registerA
        instruction.opcode.setsRegister() && target != null &&
            (target == reader || (instruction.opcode.setsWideRegister() && target + 1 == reader))
    }
    if (writesReader) {
        throw PatchException("$WHAT: $where writes over its reader.")
    }
    if (model > 15 || reader > 15) throw PatchException("$WHAT: the model or reader in $where is out of reach.")
    return LiteMadeSite(after, model, reader)
}

internal fun MutableMethod.hookLiteMade(site: LiteMadeSite) {
    addInstruction(
        site.after,
        "invoke-static { v${site.modelRegister}, v${site.readerRegister} }, " +
            "$LITE_REGION->made(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
}

/**
 * skipValue first asks the extension, which reads the value instead when it is a video's region
 * and says so, and then skipValue returns at once since the reader is already past it. v0 is
 * free at the top of the method because the method has locals.
 */
internal fun MutableMethod.hookLiteSkip() {
    val registers = implementation!!.registerCount
    if (registers <= 1) throw PatchException("$WHAT: Gson's skipValue has no register to spare.")
    if (registers - 1 > 15) throw PatchException("$WHAT: Gson's skipValue reader is out of reach.")
    addInstructionsWithLabels(
        0,
        """
            invoke-static { p0 }, $LITE_REGION->skipping(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :lite_region_skip
            return-void
        """,
        ExternalLabel("lite_region_skip", getInstruction(0)),
    )
}

/** What Lite's region needs, all found before anything is written. */
internal class LiteRegionPlan(
    val reader: LiteGsonReader,
    val madeIn: List<Pair<MutableMethod, LiteMadeSite>>,
)

private fun MutableMethod.readOf(type: String) = name == "read" && returnType == OBJECT &&
    parameterTypes.map(CharSequence::toString) == listOf(type)

internal fun planLiteRegions(): LiteRegionPlan {
    val reader = liteGsonReader { classDefByOrNull(it) }
    val madeIn = listOf(GSON_REFLECTIVE_ADAPTER, LITE_GSONOPT_ADAPTER).map { adapter ->
        val reads = mutableClassDefBy(adapter).methods.filter { it.readOf(reader.type) }
        val read = reads.singleOrNull()
            ?: throw PatchException("$WHAT: expected one read in $adapter, found ${reads.size}.")
        read to liteMadeSite(read)
    }
    return LiteRegionPlan(reader, madeIn)
}

/**
 * Show author region on TikTok Lite (#502). Lite's feed JSON has each video's "region", but
 * Lite's Aweme has no field for it, so Gson skips it. Gson now tells the extension about each
 * model it makes and where, and skipValue lets the extension read a region right inside an
 * Aweme instead. The extension's three reader stubs get Gson's own calls.
 */
internal fun LiteRegionPlan.write() {
    val reader = reader
    val type = reader.type
    val extension = mutableClassDefBy(LITE_REGION)
    val stubs = mapOf(
        "readerPath" to reader.path,
        "readerPeek" to reader.peek,
        "readerNextString" to reader.nextString,
    )
    stubs.forEach { (name, call) ->
        val stub = extension.methods.singleOrNull {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
        } ?: throw PatchException("$WHAT: the extension has no $name to fill in.")
        // What the stub did before is left behind as unreachable code.
        stub.addInstructions(
            0,
            """
                check-cast p0, $type
                invoke-virtual { p0 }, $type->${call.name}()${call.returnType}
                move-result-object p0
                return-object p0
            """,
        )
    }
    madeIn.forEach { (method, site) -> method.hookLiteMade(site) }
    mutableClassDefBy(type).methods.single {
        it.name == reader.skipValue.name && it.parameterTypes.isEmpty() && it.returnType == "V"
    }.hookLiteSkip()
}
