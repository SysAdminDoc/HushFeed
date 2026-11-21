/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The line TikTok's offline update task logs as it starts, with how many saved videos are unwatched. Only that task writes it. */
internal const val OFFLINE_UPDATE_TASK_LOG = "[UpdateExp][updateTask] begin, curUnwatchVideoCount: "

/** The offline table's queries as [normalizedSql] reads them: one line, single spaces, lower case. */
internal const val UNWATCHED_LIST_SQL = "select * from offline_mode_feed_pb where sec_uid = ? and " +
    "aweme_struct is not null and (insert_time + ?) > ? and has_watched = 0 order by insert_time asc"
internal const val SAVED_COUNT_SQL =
    "select count(aid) from offline_mode_feed_pb where sec_uid = ? and (insert_time + ?) > ?"
internal const val WATCHED_COUNT_SQL =
    "select count(aid) from offline_mode_feed_pb where sec_uid = ? and has_watched = 1 and (insert_time + ?) > ?"

/**
 * TikTok's offline update task, which tops the saved videos up to the limit every few minutes. It
 * is a suspend function, so it answers `Object`. Found by the line it logs as it starts.
 */
internal object OfflineUpdateTaskFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf(OFFLINE_UPDATE_TASK_LOG),
)

/** The offline table's count of the account's unexpired videos, watched or not, in Room's generated access object. */
internal object SavedOfflineCountQueryFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("J", "J", "Ljava/lang/String;"),
    filters = listOf(string("WHERE sec_uid = ? AND (insert_time + ?) > ?", StringComparisonType.CONTAINS)),
)

/** The same count of only the watched ones. */
internal object WatchedOfflineCountQueryFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("J", "J", "Ljava/lang/String;"),
    filters = listOf(
        string("WHERE sec_uid = ? AND has_watched = 1 AND (insert_time + ?) > ?", StringComparisonType.CONTAINS),
    ),
)

/** The extension the counts go through. */
internal const val UNWATCHED_COUNT_EXTENSION = "Lapp/morphe/extension/tiktok/offline/OfflineUnwatchedCount;"

/** Where the unwatched count goes in (#482). */
internal class UnwatchedCountSites(
    /** The update task's calls to the loader whose answer is only counted, by instruction index. */
    val sites: List<Int>,
    /** TikTok's loader of the unwatched offline videos, a static ()List. */
    val loader: MethodReference,
    /** The loader's call that asks the database for the videos, by instruction index. */
    val query: Int,
    /** The two counts, as the loader's call reaches them: through the access object's interface. */
    val saved: MethodReference,
    val watched: MethodReference,
)

/**
 * Finds where the update task loads every unwatched offline video only to count them, and the
 * counts that answer the same question without loading anything.
 *
 * The update task asks a static loader for the list and reads its size, twice on 47.9.3; a third
 * ask, behind a debug switch, logs the ids and is left alone. The loader asks the access object's
 * interface for the rows with the expiry, the time and the account, and decodes each into a
 * video. The two counts are found by their own queries, held to the list query's exact text and
 * to binding the same parameters in the same places, so saved minus watched is the length of the
 * list the loader would have built.
 */
internal fun unwatchedCountSites(
    task: Method,
    saved: Method,
    watched: Method,
    classOf: (String) -> ClassDef?,
    patch: String,
): UnwatchedCountSites {
    if (saved.definingClass != watched.definingClass) {
        throw PatchException("$patch: the two offline counts are on ${saved.definingClass} and ${watched.definingClass}.")
    }
    val access = classOf(saved.definingClass)
        ?: throw PatchException("$patch: ${saved.definingClass} is not in the app.")
    for ((label, method, sql) in listOf(Triple("saved", saved, SAVED_COUNT_SQL), Triple("watched", watched, WATCHED_COUNT_SQL))) {
        if (normalizedSql(method) != sql) {
            throw PatchException("$patch: the $label count ${access.type}->${method.name} asks \"${normalizedSql(method)}\".")
        }
    }

    val loads = sizeOnlyLoads(task)
    val matches = loads.map { it.second }.distinctBy(::signature).mapNotNull { loader ->
        val method = classOf(loader.definingClass)?.methods?.singleOrNull { it.matches(loader) } ?: return@mapNotNull null
        val query = unwatchedQuery(method) ?: return@mapNotNull null
        val asked = method.implementation!!.instructions.elementAt(query).getReference<MethodReference>()!!
        if (asked.definingClass !in access.interfaces) return@mapNotNull null
        val list = access.methods.singleOrNull { it.matches(asked) } ?: return@mapNotNull null
        if (normalizedSql(list) != UNWATCHED_LIST_SQL) return@mapNotNull null
        Triple(loader, query, Pair(asked, list))
    }
    val (loader, query, listed) = matches.singleOrNull() ?: throw PatchException(
        "$patch: expected the offline update task to count one loader of the unwatched videos, found " +
            "${matches.map { signature(it.first) }} among ${loads.map { signature(it.second) }}.",
    )
    val (asked, list) = listed

    val binding = boundParameters(list)
    for ((label, method) in listOf("saved" to saved, "watched" to watched)) {
        if (binding.isEmpty() || boundParameters(method) != binding) {
            throw PatchException(
                "$patch: the $label count binds its arguments as ${boundParameters(method)}, the list as $binding.",
            )
        }
    }

    // The loader reaches the access object through its interface, and so do the counts.
    val face = classOf(asked.definingClass)
        ?: throw PatchException("$patch: ${asked.definingClass} is not in the app.")
    fun throughFace(method: Method): MethodReference = face.methods.singleOrNull { it.matches(method) }
        ?: throw PatchException("$patch: ${face.type} does not declare ${method.name}.")

    return UnwatchedCountSites(
        sites = loads.filter { signature(it.second) == signature(loader) }.map { it.first },
        loader = loader,
        query = query,
        saved = throughFace(saved),
        watched = throughFace(watched),
    )
}

