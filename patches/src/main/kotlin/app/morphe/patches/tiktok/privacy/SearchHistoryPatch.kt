/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/SearchHistoryRecording;"
internal const val SEARCH_HISTORY = "Lcom/ss/android/ugc/aweme/search/model/SearchHistory;"

/**
 * The per-account log of typed queries 47.7.3 kept beside the history (Keva repo
 * `search_history`, key `manual_search_pv_7d_v1`). 47.8.3 dropped it: the class, its key and
 * the history callable's call to it are all gone, so the manager below is the one writer left.
 */
internal const val MANUAL_SEARCH_PV_STORE =
    "Lcom/ss/android/ugc/aweme/search/pages/middlepage/history/ManualSearchPvStore;"

/**
 * The history manager's record method, the one place a new search joins the list the search
 * page shows. The manager is renamed on every build (0D7Z on 47.0.3, 0D1i on 47.1.3, 0D1m on
 * 47.1.4) and reached through an interface, so the callers (typed searches, suggestions, the
 * Lynx search page) all land here. Its log lines open with the real name, as in
 * `recordSearchHistory, repo:`, and no other method loads one; the manager's delete method has
 * the same signature and logs `deleteSearchHistory, repo = `, which is why the signature alone
 * doesn't do.
 */
internal fun isSearchHistoryRecorder(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && !AccessFlags.ABSTRACT.isSet(method.accessFlags) &&
        method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(SEARCH_HISTORY, "Ljava/lang/String;") &&
        method.implementation?.instructions?.any {
            it.getReference<StringReference>()?.string?.startsWith("recordSearchHistory,") == true
        } == true

private object SearchHistoryRecordFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(SEARCH_HISTORY, "Ljava/lang/String;"),
    // The log line's whole literal, so the string index finds the manager without a full walk.
    strings = listOf("recordSearchHistory, repo:"),
    custom = { method, _ -> isSearchHistoryRecorder(method) },
)

/** Leaves a history writer before it touches anything when the switch is on. */
internal fun MutableMethod.skipWhenHistoryOff() = guardAtEntry(
    "Stop saving search history",
    "invoke-static {}, $EXTENSION->shouldSkip()Z",
    "return-void",
)

/**
 * Keeps new searches out of the search history TikTok saves on the phone. The writer returns
 * before it changes anything, so what is already saved stays and can still be deleted from
 * the search page. Searching itself is untouched, and so is whatever TikTok keeps on its
 * servers.
 */
@Suppress("unused")
val searchHistoryPatch = bytecodePatch(
    name = "Stop saving search history",
    description = "Stops TikTok saving your new searches on your phone. Older searches stay " +
        "until you delete them, and TikTok's servers may keep their own record. Starts off. Turn " +
        "it on in Hushfeed settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Resolved before anything is written, so a build without it leaves the patch unapplied
        // rather than half applied.
        val recorder = SearchHistoryRecordFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSearchHistory()V",
        )
        recorder.skipWhenHistoryOff()
    }
}
