package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #478: the full-screen people you may know card is stopped where its request is built. Each
 * declared build has one such builder, it asks for card type 49 with a CardTypeRequest, it already
 * answers an empty list on its own when TikTok's experiment is off, and its frame has the local the
 * guard writes.
 */
class RecUserBigCardRequestAnchorsTest {
    private val request = "Lcom/ss/android/ugc/feed/platform/cardinsert/data/CardTypeRequest;"

    @Test
    fun `each declared build has one guardable people you may know card request builder`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation != null && RecUserBigCardRequestFingerprint.takes(method, classDef)) {
                            taken += classDef to method
                        }
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { it.first.type }}", 1, taken.size)
            val (classDef, method) = taken.single()
            val code = method.implementation!!.instructions.toList()
            val built = code.indexOfFirst {
                it.opcode == Opcode.INVOKE_DIRECT && it.getReference<MethodReference>()?.let { call ->
                    call.definingClass == request && call.name == "<init>"
                } == true
            }
            assertTrue("${apk.name}: builds a CardTypeRequest", built >= 0)
            assertTrue("${apk.name}: asks for card type 49", (0 until built).any {
                (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == 49
            })
            assertTrue("${apk.name}: answers it in a one-item list", code.drop(built).any {
                it.getReference<MethodReference>()?.let { call ->
                    call.definingClass == "Ljava/util/Collections;" && call.name == "singletonList"
                } == true
            })
            assertTrue("${apk.name}: instance method on the helper, p0 is the helper", !AccessFlags.STATIC.isSet(method.accessFlags))
            assertTrue("${apk.name}: ${classDef.type} frame has no local for the guard",
                method.implementation!!.registerCount > method.parameterTypes.size + 1)
        }
    }
}
