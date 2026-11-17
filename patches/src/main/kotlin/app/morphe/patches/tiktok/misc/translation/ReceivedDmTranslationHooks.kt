/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val RECEIVED_PREVIEW = "Lapp/morphe/extension/tiktok/translation/ReceivedDmTranslationPreview;"
internal const val RECEIVED_CONTENT = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/text/content/"
internal const val RECEIVED_ASSEM = "${RECEIVED_CONTENT}TextTranslateAssem;"
internal const val RECEIVED_ROW = "${RECEIVED_CONTENT}TextTranslateReceiveAssem;"
internal const val RECEIVED_CELL = "${RECEIVED_CONTENT}TextTranslateReceiveCell;"
internal const val RECEIVED_MENU = "${RECEIVED_CONTENT}TextTranslateReceiveCell\$createAssemAttached2Cell\$1;"
/** The row's no-argument job cancellation, called by the bind and by unBind (47.7.2's wq, 47.7.3's zq). */
internal const val RECEIVED_CANCEL = "Gq"
private const val RECEIVED = "Translate comments (received message preview)"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val MESSAGE = "LX/0CjR;"
private const val MODEL = "LX/11qy;"
private const val SESSION = "Lcom/ss/android/ugc/aweme/im/chatroom/api/model/SessionInfo;"
private const val FUNCTION = "Lkotlin/jvm/functions/Function1;"
private val menuParameters = listOf(MESSAGE, "LX/11kd;", "Landroid/content/Context;", "Landroidx/fragment/app/FragmentManager;",
    "Lcom/ss/android/ugc/aweme/im/sdk/chat/feature/IMMessageListTranslationViewModel;",
    "Lcom/ss/android/ugc/aweme/im/messagelist/api/select/IMSelectTextAbility;", "LX/11gq;", "Ljava/lang/CharSequence;")

internal data class ReceivedMenuResult(val call: Int, val result: Int, val returned: Int, val register: Int)
internal data class ReceivedPreviewTargets(val bind: Method, val unbind: Method, val menu: Method,
    val result: ReceivedMenuResult)

private fun Method.calls(owner: String, name: String, result: String, parameters: List<String> = emptyList()) =
    implementation?.instructions?.any { instruction -> instruction.getReference<MethodReference>()?.let {
        it.definingClass == owner && it.name == name && it.returnType == result &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } == true } == true

internal fun Method.receivedMenuResult(): ReceivedMenuResult {
    if (definingClass != RECEIVED_MENU || name != "Rq" || returnType != LIST ||
        parameterTypes.map(CharSequence::toString) != listOf(MESSAGE) || AccessFlags.STATIC.isSet(accessFlags))
        throw PatchException("$RECEIVED: changed received menu ABI")
    val code = implementation ?: throw PatchException("$RECEIVED: missing received menu body")
    if (code.registerCount !in 3..16) throw PatchException("$RECEIVED: received menu registers changed")
    val instructions = code.instructions.toList()
    val calls = instructions.withIndex().filter { (_, instruction) ->
        instruction.getReference<MethodReference>()?.let { reference -> reference.definingClass == "LX/11gl;" &&
            reference.name == "LIZ" && reference.returnType == LIST &&
            reference.parameterTypes.map(CharSequence::toString) == menuParameters } == true
    }
    val call = calls.singleOrNull()?.index ?: throw PatchException("$RECEIVED: expected one completed native menu")
    val result = instructions.getOrNull(call + 1) as? OneRegisterInstruction
    val returned = instructions.getOrNull(call + 2) as? OneRegisterInstruction
    if (instructions.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions.getOrNull(call + 2)?.opcode != Opcode.RETURN_OBJECT || result == null || returned == null ||
        result.registerA != returned.registerA || result.registerA >= code.registerCount - 2)
        throw PatchException("$RECEIVED: native menu no longer returns its untouched result")
    return ReceivedMenuResult(call, call + 1, call + 2, result.registerA)
}

