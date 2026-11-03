package app.morphe

import app.morphe.patcher.Fingerprint
import app.morphe.patches.tiktok.feedfilter.OpenedListFillFingerprint
import app.morphe.patches.tiktok.interaction.publishdate.GridAdapterSetDataFingerprint
import app.morphe.patches.tiktok.interaction.publishdate.GridCellOpenFingerprint
import app.morphe.patches.tiktok.interaction.publishdate.GridLoadAfterRefreshFingerprint
import app.morphe.patches.tiktok.misc.camerabrightness.BrightnessBoostStartFingerprint
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The nearest-methods ranking (NearestMethods.kt) held to real fingerprints on each declared
 * build. Each sample takes one method; breaking one of its parts, the way a retarget does when
 * TikTok changes one thing (a part about the class breaks for the whole class), has to leave the
 * method among the three a report prints, or tied with the third and the report saying so. And
 * the method's own shape, standing in for an older build's, has to put it first when one part
 * broke and among the three when every part did. The samples cover strings
 * alone, a named class with strings, a class fingerprint, a custom check, and instruction filters
 * on an obfuscated owner.
 */
class NearestMethodsFixturesTest {
    @Test
    fun `the method a sample takes meets every part and ranks first`() {
        Fixtures.forEachDeclared { apk ->
            for ((fingerprint, tally, index) in taken(classesOf(apk))) {
                assertEquals("${fingerprint.javaClass.simpleName}: parts it misses", 0, tally.parts.indices.count { tally.met[index] and (1L shl it) == 0L })
                val ranking = tally.rank()
                assertEquals(ranking.report(fingerprint.javaClass.simpleName), index, ranking.nearest.first().index)
                assertTrue(ranking.nearest.first().missed.isEmpty())
            }
        }
    }

    @Test
    fun `one broken part leaves the method among the nearest three or tied with the third`() {
        Fixtures.forEachDeclared { apk ->
            for ((fingerprint, tally, index) in taken(classesOf(apk))) {
                for (bit in tally.parts.indices) {
                    val broken = tally.breaking(index, 1L shl bit)
                    val ranking = tally.rank(override = broken)
                    val title = "${fingerprint.javaClass.simpleName} without ${tally.parts[bit].label}"
                    val near = ranking.nearest.firstOrNull { it.index == index }
                    if (near != null) {
                        assertEquals(title, listOf(tally.parts[bit].label), near.missed)
                        continue
                    }
                    // Not shown: it has to be one of the methods the report says tie with the third.
                    assertTrue("$title, and no tie is reported:\n${ranking.report(title)}", ranking.tiedWithLast > 0)
                    val third = ranking.nearest.last().score
                    val wider = tally.rank(count = ranking.nearest.size + ranking.tiedWithLast, override = broken)
                    val itself = wider.nearest.firstOrNull { it.index == index }
                    assertTrue("$title, and it scores below the third:\n${ranking.report(title)}", itself != null && abs(itself.score - third) < 1e-9)
                }
            }
        }
    }

