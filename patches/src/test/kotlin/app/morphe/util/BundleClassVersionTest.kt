/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.util

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Morphe patcher 2.1 refuses a bundle holding a class newer than Java 17, so verifyBundle names
 * any such class and the build stops there instead of at a user's Manager.
 */
class BundleClassVersionTest {
    /** A bundle whose entries hold just a class file header at the given major version. */
    private fun bundleWith(vararg classes: Pair<String, Int>): File {
        val file = File.createTempFile("bundle-class-version", ".mpp").apply { deleteOnExit() }
        JarOutputStream(file.outputStream()).use { jar ->
            for ((name, major) in classes) {
                jar.putNextEntry(JarEntry(name))
                jar.write(byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                    0, 0, (major shr 8).toByte(), major.toByte(),
                ))
                jar.closeEntry()
            }
            // Not a class, whatever its bytes say.
            jar.putNextEntry(JarEntry("classes.dex"))
            jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 99))
            jar.closeEntry()
        }
        return file
    }

    @Test
    fun `a class compiled for Java 21 is named with its version`() {
        val bundle = bundleWith(
            "app/morphe/patches/Old.class" to 55,
            "app/morphe/patches/Edge.class" to 61,
            "app/morphe/patches/New.class" to 65,
        )
        JarFile(bundle).use {
            assertEquals(listOf("app/morphe/patches/New.class (major 65)"), BundleVerifier.classesNewerThanJava17(it))
        }
    }

    @Test
    fun `classes up to Java 17 pass`() {
        val bundle = bundleWith(
            "app/morphe/patches/Java8.class" to 52,
            "app/morphe/patches/Java11.class" to 55,
            "app/morphe/patches/Java17.class" to 61,
        )
        JarFile(bundle).use { assertEquals(emptyList<String>(), BundleVerifier.classesNewerThanJava17(it)) }
    }

    @Test
    fun `a file named class without the class file magic is not judged`() {
        val file = File.createTempFile("bundle-class-version", ".mpp").apply { deleteOnExit() }
        JarOutputStream(file.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("resources/notes.class"))
            jar.write("plain text, not bytecode".toByteArray())
            jar.closeEntry()
        }
        JarFile(file).use { assertEquals(emptyList<String>(), BundleVerifier.classesNewerThanJava17(it)) }
    }
}
