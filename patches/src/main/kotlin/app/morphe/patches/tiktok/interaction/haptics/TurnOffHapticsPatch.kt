/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.haptics

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.HostApplicationAttachBaseContextFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.patches.tiktok.privacy.replaceSites
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val HAPTICS = "Lapp/morphe/extension/tiktok/interaction/Haptics;"
private const val VIEW = "Landroid/view/View;"
private const val VIBRATOR = "Landroid/os/Vibrator;"
private const val EFFECT = "Landroid/os/VibrationEffect;"
private const val AUDIO_ATTRIBUTES = "Landroid/media/AudioAttributes;"

/*
 * TikTok 47.1.4 names View or Vibrator itself in every one of these calls, never a subclass:
 * 54 performHapticFeedback(int) and 6 performHapticFeedback(int, int), 56 timed vibrate(long), 25
 * vibrate(VibrationEffect) and 3 vibrate(VibrationEffect, AudioAttributes). Each becomes a static
 * call of the extension on the same registers, so a move-result after it reads the extension's
 * answer. The 4 patterned vibrate(long[], int) calls stay: that's an alert's shape, not a tap's.
 */
internal val VIEW_HAPTICS = mapOf(
    "$VIEW->performHapticFeedback(I)Z" to "$HAPTICS->performHapticFeedback(${VIEW}I)Z",
    "$VIEW->performHapticFeedback(II)Z" to "$HAPTICS->performHapticFeedback(${VIEW}II)Z",
)

internal val VIBRATIONS = mapOf(
    "$VIBRATOR->vibrate(J)V" to "$HAPTICS->vibrate(${VIBRATOR}J)V",
    "$VIBRATOR->vibrate($EFFECT)V" to "$HAPTICS->vibrate($VIBRATOR$EFFECT)V",
    "$VIBRATOR->vibrate($EFFECT$AUDIO_ATTRIBUTES)V" to "$HAPTICS->vibrate($VIBRATOR$EFFECT$AUDIO_ATTRIBUTES)V",
)

/**
 * Where the extension starts following TikTok's windows: just past the host application's call
 * to the framework's attachBaseContext, before any activity exists, as App lock does. Android
 * plays the long press buzz itself once a long click listener answers true, in framework code
 * no call site reaches. Wrapping each listener where TikTok sets it took 443 rewrites in 281
 * classes on 47.7.3, nearly all of them classes nothing else touches, and each one patched is
 * held in memory to the end (#339). The windows see the finger instead.
 */
internal fun hapticsInstallIndex(method: Method): Int {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("Turn off haptics: the host application's attachBaseContext is static, so it has no application to follow.")
    }
    val superCall = method.indexOfFirstInstruction {
        getReference<MethodReference>()?.let { reference ->
            reference.definingClass == "Landroid/app/Application;" &&
                reference.name == "attachBaseContext" &&
                reference.parameterTypes == listOf("Landroid/content/Context;")
        } == true
    }
    if (superCall < 0) {
        throw PatchException("Turn off haptics: the host application no longer calls Application.attachBaseContext, so there is nowhere to start from.")
    }
    return superCall + 1
}

/**
 * Holds back the vibrations TikTok plays on its own taps and gestures. See the extension's
 * Haptics for when.
 *
 * In the default selection with its switch off: TikTok's haptics are a matter of taste, not
 * something it does to you, so nothing changes until the user asks.
 */
@Suppress("unused")
val turnOffHapticsPatch = bytecodePatch(
    name = "Turn off haptics",
    description = "Stops the little vibrations TikTok makes when you tap or hold things. Your " +
        "keyboard and your phone's own vibrations stay. Starts off. Turn it on in Hushfeed " +
        "settings > App.",
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableTurnOffHaptics()V",
        )
        // A range invoke, so the parameter register's number never has to fit a 4-bit operand.
        HostApplicationAttachBaseContextFingerprint.method.apply {
            addInstruction(
                hapticsInstallIndex(this),
                "invoke-static/range { p0 .. p0 }, $HAPTICS->install(Landroid/content/Context;)V",
            )
        }
        val replacements = VIEW_HAPTICS + VIBRATIONS
        val sites = invokeSitesOf(replacements.keys)
        if (sites.none { it.target in VIEW_HAPTICS }) {
            throw PatchException("Turn off haptics: found no View haptic call outside the extension.")
        }
        if (sites.none { it.target in VIBRATIONS }) {
            throw PatchException("Turn off haptics: found no vibrator call outside the extension.")
        }
        replaceSites(sites, replacements)
    }
}
