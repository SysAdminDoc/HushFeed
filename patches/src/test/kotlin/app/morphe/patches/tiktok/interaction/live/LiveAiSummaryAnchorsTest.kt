package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE controls returns through the card's own hide at the entry of the feed LIVE preview's
 * Summary card show (#418). Each declared build has exactly one such method, in the real-named
 * widget, with a local for the guard. Both of the card's show paths must still go through the
 * base widget's show (which lands on the override), the card must already run the base hide the
 * guard calls, and the override must still be what sends the card's show event, or the guard
 * would leave the card up with no failure anywhere.
 */
class LiveAiSummaryAnchorsTest {
    private val widget = "Lcom/bytedance/android/widget/Widget;"

    @Test
    fun `each declared build has one guardable LIVE preview Summary card show`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            var card: ClassDef? = null
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type == LIVE_AI_SUMMARY_WIDGET) card = classDef
                    for (method in classDef.methods) {
                        if (method.implementation != null && LiveAiSummaryShowFingerprint.takes(method, classDef)) {
                            taken += classDef to method
                        }
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val (classDef, show) = taken.single()
            assertTrue("${apk.name}: instance method, p0 is the card", !AccessFlags.STATIC.isSet(show.accessFlags))
            assertTrue(
                "${apk.name}: show frame has no local for the guard",
                show.implementation!!.registerCount > show.parameterTypes.size + 1,
            )

            fun calls(method: Method) = method.implementation?.instructions?.toList().orEmpty()
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            fun isWidget(ref: MethodReference, name: String) =
                ref.definingClass == widget && ref.name == name && ref.parameterTypes.isEmpty() && ref.returnType == "V"

            val methods = card!!.methods.toList()
            val showers = methods.filter { method -> calls(method).any { isWidget(it, "show") } }
            assertTrue(
                "${apk.name}: the card's bind and room-message paths no longer call the base show: " +
                    showers.map { it.name },
                showers.size >= 2,
            )
            assertTrue(
                "${apk.name}: the card no longer runs the base hide the guard calls",
                methods.any { method -> calls(method).any { isWidget(it, "hide") } },
            )
            assertTrue(
                "${apk.name}: the card shows its view some other way than the base show",
                methods.none { method ->
                    calls(method).any { it.definingClass == "Landroid/view/View;" && it.name == "setVisibility" }
                },
            )

            // The override hands the card to its sibling that sends livesdk_ai_summary_show.
            fun shape(types: List<CharSequence>) = types.map(CharSequence::toString)
            val sent = calls(show).filter { it.definingClass == classDef.type }.mapNotNull { ref ->
                methods.firstOrNull { it.name == ref.name && shape(it.parameterTypes) == shape(ref.parameterTypes) }
            }
            assertTrue(
                "${apk.name}: show no longer sends the card's show event",
                sent.any { method ->
                    method.implementation?.instructions?.toList().orEmpty().any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "livesdk_ai_summary_show"
                    }
                },
            )
        }
    }
}
