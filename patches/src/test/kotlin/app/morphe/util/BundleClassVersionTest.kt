/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.util

import app.morphe.patches.shared.compat.AppCompatibilities
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Morphe patcher 2.1 refuses a bundle holding a class newer than Java 17, so verifyBundle names
 * any such class and the build stops there instead of at a user's Manager.
 */
class BundleClassVersionTest {
    /** Just a class file header at the given major version. */
    private fun classHeader(major: Int) = byteArrayOf(
        0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0, 0, (major shr 8).toByte(), major.toByte(),
    )

    /** A bundle whose entries hold just a class file header at the given major version. */
    private fun bundleWith(vararg classes: Pair<String, Int>): File {
        val file = File.createTempFile("bundle-class-version", ".mpp").apply { deleteOnExit() }
        JarOutputStream(file.outputStream()).use { jar ->
            for ((name, major) in classes) {
                jar.putNextEntry(JarEntry(name))
                jar.write(classHeader(major))
                jar.closeEntry()
            }
            // Not a class, whatever its bytes say.
            jar.putNextEntry(JarEntry("classes.dex"))
            jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 99))
            jar.closeEntry()
        }
        return file
    }

    /**
     * Arguments for BundleVerifier.main naming a bundle that clears every check made before the
     * class versions: the three DEX payloads, the version and every declared TikTok target. The
     * checksum is wrong on purpose, so a bundle that gets past the class check stops there.
     */
    private fun releaseShapedBundle(classMajor: Int): Array<String> {
        val targets = AppCompatibilities.tiktok().flatMap { it.targets }.mapNotNull { it.version }.distinct()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Version", "9.9.9")
            mainAttributes.putValue("Description", "TikTok " + targets.joinToString(", "))
        }
        val bundle = File.createTempFile("bundle-class-version", ".mpp").apply { deleteOnExit() }
        JarOutputStream(bundle.outputStream(), manifest).use { jar ->
            for (name in BundleVerifier.DEX_PAYLOADS) {
                jar.putNextEntry(JarEntry(name))
                // The DEX magic, then a class count of one at offset 96.
                jar.write(ByteArray(100).also {
                    byteArrayOf(100, 101, 120, 10).copyInto(it)
                    it[96] = 1
                })
                jar.closeEntry()
            }
            jar.putNextEntry(JarEntry("app/morphe/patches/Patch.class"))
            jar.write(classHeader(classMajor))
            jar.closeEntry()
        }
        val checksum = File.createTempFile("bundle-class-version", ".sha256").apply {
            deleteOnExit()
            writeText("0".repeat(64))
        }
        return arrayOf(bundle.path, "patches-list.json", "9.9.9", checksum.path)
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

    @Test
    fun `verifyBundle stops on a Java 21 class and names it`() {
        val failure = runCatching { BundleVerifier.main(releaseShapedBundle(65)) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message, failure.message!!.contains("app/morphe/patches/Patch.class (major 65)"))
    }

    @Test
    fun `verifyBundle lets a Java 11 class through to the checksum`() {
        val failure = runCatching { BundleVerifier.main(releaseShapedBundle(55)) }.exceptionOrNull()
        assertTrue("$failure", failure?.message.orEmpty().startsWith("Bundle differs from the Android build output"))
    }
}
