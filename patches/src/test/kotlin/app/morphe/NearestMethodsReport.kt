package app.morphe

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import java.io.File
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The methods nearest a fingerprint on each declared build, for a retarget where it missed.
 *
 * Skips unless `HUSHFEED_NEAREST` names the fingerprint: its object's full class name
 * (`app.morphe.patches.tiktok.feedfilter.OpenedListFillFingerprint`), a top-level val as
 * `<file class>.<field>`, or just the object's simple name. `HUSHFEED_NEAREST_FROM` may name an
 * older APK the fingerprint still takes one method of; that method's shape then counts half of
 * each score, which tells apart the methods the parts alone leave tied. Run it as
 *
 *     :patches:test --tests app.morphe.NearestMethodsReport
 *
 * with `HUSHFEED_FIXTURE_DIR` set as for the fixture tests. Each ranking prints to the test's
 * output and to `patches/build/nearest-methods/<fingerprint>-<version>.txt`.
 */
class NearestMethodsReport {
    @Test
    fun `print the methods nearest the fingerprint HUSHFEED_NEAREST names`() {
        val name = System.getenv(NAMED)
        assumeTrue("$NAMED is not set, so there is no fingerprint to rank methods for.", !name.isNullOrBlank())
        val fingerprint = fingerprintNamed(name!!.trim())
        val previous = System.getenv(FROM)?.takeIf { it.isNotBlank() }?.let { previousShape(fingerprint, File(it.trim())) }
        val out = File("build/nearest-methods")
        if (!out.mkdirs() && !out.isDirectory) fail("couldn't make $out")
        for (apk in Fixtures.declared()) {
            val title = "${fingerprint.javaClass.name} on ${apk.name}" + if (previous == null) "" else ", likeness to the method on $FROM"
            val text = fingerprint.tally(classesOf(apk)).rank(previous = previous).report(title)
            println(text)
            File(out, "${fingerprint.javaClass.simpleName}-${Fixtures.versionOf(apk)}.txt").writeText(text)
        }
    }

    private companion object {
        const val NAMED = "HUSHFEED_NEAREST"
        const val FROM = "HUSHFEED_NEAREST_FROM"

        /** The shape of the one method [fingerprint] takes on [apk], which has to be an older build it still matches. */
        fun previousShape(fingerprint: Fingerprint, apk: File): Shape {
            if (!apk.isFile) fail("$FROM names $apk, which is not a file.")
            val taken = fingerprint.shapesOn(classesOf(apk))
            if (taken.size != 1) fail("${fingerprint.javaClass.simpleName} takes ${taken.size} methods on ${apk.name}, so it has no one shape to compare with.")
            return taken.single()
        }

        fun fingerprintNamed(name: String): Fingerprint {
            val loader = NearestMethodsReport::class.java.classLoader
            fun load(className: String): Class<*>? = try {
                Class.forName(className, true, loader)
            } catch (_: ClassNotFoundException) {
                null
            }
            load(name)?.let { type ->
                val instance = type.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
                if (instance is Fingerprint) return instance
            }
            val dot = name.lastIndexOf('.')
            if (dot > 0) {
                load(name.substring(0, dot))?.let { file ->
                    val field = file.declaredFields.firstOrNull { it.name == name.substring(dot + 1) }
                    val value = field?.also { it.isAccessible = true }?.get(null)
                    if (value is Fingerprint) return value
                }
            }
            if ('.' !in name) {
                val root = File(AppCompatibilities::class.java.protectionDomain.codeSource.location.toURI())
                val found = root.walkTopDown().filter { it.name == "$name.class" }.toList()
                if (found.size == 1) {
                    val className = found.single().relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')
                    return fingerprintNamed(className)
                }
                if (found.size > 1) fail("$name names ${found.size} classes; give the full name: ${found.map { it.relativeTo(root).path }}")
            }
            fail("$NAMED names $name, which is no fingerprint object or top-level fingerprint val of the patches.")
            error("unreachable")
        }
    }
}
