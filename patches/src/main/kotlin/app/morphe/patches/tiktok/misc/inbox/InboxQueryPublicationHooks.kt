/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val INBOX_QUERY_EXTENSION = "Lapp/morphe/extension/tiktok/inbox/InboxFilter;"
internal const val INBOX_RESULT_FRAGMENT = "Lcom/ss/android/ugc/aweme/notification/v2/NotificationResultFragment;"
internal const val INBOX_LIST_CHANGE_CONFIG = "Lcom/ss/android/ugc/aweme/inbox/widget/change/InboxListChangeConfigModel;"
internal const val INBOX_INDICES_GATE = "hushLoadedInboxIndicesEnabled"

internal object InboxListPublication : Fingerprint(
    definingClass = "Lcom/bytedance/ies/powerlist/ListState;", name = "LIZ", returnType = "V",
    parameters = listOf("Lcom/bytedance/ies/powerlist/ListState;", "Ljava/util/List;", "Z", "Z", "Ljava/lang/Runnable;", "I"),
)
internal object InboxStubPublication : Fingerprint(
    definingClass = "Lcom/bytedance/ies/powerlist/PowerStub;", name = "onChanged", returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
)
/**
 * PowerList's diff submit, by its log line (47.4.3 and 47.5.3 keep the method name on a renamed
 * class). Its last parameter is the list update's metadata, which [InboxDiffAcceptance] and
 * PowerStub's publication share.
 */
internal object InboxDiffSubmission : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "Ljava/util/List;", "Ljava/util/List;", "L"),
    strings = listOf("notifyDiffer. useAsync:"),
)
/** The same differ dispatching an accepted diff: (diff result, metadata, update callback). */
internal object InboxDiffAcceptance : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", "L"),
    strings = listOf("[Start] dispatchAndNotifyUpdatesTo: time:"),
)
/** The result fragment's list publication (47.4.3 `tb`, 47.5.3 `bb`), by its log line. */
internal object InboxFragmentPublication : Fingerprint(
    definingClass = INBOX_RESULT_FRAGMENT, returnType = "V", parameters = listOf("Ljava/util/List;"),
    strings = listOf("refreshList, dataList.size = "),
)
/**
 * The merged Kotlin closure the fragment posts with each published list (a different `AwS`
 * class and `invoke` number on each build): it asks the live config's enable switch first,
 * casts its owner to the result fragment and hands the list and the visible range to the
 * fragment's index helper.
 */
internal object InboxIndexedCallback : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
    filters = listOf(methodCall(returnType = INBOX_LIST_CHANGE_CONFIG), checkCast(INBOX_RESULT_FRAGMENT)),
    custom = { method, classDef ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.single() == classDef.type &&
            method.opensWithListChangeConfig() && method.implementation!!.instructions.count { it.isIndexedListCall() } == 1
    },
)
/** The fragment's visible-range pass: it reads the last visible row and scrolls to an unread one. */
internal object InboxVisibleIndices : Fingerprint(
    definingClass = INBOX_RESULT_FRAGMENT, returnType = "V", parameters = listOf("Z"),
    filters = listOf(
        methodCall(definingClass = "Landroidx/recyclerview/widget/LinearLayoutManager;", name = "findLastVisibleItemPosition"),
        methodCall(name = "startSmoothScroll"),
    ),
)
internal object InboxNotificationClick : Fingerprint(
    definingClass = TEMPLATE_NOTIFICATION_CELL, name = "onClick", returnType = "V", parameters = listOf("Landroid/view/View;"),
)
/**
 * PowerList's pull-to-refresh layout (a renamed class on each build): the FrameLayout that keeps
 * its own getManuallyRefreshAnimator(). Its setRefreshing(Z) keeps the real name. The patch
 * matches it only in [InboxSpringManualRefresh]'s class, and the fixture test holds them to one.
 */
internal object InboxSpringRefresh : Fingerprint(
    name = "setRefreshing", returnType = "V", parameters = listOf("Z"),
    custom = { _, classDef -> classDef.isInboxSpringLayout() },
)
/** The same layout's manual refresh: setRefreshing(true), its manual flag, then the animator. */
internal object InboxSpringManualRefresh : Fingerprint(
    returnType = "V", parameters = emptyList(),
    filters = listOf(
        methodCall(name = "setRefreshing", parameters = listOf("Z")),
        methodCall(name = "getManuallyRefreshAnimator"),
        methodCall(definingClass = "Landroid/animation/ValueAnimator;", name = "start"),
    ),
    custom = { _, classDef -> classDef.isInboxSpringLayout() },
)

