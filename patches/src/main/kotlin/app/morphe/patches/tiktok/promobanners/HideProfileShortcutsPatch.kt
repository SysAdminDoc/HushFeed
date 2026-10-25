/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.buildName
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

private const val PROFILE_SHORTCUTS_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/profile/ProfileShortcuts;"
private const val PROFILE_USER_DESCRIPTOR = "Lcom/ss/android/ugc/profile/platform/base/data/ProfileUser;"
private const val USER_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/profile/model/User;"
private const val CONVERSION_TAG = "convert_data_new_2_old"

/**
 * The conversion of your own profile from TikTok's server-driven profile response into the older
 * User model. It runs before the header is built from the same response.
 */
internal object OwnProfileConversionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(PROFILE_USER_DESCRIPTOR),
    strings = listOf(CONVERSION_TAG),
)

/** The same conversion for any profile, yours or someone else's. */
internal object ProfileConversionFingerprint : Fingerprint(
    returnType = USER_DESCRIPTOR,
    parameters = listOf(USER_DESCRIPTOR, PROFILE_USER_DESCRIPTOR),
    strings = listOf(CONVERSION_TAG),
)

/** Hands the method's ProfileUser parameter to the extension before anything reads it. */
private fun MutableMethod.filterProfileUserParameter(parameterIndex: Int) {
    val register = if (AccessFlags.STATIC.isSet(accessFlags)) "p$parameterIndex" else "p${parameterIndex + 1}"
    addInstruction(
        0,
        "invoke-static/range { $register .. $register }, " +
            "$PROFILE_SHORTCUTS_CLASS_DESCRIPTOR->onProfileData($PROFILE_USER_DESCRIPTOR)V",
    )
}

private const val PATCH_NAME = "Hide profile shortcuts"

@Suppress("unused")
val hideProfileShortcutsPatch = bytecodePatch(
    name = "Hide profile shortcuts",
    description = "Hides the shortcuts you pick from the row under a profile's bio, like " +
        "TikTok Studio or Your orders, and can hide the Thoughts bubble, and Balance and the " +
        "TikTok Minis icons in your profile's menu. It can also turn Thoughts on for accounts " +
        "TikTok left out. Starts off. Turn it on in Hushfeed settings > App, then restart TikTok.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableProfileShortcuts()V",
        )
        // The Thoughts bubble (#122) is required on a declared build, where
        // ProfileThoughtsAnchorsTest holds both of its hooks, and left out with a note on any
        // other, so a renamed bubble does not take the shortcut filter down with it. Everything
        // is found before anything is written, so a miss leaves neither half applied.
        val thoughts = try {
            val avatar = ProfileAvatarViewCreatedFingerprint.method
            val store = avatar.thoughtBubbleStore(PATCH_NAME)
            val visibility = ThoughtVisibilityCallbackFingerprint.method.also { it.requireLocals(PATCH_NAME, 1) }
            Triple(avatar, store, visibility)
        } catch (problem: Exception) {
            if (packageMetadata.buildName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Hide thoughts on profiles on ${packageMetadata.buildName}: ${problem.message}")
            null
        }
        // Balance in your profile's menu (#194), held the same way by ProfileMenuCellsAnchorsTest.
        val menuCells = try {
            ProfileMenuCellFingerprint.method.also { it.requireLocals(PATCH_NAME, 1) }
        } catch (problem: Exception) {
            if (packageMetadata.buildName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Hide Balance in the profile menu on ${packageMetadata.buildName}: ${problem.message}")
            null
        }
        // Turn on Thoughts (#186), the other way round from hiding them: TikTok's two Thoughts
        // gates answer yes behind a switch that starts off. Held by SwitchedReturnFixturesTest.
        val thoughtsGates = try {
            resolveThoughtsGates()
        } catch (problem: Exception) {
            if (packageMetadata.buildName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Turn on Thoughts on ${packageMetadata.buildName}: ${problem.message}")
            null
        }
        OwnProfileConversionFingerprint.method.filterProfileUserParameter(0)
        ProfileConversionFingerprint.method.filterProfileUserParameter(1)

        if (thoughts != null) {
            val (avatar, store, visibility) = thoughts
            avatar.hideThoughtBubble(store)
            visibility.keepThoughtSpaceClosed(PATCH_NAME)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableProfileThoughts()V",
            )
        }
        if (menuCells != null) {
            menuCells.hideProfileMenuCells(PATCH_NAME)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableProfileMenuCells()V",
            )
        }
        if (thoughtsGates != null) {
            thoughtsGates.installThoughtsSwitch()
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableThoughtsSwitch()V",
            )
        }
    }
}
