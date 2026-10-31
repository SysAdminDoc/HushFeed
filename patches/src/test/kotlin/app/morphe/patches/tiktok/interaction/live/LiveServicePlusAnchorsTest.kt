package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #94: LIVE controls runs the For You LIVE preview's Service+ row hide when the row is bound to a
 * LIVE with no Service+ item while it still shows the last one. Each declared build has the row
 * under its real name with one bind that stores the room's preview right before a plain show and
 * a return, and one hide that clears the row's callbacks and hides it. The hide has to be the one
 * TikTok runs as a preview leaves the screen, or the hook would leave the row in a state TikTok
 * never puts it in.
 *
 * The show is also entered without a bind, with the stored preview, so its entry gets a hook that
 * reads the current room through the bind's holder call and room field, stores that preview and
 * hides the row when the LIVE has no Service+ item or the switch is on.
 */
class LiveServicePlusAnchorsTest {
    @Test
    fun `each declared build has the Service+ row bind and hide the hook writes around`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val rows = mutableListOf<ClassDef>()
            for (entry in container.dexEntryNames) {
                container.getEntry(entry)!!.dexFile.classes.filterTo(rows) { it.type == SERVICE_PLUS_ROW }
            }
            assertEquals("${apk.name}: Service+ row classes", 1, rows.size)
            val row = rows.single()

            val bind = servicePlusBind(row)
            assertNotEquals("${apk.name}: the bind's preview and row share a register", bind.row, bind.preview)
            assertEquals("${apk.name}: the row is p0 of a no-argument method",
                bind.method.implementation!!.registerCount - 1, bind.row)
            assertNotEquals("${apk.name}: the hide is the bind", bind.method.name, bind.hide.name)

            // TikTok's own reset, the method a preview leaving the screen runs, calls the hide.
            val callers = row.methods.filter { method ->
                method.implementation?.instructions?.toList().orEmpty().any {
                    it.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((it as ReferenceInstruction).reference as MethodReference).let { call ->
                            call.definingClass == SERVICE_PLUS_ROW && call.name == bind.hide.name &&
                                call.parameterTypes.isEmpty()
                        }
                }
            }
            assertTrue("${apk.name}: nothing in the row runs ${bind.hide.name}", callers.size >= 2)

            // The show right after the store returns without hiding the row when the preview is
            // null, which is the stale row this covers. It reads the stored preview, and the hook
            // writes the current room's preview into it first, through the holder call and room
            // field the bind uses.
            val show = (bind.method.implementation!!.instructions.toList()[bind.store + 1] as ReferenceInstruction)
                .reference as MethodReference
            assertEquals("${apk.name}: the show the bind runs", show.name, bind.show.name)
            assertTrue("${apk.name}: ${bind.show.name} no longer reads the stored preview",
                bind.show.implementation!!.instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { field ->
                        field.name == bind.stored.name && field.definingClass == SERVICE_PLUS_ROW
                    } == true
                })
            assertEquals("${apk.name}: the stored preview's type", SMB_LIVE_PREVIEW, bind.stored.type)
            assertEquals("${apk.name}: the room field's owner", bind.roomHolder.returnType, bind.roomField.definingClass)
            assertEquals("${apk.name}: the room field's type", LIVE_ROOM_STRUCT, bind.roomField.type)
            assertTrue("${apk.name}: the holder call takes arguments", bind.roomHolder.parameterTypes.isEmpty())

            // The show hook as the patch writes it: fourteen instructions, one branch (the hide)
            // landing on TikTok's first instruction, and typing the verifier takes.
            val original = bind.show.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.toList()
            val hooked = MutableMethod(bind.show)
            hooked.hookServicePlusShow(bind)
            val raw = hooked.implementation!!.instructions.toList()
            val code = raw.filter { it.opcode != Opcode.NOP }
            assertEquals("${apk.name}: hook length", original.size + 14, code.size)
            assertEquals(Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
            val calls = code.take(14).mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name }
            assertEquals("${apk.name}: the hook's calls",
                listOf(bind.roomHolder.name, "servicePlusPreview", "showServicePlusRow", bind.hide.name), calls)
            assertEquals("${apk.name}: one branch", 1, code.take(14).count { it is OffsetInstruction })
            assertEquals("${apk.name}: TikTok's show follows the hook", original[0].opcode, code[14].opcode)
            val landing = (14 until raw.size).first { raw[it].opcode != Opcode.NOP }
            assertEquals("${apk.name}: the hide branch lands on TikTok's show",
                raw.subList(11, landing).sumOf { it.codeUnits }, (raw[11] as OffsetInstruction).codeOffset)
            assertEquals("${apk.name}: ${bind.show.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }
}
