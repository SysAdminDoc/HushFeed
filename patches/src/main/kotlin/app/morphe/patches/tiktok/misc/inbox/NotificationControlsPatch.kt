/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.util.addInstruction
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.optimizer.InitPushTaskFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.patches.tiktok.privacy.replaceSites
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/inbox/NotificationControls;"
private const val PUSH_SHUTOFF = "Lapp/morphe/extension/tiktok/inbox/PushShutoff;"
private const val POWER_MANAGER = "Landroid/os/PowerManager;"
private const val WAKE_LOCK = "Landroid/os/PowerManager\$WakeLock;"

/**
 * Every wake lock call Turn off push notifications stands in front of, by the framework's own
 * descriptor, each to a PushShutoff static taking the receiver first. PowerManager and WakeLock
 * are final, so no call names a subclass. 47.0.3 makes 46 of these calls (FCM, TikTok's early
 * push lock, the push handler lighting the screen, WorkManager, JobIntentService, app
 * measurement and LIVE). On 47.1.3 and 47.1.4 R8 moved every one of them into X.00kn's API
 * outlines (le, me, ne and oe), apart from app measurement's own acquire, so those builds have
 * five. newWakeLock is there for the tag, which is how the extension tells a kept lock from
 * the rest, and release because a skipped acquire would make a reference counted release throw.
 */
internal val WAKE_LOCK_CALLS = mapOf(
    "$POWER_MANAGER->newWakeLock(ILjava/lang/String;)$WAKE_LOCK" to
        "$PUSH_SHUTOFF->newWakeLock(${POWER_MANAGER}ILjava/lang/String;)$WAKE_LOCK",
    "$WAKE_LOCK->acquire()V" to "$PUSH_SHUTOFF->acquire($WAKE_LOCK)V",
    "$WAKE_LOCK->acquire(J)V" to "$PUSH_SHUTOFF->acquire(${WAKE_LOCK}J)V",
    "$WAKE_LOCK->release()V" to "$PUSH_SHUTOFF->release($WAKE_LOCK)V",
    "$WAKE_LOCK->release(I)V" to "$PUSH_SHUTOFF->release(${WAKE_LOCK}I)V",
)

/**
 * The one method on the push handler that hands a built notification to Android. The class
 * kept its name; the method did not, so it is found by the call it makes. Nothing else on
 * MessageShowHandler calls notify, and its parameters are checked below before the message
 * is read out of p1. Block suggested video notifications reroutes that call through its filter,
 * and either patch can run first, so the rerouted call counts too.
 */
internal object PushNotifyFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/awemepushlib/manager/MessageShowHandler;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    filters = listOf(
        anyInstruction(
            methodCall(
                name = "notify",
                definingClass = "Landroid/app/NotificationManager;",
            ),
            methodCall(
                name = "notify",
                definingClass = "Lapp/morphe/extension/tiktok/inbox/SuggestedVideoPushBlock;",
            ),
        ),
    ),
)

/**
 * TikTok's answer to whether a scene shows the streak button: the chat page, the share panel,
 * the Friends tab, stories and the two in-app message alerts each ask it with their own scene,
 * and the chat list asks it for the reminder flag its cells carry. It is the only reader of the
 * server's `enableStreakBtn`, so that read finds it. This replaced a guard on the cell model's
 * `getHasStreakReminderInlineMsg`, which nothing calls on 47.5.3 or 47.6.3.
 */
internal object StreakButtonFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/im/streak/impl/StreakService;",
    returnType = "Z",
    filters = listOf(
        fieldAccess(
            definingClass = "Lcom/ss/android/ugc/aweme/im/common/experiment/DMStreakConfig;",
            name = "enableStreakBtn",
        ),
    ),
)

/**
 * StreakInnerPushHelper's insertRemindInAppPush: the streak alert that slides in over the app.
 * It logs its own name, which no other method holds.
 */
internal object StreakInAppPushFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/Map;"),
    strings = listOf("StreakInnerPushHelper start to insertRemindInAppPush, convId="),
)

/**
 * The streak helper's one void method taking a conversation id that reads the server's
 * `enableInlineMsg`: it queues the streak message TikTok inserts into the chat.
 */
internal object StreakInlineMessageFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(
            definingClass = "Lcom/ss/android/ugc/aweme/im/common/experiment/DMStreakConfig;",
            name = "enableInlineMsg",
        ),
    ),
)

@Suppress("unused")
val notificationControlsPatch = bytecodePatch(
    name = "Notification controls",
    description = "Lets you turn off new follower notifications and streak reminders, which " +
        "TikTok has no switch for, or turn off TikTok's notifications altogether. Starts off. " +
        "Turn it on in Hushfeed settings > Inbox.",
) {
    category("Inbox")
    // The notify filter carries the drawer half of Turn off push notifications.
    dependsOn(settingsPatch, sharedExtensionPatch, notificationFilterPatch, pushDeliveryDiagnosticsPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        PushNotifyFingerprint.method.apply {
            // The method is static, so p1 is the second parameter. That is the push message,
            // whose own type is obfuscated and cannot be named here, so the parameters around
            // it stand in for its name: a Context first and the built Notification fifth. p1
            // itself only has to be an object for the call to pass it as one.
            check(
                parameterTypes.size >= 5 &&
                    parameterTypes[0] == "Landroid/content/Context;" &&
                    parameterTypes[1].startsWith("L") &&
                    parameterTypes[4] == "Landroid/app/Notification;",
            ) {
                "Notification controls: the push handler no longer takes the message in p1."
            }
            guardAtEntry(
                "Notification controls",
                "invoke-static/range { p1 .. p1 }, $EXTENSION->shouldDropPush(Ljava/lang/Object;)Z",
                "return-void",
            )
        }

        StreakButtonFingerprint.method.guardAtEntry(
            "Notification controls",
            "invoke-static {}, $EXTENSION->hideMessageStreaks()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        StreakInAppPushFingerprint.method.guardAtEntry(
            "Notification controls",
            "invoke-static {}, $EXTENSION->hideStreakPopups()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        StreakInlineMessageFingerprint.method.guardAtEntry(
            "Notification controls",
            "invoke-static {}, $EXTENSION->hideStreakPopups()Z",
            "return-void",
        )

        // Turn off push notifications. TikTok's push setup is one startup task, so a guard at its
        // entry keeps the push service from starting for a launch with the switch on, and the
        // first launch after it goes off sets push up as usual. Limit background traffic's Skip
        // push setup option puts a return at the start of the same method at patch time, and
        // with both in, whichever comes first ends it.
        InitPushTaskFingerprint.method.guardAtEntry(
            "Notification controls",
            "invoke-static {}, $PUSH_SHUTOFF->skipPushSetup()Z",
            "return-void",
        )

        val wakeLockSites = invokeSitesOf(WAKE_LOCK_CALLS.keys)
        for (call in listOf("$POWER_MANAGER->newWakeLock(", "$WAKE_LOCK->acquire(", "$WAKE_LOCK->release(")) {
            if (wakeLockSites.none { it.target.startsWith(call) }) {
                throw PatchException("Notification controls: found no ${call.substringAfter("->")}) call outside the extension.")
            }
        }
        replaceSites(wakeLockSites, WAKE_LOCK_CALLS)
        println("[Notification controls] Took ${wakeLockSites.size} wake lock calls.")

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableNotificationControls()V",
        )
    }
}
