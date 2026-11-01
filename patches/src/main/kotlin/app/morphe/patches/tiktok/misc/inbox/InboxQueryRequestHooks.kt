/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val INBOX_REGULAR_VM = "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationResultViewModel;"
internal const val INBOX_CREATOR_VM = "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationCreatorResultViewModel;"
internal const val INBOX_ALL_TAB_VM = "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationAllTabResultViewModel;"
internal const val INBOX_WIDGET = "Lcom/ss/android/ugc/aweme/notification/v2/widget/NotificationWidget;"
internal const val INBOX_WIDGET_CONTAINER = "Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidgetContainer;"
internal const val INBOX_PAGE_LOADER = "Lcom/bytedance/ies/powerlist/page/PowerPageLoader;"
private const val INBOX_WIDGET_BASE = "Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidget;"
private const val INBOX_LOAD_MORE_LOG = "onLoadMore()"
private const val INBOX_REFRESH_LOG = "onRefresh(), from = "

/**
 * One reviewed native request entry. [owner] is the class it sits on, or null for a refresh
 * closure, whose merged Kotlin `AwS` class and `invoke` number R8 renumbers on every build.
 */
internal class InboxRequestSpec(
    val label: String, val owner: String?, val parameters: Int,
    val tracked: Boolean = false, val closure: Boolean = false,
    name: String? = null, strings: List<String> = emptyList(),
    filters: List<app.morphe.patcher.InstructionFilter> = emptyList(),
) {
    val counted = tracked || owner in setOf(INBOX_REGULAR_VM, INBOX_CREATOR_VM, INBOX_ALL_TAB_VM)
    val fingerprint = Fingerprint(definingClass = owner, name = name,
        returnType = if (closure) "Ljava/lang/Object;" else "V", parameters = List(parameters) { "L" },
        strings = strings.ifEmpty { null }, filters = filters.ifEmpty { null },
        custom = { method, classDef -> method.parameterTypes.size == parameters &&
            (!closure || AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.single() == classDef.type) })
}

/** A refresh closure: it logs the refresh's origin, casts its owner to [viewModel] and launches the load. */
private fun refreshClosure(label: String, viewModel: String) = InboxRequestSpec(label, null, 1, tracked = true,
    closure = true, strings = listOf(INBOX_REFRESH_LOG), filters = listOf(checkCast(viewModel)))

internal val loadedInboxRequestSpecs = listOf(
    // The container hands load-more to its last enabled widget, walking its widgets backwards.
    InboxRequestSpec("container load more", INBOX_WIDGET_CONTAINER, 0, filters = listOf(
        methodCall(definingClass = "Ljava/util/ListIterator;", name = "hasPrevious"),
        methodCall(definingClass = "Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidgetStatus;", name = "isEnable"),
        methodCall(definingClass = INBOX_WIDGET_BASE, returnType = "V"),
    )),
    InboxRequestSpec("widget load more", INBOX_WIDGET, 0, strings = listOf(INBOX_LOAD_MORE_LOG)),
    InboxRequestSpec("widget refresh", INBOX_WIDGET, 1, strings = listOf(INBOX_REFRESH_LOG)),
    InboxRequestSpec("regular load more", INBOX_REGULAR_VM, 0, tracked = true, strings = listOf(INBOX_LOAD_MORE_LOG)),
    InboxRequestSpec("creator load more", INBOX_CREATOR_VM, 0, tracked = true, strings = listOf(INBOX_LOAD_MORE_LOG)),
    InboxRequestSpec("regular refresh", INBOX_REGULAR_VM, 1, strings = listOf("load but children mode")),
    InboxRequestSpec("creator refresh", INBOX_CREATOR_VM, 1, strings = listOf("load but children mode")),
    InboxRequestSpec("all tab refresh", INBOX_ALL_TAB_VM, 1, strings = listOf("all tab onRefresh(), from = ")),
    refreshClosure("creator refresh closure", INBOX_CREATOR_VM),
    refreshClosure("regular refresh closure", INBOX_REGULAR_VM),
    // PowerPageLoader's three job launches carry no log line, and a build can move each one's
    // name a slot along. Each loads its direction from the loader's enum, whose constants keep their names.
    InboxRequestSpec("page loader load", INBOX_PAGE_LOADER, 1, tracked = true, filters = listOf(fieldAccess(name = "Prev"))),
    InboxRequestSpec("page loader append", INBOX_PAGE_LOADER, 1, tracked = true, filters = listOf(fieldAccess(name = "Next"))),
    InboxRequestSpec("page loader refresh", INBOX_PAGE_LOADER, 0, tracked = true, filters = listOf(fieldAccess(name = "Refresh"))),
)

