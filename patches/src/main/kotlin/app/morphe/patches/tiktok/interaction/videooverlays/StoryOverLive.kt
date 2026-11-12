/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STORY_OVER_LIVE_PATCH = "Remove avatar rings"
private const val STORY_OVER_LIVE = "Lapp/morphe/extension/tiktok/feed/StoryOverLive;"
private const val AVATAR_COMPONENT_VIEW = "Lcom/ss/android/ugc/aweme/avatar/AvatarComponentView;"

/**
 * The tap on the picture at the top of a creator's profile (#233), a static `(listener, View)V`
 * in one of R8's merged click listener classes. It logs "click_head", then asks the picture's
 * AvatarComponentView for its map of what it shows and opens the LIVE when that holds LIVE
 * (the "c68718.d0" jump token), before it ever looks for a story.
 */
internal object ProfileAvatarClickFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("click_head", "c68718.d0"),
    custom = { method, _ -> storyOverLiveSite(method) != null },
)

private const val CHAT_ROW = "Lcom/ss/android/ugc/aweme/im/chatlist/impl/ui/viewholder/chatlistvo/NewChatListSingleChatVH;"

/**
 * The tap on a chat's picture in the Inbox (#233), an `onClick(View)` listener holding the chat
 * row and its AvatarComponentView. When the row has a LIVE room id it opens the LIVE (the
 * "c71313.d0" jump token, enter method "dm_head") before it ever asks the picture for a story.
 */
internal object InboxAvatarClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    strings = listOf("c71313.d0", "dm_head"),
    custom = { method, _ -> method.name == "onClick" && inboxStorySite(method) != null },
)

/** Where the Inbox hook goes: the LIVE branch's first instruction, the picture's load. */
internal data class InboxStorySite(val index: Int, val picture: FieldReference, val register: Int, val listener: Int)

/**
 * The listener's load of its AvatarComponentView that opens the LIVE branch, right after the
 * `if-eqz` on the LIVE room id the listener reads off the chat row. Null when the method isn't
 * that shape.
 */
internal fun inboxStorySite(method: Method): InboxStorySite? {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val implementation = method.implementation ?: return null
    val code = implementation.instructions.toList()
    val listener = implementation.registerCount - 2
    for (index in 0 until code.size - 2) {
        val read = code[index]
        if (read.opcode != Opcode.IGET_OBJECT) continue
        val live = read.getReference<FieldReference>() ?: continue
        if (live.definingClass != CHAT_ROW || live.type != "Ljava/lang/Long;") continue
        val branch = code[index + 1]
        if (branch.opcode != Opcode.IF_EQZ ||
            (branch as OneRegisterInstruction).registerA != (read as TwoRegisterInstruction).registerA
        ) {
            continue
        }
        val load = code[index + 2]
        val picture = load.getReference<FieldReference>() ?: return null
        if (load.opcode != Opcode.IGET_OBJECT || picture.definingClass != method.definingClass ||
            picture.type != AVATAR_COMPONENT_VIEW
        ) {
            return null
        }
        load as TwoRegisterInstruction
        if (load.registerB != listener) return null
        return InboxStorySite(index + 2, picture, load.registerA, listener)
    }
    return null
}

/** Where the hook goes: the LIVE key's load, and the map and scratch registers it uses. */
internal data class StoryOverLiveSite(val index: Int, val map: Int, val scratch: Int, val view: Int)

/**
 * The `sget-object LIVE` that the LIVE check's `Map.containsKey` takes, in a static method whose
 * last parameter is the tapped View, on the map that `getCurrentActiveBusiness` handed back.
 * Null when the method isn't that shape, or when the View's register is written before the check.
 */
internal fun storyOverLiveSite(method: Method): StoryOverLiveSite? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return null
    if (method.parameterTypes.lastOrNull()?.toString() != "Landroid/view/View;") return null
    val implementation = method.implementation ?: return null
    val code = implementation.instructions.toList()
    val view = implementation.registerCount - 1
    for (index in 0 until code.size - 1) {
        val load = code[index]
        if (load.opcode != Opcode.SGET_OBJECT) continue
        val key = load.getReference<FieldReference>() ?: continue
        if (key.name != "LIVE" || key.type != key.definingClass) continue
        val check = code[index + 1]
        val contains = check.getReference<MethodReference>() ?: continue
        if (check.opcode != Opcode.INVOKE_INTERFACE || contains.definingClass != "Ljava/util/Map;" ||
            contains.name != "containsKey"
        ) {
            continue
        }
        val call = check as FiveRegisterInstruction
        val scratch = (load as OneRegisterInstruction).registerA
        if (call.registerCount != 2 || call.registerD != scratch || call.registerC == scratch) continue
        if (!readsBusinessInto(code, index, call.registerC)) continue
        if (code.take(index).any { it.writes(view) }) return null
        return StoryOverLiveSite(index, call.registerC, scratch, view)
    }
    return null
}

