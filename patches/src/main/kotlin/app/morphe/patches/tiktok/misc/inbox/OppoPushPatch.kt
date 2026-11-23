/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry

/** TikTok's adapter for OPPO's HeyTap push, kept by name since its push SDK loads it from a string. */
internal const val OPPO_PUSH_ADAPTER = "Lcom/coloros/OpPushAdapter;"
internal const val OPPO_PUSH_EXTENSION = "Lapp/morphe/extension/tiktok/inbox/OppoPush;"
private const val OPPO_PUSH_PATCH = "Fix notifications on OPPO, OnePlus and realme"

/**
 * Whether this phone has OPPO's push service, asked when TikTok lists the push services it can
 * take for its server and before each registration. It calls HeytapPushManager.isSupportPush.
 */
internal object OppoPushAvailableFingerprint : Fingerprint(
    definingClass = OPPO_PUSH_ADAPTER,
    name = "isPushAvailable",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "I"),
)

/** TikTok's registration with OPPO's push service, which uploads the token it gets back. */
internal object OppoPushRegisterFingerprint : Fingerprint(
    definingClass = OPPO_PUSH_ADAPTER,
    name = "registerPush",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "I"),
)

@Suppress("unused")
val oppoPushPatch = bytecodePatch(
    name = "Fix notifications on OPPO, OnePlus and realme",
    description = "On OPPO, OnePlus and realme phones TikTok can send its notifications through " +
        "OPPO's push service, which only delivers to TikTok as TikTok signed it, not to a patched " +
        "copy. This has TikTok use Google's push service on those phones, like it does on other " +
        "phones. Other phones don't change. Works as soon as you patch it in, with no switch.",
) {
    category("Inbox")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(sharedExtensionPatch, settingsPatch)
    execute {
        // Both found before anything is written.
        val available = OppoPushAvailableFingerprint.method
        val register = OppoPushRegisterFingerprint.method
        available.guardAtEntry(
            OPPO_PUSH_PATCH,
            "invoke-static {}, $OPPO_PUSH_EXTENSION->skipOppoPush()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        register.guardAtEntry(
            OPPO_PUSH_PATCH,
            "invoke-static {}, $OPPO_PUSH_EXTENSION->skipOppoPush()Z",
            "return-void",
        )
        registerForBuildDetails("enableOppoPushFix")
    }
}
