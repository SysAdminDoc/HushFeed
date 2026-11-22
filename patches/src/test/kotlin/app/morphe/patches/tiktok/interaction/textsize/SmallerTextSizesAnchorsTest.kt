/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.textsize

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.takes
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21ih
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Smaller text sizes hooks (#476), held to each declared build: TikTok's text size manager,
 * its Text size page, the page's slider and the page's confirm callback.
 */
class SmallerTextSizesAnchorsTest {
    @Test
    fun `every hook point is found on every declared build and carries what the extension expects`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = TextSize(apk, version)
            val sites = text.sites()

            // The mode is what the loader reads from Keva under dynamic_font_mode and keeps.
            assertTrue("$version: the loader never writes ${sites.mode}", text.loader.body().any {
                it.opcode == Opcode.SPUT && it.getReference<FieldReference>()?.let(::descriptorOf) == sites.mode
            })

            // Both factor methods open on the mode and answer 1.0 for mode 0, the size the extra
            // stops sit below.
            assertEquals("$version: ${sites.factors.map { it.name }}", 2, sites.factors.size)
            sites.factors.forEach { factor ->
                val code = factor.body()
                val read = code[0]
                assertEquals("$version: ${factor.name} opens on the mode", Opcode.SGET, read.opcode)
                assertEquals(sites.mode, descriptorOf(read.getReference<FieldReference>()!!))
                val zero = code[1]
                assertEquals("$version: ${factor.name} tests the mode for 0 first", Opcode.IF_EQZ, zero.opcode)
                assertEquals((read as OneRegisterInstruction).registerA, (zero as OneRegisterInstruction).registerA)
                val answer = code[targetOf(code, 1)]
                assertEquals("$version: ${factor.name}'s mode 0 answer", Opcode.CONST_HIGH16, answer.opcode)
                assertEquals(1f, Float.fromBits((answer as NarrowLiteralInstruction).narrowLiteral), 0f)
                assertTrue("$version: ${factor.name} has a try block", factor.implementation!!.tryBlocks.isEmpty())
            }

            // The slider's top is TikTok's last mode, 3, written once in its constructor.
            val top = sites.sliderTop.single()
            assertEquals("$version: the top is written in the constructor", "<init>", top.method.name)
            val written = top.method.body()
            assertEquals(Opcode.IPUT, written[top.index].opcode)
            assertEquals(top.register, (written[top.index] as TwoRegisterInstruction).registerA)
            val three = written[top.index - 1]
            assertEquals("$version: the top isn't a constant", Opcode.CONST_4, three.opcode)
            assertEquals(top.register, (three as OneRegisterInstruction).registerA)
            assertEquals("$version: TikTok's slider has a new number of modes", 3, (three as NarrowLiteralInstruction).narrowLiteral)

            // onCreate puts the mode it read into the page's position, an int of the page.
            val create = sites.pageMode.method.body()
            val kept = create[sites.pageMode.index]
            assertEquals("$version: onCreate keeps the mode", Opcode.IPUT, kept.opcode)
            assertEquals(sites.pageMode.register, (kept as TwoRegisterInstruction).registerA)
            val position = kept.getReference<FieldReference>()!!
            assertEquals(TEXT_SIZE_PAGE, position.definingClass)
            assertEquals("I", position.type)

            // onViewCreated shows the preview of that position, through the method the hook maps.
            val shown = text.viewCreated.body()
            val preview = shown.indices.single { index ->
                shown[index].getReference<MethodReference>()?.let {
                    it.definingClass == TEXT_SIZE_PAGE && it.name == sites.preview.method.name &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("I")
                } == true
            }
            val shownPosition = shown.subList(0, preview).last {
                it.opcode == Opcode.IGET && (it as TwoRegisterInstruction).registerA ==
                    (shown[preview] as FiveRegisterInstruction).registerD
            }
            assertEquals("$version: the preview is shown for another field", descriptorOf(position),
                descriptorOf(shownPosition.getReference<FieldReference>()!!))
            assertEquals(sites.preview.method.implementation!!.registerCount - 1, sites.preview.register)

            // The confirm callback stores the picked position as the mode, then copies it into
            // the manager's mode field from the same register.
            val confirm = sites.store.method.body()
            val store = confirm[sites.store.index] as FiveRegisterInstruction
            assertEquals(sites.store.register, store.registerE)
            val copied = confirm[sites.store.index + 1]
            assertEquals("$version: the store isn't followed by the mode's copy", Opcode.SPUT, copied.opcode)
            assertEquals(sites.mode, descriptorOf(copied.getReference<FieldReference>()!!))
            assertEquals(sites.store.register, (copied as OneRegisterInstruction).registerA)
            val picked = confirm.subList(0, sites.store.index).last {
                it.opcode == Opcode.IGET && (it as TwoRegisterInstruction).registerA == sites.store.register
            }.getReference<FieldReference>()!!
            assertEquals("$version: the stored value isn't the page's picked position", TEXT_SIZE_PAGE, picked.definingClass)
        }
    }

    @Test
    fun `the hooks ask SmallerTextSizes and leave TikTok's code as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val sites = TextSize(apk, version).sites()

            sites.factors.forEach { clean ->
                val method = MutableMethod(clean)
                method.answerExtraStop(sites.mode)
                val code = method.body()
                assertEquals("$version: ${clean.name}", Opcode.SGET, code[0].opcode)
                assertEquals(sites.mode, descriptorOf(code[0].getReference<FieldReference>()!!))
                assertCall(code[1], "useExtraStop", "Z")
                assertEquals(Opcode.MOVE_RESULT, code[2].opcode)
                assertEquals(Opcode.IF_EQZ, code[3].opcode)
                assertEquals("$version: the guard doesn't fall through to TikTok's code", 7, targetOf(code, 3))
                assertCall(code[4], "extraStopFactor", "F")
                assertEquals(Opcode.MOVE_RESULT, code[5].opcode)
                assertEquals(Opcode.RETURN, code[6].opcode)
                setOf(0, 2, 5, 6).forEach { assertEquals(0, (code[it] as OneRegisterInstruction).registerA) }
                assertEquals("$version: ${clean.name} changed", codeOf(clean.body()), codeOf(code.drop(7)))
                assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
            }

            listOf(
                sites.sliderTop.single() to "sliderMax",
                sites.pageMode to "modeToPosition",
                sites.preview to "positionToMode",
                sites.store to "commitPosition",
            ).forEach { (clean, mapper) ->
                val method = MutableMethod(clean.method)
                clean.copy(method = method).mapThrough(mapper)
                val code = method.body()
                val call = code[clean.index]
                assertCall(call, mapper, "I")
                assertEquals("$version: $mapper", clean.register, (call as RegisterRangeInstruction).startRegister)
                assertEquals(1, call.registerCount)
                assertEquals(Opcode.MOVE_RESULT, code[clean.index + 1].opcode)
                assertEquals(clean.register, (code[clean.index + 1] as OneRegisterInstruction).registerA)
                val stock = clean.method.body()
                assertEquals("$version: $mapper changed TikTok's code", codeOf(stock),
                    codeOf(code.filterIndexed { index, _ -> index != clean.index && index != clean.index + 1 }))
                assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
            }
        }
    }

    /**
     * The extra stops raise the top in every constructor of the slider's class, so every slider
     * of that class gets them. On each declared build that's the Text size slider alone: it is
     * never built in code, one layout names it, no class extends it, nothing but its own code
     * writes its top, and only the Text size page and the slider's own touch listener cast to it.
     */
    @Test
    fun `the slider class is inflated and cast only for the Text size page`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val text = TextSize(apk, version)
            val sites = text.sites()
            val slider = sites.sliderTop.single().method.definingClass
            val top = descriptorOf(sites.sliderTop.single().method.body()[sites.sliderTop.single().index].getReference<FieldReference>()!!)
            val ownListeners = text.build.byType.getValue(slider).methods.flatMap { method ->
                method.body().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { it.getReference<TypeReference>()?.type }
            }.toSet()

            val extenders = text.build.byType.values.filter { it.superclass == slider }.map { it.type }
            assertEquals("$version: classes extend the slider", emptyList<String>(), extenders)

            val built = mutableListOf<String>()
            val casts = mutableListOf<String>()
            val topWrites = mutableListOf<String>()
            text.build.methods.forEach { (classDef, method) ->
                method.body().forEach { instruction ->
                    val where = "${classDef.type}->${method.name}"
                    when (instruction.opcode) {
                        Opcode.NEW_INSTANCE -> if (instruction.getReference<TypeReference>()?.type == slider) built += where
                        Opcode.CHECK_CAST -> if (instruction.getReference<TypeReference>()?.type == slider) {
                            if (classDef.type != slider && classDef.type != TEXT_SIZE_PAGE && classDef.type !in ownListeners) casts += where
                        }
                        Opcode.IPUT -> if (instruction.getReference<FieldReference>()?.let(::descriptorOf) == top &&
                            !(classDef.type == slider && method.name == "<init>")
                        ) topWrites += where
                        else -> Unit
                    }
                }
            }
            assertEquals("$version: the slider is built in code", emptyList<String>(), built)
            assertEquals("$version: something besides the Text size page casts to the slider", emptyList<String>(), casts)
            assertEquals("$version: something besides the constructor writes the top", emptyList<String>(), topWrites)
            assertTrue("$version: the Text size page no longer casts to the slider", text.viewCreated.body().any {
                it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == slider
            })

            val javaName = slider.removePrefix("L").removeSuffix(";").replace('/', '.')
            val layouts = ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("res/") && it.name.endsWith(".xml") }
                    .filter { entry -> zip.getInputStream(entry).use { it.readBytes() }.names(javaName) }
                    .map { it.name }
                    .toList()
            }
            assertEquals("$version: layouts naming $javaName: $layouts", 1, layouts.size)
        }
    }

    @Test
    fun `a factor that starts inside a try block is refused`() {
        Fixtures.forEachDeclared { apk ->
            val text = TextSize(apk, Fixtures.versionOf(apk))
            val factor = text.sites().factors.first()
            val manager = text.build.byType.getValue(factor.definingClass)
            val wrapped = manager.withMethod(factor, factor.insideTryBlock())
            val refused = assertThrows(PatchException::class.java) { text.sites(manager = wrapped) }
            assertTrue(refused.message, refused.message!!.contains("starts inside a try block"))
        }
    }

    @Test
    fun `a second writer of the slider's top, a jump onto its write or a later read of its register is refused`() {
        Fixtures.forEachDeclared { apk ->
            val text = TextSize(apk, Fixtures.versionOf(apk))
            val top = text.sites().sliderTop.single()
            val slider = text.build.byType.getValue(top.method.definingClass)
            val field = descriptorOf(top.method.body()[top.index].getReference<FieldReference>()!!)
            val p0 = top.method.implementation!!.registerCount - 3

            val setter = ImmutableMethod(
                slider.type, "setValueTo", listOf(ImmutableMethodParameter("I", null, null)),
                "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ).let { MutableMethod(it) }.apply {
                addInstructions(0, "iput p1, p0, $field\nreturn-void")
            }
            assertRefused(text, "writes the top too", slider = slider.withMethod(null, setter))

            val jumped = MutableMethod(top.method).apply {
                addInstructionsWithLabels(top.index - 1, "if-eqz p0, :top", ExternalLabel("top", getInstruction(top.index)))
            }
            assertRefused(text, "a jump lands on", slider = slider.withMethod(top.method, jumped))

            val reread = MutableMethod(top.method).apply {
                addInstruction(top.index + 1, "iput v${top.register}, v$p0, $field")
            }
            assertRefused(text, "again after writing its top", slider = slider.withMethod(top.method, reread))
        }
    }

    @Test
    fun `a jump back to the start of the preview or onto the confirm store is refused`() {
        Fixtures.forEachDeclared { apk ->
            val text = TextSize(apk, Fixtures.versionOf(apk))
            val sites = text.sites()

            val preview = sites.preview.method
            val looped = MutableMethod(preview).apply {
                addInstructionsWithLabels(1, "if-nez p1, :start", ExternalLabel("start", getInstruction(0)))
            }
            val page = text.build.byType.getValue(TEXT_SIZE_PAGE)
            assertRefused(text, "the start of", page = page.withMethod(preview, looped))

            val store = sites.store
            val confirm = MutableMethod(store.method).apply {
                addInstructionsWithLabels(store.index - 1, "if-eqz p0, :store", ExternalLabel("store", getInstruction(store.index)))
            }
            assertRefused(text, "confirm callback's store", confirm = confirm)
        }
    }

    private fun assertRefused(
        text: TextSize,
        why: String,
        slider: ClassDef? = null,
        page: ClassDef? = null,
        confirm: Method? = null,
    ) {
        val refused = assertThrows(PatchException::class.java) {
            text.sites(slider = slider, page = page, confirm = confirm)
        }
        assertTrue("expected '$why' in: ${refused.message}", refused.message!!.contains(why))
    }

    private fun assertCall(instruction: Instruction, name: String, returns: String) {
        val call = instruction.getReference<MethodReference>()
        assertNotNull("not a call: ${instruction.opcode}", call)
        assertEquals(SMALLER_TEXT_SIZES, call!!.definingClass)
        assertEquals(name, call.name)
        assertEquals(returns, call.returnType)
        assertTrue(instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE)
    }

    /** One build's text size classes, with any of them swapped for a test's own copy. */
    private class TextSize(apk: File, val version: String) {
        val build = Build(apk)
        val loader: Method
        val manager: ClassDef
        val confirm: Method
        val page: ClassDef = build.byType.getValue(TEXT_SIZE_PAGE)
        val create: Method = page.methods.single { TextSizePageCreateFingerprint.takes(it, page) }
        val viewCreated: Method = page.methods.single { TextSizePageViewCreatedFingerprint.takes(it, page) }

        init {
            val loaders = mutableListOf<Pair<ClassDef, Method>>()
            val confirms = mutableListOf<Method>()
            build.methods.forEach { (classDef, method) ->
                if (TextSizeModeLoadFingerprint.takes(method, classDef)) loaders += classDef to method
                if (TextSizeConfirmFingerprint.takes(method, classDef)) confirms += method
            }
            assertEquals("$version: loaders ${loaders.map { it.first.type }}", 1, loaders.size)
            assertEquals("$version: confirm callbacks", 1, confirms.size)
            manager = loaders.single().first
            loader = loaders.single().second
            confirm = confirms.single()
        }

        fun sites(
            manager: ClassDef? = null,
            slider: ClassDef? = null,
            page: ClassDef? = null,
            confirm: Method? = null,
        ): TextSizeSites = textSizeSites(
            manager = manager ?: this.manager,
            page = page ?: this.page,
            create = create,
            viewCreated = viewCreated,
            confirm = confirm ?: this.confirm,
            classBy = { type -> slider?.takeIf { it.type == type } ?: build.byType.getValue(type) },
        )
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

private fun descriptorOf(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

/** The class with [old] taken out (by signature) and [new] put in; either may be null. */
private fun ClassDef.withMethod(old: Method?, new: Method?): ClassDef = ImmutableClassDef(
    type, accessFlags, superclass, interfaces, sourceFile, annotations, fields,
    methods.filter { it != old } + listOfNotNull(new),
)

/** The method with its whole body inside a catch-all try block, the handler returning 1.0. */
private fun Method.insideTryBlock(): Method {
    val body = implementation!!
    val instructions = body.instructions.toList()
    val units = instructions.sumOf { it.codeUnits }
    return ImmutableMethod(
        definingClass, name, parameters, returnType, accessFlags, annotations, hiddenApiRestrictions,
        ImmutableMethodImplementation(
            body.registerCount,
            instructions + listOf(
                ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 0),
                ImmutableInstruction21ih(Opcode.CONST_HIGH16, 0, 0x3f800000),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
            listOf(ImmutableTryBlock(0, units, listOf(ImmutableExceptionHandler(null, units)))),
            null,
        ),
    )
}

private fun targetOf(code: List<Instruction>, at: Int): Int {
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    return addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
}

/** Opcodes with payload alignment nops left out and a widened goto read as a goto. */
private fun codeOf(instructions: List<Instruction>): List<Opcode> = instructions
    .filter { it.opcode != Opcode.NOP }
    .map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }

/** Whether these bytes, a compiled XML file, hold [name] in its UTF-8 or UTF-16 string pool. */
private fun ByteArray.names(name: String): Boolean =
    holds(name.toByteArray(Charsets.UTF_8)) || holds(name.toByteArray(Charsets.UTF_16LE))

private fun ByteArray.holds(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
