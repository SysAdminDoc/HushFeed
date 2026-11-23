/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

// TikTok Lite (#502) is its own build: its reporters keep TikTok's model and API class names but
// sit in different, obfuscated callers, so each is found by the API call it makes.
internal const val LITE_STORY_API = "Lcom/ss/android/ugc/aweme/story/api/StoryApi;"
internal const val LITE_PROFILE_VIEWER_API = "Lcom/ss/android/ugc/aweme/profile/viewer/api/ProfileViewerApi;"
internal const val LITE_ACTIVE_STATUS_RESULT = "Lcom/ss/android/ugc/aweme/im/activestatus/api/network/Result;"
internal const val LITE_ACTIVE_STATUS_REPORT = "/tiktok/v1/activity_status/report"
private const val KOTLIN_UNIT = "Lkotlin/Unit;"

/**
 * Lite's story view report: a Function0 (`LX/LJC;` on 47.8.2) whose invoke() asks StoryApi to
 * report the story viewed and hands the request's task back to an executor that drops it.
 */
internal object LiteStoryViewReportFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(methodCall(definingClass = LITE_STORY_API, name = "reportStoryViewed")),
)

/**
 * Lite's profile visit record: a Callable (`LX/HBW;` on 47.8.2) that the other person's profile
 * page runs once, which posts the visit to their Profile views list and answers Unit.
 */
internal object LiteProfileViewReportFingerprint : Fingerprint(
    name = "call",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(methodCall(definingClass = LITE_PROFILE_VIEWER_API, name = "recordView")),
)

/**
 * Lite's play report sender (/aweme/v1/aweme/stats/, `LX/E9i;->LBL` on 47.8.2). Every video and
 * story that starts playing sends one, the Aweme third, and a story's puts you on its viewer list
 * just as TikTok's does (#39). It's the one static sender of this shape that reads where the
 * video came from.
 */
internal object LiteStoryStatsSenderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/String;", "I", AWEME, "Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(definingClass = AWEME, name = "itemDistributeSource"),
        fieldAccess(definingClass = AWEME, name = "mItemSourceCategory"),
    ),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Lite's activity status report (`LX/YhF;->LB` on 47.8.2), a suspend function that reads its
 * status API from a field, calls the report on it and turns the Result into the list it answers.
 * With no API object it sends nothing and answers an empty list.
 */
internal object LiteOnlineStatusReportFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Integer;", "L"),
    filters = listOf(checkCast(LITE_ACTIVE_STATUS_RESULT)),
)

/** Locals a method has below its parameters, from the original (not yet mutable) method. */
private fun Method.locals(): Int {
    val parameters = parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2L else 1L }.toInt() +
        if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    return (implementation?.registerCount ?: 0) - parameters
}

private fun Method.code(what: String) = implementation?.instructions?.toList()
    ?: throw PatchException("Ghost mode: Lite's $what has no code.")

private fun Method.requireGuardLocal(what: String) {
    if (locals() < 1) throw PatchException("Ghost mode: Lite's $what has no local register for the guard.")
}

/**
 * Checks Lite's story view lambda hands back exactly what the report call answers and nothing
 * else, so a null in its place skips the report and leaves the dropped task the only thing
 * missing. Throws before anything is written when the lambda does more than that.
 */
internal fun planLiteStoryViewSkip(method: Method) {
    val code = method.code("story view report")
    val calls = code.indices.filter {
        code[it].getReference<MethodReference>()?.let { called ->
            called.definingClass == LITE_STORY_API && called.name == "reportStoryViewed"
        } == true
    }
    val call = calls.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one story view report in Lite's lambda, found ${calls.size}.")
    val move = code.getOrNull(call + 1)
    val returned = code.getOrNull(call + 2)
    if (move?.opcode != Opcode.MOVE_RESULT_OBJECT || returned?.opcode != Opcode.RETURN_OBJECT ||
        (move as OneRegisterInstruction).registerA != (returned as OneRegisterInstruction).registerA
    ) {
        throw PatchException("Ghost mode: Lite's story view lambda no longer hands back the report it makes.")
    }
    if (code.count { it.opcode == Opcode.RETURN_OBJECT } != 1) {
        throw PatchException("Ghost mode: Lite's story view lambda has more than one answer.")
    }
    method.requireGuardLocal("story view report")
}

