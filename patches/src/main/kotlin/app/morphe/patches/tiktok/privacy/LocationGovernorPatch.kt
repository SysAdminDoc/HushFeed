/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/LocationGovernor;"
private const val MANAGER = "Landroid/location/LocationManager;"
private const val LISTENER = "Landroid/location/LocationListener;"

@Suppress("unused")
val locationGovernorPatch = bytecodePatch(
    name = "Location access governor",
    description = "Gives TikTok no location when it asks, so it can't see where you are from " +
        "your phone's location services. Starts off. Turn it on in Hushfeed settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLocationGovernor()V",
        )

        // The three LocationManager entry points TikTok 46.2.3 reaches, and the two plain
        // requestLocationUpdates overloads a later build is most likely to pick up.
        val replacements = mapOf(
            "$MANAGER->getLastKnownLocation(Ljava/lang/String;)Landroid/location/Location;" to
                "$EXTENSION->interceptGetLastKnownLocation(${MANAGER}Ljava/lang/String;)Landroid/location/Location;",
            "$MANAGER->requestSingleUpdate(Ljava/lang/String;${LISTENER}Landroid/os/Looper;)V" to
                "$EXTENSION->interceptRequestSingleUpdate(${MANAGER}Ljava/lang/String;${LISTENER}Landroid/os/Looper;)V",
            "$MANAGER->requestLocationUpdates(JFLandroid/location/Criteria;${LISTENER}Landroid/os/Looper;)V" to
                "$EXTENSION->interceptRequestLocationUpdates(${MANAGER}JFLandroid/location/Criteria;${LISTENER}Landroid/os/Looper;)V",
            "$MANAGER->requestLocationUpdates(Ljava/lang/String;JF$LISTENER)V" to
                "$EXTENSION->interceptRequestLocationUpdates(${MANAGER}Ljava/lang/String;JF$LISTENER)V",
            "$MANAGER->requestLocationUpdates(Ljava/lang/String;JF${LISTENER}Landroid/os/Looper;)V" to
                "$EXTENSION->interceptRequestLocationUpdates(${MANAGER}Ljava/lang/String;JF${LISTENER}Landroid/os/Looper;)V",
        )
        val sites = invokeSitesOf(replacements.keys)
        if (sites.isEmpty()) {
            throw PatchException("Location access governor: no LocationManager call site was found.")
        }
        replaceSites(sites, replacements)
        println("[Location governor] Intercepted ${sites.size} location access sites.")

        // TikTok's own location API (#379), which places the phone by its address and never
        // goes through LocationManager. Every load of its host goes through the extension.
        val hosts = LocationApiHostFingerprint.matchAllOrNull().orEmpty()
        hosts.forEach { it.method.redirectLocationApiHost() }
        println("[Location governor] Redirected the location API host in ${hosts.size} methods.")
    }
}

/** Puts the extension's URL choice between each load of the location API host and its use. */
internal fun MutableMethod.redirectLocationApiHost() {
    implementation!!.instructions.withIndex()
        .filter { it.value.getReference<StringReference>()?.string == LOCATION_API_HOST }
        .map { it.index }
        .reversed()
        .forEach { load ->
            val register = getInstruction<OneRegisterInstruction>(load).registerA
            addInstructions(
                load + 1,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION->locationApiUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """,
            )
        }
}
