/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep likes on this phone: on each declared build the extension is asked at the executeCall
 * lancet's send, after the LIVE enter hold, and a kept like's answer is cast to the lancet's type
 * and returned in place of the send's, inside the send's try. Aweme's getUserDigg hands every
 * return to the extension, and setUserDigg hands over each value before it's set.
 */
class LocalLikesHookTest {
    private val answer = "$LOCAL_LIKES_EXTENSION->answer(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    private val hold = "$LIVE_UNSEEN_EXTENSION->holdEnter(Ljava/lang/Object;Ljava/lang/Object;)V"
    private val read = "$LOCAL_LIKES_EXTENSION->userDigg(Ljava/lang/Object;I)I"
    private val write = "$LOCAL_LIKES_EXTENSION->setUserDigg(Ljava/lang/Object;I)V"

    @Test
    fun `each declared build answers kept likes at the send, after the LIVE hold, inside its try`() {
        Fixtures.forEachDeclared { apk ->
            val lancet = classes(apk)
                .filter { it.type == CALL_SERVER_INTERCEPTOR }
                .flatMap { it.methods }
                .filter { it.name == NETWORK_EXECUTE_CALL && it.implementation != null }
                .distinctBy { it.toString() }
                .single()
            val stockCode = lancet.implementation!!.instructions.toList()
            val stockSend = stockCode[stockCode.indexOfFirst { it.isSend() }] as FiveRegisterInstruction
            val result = (stockCode[stockCode.indexOfFirst { it.isSend() } + 1] as OneRegisterInstruction).registerA

            val method = MutableMethod(lancet)
            method.holdLiveEnters(planLiveEnterHold(method))
            method.answerLikesLocally(planLocalLikeAnswer(method))
            val patched = method.implementation!!
            val code = patched.instructions.toList()

            val ask = code.indexOfFirst { it.reference() == answer }
            assertEquals("${apk.name}: the LIVE hold comes first", ask - 1, code.indexOfFirst { it.reference() == hold })
            val call = code[ask] as FiveRegisterInstruction
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals(listOf(stockSend.registerC, stockSend.registerD), listOf(call.registerC, call.registerD))
            assertEquals(2, call.registerCount)
            assertEquals(
                listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.RETURN_OBJECT),
                code.subList(ask + 1, ask + 5).map { it.opcode },
            )
            for (index in ask + 1..ask + 4) {
                assertEquals("${apk.name}: the answer stays in the send's own register", result,
                    (code[index] as OneRegisterInstruction).registerA)
            }
            assertEquals(lancet.returnType, code[ask + 3].reference())
            assertTrue("${apk.name}: the send follows", code[ask + 5].isSend())
            val branch = code[ask + 2] as OffsetInstruction
            assertEquals("${apk.name}: no answer goes on to the send",
                address(code, ask + 5), address(code, ask + 2) + branch.codeOffset)

            val first = address(code, ask - 1)
            val last = address(code, ask + 4)
            assertTrue(
                "${apk.name}: the answer is inside the send's try, which takes an Exception",
                patched.tryBlocks.any { block ->
                    first >= block.startCodeAddress && last < block.startCodeAddress + block.codeUnitCount &&
                        block.exceptionHandlers.any { it.exceptionType == "Ljava/lang/Exception;" }
                },
            )
            val added = setOf(ask - 1, ask, ask + 1, ask + 2, ask + 3, ask + 4)
            assertEquals(
                stockCode.map { it.opcode.kind() },
                code.filterIndexed { index, _ -> index !in added }.map { it.opcode.kind() },
            )
        }
    }

    @Test
    fun `each declared build reads and sets a video's like through the extension`() {
        Fixtures.forEachDeclared { apk ->
            val aweme = classes(apk).filter { it.type == AWEME }.distinctBy { it.type }.single()
            val (stockRead, stockWrite) = planLikeState(aweme.methods.filter { it.implementation != null })

            val getter = MutableMethod(stockRead)
            getter.readKeptLikes()
            val self = stockRead.implementation!!.registerCount - 1
            val code = getter.implementation!!.instructions.toList()
            val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }
            assertEquals(stockRead.implementation!!.instructions.count { it.opcode == Opcode.RETURN }, returns.size)
            for (index in returns) {
                val value = (code[index] as OneRegisterInstruction).registerA
                val call = code[index - 2] as FiveRegisterInstruction
                assertEquals("${apk.name}: every return asks the extension", read, code[index - 2].reference())
                assertEquals(listOf(self, value), listOf(call.registerC, call.registerD))
                assertEquals(Opcode.MOVE_RESULT, code[index - 1].opcode)
                assertEquals(value, (code[index - 1] as OneRegisterInstruction).registerA)
            }

            val setter = MutableMethod(stockWrite)
            setter.noteLikeChanges()
            val registers = stockWrite.implementation!!.registerCount
            val first = setter.implementation!!.instructions.first() as FiveRegisterInstruction
            assertEquals(write, (first as Instruction).reference())
            assertEquals(listOf(registers - 2, registers - 1), listOf(first.registerC, first.registerD))
            assertEquals(
                stockWrite.implementation!!.instructions.map { it.opcode },
                setter.implementation!!.instructions.drop(1).map { it.opcode },
            )
        }
    }

    @Test
    fun `a send whose answer isn't handed straight back stops the patch`() {
        val send = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
            ImmutableMethodReference(CALL_SERVER_INTERCEPTOR, NETWORK_EXECUTE_CALL_TWIN, listOf("LX/chain;"), "LX/raw;"),
        )
        val lancet: Method = ImmutableMethod(
            CALL_SERVER_INTERCEPTOR,
            NETWORK_EXECUTE_CALL,
            listOf(ImmutableMethodParameter(CALL_SERVER_INTERCEPTOR, null, null), ImmutableMethodParameter("LX/chain;", null, null)),
            "LX/raw;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(2, listOf(send, ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        assertThrows(PatchException::class.java) { planLocalLikeAnswer(lancet) }
    }

    private fun classes(apk: java.io.File) = Fixtures.dexContainer(apk, Opcodes.getDefault()).let { container ->
        container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
    }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.isSend() =
        opcode == Opcode.INVOKE_VIRTUAL && reference().orEmpty().contains("->$NETWORK_EXECUTE_CALL_TWIN(")

    private fun Opcode.kind() = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this

    private fun address(code: List<Instruction>, index: Int) = code.take(index).sumOf { it.codeUnits }
}
