/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.uiscale

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.textsize.TextSizeModeLoadFingerprint
import app.morphe.takes
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What UI scale hooks (#477), held to each declared build: the read of TikTok's text size factor
 * in each of its density rewrites.
 */
class UiScaleAnchorsTest {
    @Test
    fun `both density rewrites read the text size factor once and only divide by it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = Manager(apk, version)
            val reads = densityFactorReads(text.manager)
            assertEquals("$version: ${reads.map { it.method.name }}", 2, reads.size)

            val factors = reads.map { read ->
                val code = read.method.body()
                val call = code[read.index - 2].getReference<MethodReference>()!!
                assertEquals(text.manager.type, call.definingClass)
                // It's the text size factor: it opens on the manager's mode, the int the loader keeps.
                val factor = text.manager.methods.single { it.name == call.name && it.parameterTypes.isEmpty() && it.returnType == "F" }
                val mode = factor.body().first().getReference<FieldReference>()!!
                assertEquals("$version: ${factor.name} doesn't open on the mode", Opcode.SGET, factor.body().first().opcode)
                assertTrue("$version: the loader doesn't keep ${mode.name}", text.loader.body().any {
                    it.opcode == Opcode.SPUT && it.getReference<FieldReference>()?.toString() == mode.toString()
                })
                // The density goes back into TikTok's resources after the factor is read.
                assertTrue("$version: ${read.method.name} updates no configuration after the read", code.drop(read.index).any {
                    it.getReference<MethodReference>()?.name == "updateConfiguration"
                })
                call.name
            }
            assertEquals("$version: the rewrites read different factors", 1, factors.distinct().size)

            // The startup rewrite runs on every start: TikTok's TuxInitTask calls it.
            val callers = text.build.methods.filter { (classDef, method) ->
                classDef.type.endsWith("/TuxInitTask;") && method.body().any { instruction ->
                    instruction.getReference<MethodReference>()?.let { call ->
                        call.definingClass == text.manager.type && reads.any { it.method.name == call.name }
                    } == true
                }
            }.toList()
            assertTrue("$version: TuxInitTask no longer runs a density rewrite", callers.isNotEmpty())
        }
    }

    @Test
    fun `the hook scales the factor right after it is read and leaves TikTok's code as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            densityFactorReads(Manager(apk, version).manager).forEach { clean ->
                val method = MutableMethod(clean.method)
                clean.copy(method = method).scale()
                val code = method.body()
                val call = code[clean.index]
                val target = call.getReference<MethodReference>()!!
                assertEquals("$version: ${clean.method.name}", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                assertEquals(UI_SCALE, target.definingClass)
                assertEquals("scaleDensityFactor", target.name)
                assertEquals("F", target.returnType)
                assertEquals(listOf("F"), target.parameterTypes.map { it.toString() })
                assertEquals(clean.register, (call as RegisterRangeInstruction).startRegister)
                assertEquals(1, call.registerCount)
                assertEquals(Opcode.MOVE_RESULT, code[clean.index + 1].opcode)
                assertEquals(clean.register, (code[clean.index + 1] as OneRegisterInstruction).registerA)
                // TikTok's own read sits right before, so nothing reaches the factor unscaled.
                assertEquals(Opcode.MOVE_RESULT, code[clean.index - 1].opcode)
                assertEquals(clean.register, (code[clean.index - 1] as OneRegisterInstruction).registerA)
                assertEquals("$version: ${clean.method.name} changed", codeOf(clean.method.body()),
                    codeOf(code.filterIndexed { index, _ -> index != clean.index && index != clean.index + 1 }))
                assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
            }
        }
    }

    @Test
    fun `a rewrite that reads the factor twice or uses it as more than a float is refused`() {
        Fixtures.forEachDeclared { apk ->
            val text = Manager(apk, Fixtures.versionOf(apk))
            val read = densityFactorReads(text.manager).first()
            val call = read.method.body()[read.index - 2].getReference<MethodReference>()!!
            val register = read.register

            val twice = MutableMethod(read.method).apply {
                addInstructions(read.index, "invoke-static {}, $call\nmove-result v$register")
            }
            assertRefused(text.manager.withMethod(read.method, twice), "reads the factor 2 times")

            val moved = MutableMethod(read.method).apply {
                addInstruction(read.index, "move v$register, v$register")
            }
            assertRefused(text.manager.withMethod(read.method, moved), "uses the factor as more than a float")
        }
    }

    private fun assertRefused(manager: ClassDef, why: String) {
        val refused = assertThrows(PatchException::class.java) { densityFactorReads(manager) }
        assertTrue("expected '$why' in: ${refused.message}", refused.message!!.contains(why))
    }

    private class Manager(apk: File, version: String) {
        val build = Build(apk)
        val manager: ClassDef
        val loader: Method

        init {
            val loaders = build.methods.filter { (classDef, method) -> TextSizeModeLoadFingerprint.takes(method, classDef) }.toList()
            assertEquals("$version: loaders ${loaders.map { it.first.type }}", 1, loaders.size)
            manager = loaders.single().first
            loader = loaders.single().second
        }
    }

    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

/** The class with [old] swapped, by signature, for [new]. */
private fun ClassDef.withMethod(old: Method, new: Method): ClassDef = ImmutableClassDef(
    type, accessFlags, superclass, interfaces, sourceFile, annotations, fields,
    methods.filter { it != old } + new,
)

/** Opcodes with payload alignment nops left out and a widened goto read as a goto. */
private fun codeOf(instructions: List<Instruction>): List<Opcode> = instructions
    .filter { it.opcode != Opcode.NOP }
    .map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }
