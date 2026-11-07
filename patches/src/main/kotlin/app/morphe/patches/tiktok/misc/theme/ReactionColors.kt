/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.PatchException
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.roundToInt

/** TikTok's yellow for a saved video's bookmark. */
internal const val TIKTOK_SAVE = "#FACE15"

/** The like color's presets: the accent's, TikTok's red first. */
internal val LIKE_PRESETS: Map<String, String> = ACCENT_PRESETS

/** The save color's presets: TikTok's yellow first, then the accent's colors and TikTok's red. */
internal val SAVE_PRESETS: Map<String, String> = linkedMapOf("TikTok original" to TIKTOK_SAVE) +
    ACCENT_PRESETS.filterKeys { it != "TikTok original" } + ("Red" to TIKTOK_ACCENT)

/**
 * The files that draw one reaction's filled icon on a build (#369). [rawIcons] and [drawables] are
 * resource names whose files are pictures, [pictures] and [animations] are paths under `assets/`.
 */
internal class ReactionFiles(
    val rawIcons: List<String>,
    val drawables: List<String>,
    val pictures: List<String>,
    val animations: List<String>,
)

/**
 * The liked heart per build. On 47.7.2 the feed's like button (X.0B9P) builds its liked state from
 * one of the raw icons or `c01`, and plays one of the animations when tapped; the animations' red
 * parts are the pictures and their own color arrays. ReactionColorsFixtureTest holds each declared
 * build's fixture to its entry here.
 */
internal val LIKE_FILES: Map<String, ReactionFiles> = linkedMapOf(
    "47.7.3" to ReactionFiles(
        rawIcons = listOf(
            "icon_color_like_red_shadow",
            "icon_color_like_red_shadow_2",
            "icon_color_like_red_shadow_alt_1",
            "icon_media_like_active",
            "icon_exp_exp_01_icon_media_like_active",
            "icon_exp_exp_02_icon_media_like_active",
        ),
        drawables = listOf("c01"),
        pictures = listOf("images/ic_home_like_after.png", "images/ic_like_fill_2_new.png", "images/icon_like_shadow_alt.png"),
        animations = listOf(
            "icon_home_like_new.json",
            "feed_fill_like/feed_fill_like_add_lottie.json",
            "tt_feed_like_tap_tux_lottie.json",
            "tt_feed_like_tap_quick_tux_lottie.json",
            "tt_feed_like_tap_old_tux_lottie.json",
            "tt_feed_like_tap_old_quick_tux_lottie.json",
        ),
    ),
)

/**
 * The saved bookmark per build. On 47.7.2 the feed's save button (X.0BCv) names its saved picture
 * by experiment group, and the older button plays the favorite animation.
 */
internal val SAVE_FILES: Map<String, ReactionFiles> = linkedMapOf(
    "47.7.3" to ReactionFiles(
        rawIcons = listOf(
            "icon_color_bookmark_yellow_shadow",
            "icon_color_bookmark_yellow_shadow_alt_1",
            "icon_media_favoriate_active",
            "icon_exp_exp_01_icon_media_favoriate_active",
            "icon_exp_exp_02_icon_media_favoriate_active",
        ),
        drawables = emptyList(),
        pictures = listOf(
            "images/ic_bookmark_2.png",
            "images/icon_media_favoriate_active.png",
            "images/icon_exp_exp_01_icon_media_favoriate_active.png",
            "images/icon_exp_exp_02_icon_media_favoriate_active.png",
        ),
        animations = listOf("tt_feed_favorite_old_tux_lottie.json"),
    ),
)

/** One reaction to recolor: its files, the color TikTok draws them in and the one picked. */
internal class ReactionRecolor(val label: String, val files: ReactionFiles, val source: String, val target: String)

/** The reactions whose color was changed, with this build's files, or a refusal for a build never read. */
internal fun reactionRecolors(versionName: String?, like: String, save: String): List<ReactionRecolor> {
    val wanted = listOf(
        Triple("Like color", LIKE_FILES, TIKTOK_ACCENT to like),
        Triple("Save color", SAVE_FILES, TIKTOK_SAVE to save),
    ).filter { (_, _, colors) -> colors.first != colors.second }
    return wanted.map { (label, table, colors) ->
        val files = versionName?.let { table[it] } ?: throw PatchException(unreadReactionRefusal(label, versionName))
        ReactionRecolor(label, files, colors.first, colors.second)
    }
}

internal fun unreadReactionRefusal(label: String, versionName: String?): String {
    val build = if (versionName.isNullOrBlank()) "this TikTok build" else "TikTok $versionName"
    return "Accent color: $label hasn't read which pictures draw that icon in $build, so nothing " +
        "was changed. The pictures are known for TikTok ${LIKE_FILES.keys.joinToString(", ")}."
}

