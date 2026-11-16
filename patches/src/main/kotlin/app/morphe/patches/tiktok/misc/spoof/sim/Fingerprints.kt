/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.patcher.Fingerprint

/**
 * The startup task that notices the SIM has changed and reports it.
 *
 * <p>Read out of the 46.2.3 fixture on 2026-09-09. `run` is gated on the `enable_sim_utils` AB
 * key, reads `TelephonyManager.getSimState`, builds the current SIM identity through
 * `LX/0Nid;->LIZ`, compares it with the one stored in Keva under `last_sim_info`, stores the new
 * one, and if the two differ reads `SubscriptionManager.getActiveSubscriptionInfoCount` (index 82)
 * and sends the event `ttac_sim_change_detect` carrying `sim_change_type`, `duration`, `n_sim`,
 * `prev_sim_country_code`, `prev_carrier`, `curr_sim_country_code` and `curr_carrier`. That event
 * is all it does: nothing else in the app reads `last_sim_info`, so the task can be skipped
 * whole.
 *
 * <p>"Differ" means the first field of `LX/0Nic;` only, which its own `toString` names
 * `subscriptionId` and which comes from `SubscriptionManager.getDefaultDataSubscriptionId` or
 * `getActiveDataSubscriptionId`. The country and carrier are the second and third fields and take
 * no part in the comparison: they come from `LX/1AbB;->LLIIZ` and `LLILII`, which wrap
 * `TelephonyManager.getSimCountryIso` and `getSimOperatorName`, so the spoof already reaches them
 * and both halves of the event read as the preset. Neither the subscription id nor the
 * subscription count goes through TikTok's telephony wrappers at all, so what the event still
 * carries is the phone's real SIM count and the fact that its hardware changed.
 */
/**
 * Fresco's network fetch producer, `produceResults(Consumer, ProducerContext)` (`LX/0CT5;->LIZ`
 * on 47.7.3). It builds the fetch state through the fetcher (`LX/03zQ;->LIZIZ`, returning
 * `LX/0Eoc;`), turns an expired signed URL down on the phone by calling its own failure method
 * (`LJFF(FetchState, Throwable)`) with the second string, and otherwise hands the state to the
 * fetcher, whose callbacks reach the same failure method. Every image fetch and every failed one
 * pass through it, which is what the IMAGE LOADS report counts (#415).
 */
internal object NetworkFetchProducerFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("NetworkFetchProducer", "The expired url does not initiate the request"),
)

internal object CheckSimChangeTaskFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("last_sim_info", "last_check_time", "ttac_sim_change_detect"),
    custom = { method, classDef ->
        classDef.type == "Lcom/ss/android/ugc/aweme/legoImpl/task/CheckSIMChangeTask;" &&
            method.name == "run"
    },
)
