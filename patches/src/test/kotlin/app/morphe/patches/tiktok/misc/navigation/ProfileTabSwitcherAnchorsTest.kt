package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Long-press Profile for Watch history answers TikTok's account switcher, held to every
 * fixture (#341).
 *
 * <p>Where holding Profile is TikTok's account switcher, the tab's listener posts the tab tag and
 * one handler checks it is "USER" before it shows the account sheet. The patch asks the extension
 * at the top of that handler with the tag in p1, and returns before the sheet on a yes, so
 * TikTok's listener never has to leave the tab.
 */
class ProfileTabSwitcherAnchorsTest {
    @Test
    fun `one handler on every fixture checks p1 is Profile's tag and then shows the account sheet`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = ArrayList<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (ProfileTabSwitcherFingerprint.takes(method, classDef)) taken += classDef to method
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val handler = taken.single().second
            assertFalse("${apk.name}: the handler is static, so p1 would not be the tag",
                AccessFlags.STATIC.isSet(handler.accessFlags))
            val implementation = handler.implementation!!
            assertTrue("${apk.name}: the handler has no local register for the answer",
                implementation.registerCount - 2 >= 1)
            val tagRegister = implementation.registerCount - 1

            val instructions = implementation.instructions.toList()
            val profileTag = instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == "USER"
            }
            val compared = instructions.drop(profileTag + 1).firstOrNull {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name != null
            }
            assertTrue("${apk.name}: the handler no longer compares p1 with Profile's tag first: $compared",
                profileTag in 0..1 && compared is FiveRegisterInstruction && compared.registerC == tagRegister)
            assertTrue("${apk.name}: the handler no longer shows the account sheet",
                instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "showInChooseAccountBottomSheet"
                })
        }
    }

    /**
     * Long-press Profile to switch accounts answers TikTok's switch_account_gesture read (#388): one
     * static `()Z` on every fixture, which both the switcher handler and the bottom bar call, the
     * bar right before it puts the hold on Profile's tab.
     */
    @Test
    fun `one static gesture read on every fixture gates both the handler and the Profile tab hold`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = ArrayList<Pair<ClassDef, Method>>()
            val methods = ArrayList<Method>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        methods += method
                        if (SwitchAccountGestureFingerprint.takes(method, classDef)) taken += classDef to method
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val (owner, read) = taken.single()
            assertTrue("${apk.name}: the gesture read isn't static", AccessFlags.STATIC.isSet(read.accessFlags))

            fun callsRead(method: Method) = method.implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    it.definingClass == owner.type && it.name == read.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
                } == true
            } == true
            assertTrue("${apk.name}: the switcher handler no longer asks the gesture read",
                methods.filter { callsRead(it) }.any { method ->
                    method.implementation!!.instructions.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "switch_account_long_press"
                    }
                })
            assertTrue("${apk.name}: no bottom bar code asks the gesture read before setting a long press",
                methods.filter { callsRead(it) }.any { method ->
                    method.implementation!!.instructions.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setOnLongClickListener"
                    }
                })
        }
    }
}
