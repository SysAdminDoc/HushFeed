/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val FOLLOWING_STORY_CELL = "Lcom/ss/android/ugc/aweme/business/story/ui/cell/StorySkylightCell;"
internal const val INBOX_STORY_CELL = "Lcom/ss/android/ugc/aweme/story/inbox/view/StoryInboxCell;"
internal const val SKYLIGHT_LIST_ASSEM = "Lcom/ss/android/ugc/aweme/base/ui/assem/FeedSkylightListAssem;"
internal const val RECENT_FOLLOW_EXPERIMENT = "Lcom/ss/android/ugc/aweme/experiment/FollowingSkylightAddRecentFollowExp;"
private const val STORY_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val STORY_ROW_BUTTONS = "Lapp/morphe/extension/tiktok/feed/StoryRowButtons;"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/**
 * A story cell's bind asks one static (Aweme)Z question: whether the viewer's own item has no Story
 * up. True with no Story, so that's the Create circle, on the Following row and in the Inbox alike.
 * The flag the bind writes afterwards is the other way round (own and has a Story), and its name
 * changes on every build. -1 when the method doesn't ask.
 */
private fun storyAskedIndex(code: List<Instruction>): Int = code.indexOfFirst { instruction ->
    instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let {
        it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(STORY_AWEME)
    } == true
}

/**
 * Where the answer lands: the move-result right after the question, which only the viewer's own
 * item gets to. -1 when the method doesn't ask.
 */
internal fun storyAnswerIndex(method: Method): Int {
    val code = method.code()
    val asked = storyAskedIndex(code)
    if (asked < 0 || code.getOrNull(asked + 1)?.opcode != Opcode.MOVE_RESULT) return -1
    return asked + 1
}

/**
 * Where the bind writes "this is the viewer's own item": the last boolean it writes to the cell
 * before asking, and every other cell branches past the question from here. -1 when it doesn't.
 */
internal fun storyOwnerWriteIndex(method: Method): Int {
    val code = method.code()
    val asked = storyAskedIndex(code)
    if (asked < 0) return -1
    return (asked - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.IPUT_BOOLEAN &&
            code[it].getReference<FieldReference>()?.definingClass == method.definingClass
    } ?: -1
}

/** A story cell's bind, the one (item)V method on the class that writes the owner and asks. */
internal fun isStoryCellBind(method: Method): Boolean =
    method.returnType == "V" && method.parameterTypes.size == 1 &&
        storyAnswerIndex(method) >= 0 && storyOwnerWriteIndex(method) >= 0

/**
 * Hands the cell to the extension twice: right after the owner write, which every bind reaches, and
 * right after the answer, which only the viewer's own item reaches. Neither is a branch target, so
 * both are one straight call on registers the bind already holds. The parameters are ints (the Z
 * rule in the repo's notes).
 */
internal fun MutableMethod.hookStoryCreateCell(patch: String) {
    val owner = storyOwnerWriteIndex(this)
    val answer = storyAnswerIndex(this)
    if (owner < 0 || answer < 0) throw PatchException("$patch: $definingClass->$name no longer asks about the viewer's Story.")
    val code = implementation!!.instructions.toList()
    val write = code[owner] as TwoRegisterInstruction
    val result = (code[answer] as OneRegisterInstruction).registerA
    if (write.registerA > 15 || write.registerB > 15 || result > 15) {
        throw PatchException("$patch: $definingClass->$name holds the Create cell above v15.")
    }
    addInstruction(
        answer + 1,
        "invoke-static {v${write.registerB}, v$result}, $STORY_ROW_BUTTONS->onOwnCellAnswer(Ljava/lang/Object;I)V",
    )
    addInstruction(
        owner + 1,
        "invoke-static {v${write.registerB}, v${write.registerA}}, $STORY_ROW_BUTTONS->onStoryCellOwner(Ljava/lang/Object;I)V",
    )
}

/**
 * The call that shows the Following row's All button: the recent follows experiment is asked, and
 * the next static (visibility, view)V call is TikTok's own helper setting the button from the
 * answer. onViewCreated and the row's refresh both do this and nothing else asks the experiment
 * inside those two methods first. -1 when the method doesn't do that.
 */
internal fun allButtonShowIndex(method: Method): Int {
    val code = method.code()
    val asked = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let {
            it.definingClass == RECENT_FOLLOW_EXPERIMENT && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } == true
    }
    if (asked < 0) return -1
    return (asked + 1 until code.size).firstOrNull { index ->
        code[index].opcode == Opcode.INVOKE_STATIC && code[index].getReference<MethodReference>()?.let {
            it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I", "Landroid/view/View;")
        } == true
    } ?: -1
}

/**
 * Hands the button to the extension right after TikTok's show call. A branch landing on the next
 * instruction skips this, which is right: only the way through the show call has shown it.
 */
internal fun MutableMethod.hookAllButtonShow(patch: String) {
    val show = allButtonShowIndex(this)
    if (show < 0) throw PatchException("$patch: $definingClass->$name no longer shows the All button.")
    val button = (implementation!!.instructions.toList()[show] as FiveRegisterInstruction).registerD
    addInstruction(show + 1, "invoke-static {v$button}, $STORY_ROW_BUTTONS->hideAllButton(Landroid/view/View;)V")
}
