/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/377d4e15016296b45d809697f7f69bce74badd3a/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/settings/EnableOpenDebugPatch.kt
 * (the revision De-Vanced cites; ReVanced's main has since dropped the file)
 *
 * TikTok 43.6.2: ReVanced MR !6535. OpenDebug hooks use heavy obfuscation; names differ by APK variant.
 * State class: many short LX/… UI states reuse obfuscated field names like LLILLL—selection is driven by the
 * unique Jetpack compose `LIZ` method that calls Context.getString(I) and reads LLILL (OpenDebug row).
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.util.addInstruction
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CRASH_CAPTURE_INITIALIZER =
    "Lapp/morphe/extension/tiktok/diagnostics/JavaCrashCapture;->initialize(Landroid/content/Context;)V"

/** How the app starts its crash reporter, which TikTok Lite does from its own start-up code. */
internal const val NPTH_INIT =
    "Lcom/bytedance/crash/Npth;->init(Landroid/content/Context;Lcom/bytedance/crash/ICommonParams;ZZZ)V"

/**
 * The parameter register holding the Context of an app method that starts the crash reporter,
 * for crash capture to go in on that method's way out. Null when [method] doesn't start it, is
 * the reporter's own code, returns a value or has no Context to hand over.
 */
internal fun crashReporterStartContext(classDef: ClassDef, method: Method): String? {
    if (classDef.type.startsWith("Lcom/bytedance/crash/") || method.returnType != "V") return null
    val instructions = method.implementation?.instructions ?: return null
    val starts = instructions.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == NPTH_INIT
    }
    if (!starts) return null
    val parameters = method.parameterTypes.map { it.toString() }
    val at = parameters.indexOf("Landroid/content/Context;")
    if (at < 0) return null
    val static = method.accessFlags and AccessFlags.STATIC.value != 0
    return "p" + ((if (static) 0 else 1) + parameters.take(at).sumOf { if (it == "J" || it == "D") 2 else 1 })
}

@Suppress("unused")
val enableOpenDebugPatch = bytecodePatch(
    name = "Diagnostic tools",
    description = "Adds logs and reports you can save or share when something goes wrong, so " +
        "a problem is easier to report and fix. Starts off. Turn it on in Hushfeed settings > " +
        "Diagnostics.",
) {
    category("Settings")
    dependsOn(sharedExtensionPatch, settingsPatch)

    compatibleWith(*AppCompatibilities.tiktokAndLite())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDiagnostics()V",
        )

        if (packageMetadata.packageName == AppCompatibilities.TIKTOK_LITE_PACKAGE) {
            // Lite (#48) has no NpthExtentTask. It starts the reporter from a start-up task of its
            // own and again from its mini mode's, so capture goes in on the way out of each. The
            // first to finish installs it, and the rest find it already there.
            val starts = invokeSitesOf(setOf(NPTH_INIT), static = true).map { it.owner to it.method }
                .distinctBy { (owner, method) -> owner.type to method.toString() }
                .mapNotNull { (owner, method) ->
                    crashReporterStartContext(owner, method)?.let { context -> Triple(owner, method, context) }
                }
            if (starts.isEmpty()) {
                throw PatchException("Diagnostics: nothing in TikTok Lite starts its crash reporter with a Context to hand on.")
            }
            starts.forEach { (owner, method, context) ->
                val start = mutableClassDefBy(owner).findMutableMethodOf(method)
                start.findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_VOID }.forEach { index ->
                    start.addInstructionsAtControlFlowLabel(
                        index,
                        "invoke-static/range { $context .. $context }, $CRASH_CAPTURE_INITIALIZER",
                    )
                }
            }
            return@execute
        }

        val npthExtent = NpthExtentTaskInitFingerprint.method
        // The fingerprint holds the context to the first and only parameter, so it is p0 on a
        // static method and p1 behind `this` on an instance one. Both are named here rather
        // than assumed: a frame past v15 cannot be reached by the plain invoke below.
        val contextRegister =
            if (npthExtent.accessFlags and AccessFlags.STATIC.value != 0) "p0" else "p1"
        val contextIndex = npthExtent.implementation!!.registerCount -
            npthExtent.numberOfParameterRegisters +
            (if (npthExtent.accessFlags and AccessFlags.STATIC.value != 0) 0 else 1)
        check(contextIndex <= 15) {
            "Diagnostics: NpthExtentTask.LIZ keeps its context in v$contextIndex, past what " +
                "the plain invoke can name."
        }
        val returnIndices = npthExtent.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }

        returnIndices.asReversed().forEach { returnIndex ->
            npthExtent.addInstructionsAtControlFlowLabel(
                returnIndex,
                "invoke-static {$contextRegister}, $CRASH_CAPTURE_INITIALIZER",
            )
        }
    }
}
