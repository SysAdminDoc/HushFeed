/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val MESSAGE_TIMES = "Lapp/morphe/extension/tiktok/inbox/MessageTimes;"

private const val MESSAGE_CELLS = "Lcom/ss/android/ugc/aweme/im/sdk/chat/messagelist/skeleton/cell/"

/**
 * The two slots that bind a chat message row. They keep their names on 47.7.3, and each binds a row
 * through one method that takes the row's item and sets the row's padding from TikTok's spacing.
 */
internal val MESSAGE_ROW_SLOTS = listOf(
    "${MESSAGE_CELLS}IMSkeletonLayoutReusedUISlotAssem;",
    "${MESSAGE_CELLS}SkeletonLayoutReusedUISlotAssem;",
)

private fun MethodReference.isSetPadding() =
    definingClass == "Landroid/view/View;" && name == "setPadding" && returnType == "V" &&
        parameterTypes.map { it.toString() } == listOf("I", "I", "I", "I")

private fun MethodReference.isPaddingDp() = name == "getPaddingDp" && returnType == "I" && parameterTypes.isEmpty()

/** A slot's bind: one item in, nothing out, a single setPadding with TikTok's spacing before it. */
internal fun Method.isMessageRowPadding(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || parameterTypes.size != 1) return false
    val instructions = implementation?.instructions?.toList() ?: return false
    val paddings = instructions.indices.filter { instructions[it].getReference<MethodReference>()?.isSetPadding() == true }
    if (paddings.size != 1) return false
    return instructions.take(paddings.single()).any { it.getReference<MethodReference>()?.isPaddingDp() == true }
}

internal object IMSkeletonRowBindFingerprint : Fingerprint(
    definingClass = MESSAGE_ROW_SLOTS[0],
    returnType = "V",
    custom = { method, _ -> method.isMessageRowPadding() },
)

internal object SkeletonRowBindFingerprint : Fingerprint(
    definingClass = MESSAGE_ROW_SLOTS[1],
    returnType = "V",
    custom = { method, _ -> method.isMessageRowPadding() },
)

/**
 * The way from a row's item to its message, the same in both slots: the item's holder getter, then
 * two fields, the last one typed as TikTok's IM message.
 */
internal data class MessagePath(
    val itemType: String,
    val holder: MethodReference,
    val holderIsInterface: Boolean,
    val inner: FieldReference,
    val message: FieldReference,
)

/** Where the bind sets the padding, and the registers the hook reads right after it. */
internal data class MessageRowBind(
    val padding: Int,
    val row: Int,
    val item: Int,
    val path: MessagePath,
)

/**
 * Reads [MessageRowBind] off a slot's bind, or null when its shape is not the one the hook
 * fits. [isMessage] answers whether a class is TikTok's IM message (it has `getCreatedAt()J`).
 *
 * The item is the bind's only parameter. Up to the setPadding it is only ever cast to its own type,
 * so it still holds the item right after. The row is the setPadding's receiver, the view TikTok just
 * padded. The message is read once in the bind the way TikTok's own code reads it: the item's holder
 * getter, the holder's field, and that field's message field.
 */
internal fun Method.messageRowBind(isMessage: (String) -> Boolean): MessageRowBind? {
    if (!isMessageRowPadding()) return null
    val implementation = implementation ?: return null
    val instructions = implementation.instructions.toList()
    val padding = instructions.indexOfFirst { it.getReference<MethodReference>()?.isSetPadding() == true }
    val call = instructions[padding] as? FiveRegisterInstruction ?: return null
    if (call.registerCount != 5) return null
    val item = implementation.registerCount - 1
    val row = call.registerC
    if (row == item || row > 15 || item > 15) return null
    if (instructions.take(padding + 1).any { it.writesRegister(item) }) return null
    // A path landing right after the padding skipped this setPadding, so it gets no time either.
    if (isBranchTarget(padding + 1)) return null

    val paths = instructions.indices.mapNotNull { pathAt(instructions, it, item, isMessage) }.distinct()
    val path = paths.singleOrNull() ?: return null
    return MessageRowBind(padding, row, item, path)
}

/** Whether [instruction] writes [register], other than a check-cast that keeps the value. */
private fun Instruction.writesRegister(register: Int): Boolean {
    if (opcode == Opcode.CHECK_CAST || this !is OneRegisterInstruction) return false
    if (!opcode.setsRegister()) return false
    return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
}

