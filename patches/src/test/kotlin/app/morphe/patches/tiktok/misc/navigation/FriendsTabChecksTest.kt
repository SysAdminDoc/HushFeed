package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikTok's Friends tabs each ask a check of their own (#98), so the switch has to answer those and
 * not only the layout. Each one is a single method on the fixture, and it returns a plain value the
 * hook can pass through.
 */
class FriendsTabChecksTest {
    private val checks = listOf(
        "Lcom/ss/android/ugc/aweme/friendstab/service/FriendsFeedServiceImpl;" to FriendsOnTopFingerprint,
        "Lcom/ss/android/ugc/aweme/friendstab/experiment/FriendsTabTopExperiment;" to FriendsTopIndexFingerprint,
        "Lcom/ss/android/ugc/aweme/tab/FriendsTabProtocol;" to FriendsBottomTabEnableFingerprint,
    )

    @Test
    fun `the saved tabs flag has one reader on every fixture`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.flatMap { classDef ->
                    classDef.methods.filter { SavedTabsFingerprint.takes(it, classDef) }
                }
            }
            assertEquals("${apk.name}: ${taken.map { it.definingClass + it.name }}", 1, taken.size)
            val implementation = taken.single().implementation!!
            assertTrue("${apk.name} never returns", implementation.instructions.any { it.opcode == Opcode.RETURN })
            assertTrue("${apk.name} has a try block", implementation.tryBlocks.isEmpty())
        }
    }

    @Test
    fun `each Friends check is one method on every fixture`() {
        for (apk in Fixtures.apks()) {
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            for ((type, fingerprint) in checks) {
                val owner = classes[type]
                assertNotNull("${apk.name}: no $type", owner)
                val taken = owner!!.methods.filter { fingerprint.takes(it, owner) }
                assertEquals("${apk.name} $type: ${taken.map { it.name }}", 1, taken.size)
                val implementation = taken.single().implementation
                assertNotNull("${apk.name} $type has no code", implementation)
                val instructions = implementation!!.instructions.toList()
                assertTrue("${apk.name} $type never returns", instructions.any { it.opcode == Opcode.RETURN })
                assertTrue("${apk.name} $type has a try block", implementation.tryBlocks.isEmpty())
            }
        }
    }
}
