/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/settings/Fingerprints.kt
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference


internal object AdPersonalizationActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;",
    name = "onCreate",
)

internal object AdPersonalizationActivityOnBackPressedFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;",
    name = "onBackPressed",
    returnType = "V",
    parameters = emptyList(),
)



internal object SettingsStatusLoadFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/tiktok/settings/SettingsStatus;",
    name = "load",
)

/**
 * Records that a patch with no settings row of its own went in, so Build details lists it with
 * the rest. Call it last in execute: a patch that fails partway must not claim to be present.
 */
internal fun BytecodePatchContext.registerForBuildDetails(enable: String) {
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->$enable()V",
    )
}

/**
 * The crash reporter's initialiser, which is handed the application context. The context is
 * what the injection passes on, so its position is part of the shape: first, and the only
 * parameter, or the register arithmetic in the patch names something else. R8 names it LIZ on
 * TikTok; the task's run(Context) is the class's only other method
 * of that shape.
 */
internal object NpthExtentTaskInitFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/legoImp/task/NpthExtentTask;",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    custom = { method, _ -> method.name != "run" },
)

/**
 * The compose pass that turns TikTok's settings cells into the visible, sorted menu list.
 *
 * TikTok 47.0.3 changed the method name and parameter count again. The class and the final
 * comparator-plus-iterable sort are the contract the patch actually consumes, so matching that
 * call keeps the Hushfeed row at index zero without coupling the fingerprint to R8 output.
 */
internal fun isSettingsComposeRowsMethod(method: Method, classDef: ClassDef): Boolean =
    classDef.type.endsWith("/SettingsComposeRvmpFragment;") &&
        method.returnType == "V" &&
        method.implementation?.instructions?.any { instruction ->
            if (instruction.opcode != Opcode.INVOKE_STATIC) return@any false
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@any false
            reference.isSettingsRowsSort()
        } == true

/** Kotlin's sorted-with helper after obfuscation. Its owner and name drift; its contract does not. */
internal fun MethodReference.isSettingsRowsSort(): Boolean =
    parameterTypes == listOf("Ljava/util/Comparator;", "Ljava/lang/Iterable;") &&
        returnType == "Ljava/util/List;"

internal object SettingsComposeRowsFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/setting/ui/rvmpcompose/SettingsComposeRvmpFragment;",
    returnType = "V",
    custom = ::isSettingsComposeRowsMethod,
)

internal object SupportGroupDefaultStateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/setting/ui/rvmpcompose/group/support/SupportGroupVM;",
    name = "defaultState",
)

internal object OpenDebugCellVmDefaultStateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/setting/ui/rvmpcompose/group/support/cells/OpenDebugCellVM;",
    name = "defaultState",
)
