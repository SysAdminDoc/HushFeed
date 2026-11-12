/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val BOTTOM_BAR_HEIGHT_EXTENSION = "Lapp/morphe/extension/tiktok/navigation/BottomBarHeight;"

/** The main page's precompiled inflaters, which build the bottom bar in code. */
internal val BOTTOM_BAR_FRAMES = listOf(
    "Lcom/by/andInflater/homepage_common_fragment_main_page;",
    "Lcom/by/andInflater/homepage_common_fragment_main_page_opt;",
)

/** The main activity's inflater, which sizes its own bottom frame from the same dimension. */
internal const val BOTTOM_BAR_ACTIVITY_FRAME = "Lcom/by/andInflater/homepage_common_activity_main;"

private const val RESOURCES = "Landroid/content/res/Resources;"

/** The reads of a dimension resource, each with the type it hands back. */
internal val BAR_DIMENSION_READS = mapOf(
    "$RESOURCES->getDimension(I)F" to "F",
    "$RESOURCES->getDimensionPixelOffset(I)I" to "I",
    "$RESOURCES->getDimensionPixelSize(I)I" to "I",
)

/** The class whose strategy getter the nav height's static initializer calls first. */
internal const val LOW_SCREEN_AB = "Lcom/ss/android/ugc/feed/platform/ab/LowScreenOptimizeABImpl;"

/** The divider's and the stub's distances up from the bottom, in dp, inside the main page. */
private val ABOVE_BAR_LITERALS = setOf(47f, 45f)

/** A read of the bar's dimension whose result lands in [register] at [result]. */
internal class BarRead(val owner: String, val method: Method, val result: Int, val register: Int, val type: String)

/** A dp literal at [index] that places something on the bar, held in [register]. */
internal class AboveBarLiteral(val owner: String, val method: Method, val index: Int, val register: Int)

/**
 * The static that holds the room screens leave for the bar, in dp. [after] is the instruction
 * past the store of the extra above the 47 dp bar, which overwrites [register] next.
 */
internal class NavHeightStatic(
    val owner: String,
    val method: Method,
    val after: Int,
    val register: Int,
    val field: FieldReference,
)

/**
 * Where the bottom bar's height comes from, three places that each have to move:
 *
 * - the bar view, which the main page's inflater sizes from a dimension resource. That id is the
 *   first dimension the inflater reads, and the main activity's inflater reads it too. Every read
 *   of it in the app (8 on 47.7.3, one of them as whole pixels) is passed through the extension;
 * - a divider and a stub in the same inflater, placed 47 and 45 dp up from the bottom;
 * - the nav height static, worked out once in a static initializer that starts on the low screen
 *   strategy getter, then stored with the extra above the 47 dp bar right after it. The video
 *   cells, sheets and toasts all leave that much room.
 *
 * BottomBarHeightAnchorsTest holds each piece to every declared build.
 */
internal class BottomBarHeightSites(
    val dimension: Int,
    val reads: List<BarRead>,
    val literals: List<AboveBarLiteral>,
    val navHeight: NavHeightStatic,
)

private fun Instruction.methodReference(): MethodReference? =
    if (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_STATIC) getReference<MethodReference>() else null

private fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The inflater method of a precompiled layout: `(Context, ViewGroup, boolean) -> View`. */
private fun ClassDef.inflater(): Method? = methods.singleOrNull {
    it.returnType == "Landroid/view/View;" &&
        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Landroid/view/ViewGroup;", "Z")
}

/** The id a dimension read at [index] reads, when a const right before it loads that id. */
private fun List<Instruction>.dimensionReadAt(index: Int): Int? {
    val reference = this[index].methodReference() ?: return null
    if (reference.definingClass != RESOURCES || reference.descriptor() !in BAR_DIMENSION_READS) return null
    val call = this[index] as? FiveRegisterInstruction ?: return null
    if (call.registerCount != 2 || index == 0) return null
    val load = this[index - 1]
    if (load.opcode != Opcode.CONST || (load as OneRegisterInstruction).registerA != call.registerD) return null
    return (load as NarrowLiteralInstruction).narrowLiteral
}

/**
 * [classDef] looks up a class by type. [callers] returns the classes holding a call to one of the
 * given targets, each a full method descriptor or a type for any method of it.
 */
