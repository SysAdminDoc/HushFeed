package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #271: the shop's "Find <product>" bar is stopped at its own trigger's show check. */
class ShopFindBarFixturesTest {
    @Test
    fun `the shop find bar's show check is unique and guardable on every retained fixture`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val triggers = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { classDef ->
                    classDef.type.endsWith("/FeedEcSearchBottomBarAssemTrigger;")
                }
            }
            assertEquals("${apk.name}: the shop find bar's trigger", 1, triggers.size)
            val trigger = triggers.single()
            assertTrue(
                "${apk.name}: the trigger is the ec search bar's, by its banner key",
                trigger.methods.any { method ->
                    method.implementation?.instructions?.any { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                            "bottom_banner_ec_search_rs"
                    } == true
                },
            )
            val checks = trigger.methods.filter { method ->
                method.returnType == "Z" && method.parameterTypes.map(CharSequence::toString) ==
                    listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;") &&
                    !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null
            }
            assertEquals("${apk.name}: the shop find bar's show check", 1, checks.size)
            assertTrue(
                "${apk.name}: the show check hands back its answer with a plain return the hook can filter",
                checks.single().implementation!!.instructions.any { it.opcode == Opcode.RETURN },
            )
        }
    }
}
