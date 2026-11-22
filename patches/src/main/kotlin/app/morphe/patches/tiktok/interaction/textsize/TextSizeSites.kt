/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.textsize

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.navigation.branchTargets
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val SMALLER_TEXT_SIZES = "Lapp/morphe/extension/tiktok/font/SmallerTextSizes;"
private const val TUX_ICON_VIEW = "Lcom/bytedance/tux/icon/TuxIconView;"
private const val KEVA = "Lcom/bytedance/keva/Keva;"

/** An int on its way through SmallerTextSizes: [register] of [method], at instruction [index]. */
internal data class IntSite(val method: Method, val index: Int, val register: Int)

/**
 * Every Smaller text sizes hook point on one build (#476). They are all found before any is
 * written: the extra stops only work with every one of them in, so a build missing one gets none.
 * A missing one is a [PatchException] naming it.
 */
internal class TextSizeSites(
    /** TikTok's mode field, `Lowner;->name:I`, 0 for its smallest size to 3 for its largest. */
    val mode: String,
    /** The manager's factor methods. Each asks SmallerTextSizes first, at index 0. */
    val factors: List<Method>,
    /** The slider's top, raised by the extra stops right before each constructor writes it. */
    val sliderTop: List<IntSite>,
    /** The page's read of the saved mode in onCreate, turned into a slider position after it. */
    val pageMode: IntSite,
    /** The page's preview of a position, turned into the mode it shows before anything runs. */
    val preview: IntSite,
    /** The confirmed position, kept and turned into TikTok's mode right before Keva stores it. */
    val store: IntSite,
)

private fun missing(why: String): Nothing = throw PatchException("Smaller text sizes: $why")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun FieldReference.descriptor() = "$definingClass->$name:$type"

/** A site whose register a 8-bit `move-result` can name, refused otherwise. */
private fun site(method: Method, index: Int, register: Int): IntSite {
    if (register > 255) missing("${method.definingClass}->${method.name} keeps the value in v$register")
    return IntSite(method, index, register)
}

/**
 * Finds every hook point. [manager] is the text size manager's class, [page] the Text size page
 * with its [create] and [viewCreated], [confirm] its confirm callback, and [classBy] looks up
 * the slider class the page wires.
 */
