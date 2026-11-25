package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private const val PUBLIC_SCREENS = "Lcom/bytedance/android/live/publicscreen/impl/widget/"

/** #525: every kind of LIVE room's chat loads through the one method the hook sits in. */
class LiveChatAnchorsTest {
    @Test
    fun `each declared build loads every LIVE chat through the hooked onLoad, which hands over its own view`() {
        Fixtures.forEachDeclared { apk ->
            val classes = Fixtures.dexContainer(apk, Opcodes.getDefault()).let { container ->
                container.dexEntryNames.asSequence()
                    .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            }
            val taken = classes.flatMap { cls -> cls.methods.filter { LivePublicScreenLoadFingerprint.takes(it, cls) } }
            assertEquals("${apk.name}: ${taken.map { it.definingClass }}", 1, taken.size)
            val load = taken.single()

            // The chats a room picks from, each running the base load first from its own.
            val chats = classes.filter { it.superclass == LIVE_PUBLIC_SCREEN }
            assertEquals(
                "${apk.name}: the chats",
                setOf("Portrait", "Landscape", "Extended", "GameDualDevice")
                    .map { "${PUBLIC_SCREENS}${it}PublicScreenWidget;" }.toSet(),
                chats.map { it.type }.toSet(),
            )
            // The extended and game chats replace the base load without running it, so the base
            // hook alone left their chat showing; theirs are hooked too.
            val skipping = liveChatLoadsSkippingTheBase(chats, load)
            assertEquals(
                "${apk.name}: the chats whose own load skips the base",
                setOf("Extended", "GameDualDevice").map { "${PUBLIC_SCREENS}${it}PublicScreenWidget;" }.toSet(),
                skipping.map { it.definingClass }.toSet(),
            )
            assertEquals(2, skipping.size)
            for (chat in chats.filter { chat -> skipping.none { it.definingClass == chat.type } }) {
                val own = chat.methods.single {
                    it.name == load.name && it.parameterTypes.map(CharSequence::toString) ==
                        load.parameterTypes.map(CharSequence::toString)
                }
                val first = own.implementation!!.instructions.first()
                assertEquals("${chat.type}: runs the base load first", Opcode.INVOKE_SUPER, first.opcode)
                val called = first.getReference<MethodReference>()!!
                assertEquals(chat.type, "$LIVE_PUBLIC_SCREEN->onLoad", "${called.definingClass}->${called.name}")
            }

            for (method in listOf(load) + skipping) assertHandsOverItsView(method)
        }
    }

    private fun assertHandsOverItsView(load: Method) {
        val hooked = MutableMethod(load)
        val before = hooked.implementation!!.instructions.toList()
        hooked.hookLiveChatLoad()
        val built = hooked.implementation!!.instructions.toList()
        assertEquals(before.size + 2, built.size)
        val read = built[0]
        assertEquals(Opcode.IGET_OBJECT, read.opcode)
        val field = read.getReference<FieldReference>()!!
        assertEquals("Lcom/bytedance/android/widget/Widget;->contentView:Landroid/view/View;",
            "${field.definingClass}->${field.name}:${field.type}")
        val receiver = load.implementation!!.registerCount - 3
        assertEquals("the widget itself", receiver, (read as TwoRegisterInstruction).registerB)
        assertEquals(0, read.registerA)
        val call = built[1]
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        val handed = call.getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/live/LiveChat;->onLoad(Landroid/view/View;)V",
            "${handed.definingClass}->${handed.name}(${handed.parameterTypes.joinToString("")})${handed.returnType}")
        assertEquals(listOf(0), call.namedRegisters())
        before.forEachIndexed { index, instruction -> assertSame(instruction, built[index + 2]) }
    }
}
