package app.morphe

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/*
 * The methods of a build that come closest to a fingerprint that missed it, for a retarget.
 *
 * A miss only says that no method passed. Here each part the fingerprint declares (its type and
 * name fields, each parameter, each string, each instruction filter, the class fingerprint and the
 * custom check) is judged on every method of the build on its own, and a method's parts score is
 * the share of the parts it meets, each weighted by how few methods meet it: a string one method
 * loads says more than a `V` return. When the method the fingerprint took on an earlier build is
 * known, each candidate is also compared with it by shape (its strings, the calls and fields it
 * reads with TikTok's obfuscated names blanked, its literals, its opcodes and its class's
 * supertypes), the cross-version idea of ApkMatcher and of Feichtner and Rabensteiner, and the two
 * scores count half each. Nothing here ships. NearestMethodsReport prints it for a named
 * fingerprint, and NearestMethodsFixturesTest holds it to real fingerprints on real builds.
 */

/**
 * One thing a fingerprint asks of a method, named the way the report prints it. A part judged
 * per instruction is met when any instruction of the method passes it. A [perClass] part is
 * about the method's class, so every method of a class meets it or none does.
 */
internal class Part(
    val label: String,
    val method: ((Method, ClassDef) -> Boolean)? = null,
    val instruction: ((Method, Instruction) -> Boolean)? = null,
    val perClass: Boolean = false,
)

/**
 * The fingerprint's parts, each on its own. Instruction filters count wherever they sit, since a
 * part is judged alone; the order and placement [takes] holds them to don't apply here.
 */
internal fun Fingerprint.parts(): List<Part> {
    val parts = mutableListOf<Part>()
    classFingerprint?.let { wanted ->
        val holders = HashMap<String, Boolean>()
        parts += Part("class fingerprint", perClass = true, method = { _, classDef ->
            holders.getOrPut(classDef.type) { classDef.methods.any { holds { wanted.takes(it, classDef) } } }
        })
    }
    declaredDefiningClass?.let { wanted ->
        parts += Part("defining class $wanted", perClass = true, method = { method, _ -> typeMatches(method.definingClass, wanted) })
    }
    declaredName?.let { wanted -> parts += Part("name $wanted", method = { method, _ -> method.name == wanted }) }
    declaredAccessFlags?.let { wanted ->
        parts += Part("access flags ${AccessFlags.formatAccessFlagsForMethod(wanted)}", method = { method, _ -> method.accessFlags == wanted })
    }
    declaredIsStatic?.let { wanted ->
        parts += Part(if (wanted) "static" else "not static", method = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) == wanted })
    }
    declaredReturnType?.let { wanted -> parts += Part("returns $wanted", method = { method, _ -> typeMatches(method.returnType, wanted) }) }
    declaredSuperclass?.let { wanted ->
        parts += Part("superclass $wanted", perClass = true, method = { _, classDef ->
            classDef.superclass?.let { typeMatches(it, wanted) } == true
        })
    }
    declaredInterfaces?.forEach { wanted ->
        parts += Part("implements $wanted", perClass = true, method = { _, classDef -> classDef.interfaces.any { typeMatches(it, wanted) } })
    }
    declaredParameters?.let { wanted ->
        parts += Part("${wanted.size} parameters", method = { method, _ -> method.parameterTypes.size == wanted.size })
        wanted.forEachIndexed { index, type ->
            parts += Part("parameter ${index + 1} $type", method = { method, _ ->
                method.parameterTypes.getOrNull(index)?.let { typeMatches(it.toString(), type) } == true
            })
        }
    }
    stringFilters?.forEach { filter ->
        parts += Part("string \"${filter.string()}\"", instruction = { method, instruction ->
            (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                filter.matches(method, instruction)
        })
    }
    filters.orEmpty().forEachIndexed { index, filter ->
        parts += Part("instruction filter ${index + 1} (${filter.javaClass.simpleName})", instruction = filter::matches)
    }
    custom?.let { check -> parts += Part("custom check", method = check) }
    return parts
}