internal fun missingReactionRefusal(label: String, name: String): String =
    "Accent color: $label found nothing to recolor in $name, so this TikTok's pictures aren't the " +
        "ones the patch read. Nothing was changed."

/**
 * [rgb] moved from [source]'s family to [target]'s, the way accentShade moves TikTok's reds: the
 * hue turns by the target's turn, saturation scales and lightness keeps its distance, so a
 * picture's darker edges stay darker. Null when [rgb] isn't a shade of [source]: more than 25
 * degrees round the color wheel, washed out, or near black or white. White highlights and gray
 * shadows stay as they are.
 */
internal fun familyShade(rgb: Int, source: Int, target: Int): Int? {
    val from = hsl(rgb and 0xffffff)
    val base = hsl(source and 0xffffff)
    val turn = abs(from.h - base.h).let { minOf(it, 360.0 - it) }
    if (turn > 25.0 || from.s < 0.35 || from.l !in 0.15..0.9) return null
    val to = hsl(target and 0xffffff)
    val hue = ((to.h + from.h - base.h) % 360.0 + 360.0) % 360.0
    val saturation = if (base.s == 0.0) to.s else (from.s * to.s / base.s).coerceIn(0.0, 1.0)
    val lightness = (from.l + to.l - base.l).coerceIn(0.0, 1.0)
    return rgb(Hsl(hue, saturation, lightness))
}

/** The recolor for one reaction: [familyShade] from its source to its target. */
internal fun reactionShade(source: String, target: String): (Int) -> Int? {
    val from = source.removePrefix("#").takeLast(6).toInt(16)
    val to = target.removePrefix("#").takeLast(6).toInt(16)
    return { rgb -> familyShade(rgb, from, to) }
}

private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

/**
 * [png] with every color [recolor] answers for changed and each pixel's alpha kept. A palette PNG
 * changes only its PLTE. An 8-bit RGB or RGBA one is inflated, unfiltered, recolored and written
 * back unfiltered in one IDAT. Morphe Manager patches on the phone, where there's no image
 * library, so this reads the format itself. Null when nothing changed or the PNG is one this
 * doesn't read (interlaced, 16-bit or gray).
 */
