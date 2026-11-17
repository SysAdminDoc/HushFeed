/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val CONTINUE_CONFIG =
    "Lcom/ss/android/ugc/aweme/feed/experiment/FeedPlayProgressContinueConfig;"

/**
 * The lambda that answers whether a video resumes where it was left.
 *
 * <p>Its class was written here as `LX/0Lyr;`, which is `LX/0MGs;` on 46.7.3 and `LX/0M77;` on
 * 46.8.3. What it reads is not renamed: `FeedPlayProgressContinueConfig.enable`, and exactly one
 * `invoke()` in the app reads it on each of the three builds.
 */
internal object FeedProgressContinueGateFingerprint : Fingerprint(
    name = "invoke",
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(fieldAccess(definingClass = CONTINUE_CONFIG, name = "enable")),
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<FieldReference>()?.let { field ->
                field.definingClass == CONTINUE_CONFIG && field.name == "enable"
            } == true
        } == true
    },
)

internal object FeedPlayCompletedFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;",
    name = "onPlayCompleted",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

internal object FeedPlayProgressFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;",
    name = "onPlayProgressChange",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
)

// The method that answers "where should this video start". It's found by the one read of the
// config's event_type_list, the check the patch hooks: 47.8.3 gave the method a third parameter
// and moved the landscape tags it used to be found by into a helper of their own.
internal object FeedProgressResumePositionFingerprint : Fingerprint(
    returnType = "J",
    filters = listOf(fieldAccess(definingClass = CONTINUE_CONFIG, name = "event_type_list")),
    custom = { method, _ ->
        method.parameterTypes.firstOrNull()?.toString() == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    },
)