/** A check that throws on a method it wasn't written for doesn't hold there. */
private inline fun holds(check: () -> Boolean): Boolean = try {
    check()
} catch (_: Exception) {
    false
}

/** Every method of [classes], numbered in the order this walks them, which every pass repeats. */
internal inline fun forEachMethod(classes: List<ClassDef>, action: (index: Int, classDef: ClassDef, method: Method) -> Unit) {
    var index = 0
    for (classDef in classes) for (method in classDef.methods) action(index++, classDef, method)
}

/** A method's code size in code units, or -1 when it has no code. */
internal fun codeSize(method: Method): Int = when (val implementation = method.implementation) {
    null -> -1
    is DexBackedMethodImplementation -> implementation.instructionsSize
    else -> implementation.instructions.sumOf { it.codeUnits }
}

/** One method's place in a ranking: how close it came and which parts it missed. */
internal class Nearness(
    val index: Int,
    val classDef: ClassDef,
    val method: Method,
    val parts: Double,
    val likeness: Double?,
    val missed: List<String>,
) {
    val score: Double get() = if (likeness == null) parts else (parts + likeness) / 2

    val reference: String
        get() = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
}

/** The nearest methods, and how many more tie with the last of them. */
internal class Ranking(val nearest: List<Nearness>, val tiedWithLast: Int) {
    fun report(title: String): String = buildString {
        appendLine(title)
        if (nearest.isEmpty()) appendLine("  no method meets any part")
        nearest.forEachIndexed { place, near ->
            append("  ${place + 1}. ${near.reference}  parts %.2f".format(near.parts))
            near.likeness?.let { append("  likeness %.2f".format(it)) }
            appendLine(if (near.missed.isEmpty()) "  meets every part" else "  misses: ${near.missed.joinToString("; ")}")
        }
        if (tiedWithLast > 0) {
            appendLine("  ($tiedWithLast more score the same as the last one, so the ranking can't tell them apart." +
                " HUSHFEED_NEAREST_FROM adds the likeness to an older build's match.)")
        }
    }
}

/**
 * Which of [parts] each method of [classes] meets, as one bit per part in walk order. [rank] reads
 * it as often as wanted without judging the build again.
 */
internal class Tally(val parts: List<Part>, val classes: List<ClassDef>) {
    val met: LongArray
    val sizes: IntArray
    private val counts = IntArray(parts.size)

    init {
        require(parts.size <= Long.SIZE_BITS) { "a fingerprint with ${parts.size} parts doesn't fit a tally" }
        var total = 0
        forEachMethod(classes) { _, _, _ -> total++ }
        met = LongArray(total)
        sizes = IntArray(total)
        val byMethod = parts.withIndex().filter { it.value.method != null }
        val byInstruction = parts.withIndex().filter { it.value.instruction != null }
        forEachMethod(classes) { index, classDef, method ->
            var mask = 0L
            for ((bit, part) in byMethod) if (holds { part.method!!(method, classDef) }) mask = mask or (1L shl bit)
            if (byInstruction.isNotEmpty()) {
                method.implementation?.instructions?.forEach { instruction ->
                    for ((bit, part) in byInstruction) {
                        if (mask and (1L shl bit) == 0L && holds { part.instruction!!(method, instruction) }) mask = mask or (1L shl bit)
                    }
                }
            }
            met[index] = mask
            sizes[index] = codeSize(method)
            for (bit in parts.indices) if (mask and (1L shl bit) != 0L) counts[bit]++
        }
    }

    /** The indexes of the methods [predicate] takes, in walk order. */
    fun indexWhere(predicate: (Method, ClassDef) -> Boolean): List<Int> {
        val found = mutableListOf<Int>()
        forEachMethod(classes) { index, classDef, method -> if (holds { predicate(method, classDef) }) found += index }
        return found
    }

