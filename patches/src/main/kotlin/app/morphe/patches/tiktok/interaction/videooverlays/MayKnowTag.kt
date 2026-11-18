/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MAY_KNOW_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val MATCHED_FRIEND = "Lcom/ss/android/ugc/aweme/profile/model/MatchedFriendStruct;"
private const val AWEME_BIZ = "Lcom/ss/android/ugc/aweme/feed/model/AwemeBizExtKt;"

/**
 * Whether a video gets TikTok's "people you may know" tag beside the creator's name (#462): one
 * static `(Aweme)Z` both of the tag's triggers ask. Its class is renamed on every build, so it is
 * found by what it reads. The Friends and Following labels are decided by a gate of their own
 * that reads none of these, so they stay.
 */
internal object MayKnowTagGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(MAY_KNOW_AWEME),
    filters = listOf(fieldAccess(definingClass = MATCHED_FRIEND, name = "socialUIConfig")),
    custom = { method, _ -> isMayKnowTagGate(method) },
)

internal fun isMayKnowTagGate(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf(MAY_KNOW_AWEME)) return false
    val code = method.implementation ?: return false
    var readsConfig = false
    var readsSchool = false
    var asksRepost = false
    for (instruction in code.instructions) {
        instruction.getReference<FieldReference>()?.let {
            if (it.definingClass == MATCHED_FRIEND && it.name == "socialUIConfig") readsConfig = true
            if (it.definingClass == MATCHED_FRIEND && it.name == "schoolMutualStruct") readsSchool = true
        }
        instruction.getReference<MethodReference>()?.let {
            if (it.definingClass == AWEME_BIZ && it.name == "getReplaceRecTagByRepost") asksRepost = true
        }
    }
    return readsConfig && readsSchool && asksRepost
}

/**
 * Puts the switch in front of the gate. With it on the gate answers false at once, which is what
 * a video with no such tag already gets, and with it off the gate runs from its first instruction.
 */
internal fun MutableMethod.hideMayKnowTag(patch: String) = guardAtEntry(
    patch,
    "invoke-static {}, Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->shouldHideMayKnowTag()Z",
    """
        const/4 v0, 0x0
        return v0
    """,
)
