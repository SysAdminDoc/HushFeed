/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

private const val HIDE_READ_RECEIPTS = "Lapp/morphe/extension/tiktok/inbox/HideReadReceipts;"
private const val HIDE_READ_RECEIPTS_PATCH = "Hide read receipts"
private const val REQUEST_BODY = "Lcom/bytedance/im/core/proto/RequestBody;"

/**
 * The method every chat request handler in TikTok's IM SDK sends its request through: the inbox type, the request
 * body, a callback and extras, answering the id of the request. The class and method names are obfuscated, so it
 * is found by what it takes, and the body type, which keeps its readable name, tells it from other methods. A
 * second family of handlers has a final override of the same shape that sends through another queue. Chat read
 * marks never go through it, so only the method that isn't final is wanted.
 */
internal object ChatRequestSendFingerprint : Fingerprint(
    returnType = "J",
    custom = { method, _ ->
        val types = method.parameterTypes.map { it.toString() }
        types.size == 4 && types[0] == "I" && types[1] == REQUEST_BODY && types[3] == "[Ljava/lang/Object;" &&
            (method.accessFlags and AccessFlags.FINAL.value) == 0
    },
)

/** The same sender, for the handlers that add headers of their own to the request. */
internal object ChatRequestSendWithHeadersFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val types = method.parameterTypes.map { it.toString() }
        types.size == 5 && types[0] == "I" && types[1] == REQUEST_BODY && types[3] == "Ljava/util/Map;" &&
            types[4] == "[Ljava/lang/Object;"
    },
)

@Suppress("unused")
val hideReadReceiptsPatch = bytecodePatch(
    name = "Hide read receipts",
    description = "Stops TikTok telling people you've read their messages. Your own unread badges still " +
        "clear as usual. Starts off. Turn it on in Hushfeed settings > Inbox.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Both senders ask first. A request that marks a chat as read is answered as if it had been sent, so the
        // handler carries on: your phone has already marked the chat read, and the other person is never told.
        ChatRequestSendFingerprint.method.apply {
            // The question writes v0 for its answer, and the answer for a skipped request is a long, which takes v0 and v1.
            requireLocals(HIDE_READ_RECEIPTS_PATCH, 2)
            guardAtEntry(
                HIDE_READ_RECEIPTS_PATCH,
                callThroughLocals(
                    HIDE_READ_RECEIPTS_PATCH,
                    "invoke-static",
                    "$HIDE_READ_RECEIPTS->shouldSkip(Ljava/lang/Object;)Z",
                    objectIn("p2"),
                ),
                """
                    const-wide/16 v0, 0x0
                    return-wide v0
                """,
            )
        }
        ChatRequestSendWithHeadersFingerprint.method.apply {
            requireLocals(HIDE_READ_RECEIPTS_PATCH, 1)
            guardAtEntry(
                HIDE_READ_RECEIPTS_PATCH,
                callThroughLocals(
                    HIDE_READ_RECEIPTS_PATCH,
                    "invoke-static",
                    "$HIDE_READ_RECEIPTS->shouldSkip(Ljava/lang/Object;)Z",
                    objectIn("p2"),
                ),
                "return-void",
            )
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideReadReceipts()V",
        )
    }
}
