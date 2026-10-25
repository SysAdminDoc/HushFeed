/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.camerabrightness

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Keep the screen's brightness in the camera"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/CameraBrightness;"
private const val LIFECYCLE_OWNER = "Landroidx/lifecycle/LifecycleOwner;"
private const val LAYOUT_PARAMS = "Landroid/view/WindowManager\$LayoutParams;"
private const val SCREEN_BRIGHTNESS = "screenBrightness"
private const val SCENE = "Lcom/bytedance/scene/Scene;"
private const val OBJECT_ANIMATOR = "Landroid/animation/ObjectAnimator;"
private const val FILL_LIGHT_CONFIG = "Lcom/ss/android/ugc/gamora/recorder/frontflash/config/StudioSelfieFillLightConfig;"
private const val TAKO_RECORD_UI = "Lcom/ss/android/ugc/aweme/tako/camera/record/pageui/TakoRecordUIAssem;"
private const val FULL_BRIGHTNESS = 1.0f

/** The window brightness read that saves TikTok's level before a flash, then the full-brightness store. */
private fun savesThenRaises(): List<InstructionFilter> = listOf(
    fieldAccess(definingClass = LAYOUT_PARAMS, name = SCREEN_BRIGHTNESS, opcode = Opcode.IGET),
    literal(FULL_BRIGHTNESS),
    fieldAccess(definingClass = LAYOUT_PARAMS, name = SCREEN_BRIGHTNESS, opcode = Opcode.IPUT),
)

/**
 * The brightness boost's start on 47.4.3, `BrightnessManager.LIZ(activity, owner, scene)`. The
 * camera, editor, post screen and effect preview call it from onStart. It reads TikTok's server
 * config, then either hands the window to the light sensor or sets it once from the phone's own
 * level plus a step, and watches the brightness slider until the owner stops. The class keeps its
 * name; the method is the only static void one there taking a lifecycle owner second.
 */
internal object BrightnessBoostStartFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/property/BrightnessManager;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.size == 3 && method.parameterTypes[1].toString() == LIFECYCLE_OWNER
    },
)

/**
 * The older start, in an obfuscated class (`LX/1P0U;` on 47.4.3). The same screens call it from
 * onCreate, and it returns straight away when the server flag picks the one above, so only one
 * of the two runs. It names its own server key and writes the window's brightness itself.
 */
internal object OldBrightnessBoostStartFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", LIFECYCLE_OWNER, "L"),
    strings = listOf("creative_tools_opti_max_brightness", "screen_brightness"),
    filters = listOf(fieldAccess(definingClass = LAYOUT_PARAMS, name = SCREEN_BRIGHTNESS, opcode = Opcode.IPUT)),
)

/*
 * The front flash and fill light (47.5.3). Each saves the window's brightness in a field of its
 * own, stores full brightness in the window's layout parameters and hands them back with
 * setAttributes. Turning off puts the saved level back. None of them goes through the boost
 * starts above, so the guards there don't reach them, and a white overlay that lights your face
 * comes up with each one.
 */

/**
 * The fill light's automatic full brightness on the record screen (`LX/1SYY;->LLLLLLZ()V`), made
 * by FrontFlashAdjustmentCoordinator. It runs only while the fill light config allows it.
 */
internal object FillLightFullBrightnessFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(definingClass = FILL_LIGHT_CONFIG, name = "autoAdjustFullBrightness"),
        fieldAccess(definingClass = SCENE, name = "mActivity"),
    ) + savesThenRaises(),
)

/**
 * The record screen's front flash (`LX/1SYd;->LLLLLLL()V`), another scene. Its first instruction
 * reads its own "already raised" flag, which tells it from the fill light above.
 */
internal object FrontFlashSceneFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(fieldAccess(definingClass = SCENE, name = "mActivity")) + savesThenRaises(),
    custom = { method, _ ->
        val first = method.implementation?.instructions?.firstOrNull()
        first != null && first.opcode == Opcode.IGET_BOOLEAN &&
            first.getReference<FieldReference>()?.definingClass == method.definingClass
    },
)

