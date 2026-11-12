/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.remoteconfig

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.returnVoidWhenOn
import app.morphe.util.addInstruction

private const val PATCH = "Freeze TikTok's experiments"
internal const val REMOTE_CONFIG_SWITCH =
    "Lapp/morphe/extension/tiktok/misc/RemoteConfigFreeze;->holdsStore()Z"

/**
 * The store of a whole A/B settings response. It parses a response that already arrived, then
 * writes its feature flags into the values TikTok reads, logging each step under these names.
 * 47.7.3 has the same code twice, `X.0GWL.LIZ` for FetchABTestCommonTask and a runnable's `LIZ$0`
 * for FetchABTestCommonRequest, so [responseStores] takes every match.
 */
internal object ResponseStoreFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    isStatic = false,
    strings = listOf("ab_common_request_response_parse", "ab_common_request_start_to_store_feature_flags"),
)

/**
 * The store of one chunk of a chunked response (`X.1TpM.LIZ`, which FetchABTestCommonChunkTask's
 * runnable calls). It merges the chunk into what was stored before and saves the result.
 */
internal object ChunkStoreFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    isStatic = false,
    strings = listOf("ABTestChunk ChunkAll update originalData: size: ", "ab_common_request_save_config_data_finish_to_end"),
)

/**
 * The store of a chunk on the optimized path (`X.08fe.LIZLLL`), which the chunk observer's onNext
 * calls with the parsed chunk. It writes the typed values itself or hands them to the main AB
 * store, so it's the one point every optimized chunk passes.
 */
internal object OptimizedChunkStoreFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", "L", "L", "L", "L"),
    isStatic = false,
    strings = listOf("ABTestChunk resp data key size: ", "value_group_hash"),
)

@Suppress("unused")
val freezeRemoteConfigPatch = bytecodePatch(
    // A literal, not PATCH: DefaultSelectionPolicyTest reads the declarations as text.
    name = "Freeze TikTok's experiments",
    description = "Lets you keep the settings TikTok's servers sent it so far. TikTok tests new " +
        "layouts and features on people by sending new ones all the time, and with this on it " +
        "ignores them. Starts off. Turn it on in Hushfeed settings > App.",
) {
    category("Settings")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        installRemoteConfigFreeze()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableRemoteConfigFreeze()V",
        )
    }
}

/**
 * The patch's hooks, without its settings row, so a test can put them on a build that carries no
 * extension. Each store returns before it writes anything while the switch answers true, and runs
 * as TikTok wrote it otherwise.
 */
internal fun installRemoteConfigFreeze() {
    for (store in responseStores()) store.returnVoidWhenOn(PATCH, REMOTE_CONFIG_SWITCH)
    ChunkStoreFingerprint.method.returnVoidWhenOn(PATCH, REMOTE_CONFIG_SWITCH)
    OptimizedChunkStoreFingerprint.method.returnVoidWhenOn(PATCH, REMOTE_CONFIG_SWITCH)
}

/**
 * Every store of a whole response. Holding only the first one the patcher finds would let the
 * other request's responses through, so each copy gets the guard.
 */
internal fun responseStores(): List<MutableMethod> {
    val stores = ResponseStoreFingerprint.matchAllOrNull().orEmpty().map { it.method }
    if (stores.isEmpty()) throw PatchException("$PATCH: found no store of a whole server settings response.")
    return stores
}
