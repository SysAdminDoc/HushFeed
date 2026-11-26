/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EVERYWHERE = "Always show publish date (Use this date format everywhere)"
private const val DATES_EXTENSION = "Lapp/morphe/extension/tiktok/publishdate/AlwaysShowPublishDatePatch;"
private const val AWEME_MODEL = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val CHAR_SEQUENCE_TYPE = "Ljava/lang/CharSequence;"
private const val STRING_TYPE = "Ljava/lang/String;"

/** The date row of a search result's video card. */
internal const val SEARCH_CARD_TIME =
    "Lcom/ss/android/ugc/aweme/search/arch/v2/protocol/card/components/SearchCardCreateTimeAssem;"

/** The date header between a chat's messages. */
internal const val CHAT_DATE_CELL = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/datetime/DateSeparatorPowerCell;"

/** The words TikTok's chat date item describes itself with, which survive its renaming. */
internal const val CHAT_DATE_ITEM_NAME = "DateViewHolderData("
internal const val CHAT_DATE_CREATED_AT = ", createdAt="

/**
 * The search card's bind: it reads the card's video, turns its createTime into milliseconds,
 * hands that to TikTok's age formatter and writes the text on the card's date view.
 */
internal object SearchCardDateFingerprint : Fingerprint(
    definingClass = SEARCH_CARD_TIME,
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(methodCall(definingClass = AWEME_MODEL, name = "getCreateTime")),
    custom = { method, _ -> method.searchCardDateSite() != null },
)

/**
 * The chat date header's bind. TikTok formats the header when it lays out the chat and keeps the
 * text on the header's item, so the bind casts its item and writes that text.
 */
internal object ChatDateFingerprint : Fingerprint(
    definingClass = CHAT_DATE_CELL,
    returnType = "V",
    custom = { method, _ ->
        method.name == "onBindItemView" && method.parameterTypes.size == 1 && method.chatDateSite() != null
    },
)

/**
 * Where a search card writes its date: the setText at [writeIndex] of [textRegister], a String the
 * formatter right before it returned, with the card's video still in [awemeRegister].
 */
internal data class CardDateSite(val writeIndex: Int, val textRegister: Int, val awemeRegister: Int)

/**
 * Where a chat date header writes its text: the setText at [writeIndex] of [textRegister], read
 * from a String field of the item in [itemRegister], the bind's parameter cast to [itemType].
 */
internal data class ChatDateSite(val writeIndex: Int, val textRegister: Int, val itemRegister: Int, val itemType: String)

private fun Instruction.isCharSequenceWrite() =
    opcode == Opcode.INVOKE_VIRTUAL &&
        getReference<MethodReference>()?.let { reference ->
            reference.name == "setText" && reference.returnType == "V" &&
                reference.parameterTypes.map(CharSequence::toString) == listOf(CHAR_SEQUENCE_TYPE)
        } == true

private fun Instruction.overwrites(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/** The first search card date write past the video's createTime, or null when it isn't shaped so. */
internal fun Method.searchCardDateSite(): CardDateSite? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val read = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == AWEME_MODEL && it.name == "getCreateTime" && it.returnType == "J"
            } == true
    }
    if (read < 0) return null
    val aweme = (instructions[read] as FiveRegisterInstruction).registerC
    val write = (read + 1 until instructions.size).firstOrNull { instructions[it].isCharSequenceWrite() } ?: return null
    val text = (instructions[write] as FiveRegisterInstruction).registerD
    val result = instructions[write - 1]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA != text) return null
    val formatter = instructions[write - 2].getReference<MethodReference>() ?: return null
    if (formatter.returnType != STRING_TYPE || "J" !in formatter.parameterTypes.map(CharSequence::toString)) return null
    if ((read + 1 until write).any { instructions[it].overwrites(aweme) }) return null
    return CardDateSite(write, text, aweme)
}

