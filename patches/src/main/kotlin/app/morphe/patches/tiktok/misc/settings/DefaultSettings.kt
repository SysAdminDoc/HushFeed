/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.PatchBuilder
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.floatOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.longOption
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.patch.bytecodePatch as buildBytecodePatch
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets

internal const val DEFAULT_SETTINGS_ASSET = "assets/hushfeed-defaults-v1.json"

/** The same DSL, with native Morphe options added to every configurable TikTok patch. */
fun configurableBytecodePatch(
    name: String? = null,
    description: String? = null,
    default: Boolean = true,
    block: BytecodePatchBuilder.() -> Unit = {},
) = buildBytecodePatch(name, description, default) {
    block()
    if (name != null) addDefaultSettings(name)
}

internal val defaultSettingsCatalog: JsonObject by lazy {
    val stream = object {}.javaClass.getResourceAsStream("/settings-defaults.json")
        ?: throw PatchException("Missing settings defaults catalog")
    stream.bufferedReader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
}

private fun PatchBuilder<*>.addDefaultSettings(patchName: String) {
    val keys = defaultSettingsCatalog.getAsJsonObject("patches").getAsJsonArray(patchName) ?: return
    val settings = defaultSettingsCatalog.getAsJsonObject("settings")
    val configured = keys.map { entry ->
        val key = entry.asString
        val setting = settings.getAsJsonObject(key)
        val stock = setting.getAsJsonPrimitive("default")
        val title = "Default: " + key.replace('_', ' ')
        val option: Option<*> = when (setting["type"].asString) {
            "boolean" -> booleanOption(key, stock.asBoolean, title = title)
            "integer" -> intOption(key, stock.asInt, title = title,
                validator = { it == null || validDefault(setting, it) })
            "long" -> longOption(key, stock.asLong, title = title)
            "float" -> floatOption(key, stock.asFloat, title = title,
                validator = { it == null || it.isFinite() && validDefault(setting, it) })
            "enum", "string" -> stringOption(key, stock.asString, title = title,
                values = setting.getAsJsonArray("values")?.associate { it.asString to it.asString },
                validator = { it == null || validDefault(setting, it) })
            else -> throw PatchException("Unsupported setting type: $key")
        }
        Triple(key, stock, option)
    }
    // An individual dependency captures this patch's options. Run state is kept in the output
    // asset rather than in a singleton, so cancellation and a second build cannot leak values.
    dependsOn(rawResourcePatch {
        execute {
            if (DEFAULT_SETTINGS_ASSET in listApkEntries(DEFAULT_SETTINGS_ASSET)) {
                throw PatchException("Settings defaults already exist in the input APK; use an unpatched APK")
            }
            val overrides = JsonObject()
            configured.forEach { (key, stock, option) ->
                val value = option.value ?: return@forEach
                val encoded = when (value) {
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is String -> JsonPrimitive(value)
                    else -> throw PatchException("Unsupported setting value: $key")
                }
                if (encoded != stock) overrides.add(key, encoded)
            }
            if (overrides.size() != 0) mergeDefaultSettings(get(DEFAULT_SETTINGS_ASSET), overrides, patchName)
        }
    })
}

internal fun validDefault(setting: JsonObject, value: Any): Boolean {
    if (value is Number) {
        val number = value.toDouble()
        if (!number.isFinite()) return false
        if (setting.has("minimum") && number < setting["minimum"].asDouble) return false
        if (setting.has("maximum") && number > setting["maximum"].asDouble) return false
    }
    if (value is String) {
        if (value.length > 512 * 1024) return false
        setting.getAsJsonArray("values")?.let { values -> if (values.none { it.asString == value }) return false }
        when (setting["key"].asString) {
            "simspoof_iso" -> if (!Regex("[a-zA-Z]{2}").matches(value) ||
                value.uppercase(java.util.Locale.ROOT) !in java.util.Locale.getISOCountries()) return false
            "simspoof_mccmnc" -> if (!Regex("[0-9]{5,6}").matches(value)) return false
            "blocked_caption_words", "blocked_creators", "local_hidden_creators", "creator_filter_exceptions" -> {
                if (value.toByteArray(StandardCharsets.UTF_8).size > 256 * 1024) return false
                if (value.count { it == '\n' || it == ',' } >= 10_000) return false
            }
        }
    }
    return true
}

/** Shared settings may be configured through either owner; differing overrides are an error. */
internal fun mergeDefaultSettings(output: File, overrides: JsonObject, patchName: String) {
    val merged = if (output.isFile) JsonParser.parseString(output.readText(Charsets.UTF_8)).asJsonObject else JsonObject()
    overrides.entrySet().forEach { (key, value) ->
        if (merged.has(key) && merged[key] != value) {
            throw PatchException("$patchName: conflicting defaults for $key; use the same value in every patch that shares it")
        }
        merged.add(key, value)
    }
    val sorted = JsonObject().apply { merged.keySet().sorted().forEach { add(it, merged[it]) } }
    val encoded = sorted.toString() + "\n"
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > 8 * 1024 * 1024) {
        throw PatchException("$patchName: settings defaults exceed the 8 MB asset limit")
    }
    val parent = output.absoluteFile.parentFile ?: throw PatchException("Settings defaults asset has no parent")
    check(parent.isDirectory || parent.mkdirs()) { "Cannot create settings defaults asset" }
    output.writeText(encoded, Charsets.UTF_8)
}
