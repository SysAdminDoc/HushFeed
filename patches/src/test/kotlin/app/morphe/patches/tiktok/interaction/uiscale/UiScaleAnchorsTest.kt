/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.uiscale

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.textsize.TextSizeModeLoadFingerprint
import app.morphe.takes
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.bodyIsBuilt
import app.morphe.util.findMutableMethodOf
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
 * What UI scale hooks (#477), held to each declared build: every read of TikTok's text size
 * factor in a method that turns it into a display density, the manager's own two and the
 * per-activity context wrapper's.
 */
class UiScaleAnchorsTest {
    @Test
    fun `both density rewrites read the text size factor once and only divide by it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = Manager(apk, version)
            val reads = densityFactorReads(text.manager, text.build.classes).filter { it.method.definingClass == text.manager.type }
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
    fun `every read that becomes an activity's density is scaled, not only the manager's`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = Manager(apk, version)
            val factor = text.factor()
            // Worked out here from the build alone: each call of the factor in a method that hands
            // Android a configuration built from it. On 47.9.3 that's X.03aF's LIZLLL and LJFF and
            // the two in X.03Ji.invoke, the wrapper every activity's context goes through.
            val expected = text.build.methods.flatMap { (_, method) ->
                val code = method.body()
                if (code.none { it.isConfigurationHandOff() }) return@flatMap emptySequence()
                code.indices.filter { code[it].getReference<MethodReference>()?.toString() == factor }
                    .map { "${method.definingClass}->${method.name}@$it" }.asSequence()
            }.toSet()
            val activityWrappers = text.build.methods.filter { (classDef, method) ->
                classDef.type != text.manager.type && method.body().let { code ->
                    code.any { it.getReference<MethodReference>()?.name == "createConfigurationContext" } &&
                        code.any { it.getReference<MethodReference>()?.toString() == factor }
                }
            }.toList()
            assertTrue("$version: no activity context is built from the factor any more", activityWrappers.isNotEmpty())

            val scaled = densityFactorReads(text.manager, text.build.classes)
                .map { "${it.method.definingClass}->${it.method.name}@${it.index - 2}" }.toSet()
            assertEquals("$version: reads that set a density", expected, scaled)
        }
    }

    @Test
    fun `two reads in one method are both scaled where they were read`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = Manager(apk, version)
            val byMethod = densityFactorReads(text.manager, text.build.classes).groupBy { it.method }
            val (clean, reads) = byMethod.entries.firstOrNull { it.value.size > 1 }
                ?: throw AssertionError("$version: no density rewrite reads the factor twice")
            val method = MutableMethod(clean)
            reads.scaleAll { method }
            val code = method.body()
            val calls = code.indices.filter { code[it].getReference<MethodReference>()?.name == "scaleDensityFactor" }
            assertEquals("$version: ${clean.definingClass}->${clean.name}", reads.size, calls.size)
            // Each scale call sits right after one of TikTok's reads, on the register that read filled.
            reads.sortedBy { it.index }.forEachIndexed { order, read ->
                val at = calls[order]
                assertEquals(text.factor(), code[at - 2].getReference<MethodReference>().toString())
                assertEquals(read.register, (code[at - 1] as OneRegisterInstruction).registerA)
                assertEquals(read.register, (code[at] as RegisterRangeInstruction).startRegister)
                assertEquals(read.register, (code[at + 1] as OneRegisterInstruction).registerA)
            }
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }

    @Test
    fun `the hook scales the factor right after it is read and leaves TikTok's code as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = Manager(apk, version)
            densityFactorReads(text.manager, text.build.classes).forEach { clean ->
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
            val read = densityFactorReads(text.manager, text.build.classes).first { it.method.definingClass == text.manager.type }
            val call = read.method.body()[read.index - 2].getReference<MethodReference>()!!
            val register = read.register

            val twice = MutableMethod(read.method).apply {
                addInstructions(read.index, "invoke-static {}, $call\nmove-result v$register")
            }
            assertRefused(text, text.manager.withMethod(read.method, twice), "reads the factor 2 times")

            val moved = MutableMethod(read.method).apply {
                addInstruction(read.index, "move v$register, v$register")
            }
            assertRefused(text, text.manager.withMethod(read.method, moved), "uses the factor as more than a float")
        }
    }

    @Test
    fun `a build whose activities no longer take the factor is refused`() {
        Fixtures.forEachDeclared { apk ->
            val text = Manager(apk, Fixtures.versionOf(apk))
            val wrappers = densityFactorReads(text.manager, text.build.classes)
                .map { it.method.definingClass }.filter { it != text.manager.type }.toSet()
            assertTrue(wrappers.isNotEmpty())
            val without = text.build.classes.filter { it.type !in wrappers }
            val refused = assertThrows(PatchException::class.java) { densityFactorReads(text.manager, without) }
            assertTrue(refused.message, refused.message!!.contains("no activity"))
        }
    }

    /**
     * By the time UI scale runs, earlier patches have made thousands of TikTok's classes mutable,
     * and the patcher keeps every method body anything reads off one of those until it writes the
     * dex. The scan reads every method TikTok has, so reading them plainly kept most of a full run's
     * heap and Morphe Manager ran out of memory on 4 GB phones (#497, #460, #310). The scan has to
     * find the same reads in mutable classes and build only the bodies it then scales.
     */
    @Test
    fun `the scan builds no body in classes earlier patches made mutable, only the ones it scales`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = Manager(apk, version)
            val plain = densityFactorReads(text.manager, text.build.classes)
            // The manager, every class a read is in, and a few thousand others, as earlier patches
            // leave them.
            val holders = plain.map { it.method.definingClass }.toSet() + text.manager.type
            val chosen = text.build.classes.filter { it.type in holders } +
                text.build.classes.filter { it.type !in holders }.take(3000)
            val mutable = chosen.associate { it.type to MutableClass(it) }
            val classes = text.build.classes.map { mutable[it.type] ?: it }

            val reads = densityFactorReads(mutable.getValue(text.manager.type), classes)
            assertEquals("$version: reads found in mutable classes", plain.map { it.key() }.toSet(), reads.map { it.key() }.toSet())
            val methods = mutable.values.flatMap { it.methods }
            assertTrue(
                "$version: this patcher no longer says whether a body was built, so reads build them again",
                methods.all { bodyIsBuilt(it) != null },
            )
            assertEquals("$version: bodies the scan built", emptyList<String>(), methods.filter { bodyIsBuilt(it) == true }.map { it.toString() })

            reads.scaleAll { mutable.getValue(it.definingClass).findMutableMethodOf(it) }
            val scaled = plain.map { "${it.method.definingClass}->${it.method.name}" }.toSet()
            assertEquals(
                "$version: bodies built once the reads are scaled",
                scaled,
                methods.filter { bodyIsBuilt(it) == true }.map { "${it.definingClass}->${it.name}" }.toSet(),
            )
        }
    }

    private fun assertRefused(text: Manager, manager: ClassDef, why: String) {
        val refused = assertThrows(PatchException::class.java) { densityFactorReads(manager, text.build.classes) }
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

        /** The factor the manager's startup rewrite reads, as `Lcls;->name()F`. */
        fun factor(): String {
            val rewrite = manager.methods.first { method -> method.body().any { it.getReference<MethodReference>()?.name == "updateConfiguration" } }
            return rewrite.body().mapNotNull { it.getReference<MethodReference>() }
                .single { it.definingClass == manager.type && it.returnType == "F" && it.parameterTypes.isEmpty() }.toString()
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

        val classes: Sequence<ClassDef>
            get() = byType.values.asSequence()

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = classes.flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

/** A read by where it is: the method's signature, the index the scale goes in at and the register. */
private fun FactorRead.key() = "$method@$index/v$register"

/** A call that gives Android a configuration: Resources.updateConfiguration or Context.createConfigurationContext. */
private fun Instruction.isConfigurationHandOff(): Boolean = getReference<MethodReference>()?.let {
    (it.definingClass == "Landroid/content/res/Resources;" && it.name == "updateConfiguration") || it.name == "createConfigurationContext"
} == true

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