/** The chat date header's write of its item's text, or null when the bind isn't shaped so. */
internal fun Method.chatDateSite(): ChatDateSite? {
    val body = implementation ?: return null
    val instructions = body.instructions.toList()
    val parameter = body.registerCount - 1
    val cast = instructions.indexOfFirst { it.opcode == Opcode.CHECK_CAST }
    if (cast < 0 || (instructions[cast] as OneRegisterInstruction).registerA != parameter) return null
    val itemType = instructions[cast].getReference<TypeReference>()?.type ?: return null
    val write = (cast + 1 until instructions.size).firstOrNull { instructions[it].isCharSequenceWrite() } ?: return null
    val text = (instructions[write] as FiveRegisterInstruction).registerD
    val read = (cast + 1 until write).lastOrNull { index ->
        instructions[index].opcode == Opcode.IGET_OBJECT && (instructions[index] as TwoRegisterInstruction).registerA == text
    } ?: return null
    val field = instructions[read].getReference<FieldReference>() ?: return null
    if (field.definingClass != itemType || field.type != STRING_TYPE ||
        (instructions[read] as TwoRegisterInstruction).registerB != parameter
    ) {
        return null
    }
    if ((read + 1 until write).any { instructions[it].overwrites(text) || instructions[it].overwrites(parameter) }) return null
    return ChatDateSite(write, text, parameter, itemType)
}

/** Whether [classDef] describes itself the way the extension reads the createdAt from. */
internal fun ClassDef.describesChatDate(): Boolean {
    val strings = methods.filter { it.name == "toString" }
        .flatMap { method -> method.implementation?.instructions?.toList().orEmpty() }
        .mapNotNull { it.getReference<StringReference>()?.string }
    return strings.any { it.startsWith(CHAT_DATE_ITEM_NAME) } && CHAT_DATE_CREATED_AT in strings
}

private fun Method.refuseBranchesOnto(index: Int, what: String) {
    val flow = ControlFlow.of(this)
    val branched = flow.normal.indices.any { from -> flow.normal[from].any { it == index && it != from + 1 } }
    if (branched) throw PatchException("$EVERYWHERE: something branches onto $what, so a hook before it would be skipped")
}

/** The search card's date goes through the extension, with the card's video, right before it's written. */
internal fun MutableMethod.hookSearchCardDate() {
    val site = searchCardDateSite() ?: throw PatchException("$EVERYWHERE: the search card's date write isn't where it was")
    refuseBranchesOnto(site.writeIndex, "the search card's date write")
    val call = callThroughLocals(
        EVERYWHERE,
        "invoke-static",
        "$DATES_EXTENSION->searchCardDate(${STRING_TYPE}Ljava/lang/Object;)$STRING_TYPE",
        false,
        objectIn("v${site.textRegister}"),
        objectIn("v${site.awemeRegister}"),
    )
    addInstructions(
        site.writeIndex,
        """
            $call
            move-result-object v${site.textRegister}
        """,
    )
}

/**
 * A chat date header's text goes through the extension, with TikTok's item, right before it's
 * written. The extension reads the item's createdAt from its description, so [classOf] has to
 * find the item class describing itself that way.
 */
internal fun MutableMethod.hookChatDate(classOf: (String) -> ClassDef?) {
    val site = chatDateSite() ?: throw PatchException("$EVERYWHERE: the chat date header's write isn't where it was")
    if (classOf(site.itemType)?.describesChatDate() != true) {
        throw PatchException("$EVERYWHERE: ${site.itemType} no longer describes its createdAt")
    }
    refuseBranchesOnto(site.writeIndex, "the chat date header's write")
    val call = callThroughLocals(
        EVERYWHERE,
        "invoke-static",
        "$DATES_EXTENSION->chatDate(${STRING_TYPE}Ljava/lang/Object;)$STRING_TYPE",
        false,
        objectIn("v${site.textRegister}"),
        objectIn("v${site.itemRegister}"),
    )
    addInstructions(
        site.writeIndex,
        """
            $call
            move-result-object v${site.textRegister}
        """,
    )
}
