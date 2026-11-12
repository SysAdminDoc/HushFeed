/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.PatchException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val OWN_CLASSES_ASSET = "assets/hushfeed-classes-v1.txt"

/** The extension dex files the TikTok extension patch adds, as extendWith names them. */
internal val OWN_EXTENSION_DEX = listOf("extensions/shared.mpe", "extensions/tiktok.mpe")

/**
 * Lists every class Hushfeed's extension adds under app/, so the diagnostic report can tell one
 * of its own from a class another bundle added under the same package.
 */
internal val ownClassesPatch = patch {
    execute {
        // The same class loader extendWith reads the extension dex files through.
        OwnClasses.write(resourceFile(OWN_CLASSES_ASSET)) { path -> OwnClasses::class.java.classLoader.getResourceAsStream(path) }
    }
}

internal object OwnClasses {
    fun write(output: File, open: (String) -> InputStream?) {
        val names = OWN_EXTENSION_DEX.flatMapTo(sortedSetOf()) { path ->
            val dex = open(path)?.use { it.readBytes() } ?: throw PatchException("The bundle has no $path")
            appClasses(dex)
        }
        check(output.parentFile.isDirectory || output.parentFile.mkdirs()) { "Cannot create the own classes asset" }
        output.writeText(names.joinToString("\n", postfix = "\n"), Charsets.ISO_8859_1)
    }

    /** The type descriptors of the classes a dex file defines under app/. */
    fun appClasses(dex: ByteArray): List<String> {
        if (dex.size < 0x70 || String(dex, 0, 4, Charsets.ISO_8859_1) != "dex\n") throw PatchException("Not a dex file")
        val data = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val stringIds = data.getInt(0x3C)
        val typeIds = data.getInt(0x44)
        val classDefs = data.getInt(0x64)
        return (0 until data.getInt(0x60)).map { index ->
            val type = data.getInt(classDefs + index * 32)
            var at = data.getInt(stringIds + data.getInt(typeIds + type * 4) * 4)
            // The string's UTF-16 length comes first, as a uleb128.
            while ((dex[at++].toInt() and 0x80) != 0) Unit
            var end = at
            while (dex[end] != 0.toByte()) end++
            String(dex, at, end - at, Charsets.ISO_8859_1)
        }.filter { it.startsWith("Lapp/") }
    }
}