    /**
     * The [count] methods nearest the whole fingerprint. With [previous], the shape of the method
     * it took on an older build, the [budget] methods closest by parts, signature and size are
     * also compared with it in full, and only those are ranked. [override] replaces a method's
     * parts, which is how the tests break a fingerprint for one method.
     */
    fun rank(count: Int = 3, previous: Shape? = null, budget: Int = 20_000, override: Map<Int, Long> = emptyMap()): Ranking {
        val weights = DoubleArray(parts.size) { bit ->
            val meeting = counts[bit] + override.entries.sumOf { (index, mask) ->
                val before = if (met[index] and (1L shl bit) != 0L) 1 else 0
                val after = if (mask and (1L shl bit) != 0L) 1 else 0
                after - before
            }
            ln((met.size + 1.0) / (meeting + 1.0))
        }
        val whole = weights.sum()
        fun partsOf(index: Int): Double {
            if (whole == 0.0) return 0.0
            val mask = override[index] ?: met[index]
            var sum = 0.0
            for (bit in parts.indices) if (mask and (1L shl bit) != 0L) sum += weights[bit]
            return sum / whole
        }

        val scores: Map<Int, Pair<Double, Double?>>
        if (previous == null) {
            scores = HashMap()
            val best = PriorityQueue<Pair<Int, Double>>(compareBy<Pair<Int, Double>> { it.second }.thenByDescending { it.first })
            for (index in met.indices) {
                val parts = partsOf(index)
                if (parts == 0.0) continue
                best += index to parts
                if (best.size > count) best.poll()
            }
            for ((index, parts) in best) scores[index] = parts to null
        } else {
            val heads = DoubleArray(met.size)
            val previousHead = previous.head ?: previous
            forEachMethod(classes) { index, classDef, method -> heads[index] = Shape.head(method, classDef).likeness(previousHead) }
            val closest = PriorityQueue<Pair<Int, Double>>(compareBy<Pair<Int, Double>> { it.second }.thenByDescending { it.first })
            for (index in met.indices) {
                val near = partsOf(index) + heads[index] + previous.sizeCloseness(sizes[index])
                closest += index to near
                if (closest.size > budget) closest.poll()
            }
            val compared = closest.map { it.first }.toHashSet()
            scores = HashMap()
            forEachMethod(classes) { index, classDef, method ->
                if (index in compared) scores[index] = partsOf(index) to Shape.of(method, classDef).likeness(previous)
            }
        }

        fun scoreOf(entry: Map.Entry<Int, Pair<Double, Double?>>) =
            entry.value.second?.let { (entry.value.first + it) / 2 } ?: entry.value.first
        val ordered = scores.entries.sortedWith(compareByDescending<Map.Entry<Int, Pair<Double, Double?>>> { scoreOf(it) }.thenBy { it.key })
        val chosen = ordered.take(count)
        val tied = if (chosen.size < count) 0 else {
            val last = scoreOf(chosen.last())
            if (previous == null) {
                val shown = chosen.mapTo(HashSet()) { it.key }
                met.indices.count { it !in shown && abs(partsOf(it) - last) < 1e-9 }
            } else {
                ordered.drop(count).count { abs(scoreOf(it) - last) < 1e-9 }
            }
        }
        val wanted = chosen.associate { it.key to it.value }
        val nearest = mutableListOf<Nearness>()
        forEachMethod(classes) { index, classDef, method ->
            val (parts, likeness) = wanted[index] ?: return@forEachMethod
            val mask = override[index] ?: met[index]
            val missed = this.parts.indices.filter { mask and (1L shl it) == 0L }.map { this.parts[it].label }
            nearest += Nearness(index, classDef, method, parts, likeness, missed)
        }
        nearest.sortWith(compareByDescending<Nearness> { it.score }.thenBy { it.index })
        return Ranking(nearest, tied)
    }
}

internal fun Fingerprint.tally(classes: List<ClassDef>) = Tally(parts(), classes)

/** The shapes of the methods this fingerprint takes among [classes]; one, on a build it still matches. */
internal fun Fingerprint.shapesOn(classes: List<ClassDef>): List<Shape> {
    val taken = mutableListOf<Shape>()
    for (classDef in classes) for (method in classDef.methods) if (takes(method, classDef)) taken += Shape.of(method, classDef)
    return taken
}

/** Every class of an APK, each type once, the way the fixture tests read a build. */
internal fun classesOf(apk: File): List<ClassDef> {
    val classes = LinkedHashMap<String, ClassDef>()
    val container = Fixtures.dexContainer(apk)
    for (entry in container.dexEntryNames) {
        for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
    }
    return classes.values.toList()
}