private fun pathAt(instructions: List<Instruction>, at: Int, item: Int, isMessage: (String) -> Boolean): MessagePath? {
    if (at + 3 >= instructions.size) return null
    val call = instructions[at]
    if (call.opcode != Opcode.INVOKE_INTERFACE && call.opcode != Opcode.INVOKE_VIRTUAL) return null
    call as FiveRegisterInstruction
    if (call.registerCount != 1 || call.registerC != item) return null
    val holder = call.getReference<MethodReference>() ?: return null
    if (holder.parameterTypes.isNotEmpty() || !holder.returnType.startsWith("L")) return null
    val move = instructions[at + 1]
    if (move.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val holderRegister = (move as OneRegisterInstruction).registerA
    val first = instructions[at + 2]
    val second = instructions[at + 3]
    if (first.opcode != Opcode.IGET_OBJECT || second.opcode != Opcode.IGET_OBJECT) return null
    first as TwoRegisterInstruction
    second as TwoRegisterInstruction
    if (first.registerB != holderRegister || second.registerB != first.registerA) return null
    val inner = first.getReference<FieldReference>() ?: return null
    val message = second.getReference<FieldReference>() ?: return null
    if (inner.definingClass != holder.returnType || message.definingClass != inner.type) return null
    if (!isMessage(message.type)) return null
    return MessagePath(holder.definingClass, holder, call.opcode == Opcode.INVOKE_INTERFACE, inner, message)
}

/** Whether [type] or a class above it declares the message's `getCreatedAt()J`. */
internal fun hasCreatedAt(type: String, classOf: (String) -> ClassDef?): Boolean {
    var current: String? = type
    var depth = 0
    while (current != null && depth++ < 8) {
        val classDef = classOf(current) ?: return false
        if (classDef.methods.any { it.name == "getCreatedAt" && it.returnType == "J" && it.parameterTypes.isEmpty() }) return true
        current = classDef.superclass
    }
    return false
}

/** Whether [type] is an interface, so `getCreatedAt` is called on it with invoke-interface. */
internal fun isInterfaceType(type: String, classOf: (String) -> ClassDef?) =
    classOf(type)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true

/**
 * Hands the padded row and the item to the extension right after the setPadding, straight-line and
 * with no register written, so TikTok's code after it reads what it always did.
 */
internal fun MutableMethod.reportMessageRow(bind: MessageRowBind) {
    addInstructions(
        bind.padding + 1,
        "invoke-static { v${bind.row}, v${bind.item} }, $MESSAGE_TIMES->bound(Landroid/view/View;Ljava/lang/Object;)V",
    )
}

/**
 * The body of the `createdAt(Object)J` stub in [MESSAGE_TIMES], put in front of it (cloned with two more registers):
 * the item's message's `getCreatedAt()`, or 0 for anything that isn't an item or has no message.
 */
internal fun messageTimeBridgeBody(path: MessagePath, messageIsInterface: Boolean): String {
    val holderCall = if (path.holderIsInterface) "invoke-interface" else "invoke-virtual"
    val timeCall = if (messageIsInterface) "invoke-interface" else "invoke-virtual"
    return """
        instance-of v0, p0, ${path.itemType}
        if-eqz v0, :none
        check-cast p0, ${path.itemType}
        $holderCall { p0 }, ${path.holder}
        move-result-object v0
        if-eqz v0, :none
        iget-object v0, v0, ${path.inner}
        if-eqz v0, :none
        iget-object v0, v0, ${path.message}
        if-eqz v0, :none
        $timeCall { v0 }, ${path.message.type}->getCreatedAt()J
        move-result-wide v0
        return-wide v0
        :none
        const-wide/16 v0, 0x0
        return-wide v0
    """
}

internal fun MutableMethod.writeMessageTimeBridge(path: MessagePath, messageIsInterface: Boolean) {
    addInstructionsWithLabels(0, messageTimeBridgeBody(path, messageIsInterface))
}

internal fun requireMessageRowBind(patch: String, method: Method, classOf: (String) -> ClassDef?): MessageRowBind =
    method.messageRowBind { hasCreatedAt(it, classOf) }
        ?: throw PatchException("$patch: ${method.definingClass}->${method.name} doesn't bind a message row the way Show message times hooks.")

/** Both slots' binds and the one way to their message, found before anything is written. */
internal class MessageTimeHooks(
    val binds: List<Pair<MutableMethod, MessageRowBind>>,
    val path: MessagePath,
    val messageIsInterface: Boolean,
)

internal fun messageTimeHooks(patch: String): MessageTimeHooks {
    val classOf: (String) -> ClassDef? = { classDefByOrNull(it) }
    val binds = listOf(IMSkeletonRowBindFingerprint.method, SkeletonRowBindFingerprint.method)
        .map { it to requireMessageRowBind(patch, it, classOf) }
    val path = binds.map { it.second.path }.distinct().singleOrNull()
        ?: throw PatchException("$patch: the two message row slots reach the message differently.")
    return MessageTimeHooks(binds, path, isInterfaceType(path.message.type, classOf))
}

/** Fills the extension's `createdAt` stub with the read of the item's send time and hooks both binds. */
internal fun MessageTimeHooks.install(patch: String) {
    val extension = mutableClassDefBy(MESSAGE_TIMES)
    val stub = extension.methods.singleOrNull { it.name == "createdAt" && it.returnType == "J" }
        ?: throw PatchException("$patch: the extension has no createdAt to fill in.")
    val bridge = stub.cloneMutable(additionalRegisters = 2)
    bridge.writeMessageTimeBridge(path, messageIsInterface)
    extension.methods.remove(stub)
    extension.methods.add(bridge)
    binds.forEach { (method, bind) -> method.reportMessageRow(bind) }
}
