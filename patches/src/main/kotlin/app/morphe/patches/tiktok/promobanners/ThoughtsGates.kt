/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.checkBooleanWhenOn
import app.morphe.patches.tiktok.shared.isLazyAbRead
import app.morphe.patches.tiktok.shared.resolveLazyAbGate
import app.morphe.patches.tiktok.shared.returnBooleanWhenOn

internal const val THOUGHTS_SWITCH = "Lapp/morphe/extension/tiktok/profile/ThoughtsGate;->isOn()Z"

/**
 * The AB key behind writing a Thought (#186): the "Share your thoughts..." prompt on your own
 * profile and the composer it opens. Its default is 1, so an account without it has been sent 0.
 */
internal const val THOUGHTS_PUBLISH_KEY = "story_note_enable_publish"

/**
 * The AB key behind seeing Thoughts (#186): the bubble above a profile picture and the Thoughts
 * row in the inbox. Same default and same shape as [THOUGHTS_PUBLISH_KEY].
 */
internal const val THOUGHTS_CONSUMPTION_KEY = "story_note_enable_consumption"

private const val THOUGHTS_WHAT = "Turn on Thoughts"

/**
 * TikTok's two Thoughts gates, `X.0InD.LIZ()` (publish) and `X.0NmZ.LIZ()` (consumption) on
 * 47.5.3. Each is a static no-argument boolean on a class of its own that reads its key's lazy
 * value and answers yes when it is 1 and the account's Stories experiment (tt_story2_exp) is
 * above 0. Every Thoughts surface asks one of the two, directly or through the helper that ORs
 * them with the two redesign gates, so these are the whole switch. The Under 16 and account type
 * check that sits beside them is TikTok's and is left alone.
 */
internal class ThoughtsGates(val publish: MutableMethod, val consumption: MutableMethod)

private fun resolveThoughtsGate(key: String): MutableMethod =
    resolveLazyAbGate(THOUGHTS_WHAT, key) { method, _ ->
        method.returnType == "Z" && method.parameterTypes.none() && method.isLazyAbRead()
    }

/** Both gates, checked for the hook before either is written. Throws when either is missing. */
internal fun resolveThoughtsGates(): ThoughtsGates {
    val publish = resolveThoughtsGate(THOUGHTS_PUBLISH_KEY)
    val consumption = resolveThoughtsGate(THOUGHTS_CONSUMPTION_KEY)
    if (publish.definingClass == consumption.definingClass && publish.name == consumption.name) {
        throw PatchException("$THOUGHTS_WHAT: both keys led to ${publish.definingClass}->${publish.name}")
    }
    publish.checkBooleanWhenOn(THOUGHTS_WHAT)
    consumption.checkBooleanWhenOn(THOUGHTS_WHAT)
    return ThoughtsGates(publish, consumption)
}

/**
 * With the switch on both gates answer yes, and with it off, or while Hushfeed is paused, TikTok
 * reads its own values. Whether a Thought written this way posts is still up to TikTok's servers.
 */
internal fun ThoughtsGates.installThoughtsSwitch() {
    publish.returnBooleanWhenOn(THOUGHTS_WHAT, THOUGHTS_SWITCH, true)
    consumption.returnBooleanWhenOn(THOUGHTS_WHAT, THOUGHTS_SWITCH, true)
}