internal fun ClassDef.isInboxSpringLayout() = superclass == "Landroid/widget/FrameLayout;" &&
    methods.any { it.name == "getManuallyRefreshAnimator" && it.returnType == "Landroid/animation/ValueAnimator;" }

/** Opens with the static live config getter, as the native enable check does. */
internal fun Method.opensWithListChangeConfig(): Boolean {
    val first = implementation?.instructions?.firstOrNull() ?: return false
    val getter = first.getReference<MethodReference>() ?: return false
    return first.opcode == Opcode.INVOKE_STATIC && getter.parameterTypes.isEmpty() && getter.returnType == INBOX_LIST_CHANGE_CONFIG
}

/** The index helper's (List, Pair) entry the indexed closure calls. */
internal fun Instruction.isIndexedListCall(): Boolean =
    (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_INTERFACE) && getReference<MethodReference>()?.let { ref ->
        ref.returnType == "V" && ref.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;", "Lkotlin/Pair;")
    } == true

/** Resolve and validate the complete group before the patch writes any instruction. */
context(patchContext: BytecodePatchContext)
internal fun loadedInboxPublicationSeams(): InboxPublicationSeams {
    val indexed = InboxIndexedCallback.method
    val owner = patchContext.mutableClassDefBy(INBOX_RESULT_FRAGMENT)
    check(owner.methods.none { it.name == INBOX_INDICES_GATE }) { "Loaded Inbox index gate already exists" }
    val gate = loadedIndicesGate(indexed)
    val submit = InboxDiffSubmission.method
    val accepted = InboxDiffAcceptance.method
    val metadata = submit.parameterTypes[3].toString()
    check(accepted.definingClass == submit.definingClass && accepted.parameterTypes[1].toString() == metadata) {
        "Loaded Inbox diff submit and acceptance are not one differ"
    }
    // setRefreshing(Z) has nothing the patcher can look up, so it's matched in the class that holds
    // the manual refresh InboxSpringManualRefresh found, rather than in every class of TikTok.
    val manualRefresh = InboxSpringManualRefresh.method
    val refresh = InboxSpringRefresh.match(patchContext.classDefBy(manualRefresh.definingClass)).method
    return InboxPublicationSeams(InboxListPublication.method,
        InboxStubPublication.method, submit, accepted,
        InboxFragmentPublication.method, indexed, InboxVisibleIndices.method, InboxNotificationClick.method,
        refresh, manualRefresh, metadata) { owner.methods.add(gate) }
}

internal class InboxPublicationSeams(
    private val publication: MutableMethod, private val stub: MutableMethod,
    private val submit: MutableMethod, private val accepted: MutableMethod,
    private val fragment: MutableMethod, private val indexed: MutableMethod,
    private val visible: MutableMethod, private val click: MutableMethod,
    private val refresh: MutableMethod, private val manualRefresh: MutableMethod,
    private val metadata: String, private val installGate: () -> Unit,
) {
    init {
        publication.loadedPublicationHook(true)
        stub.loadedStubHook(metadata, true)
        submit.loadedDiffSubmissionHook(true)
        accepted.loadedDiffAcceptanceHook(true)
        indexed.loadedIndexedHook(true)
        visible.loadedVisibleHook(true)
        click.requireLocals("Loaded Inbox search", 1)
        refresh.loadedRefreshHook(true)
        manualRefresh.loadedRefreshHook(true)
    }
    fun install() {
        publication.loadedPublicationHook()
        stub.loadedStubHook(metadata)
        submit.loadedDiffSubmissionHook()
        accepted.loadedDiffAcceptanceHook()
        indexed.loadedIndexedHook()
        visible.loadedVisibleHook()
        fragment.addInstruction(0, "invoke-static/range {p0 .. p0}, $INBOX_QUERY_EXTENSION->onLoadedFragmentBound(Ljava/lang/Object;)V")
        click.loadedGuard("allowLoadedNotificationClick")
        refresh.loadedRefreshHook()
        manualRefresh.loadedRefreshHook()
        installGate()
    }
}

