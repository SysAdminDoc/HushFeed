/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/WatchHistoryRecording;"

/** Keeps its real name on every build; only its methods and their item class are renamed. */
internal const val AWEME_STATS_API = "Lcom/ss/android/ugc/aweme/feed/api/AwemeStatsApi;"

private fun Method.loads(value: String) =
    implementation?.instructions?.any { it.getReference<StringReference>()?.string == value } == true

private fun Method.isStaticVoidOnStatsApi() =
    definingClass == AWEME_STATS_API && AccessFlags.STATIC.isSet(accessFlags) &&
        returnType == "V" && parameterTypes.size == 1

/**
 * The view report for one watched video (/aweme/v1/aweme/stats/), LIZIZ on 47.0.3, 47.1.3 and
 * 47.1.4 with the item class renamed each time (09gd, 09bJ, 09bN). It either sends at once or,
 * for For You with `basic_vv_batch_size` above one, queues the item and sends a batch when the
 * queue is full. It's the only method that reads that setting, and it already returns early
 * on a path of TikTok's own, so leaving at entry is a state TikTok handles.
 */
internal fun isViewReport(method: Method): Boolean =
    method.isStaticVoidOnStatsApi() && method.parameterTypes.single().toString() != "Ljava/util/List;" &&
        method.loads("basic_vv_batch_size") && method.loads("homepage_hot")

/**
 * The batch send, LIZ(List), reached from the view report when the queue fills and from
 * AwemeStatsBatchFlushComponent when the feed pauses. It returns at once on an empty list.
 */
internal fun isViewReportFlush(method: Method): Boolean =
    method.isStaticVoidOnStatsApi() && method.parameterTypes.single().toString() == "Ljava/util/List;" &&
        method.loads("first_install_time")

private object ViewReportFingerprint : Fingerprint(
    definingClass = AWEME_STATS_API,
    returnType = "V",
    custom = { method, _ -> isViewReport(method) },
)

private object ViewReportFlushFingerprint : Fingerprint(
    definingClass = AWEME_STATS_API,
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    custom = { method, _ -> isViewReportFlush(method) },
)

/**
 * Where a sender has its answer: just past its Future.get(), the wait for the request, and past
 * that call's move-result if it keeps one. Both senders have one get, and null means this build
 * has none.
 */
internal fun Method.afterSent(): Int? {
    val at = sendWait() ?: return null
    val next = implementation!!.instructions.elementAtOrNull(at + 1)?.opcode
    return if (next == Opcode.MOVE_RESULT_OBJECT) at + 2 else at + 1
}

/** The sender's Future.get(), its wait for the request, or null on a build without one. */
internal fun Method.sendWait(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    return instructions.indexOfFirst {
        it.getReference<MethodReference>()?.let { r ->
            r.definingClass == "Ljava/util/concurrent/Future;" && r.name == "get"
        } == true
    }.takeIf { it >= 0 }
}

/**
 * Just past the view report's first read of `basic_vv_batch_size`, the int call that takes the
 * name and its move-result: the index and the register holding the size. Null on a build that
 * reads it some other way.
 */
internal fun Method.afterBatchSizeRead(): Pair<Int, Int>? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val named = instructions.indexOfFirst { it.getReference<StringReference>()?.string == "basic_vv_batch_size" }
    if (named < 0) return null
    val call = (named + 1 until instructions.size).firstOrNull { at ->
        instructions[at].getReference<MethodReference>()?.let { r ->
            r.returnType == "I" && r.parameterTypes.any { it.toString() == "Ljava/lang/String;" }
        } == true
    } ?: return null
    val result = instructions.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) return null
    return call + 2 to (result as OneRegisterInstruction).registerA
}

/**
 * Where the view report stops for TikTok's own request limit (ApiSpringLimitHelper, `09RE` on
 * 47.9.3): just past the branch on the first static check that takes an object and answers a
 * boolean, whose fall-through ends the method within three instructions. Null on a build laid
 * out some other way.
 */