/** The two bind parameters must still be the native receiver/model at every completed return. */
internal fun Method.receivedBindReturns(): List<Int> {
    val code = implementation ?: throw PatchException("$RECEIVED: missing bind body")
    if (AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.map(CharSequence::toString) != listOf(MODEL, "Z") ||
        returnType != "V" || code.registerCount < 3) throw PatchException("$RECEIVED: changed bind parameters")
    val receiver = code.registerCount - 3
    val instructions = code.instructions.toList()
    if (instructions.any { instruction ->
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        instruction.opcode.setsRegister() && destination != null &&
            (destination in receiver..receiver + 1 || instruction.opcode.setsWideRegister() && destination + 1 in receiver..receiver + 1)
    }) throw PatchException("$RECEIVED: bind overwrites a captured native parameter")
    val cancellations = instructions.withIndex().filter { (_, instruction) -> instruction.getReference<MethodReference>()?.let {
        it.definingClass == RECEIVED_ASSEM && it.name == RECEIVED_CANCEL && it.returnType == "V" && it.parameterTypes.isEmpty()
    } == true }
    val cancellation = cancellations.singleOrNull()?.index ?: throw PatchException("$RECEIVED: changed native bind cancellation")
    return instructions.withIndex().filter { (index, instruction) -> index > cancellation && instruction.opcode == Opcode.RETURN_VOID }
        .map { it.index }.also { if (it.isEmpty()) throw PatchException("$RECEIVED: no completed native bind") }
}

