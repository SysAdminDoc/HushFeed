/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val LYNX_ACCENT_EXTENSION = "Lapp/morphe/extension/tiktok/theme/LynxAccent;"

/** Lynx's text node, whose kept `color` property setter is where a page's text color comes in. */
internal const val LYNX_TEXT_SHADOW_NODE = "Lcom/lynx/tasm/behavior/shadow/text/BaseTextShadowNode;"

/** The packed props newer Lynx pages send, whose reader stores a text color of its own. */
internal const val LYNX_COMPACT_BUFFER = "Lcom/lynx/react/bridge/mapbuffer/ReadableCompactArrayBuffer;"

/**
 * The base of every Lynx view, whose `background-color` setter most views end in. The tab bar's own
 * setter, keyframe frames and inline text span backgrounds skip it, and keep the page's red.
 */
internal const val LYNX_BASE_UI = "Lcom/lynx/tasm/behavior/ui/LynxBaseUI;"

private const val INTEGER = "Ljava/lang/Integer;"

/**
 * Where Lynx stores a text color, found from its `color` setter: the Integer field that the setter
 * stores an `Integer.valueOf(int)` result into. The setter clears the same field with null for a
 * gradient, which isn't a store of a number and doesn't count. Null when the setter stores no
 * number or numbers into more than one field.
 */
internal fun ClassDef.lynxTextColorField(): FieldReference? {
    val setter = methods.singleOrNull { it.name == "setColor" && it.parameterTypes.size == 1 } ?: return null
    val instructions = setter.implementation?.instructions?.toList() ?: return null
    return instructions.indices.mapNotNull { instructions.boxedStoreAt(it) }.distinctBy { it.toString() }.singleOrNull()
}

/**
 * The indices of each `Integer.valueOf(int)` whose result the next two instructions store into
 * [field]: `invoke-static valueOf`, `move-result-object`, `iput-object` of that same register.
 */
internal fun List<Instruction>.lynxColorStores(field: FieldReference): List<Int> =
    indices.filter { index -> boxedStoreAt(index)?.let { it.definingClass == field.definingClass && it.name == field.name } == true }

private fun List<Instruction>.boxedStoreAt(index: Int): FieldReference? {
    val call = this[index]
    if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val method = call.getReference<MethodReference>() ?: return null
    if (method.definingClass != INTEGER || method.name != "valueOf" || method.parameterTypes.map(Any::toString) != listOf("I")) return null
    val result = getOrNull(index + 1) ?: return null
    val store = getOrNull(index + 2) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || store.opcode != Opcode.IPUT_OBJECT) return null
    if ((store as TwoRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA) return null
    return store.getReference<FieldReference>()?.takeIf { it.type == INTEGER }
}

/** The register holding the number `Integer.valueOf` is called with at [index]. */
internal fun List<Instruction>.boxedRegister(index: Int): Int = when (val call = this[index]) {
    is FiveRegisterInstruction -> call.registerC
    is RegisterRangeInstruction -> call.startRegister
    else -> throw PatchException("Accent color: Lynx's text color call at $index isn't an invocation.")
}

/**
 * Runs each stored Lynx text color through the extension first, at [stores] (indices from
 * [lynxColorStores]), so the number that gets boxed and kept is the accent's. A range call takes
 * any register, and move-result takes up to v255, past anything these short methods use.
 */
internal fun MutableMethod.accentLynxColors(stores: List<Int>) {
    val instructions = implementation!!.instructions.toList()
    for (index in stores.sortedDescending()) {
        val register = instructions.boxedRegister(index)
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $LYNX_ACCENT_EXTENSION->color(I)I
                move-result v$register
            """,
        )
    }
}

/** Lynx's view `setBackgroundColor(I)V` with code, or null. */
internal fun ClassDef.lynxBackgroundSetter(): Method? = methods.singleOrNull {
    it.name == "setBackgroundColor" && it.parameterTypes.map(Any::toString) == listOf("I") &&
        it.returnType == "V" && it.implementation != null
}

/** Runs the color handed to a view's background setter through the extension before the setter reads it. */
internal fun MutableMethod.accentLynxBackground() {
    addInstructions(
        0,
        """
            invoke-static/range { p1 .. p1 }, $LYNX_ACCENT_EXTENSION->color(I)I
            move-result p1
        """,
    )
}

/**
 * The text on TikTok's Lynx pages takes the accent too (#163). The "You may like" words in search
 * are Lynx text in #FF576F on dark and #E10543 on light, colors a Lynx page brings with it, so no
 * resource holds them. Lynx turns each text color into a number and keeps it in one field of the
 * text's attributes, both from the `color` property setter and from the compact prop buffer newer
 * pages send. Each of those stores now asks the extension first, which answers with the color the
 * resource side wrote under that red's name, or the number unchanged. A view's background color
 * (the dot before each of those words) goes through the same question at the top of the one setter
 * every Lynx view inherits. With [active] false (TikTok's own red and no wallpaper choice) nothing
 * is changed.
 */
internal fun lynxTextAccentPatch(active: () -> Boolean) = bytecodePatch {
    dependsOn(sharedExtensionPatch)
    execute {
        if (!active()) return@execute
        val node = classDefByOrNull(LYNX_TEXT_SHADOW_NODE)
            ?: throw PatchException("Accent color: this TikTok has no Lynx text node, so its search words can't follow the accent.")
        val field = node.lynxTextColorField()
            ?: throw PatchException("Accent color: couldn't find where Lynx keeps a text color.")
        val writers = Fingerprint(
            filters = listOf(fieldAccess(definingClass = field.definingClass, name = field.name, opcode = Opcode.IPUT_OBJECT)),
            custom = { _: Method, classDef: ClassDef -> !classDef.type.startsWith("Lapp/morphe/extension/") },
        ).matchAllOrNull().orEmpty()
        var hooked = 0
        var setter = false
        var compact = false
        for (match in writers) {
            val stores = match.method.implementation!!.instructions.toList().lynxColorStores(field)
            if (stores.isEmpty()) continue
            match.method.accentLynxColors(stores)
            hooked += stores.size
            if (match.originalClassDef.type == LYNX_TEXT_SHADOW_NODE && match.method.name == "setColor") setter = true
            if (match.method.parameterTypes.any { it.toString() == LYNX_COMPACT_BUFFER }) compact = true
        }
        if (!setter) throw PatchException("Accent color: Lynx's text color setter stores no color to follow the accent.")
        // Newer pages send their props packed, so missing this writer would leave half the words red.
        if (!compact) throw PatchException("Accent color: Lynx's packed props store no text color to follow the accent.")
        if (classDefByOrNull(LYNX_BASE_UI)?.lynxBackgroundSetter() == null) {
            throw PatchException("Accent color: couldn't find Lynx's view background color setter.")
        }
        (mutableClassDefBy(LYNX_BASE_UI).lynxBackgroundSetter() as MutableMethod).accentLynxBackground()
        println("[Accent color] Lynx text colors follow the accent at $hooked stores, and Lynx view backgrounds too.")
    }
}