    /**
     * With every part broken the parts can still favour another method: for GridCellOpenFingerprint
     * on 47.6.3, ProfileBaseAwemeListFragment's z2 of the same parameters meets all but the defining
     * class, and a real older build's shape is never a likeness of 1 to lean on. So the
     * shape has to put it first when one part broke and among the three when they all did.
     */
    @Test
    fun `its own shape puts the method first with one part broken and among the three with every part broken`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            for ((fingerprint, tally, index) in taken(classes)) {
                var shape: Shape? = null
                forEachMethod(classes) { at, classDef, method -> if (at == index) shape = Shape.of(method, classDef) }
                val every = (1L shl tally.parts.size) - 1
                for (bits in tally.parts.indices.map { 1L shl it } + every) {
                    val broken = tally.breaking(index, bits)
                    val ranking = tally.rank(previous = shape, override = broken)
                    val title = "${fingerprint.javaClass.simpleName} meeting ${java.lang.Long.bitCount(broken.getValue(index))} of ${tally.parts.size} parts"
                    val place = ranking.nearest.indexOfFirst { it.index == index }
                    if (bits == every) {
                        assertTrue(ranking.report(title), place >= 0)
                    } else {
                        assertEquals(ranking.report(title), 0, place)
                    }
                    assertEquals(1.0, ranking.nearest[place].likeness!!, 1e-9)
                }
            }
        }
    }

    /**
     * The retarget itself, when `HUSHFEED_NEAREST_FROM` names an older build: on the declared
     * build, with any one part broken, the shape of the method a sample took on the older build
     * ranks the method among the three a report prints. A sample the older build doesn't take
     * exactly one method of is left out and named, and at least one has to be compared.
     */
    @Test
    fun `an older build's shape finds the method a sample with one broken part missed`() {
        val from = System.getenv("HUSHFEED_NEAREST_FROM")?.takeIf { it.isNotBlank() }?.let { File(it.trim()) }
        assumeTrue("HUSHFEED_NEAREST_FROM is not set, so there is no older build to compare with.", from != null)
        val shapes = olderShapes(from!!)
        assertTrue("no sample takes exactly one method on ${from.name}, so nothing was compared", shapes.isNotEmpty())
        Fixtures.forEachDeclared { apk ->
            for ((fingerprint, tally, index) in taken(classesOf(apk))) {
                val shape = shapes[fingerprint] ?: continue
                for (bit in tally.parts.indices) {
                    val ranking = tally.rank(previous = shape, override = tally.breaking(index, 1L shl bit))
                    val title = "${fingerprint.javaClass.simpleName} without ${tally.parts[bit].label}, likeness to ${from.name}"
                    assertTrue(ranking.report(title), ranking.nearest.any { it.index == index })
                }
            }
        }
    }

    /**
     * No fingerprint in the patches declares a superclass or interfaces yet, so a declaration
     * built from a sample's own method stands in. With its class's real supertypes it takes that
     * method and meets every part; with a supertype the class doesn't have it takes nothing, and
     * that is the one part the method is shown missing.
     */
    @Test
    fun `a declared superclass and interfaces are judged on the method's class`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            val indices = taken(classes).map { it.third }.toSet()
            var found: Triple<Int, Method, ClassDef>? = null
            forEachMethod(classes) { at, classDef, method ->
                if (found == null && at in indices && classDef.superclass != null && classDef.interfaces.isNotEmpty()) {
                    found = Triple(at, method, classDef)
                }
            }
            val (index, method, classDef) = found ?: error("no sample's class on ${apk.name} has a superclass and an interface")
            fun declaring(superclass: String, interfaces: List<String>) = Fingerprint(
                definingClass = classDef.type,
                name = method.name,
                returnType = method.returnType,
                parameters = method.parameterTypes.map(CharSequence::toString),
                superclass = superclass,
                interfaces = interfaces,
            )
            val superclass = classDef.superclass!!
            val real = declaring(superclass, classDef.interfaces)
            assertTrue("the method's own supertypes", real.takes(method, classDef))
            val tally = real.tally(classes)
            val labels = tally.parts.map { it.label }
            assertTrue(labels.toString(), "superclass $superclass" in labels && classDef.interfaces.all { "implements $it" in labels })
            val first = tally.rank().nearest.first()
            assertEquals(index, first.index)
            assertTrue(first.missed.toString(), first.missed.isEmpty())

            for ((label, wrong) in listOf(
                "superclass Lhushfeed/NoSuchClass;" to declaring("Lhushfeed/NoSuchClass;", classDef.interfaces),
                "implements Lhushfeed/NoSuchInterface;" to declaring(superclass, classDef.interfaces + "Lhushfeed/NoSuchInterface;"),
            )) {
                assertFalse(label, wrong.takes(method, classDef))
                val ranking = wrong.tally(classes).rank()
                assertEquals(ranking.report(label), index, ranking.nearest.first().index)
                assertEquals(listOf(label), ranking.nearest.first().missed)
            }
        }
    }

    /** Each sample's one method on [apk], read and let go before the declared build is; the others are named and left out. */
    private fun olderShapes(apk: File): Map<Fingerprint, Shape> {
        val classes = classesOf(apk)
        val shapes = samples().associateWith { it.shapesOn(classes) }
        shapes.filterValues { it.size != 1 }.forEach { (fingerprint, found) ->
            println("${fingerprint.javaClass.simpleName} takes ${found.size} methods on ${apk.name}, so it isn't compared")
        }
        return shapes.filterValues { it.size == 1 }.mapValues { it.value.single() }
    }

    /**
     * The overrides that break [bits] the way a new build would: for the method at [index], and a
     * part about its class for every method of that class as well, since siblings share it. A
     * renamed obfuscated class can also hand its old name to an unrelated class, whose methods
     * then meet the part; no sample has an obfuscated class part, so that isn't modelled.
     */
    private fun Tally.breaking(index: Int, bits: Long): Map<Int, Long> {
        val classBits = parts.indices.filter { parts[it].perClass }.fold(0L) { all, bit -> all or (1L shl bit) } and bits
        val broken = HashMap<Int, Long>()
        if (classBits != 0L) {
            var owner: String? = null
            forEachMethod(classes) { at, classDef, _ -> if (at == index) owner = classDef.type }
            forEachMethod(classes) { at, classDef, _ -> if (classDef.type == owner) broken[at] = met[at] and classBits.inv() }
        }
        broken[index] = met[index] and bits.inv()
        return broken
    }

    /** Fingerprints that take one method on every declared build. GridTabNameFingerprint takes several by design. */
    private fun samples(): List<Fingerprint> = listOf(
        GridLoadAfterRefreshFingerprint,
        GridCellOpenFingerprint,
        GridAdapterSetDataFingerprint,
        BrightnessBoostStartFingerprint,
        OpenedListFillFingerprint,
    )

    /** Each sample's tally on the build and the index of the one method it takes there, every miscount named at once. */
    private fun taken(classes: List<ClassDef>): List<Triple<Fingerprint, Tally, Int>> {
        val all = samples().map { fingerprint ->
            val tally = fingerprint.tally(classes)
            Triple(fingerprint, tally, tally.indexWhere { method, classDef -> fingerprint.takes(method, classDef) })
        }
        val wrong = all.filter { it.third.size != 1 }.map { "${it.first.javaClass.simpleName} takes ${it.third.size}" }
        assertTrue("samples that don't take exactly one method: $wrong", wrong.isEmpty())
        return all.map { Triple(it.first, it.second, it.third.single()) }
    }
}