/** Returns null from Lite's story view lambda before it reports, while the guard says so. */
internal fun MutableMethod.skipLiteStoryView() = guardAtEntry(
    "Ghost mode",
    "invoke-static {}, $GHOST_MODE_EXTENSION->shouldBlockStoryView()Z",
    "const/4 v0, 0x0\nreturn-object v0",
)

/**
 * Checks Lite's profile visit Callable records one visit and always answers Unit, and returns the
 * Unit field it answers with, so the guard can answer the same. Throws before anything is
 * written when either moved.
 */
internal fun planLiteProfileViewSkip(method: Method): String {
    val code = method.code("profile visit record")
    val records = code.count {
        it.getReference<MethodReference>()?.let { called ->
            called.definingClass == LITE_PROFILE_VIEWER_API && called.name == "recordView"
        } == true
    }
    if (records != 1) throw PatchException("Ghost mode: expected one profile visit record in Lite's call, found $records.")
    val answers = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.map { index ->
        val register = (code[index] as OneRegisterInstruction).registerA
        val load = code.getOrNull(index - 1)
        val field = load?.takeIf {
            it.opcode == Opcode.SGET_OBJECT && (it as OneRegisterInstruction).registerA == register
        }?.getReference<FieldReference>()?.takeIf { it.type == KOTLIN_UNIT }
            ?: throw PatchException("Ghost mode: Lite's profile visit record no longer answers Unit.")
        "${field.definingClass}->${field.name}:${field.type}"
    }.distinct()
    val unit = answers.singleOrNull()
        ?: throw PatchException("Ghost mode: Lite's profile visit record answers ${answers.size} ways.")
    method.requireGuardLocal("profile visit record")
    return unit
}

/** Answers Unit from Lite's profile visit Callable before it records the visit, while the guard says so. */
internal fun MutableMethod.skipLiteProfileView(unit: String) = guardAtEntry(
    "Ghost mode",
    "invoke-static {}, $GHOST_MODE_EXTENSION->shouldBlockProfileView()Z",
    "sget-object v0, $unit\nreturn-object v0",
)

/**
 * The parameter register (`pN`) holding the Aweme in Lite's play report sender. Throws when the
 * sender takes no Aweme, or more than one, or has no local for the guard.
 */
internal fun planLiteStoryStatsGuard(method: Method): String {
    val types = method.parameterTypes.map { it.toString() }
    if (types.count { it == AWEME } != 1) throw PatchException("Ghost mode: Lite's play report sender no longer takes one Aweme.")
    val register = types.take(types.indexOf(AWEME)).sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt() +
        if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    method.code("play report sender")
    method.requireGuardLocal("play report sender")
    return "p$register"
}

/** Returns from Lite's play report sender before it builds a story's report, while the guard says so. */
internal fun MutableMethod.guardLiteStoryStats(aweme: String) = guardAtEntry(
    "Ghost mode",
    "invoke-static/range {$aweme .. $aweme}, $GHOST_MODE_EXTENSION->shouldBlockStoryStats($AWEME)Z",
    "return-void",
)

/** Where the status API object is read and checked, in Lite's activity status report. */
internal class LiteOnlineStatusGate(val index: Int, val register: Int, val apiType: String)

/**
 * Finds the one report call in Lite's activity status reporter (its Retrofit method posts to
 * [LITE_ACTIVE_STATUS_REPORT]), and the one place the API object it's made on is read from a field
 * and checked for null. The hook goes between the two, so a held report takes Lite's own path for
 * a missing API, which sends nothing. Throws before anything is written when any of it moved, or
 * when something jumps straight to the check.
 */
