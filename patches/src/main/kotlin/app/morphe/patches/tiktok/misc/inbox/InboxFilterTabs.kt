/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val FILTER_TABS_EXTENSION = "Lapp/morphe/extension/tiktok/inbox/InboxFilterTabs;"

/**
 * TikTok's professional inbox manager works out an account's inbox mode (personal, creator or
 * business) in produceCurrentInboxMode(uid), the one method that logs this line. The manager's
 * other methods are found in its class.
 */
internal object InboxModeProducerFingerprint : Fingerprint(
    returnType = "Lkotlin/Pair;",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("produceCurrentInboxMode start, uid: "),
)

/**
 * The manager's current mode: its one instance method without arguments that falls back on
 * produceCurrentInboxMode and answers the mode it reads off the (uid, mode) pair. The Inbox's tab
 * bar, its empty states and every tab's filter ask this.
 */
internal object InboxModeGetterFingerprint : Fingerprint(
    classFingerprint = InboxModeProducerFingerprint,
    isStatic = false,
    parameters = listOf(),
    custom = { method, classDef -> inboxModeReturn(method, classDef) != null },
)

/**
 * The manager's tab list, which reads the mode off its cached pair itself rather than through the
 * getter, then picks the tabs for it. The business mode's list is a server setting under this key.
 */
internal object InboxFilterTabListFingerprint : Fingerprint(
    classFingerprint = InboxModeProducerFingerprint,
    isStatic = false,
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    strings = listOf("tt_im_business_inbox_filter_item_list"),
)

/**
 * Whether the pop-up about the upgraded creator Inbox may show. It offers itself once the mode
 * reads creator and the account hasn't seen it, which the shown tabs would otherwise bring about.
 */
internal object ProInboxGuideCanShowFingerprint : Fingerprint(
    name = "canShow",
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("shown_pro_creator_upgrade_guide:"),
)

private fun Instruction.typeReference() = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

/**
 * Where the mode getter returns, when [method] of [owner] is that getter: an object return of a
 * type that isn't a list or a pair, reached through a call to the class's produceCurrentInboxMode
 * shape, with the mode cast to that type right before its only return, and no try block for the
 * hook in front of the return to land in.
 */
internal fun inboxModeReturn(method: Method, owner: ClassDef): Int? {
    val type = method.returnType
    if (!type.startsWith("L") || type == "Ljava/util/List;" || type == "Lkotlin/Pair;") return null
    val body = method.implementation ?: return null
    if (body.tryBlocks.isNotEmpty()) return null
    val instructions = body.instructions.toList()
    val asksProducer = instructions.any { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference != null && reference.definingClass == owner.type && reference.returnType == "Lkotlin/Pair;" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
    }
    if (!asksProducer) return null
    val returns = instructions.indices.filter { instructions[it].opcode == Opcode.RETURN_OBJECT }
    val index = returns.singleOrNull() ?: return null
    val cast = instructions.getOrNull(index - 1) ?: return null
    if (cast.opcode != Opcode.CHECK_CAST || cast.typeReference() != type) return null
    val returned = (instructions[index] as OneRegisterInstruction).registerA
    return if ((cast as OneRegisterInstruction).registerA == returned) index else null
}

/**
 * Where the tab list has the mode in hand: its one cast to [modeType], which the switch over the
 * mode's ordinal follows.
 */
internal fun inboxModeCast(tabs: Method, modeType: String): Int {
    val instructions = tabs.implementation!!.instructions.toList()
    val casts = instructions.indices.filter {
        instructions[it].opcode == Opcode.CHECK_CAST && instructions[it].typeReference() == modeType
    }
    val cast = casts.singleOrNull()
        ?: throw PatchException("Inbox filters: ${tabs.name} casts to $modeType ${casts.size} times, not once")
    val ordinal = instructions.drop(cast + 1).take(3).any { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.definingClass == "Ljava/lang/Enum;" && reference.name == "ordinal"
    }
    if (!ordinal) throw PatchException("Inbox filters: ${tabs.name} doesn't switch on the mode it casts")
    return cast
}

/** The three hooks, found and checked before any of them is written. */
internal class InboxFilterTabHooks(
    manager: ClassDef,
    private val getter: MutableMethod,
    private val tabs: MutableMethod,
    private val guide: MutableMethod,
) {
    private val modeType = getter.returnType
    private val returnIndex = inboxModeReturn(getter, manager)
        ?: throw PatchException("Inbox filters: ${getter.name} is not the mode getter")
    private val castIndex = inboxModeCast(tabs, modeType)

    fun install(patch: String) {
        val returned = (getter.implementation!!.instructions[returnIndex] as OneRegisterInstruction).registerA
        getter.addInstructionsAtControlFlowLabel(returnIndex, shownMode(returned))
        val cast = (tabs.implementation!!.instructions[castIndex] as OneRegisterInstruction).registerA
        tabs.addInstructionsAtControlFlowLabel(castIndex + 1, shownMode(cast))
        guide.guardAtEntry(
            patch,
            "invoke-static {}, $FILTER_TABS_EXTENSION->hideProInboxGuide()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }

    /** Straight-line: the extension decides, and the answer goes back where the mode was. */
    private fun shownMode(register: Int) = """
        invoke-static/range { v$register .. v$register }, $FILTER_TABS_EXTENSION->shownInboxMode(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v$register
        check-cast v$register, $modeType
    """
}

context(patchContext: BytecodePatchContext)
internal fun inboxFilterTabHooks() = InboxFilterTabHooks(
    InboxModeProducerFingerprint.classDef,
    InboxModeGetterFingerprint.method,
    InboxFilterTabListFingerprint.method,
    ProInboxGuideCanShowFingerprint.method,
)
