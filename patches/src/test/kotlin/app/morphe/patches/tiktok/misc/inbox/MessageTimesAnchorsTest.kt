/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.takes
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Show message times hooks, held to each declared build: in both message row slots the one bind
 * that pads the row, the item still in its parameter register after the padding, one way from the
 * item to an IM message with `getCreatedAt()J`, and the hook going in right after the padding with
 * TikTok's code around it unchanged. The bridge that reads the time is built on the extension's
 * stub the way the patch builds it.
 */
class MessageTimesAnchorsTest {
    private val patch = "Hide inbox items"

    @Test
    fun `each build binds both message slots through one padded bind, and the hook fits`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val fingerprints = listOf(IMSkeletonRowBindFingerprint, SkeletonRowBindFingerprint)
            val binds = MESSAGE_ROW_SLOTS.zip(fingerprints).map { (slot, fingerprint) ->
                val classDef = classes[slot] ?: error("$version: $slot is missing")
                val taken = classDef.methods.filter { fingerprint.takes(it, classDef) }
                assertEquals("$version: $slot binds ${taken.map { it.name }}", 1, taken.size)
                val method = taken.single()
                val bind = requireMessageRowBind(patch, method) { classes[it] }
                checkHook(version, method, bind)
                bind
            }
            val path = binds.map { it.path }.distinct().singleOrNull()
            assertNotNull("$version: the slots reach the message differently: ${binds.map { it.path }}", path)
            path!!
            val message = classes.getValue(path.message.type)
            assertTrue("$version: ${message.type} is TikTok's IM message", hasCreatedAt(message.type) { classes[it] })
            assertTrue(
                "$version: ${message.type} also has getUuid",
                message.methods.any { it.name == "getUuid" && it.returnType == "Ljava/lang/String;" },
            )
            assertEquals("$version: the holder getter is called the way its class takes it",
                isInterfaceType(path.itemType) { classes[it] }, path.holderIsInterface)
            checkBridge(version, path, isInterfaceType(path.message.type) { classes[it] })
        }
    }

    private fun checkHook(version: String, native: Method, bind: MessageRowBind) {
        val before = native.implementation!!.instructions.toList()
        assertFalse("$version: nothing lands right after the padding", native.isBranchTarget(bind.padding + 1))
        val padding = before[bind.padding] as FiveRegisterInstruction
        assertEquals("$version: the padded row is the setPadding's receiver", padding.registerC, bind.row)
        assertEquals("$version: the item is the last register", native.implementation!!.registerCount - 1, bind.item)

        val patched = MutableMethod(native)
        patched.reportMessageRow(bind)
        val after = patched.implementation!!.instructions.toList()
        assertEquals("$version: one instruction added", before.size + 1, after.size)
        assertEquals(before.take(bind.padding + 1).map { it.opcode }, after.take(bind.padding + 1).map { it.opcode })
        assertEquals(before.drop(bind.padding + 1).map { it.opcode }, after.drop(bind.padding + 2).map { it.opcode })
        val call = after[bind.padding + 1] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        val target = after[bind.padding + 1].getReference<MethodReference>()!!
        assertEquals("$MESSAGE_TIMES->bound(Landroid/view/View;Ljava/lang/Object;)V", target.toString())
        assertEquals(2, call.registerCount)
        assertEquals("$version: the row TikTok padded", padding.registerC, call.registerC)
        assertEquals("$version: and the bound item", bind.item, call.registerD)
    }

    /** The bridge on a stub shaped like the extension's `createdAt(Object)J`: two locals, then p0. */
    private fun checkBridge(version: String, path: MessagePath, messageIsInterface: Boolean) {
        val stub = MutableMethod(
            ImmutableMethod(
                MESSAGE_TIMES, "createdAt", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "J",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        ).apply {
            addInstructions(
                """
                    const-wide/16 v0, 0x0
                    return-wide v0
                """,
            )
        }
        val bridge = stub.cloneMutable(additionalRegisters = 2)
        bridge.writeMessageTimeBridge(path, messageIsInterface)
        val code = bridge.implementation!!.instructions.toList()
        val parameter = bridge.implementation!!.registerCount - 1

        assertEquals(Opcode.INSTANCE_OF, code[0].opcode)
        assertEquals(path.itemType, code[0].getReference<TypeReference>()!!.type)
        assertEquals(Opcode.CHECK_CAST, code[2].opcode)
        val holder = code[3] as FiveRegisterInstruction
        assertEquals(if (path.holderIsInterface) Opcode.INVOKE_INTERFACE else Opcode.INVOKE_VIRTUAL, holder.opcode)
        assertEquals("$version: the holder is read off the item", parameter, holder.registerC)
        assertEquals(path.holder.toString(), code[3].getReference<MethodReference>().toString())
        assertEquals(path.inner.toString(), code[6].getReference<FieldReference>().toString())
        assertEquals(path.message.toString(), code[8].getReference<FieldReference>().toString())
        val time = code[10].getReference<MethodReference>()!!
        assertEquals("${path.message.type}->getCreatedAt()J", time.toString())
        assertEquals(if (messageIsInterface) Opcode.INVOKE_INTERFACE else Opcode.INVOKE_VIRTUAL, code[10].opcode)
        assertEquals(Opcode.MOVE_RESULT_WIDE, code[11].opcode)
        assertEquals(Opcode.RETURN_WIDE, code[12].opcode)
        assertEquals("$version: nothing found answers 0", listOf(Opcode.CONST_WIDE_16, Opcode.RETURN_WIDE), code.subList(13, 15).map { it.opcode })
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
    }

    @Test
    fun `a bind that writes the item before the padding, or pads twice, is not taken`() {
        val found = bind(writesItem = false, paddings = 1).messageRowBind { it == MESSAGE_TYPE }
        assertNotNull(found)
        assertEquals(11, found!!.padding)
        assertEquals(1, found.row)
        assertEquals(3, found.item)
        assertEquals("$INNER->LIZ:$MESSAGE_TYPE", found.path.message.toString())
        assertNull("the item register reused", bind(writesItem = true, paddings = 1).messageRowBind { it == MESSAGE_TYPE })
        assertNull("two paddings", bind(writesItem = false, paddings = 2).messageRowBind { it == MESSAGE_TYPE })
        assertNull("no message on the way", bind(writesItem = false, paddings = 1).messageRowBind { false })
    }

    private val ITEM = "LX/0jYx;"
    private val HOLDER = "LX/0jcn;"
    private val INNER = "LX/0jcU;"
    private val MESSAGE_TYPE = "LX/0CmB;"
    private val SPACING = "LX/0jcQ;"

    /**
     * A bind cut down to the part the reader looks at, as 47.7.3 has it, in four registers: the
     * message read (indexes 0-3), TikTok's spacing read into v0 (4-8), the row into v1 (9-10), and
     * setPadding on the row at index 11. p1 is v3.
     */
    private fun bind(writesItem: Boolean, paddings: Int): Method {
        val padding = "invoke-virtual { v1, v0, v0, v0, v0 }, Landroid/view/View;->setPadding(IIII)V"
        val method = MutableMethod(
            ImmutableMethod(
                MESSAGE_ROW_SLOTS[0], "Rq", listOf(ImmutableMethodParameter(ITEM, null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        )
        method.addInstructions(
            """
                invoke-interface { p1 }, $ITEM->LLJZIJLIL()$HOLDER
                move-result-object v0
                iget-object v0, v0, $HOLDER->LIZ:$INNER
                iget-object v0, v0, $INNER->LIZ:$MESSAGE_TYPE
                invoke-interface { p1 }, $ITEM->LLJZIJLIL()$HOLDER
                ${if (writesItem) "move-result-object p1" else "move-result-object v0"}
                iget-object v0, v0, $HOLDER->LIZIZ:$SPACING
                invoke-interface { v0 }, $SPACING->getPaddingDp()I
                move-result v0
                invoke-virtual { p0 }, ${MESSAGE_ROW_SLOTS[0]}->Lq()Landroid/view/View;
                move-result-object v1
                $padding
                ${if (paddings > 1) padding else "nop"}
                return-void
            """,
        )
        return method
    }
}