/**
 * The writes: the extension's loader stub becomes a call to TikTok's loader, the loader counts in
 * front of its query, and the update task's size-only asks go through the extension.
 */
internal fun countUnwatchedOfflineVideos(task: MutableMethod, found: UnwatchedCountSites) {
    val extension = mutableClassDefBy(UNWATCHED_COUNT_EXTENSION)
    extension.fillStub(
        extension.methods.single { it.name == "loadNative" },
        """
            invoke-static {}, ${signature(found.loader)}
            move-result-object v0
            return-object v0
        """,
    )
    mutableClassDefBy(found.loader.definingClass).methods.single {
        it.name == found.loader.name && it.returnType == found.loader.returnType && it.parameterTypes.isEmpty()
    }.countInFrontOfQuery(found)
    task.countAtSizeOnlyLoads(found)
}

/**
 * In front of the loader's query: while the extension marks the thread as counting, asks the two
 * counts with the query's own arguments and answers the extension's list of their difference.
 * The query's result register is written over right after it, so it is free before it, and the
 * receiver is free once both counts are asked. Straight on to the query otherwise.
 */
internal fun MutableMethod.countInFrontOfQuery(found: UnwatchedCountSites) {
    val query = getInstruction(found.query)
    val registers = registersOf(query)
    val (invoke, arguments) = if (query is RegisterRangeInstruction) {
        "invoke-interface/range" to "{v${registers.first()} .. v${registers.last()}}"
    } else {
        "invoke-interface" to registers.joinToString(", ", "{", "}") { "v$it" }
    }
    val result = getInstruction<OneRegisterInstruction>(found.query + 1).registerA
    val receiver = registers.first()
    addInstructionsWithLabels(
        found.query,
        """
            invoke-static {}, $UNWATCHED_COUNT_EXTENSION->counting()Z
            move-result v$result
            if-eqz v$result, :morphe_load_unwatched
            $invoke $arguments, ${signature(found.saved)}
            move-result v$result
            $invoke $arguments, ${signature(found.watched)}
            move-result v$receiver
            invoke-static {v$result, v$receiver}, $UNWATCHED_COUNT_EXTENSION->unwatched(II)Ljava/util/List;
            move-result-object v$result
            return-object v$result
        """,
        ExternalLabel("morphe_load_unwatched", query),
    )
}

/**
 * Points each of the update task's size-only asks at the extension, which marks the thread and
 * calls the same loader. The list's register is dropped right after each.
 */
internal fun MutableMethod.countAtSizeOnlyLoads(found: UnwatchedCountSites) {
    for (site in found.sites) {
        replaceInstruction(site, "invoke-static {}, $UNWATCHED_COUNT_EXTENSION->countedUnwatched()Ljava/util/List;")
    }
}

/**
 * The task's calls to a static ()List whose answer only has its size read: the call, its result,
 * `size()` on that result and its own result, and then the list's register written over before
 * anything reads it. Nothing else ever sees such a list, so a list of the same length holding
 * nothing answers it the same.
 */
