/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.foldable

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val TABLET_ROTATION_EXTENSION = "Lapp/morphe/extension/tiktok/foldable/TabletRotation;"

/** A profile opened from search, which TikTok's manifest locks to portrait. */
internal const val PROFILE_HOST_ACTIVITY =
    "Lcom/ss/android/ugc/profile/platform/framework/aweme/profile/ui/UserProfileHostActivity;"

/** The picture a comment carries, opened full screen. It asks for portrait on its own. */
internal const val COMMENT_PHOTO_ACTIVITY = "Lcom/ss/android/ugc/aweme/comment/photocomment/PhotoDetailActivityV2;"

/**
 * TikTok's check whether this device's screens may turn (X.07A4.LIZ on 47.9.3): a tablet wide
 * enough, in the right test group. Its main screen asks to follow the device when this says yes.
 */
internal object SupportLandscapeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("Landroid/app/Activity;"),
    strings = listOf(" supportLandscape: false, r: device not tablet"),
)

internal object ProfileHostCreateFingerprint : Fingerprint(
    definingClass = PROFILE_HOST_ACTIVITY,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

private fun isOrientationRequest(name: String, returnType: String, parameters: List<CharSequence>) =
    name == "setRequestedOrientation" && returnType == "V" && parameters.map { it.toString() } == listOf("I")

/** Where [method] asks its superclass for an orientation, by index. */
internal fun superOrientationRequests(method: Method): List<Int> =
    method.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_SUPER &&
            ((instruction as ReferenceInstruction).reference as? MethodReference)
                ?.let { isOrientationRequest(it.name, it.returnType, it.parameterTypes) } == true
    }?.map { it.index } ?: emptyList()

/** The comment picture's own setRequestedOrientation and the other methods' direct requests. */
internal class CommentPhotoRequests<M : Method>(val override: M, val direct: Map<M, List<Int>>)

/**
 * The comment picture's orientation requests among its [methods]: its override, which asks its
 * superclass for portrait whatever it's handed, and the methods that skip it and ask the
 * superclass themselves (create, resume and a configuration change on 47.9.3).
 */
internal fun <M : Method> commentPhotoRequests(methods: Iterable<M>): CommentPhotoRequests<M> {
    val override = methods.singleOrNull { isOrientationRequest(it.name, it.returnType, it.parameterTypes) }
        ?: throw PatchException("Tablet rotation: the comment picture no longer has its own setRequestedOrientation")
    if (superOrientationRequests(override).size != 1) {
        throw PatchException("Tablet rotation: the comment picture's setRequestedOrientation no longer asks its superclass once")
    }
    val direct = methods.filter { it !== override }
        .associateWith { superOrientationRequests(it) }
        .filterValues { it.isNotEmpty() }
    if (direct.isEmpty()) {
        throw PatchException("Tablet rotation: nothing in the comment picture asks for an orientation directly any more")
    }
    return CommentPhotoRequests(override, direct)
}

/**
 * Hands the orientation the override asks its superclass for to the extension first, which
 * gives back the one TikTok's main screen asks for on a tablet that turns. The call is the
 * 4-bit form, so both registers fit the extension call as they are.
 */
internal fun MutableMethod.askThroughTabletRotation() {
    val index = superOrientationRequests(this).single()
    val call = getInstruction(index) as FiveRegisterInstruction
    addInstructionsAtControlFlowLabel(
        index,
        """
            invoke-static {v${call.registerC}, v${call.registerD}}, $TABLET_ROTATION_EXTENSION->orientation(Landroid/app/Activity;I)I
            move-result v${call.registerD}
        """,
    )
}

/**
 * Sends a direct request at [index] through the screen's own override, same registers, so it
 * gets the same answer. The override ignores the value it's handed, as these always ask for
 * portrait.
 */
internal fun MutableMethod.requestThroughOverride(index: Int, photo: String) {
    val call = getInstruction(index) as FiveRegisterInstruction
    if (call.registerCount != 2) throw PatchException("Tablet rotation: a direct orientation request has ${call.registerCount} registers")
    replaceInstruction(
        index,
        "invoke-virtual {v${call.registerC}, v${call.registerD}}, $photo->setRequestedOrientation(I)V",
    )
}

/** Has a profile ask to follow the device as each way out of its onCreate returns. */
internal fun MutableMethod.followAfterCreate() {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("Tablet rotation: the profile's onCreate never returns")
    returns.sortedDescending().forEach { index ->
        addInstructionsAtControlFlowLabel(
            index,
            "invoke-static/range {p0 .. p0}, $TABLET_ROTATION_EXTENSION->follow(Landroid/app/Activity;)V",
        )
    }
}

/**
 * TabletRotation.tikTokSupportsLandscape, a stub that answers no, rewritten as a call to TikTok's
 * own check. D8 builds the stub with no register of its own (it answers through p0), so the copy
 * gets one, which is free at the start, where the call goes.
 */
internal fun landscapeBridge(stub: Method, check: Method): MutableMethod {
    if (!AccessFlags.STATIC.isSet(check.accessFlags)) throw PatchException("Tablet rotation: TikTok's landscape check is no longer static")
    return stub.cloneMutable(additionalRegisters = 1).apply {
        addInstructions(
            0,
            """
                invoke-static/range {p0 .. p0}, ${check.definingClass}->${check.name}(Landroid/app/Activity;)Z
                move-result v0
                return v0
            """,
        )
    }
}

@Suppress("unused")
val tabletRotationPatch = bytecodePatch(
    name = "Turn profiles and comment pictures on tablets",
    description = "On a tablet where TikTok lets its main screen turn, a profile opened from " +
        "search and a comment's picture turn with it instead of flipping the screen to portrait. " +
        "Works as soon as you patch it in, with no switch.",
) {
    category("Interface")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(sharedExtensionPatch, settingsPatch)
    execute {
        // Found before anything is written.
        val check = SupportLandscapeFingerprint.method
        val profileCreate = ProfileHostCreateFingerprint.method
        val requests = commentPhotoRequests(mutableClassDefBy(COMMENT_PHOTO_ACTIVITY).methods)
        val extension = mutableClassDefBy(TABLET_ROTATION_EXTENSION)
        val stub = extension.methods.singleOrNull { it.name == "tikTokSupportsLandscape" }
            ?: throw PatchException("Tablet rotation: missing the landscape bridge")
        val bridge = landscapeBridge(stub, check)

        extension.methods.remove(stub)
        extension.methods.add(bridge)
        profileCreate.followAfterCreate()
        requests.override.askThroughTabletRotation()
        requests.direct.forEach { (method, indexes) ->
            indexes.sortedDescending().forEach { method.requestThroughOverride(it, COMMENT_PHOTO_ACTIVITY) }
        }
    }
}