/** Repeat the native live enable check without asking ZX to initialize its lazy helper. */
internal fun loadedIndicesGate(indexed: MutableMethod): MutableMethod {
    val code = indexed.implementation!!.instructions.toList()
    val getter = code[0].getReference<MethodReference>()
    val enabled = code[2].getReference<FieldReference>()
    check(indexed.opensWithListChangeConfig() && getter != null)
    check(code[1].opcode == Opcode.MOVE_RESULT_OBJECT && code[2].opcode == Opcode.IGET_BOOLEAN
        && enabled?.definingClass == INBOX_LIST_CHANGE_CONFIG && enabled.name == "enable" && enabled.type == "Z"
        && code[3].opcode == Opcode.IF_EQZ)
    return MutableMethod(ImmutableMethod(INBOX_RESULT_FRAGMENT, INBOX_INDICES_GATE, emptyList(), "Z",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value, null, null,
        ImmutableMethodImplementation(2, emptyList(), null, null))).also {
        it.addInstructions("""
            invoke-static {}, $getter
            move-result-object v0
            iget-boolean v0, v0, $enabled
            return v0
        """.trimIndent())
    }
}

internal fun MutableMethod.loadedRefreshHook(checkOnly: Boolean = false) {
    check(returnType == "V")
    val manual = parameterTypes.isEmpty()
    check(manual || (name == "setRefreshing" && parameterTypes.map(CharSequence::toString) == listOf("Z")))
    requireLocals("Loaded Inbox refresh", 1)
    val code = implementation!!.instructions.toList()
    // The layout's own flag: setRefreshing stores the state it reads first, and the manual
    // refresh marks itself right after it calls setRefreshing(true).
    val flag = if (manual) {
        val call = code.indexOfFirst { it.getReference<MethodReference>()?.let { ref ->
            ref.definingClass == definingClass && ref.name == "setRefreshing" } == true }
        code.getOrNull(call + 1)?.takeIf { call >= 0 && it.opcode == Opcode.IPUT_BOOLEAN }
    } else code.firstOrNull()?.takeIf { it.opcode == Opcode.IGET_BOOLEAN }
    val field = flag?.getReference<FieldReference>()
    check(field != null && field.definingClass == definingClass && field.type == "Z" && code.any {
        it.opcode == Opcode.IPUT_BOOLEAN && it.getReference<FieldReference>() == field
    }) { "Loaded Inbox refresh flag changed" }
    if (checkOnly) return
    if (manual) loadedGuard("allowLoadedManualRefresh")
    else loadedGuard("allowLoadedRefresh", "Ljava/lang/Object;Z", "p0 .. p1")
}

internal fun MutableMethod.loadedPublicationHook(checkOnly: Boolean = false) {
    check(definingClass == "Lcom/bytedance/ies/powerlist/ListState;" && name == "LIZ" && parameterTypes.size == 6)
    check(implementation!!.registerCount - 5 <= 255) { "Loaded Inbox publication register changed" }
    if (checkOnly) return
    addInstructions(0, """
        invoke-static/range {p0 .. p5}, $INBOX_QUERY_EXTENSION->onLoadedPublication(Ljava/lang/Object;Ljava/util/List;ZZLjava/lang/Runnable;I)Ljava/util/List;
        move-result-object p1
    """.trimIndent())
}

internal fun MutableMethod.loadedStubHook(metadata: String, checkOnly: Boolean = false) {
    val constructors = implementation!!.instructions.withIndex().filter {
        it.value.getReference<MethodReference>()?.let { ref -> ref.definingClass == metadata && ref.name == "<init>" } == true
    }
    check(constructors.size == 1)
    val constructor = constructors.single()
    val holder = (constructor.value as RegisterRangeInstruction).startRegister
    check(implementation!!.registerCount <= 16 && holder <= 15)
    if (checkOnly) return
    addInstruction(constructor.index + 1, "invoke-static {p0, p1, v$holder}, " +
        "$INBOX_QUERY_EXTENSION->onLoadedStubPublication(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V")
}

