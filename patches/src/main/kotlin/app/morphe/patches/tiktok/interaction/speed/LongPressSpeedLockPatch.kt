/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.patcher.patch.packageMetadata
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.moveResultRegisterAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FEATURE_CONTROLS_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private const val HOLD_PULL_GUARD = "Lapp/morphe/extension/tiktok/speed/HoldPullGuard;"

@Suppress("unused")
val longPressSpeedLockPatch = bytecodePatch(
    name = "Hold-and-slide 2x lock",
    description = "Turns on TikTok's own gesture where you hold a video, slide down and let " +
        "go to keep it playing fast without holding. Starts off. Turn it on in Hushfeed settings " +
        "> Feed screen.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLongPressSpeedLock()V",
        )

        LongPressSpeedUpEnableFingerprint.method.let { method ->
            val lookupIndex = method.indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes == listOf("I", "Ljava/lang/String;", "Z", "Z") &&
                        reference.returnType == "Z"
                } == true
            }
            val resultRegister = method.moveResultRegisterAfter(lookupIndex, "Hold-and-slide 2x lock")
            method.addInstructions(
                lookupIndex + 2,
                """
                    invoke-static/range {v$resultRegister .. v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->overrideLongPressSpeedUpEnabled(Z)Z
                    move-result v$resultRegister
                """,
            )
        }

        LongPressSpeedUpLockFingerprint.method.let { method ->
            val lookupIndex = method.indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes == listOf("I", "I", "Ljava/lang/String;", "Z") &&
                        reference.returnType == "I"
                } == true
            }
            val resultRegister = method.moveResultRegisterAfter(lookupIndex, "Hold-and-slide 2x lock")
            method.addInstructions(
                lookupIndex + 2,
                """
                    invoke-static/range {v$resultRegister .. v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->overrideLongPressSpeedUpLockDistance(I)I
                    move-result v$resultRegister
                """,
            )
        }

        // TikTok's hold listener never asks the feed to leave a held finger alone, so the pull
        // scrolled the feed or refreshed For You and cancelled the hold before the lock armed
        // (#422). The listener hands over its view on the way down, and once the speed-up starts
        // that view asks its parents to keep out of the rest of the touch.
        try {
            val touch = EdgeSpeedupTouchFingerprint.method
            check(!AccessFlags.STATIC.isSet(touch.accessFlags)) {
                "Hold-and-slide 2x lock: the hold's onTouch became static, so p1 and p2 are not the view and event."
            }
            val press = EdgeSpeedupPressFingerprint.method
            val started = press.holdGuardEnd("the hold gesture's press")
            touch.addInstruction(
                0,
                "invoke-static/range {p1 .. p2}, $HOLD_PULL_GUARD->onHoldTouch(Landroid/view/View;Landroid/view/MotionEvent;)V",
            )
            press.addInstruction(started, "invoke-static {}, $HOLD_PULL_GUARD->onSpeedUp()V")
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Hold-and-slide 2x lock] Left out keeping the pull from the feed on ${packageMetadata.versionName}: ${problem.message}")
        }
    }
}
