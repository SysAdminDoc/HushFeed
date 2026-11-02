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
 * Where a story cell's bind writes "this is the viewer's own item and it has no Story": the first
 * static (Aweme)Z call asks whether the item has a Story, and the next boolean written to the cell
 * holds the answer on both ways in (true past the checks, false from the branches that jump to the
 * same write). That's the Create circle, on the Following row and in the Inbox alike, and the
 * flag's name changes on every build. -1 when the method doesn't do that.
 */
internal fun storyCreateFlagIndex(method: Method): Int {
    val code = method.code()
    val asked = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(STORY_AWEME)
        } == true
    }
    if (asked < 0) return -1
    return (asked + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.IPUT_BOOLEAN &&
            code[it].getReference<FieldReference>()?.definingClass == method.definingClass
    } ?: -1
}

/** A story cell's bind, the one (item)V method on the class that writes the Create flag. */
internal fun isStoryCellBind(method: Method): Boolean =
    method.returnType == "V" && method.parameterTypes.size == 1 && storyCreateFlagIndex(method) >= 0

/**
 * Hands the flag and the cell to the extension right after the flag is written. Nothing branches
 * past the write, so both ways in reach the call, and the registers are the write's own. The
 * parameter is an int because one way in writes a const (the Z rule in the repo's notes).
 */
internal fun MutableMethod.hookStoryCreateFlag(patch: String) {
    val flag = storyCreateFlagIndex(this)
    if (flag < 0) throw PatchException("$patch: $definingClass->$name no longer writes the Create flag.")
    val write = implementation!!.instructions.toList()[flag] as TwoRegisterInstruction
    if (write.registerA > 15 || write.registerB > 15) {
        throw PatchException("$patch: $definingClass->$name writes the Create flag above v15.")
    }
    addInstruction(
        flag + 1,
        "invoke-static {v${write.registerB}, v${write.registerA}}, $STORY_ROW_BUTTONS->onStoryCellBound(Ljava/lang/Object;I)V",
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
