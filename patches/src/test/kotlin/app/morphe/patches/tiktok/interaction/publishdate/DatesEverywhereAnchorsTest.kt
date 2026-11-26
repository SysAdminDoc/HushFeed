/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.ControlFlow
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Use this date format everywhere (#536) rewrites a search result card's date and a chat's
 * date headers, held to every declared build.
 *
 * <p>SearchCardCreateTimeAssem's bind reads the card's video, hands its createTime in milliseconds
 * to TikTok's age formatter and writes the String it returns on the card's date view; the hook goes
 * right before that write. DateSeparatorPowerCell's bind casts its item, reads the header text
 * TikTok formatted when it laid the chat out from one of the item's String fields and writes it;
 * the hook goes right before that write too, and reads the item's createdAt from the description
 * Kotlin gave the item's data class.
 */
class DatesEverywhereAnchorsTest {
    private fun classesOf(apk: File): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
    }

    private fun List<ClassDef>.taken(type: String, take: (Method, ClassDef) -> Boolean) =
        filter { it.type == type }.flatMap { cls -> cls.methods.filter { take(it, cls) } }

    private fun branchedOnto(method: Method, index: Int): Boolean {
        val flow = ControlFlow.of(method)
        return flow.normal.indices.any { from -> flow.normal[from].any { it == index && it != from + 1 } }
    }

    private fun assertDateCall(instruction: Instruction, name: String, registers: List<Int>) {
        assertEquals(Opcode.INVOKE_STATIC, instruction.opcode)
        assertEquals(
            "Lapp/morphe/extension/tiktok/publishdate/AlwaysShowPublishDatePatch;->$name" +
                "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;",
            instruction.getReference<MethodReference>().toString(),
        )
        val call = instruction as FiveRegisterInstruction
        assertEquals(registers, listOf(call.registerC, call.registerD).take(call.registerCount))
    }

    @Test
    fun `the search card's date write resolves on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = classesOf(apk).taken(SEARCH_CARD_TIME) { method, cls -> SearchCardDateFingerprint.takes(method, cls) }
            assertEquals("$version: the search card's bind", 1, taken.size)
            val stock = taken.single()
            val site = stock.searchCardDateSite() ?: throw AssertionError("$version: no date write")
            val instructions = stock.implementation!!.instructions.toList()

            val read = instructions.indexOfFirst { it.getReference<MethodReference>()?.name == "getCreateTime" }
            assertEquals("$version: the video the hook hands over is the one whose time was read",
                (instructions[read] as FiveRegisterInstruction).registerC, site.awemeRegister)
            val write = instructions[site.writeIndex] as FiveRegisterInstruction
            assertEquals("$version: setText", "setText", instructions[site.writeIndex].getReference<MethodReference>()!!.name)
            assertEquals("$version: the written text", write.registerD, site.textRegister)
            val formatter = instructions[site.writeIndex - 2].getReference<MethodReference>()!!
            assertTrue("$version: the formatter takes the time", "J" in formatter.parameterTypes.map(CharSequence::toString))
            assertFalse("$version: something branches onto the write", branchedOnto(stock, site.writeIndex))

            val method = MutableMethod(stock)
            val before = method.implementation!!.instructions.map { it.opcode }
            method.hookSearchCardDate()
            val after = method.implementation!!.instructions.toList()
            assertDateCall(after[site.writeIndex], "searchCardDate", listOf(site.textRegister, site.awemeRegister))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[site.writeIndex + 1].opcode)
            assertEquals(site.textRegister, (after[site.writeIndex + 1] as OneRegisterInstruction).registerA)
            assertEquals("$version: TikTok's own code changed", before,
                after.filterIndexed { index, _ -> index != site.writeIndex && index != site.writeIndex + 1 }.map { it.opcode })
            assertEquals("$version", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }

    @Test
    fun `the chat date header's write resolves on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            val taken = classes.taken(CHAT_DATE_CELL) { method, cls -> ChatDateFingerprint.takes(method, cls) }
            assertEquals("$version: the chat date header's bind", 1, taken.size)
            val stock = taken.single()
            val site = stock.chatDateSite() ?: throw AssertionError("$version: no header write")
            val instructions = stock.implementation!!.instructions.toList()

            assertEquals("$version: the item is the bind's parameter", stock.implementation!!.registerCount - 1, site.itemRegister)
            val read = (0 until site.writeIndex).last { index ->
                instructions[index].opcode == Opcode.IGET_OBJECT &&
                    (instructions[index] as TwoRegisterInstruction).registerA == site.textRegister
            }
            val field = instructions[read].getReference<FieldReference>()!!
            assertEquals("$version: the header text is the item's own", site.itemType, field.definingClass)
            assertEquals("$version: the header text", "Ljava/lang/String;", field.type)
            assertFalse("$version: something branches onto the write", branchedOnto(stock, site.writeIndex))

            val item = classes.single { it.type == site.itemType }
            assertTrue("$version: ${site.itemType} doesn't describe its createdAt", item.describesChatDate())
            assertTrue("$version: the item has no long to describe",
                item.fields.any { it.type == "J" })

            val method = MutableMethod(stock)
            val before = method.implementation!!.instructions.map { it.opcode }
            method.hookChatDate { type -> classes.firstOrNull { it.type == type } }
            val after = method.implementation!!.instructions.toList()
            assertDateCall(after[site.writeIndex], "chatDate", listOf(site.textRegister, site.itemRegister))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[site.writeIndex + 1].opcode)
            assertEquals(site.textRegister, (after[site.writeIndex + 1] as OneRegisterInstruction).registerA)
            assertEquals("$version: TikTok's own code changed", before,
                after.filterIndexed { index, _ -> index != site.writeIndex && index != site.writeIndex + 1 }.map { it.opcode })
            assertEquals("$version", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }
}
