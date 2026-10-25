package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.interaction.authorregion.showAuthorRegionPatch
import app.morphe.patches.tiktok.misc.spoof.sim.simSpoofPatch
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DefaultSettingsTest {
    @Test
    fun `every declared configurable patch exposes its typed defaults`() {
        val catalog = defaultSettingsCatalog
        val settings = catalog.getAsJsonObject("settings")
        assertTrue(settings.size() > 400)
        catalog.getAsJsonObject("patches").entrySet().forEach { (name, keys) ->
            val patch = bytecodePatch(name = name)
            keys.asJsonArray.forEach { entry ->
                val key = entry.asString
                val definition = settings.getAsJsonObject(key)
                val option = patch.options[key]
                assertEquals("$name: $key", definition["default"], when (val value = option.value) {
                    is Boolean -> com.google.gson.JsonPrimitive(value)
                    is Number -> com.google.gson.JsonPrimitive(value)
                    is String -> com.google.gson.JsonPrimitive(value)
                    else -> fail("Missing typed value: $name: $key")
                })
                assertTrue("$name: $key", validDefault(definition, option.value!!))
            }
        }
    }

    @Test
    fun `real patch declarations include the country and flag options`() {
        assertEquals(false, simSpoofPatch.options["simspoof"].default)
        assertEquals("us", simSpoofPatch.options["simspoof_iso"].default)
        assertEquals(Boolean::class, simSpoofPatch.options["simspoof"].type.classifier)
        assertEquals(String::class, simSpoofPatch.options["simspoof_iso"].type.classifier)
        assertEquals(false, showAuthorRegionPatch.options["region_as_flag"].default)
    }

    @Test
    fun `numeric ranges and country options refuse invalid defaults`() {
        val patch = bytecodePatch(name = "Foldable split comment view")
        assertThrows(Exception::class.java) { patch.options["foldable_split_view_min_width_dp"] = 0 }
        patch.options["foldable_split_view_min_width_dp"] = 800
        val sim = bytecodePatch(name = "SIM spoof")
        assertThrows(Exception::class.java) { sim.options["simspoof_iso"] = "ZZ" }
        assertThrows(Exception::class.java) { sim.options["simspoof_mccmnc"] = "abc" }
        sim.options["simspoof_iso"] = "US"
    }

    @Test
    fun `merging shared defaults is deterministic and conflicts leave the old asset intact`() {
        val output = File.createTempFile("hushfeed-defaults", ".json").apply { delete() }
        try {
            mergeDefaultSettings(output, JsonParser.parseString("""{"simspoof":true,"region_as_flag":true}""").asJsonObject, "A")
            mergeDefaultSettings(output, JsonParser.parseString("""{"simspoof":true}""").asJsonObject, "B")
            val before = output.readText()
            assertEquals("{\"region_as_flag\":true,\"simspoof\":true}\n", before)
            assertThrows(PatchException::class.java) {
                mergeDefaultSettings(output, JsonParser.parseString("""{"simspoof":false}""").asJsonObject, "C")
            }
            assertEquals(before, output.readText())
            val secondBuild = output.resolveSibling("defaults-second-build.json")
            try {
                mergeDefaultSettings(secondBuild, JsonParser.parseString("""{"simspoof":false}""").asJsonObject, "D")
                assertEquals("{\"simspoof\":false}\n", secondBuild.readText())
            } finally { secondBuild.delete() }
        } finally { output.delete() }
    }
}
