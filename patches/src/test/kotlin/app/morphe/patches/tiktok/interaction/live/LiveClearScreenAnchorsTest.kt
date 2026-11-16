package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #57: Offer clear screen in every LIVE room answers true at the entry of TikTok's server setting
 * for the clear screen menu entry. Each declared build has the one getter, reading
 * live_clear_screen_feature_entry_completion with a spare register for the guard, and the portrait
 * room's panel builder still gates its clear screen entry on it and turns clear screen on and off
 * through IClearScreenService.
 */
class LiveClearScreenAnchorsTest {
    private val clearScreenService = "Lcom/bytedance/android/livesdk/watch/clearscreen/IClearScreenService;"
    private val portraitRoom = "Lcom/bytedance/android/livesdk/chatroom/ui/PortraitAudienceInteractionFragment;"

    @Test
    fun `each declared build has the clear screen entry setting and the room menu that reads it`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val taken = classes.flatMap { cls -> cls.methods.filter { LiveClearScreenEntryFingerprint.takes(it, cls) } }
            assertEquals("${apk.name}: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val getter = taken.single()
            assertTrue("${apk.name}: no spare register for the guard", getter.implementation!!.registerCount >= 1)
            assertTrue(
                "${apk.name}: the getter no longer reads live_clear_screen_feature_entry_completion",
                getter.implementation!!.instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "live_clear_screen_feature_entry_completion"
                },
            )

            val menus = classes.single { it.type == portraitRoom }.methods.filter { method ->
                val calls = method.implementation?.instructions?.toList().orEmpty()
                    .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                calls.any { it.definingClass == LIVE_CLEAR_SCREEN_ENTRY_SETTING && it.name == "getValue" } &&
                    calls.any { it.definingClass == clearScreenService }
            }
            assertEquals("${apk.name}: portrait room menus gated on the clear screen entry", 1, menus.size)
        }
    }

    @Test
    fun `each declared build keeps clear screen for the session through the guarded getter`() {
        val service = "Lcom/bytedance/android/livesdk/clearscreen/ClearScreenService;"
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val taken = classes.flatMap { cls -> cls.methods.filter { LiveClearScreenSessionFingerprint.takes(it, cls) } }
            assertEquals("${apk.name}: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val getter = taken.single()
            assertTrue(
                "${apk.name}: the getter no longer reads live_clear_screen_session_persistence",
                getter.implementation!!.instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "live_clear_screen_session_persistence"
                },
            )
            // The save on each change and the hand-over to a new room's controller both ask it.
            val askers = classes.single { it.type == service }.methods.filter { method ->
                method.implementation?.instructions?.toList().orEmpty().any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == LIVE_CLEAR_SCREEN_SESSION_SETTING && call.name == "getValue"
                }
            }.map { it.name }.toSet()
            assertEquals("${apk.name}: ClearScreenService readers $askers", 2, askers.size)

            val patched = MutableMethod(getter)
            patched.guardAtEntry(
                "test",
                "invoke-static {}, Lapp/morphe/extension/tiktok/live/LiveControls;->keepClearScreen()Z",
                "const/4 v0, 0x1\nreturn v0",
            )
            val first = patched.implementation!!.instructions.first()
            assertEquals(
                "${apk.name}: the guard is not first",
                "keepClearScreen",
                ((first as? ReferenceInstruction)?.reference as? MethodReference)?.name,
            )
            assertEquals("${apk.name}: the guard's types", emptyList<Any>(), BridgeTypeFlow.check(patched))
        }
    }

    @Test
    fun `each declared build exposes the live controller state change and initialized state`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val taken = classes.flatMap { cls ->
                cls.methods.filter { LiveClearScreenControllerFingerprint.takes(it, cls) }
            }
            assertEquals("${apk.name}: ${taken.map { it.definingClass }}", 1, taken.size)
            val initialized = taken.single()
            val controller = classes.single { it.type == initialized.definingClass }
            assertTrue("${apk.name}: controller has no room field", controller.fields.any { it.type == LIVE_ROOM_TYPE })
            val changed = controller.methods.single(::liveClearStateChange)
            assertTrue("${apk.name}: change hook has no return", nativeClearReturn(changed) >= 0)
            assertTrue("${apk.name}: constructor hook has no return", nativeClearReturn(initialized) >= 0)
            assertEquals("${apk.name}: no readable clear state", 1, controller.methods.count {
                it.returnType == "Z" && it.parameterTypes.isEmpty()
            })
        }
    }

    /**
     * #57: both full screen buttons pass their answer through the extension at the last return,
     * after TikTok's own gate (LIVE Studio or screen share) and its multi-stream note have run.
     * Every branch onto that return lands on the call, and the early false stays as it was.
     */
    @Test
    fun `each declared build hands both full screen buttons' answers to the extension`() {
        val room = LIVE_ROOM_TYPE
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            listOf(LiveFullVideoButtonAttachFingerprint, LiveFullVideoButtonV2AttachFingerprint).forEach { fingerprint ->
                val taken = classes.flatMap { cls -> cls.methods.filter { fingerprint.takes(it, cls) } }
                assertEquals("${apk.name}: ${taken.map { it.definingClass }}", 1, taken.size)
                val stock = taken.single()
                val reads = stock.implementation!!.instructions.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { field -> field.definingClass == room }?.name
                }.toSet()
                assertTrue("${apk.name}: ${stock.definingClass} gate reads $reads", reads.containsAll(listOf("isThirdParty", "isScreenshot")))

                val hooked = MutableMethod(stock)
                hookFullScreenAttach(hooked)
                val built = hooked.implementation!!.instructions.toList()
                val calls = built.indices.filter {
                    ((built[it] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "offerFullScreen"
                }
                // The method goes on past its last return (the multi-stream false jumps back up), so
                // the answer's return is the last RETURN, not the last instruction.
                val returns = built.indices.filter { built[it].opcode == Opcode.RETURN }
                assertEquals("${apk.name}: the early false and the answer", 2, returns.size)
                val answerReturn = returns.last()
                assertEquals("${apk.name}: one call, right before the answer's return", listOf(answerReturn - 2), calls)
                val call = calls.single()
                val answer = (built[call] as RegisterRangeInstruction).startRegister
                assertEquals(Opcode.MOVE_RESULT, built[call + 1].opcode)
                assertEquals(answer, (built[call + 1] as OneRegisterInstruction).registerA)
                assertEquals(answer, (built[answerReturn] as OneRegisterInstruction).registerA)
                val note = built.indexOfLast {
                    it.opcode == Opcode.IPUT_BOOLEAN && ((it as ReferenceInstruction).reference as FieldReference).definingClass == stock.definingClass
                }
                assertTrue("${apk.name}: the multi-stream note runs before the call", note in 0 until call)
                val targets = built.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }
                assertTrue("${apk.name}: a branch skips the call", targets.none { it == call + 1 || it == answerReturn })
                assertTrue("${apk.name}: no branch lands on the call", call in targets)
                assertEquals("${apk.name}", emptyList<Any>(), BridgeTypeFlow.check(hooked))
            }
        }
    }
}