/** Resolve every reflected owner before modifying a native instruction. */
internal fun findReceivedPreviewTargets(classOf: (String) -> ClassDef?): ReceivedPreviewTargets {
    validateNativeDmTranslation(classOf)
    fun owner(type: String) = classOf(type) ?: throw PatchException("$RECEIVED: missing $type")
    fun abi(type: String, name: String, result: String, parameters: List<String> = emptyList(), static: Boolean = false): Method {
        val pending = ArrayDeque<String>().apply { add(type) }
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty() && seen.size < 32) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            val definition = owner(current)
            val methods = definition.methods.filter { it.name == name && it.returnType == result &&
                it.parameterTypes.map(CharSequence::toString) == parameters && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                AccessFlags.STATIC.isSet(it.accessFlags) == static }
            if (methods.size > 1) throw PatchException("$RECEIVED: ambiguous $type->$name")
            methods.singleOrNull()?.let { return it }
            if (name == "<init>") break
            definition.superclass?.takeUnless { it == "Ljava/lang/Object;" }?.let(pending::add)
            definition.interfaces.forEach { pending.add(it) }
        }
        throw PatchException("$RECEIVED: changed $type->$name ABI")
    }
    fun field(type: String, name: String, fieldType: String, static: Boolean = false) {
        if (owner(type).fields.count { it.name == name && it.type == fieldType && AccessFlags.STATIC.isSet(it.accessFlags) == static } != 1)
            throw PatchException("$RECEIVED: changed $type->$name field")
    }
    if (owner(RECEIVED_ROW).superclass != RECEIVED_ASSEM || MODEL !in owner("LX/11qv;").interfaces)
        throw PatchException("$RECEIVED: changed received-row subtype")
    field(RECEIVED_MENU, "LLLILZLLLI", RECEIVED_CELL)
    val menu = abi(RECEIVED_MENU, "Rq", LIST, listOf(MESSAGE))
    val result = menu.receivedMenuResult()
    if (!menu.calls("LX/03su;", "getChildren", LIST) ||
        !menu.calls("Lcom/bytedance/ies/powerlist/PowerCell;", "getItem", "LX/07F6;") ||
        menu.implementation!!.instructions.none { it.opcode == Opcode.CHECK_CAST && it.getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()?.type == "LX/11qv;" })
        throw PatchException("$RECEIVED: changed current received-cell ownership")
    val bind = abi(RECEIVED_ASSEM, "zq", "V", listOf(MODEL, "Z"))
    bind.receivedBindReturns()
    val unbind = abi(RECEIVED_ASSEM, RECEIVED_CANCEL, "V")
    if (!abi(RECEIVED_ASSEM, "unBind", "V").calls(RECEIVED_ASSEM, RECEIVED_CANCEL, "V"))
        throw PatchException("$RECEIVED: changed native row retirement")
    abi("LX/03su;", "getChildren", LIST)
    abi("Lcom/bytedance/ies/powerlist/PowerCell;", "getItem", "LX/07F6;")
    abi("LX/0AWf;", "LIZ", OBJECT, listOf("Lcom/bytedance/assem/arch/reused/ReusedAssem;"), true)
    abi("LX/115P;", "LIZ", MESSAGE, listOf("LX/115V;"), true)
    abi(MODEL, "LLJJ", STRING)
    abi(RECEIVED_ASSEM, "Sq", "LX/11iS;")
    field("LX/11iS;", "LL", "Landroid/view/View;")
    field(RECEIVED_ASSEM, "LLJL", "LX/05KU;")
    abi("LX/05KU;", "getValue", OBJECT)
    field("LX/11ZA;", "LL", SESSION)
    listOf("isSingleChat", "isPreviewMode", "isReportPage").forEach { abi(SESSION, it, "Z") }
    abi(SESSION, "getConversationId", STRING)
    if (owner("LX/12At;").superclass != SESSION) throw PatchException("$RECEIVED: changed single-peer session")
    abi("LX/12At;", "getSingleChatFromUserId", STRING)
    listOf("getUuid", "getConversationId", "getContent").forEach { abi(MESSAGE, it, STRING) }
    abi(MESSAGE, "getSender", "J")
    abi(MESSAGE, "getMsgType", "I")
    abi("LX/0DKp;", "LJI", "Z", listOf(MESSAGE), true)
    abi("LX/0Fva;", "LJIIIZ", "Z", listOf(MESSAGE, "LX/11gm;"), true)
    field("LX/11gm;", "TRANSLATE", "LX/11gm;", true)
    field("LX/0DCo;", "LIZIZ", "LX/0DCo;", true)
    abi("LX/0DCo;", "LJIIZILJ", "Z")
    abi("LX/1290;", "<init>", "V", listOf(STRING, "Ljava/lang/Enum;", "LX/129E;", "LX/129F;", "LX/128z;", "LX/129D;", "Z", FUNCTION))
    field("LX/1290;", "LIZ", STRING)
    field("LX/1290;", "LJII", FUNCTION)
    abi("LX/1296;", "<init>", "V", listOf("I"))
    abi("LX/1294;", "<init>", "V", listOf(STRING))
    abi("LX/129D;", "<init>", "V")
    if ("LX/129E;" !in owner("LX/1296;").interfaces || "LX/129F;" !in owner("LX/1294;").interfaces)
        throw PatchException("$RECEIVED: changed menu icon/text wrappers")
    field("LX/128z;", "PRIMARY", "LX/128z;", true)
    field("LX/11gG;", "TRANSLATE", "LX/11gG;", true)
    field("Lkotlin/Unit;", "LIZ", "Lkotlin/Unit;", true)
    abi(FUNCTION, "invoke", OBJECT, listOf(OBJECT))
    return ReceivedPreviewTargets(bind, unbind, menu, result)
}

internal fun MutableMethod.captureReceivedBinding() {
    receivedBindReturns().asReversed().forEach { index -> addInstructionsAtControlFlowLabel(index,
        "invoke-static/range {p0 .. p1}, $RECEIVED_PREVIEW->bound($OBJECT$OBJECT)V") }
    addInstructions(0, "invoke-static/range {p0 .. p0}, $RECEIVED_PREVIEW->unbound($OBJECT)V")
}

internal fun MutableMethod.appendReceivedMenu(result: ReceivedMenuResult) {
    addInstructionsAtControlFlowLabel(result.returned, """
        invoke-static {v${result.register}, p0, p1}, $RECEIVED_PREVIEW->appendAction($LIST$OBJECT$OBJECT)$LIST
        move-result-object v${result.register}
    """)
}

internal fun hookReceivedDmTranslationPreview(targets: ReceivedPreviewTargets) {
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
    mutable(targets.bind).captureReceivedBinding()
    mutable(targets.unbind).addInstructions(0, "invoke-static/range {p0 .. p0}, $RECEIVED_PREVIEW->unbound($OBJECT)V")
    mutable(targets.menu).appendReceivedMenu(targets.result)
}
