package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The series player's advert is stopped at the requester's validity check. Each declared build has
 * exactly one such method, it is static and answers a boolean, and its frame has the local the
 * guard writes. The callers branch on the answer and fall to the no-advert path on false.
 */
class SeriesRealtimeAdAnchorsTest {
    @Test
    fun `each declared build has one guardable series advert check`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation != null && SeriesRealtimeAdValidFingerprint.takes(method, classDef)) {
                            taken += classDef to method
                        }
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { it.first.type }}", 1, taken.size)
            val (classDef, method) = taken.single()
            assertEquals("Z", method.returnType)
            assertTrue("${apk.name}: static, so p0 is the advert", AccessFlags.STATIC.isSet(method.accessFlags))
            assertTrue(
                "${apk.name}: ${classDef.type} frame has no local for the guard",
                method.implementation!!.registerCount > method.parameterTypes.size,
            )
        }
    }
}