internal fun bottomBarHeightSites(
    classDef: (String) -> ClassDef?,
    callers: (Collection<String>) -> Iterable<ClassDef>,
): BottomBarHeightSites {
    val frames = BOTTOM_BAR_FRAMES.mapNotNull(classDef)
    if (frames.isEmpty()) throw PatchException("Bottom bar height: the main page's inflater isn't there.")
    val frameMethods = frames.associate { frame ->
        frame.type to (frame.inflater() ?: throw PatchException("Bottom bar height: ${frame.type} has no inflater."))
    }

    val ids = frameMethods.values.map { method ->
        val code = method.implementation!!.instructions.toList()
        code.indices.firstNotNullOfOrNull { code.dimensionReadAt(it) }
            ?: throw PatchException("Bottom bar height: ${method.definingClass} reads no dimension.")
    }.toSet()
    val dimension = ids.singleOrNull()
        ?: throw PatchException("Bottom bar height: the main page's inflaters read different first dimensions.")
    classDef(BOTTOM_BAR_ACTIVITY_FRAME)?.inflater()?.let { activity ->
        val code = activity.implementation!!.instructions.toList()
        if (code.indices.none { code.dimensionReadAt(it) == dimension }) {
            throw PatchException("Bottom bar height: the main activity doesn't size its frame from the bar's dimension.")
        }
    }

    val reads = mutableListOf<BarRead>()
    for (owner in callers(BAR_DIMENSION_READS.keys)) {
        if (owner.type.startsWith("Lapp/morphe/extension/")) continue
        owner.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val targets by lazy { method.branchTargets() }
            code.indices.forEach { index ->
                if (code.dimensionReadAt(index) != dimension) return@forEach
                val result = index + 1
                if (result >= code.size || code[result].opcode != Opcode.MOVE_RESULT) return@forEach
                // A call put where a branch lands would also run on the path that skipped the read.
                if (result + 1 in targets) return@forEach
                val type = BAR_DIMENSION_READS.getValue(code[index].getReference<MethodReference>()!!.descriptor())
                reads += BarRead(owner.type, method, result, (code[result] as OneRegisterInstruction).registerA, type)
            }
        }
    }
    frameMethods.keys.forEach { type ->
        if (reads.none { it.owner == type }) {
            throw PatchException("Bottom bar height: $type's read of the bar's size can't take a hook.")
        }
    }

    val literals = frameMethods.flatMap { (type, method) ->
        val code = method.implementation!!.instructions.toList()
        val targets = method.branchTargets()
        val found = code.indices.mapNotNull { index ->
            val load = code[index]
            if (load.opcode != Opcode.CONST_HIGH16) return@mapNotNull null
            val value = Float.fromBits((load as NarrowLiteralInstruction).narrowLiteral)
            if (value !in ABOVE_BAR_LITERALS) return@mapNotNull null
            val register = (load as OneRegisterInstruction).registerA
            // Only a literal turned into pixels as a dp value places something.
            val used = code.subList(index + 1, minOf(code.size, index + 4)).any { next ->
                next.methodReference()?.let { it.definingClass == "Landroid/util/TypedValue;" && it.name == "applyDimension" } == true &&
                    (next as FiveRegisterInstruction).registerD == register
            }
            if (!used || index + 1 in targets) return@mapNotNull null
            AboveBarLiteral(type, method, index, register) to value
        }
        if (found.none { it.second == 47f }) {
            throw PatchException("Bottom bar height: $type no longer places the divider 47 dp up.")
        }
        found.map { it.first }
    }

    val statics = callers(listOf(LOW_SCREEN_AB)).flatMap { owner ->
        val clinit = owner.methods.singleOrNull { it.name == "<clinit>" } ?: return@flatMap emptyList()
        val code = clinit.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.mapNotNull { index ->
            val store = code[index]
            if (store.opcode != Opcode.SPUT || index + 1 >= code.size) return@mapNotNull null
            val register = (store as OneRegisterInstruction).registerA
            val extra = code[index + 1]
            if (extra.opcode != Opcode.ADD_INT_LIT8 || (extra as NarrowLiteralInstruction).narrowLiteral != -47) return@mapNotNull null
            if ((extra as TwoRegisterInstruction).registerB != register) return@mapNotNull null
            // The extra goes into a static of its own before the register is reused.
            val extraStore = (index + 2 until code.size).firstOrNull { code[it].opcode == Opcode.SPUT } ?: return@mapNotNull null
            val free = (code[extraStore] as OneRegisterInstruction).registerA
            if (extraStore + 1 >= code.size || !code[extraStore + 1].writes(free)) return@mapNotNull null
            NavHeightStatic(owner.type, clinit, extraStore + 1, free, store.getReference<FieldReference>()!!)
        }
    }
    val navHeight = statics.singleOrNull()
        ?: throw PatchException("Bottom bar height: expected one nav height static, found ${statics.size}.")
    if (navHeight.field.type != "I") throw PatchException("Bottom bar height: the nav height static isn't an int.")

    return BottomBarHeightSites(dimension, reads, literals, navHeight)
}