internal fun recolorPng(png: ByteArray, recolor: (Int) -> Int?): ByteArray? {
    val chunks = pngChunks(png) ?: return null
    val header = chunks.firstOrNull()?.takeIf { it.first == "IHDR" && it.second.size == 13 }?.second ?: return null
    val width = header.int(0)
    val height = header.int(4)
    val depth = header[8].toInt()
    val colorType = header[9].toInt()
    val interlaced = header[12].toInt() != 0
    if (colorType == 3) {
        val index = chunks.indexOfFirst { it.first == "PLTE" }.takeIf { it >= 0 } ?: return null
        val palette = chunks[index].second.copyOf()
        var changed = 0
        for (entry in 0 until palette.size / 3 * 3 step 3) {
            val was = palette.int24(entry)
            val now = recolor(was) ?: continue
            if (now != was) changed++
            palette.putInt24(entry, now)
        }
        if (changed == 0) return null
        return writePng(chunks.mapIndexed { i, chunk -> if (i == index) "PLTE" to palette else chunk })
    }
    if (depth != 8 || interlaced || (colorType != 2 && colorType != 6) || width <= 0 || height <= 0) return null
    val channels = if (colorType == 6) 4 else 3
    val stride = width * channels
    val compressed = ByteArrayOutputStream().apply { chunks.filter { it.first == "IDAT" }.forEach { write(it.second) } }.toByteArray()
    val raw = ByteArray(height * (stride + 1))
    val inflater = Inflater()
    try {
        inflater.setInput(compressed)
        var filled = 0
        while (filled < raw.size) {
            val read = inflater.inflate(raw, filled, raw.size - filled)
            if (read == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
            filled += read
        }
        if (filled != raw.size) return null
    } catch (_: java.util.zip.DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
    val pixels = unfilter(raw, height, stride, channels) ?: return null
    var changed = 0
    for (at in pixels.indices step channels) {
        if (channels == 4 && pixels[at + 3].toInt() == 0) continue
        val was = pixels.int24(at)
        val now = recolor(was) ?: continue
        if (now != was) changed++
        pixels.putInt24(at, now)
    }
    if (changed == 0) return null
    val unfiltered = ByteArray(raw.size)
    for (row in 0 until height) System.arraycopy(pixels, row * stride, unfiltered, row * (stride + 1) + 1, stride)
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    val data = try {
        deflater.setInput(unfiltered)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        out.toByteArray()
    } finally {
        deflater.end()
    }
    val first = chunks.indexOfFirst { it.first == "IDAT" }
    val rebuilt = chunks.filterIndexed { i, chunk -> chunk.first != "IDAT" || i == first }
        .map { if (it.first == "IDAT") "IDAT" to data else it }
    return writePng(rebuilt)
}

/** The PNG's chunks in order, or null when it isn't a whole PNG. */
private fun pngChunks(png: ByteArray): List<Pair<String, ByteArray>>? {
    if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
    val chunks = mutableListOf<Pair<String, ByteArray>>()
    var at = 8
    while (at + 12 <= png.size) {
        val length = png.int(at)
        if (length < 0 || at + 12 + length > png.size) return null
        val type = String(png, at + 4, 4, Charsets.US_ASCII)
        chunks += type to png.copyOfRange(at + 8, at + 8 + length)
        at += 12 + length
        if (type == "IEND") return chunks
    }
    return null
}

private fun writePng(chunks: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(PNG_SIGNATURE)
    for ((type, data) in chunks) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(bigEndian(data.size))
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        out.write(bigEndian(crc.toInt()))
    }
    return out.toByteArray()
}

/** The pixel rows of an inflated 8-bit image, each row's filter undone, or null for an unknown filter. */
private fun unfilter(raw: ByteArray, height: Int, stride: Int, channels: Int): ByteArray? {
    val pixels = ByteArray(height * stride)
    for (row in 0 until height) {
        val filter = raw[row * (stride + 1)].toInt()
        val from = row * (stride + 1) + 1
        val to = row * stride
        for (x in 0 until stride) {
            val value = raw[from + x].toInt() and 0xff
            val left = if (x >= channels) pixels[to + x - channels].toInt() and 0xff else 0
            val up = if (row > 0) pixels[to - stride + x].toInt() and 0xff else 0
            val upLeft = if (row > 0 && x >= channels) pixels[to - stride + x - channels].toInt() and 0xff else 0
            val predicted = when (filter) {
                0 -> 0
                1 -> left
                2 -> up
                3 -> (left + up) / 2
                4 -> paeth(left, up, upLeft)
                else -> return null
            }
            pixels[to + x] = (value + predicted).toByte()
        }
    }
    return pixels
}

private fun paeth(left: Int, up: Int, upLeft: Int): Int {
    val estimate = left + up - upLeft
    val toLeft = abs(estimate - left)
    val toUp = abs(estimate - up)
    val toUpLeft = abs(estimate - upLeft)
    return if (toLeft <= toUp && toLeft <= toUpLeft) left else if (toUp <= toUpLeft) up else upLeft
}

private fun ByteArray.int(at: Int): Int = ((this[at].toInt() and 0xff) shl 24) or ((this[at + 1].toInt() and 0xff) shl 16) or
    ((this[at + 2].toInt() and 0xff) shl 8) or (this[at + 3].toInt() and 0xff)

private fun ByteArray.int24(at: Int): Int = ((this[at].toInt() and 0xff) shl 16) or ((this[at + 1].toInt() and 0xff) shl 8) or
    (this[at + 2].toInt() and 0xff)

private fun ByteArray.putInt24(at: Int, rgb: Int) {
    this[at] = (rgb shr 16).toByte()
    this[at + 1] = (rgb shr 8).toByte()
    this[at + 2] = rgb.toByte()
}

private fun bigEndian(value: Int): ByteArray =
    byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

/** A Lottie color: a `k`, `s` or `e` array of four numbers from 0 to 1, red, green, blue and alpha. */
private val LOTTIE_COLOR = Regex(""""([kse])"(\s*):(\s*)\[([^\[\]{}]*)\]""")

/**
 * [json], a Lottie animation, with every color [recolor] answers for changed and its alpha kept.
 * Null when no color changed.
 */
internal fun recolorLottie(json: String, recolor: (Int) -> Int?): String? {
    var changed = 0
    val out = LOTTIE_COLOR.replace(json) { match ->
        val parts = match.groupValues[4].split(',')
        val numbers = parts.map { it.trim().toDoubleOrNull() }
        if (numbers.size != 4 || numbers.any { it == null || it < 0.0 || it > 1.0 }) return@replace match.value
        val was = numbers.take(3).fold(0) { rgb, channel -> (rgb shl 8) or (channel!! * 255).roundToInt() }
        val now = recolor(was) ?: return@replace match.value
        if (now == was) return@replace match.value
        changed++
        val channels = listOf(16, 8, 0).map { shift -> lottieNumber((now shr shift and 0xff) / 255.0) }
        "\"${match.groupValues[1]}\"${match.groupValues[2]}:${match.groupValues[3]}[" +
            channels.joinToString(",") + "," + parts[3].trim() + "]"
    }
    return if (changed == 0) null else out
}

private fun lottieNumber(value: Double): String =
    String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