internal fun sizeOnlyLoads(task: Method): List<Pair<Int, MethodReference>> {
    val instructions = task.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.mapNotNull { index ->
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        val loader = call.getReference<MethodReference>() ?: return@mapNotNull null
        if (loader.parameterTypes.isNotEmpty() || loader.returnType != "Ljava/util/List;") return@mapNotNull null
        val result = instructions.getOrNull(index + 1)
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        val list = (result as OneRegisterInstruction).registerA
        val size = instructions.getOrNull(index + 2) ?: return@mapNotNull null
        val sized = size.getReference<MethodReference>()
        if ((size.opcode != Opcode.INVOKE_INTERFACE && size.opcode != Opcode.INVOKE_INTERFACE_RANGE) ||
            sized?.name != "size" || sized.returnType != "I" || sized.parameterTypes.isNotEmpty() ||
            sized.definingClass !in setOf("Ljava/util/List;", "Ljava/util/Collection;") ||
            firstRegister(size) != list
        ) {
            return@mapNotNull null
        }
        val count = instructions.getOrNull(index + 3)
        if (count?.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        val dropped = (count as OneRegisterInstruction).registerA == list ||
            instructions.getOrNull(index + 4)?.overwritesWithoutReading(list) == true
        if (dropped) index to loader else null
    }
}

/**
 * The loader's one call through an interface that takes the expiry, the time and the account and
 * answers the rows, or null. Code goes in front of it, so nothing may branch onto it, no try block
 * may cover the loader, the register it answers into can't be one it reads, and the two registers
 * the counts answer into have to fit a plain call.
 */
internal fun unwatchedQuery(loader: Method): Int? {
    val body = loader.implementation ?: return null
    if (body.tryBlocks.isNotEmpty()) return null
    val instructions = body.instructions.toList()
    val queries = instructions.indices.filter { index ->
        val call = instructions[index]
        (call.opcode == Opcode.INVOKE_INTERFACE || call.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            call.getReference<MethodReference>()?.let {
                it.returnType == "Ljava/util/List;" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("J", "J", "Ljava/lang/String;")
            } == true
    }
    val query = queries.singleOrNull() ?: return null
    val answer = instructions.getOrNull(query + 1)
    if (answer?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val result = (answer as OneRegisterInstruction).registerA
    val read = registersOf(instructions[query])
    if (result in read || result > 15 || read.first() > 15 || loader.isBranchTarget(query)) return null
    return query
}

/** The registers a call reads, in order. */
internal fun registersOf(call: Instruction): List<Int> = when (call) {
    is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
    is FiveRegisterInstruction ->
        listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    else -> emptyList()
}

/** The query a Room access method runs, as one line of single spaces in lower case: its first string. */
internal fun normalizedSql(method: Method): String? = method.implementation?.instructions
    ?.firstOrNull { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
    ?.getReference<StringReference>()?.string
    ?.trim()?.split(Regex("\\s+"))?.joinToString(" ")?.lowercase()

/**
 * Which parameter each `?` of a Room query is bound to, by its position: Room binds the
 * statement's arguments one call each before it runs the query. Two queries with the same text
 * read the expiry and the time the same way only when this matches too.
 */
internal fun boundParameters(method: Method): Map<Int, Int> {
    val body = method.implementation ?: return emptyMap()
    val instructions = body.instructions.toList()
    val wide = setOf("J", "D")
    val static = AccessFlags.STATIC.isSet(method.accessFlags)
    val parameterRegisters = method.parameterTypes.sumOf { if (it.toString() in wide) 2 else 1 } + if (static) 0 else 1
    var register = body.registerCount - parameterRegisters + if (static) 0 else 1
    val parameterAt = HashMap<Int, Int>()
    method.parameterTypes.forEachIndexed { index, type ->
        parameterAt[register] = index
        register += if (type.toString() in wide) 2 else 1
    }
    val bound = LinkedHashMap<Int, Int>()
    for ((index, instruction) in instructions.withIndex()) {
        val call = instruction.getReference<MethodReference>() ?: continue
        if (call.returnType == "Landroid/database/Cursor;") break
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL || call.returnType != "V") continue
        val shape = call.parameterTypes.map(CharSequence::toString)
        if (shape != listOf("I", "J") && shape != listOf("I", "Ljava/lang/String;")) continue
        val five = instruction as? FiveRegisterInstruction ?: continue
        val position = (lastWrite(instructions, index, five.registerD)?.second as? NarrowLiteralInstruction)
            ?.narrowLiteral ?: continue
        bound[position] = parameterAt[sourceRegister(instructions, index, five.registerE)] ?: -1
    }
    return bound
}

/** The register [register] was copied from before [index], following moves back to where it came from. */
private fun sourceRegister(instructions: List<Instruction>, index: Int, register: Int): Int {
    val (at, write) = lastWrite(instructions, index, register) ?: return register
    val moves = setOf(
        Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16,
        Opcode.MOVE_WIDE_16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
    )
    return if (write.opcode in moves) sourceRegister(instructions, at, (write as TwoRegisterInstruction).registerB) else -1
}

/** The last instruction before [index] that writes [register], read straight back without following branches. */
private fun lastWrite(instructions: List<Instruction>, index: Int, register: Int): Pair<Int, Instruction>? =
    (index - 1 downTo 0).firstOrNull { at ->
        val instruction = instructions[at]
        instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == register
    }?.let { it to instructions[it] }

/** Whether this writes [register] without reading it first. */
private fun Instruction.overwritesWithoutReading(register: Int) =
    opcode.setsRegister() && this is OneRegisterInstruction && this !is TwoRegisterInstruction &&
        opcode != Opcode.CHECK_CAST && registerA == register

private fun firstRegister(call: Instruction): Int? = registersOf(call).firstOrNull()

private fun Method.matches(reference: MethodReference) = name == reference.name && returnType == reference.returnType &&
    parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)

private fun signature(reference: MethodReference) =
    "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"