internal fun planLiteOnlineStatusGate(method: MutableMethod, classOf: (String) -> ClassDef?): LiteOnlineStatusGate {
    val code = method.implementation?.instructions?.toList()
        ?: throw PatchException("Ghost mode: Lite's activity status report has no code.")
    val reports = code.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@mapNotNull null
        val called = instruction.getReference<MethodReference>() ?: return@mapNotNull null
        val declared = classOf(called.definingClass)?.methods?.firstOrNull {
            it.name == called.name && it.returnType == called.returnType &&
                it.parameterTypes.map(CharSequence::toString) == called.parameterTypes.map(CharSequence::toString)
        } ?: return@mapNotNull null
        val posts = declared.annotations.any { annotation ->
            annotation.elements.any { (it.value as? StringEncodedValue)?.value == LITE_ACTIVE_STATUS_REPORT }
        }
        called.definingClass.takeIf { posts }
    }
    val api = reports.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one activity status report call in Lite, found ${reports.size}.")
    val checks = code.indices.filter { index ->
        val read = code[index]
        val check = code.getOrNull(index + 1)
        read.opcode == Opcode.IGET_OBJECT && read.getReference<FieldReference>()?.type == api &&
            check?.opcode == Opcode.IF_EQZ &&
            (check as OneRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA
    }
    val read = checks.singleOrNull()
        ?: throw PatchException("Ghost mode: expected Lite's activity status API read and checked once, found ${checks.size}.")
    val check = code[read + 1]
    if (check.location.labels.isNotEmpty()) {
        throw PatchException("Ghost mode: something jumps to Lite's activity status API check.")
    }
    return LiteOnlineStatusGate(read + 1, (check as OneRegisterInstruction).registerA, api)
}

/** Hands the status API object to the extension, which answers null while the report is held back. */
internal fun MutableMethod.gateLiteOnlineStatus(gate: LiteOnlineStatusGate) = addInstructions(
    gate.index,
    """
        invoke-static/range {v${gate.register} .. v${gate.register}}, $GHOST_MODE_EXTENSION->onlineStatusReporter(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v${gate.register}
        check-cast v${gate.register}, ${gate.apiType}
    """,
)

private fun Fingerprint.liteSingle(what: String): MutableMethod {
    val found = matchAllOrNull().orEmpty().map { it.method }
    return found.singleOrNull()
        ?: throw PatchException("Ghost mode: expected one $what in TikTok Lite, found ${found.size}.")
}

/**
 * Ghost mode in TikTok Lite (#502): story views, profile visits, typing and online status, each
 * under its own switch as on TikTok. Lite has no LIVE room enter or network lancet to hold LIVE
 * enters and likes in, and no story ring status to keep watched, so those parts stay TikTok's.
 * Everything is found and checked before the first write.
 */
internal fun ghostModeOnLite() {
    val storyView = LiteStoryViewReportFingerprint.liteSingle("story view report")
    planLiteStoryViewSkip(storyView)
    val profileView = LiteProfileViewReportFingerprint.liteSingle("profile visit record")
    val unit = planLiteProfileViewSkip(profileView)
    val typing = TypingStatusSenderFingerprint.matchAll().filter { it.originalMethod.implementation != null }.map { it.method }
    if (typing.isEmpty() || typing.any { it.returnType != "V" }) {
        throw PatchException("Ghost mode: expected Lite's typing sender to return nothing, found ${typing.size} senders.")
    }
    typing.forEach { it.requireLocals("Ghost mode", 1) }
    val stats = LiteStoryStatsSenderFingerprint.liteSingle("play report sender")
    val aweme = planLiteStoryStatsGuard(stats)
    val status = LiteOnlineStatusReportFingerprint.liteSingle("activity status report")
    val gate = planLiteOnlineStatusGate(status) { classDefByOrNull(it) }

    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableGhostMode()V",
    )
    SettingsStatusLoadFingerprint.method.addInstruction(0, "invoke-static {}, $GHOST_MODE_EXTENSION->installed()V")
    storyView.skipLiteStoryView()
    profileView.skipLiteProfileView(unit)
    if (typing.any { !it.returnBeforeReporting("shouldBlockTypingStatus") }) {
        throw PatchException("Ghost mode: could not install every shouldBlockTypingStatus hook.")
    }
    stats.guardLiteStoryStats(aweme)
    status.gateLiteOnlineStatus(gate)
}
