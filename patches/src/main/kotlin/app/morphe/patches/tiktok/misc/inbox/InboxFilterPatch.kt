/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private object InboxRowBindingFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/Object;"),
    strings = listOf("MultiBaseVH innerOnBind data type is not match!"),
)

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/inbox/InboxFilter;"

private const val PATCH_NAME = "Hide inbox items"

internal object MainActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/main/MainActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** Both Inbox patches need the live layout observer, but a combined selection injects it once. */
internal fun MutableMethod.installInboxLayoutFilter() {
    val alreadyInstalled = implementation!!.instructions.any { instruction ->
        instruction.getReference<MethodReference>()?.let { reference ->
            reference.definingClass == EXTENSION_CLASS_DESCRIPTOR &&
                reference.name == "install" &&
                reference.parameterTypes.map(CharSequence::toString) ==
                listOf("Landroid/app/Activity;") &&
                reference.returnType == "V"
        } == true
    }
    if (alreadyInstalled) return

    // p0 is the activity. Uses invoke-static/range because a parameter register is
    // usually above v15, which the plain invoke-static cannot encode.
    addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, " +
            "$EXTENSION_CLASS_DESCRIPTOR->install(Landroid/app/Activity;)V",
    )
}

@Suppress("unused")
val inboxFilterPatch = bytecodePatch(
    name = "Hide inbox items",
    description = "Lets you hide Inbox rows you don't use, like message requests, TikTok Shop, " +
        "the stories row and the On this day memory in it, plus call buttons and suggested replies in chats. Each has its own " +
        "switch. Also offers search in loaded Activity notifications and TikTok's Main, Requests, " +
        "Unread and Starred tabs on any account, a way to rebuild message search and room to pin " +
        "more than 5 chats. Starts off. " +
        "Turn it on in Hushfeed settings > Inbox.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Found and checked before anything is written: a patch that fails part way keeps
        // what it already wrote.
        val gestures = ChatMessageGestureFingerprint.method
        val gestureRegister = chatGestureRegister(gestures)
        val notificationBinds = listOf(InboxNotificationNormalBind.method, InboxNotificationPayloadBind.method)
        val notificationVm = InboxNotificationVmGetter.method
        val loadedPublication = loadedInboxPublicationSeams()
        val loadedRequests = loadedInboxRequestSeams()
        // The group chat banner's update is required on a declared build, where
        // InboxGroupBannerAnchorsTest holds it, and left out with a note on any other.
        val groupBanner = try {
            InboxGroupBannerUpdateFingerprint.method.also { it.requireLocals(PATCH_NAME, 1) }
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out the group chat prompt switch on ${packageMetadata.versionName}: ${problem.message}")
            null
        }
        // The On this day cell in the stories tray, held the same way by InboxOnThisDayAnchorsTest.
        val onThisDay = try {
            StoryOnThisDayBindFingerprint.method
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out the On this day switch on ${packageMetadata.versionName}: ${problem.message}")
            null
        }
        // Show inbox filters, held the same way by InboxFilterTabsAnchorsTest.
        val filterTabs = try {
            inboxFilterTabHooks()
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out the inbox filters switch on ${packageMetadata.versionName}: ${problem.message}")
            null
        }
        // Rebuild message search, held the same way by MessageSearchRebuildAnchorsTest.
        val searchRebuild = try {
            messageSearchBridges()
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Rebuild message search on ${packageMetadata.versionName}: ${problem.message}")
            null
        }
        // Pin more chats, held the same way by PinMoreChatsAnchorsTest.
        val pinLimit = try {
            PinChatLimitFingerprint.method
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Pin more chats on ${packageMetadata.versionName}: ${problem.message}")
            null
        }
        val binding = InboxRowBindingFingerprint.method
        check(binding.implementation!!.instructions.any { instruction ->
            instruction.getReference<FieldReference>()?.let {
                it.name == "itemView" && it.type == "Landroid/view/View;" &&
                    it.definingClass == "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"
            } == true
        }) {
            "Inbox filter: ${binding.name} does not read the ViewHolder's itemView, so it is not " +
                "the row bind this hooks."
        }
        notificationBinds.forEach { it.captureLoadedInboxOwner() }
        notificationVm.captureLoadedInboxVm()
        loadedPublication.install()
        loadedRequests.install()
        binding.addInstruction(0,
            "invoke-static/range {p0 .. p2}, $EXTENSION_CLASS_DESCRIPTOR->onRowBound(Ljava/lang/Object;ILjava/lang/Object;)V")
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableInboxFilter()V",
        )

        MainActivityOnCreateFingerprint.method.installInboxLayoutFilter()

        // The chat screen's switches. Each hook passes TikTok's own answer through while its
        // switch is off, and a build missing an anchor stops the patch here.
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableChatDeclutter()V",
        )
        ChatTitleBarRightBindFingerprint.method.hideCallButtonsAtBind()
        ChatStickerBannerEnabledFingerprint.method.hideInboxWidget("shouldShowChatStickerBanner")
        ChatSuggestedReplyEnabledFingerprint.method.hideInboxWidget("shouldShowChatAiReplies")
        ChatSmartReplyIntroEnabledFingerprint.method.hideInboxWidget("shouldShowChatAiReplies")
        gestures.skipChatGestures(gestureRegister)

        // The group chat prompt at the top of the Inbox. Returning before the update leaves the
        // banner at the INIT state it is built with, which shows nothing.
        if (groupBanner != null) {
            groupBanner.hideGroupChatBanner(PATCH_NAME)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableGroupChatBanner()V",
            )
        }

        // The On this day cell at the front of the stories tray (#358).
        if (onThisDay != null) {
            onThisDay.reportOnThisDayBind(PATCH_NAME)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableInboxOnThisDay()V",
            )
        }

        // TikTok's own filter tabs above the chat list, which it gives creator and business
        // accounts. The Inbox is told the creator mode while the stored mode stays personal.
        if (filterTabs != null) {
            filterTabs.install(PATCH_NAME)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableInboxFilterTabs()V",
            )
        }

        // The settings action that clears TikTok's message search and has its own catch-up
        // index every saved message again with no search page open.
        if (searchRebuild != null) {
            installMessageSearchRebuild(searchRebuild)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableMessageSearchRebuild()V",
            )
        }

        // The cap on pinned chats (#385). TikTok checks it on the phone before any pin reaches
        // its server, and the server setting it reads defaults to 5.
        if (pinLimit != null) {
            pinLimit.liftPinChatLimit(PATCH_NAME)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePinMoreChats()V",
            )
        }
    }
}
