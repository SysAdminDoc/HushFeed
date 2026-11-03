package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STREAK_SERVICE = "Lcom/ss/android/ugc/aweme/im/streak/impl/StreakService;"
private const val STREAK_CONFIG = "Lcom/ss/android/ugc/aweme/im/common/experiment/DMStreakConfig;"

/** Hide message streaks takes TikTok's one streak button answer on every declared host. */
class StreakButtonFixturesTest {
    @Test
    fun `each declared host has one reader of enableStreakBtn, StreakService's per scene answer, and the guard takes it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()
            // DMStreakConfig's constructor writes the field, so only reads count.
            val readers = classes.flatMap { classDef ->
                classDef.methods.filter { method ->
                    method.implementation?.instructions?.any {
                        val field = it.getReference<FieldReference>()
                        it.opcode == Opcode.IGET_BOOLEAN &&
                            field?.definingClass == STREAK_CONFIG && field.name == "enableStreakBtn"
                    } == true
                }.map { classDef to it }
            }
            assertEquals("$version readers of enableStreakBtn", 1, readers.size)
            val (classDef, method) = readers.single()
            assertEquals(version, STREAK_SERVICE, classDef.type)
            assertEquals("$version: it answers one scene", 1, method.parameterTypes.size)
            assertTrue("$version: the guard takes it", StreakButtonFingerprint.takes(method, classDef))
            val implementation = method.implementation!!
            assertTrue("$version: it has a local for the guard's answer",
                implementation.registerCount > method.numberOfParameterRegisters)
            assertEquals("$version: nothing else on StreakService is taken", 1,
                classDef.methods.count { StreakButtonFingerprint.takes(it, classDef) })
        }
    }
}
