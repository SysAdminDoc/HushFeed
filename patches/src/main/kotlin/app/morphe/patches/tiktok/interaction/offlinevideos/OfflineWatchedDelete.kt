/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The line TikTok logs as its watched-video clean-up hands the ids to the delete. Only that step writes it. */
internal const val WATCHED_DELETE_LOG = "deleteWatchedVideosForLowStorage: deleting "

/** When that clean-up last ran, which holds it to one run per window. */
internal const val WATCHED_DELETE_STAMP_KEY = "last_watch_delete_batch_timestamp"

/** The part of the offline table's query that counts saved videos not watched yet and not expired. */
internal const val UNWATCHED_COUNT_SQL = "has_cached = 1 AND has_watched = 0 AND (insert_time + ?) > ?"

/**
 * TikTok's watched-video clean-up for a phone low on space: a step of the offline start-up task,
 * a suspend function, so it answers `Object` and takes its continuation. It checks a server
 * switch, the free space, a flag saying an offline video was played this session and the time
 * since its last run, then asks for the account's watched ids and hands them to the delete that
 * also clears expired videos. Found by the line it logs and the key of its last run.
 */
internal object OfflineWatchedDeleteFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf(WATCHED_DELETE_LOG, WATCHED_DELETE_STAMP_KEY),
)

/**
 * The offline table's count of saved videos not watched yet, in Room's generated access object:
 * the expiry and the time, then the account, answering an int. The query is one long literal, so
 * it is matched by the piece that only this count has.
 */
internal object UnwatchedOfflineCountQueryFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("J", "J", "Ljava/lang/String;"),
    filters = listOf(string(UNWATCHED_COUNT_SQL, StringComparisonType.CONTAINS)),
)

/** What the clean-up calls that the extension calls too. */
internal class WatchedDeleteCalls(
    /** The static question for the current account's watched, unexpired ids, answering a List as Object. */
    val watched: MethodReference,
    /** The static delete for a list of ids, which removes the files, the rows and tells the listeners. */
    val delete: MethodReference,
    /** The static flag that an offline video was played this session. */
    val consumed: FieldReference,
)

/**
 * Reads the three calls off the clean-up. The delete is the one static (List)V of the clean-up's
 * own class it invokes. The question is the static ()Object whose answer lands in the register
 * that delete is handed, the nearest one before it, since other calls answer into other registers
 * on the way. The flag is the boolean of the clean-up's own class it reads, every read the same.
 */
internal fun watchedDeleteCalls(cleanup: Method, patch: String): WatchedDeleteCalls {
    val owner = cleanup.definingClass
    val instructions = cleanup.implementation?.instructions?.toList()
        ?: throw PatchException("$patch: $owner->${cleanup.name} has no implementation.")

    val deletes = instructions.withIndex().filter { (_, instruction) ->
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == owner && it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;")
            } == true
    }
    val (deleteIndex, deleteCall) = deletes.singleOrNull() ?: throw PatchException(
        "$patch: expected the watched-video clean-up to hand one list to a delete of $owner, found ${deletes.size}.",
    )
    val handed = when (deleteCall) {
        is FiveRegisterInstruction -> deleteCall.registerC
        is RegisterRangeInstruction -> deleteCall.startRegister
        else -> throw PatchException("$patch: the delete call in $owner->${cleanup.name} names no register.")
    }

    val asked = (deleteIndex - 1 downTo 1).firstOrNull { index ->
        val result = instructions[index]
        val call = instructions[index - 1]
        result.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (result as OneRegisterInstruction).registerA == handed &&
            (call.opcode == Opcode.INVOKE_STATIC || call.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call.getReference<MethodReference>()?.let {
                it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;"
            } == true
    } ?: throw PatchException(
        "$patch: nothing static answers v$handed before the delete in $owner->${cleanup.name}.",
    )
    val watched = instructions[asked - 1].getReference<MethodReference>()!!

    val flags = instructions.filter { it.opcode == Opcode.SGET_BOOLEAN }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == owner && it.type == "Z" }
        .distinctBy { it.name }
    val consumed = flags.singleOrNull() ?: throw PatchException(
        "$patch: expected the clean-up to read one boolean of $owner, found ${flags.map { it.name }}.",
    )

    return WatchedDeleteCalls(watched, deleteCall.getReference<MethodReference>()!!, consumed)
}

/**
 * The static ()Object beside the watched question that asks the count query: the same class
 * answers the settings page's figures, each a boxed result of one query on the current account.
 * The access object is reached through its interface, so a call to [query]'s name and shape on
 * any of [queryTypes] counts.
 */
internal fun unwatchedCountGetter(holder: ClassDef, query: Method, queryTypes: Set<String>, patch: String): Method {
    val shape = query.parameterTypes.map(CharSequence::toString)
    val getters = holder.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.isEmpty() &&
            method.returnType == "Ljava/lang/Object;" &&
            method.implementation?.instructions?.any { instruction ->
                instruction.getReference<MethodReference>()?.let {
                    it.definingClass in queryTypes && it.name == query.name && it.returnType == query.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == shape
                } == true
            } == true
    }
    return getters.singleOrNull() ?: throw PatchException(
        "$patch: expected one static question on ${holder.type} that counts unwatched offline videos, " +
            "found ${getters.map { it.name }}.",
    )
}