/**
 * A method's shape with TikTok's obfuscated names blanked, so the same method on two builds reads
 * alike: the `X/` classes and names like `LIZIZ` are renamed between builds, the strings, the
 * framework calls and the code mostly aren't. [head] is the part read without decoding the code.
 */
internal class Shape private constructor(private val tokens: Map<String, Int>, val size: Int, val head: Shape?) {
    private val weight = tokens.entries.sumOf { (token, times) -> weightOf(token) * times }

    /** Weighted Jaccard likeness: what both share over what either has, 1 for the same shape. */
    fun likeness(other: Shape): Double {
        var common = 0.0
        for ((token, times) in tokens) {
            val theirs = other.tokens[token] ?: continue
            common += weightOf(token) * min(times, theirs)
        }
        val either = weight + other.weight - common
        return if (either == 0.0) 1.0 else common / either
    }

    /** 1 for the same code size, falling toward 0 as the sizes part; 0 when one has no code. */
    fun sizeCloseness(other: Int): Double = when {
        size < 0 || other < 0 -> if (size == other) 1.0 else 0.0
        else -> 1.0 - abs(size - other).toDouble() / max(max(size, other), 1)
    }

    companion object {
        private val OBFUSCATED_NAME = Regex("L[FIJLZ]{1,15}")

        private fun blankType(type: CharSequence): String {
            val written = type.toString()
            val element = written.trimStart('[')
            return if (element.startsWith("LX/")) written.substring(0, written.length - element.length) + "L?;" else written
        }

        private fun blankName(name: String) = if (OBFUSCATED_NAME.matches(name)) "?" else name

        private fun weightOf(token: String): Double = when {
            token.startsWith("str:") -> 4.0
            token.startsWith("name:") -> if (token == "name:?") 0.5 else 3.0
            token.startsWith("call:") || token.startsWith("field:") -> if ('?' in token) 1.0 else 2.0
            token.startsWith("op:") -> 0.2
            token.startsWith("lit:") -> 0.5
            else -> 1.0
        }

        private fun headTokens(method: Method, classDef: ClassDef): MutableMap<String, Int> {
            val tokens = HashMap<String, Int>()
            fun add(token: String) = tokens.merge(token, 1, Int::plus)
            add("ret:${blankType(method.returnType)}")
            add("arity:${method.parameterTypes.size}")
            method.parameterTypes.forEachIndexed { index, type -> add("p$index:${blankType(type)}") }
            add("flags:${method.accessFlags}")
            add("name:${blankName(method.name)}")
            classDef.superclass?.let { add("super:${blankType(it)}") }
            classDef.interfaces.forEach { add("iface:${blankType(it)}") }
            return tokens
        }

        fun head(method: Method, classDef: ClassDef) = Shape(headTokens(method, classDef), codeSize(method), null)

        fun of(method: Method, classDef: ClassDef): Shape {
            val tokens = headTokens(method, classDef)
            fun add(token: String) = tokens.merge(token, 1, Int::plus)
            method.implementation?.instructions?.forEach { instruction ->
                add("op:${instruction.opcode.ordinal}")
                when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                    is StringReference -> add("str:${reference.string}")
                    is MethodReference -> add("call:${blankType(reference.definingClass)}->${blankName(reference.name)}(" +
                        reference.parameterTypes.joinToString("") { blankType(it) } + ")${blankType(reference.returnType)}")
                    is FieldReference -> add("field:${blankType(reference.definingClass)}->${blankName(reference.name)}:${blankType(reference.type)}")
                    is TypeReference -> add("type:${blankType(reference.type)}")
                }
                // Resource ids (0x7f......) are renumbered between builds; other literals mostly stay.
                (instruction as? WideLiteralInstruction)?.wideLiteral?.let { literal ->
                    if (literal !in 0x7f000000L..0x7fffffffL) add("lit:$literal")
                }
            }
            return Shape(tokens, codeSize(method), head(method, classDef))
        }
    }
}
