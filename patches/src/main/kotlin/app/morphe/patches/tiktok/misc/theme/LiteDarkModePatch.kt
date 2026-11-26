/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val LITE_DARK_MODE_HOOK = "Lapp/morphe/extension/tiktok/theme/LiteDarkMode;->mode(I)I"

/**
 * TikTok Lite's saved dark mode (#543), `LX/K6R;->L()I` on 47.8.2: the static int getter that
 * reads "dark_mode" from the "repo_dark_mode" Keva repo, 1 (light) when nothing is saved. Its
 * readers are the whole of Lite's dark theme: the activity processor
 * `UltraLiteActivityViewModel` adds (`LX/QgT;`) hands it to AppCompat's default night mode at
 * the first screen and puts TikTok's dark TUX theme on every screen whose isDarkModeSupported
 * says yes (BaseActivity's answer, which the home screen keeps), and the Spark pages, the
 * configuration change and the hybrid pages' dark check read it too. The class's other
 * "dark_mode" readers take a Context or return a boolean.
 */
internal object LiteDarkModeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf(),
    strings = listOf("dark_mode"),
)

/**
 * Sends every value [this] getter returns through the extension, which answers dark while the
 * switch is on. Returns how many returns it hooked; a getter with none fails the patch.
 */
internal fun MutableMethod.hookLiteDarkMode(): Int {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index }
    if (returns.isEmpty()) throw PatchException("TikTok Lite's dark mode getter has no int return to hook.")
    // From the last, so each index still names its return when it is hooked.
    for (index in returns.reversed()) {
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(index, """
            invoke-static/range { v$register .. v$register }, $LITE_DARK_MODE_HOOK
            move-result v$register
        """)
    }
    return returns.size
}

@Suppress("unused")
val liteDarkModePatch = bytecodePatch(
    name = "Dark mode",
    description = "Turns on the dark theme TikTok Lite carries but never lets you pick. Starts off. " +
        "Turn it on in Hushfeed settings > App, then restart TikTok Lite.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(AppCompatibilities.tiktokLite())

    execute {
        val hooked = LiteDarkModeFingerprint.method.hookLiteDarkMode()
        registerForBuildDetails("enableLiteDarkMode")
        println("[Dark mode] Hooked $hooked return(s) of TikTok Lite's saved dark mode.")
    }
}
