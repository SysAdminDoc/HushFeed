/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.navigation.branchTargets
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.privacy.InvokeSite
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.patches.tiktok.privacy.replaceSites
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classesCallingMethods
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The extension class that decides, per theme, whether TikTok's own grays go back (#372). */
internal const val AMOLED_THEME_EXTENSION = "Lapp/morphe/extension/tiktok/theme/AmoledTheme;"

/** The extension's ContextThemeWrapper, which puts the grays back on the theme it builds. */
internal const val AMOLED_THEME_WRAPPER = "Lapp/morphe/extension/tiktok/theme/AmoledThemeWrapper;"

internal const val THEME_APPLY_STYLE = "Landroid/content/res/Resources\$Theme;->applyStyle(IZ)V"

/** applyStyle's stand-in. It takes the force flag as an int: a hook never takes a boolean. */
internal const val THEME_APPLY_STYLE_HOOK =
    "$AMOLED_THEME_EXTENSION->applyStyle(Landroid/content/res/Resources\$Theme;II)V"

/** setTheme(int) as TikTok's code names it: on a Context, a wrapper of one, or an Activity. */
internal val SET_THEME_TARGETS: Set<String> = listOf(
    "Landroid/content/Context;",
    "Landroid/content/ContextWrapper;",
    "Landroid/view/ContextThemeWrapper;",
    "Landroid/app/Activity;",
).mapTo(LinkedHashSet()) { "$it->setTheme(I)V" }

internal const val SET_THEME_HOOK = "$AMOLED_THEME_EXTENSION->setTheme(Landroid/content/Context;I)V"

private const val CONTEXT_THEME_WRAPPER = "Landroid/view/ContextThemeWrapper;"

internal const val THEME_WRAPPER_INIT = "$CONTEXT_THEME_WRAPPER-><init>(Landroid/content/Context;I)V"

internal const val AMOLED_THEME_WRAPPER_INIT = "$AMOLED_THEME_WRAPPER-><init>(Landroid/content/Context;I)V"

private const val DIALOG = "Landroid/app/Dialog;"

/** Every Dialog constructor. A sheet or dialog TikTok builds reaches one of them first. */
internal val DIALOG_INITS: Set<String> = linkedSetOf(
    "$DIALOG-><init>(Landroid/content/Context;)V",
    "$DIALOG-><init>(Landroid/content/Context;I)V",
    "$DIALOG-><init>(Landroid/content/Context;ZLandroid/content/DialogInterface\$OnCancelListener;)V",
)

internal const val DIALOG_HOOK = "$AMOLED_THEME_EXTENSION->dialog(Landroid/app/Dialog;)V"

internal const val ACTIVITY_RESTORE_HOOK = "$AMOLED_THEME_EXTENSION->restore(Landroid/content/Context;)V"

/**
 * AppCompatActivity's setTheme: the framework's setTheme, then the delegate's. Every TikTok screen
 * is an AppCompatActivity, and Android calls this with the screen's theme before its content is
 * built, as do TikTok's own theme switches.
 */
internal object AppCompatSetThemeFingerprint : Fingerprint(
    name = "setTheme",
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_SUPER, definingClass = "Landroid/app/Activity;", name = "setTheme"),
        methodCall(name = "getDelegate"),
    ),
)

/**
 * A `new ContextThemeWrapper(context, theme)` in TikTok's code: the [create] instruction and the
 * [init] that constructs it, on the receiver [register], in the init's own register form.
 */
internal data class WrapperSite(
    val owner: ClassDef,
    val method: Method,
    val create: Int,
    val init: Int,
    val register: Int,
    val registers: List<Int>,
    val ranged: Boolean,
)

/** A Dialog constructor call at [index], whose dialog is in [register] once it returns. */
internal data class DialogSite(val owner: ClassDef, val method: Method, val index: Int, val register: Int)

/** Where the switch went in, for the fixture test to read the instructions back. */
internal class AmoledThemeSwitchSites(
    val activitySetTheme: Method,
    val applyStyles: List<InvokeSite>,
    val setThemes: List<InvokeSite>,
    val wrappers: List<WrapperSite>,
    val dialogs: List<DialogSite>,
)

