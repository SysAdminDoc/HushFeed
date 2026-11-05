package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PineDrama promo card is stopped where the series player builds its mid-insert request. Each
 * declared build has exactly one such builder, an instance method with no parameters that answers
 * an object (null is the app's own "no card component"), and its frame has a local for the guard.
 */
class PineDramaMidInsertAnchorsTest {
    @Test
    fun `each declared build has one guardable mid-insert request builder`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<String>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation != null && PineDramaMidInsertRequestFingerprint.takes(method, classDef)) {
                            taken += classDef.type
                            assertTrue("${apk.name}: instance method", !AccessFlags.STATIC.isSet(method.accessFlags))
                            assertEquals("${apk.name}: no parameters", 0, method.parameterTypes.size)
                            assertTrue("${apk.name}: returns an object", method.returnType.startsWith("L"))
                            assertTrue("${apk.name}: local for the guard", method.implementation!!.registerCount > 1)
                        }
                    }
                }
            }
            assertEquals("${apk.name}: $taken", 1, taken.size)
        }
    }
}