/** Whether the map register last came from AvatarComponentView's getCurrentActiveBusiness. */
private fun readsBusinessInto(code: List<Instruction>, before: Int, map: Int): Boolean {
    for (index in before - 1 downTo 1) {
        val instruction = code[index]
        if (!instruction.writes(map)) continue
        if (instruction.opcode != Opcode.MOVE_RESULT_OBJECT) return false
        val call = code[index - 1].getReference<MethodReference>() ?: return false
        return call.definingClass == AVATAR_COMPONENT_VIEW && call.name == "getCurrentActiveBusiness" &&
            call.parameterTypes.isEmpty() && call.returnType == "Ljava/util/Map;"
    }
    return false
}

private fun Instruction.writes(register: Int) = opcode.setsRegister() &&
    (this as? OneRegisterInstruction)?.registerA.let {
        it == register || (opcode.setsWideRegister() && it == register - 1)
    }

/**
 * Hooks the profile picture's tap, and the Inbox chat picture's, for the Story first switch (#233)
 * and says whether the profile hook went in. Required on a declared build, where the anchors test holds it, and left out with a
 * note on any other, so a reworked profile there doesn't take the rings down with it. The row
 * shows only when the hook went in.
 */
internal fun hookStoryOverLiveOn(versionName: String): Boolean {
    if (!hookStoryOverLive(versionName)) return false
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableStoryOverLive()V",
    )
    return true
}

/**
 * The hooks alone, without the settings flag that shows the row, so a probe patch can run them.
 * The profile picture's is the one the switch needs; the Inbox's goes in alongside it.
 */
internal fun hookStoryOverLive(versionName: String): Boolean {
    val declared = versionName in declaredVersions()
    val tap = if (declared) ProfileAvatarClickFingerprint.method else ProfileAvatarClickFingerprint.methodOrNull
    if (tap == null) {
        println("[$STORY_OVER_LIVE_PATCH] Left out the Story first switch on $versionName: no profile picture tap found.")
        return false
    }
    tap.openStoryBeforeLive()
    val chat = if (declared) InboxAvatarClickFingerprint.method else InboxAvatarClickFingerprint.methodOrNull
    if (chat == null) {
        println("[$STORY_OVER_LIVE_PATCH] The Story first switch leaves the Inbox to TikTok on $versionName: no Inbox picture tap found.")
    } else {
        chat.openChatStoryBeforeLive()
    }
    return true
}

/**
 * Asks StoryOverLive at the top of the Inbox picture's LIVE branch, with the picture the branch
 * loads next. A yes means the Story opened and the tap returns; a no runs the branch as it was.
 * The register is the one that load writes, so nothing live is overwritten.
 */
internal fun MutableMethod.openChatStoryBeforeLive() {
    val site = inboxStorySite(this)
        ?: throw PatchException("$STORY_OVER_LIVE_PATCH: the Inbox picture's LIVE open is gone.")
    val picture = "${site.picture.definingClass}->${site.picture.name}:${site.picture.type}"
    addInstructionsWithLabels(
        site.index,
        """
            iget-object v${site.register}, v${site.listener}, $picture
            invoke-static { v${site.register} }, $STORY_OVER_LIVE->openChatStory(Landroid/view/View;)Z
            move-result v${site.register}
            if-eqz v${site.register}, :stock
            return-void
        """,
        ExternalLabel("stock", getInstruction(site.index)),
    )
}

/**
 * Asks StoryOverLive ahead of the LIVE check, with the map and the tapped View. A yes means the
 * Story opened and the tap returns; a no runs TikTok's own check from the LIVE key's load on. The
 * scratch register is the one that load writes next, so nothing live is overwritten.
 */
internal fun MutableMethod.openStoryBeforeLive() {
    val site = storyOverLiveSite(this)
        ?: throw PatchException("$STORY_OVER_LIVE_PATCH: the profile picture's LIVE check is gone.")
    addInstructionsWithLabels(
        site.index,
        """
            move-object/from16 v${site.scratch}, v${site.view}
            invoke-static { v${site.map}, v${site.scratch} }, $STORY_OVER_LIVE->openStory(Ljava/util/Map;Landroid/view/View;)Z
            move-result v${site.scratch}
            if-eqz v${site.scratch}, :stock
            return-void
        """,
        ExternalLabel("stock", getInstruction(site.index)),
    )
}