/**
 * Every new ContextThemeWrapper in [method] paired with its constructor, or nothing when any of
 * them can't be paired for certain: a wrapper whose class changed without its constructor (or
 * the other way round) fails verification, so a method is changed whole or not at all.
 */
internal fun themeWrapperPairs(owner: ClassDef, method: Method): List<WrapperSite> {
    val implementation = method.implementation ?: return emptyList()
    val instructions = implementation.instructions.toList()
    val creates = instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.NEW_INSTANCE &&
            instructions[index].getReference<TypeReference>()?.type == CONTEXT_THEME_WRAPPER
    }
    if (creates.isEmpty()) return emptyList()
    // A wrapper subclass's own constructor initializes itself through this one; that call
    // constructs `this`, not a new wrapper, and stays as it is.
    val parameterRegisters = method.parameterTypes.sumOf { type ->
        if (type.toString() == "J" || type.toString() == "D") 2 else 1
    } + 1
    val self = if (method.name == "<init>") implementation.registerCount - parameterRegisters else -1
    val inits = instructions.indices.filter { index ->
        val reference = instructions[index].getReference<MethodReference>()
        (instructions[index].opcode == Opcode.INVOKE_DIRECT || instructions[index].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            reference?.definingClass == CONTEXT_THEME_WRAPPER && reference.name == "<init>" &&
            invokeRegisters(instructions[index])?.firstOrNull() != self
    }
    val pairs = creates.map { create -> create to wrapperInit(instructions, create) }
    if (pairs.any { it.second == null }) return emptyList()
    if (pairs.map { it.second }.toSet() != inits.toSet() || inits.size != creates.size) return emptyList()
    if (inits.any { instructions[it].getReference<MethodReference>().toString() != THEME_WRAPPER_INIT }) return emptyList()
    return pairs.map { (create, init) ->
        val call = instructions[init!!]
        WrapperSite(
            owner, method, create, init,
            register = (instructions[create] as OneRegisterInstruction).registerA,
            registers = invokeRegisters(call)!!,
            ranged = call is RegisterRangeInstruction,
        )
    }
}

/** The registers an invoke passes, receiver first, or null for another shape. */
private fun invokeRegisters(instruction: Instruction): List<Int>? = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
    else -> null
}

/**
 * The first ContextThemeWrapper constructor after [create] on the register it filled, or null
 * when something writes that register first.
 */
private fun wrapperInit(instructions: List<Instruction>, create: Int): Int? {
    val register = (instructions[create] as OneRegisterInstruction).registerA
    for (index in create + 1 until instructions.size) {
        val instruction = instructions[index]
        if (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) {
            val reference = instruction.getReference<MethodReference>()
            if (reference?.definingClass == CONTEXT_THEME_WRAPPER && reference.name == "<init>" &&
                invokeRegisters(instruction)?.firstOrNull() == register
            ) {
                return index
            }
        }
        if (instruction.opcode.setsRegister() && instruction is OneRegisterInstruction) {
            val set = instruction.registerA
            if (set == register || (instruction.opcode.setsWideRegister() && set + 1 == register)) return null
        }
    }
    return null
}

/** Every Dialog constructor call in TikTok's code with room for a call right after it. */
internal fun dialogSites(): List<DialogSite> {
    val sites = mutableListOf<DialogSite>()
    for (callerType in classesCallingMethods(DIALOG_INITS)) {
        if (callerType.startsWith("Lapp/morphe/extension/")) continue
        val owner = classDefByOrNull(callerType) ?: continue
        owner.methods.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            val targets by lazy { method.branchTargets() }
            instructions.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) {
                    return@forEachIndexed
                }
                if (instruction.getReference<MethodReference>()?.toString() !in DIALOG_INITS) return@forEachIndexed
                val next = index + 1
                // A call put where a branch lands would be skipped by that branch.
                if (next >= instructions.size || next in targets) return@forEachIndexed
                if (instructions[next].opcode == Opcode.MOVE_EXCEPTION) return@forEachIndexed
                val register = invokeRegisters(instruction)?.firstOrNull() ?: return@forEachIndexed
                sites += DialogSite(owner, method, index, register)
            }
        }
    }
    return sites
}

