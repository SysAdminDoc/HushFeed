package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page SuggestedAccountCells.PROFILE_RELATIONS names, which Keep suggestions on profiles fills. */
private const val PROFILE_RELATIONS = "Lcom/ss/android/ugc/profile/business/ur/following/ui/FollowRelationTabActivity;"
private const val PROFILE_PANEL = "Lcom/ss/android/ugc/profile/business/ur/platform/HeaderRecommendUserCardUserComponent;"

/** #462: View all on a profile's suggestions opens the page whose cells Keep leaves in place. */
class ProfileRelationsAnchorsTest {
    @Test
    fun `each declared build opens the profile relations page from the suggestion panel's View all`() {
        Fixtures.forEachDeclared { apk ->
            val classes = Fixtures.dexContainer(apk, Opcodes.getDefault()).let { container ->
                container.dexEntryNames.asSequence()
                    .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            }
            assertTrue("${apk.name}: no $PROFILE_RELATIONS", classes.any { it.type == PROFILE_RELATIONS })

            // The click logs click_see_all, reads the panel it belongs to and opens the page on
            // its Suggested tab.
            val opens = classes.flatMap { it.methods }.filter { method ->
                val code = method.implementation?.instructions?.toList() ?: return@filter false
                val strings = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                "click_see_all" in strings && "suggest_user" in strings &&
                    code.any { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == PROFILE_PANEL } &&
                    code.any { it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).definingClass == PROFILE_RELATIONS }
            }
            assertEquals("${apk.name}: ${opens.map { it.definingClass + "->" + it.name }}", 1, opens.size)
        }
    }
}
