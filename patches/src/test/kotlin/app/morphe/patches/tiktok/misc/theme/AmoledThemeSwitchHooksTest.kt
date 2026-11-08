/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.takes
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.InputStream
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The AMOLED dark mode switch (#372) on each declared build: every theme TikTok builds passes
 * through the extension, so turning the switch off can put TikTok's own grays back on screens,
 * sheets and dialogs alike. The counts come straight from the fixture's dex, and every hook is
 * read back from the patched methods: the call it replaced or follows, and the registers it takes.
 * The probe merges the built extension, so the hooks' targets are checked to exist as well.
 */
class AmoledThemeSwitchHooksTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `every theme TikTok builds reaches the switch on each declared build`() {
        assertTrue("no built extension on the test classpath", OWNER.getResource(EXTENSION) != null)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val stock = StockCounts()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) stock.count(classDef, method)
                }
            }
            assertEquals("$version: AppCompatActivity setThemes ${stock.activities}", 1, stock.activities.size)
            assertTrue("$version: applyStyle calls, ${stock.applyStyles}", stock.applyStyles > 1000)
            assertTrue("$version: setTheme calls, ${stock.setThemes}", stock.setThemes > 30)
            assertTrue("$version: new ContextThemeWrappers, ${stock.wrappers}", stock.wrappers > 300)
            assertTrue("$version: Dialog constructor calls, ${stock.dialogs}", stock.dialogs >= 10)

            var checked = false
            val probe = bytecodePatch(name = "amoled switch probe") {
                extendWith(Supplier<InputStream> { OWNER.getResourceAsStream(EXTENSION)!! })
                execute {
                    assertHookTargets()
                    AppCompatSetThemeFingerprint.clearMatch()
                    val sites = hookAmoledThemeSwitch()
                    AppCompatSetThemeFingerprint.clearMatch()

                    assertEquals("$version: every applyStyle call", stock.applyStyles, sites.applyStyles.size)
                    assertEquals("$version: every setTheme call", stock.setThemes, sites.setThemes.size)
                    assertTrue(
                        "$version: ${sites.wrappers.size} of ${stock.wrappers} wrappers",
                        sites.wrappers.size > 300 && sites.wrappers.size <= stock.wrappers,
                    )
                    assertTrue(
                        "$version: ${sites.dialogs.size} of ${stock.dialogs} dialogs",
                        sites.dialogs.size >= 10 && sites.dialogs.size <= stock.dialogs,
                    )
                    assertActivityHook(version, stock.activityCode.map(::kind), sites.activitySetTheme)

                    val touched = LinkedHashMap<Pair<String, String>, Pair<ClassDef, Method>>()
                    fun touch(owner: ClassDef, method: Method) {
                        touched.getOrPut(owner.type to method.toString()) { owner to method }
                    }
                    sites.applyStyles.forEach { touch(it.owner, it.method) }
                    sites.setThemes.forEach { touch(it.owner, it.method) }
                    sites.wrappers.forEach { touch(it.owner, it.method) }
                    sites.dialogs.forEach { touch(it.owner, it.method) }
                    val applyStyles = sites.applyStyles.groupBy { it.owner.type to it.method.toString() }
                    val setThemes = sites.setThemes.groupBy { it.owner.type to it.method.toString() }
                    val wrappers = sites.wrappers.groupBy { it.owner.type to it.method.toString() }
                    val dialogs = sites.dialogs.groupBy { it.owner.type to it.method.toString() }
                    for ((key, value) in touched) {
                        val (owner, method) = value
                        val code = mutableClassDefBy(owner.type).findMutableMethodOf(method).implementation!!.instructions.toList()
                        val where = "$version ${owner.type}->${method.name}"
                        assertSwapped(
                            where, code, setOf(THEME_APPLY_STYLE), THEME_APPLY_STYLE_HOOK,
                            applyStyles[key].orEmpty().map { it.registers to it.ranged },
                        )
                        assertSwapped(
                            where, code, SET_THEME_TARGETS, SET_THEME_HOOK,
                            setThemes[key].orEmpty().map { it.registers to it.ranged },
                        )
                        assertWrappers(where, code, wrappers[key].orEmpty().size)
                        assertDialogHooks(where, code, dialogs[key].orEmpty().size)
                    }
                    checked = true
                }
            }
            AppCompatSetThemeFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            AppCompatSetThemeFingerprint.clearMatch()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    /** What the fixture's own dex holds, before anything is patched. */
    private class StockCounts {
        val activities = mutableListOf<String>()

        /** AppCompatActivity's setTheme as the fixture ships it, NOPs left out. */
        val activityCode = mutableListOf<Instruction>()
        var applyStyles = 0
        var setThemes = 0
        var wrappers = 0
        var dialogs = 0

        fun count(classDef: ClassDef, method: Method) {
            val instructions = method.implementation?.instructions ?: return
            if (AppCompatSetThemeFingerprint.takes(method, classDef)) {
                activities += classDef.type
                activityCode += instructions.filter { it.opcode != Opcode.NOP }
            }
            for (instruction in instructions) {
                when (instruction.opcode) {
                    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> {
                        val reference = instruction.getReference<MethodReference>()?.toString()
                        if (reference == THEME_APPLY_STYLE) applyStyles++
                        if (reference in SET_THEME_TARGETS) setThemes++
                    }
                    Opcode.NEW_INSTANCE ->
                        if (instruction.getReference<TypeReference>()?.type == CONTEXT_THEME_WRAPPER_TYPE) wrappers++
                    Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE ->
                        if (instruction.getReference<MethodReference>()?.toString() in DIALOG_INITS) dialogs++
                    else -> Unit
                }
            }
        }
    }

    /** Each hook the patch writes names a public static method of the merged extension, and none takes a boolean. */
    private fun BytecodePatchContext.assertHookTargets() {
        val theme = classDefBy(AMOLED_THEME_EXTENSION)
        for (hook in listOf(THEME_APPLY_STYLE_HOOK, SET_THEME_HOOK, DIALOG_HOOK, ACTIVITY_RESTORE_HOOK)) {
            val method = theme.methods.singleOrNull { smali(it) == hook }
            assertNotNull("the extension has $hook", method)
            assertTrue("$hook is public and static", AccessFlags.STATIC.isSet(method!!.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags))
            assertTrue("$hook takes no boolean", method.parameterTypes.none { it.toString() == "Z" })
        }
        val wrapper = classDefBy(AMOLED_THEME_WRAPPER)
        assertEquals("the wrapper is a ContextThemeWrapper", CONTEXT_THEME_WRAPPER_TYPE, wrapper.superclass)
        assertTrue("the wrapper's constructor", wrapper.methods.any { smali(it) == AMOLED_THEME_WRAPPER_INIT && AccessFlags.PUBLIC.isSet(it.accessFlags) })
    }

    /**
     * AppCompatActivity's setTheme hands the activity to the extension right before each return.
     * [stock] comes from the unpatched dex: the match already reads the patched body.
     */
    private fun BytecodePatchContext.assertActivityHook(version: String, stock: List<Opcode>, matched: Method) {
        val patched = mutableClassDefBy(matched.definingClass).findMutableMethodOf(matched)
        val code = patched.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
        val returns = stock.count { it == Opcode.RETURN_VOID }
        assertTrue("$version: setTheme returns", returns >= 1)
        assertEquals(
            "$version: a hook before each return and nothing else changed",
            stock.flatMap { if (it == Opcode.RETURN_VOID) listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.RETURN_VOID) else listOf(it) },
            code.map(::kind),
        )
        val activity = patched.implementation!!.registerCount - 2
        val hooks = code.filter { it.getReference<MethodReference>()?.let(::smali) == ACTIVITY_RESTORE_HOOK }
        assertEquals("$version: one hook per return", returns, hooks.size)
        hooks.forEach { hook ->
            hook as RegisterRangeInstruction
            assertEquals("$version: the hook takes the activity", activity, hook.startRegister)
            assertEquals(1, hook.registerCount)
        }
    }

    /**
     * Every call to [targets] in a method became [hook] with the same registers in the same form,
     * as many as were found there.
     */
    private fun assertSwapped(
        where: String,
        code: List<Instruction>,
        targets: Set<String>,
        hook: String,
        expected: List<Pair<List<Int>, Boolean>>,
    ) {
        val left = code.filter {
            (it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                it.getReference<MethodReference>()?.let(::smali) in targets
        }
        if (expected.isNotEmpty()) assertTrue("$where: ${left.size} calls to $targets left", left.isEmpty())
        val hooks = code.filter { it.getReference<MethodReference>()?.let(::smali) == hook }
        assertEquals("$where: calls to $hook", expected.size, hooks.size)
        assertEquals(
            "$where: the registers $hook takes",
            expected.map { it.first.toString() + it.second }.sorted(),
            hooks.map { registersOf(it).toString() + (it is RegisterRangeInstruction) }.sorted(),
        )
        hooks.forEach { call ->
            assertTrue("$where: $hook is a static call", call.opcode == Opcode.INVOKE_STATIC || call.opcode == Opcode.INVOKE_STATIC_RANGE)
        }
    }

    /** A method whose wrappers were swapped builds only the extension's, each constructed on its own register. */
    private fun assertWrappers(where: String, code: List<Instruction>, expected: Int) {
        if (expected == 0) return
        val stockCreates = code.filter { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == CONTEXT_THEME_WRAPPER_TYPE }
        assertTrue("$where: a plain ContextThemeWrapper is left", stockCreates.isEmpty())
        val creates = code.filter { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == AMOLED_THEME_WRAPPER }
        val inits = code.filter { it.getReference<MethodReference>()?.let(::smali) == AMOLED_THEME_WRAPPER_INIT }
        assertEquals("$where: the extension's wrappers", expected, creates.size)
        assertEquals("$where: their constructors", expected, inits.size)
        assertEquals(
            "$where: each constructor runs on a wrapper's register",
            creates.map { (it as OneRegisterInstruction).registerA }.sorted(),
            inits.map { registersOf(it).first() }.sorted(),
        )
        inits.forEach { assertTrue("$where: a direct call", it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE) }
    }

    /** Right after each Dialog constructor the patch kept, the extension gets the dialog it built. */
    private fun assertDialogHooks(where: String, code: List<Instruction>, expected: Int) {
        val hooks = code.withIndex().filter { it.value.getReference<MethodReference>()?.let(::smali) == DIALOG_HOOK }
        assertEquals("$where: dialog hooks", expected, hooks.size)
        hooks.forEach { (at, hook) ->
            assertEquals("$where: the hook's form", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            hook as RegisterRangeInstruction
            assertEquals(1, hook.registerCount)
            val init = code[at - 1]
            assertTrue(
                "$where: a Dialog constructor before the hook, not ${init.opcode}",
                init.getReference<MethodReference>()?.let(::smali) in DIALOG_INITS,
            )
            assertEquals("$where: the hook takes the dialog", registersOf(init).first(), hook.startRegister)
        }
    }

    private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(
            instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG,
        ).take(instruction.registerCount)
        is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
        else -> error("not an invoke: ${instruction.opcode}")
    }

    /** An insert can widen a plain goto; it's still the same branch. */
    private fun kind(instruction: Instruction): Opcode = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }

    private fun smali(reference: MethodReference) =
        "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

    private companion object {
        const val EXTENSION = "/extensions/tiktok.mpe"
        const val CONTEXT_THEME_WRAPPER_TYPE = "Landroid/view/ContextThemeWrapper;"
        val OWNER = AmoledThemeSwitchHooksTest::class.java
    }
}