internal fun Method.whereTikTokHoldsBack(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val check = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let { r ->
            r.returnType == "Z" && r.parameterTypes.size == 1 && r.parameterTypes.single().startsWith("L")
        } == true
    }
    if (check < 0) return null
    if (instructions.getOrNull(check + 1)?.opcode != Opcode.MOVE_RESULT) return null
    if (instructions.getOrNull(check + 2)?.opcode != Opcode.IF_EQZ) return null
    val held = check + 3
    val returns = (held until minOf(held + 3, instructions.size)).any { instructions[it].opcode == Opcode.RETURN_VOID }
    return if (returns) held else null
}

/**
 * Sends the batch send's wait through the extension, which counts a failed send and throws it on
 * to TikTok's catch. One invoke swapped for another on the same register, so nothing branches
 * and no register changes type. The index, or null on a build without the wait.
 */
internal fun MutableMethod.countFailedSends(): Int? {
    val at = sendWait() ?: return null
    val operands = when (val wait = implementation!!.instructions[at]) {
        is RegisterRangeInstruction -> "/range {v${wait.startRegister} .. v${wait.startRegister}}"
        is FiveRegisterInstruction -> " {v${wait.registerC}}"
        else -> return null
    }
    replaceInstruction(at, "invoke-static$operands, $EXTENSION->awaitBatch(Ljava/util/concurrent/Future;)Ljava/lang/Object;")
    return at
}

/** Leaves a view report sender before it builds or sends anything when the switch is on. */
internal fun MutableMethod.skipWhenWatchHistoryOff() = guardAtEntry(
    "Stop recording watch history",
    "invoke-static {}, $EXTENSION->shouldSkip()Z",
    "return-void",
)

/**
 * Stops the view report TikTok sends for each video you watch, which is where its Watch history
 * comes from. Both senders return before they build a request: the single report, so nothing is
 * queued, and the batch send, so a queue filled before the switch went on isn't sent either.
 * Stories keep their own guard in Ghost mode, upstream of these.
 */
@Suppress("unused")
val watchHistoryPatch = bytecodePatch(
    name = "Stop recording watch history",
    description = "Stops TikTok adding the videos you watch to your Watch history. The catch: " +
        "your views stop counting, and For You learns less about what you like. Starts off. Turn " +
        "it on in Hushfeed settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Both resolved before anything is written, so a build missing either one leaves the
        // patch unapplied rather than half applied.
        val report = ViewReportFingerprint.method
        val flush = ViewReportFlushFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableWatchHistory()V",
        )
        // The diagnostic report's counts (#448), written from the end back so each index holds.
        // A build where one of them moved just goes without that count.
        val reportWrites = listOfNotNull(
            report.afterSent()?.let { it to "invoke-static {}, $EXTENSION->noteSentAlone()V" },
            report.afterBatchSizeRead()?.let { (at, register) ->
                at to "invoke-static {v$register}, $EXTENSION->noteBatchSize(I)V"
            },
            report.whereTikTokHoldsBack()?.let { it to "invoke-static {}, $EXTENSION->noteHeldByTikTok()V" },
        )
        reportWrites.sortedByDescending { it.first }.forEach { (at, smali) -> report.addInstruction(at, smali) }
        // The sent count goes in past the wait first, then the wait itself is swapped in place.
        flush.afterSent()?.let { flush.addInstruction(it, "invoke-static {}, $EXTENSION->noteBatchSent()V") }
        flush.countFailedSends()
        report.skipWhenWatchHistoryOff()
        flush.skipWhenWatchHistoryOff()
        // Ahead of the guards, so a report the switch holds back still counts as made.
        report.addInstruction(0, "invoke-static {}, $EXTENSION->noteReport()V")
        flush.addInstruction(0, "invoke-static {p0}, $EXTENSION->noteBatch(Ljava/util/List;)V")
    }
}
