/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** TikTok's speech engine factory, the only place its engine class is built. */
internal const val SPEECH_GENERATOR_CLASS_DESCRIPTOR = "Lcom/bytedance/speech/speechengine/SpeechEngineGenerator;"

/** The engine interface every caller holds. */
internal const val SPEECH_ENGINE_CLASS_DESCRIPTOR = "Lcom/bytedance/speech/speechengine/SpeechEngine;"

/** Loads libspeechengine and libspeechsdk (load) and makes the native bridge (createBridge). */
internal const val SPEECH_LOADER_CLASS_DESCRIPTOR = "Lcom/bytedance/speech/speechengine/SpeechEngineLoader;"

/** The native bridge the engine keeps in a final field and calls for every real operation. */
internal const val SPEECH_BRIDGE_CLASS_DESCRIPTOR = "Lcom/bytedance/speech/speechengine/bridge/SpeechBridge;"

private val SPEECH_LIBRARY_ENTRIES = setOf("load", "createBridge")

private val MOVE_RESULTS = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT)

/**
 * `SpeechEngineGenerator.getInstance()` builds the engine (obfuscated, LX/0Igl on 47.4.3) under
 * the generator's lock. The engine's class initializer loads the speech libraries and its
 * constructor makes the native bridge, so the engine type is read from this method's
 * new-instance. Pinned to the class, since the name is TikTok's own and not obfuscated.
 */
internal object SpeechEngineGetInstanceFingerprint : Fingerprint(
    definingClass = SPEECH_GENERATOR_CLASS_DESCRIPTOR,
    name = "getInstance",
    returnType = SPEECH_ENGINE_CLASS_DESCRIPTOR,
    parameters = listOf(),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

private val WIDE_CONSTANTS = setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE)

/**
 * True when [method] reaches the speech libraries before checking the engine handle: it calls
 * the loader's load or createBridge, or reads the bridge, ahead of comparing its own long field
 * (the handle, -1 until a native engine exists) with -1 and branching away only when they differ
 * (cmp-long, then if-nez on its result). Methods that check the handle that way already answer
 * "no engine" while it's -1, so the guard leaves them alone. Any other compare doesn't count.
 */
internal fun reachesSpeechLibraries(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    var handleRegister = -1
    var minusOneRegister = -1
    for ((index, instruction) in instructions.withIndex()) {
        val reference = (instruction as? ReferenceInstruction)?.reference
        when {
            instruction.opcode == Opcode.IGET_WIDE && reference is FieldReference &&
                reference.definingClass == method.definingClass && reference.type == "J" ->
                handleRegister = (instruction as TwoRegisterInstruction).registerA
            instruction.opcode in WIDE_CONSTANTS && (instruction as WideLiteralInstruction).wideLiteral == -1L ->
                minusOneRegister = (instruction as OneRegisterInstruction).registerA
            instruction.opcode == Opcode.CMP_LONG -> {
                val compare = instruction as ThreeRegisterInstruction
                val branch = instructions.getOrNull(index + 1)
                if (setOf(compare.registerB, compare.registerC) == setOf(handleRegister, minusOneRegister) &&
                    branch?.opcode == Opcode.IF_NEZ && (branch as OneRegisterInstruction).registerA == compare.registerA
                ) {
                    return false
                }
            }
            reference is MethodReference && reference.definingClass == SPEECH_LOADER_CLASS_DESCRIPTOR &&
                reference.name in SPEECH_LIBRARY_ENTRIES -> return true
            instruction.opcode == Opcode.IGET_OBJECT && reference is FieldReference &&
                reference.type == SPEECH_BRIDGE_CLASS_DESCRIPTOR -> return true
        }
    }
    return false
}

/** The "no engine" answer for a guarded method, and the registers it writes. */
private fun noEngineAnswer(method: Method): Pair<String, Int> = when (method.returnType) {
    "V" -> "return-void" to 0
    "Z" -> "const/4 v0, 0x0\nreturn v0" to 1
    "I" -> "const/4 v0, -0x1\nreturn v0" to 1
    "J" -> "const-wide/16 v0, -0x1\nreturn-wide v0" to 2
    "Ljava/lang/String;" -> "const-string v0, \"\"\nreturn-object v0" to 1
    else -> throw PatchException("Speech engine method ${method.name} returns ${method.returnType}, which has no no-engine answer")
}