/**
 * kotlinx.coroutines' launch with defaults (renamed on every build): (scope, context, start,
 * block, mask) answering the Job.
 */
internal fun Instruction.isCoroutineLaunch(): Boolean = opcode == Opcode.INVOKE_STATIC &&
    getReference<MethodReference>()?.let { ref ->
        val types = ref.parameterTypes.map(CharSequence::toString)
        types.size == 5 && types[1] == "Lkotlin/coroutines/CoroutineContext;" &&
            types[3] == "Lkotlin/jvm/functions/Function2;" && types[4] == "I" && ref.returnType.startsWith("L")
    } == true

context(patchContext: BytecodePatchContext)
internal fun loadedInboxRequestSeams() = InboxRequestSeams(loadedInboxRequestSpecs.map { it to it.fingerprint.method })

internal class InboxRequestSeams(private val methods: List<Pair<InboxRequestSpec, MutableMethod>>) {
    init {
        methods.forEach { (spec, method) -> method.loadedRequestHook(spec, true) }
        check(methods.map { it.second }.distinct().size == methods.size) { "Two Loaded Inbox request entries took one method" }
    }
    fun install() { methods.forEach { (spec, method) -> method.loadedRequestHook(spec) } }
}

internal fun MutableMethod.loadedRequestHook(spec: InboxRequestSpec, checkOnly: Boolean = false) {
    check((spec.owner == null || definingClass == spec.owner) && parameterTypes.size == spec.parameters) {
        "Loaded Inbox ${spec.label} moved"
    }
    requireLocals("Loaded Inbox search", 1)
    val native = implementation!!.instructions.toList()
    val returns = native.withIndex().filter { it.value.opcode == if (spec.closure) Opcode.RETURN_OBJECT else Opcode.RETURN_VOID }
    check(returns.isNotEmpty())
    val launches = native.withIndex().filter { it.value.isCoroutineLaunch() }
    if (spec.tracked) check(launches.size == 1) { "Loaded Inbox request must launch exactly one reviewed native job" }
    if (spec.tracked) check(implementation!!.registerCount <= 16)
    if (checkOnly) return

    if (spec.counted) {
        // Return labels must land on the counter cleanup, including branches in the refresh
        // closure. This marks only synchronous method exit, never asynchronous job completion.
        returns.asReversed().forEach { returned ->
            val function = if (spec.closure) "returnedLoadedClosureRequest" else "returnedLoadedRequest"
            replaceInstruction(returned.index, "invoke-static/range {p0 .. p0}, $INBOX_QUERY_EXTENSION->$function(Ljava/lang/Object;)V")
            implementation!!.addInstruction(returned.index + 1, returned.value)
        }
    }
    if (spec.tracked) {
        // The returned Job was previously discarded at VM/loader entries. Capture that genuine
        // result. Existing refresh closures already move it into a native job field.
        val launch = launches.single().value
        val launchIndex = implementation!!.instructions.indexOf(launch)
        val resultInstruction = implementation!!.instructions[launchIndex + 1]
        val existingResult = resultInstruction.opcode == Opcode.MOVE_RESULT_OBJECT
        val result = if (existingResult) (resultInstruction as OneRegisterInstruction).registerA else 0
        val function = if (spec.closure) "onLoadedClosureRequestJob" else "onLoadedRequestJob"
        if (existingResult) addInstruction(launchIndex + 2,
            "invoke-static {p0, v$result}, $INBOX_QUERY_EXTENSION->$function(Ljava/lang/Object;Ljava/lang/Object;)V")
        else addInstructions(launchIndex + 1, """
            move-result-object v0
            invoke-static {p0, v0}, $INBOX_QUERY_EXTENSION->$function(Ljava/lang/Object;Ljava/lang/Object;)V
        """.trimIndent())
    }

    val guard = if (spec.closure) "beginLoadedClosureRequest" else if (spec.counted) "beginLoadedRequest" else "allowLoadedRequest"
    val refusal = if (spec.closure) "sget-object v0, Lkotlin/Unit;->LIZ:Lkotlin/Unit;\nreturn-object v0" else "return-void"
    addInstructionsWithLabels(0, """
        invoke-static/range {p0 .. p0}, $INBOX_QUERY_EXTENSION->$guard(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :native_request
        $refusal
    """.trimIndent(), ExternalLabel("native_request", getInstruction(0)))
}