internal fun textSizeSites(
    manager: ClassDef,
    page: ClassDef,
    create: Method,
    viewCreated: Method,
    confirm: Method,
    classBy: (String) -> ClassDef,
): TextSizeSites {
    // X.03aF on 47.9.3: LIZ()F and LIZJ()F both open with `sget v1, LIZ:I` and answer 1.0 for
    // mode 0. Nothing else in the class is a static ()F that reads an int of its own.
    val factors = manager.methods.filter { it.isModeFactor(manager.type) }
    if (factors.size != 2) missing("${manager.type} has ${factors.size} text size factor methods, not 2")
    val modes = factors.map { it.modeRead(manager.type)!!.descriptor() }.distinct()
    val mode = modes.singleOrNull() ?: missing("${manager.type}'s factor methods read $modes")
    // The answer goes to v0 in front of each one's first instruction. A static ()F method that
    // reads a field has at least that register, and none of it is a parameter. A handler covering
    // index 0 would cover the answer too.
    factors.forEach { factor ->
        if (factor.implementation!!.tryBlocks.any { it.startCodeAddress == 0 }) {
            missing("${manager.type}->${factor.name} starts inside a try block")
        }
    }

    // The slider works in positions, 0 to its top. Its top is TikTok's last mode, written once in
    // the constructor (LX/1PkU;.LLJZ:I on 47.9.3, read back by getValueTo).
    val wired = viewCreated.code().mapNotNull { it.getReference<MethodReference>() }
        .filter { it.name == "setOnSliderListener" }.map { it.definingClass }.distinct()
    val sliderType = wired.singleOrNull() ?: missing("the Text size page wires ${wired.size} sliders")
    val slider = classBy(sliderType)
    val top = slider.methods
        .singleOrNull { it.name == "getValueTo" && it.parameterTypes.isEmpty() && it.returnType == "I" }
        ?.code()?.firstOrNull { it.opcode == Opcode.IGET }?.getReference<FieldReference>()
        ?.takeIf { it.definingClass == sliderType && it.type == "I" }
        ?.descriptor()
        ?: missing("the slider $sliderType reads no top in getValueTo")
    val writes = slider.methods.flatMap { method ->
        method.code().withIndex()
            .filter { (_, instruction) ->
                instruction.opcode == Opcode.IPUT && instruction.getReference<FieldReference>()?.descriptor() == top
            }
            .map { method to it.index }
    }
    if (writes.isEmpty()) missing("the slider $sliderType never writes its top")
    val sliderTop = writes.map { (method, index) ->
        // A setter would put TikTok's top back after the constructor raised it.
        if (method.name != "<init>") missing("$sliderType->${method.name} writes the top too")
        val register = (method.code()[index] as TwoRegisterInstruction).registerA
        if (index in method.branchTargets()) missing("a jump lands on $sliderType's write of its top")
        // The raised top replaces the register's value, so nothing after the write may read it.
        if (method.readsAfter(index, register).isNotEmpty()) {
            missing("$sliderType reads v$register again after writing its top")
        }
        site(method, index, register)
    }

    // onCreate copies the saved mode into the page's position: sget mode, iput LLLF on 47.9.3.
    val modeReads = create.code().withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.SGET && instruction.getReference<FieldReference>()?.descriptor() == mode
    }
    val modeRead = modeReads.singleOrNull()
        ?: missing("the Text size page's onCreate reads the mode ${modeReads.size} times")
    val pageMode = site(create, modeRead.index + 1, (modeRead.value as OneRegisterInstruction).registerA)

    // bZ(I)V on 47.9.3: styles the page's four sample icons for a position.
    val previews = page.methods.filter { method ->
        method.parameterTypes.map { it.toString() } == listOf("I") && method.returnType == "V" &&
            method.code().any {
                it.opcode == Opcode.NEW_ARRAY && it.getReference<TypeReference>()?.type == "[$TUX_ICON_VIEW"
            }
    }
    val preview = previews.singleOrNull()
        ?: missing("the Text size page has ${previews.size} preview methods")
    // A jump back to the start would turn a mode into a mode a second time.
    if (0 in preview.branchTargets()) missing("a jump lands on the start of ${page.type}->${preview.name}")
    // The int parameter is the last register, whether or not there's a this before it.
    val previewSite = site(preview, 0, preview.implementation!!.registerCount - 1)

    // Keva.storeInt(String, I) with the mode's key: the position TikTok would keep as its mode.
    val code = confirm.code()
    val stores = code.indices.filter { index ->
        val call = code[index].getReference<MethodReference>()
        call?.definingClass == KEVA && call.name == "storeInt" && code.keyOf(index) == TEXT_SIZE_MODE_KEY
    }
    val store = stores.singleOrNull()
        ?: missing("the Text size confirm callback stores the mode ${stores.size} times")
    if (store in confirm.branchTargets()) missing("a jump lands on the Text size confirm callback's store")
    val storeSite = site(confirm, store, (code[store] as FiveRegisterInstruction).registerE)

    return TextSizeSites(mode, factors, sliderTop, pageMode, previewSite, storeSite)
}

private fun Method.isModeFactor(owner: String): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "F" && parameterTypes.isEmpty() &&
        modeRead(owner) != null

/** The first static int of [owner] this method reads. */
private fun Method.modeRead(owner: String): FieldReference? =
    code().firstNotNullOfOrNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.SGET }?.getReference<FieldReference>()
            ?.takeIf { it.definingClass == owner && it.type == "I" }
    }

/** The string the call at [index] takes as its key, when one of the three instructions before loads it. */
private fun List<Instruction>.keyOf(index: Int): String? {
    val call = this[index] as? FiveRegisterInstruction ?: return null
    if (call.registerCount != 3) return null
    return subList(maxOf(0, index - 3), index).lastOrNull {
        it.opcode == Opcode.CONST_STRING && (it as OneRegisterInstruction).registerA == call.registerD
    }?.getReference<StringReference>()?.string
}

/**
 * Asks SmallerTextSizes before the factor method's own code: with TikTok on its smallest mode
 * and an extra stop picked, the extra stop's factor is the answer. v0 is written before anything
 * reads it (textSizeSites checked the frame has it as a local).
 */
internal fun MutableMethod.answerExtraStop(mode: String) {
    addInstructionsWithLabels(
        0,
        """
            sget v0, $mode
            invoke-static {v0}, $SMALLER_TEXT_SIZES->useExtraStop(I)Z
            move-result v0
            if-eqz v0, :morphe_keep_text_size_factor
            invoke-static {}, $SMALLER_TEXT_SIZES->extraStopFactor()F
            move-result v0
            return v0
        """,
        ExternalLabel("morphe_keep_text_size_factor", getInstruction(0)),
    )
}

/** Passes the site's int through SmallerTextSizes.[mapper] at the site's index. */
internal fun IntSite.mapThrough(mapper: String) {
    (method as MutableMethod).addInstructions(
        index,
        """
            invoke-static/range {v$register .. v$register}, $SMALLER_TEXT_SIZES->$mapper(I)I
            move-result v$register
        """,
    )
}
