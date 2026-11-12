/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private const val SPEECH_LOG_TRACKER = "Lcom/bytedance/speech/speechengine/log_tracker/SpeechLogTracker;"

/** A dynamic feature module's code: `lib/arm64-v8a/libdex_df_ship.so`. */
private val FEATURE_DEX = Regex("""lib/[^/]+/libdex_[^/]+\.so""")

private val RETURNS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT)

/**
 * Issue #213, held to every declared build: the speech engine the voice strip's guard rewrites,
 * the methods it gives a "no engine" answer, and that nothing else in TikTok loads the emptied
 * libraries.
 */
class SpeechEngineGuardTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `the speech engine is built in one place and only it reaches the speech libraries`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk)
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .associateBy { it.type }
            val taken = classes.values.flatMap { classDef ->
                classDef.methods.filter { SpeechEngineGetInstanceFingerprint.takes(it, classDef) }
            }
            assertEquals("getInstance matches", 1, taken.size)
            val engineType = taken.single().implementation!!.instructions
                .filter { it.opcode == Opcode.NEW_INSTANCE }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                .single()
            val engine = checkNotNull(classes[engineType]) { "no $engineType" }
            val generator = checkNotNull(classes[SPEECH_GENERATOR_CLASS_DESCRIPTOR])
            assertTrue("$engineType implements the engine", SPEECH_ENGINE_CLASS_DESCRIPTOR in engine.interfaces)
            val instanceFields = engine.fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
            val bridge = instanceFields.single { it.type == SPEECH_BRIDGE_CLASS_DESCRIPTOR }
            val handle = instanceFields.single { it.type == "J" }

            // The class initializer's load is the only one the guard turns off, and nothing keeps
            // its result.
            val initializer = engine.methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
            val loads = initializer.indices.filter { initializer[it].calls("load") }
            assertEquals("loads in the class initializer", 1, loads.size)
            assertTrue("the load result is kept", initializer.getOrNull(loads.single() + 1)?.opcode !in
                setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT))

            // The constructor starts the handle at -1 and stores the new bridge straight away.
            val constructor = engine.methods.single { it.name == "<init>" && it.parameterTypes.isEmpty() }
                .implementation!!.instructions.toList()
            assertTrue("the handle starts at -1", constructor.indices.any { index ->
                constructor[index].opcode == Opcode.IPUT_WIDE && constructor[index].field()?.name == handle.name &&
                    (constructor.getOrNull(index - 1) as? WideLiteralInstruction)?.wideLiteral == -1L
            })
            val creates = constructor.indices.filter { constructor[it].calls("createBridge") }
            assertEquals("bridges made in the constructor", 1, creates.size)
            val result = constructor[creates.single() + 1]
            val store = constructor[creates.single() + 2]
            assertEquals(Opcode.MOVE_RESULT_OBJECT, result.opcode)
            assertEquals(Opcode.IPUT_OBJECT, store.opcode)
            assertEquals(bridge.name, store.field()?.name)
            val register = (result as OneRegisterInstruction).registerA
            assertEquals(register, (store as TwoRegisterInstruction).registerA)
            assertTrue("v$register doesn't fit const/4", register <= 15)

            // The methods that reach the libraries without checking the handle, which the guard
            // answers up front. A new one here needs its own no-engine answer reviewed.
            val guarded = (engine.methods.map { engine to it } + generator.methods.map { generator to it })
                .filter { (_, method) -> method.name != "<init>" && method.name != "<clinit>" && reachesSpeechLibraries(method) }
                .map { (owner, method) -> "${if (owner == engine) "engine" else "generator"}.${method.signature()}" }
                .toSet()
            assertEquals(
                setOf(
                    "engine.createEngine()J",
                    "engine.getVersion()Ljava/lang/String;",
                    "engine.isEngineSupported(Ljava/lang/String;)Z",
                    "generator.isEngineSupported(Ljava/lang/String;)Z",
                    "generator.initMonitor(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;" +
                        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                ),
                guarded,
            )

            // Every other engine method that reads the bridge first compares the handle with -1
            // and branches to the bridge only when they differ, and the -1 side returns without
            // touching it, so a -1 handle keeps it off the null bridge.
            for (method in engine.methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                val firstBridgeRead = instructions.indexOfFirst {
                    it.opcode == Opcode.IGET_OBJECT && it.field()?.name == bridge.name
                }
                if (firstBridgeRead < 0 || reachesSpeechLibraries(method)) continue
                val branch = (1 until firstBridgeRead).firstOrNull { index ->
                    val compare = instructions[index - 1] as? ThreeRegisterInstruction
                    val ifNez = instructions[index]
                    val before = instructions.subList(0, index - 1)
                    val handleRegister = before.lastOrNull { it.opcode == Opcode.IGET_WIDE && it.field()?.name == handle.name }
                        ?.let { (it as TwoRegisterInstruction).registerA }
                    val minusOneRegister = before.lastOrNull {
                        it.opcode in setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE) &&
                            (it as WideLiteralInstruction).wideLiteral == -1L
                    }?.let { (it as OneRegisterInstruction).registerA }
                    compare != null && compare.opcode == Opcode.CMP_LONG && ifNez.opcode == Opcode.IF_NEZ &&
                        (ifNez as OneRegisterInstruction).registerA == compare.registerA &&
                        setOf(compare.registerB, compare.registerC) == setOf(handleRegister, minusOneRegister)
                }
                assertNotNull("${method.signature()} reads the bridge before checking the handle against -1", branch)
                val fallThrough = instructions.subList(branch!! + 1, instructions.size)
                val end = fallThrough.indexOfFirst { it.opcode in RETURNS }
                assertTrue("${method.signature()}: the -1 side never returns", end >= 0)
                assertTrue("${method.signature()}: the -1 side branches or reads the bridge before returning",
                    fallThrough.subList(0, end).none {
                        it is OffsetInstruction || (it.opcode == Opcode.IGET_OBJECT && it.field()?.name == bridge.name)
                    })
            }

            // Nothing else loads the libraries or makes a bridge, and the log tracker, whose class
            // initializer loads them too, is never used. The voice callers sit in dynamic feature
            // modules (lib/<abi>/libdex_<module>.so, a zip holding its dex), so those count too.
            val owners = setOf(engineType, SPEECH_GENERATOR_CLASS_DESCRIPTOR, SPEECH_LOADER_CLASS_DESCRIPTOR, SPEECH_LOG_TRACKER)
            val modules = featureModuleClasses(apk)
            assertTrue("no feature module asks the generator for the engine, so the voice callers went unread",
                modules.any { classDef ->
                    classDef.methods.any { method ->
                        method.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == SPEECH_GENERATOR_CLASS_DESCRIPTOR
                        } == true
                    }
                })
            val outside = mutableListOf<String>()
            for (classDef in classes.values + modules) {
                if (classDef.type in owners) continue
                for (method in classDef.methods) {
                    for (instruction in method.implementation?.instructions ?: continue) {
                        val reference = (instruction as? ReferenceInstruction)?.reference
                        val touchesTracker = when (reference) {
                            is MethodReference -> reference.definingClass == SPEECH_LOG_TRACKER
                            is FieldReference -> reference.definingClass == SPEECH_LOG_TRACKER
                            is TypeReference -> reference.type == SPEECH_LOG_TRACKER
                            else -> false
                        }
                        if (instruction.calls("load") || instruction.calls("createBridge") || touchesTracker) {
                            outside += "${classDef.type}->${method.signature()}"
                        }
                    }
                }
            }
            assertEquals("speech library users outside the engine", emptyList<String>(), outside)
        }
    }

    @Test
    fun `the guard leaves the engine without its libraries on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            var checked = false
            val probe = patch(name = "speech engine guard probe", default = false) {
                dependsOn(speechEngineGuardPatch)
                execute {
                    // The proxies the guard edited, not the input's classes.
                    val generator = mutableClassDefBy(SPEECH_GENERATOR_CLASS_DESCRIPTOR)
                    val engineType = generator.methods
                        .single { it.name == "getInstance" && it.parameterTypes.isEmpty() && it.returnType == SPEECH_ENGINE_CLASS_DESCRIPTOR }
                        .implementation!!.instructions
                        .single { it.opcode == Opcode.NEW_INSTANCE }
                        .let { ((it as ReferenceInstruction).reference as TypeReference).type }
                    val engine = mutableClassDefBy(engineType)
                    val bridge = engine.fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == SPEECH_BRIDGE_CLASS_DESCRIPTOR }

                    val initializer = engine.methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
                    assertTrue("the class initializer still loads", initializer.none { it.calls("load") })
                    assertTrue("the load left no nop", initializer.any { it.opcode == Opcode.NOP })

                    val constructor = engine.methods.single { it.name == "<init>" && it.parameterTypes.isEmpty() }
                        .implementation!!.instructions.toList()
                    assertTrue("the constructor still makes a bridge", constructor.none { it.calls("createBridge") })
                    val store = constructor.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.field()?.name == bridge.name }
                    val nulled = constructor[store - 1]
                    assertEquals("the bridge is stored from a constant", Opcode.CONST_4, nulled.opcode)
                    assertEquals("the bridge is null", 0, (nulled as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals((nulled as OneRegisterInstruction).registerA, (constructor[store] as TwoRegisterInstruction).registerA)

                    fun answer(owner: ClassDef, signature: String) = owner.methods.single { it.signature() == signature }
                        .implementation!!.instructions.take(2).toList()
                    assertEquals(listOf(Opcode.CONST_WIDE_16, Opcode.RETURN_WIDE), answer(engine, "createEngine()J").map { it.opcode })
                    assertEquals(-1L, (answer(engine, "createEngine()J").first() as WideLiteralInstruction).wideLiteral)
                    assertEquals(listOf(Opcode.CONST_STRING, Opcode.RETURN_OBJECT),
                        answer(engine, "getVersion()Ljava/lang/String;").map { it.opcode })
                    for (owner in listOf(engine, generator)) {
                        val supported = answer(owner, "isEngineSupported(Ljava/lang/String;)Z")
                        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN), supported.map { it.opcode })
                        assertEquals("isEngineSupported answers no", 0, (supported.first() as NarrowLiteralInstruction).narrowLiteral)
                    }
                    val monitor = answer(generator, "initMonitor(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;" +
                        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
                    assertEquals(Opcode.RETURN_VOID, monitor.first().opcode)
                    // The shorter overload only hands on to the guarded one, so it stays as it was.
                    val shorterMonitor = answer(generator, "initMonitor(Landroid/content/Context;Ljava/lang/String;" +
                        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
                    assertTrue("the shorter initMonitor got an answer of its own", shorterMonitor.first().opcode != Opcode.RETURN_VOID)
                    checked = true
                }
            }
            SpeechEngineGetInstanceFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            assertTrue("${apk.name}: the guard probe never ran", checked)
        }
    }

    @Test
    fun `the voice strip brings the guard, and only the voice strip`() {
        assertTrue("the voice strip doesn't bring the guard", speechEngineGuardPatch in voiceToolsDebloatPatch.dependencies)
        assertNull("the guard shows up as a patch of its own", speechEngineGuardPatch.name)
        assertTrue("the guard pulls in the extension", sharedExtensionPatch !in speechEngineGuardPatch.dependencies)
        for (patch in listOf(coreAssetDebloatPatch, studioCreationDebloatPatch, liveStreamSuiteOptimizerPatch,
            p2pRelayBlockerPatch, languagePackPurgerPatch)) {
            assertTrue("${patch.name} brings the speech engine guard", speechEngineGuardPatch !in patch.dependencies)
        }
    }

    /** The classes of every dynamic feature module's dex, each module once (every ABI ships the same file). */
    private fun featureModuleClasses(apk: File): List<ClassDef> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { FEATURE_DEX.matches(it.name) }
            .distinctBy { it.name.substringAfterLast('/') }.toList()
            .flatMap { module ->
                ZipInputStream(zip.getInputStream(module)).use { inner ->
                    generateSequence { inner.nextEntry }.filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                        .flatMap { DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(inner.readBytes())).classes }
                        .toList()
                }
            }
    }

    private fun Instruction.calls(name: String): Boolean {
        val reference = (this as? ReferenceInstruction)?.reference
        return reference is MethodReference && reference.definingClass == SPEECH_LOADER_CLASS_DESCRIPTOR && reference.name == name
    }

    private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Method.signature() = "$name(${parameterTypes.joinToString("")})$returnType"
}