/**
 * Keeps TikTok alive once Remove voice tools has emptied the speech libraries (issue #213): the
 * engine's class initializer no longer loads them and its constructor leaves the bridge null, so
 * the engine handle stays -1 and the methods that check it answer "no engine" the way they do
 * when the native side fails. The few methods that touch the libraries without that check
 * (createEngine, getVersion, isEngineSupported and the generator's monitor setup) get the same
 * answer up front. Voice search and the copyright check while posting already treat a failed
 * engine start as an error they report, so they fail quietly. Dependency-only: a bundle without
 * the strip leaves the engine alone.
 */
internal val speechEngineGuardPatch = bytecodePatch {
    execute {
        val generator = SpeechEngineGetInstanceFingerprint.classDef
        val engineType = SpeechEngineGetInstanceFingerprint.method.implementation?.instructions
            ?.filter { it.opcode == Opcode.NEW_INSTANCE }
            ?.map { ((it as ReferenceInstruction).reference as TypeReference).type }
            ?.singleOrNull()
            ?: throw PatchException("Speech engine getInstance no longer builds exactly one object")
        val engine = mutableClassDefBy(engineType)
        if (SPEECH_ENGINE_CLASS_DESCRIPTOR !in engine.interfaces) {
            throw PatchException("$engineType is no longer the speech engine")
        }
        val instanceFields = engine.fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
        val bridge = instanceFields.singleOrNull { it.type == SPEECH_BRIDGE_CLASS_DESCRIPTOR }
            ?: throw PatchException("Speech engine no longer keeps one bridge field")
        val handle = instanceFields.singleOrNull { it.type == "J" }
            ?: throw PatchException("Speech engine no longer keeps one handle field")

        engine.methods.single { it.name == "<clinit>" }.apply {
            val instructions = implementation!!.instructions.toList()
            val load = instructions.indices.singleOrNull { index ->
                val reference = (instructions[index] as? ReferenceInstruction)?.reference
                reference is MethodReference && reference.definingClass == SPEECH_LOADER_CLASS_DESCRIPTOR &&
                    reference.name == "load"
            } ?: throw PatchException("Speech engine class initializer no longer loads the libraries once")
            if (instructions.getOrNull(load + 1)?.opcode in MOVE_RESULTS) {
                throw PatchException("Speech engine class initializer now keeps the load result")
            }
            replaceInstruction(load, "nop")
        }

        engine.methods.single { it.name == "<init>" && it.parameterTypes.isEmpty() }.apply {
            val instructions = implementation!!.instructions.toList()
            val handleStart = instructions.indices.any { index ->
                val store = instructions[index]
                val reference = (store as? ReferenceInstruction)?.reference
                store.opcode == Opcode.IPUT_WIDE && reference is FieldReference && reference.name == handle.name &&
                    (instructions.getOrNull(index - 1) as? WideLiteralInstruction)?.wideLiteral == -1L
            }
            if (!handleStart) throw PatchException("Speech engine constructor no longer starts the handle at -1")
            val create = instructions.indices.singleOrNull { index ->
                val reference = (instructions[index] as? ReferenceInstruction)?.reference
                reference is MethodReference && reference.definingClass == SPEECH_LOADER_CLASS_DESCRIPTOR &&
                    reference.name == "createBridge"
            } ?: throw PatchException("Speech engine constructor no longer makes the bridge once")
            val result = instructions.getOrNull(create + 1)
            val store = instructions.getOrNull(create + 2)
            val register = (result as? OneRegisterInstruction)?.registerA ?: -1
            val storedField = ((store as? ReferenceInstruction)?.reference as? FieldReference)?.name
            if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || store?.opcode != Opcode.IPUT_OBJECT ||
                (store as TwoRegisterInstruction).registerA != register || storedField != bridge.name || register > 15
            ) {
                throw PatchException("Speech engine constructor no longer stores the new bridge straight away")
            }
            // const/4 takes v0 to v15; the bridge field is final, so the constructor still has to
            // write it, now with null.
            replaceInstruction(create + 1, "const/4 v$register, 0x0")
            replaceInstruction(create, "nop")
        }

        (engine.methods + generator.methods)
            .filter { it.name != "<init>" && it.name != "<clinit>" && reachesSpeechLibraries(it) }
            .forEach { method ->
                val (answer, registers) = noEngineAnswer(method)
                if (method.implementation!!.registerCount < registers) {
                    throw PatchException("Speech engine method ${method.name} has no room for its no-engine answer")
                }
                method.addInstructions(0, answer)
            }
    }
}