/**
 * The screen flash behind a camera's flash button (`LX/1UoG;->gp(Z)V`), which then fades its
 * white overlay in with an alpha animator.
 */
internal object ScreenFlashOnFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("alpha"),
    filters = savesThenRaises() + methodCall(definingClass = OBJECT_ANIMATOR, name = "ofFloat"),
)

/** The Tako AI assistant's camera flash, turned on from its flash button (`TakoRecordUIAssem.fr()V`). */
internal object TakoFlashOnFingerprint : Fingerprint(
    definingClass = TAKO_RECORD_UI,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = savesThenRaises(),
)

/**
 * The avatar camera's flash (`LX/0dGY;->LIZIZ(...)Ljava/lang/Object;`, a suspend function). It
 * stores the brighter of the phone's brightness setting and its own config value, not a plain
 * 1.0, so it's told by the int-to-float of that setting before the store and its alpha fade.
 */
internal object AvatarFlashOnFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
    strings = listOf("alpha"),
    filters = listOf(
        fieldAccess(definingClass = LAYOUT_PARAMS, name = SCREEN_BRIGHTNESS, opcode = Opcode.IGET),
        opcode(Opcode.INT_TO_FLOAT),
        fieldAccess(definingClass = LAYOUT_PARAMS, name = SCREEN_BRIGHTNESS, opcode = Opcode.IPUT),
        methodCall(definingClass = OBJECT_ANIMATOR, name = "ofFloat"),
    ),
)

internal val flashFingerprints: List<Fingerprint>
    get() = listOf(
        FillLightFullBrightnessFingerprint,
        FrontFlashSceneFingerprint,
        ScreenFlashOnFingerprint,
        TakoFlashOnFingerprint,
        AvatarFlashOnFingerprint,
    )

/**
 * Hands a flash's one store of the window's brightness to the extension, which makes the same
 * store unless the switch is on. The store is replaced, not skipped with a branch, because one of
 * them is a branch target, and a replaced instruction keeps its label. The saved level, the
 * setAttributes call, the white overlay and the restore all stay TikTok's, so turning the flash
 * off writes back the level the window already has. An iput's registers are 4-bit, so the
 * five-register invoke always fits.
 */
internal fun MutableMethod.keepFlashBrightness(patch: String) {
    val stores = implementation!!.instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IPUT && instruction.getReference<FieldReference>()?.let {
            it.definingClass == LAYOUT_PARAMS && it.name == SCREEN_BRIGHTNESS
        } == true
    }
    if (stores.size != 1) {
        throw PatchException("$patch: $definingClass->$name stores the window's brightness ${stores.size} times, not once.")
    }
    val (index, store) = stores.single()
    store as TwoRegisterInstruction
    replaceInstruction(
        index,
        "invoke-static { v${store.registerB}, v${store.registerA} }, " +
            "$EXTENSION->setFlashBrightness(${LAYOUT_PARAMS}F)V",
    )
}

@Suppress("unused")
val cameraBrightnessPatch = bytecodePatch(
    name = "Keep the screen's brightness in the camera",
    description = "Stops TikTok from turning your screen brighter while you film, edit or " +
        "post a video. Starts off. Turn it on in Hushfeed settings > App.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)

    // PineDrama has no camera, and no start or flash is in it.
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val ask = "invoke-static {}, $EXTENSION->keepScreenBrightness()Z"
        // A plain skip, so the window keeps whatever brightness it had. Skipped, the start also
        // registers no light sensor and no stop observer, so nothing writes it later either.
        BrightnessBoostStartFingerprint.method.guardAtEntry(PATCH, ask, "return-void")
        OldBrightnessBoostStartFingerprint.method.guardAtEntry(PATCH, ask, "return-void")
        // The front flash and fill light write full brightness on their own (#200: "extreme"
        // brightness as the camera opens). Only their store is handed over; the flash itself stays.
        flashFingerprints.forEach { it.method.keepFlashBrightness(PATCH) }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCameraBrightness()V",
        )
    }
}