/**
 * The hooks that put TikTok's own dark grays back while AMOLED dark mode is off in Hushfeed
 * settings (#372). The resource side keeps TikTok's value of every surface it repaints beside it,
 * under a theme attribute of its own, and adds styles that point each surface back at that value.
 * The extension applies those styles to a theme right after TikTok builds it, and does nothing
 * while the switch is on:
 * - AppCompatActivity's setTheme, which every TikTok screen's theme goes through.
 * - Every Resources.Theme.applyStyle call in TikTok's code, androidx's own ContextThemeWrapper and
 *   TikTok's theme overlays among them.
 * - Every setTheme(int) TikTok calls on a Context, which covers its Scene pages.
 * - Every new ContextThemeWrapper(context, theme), which becomes the extension's wrapper.
 * - Every Dialog constructor, which covers the sheets: each builds its own themed context.
 */
internal fun hookAmoledThemeSwitch(): AmoledThemeSwitchSites {
    val activities = AppCompatSetThemeFingerprint.matchAllOrNull().orEmpty()
    val activity = activities.singleOrNull()
        ?: throw PatchException("AMOLED dark theme: expected one AppCompatActivity setTheme, found ${activities.size}.")
    val applyStyles = invokeSitesOf(setOf(THEME_APPLY_STYLE))
    val setThemes = invokeSitesOf(SET_THEME_TARGETS)
    val wrappers = classesCallingMethods(setOf(THEME_WRAPPER_INIT))
        .filterNot { it.startsWith("Lapp/morphe/extension/") }
        .mapNotNull { classDefByOrNull(it) }
        .flatMap { owner -> owner.methods.flatMap { themeWrapperPairs(owner, it) } }
    val dialogs = dialogSites()
    if (applyStyles.isEmpty() || dialogs.isEmpty()) {
        throw PatchException("AMOLED dark theme: TikTok's theme calls aren't where they were.")
    }

    // Swaps first: they replace an instruction in place, so every index read above still holds.
    replaceSites(applyStyles, mapOf(THEME_APPLY_STYLE to THEME_APPLY_STYLE_HOOK))
    replaceSites(setThemes, SET_THEME_TARGETS.associateWith { SET_THEME_HOOK })
    wrappers.forEach { site ->
        val method = mutableClassDefBy(site.owner).findMutableMethodOf(site.method)
        method.replaceInstruction(site.create, "new-instance v${site.register}, $AMOLED_THEME_WRAPPER")
        val call = if (site.ranged) {
            "invoke-direct/range { v${site.registers.first()} .. v${site.registers.last()} }, $AMOLED_THEME_WRAPPER_INIT"
        } else {
            "invoke-direct { ${site.registers.joinToString(", ") { "v$it" }} }, $AMOLED_THEME_WRAPPER_INIT"
        }
        method.replaceInstruction(site.init, call)
    }

    // Then the insertions, each method's from its last index back.
    val setTheme = activity.method
    setTheme.implementation!!.instructions.toList().withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        .asReversed().forEach { index ->
            setTheme.addInstructionsAtControlFlowLabel(index, "invoke-static/range { p0 .. p0 }, $ACTIVITY_RESTORE_HOOK")
        }
    dialogs.sortedWith(compareBy({ it.owner.type }, { it.method.toString() }, { -it.index })).forEach { site ->
        mutableClassDefBy(site.owner).findMutableMethodOf(site.method).addInstructions(
            site.index + 1,
            "invoke-static/range { v${site.register} .. v${site.register} }, $DIALOG_HOOK",
        )
    }
    return AmoledThemeSwitchSites(activity.originalMethod, applyStyles, setThemes, wrappers, dialogs)
}

/** The switch for AMOLED dark theme in Hushfeed settings, and the hooks it drives (#372). */
internal val amoledThemeSwitchPatch = bytecodePatch {
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        hookAmoledThemeSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAmoledDarkMode()V",
        )
    }
}