internal fun MutableMethod.loadedDiffSubmissionHook(checkOnly: Boolean = false) {
    val writes = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.IPUT &&
        it.value.getReference<FieldReference>()?.let { ref -> ref.definingClass == definingClass && ref.type == "I" } == true }
    check(writes.size == 1 && parameterTypes.size == 4)
    if (checkOnly) return
    addInstruction(writes.single().index + 1, "invoke-static/range {p0 .. p4}, " +
        "$INBOX_QUERY_EXTENSION->onLoadedDiffSubmitted(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/lang/Object;)V")
}

internal fun MutableMethod.loadedDiffAcceptanceHook(checkOnly: Boolean = false) {
    // (diff result, metadata, update callback): the metadata's one List read is its completion
    // list, and the dispatch is the diff result's call that takes the update callback.
    val (result, metadata, callback) = parameterTypes.map(CharSequence::toString)
    val completion = implementation!!.instructions.withIndex().filter { it.value.getReference<FieldReference>()?.let { ref ->
        ref.definingClass == metadata && ref.type == "Ljava/util/List;" } == true }
    check(completion.size == 1 && implementation!!.registerCount <= 16)
    val dispatch = implementation!!.instructions.indexOfFirst { it.getReference<MethodReference>()?.let { ref ->
        ref.definingClass == result && ref.parameterTypes.map(CharSequence::toString) == listOf(callback) } == true }
    check(dispatch >= 0 && dispatch < completion.single().index)
    if (checkOnly) return
    val index = completion.single().index
    val originalRead = implementation!!.instructions[index]
    // The listener-loop exit branches to this location. Preserve its label on the hook.
    replaceInstruction(index, "invoke-static {p0, p2}, $INBOX_QUERY_EXTENSION->onLoadedDiffAccepted(Ljava/lang/Object;Ljava/lang/Object;)V")
    implementation!!.addInstruction(index + 1, originalRead)
}

internal fun MutableMethod.loadedIndexedHook(checkOnly: Boolean = false) {
    val calls = implementation!!.instructions.withIndex().filter { it.value.isIndexedListCall() }
    check(calls.size == 1 && implementation!!.registerCount <= 16)
    val call = calls.single()
    val registers = call.value as FiveRegisterInstruction
    check(registers.registerCount == 3)
    if (checkOnly) return
    replaceInstruction(call.index, "invoke-static {p0, v${registers.registerC}, v${registers.registerD}, v${registers.registerE}}, " +
        "$INBOX_QUERY_EXTENSION->onLoadedIndexedCallback(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V")
}

internal fun MutableMethod.loadedVisibleHook(checkOnly: Boolean = false) {
    // The list's live-data value is cast to its aggregate (a renamed class on each build) right
    // after LiveData.getValue(); both reads of the aggregate's List are the rows.
    val code = implementation!!.instructions.toList()
    val aggregates = code.indices.filter { at -> at >= 2 && code[at].opcode == Opcode.CHECK_CAST &&
        code[at - 2].getReference<MethodReference>()?.let { it.definingClass == "Landroidx/lifecycle/LiveData;" && it.name == "getValue" } == true
    }.mapNotNull { code[it].getReference<TypeReference>()?.type }.toSet()
    val reads = code.withIndex().filter { it.value.opcode == Opcode.IGET_OBJECT &&
        it.value.getReference<FieldReference>()?.let { ref -> ref.definingClass in aggregates && ref.type == "Ljava/util/List;" } == true }
    check(reads.size == 2 && implementation!!.registerCount <= 16)
    requireLocals("Loaded Inbox search", 1)
    if (checkOnly) return
    reads.asReversed().forEach { read ->
        val result = (read.value as TwoRegisterInstruction).registerA
        addInstructions(read.index + 1, """
            invoke-static {p0, v$result}, $INBOX_QUERY_EXTENSION->onLoadedIndexedList(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
            move-result-object v$result
        """.trimIndent())
    }
    loadedGuard("allowLoadedVisibleIndices", "Ljava/lang/Object;Z", "p0 .. p1")
}

internal fun MutableMethod.loadedGuard(function: String, parameters: String = "Ljava/lang/Object;", registers: String = "p0 .. p0") {
    requireLocals("Loaded Inbox search", 1)
    addInstructionsWithLabels(0, """
        invoke-static/range {$registers}, $INBOX_QUERY_EXTENSION->$function($parameters)Z
        move-result v0
        if-nez v0, :native_body
        return-void
    """.trimIndent(), ExternalLabel("native_body", getInstruction(0)))
}
