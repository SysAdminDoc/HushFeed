package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #379: location-v6.tiktokv.us was still asked with Block location on. TikTok's own
 * location API never goes through LocationManager; it is built on location.tiktokv.com, which a
 * backend caller turns into its v6 URL. Every literal naming a location.tiktokv host has to be
 * that exact host, loaded in a method the redirect's fingerprint takes, or a request would still
 * leave the phone.
 */
class LocationApiAnchorsTest {
    @Test
    fun `each declared build names TikTok's location API host only where the redirect takes it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val named = mutableListOf<String>()
            val missed = mutableListOf<String>()
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    classDef.methods.forEach { method ->
                        val hosts = method.implementation?.instructions?.mapNotNull {
                            (it as? ReferenceInstruction)
                                ?.reference as? StringReference
                        }.orEmpty().map { it.string }.filter { "location.tiktokv" in it || "location-v6" in it }
                        if (hosts.isEmpty()) return@forEach
                        named += "${classDef.type}->${method.name} $hosts"
                        if (hosts.any { it != LOCATION_API_HOST } || !LocationApiHostFingerprint.takes(method, classDef)) {
                            missed += "${classDef.type}->${method.name} $hosts"
                        }
                    }
                }
            }
            assertTrue("$version: no load of $LOCATION_API_HOST", named.isNotEmpty())
            assertEquals("$version: location hosts the redirect would not catch", emptyList<String>(), missed)
        }
    }
}