/** The code each site takes, by the index it goes in at. */
internal fun BottomBarHeightSites.hooksByMethod(): Map<Pair<String, Method>, List<Pair<Int, String>>> {
    val hooks = LinkedHashMap<Pair<String, Method>, MutableList<Pair<Int, String>>>()
    fun add(owner: String, method: Method, index: Int, code: String) {
        hooks.getOrPut(owner to method) { mutableListOf() } += index to code
    }
    reads.forEach { read ->
        add(
            read.owner, read.method, read.result + 1,
            """
                invoke-static/range {v${read.register} .. v${read.register}}, $BOTTOM_BAR_HEIGHT_EXTENSION->barPixels(${read.type})${read.type}
                move-result v${read.register}
            """,
        )
    }
    literals.forEach { literal ->
        add(
            literal.owner, literal.method, literal.index + 1,
            """
                invoke-static/range {v${literal.register} .. v${literal.register}}, $BOTTOM_BAR_HEIGHT_EXTENSION->aboveBar(F)F
                move-result v${literal.register}
            """,
        )
    }
    return hooks
}

/** Applies one method's hooks from the last index back, so each index still holds. */
internal fun MutableMethod.addBottomBarHooks(hooks: List<Pair<Int, String>>) {
    hooks.sortedByDescending { it.first }.forEach { (index, code) -> addInstructions(index, code) }
}

/**
 * The bottom tab's constructor, where the extension moves the tab's icon and name as they go in.
 * The hook reads the tab (p0) at the one return, so nothing before it may reuse that register.
 */
internal fun barCellConstructor(cell: ClassDef): Method {
    fun fail(problem: String): Nothing = throw PatchException("Bottom bar height: $problem")
    val constructor = cell.methods.filter { it.name == "<init>" }.singleOrNull()
        ?: fail("${cell.type} has more than one constructor.")
    val code = constructor.implementation ?: fail("${cell.type}'s constructor has no code.")
    if (code.instructions.count { it.opcode == Opcode.RETURN_VOID } != 1) {
        fail("${cell.type}'s constructor doesn't end in one return.")
    }
    val tab = code.registerCount - constructor.parameters.sumOf { if (it.type == "J" || it.type == "D") 2 else 1 } - 1
    if (code.instructions.any { it.writes(tab) }) fail("${cell.type}'s constructor reuses its own register.")
    return constructor
}

/** Hands the finished tab to the extension. */
internal fun MutableMethod.holdBarCellContent() {
    val returnIndex = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
    addInstructionsAtControlFlowLabel(
        returnIndex,
        "invoke-static/range {p0 .. p0}, $BOTTOM_BAR_HEIGHT_EXTENSION->onBarCell(Landroid/view/View;)V",
    )
}

/** Passes the nav height through the extension once the extra above the bar is stored. */
internal fun MutableMethod.adjustNavHeight(site: NavHeightStatic) {
    val field = "${site.field.definingClass}->${site.field.name}:${site.field.type}"
    addInstructionsAtControlFlowLabel(
        site.after,
        """
            sget v${site.register}, $field
            invoke-static/range {v${site.register} .. v${site.register}}, $BOTTOM_BAR_HEIGHT_EXTENSION->navHeight(I)I
            move-result v${site.register}
            sput v${site.register}, $field
        """,
    )
}
