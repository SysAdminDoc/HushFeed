/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Use this date format everywhere (#536) writes into a search card's bind and a chat date
 * header's bind, on methods shaped like 47.9.3's: the extension call right before the date's
 * write, its answer back in the written register, every branch still landing where it did, and
 * the shapes it refuses rather than hook blind.
 */
class DatesEverywhereHookTest {
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    private val card = "Lsearch/CardItem;"
    private val ages = "Lsearch/Ages;"
    private val item = "Lchat/DateItem;"
    private val textView = "Landroid/widget/TextView;"
    private val setText = "$textView->setText(Ljava/lang/CharSequence;)V"
    private val extension = "Lapp/morphe/extension/tiktok/publishdate/AlwaysShowPublishDatePatch;"

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) =
        MutableMethod(ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).apply { addInstructionsWithLabels(0, smali) }

    /** 47.9.3's SearchCardCreateTimeAssem.A4, with [middle] between the formatter and the write. */
    private fun searchCard(middle: String = "", beforeRead: String = "") = method(SEARCH_CARD_TIME, "bind", listOf("Ljava/lang/Object;"), "V", 10, """
        check-cast p1, $card
        invoke-interface { p1 }, $card->getAweme()$aweme
        move-result-object v7
        iget-object v6, p0, $SEARCH_CARD_TIME->date:$textView
        invoke-virtual { v6 }, Landroid/view/View;->getContext()Landroid/content/Context;
        move-result-object v5
        if-eqz v5, :end
        $beforeRead
        invoke-virtual { v7 }, $aweme->getCreateTime()J
        move-result-wide v2
        const-wide/16 v0, 0x3e8
        mul-long/2addr v2, v0
        $middle
        invoke-static { v2, v3, v5 }, $ages->ago(JLandroid/content/Context;)Ljava/lang/String;
        move-result-object v0
        :write
        invoke-virtual { v6, v0 }, $setText
        invoke-virtual { v7 }, $aweme->isAd()Z
        :end
        return-void
    """)

    /** 47.9.3's DateSeparatorPowerCell.onBindItemView, the header text in v2 and the item in p1. */
    private fun chatHeader(text: String = "iget-object v2, p1, $item->formattedTimestamp:Ljava/lang/String;") =
        method(CHAT_DATE_CELL, "onBindItemView", listOf("Lpower/Item;"), "V", 8, """
            check-cast p1, $item
            $text
            const/4 v1, 0x0
            if-eqz v2, :end
            invoke-virtual { v2 }, Ljava/lang/String;->length()I
            move-result v0
            if-eqz v0, :end
            iget-object v0, p0, $CHAT_DATE_CELL->date:$textView
            invoke-virtual { v0, v2 }, $setText
            invoke-virtual { v0, v1 }, Landroid/view/View;->setVisibility(I)V
            :end
            return-void
        """)

    /** The chat item, describing itself the way Kotlin writes a data class's toString. */
    private fun dateItem(vararg strings: String): ClassDef {
        val describe = method(item, "toString", emptyList(), "Ljava/lang/String;", 2,
            strings.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-object v0")
        return ImmutableClassDef(item, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(describe))
    }

    private val describedItem = dateItem("DateViewHolderData(uuid=", ", createdAt=", ", formattedTimestamp=")

    private fun MutableMethod.instructions(): List<Instruction> = implementation!!.instructions.toList()

    private fun List<Instruction>.indexOfCall(name: String) = indexOfFirst { it.getReference<MethodReference>()?.name == name }

    private fun assertExtensionCall(instruction: Instruction, name: String, registers: List<Int>) {
        assertEquals(Opcode.INVOKE_STATIC, instruction.opcode)
        assertEquals("$extension->$name(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;",
            instruction.getReference<MethodReference>().toString())
        val call = instruction as FiveRegisterInstruction
        assertEquals(registers.size, call.registerCount)
        assertEquals(registers, listOf(call.registerC, call.registerD))
    }

    private fun Instruction.targetIndex() = (this as BuilderOffsetInstruction).target.location.index

    @Test
    fun `the search card's date goes through the extension right before it's written`() {
        val method = searchCard()
        val site = method.searchCardDateSite()!!
        assertEquals(CardDateSite(method.instructions().indexOfCall("setText"), 0, 7), site)

        method.hookSearchCardDate()
        val after = method.instructions()
        val write = after.indexOfCall("setText")
        assertEquals("two instructions go in before the write", site.writeIndex + 2, write)
        assertExtensionCall(after[write - 2], "searchCardDate", listOf(0, 7))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, after[write - 1].opcode)
        assertEquals(0, (after[write - 1] as OneRegisterInstruction).registerA)
        assertEquals(0, (after[write] as FiveRegisterInstruction).registerD)
        // The formatter's own answer still comes first.
        assertEquals("ago", after[write - 4].getReference<MethodReference>()!!.name)
        // The skip past the date still lands on the return.
        val skip = after.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals(Opcode.RETURN_VOID, after[after[skip].targetIndex()].opcode)
    }

    @Test
    fun `a search card that writes anything but the formatter's text is left alone`() {
        val constant = method(SEARCH_CARD_TIME, "bind", listOf("Ljava/lang/Object;"), "V", 10, """
            check-cast p1, $card
            invoke-interface { p1 }, $card->getAweme()$aweme
            move-result-object v7
            iget-object v6, p0, $SEARCH_CARD_TIME->date:$textView
            invoke-virtual { v7 }, $aweme->getCreateTime()J
            move-result-wide v2
            const-string v0, ""
            invoke-virtual { v6, v0 }, $setText
            return-void
        """)
        assertNull(constant.searchCardDateSite())
        val refused = assertThrows(PatchException::class.java) { constant.hookSearchCardDate() }
        assertTrue(refused.message, refused.message!!.contains("isn't where it was"))
    }

    @Test
    fun `a search card that writes over its video before the date is left alone`() {
        assertNull(searchCard(middle = "const/4 v7, 0x0").searchCardDateSite())
    }

    @Test
    fun `a search card that branches onto its date write is refused`() {
        val method = searchCard(beforeRead = "const-string v0, \"\"\n        if-eqz v6, :write")
        assertTrue(method.searchCardDateSite() != null)
        val refused = assertThrows(PatchException::class.java) { method.hookSearchCardDate() }
        assertTrue(refused.message, refused.message!!.contains("branches onto"))
    }

    @Test
    fun `a chat date header's text goes through the extension with its item`() {
        val method = chatHeader()
        val site = method.chatDateSite()!!
        assertEquals(ChatDateSite(method.instructions().indexOfCall("setText"), 2, 7, item), site)

        method.hookChatDate { type -> describedItem.takeIf { it.type == type } }
        val after = method.instructions()
        val write = after.indexOfCall("setText")
        assertEquals("two instructions go in before the write", site.writeIndex + 2, write)
        assertExtensionCall(after[write - 2], "chatDate", listOf(2, 7))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, after[write - 1].opcode)
        assertEquals(2, (after[write - 1] as OneRegisterInstruction).registerA)
        assertEquals(2, (after[write] as FiveRegisterInstruction).registerD)
        // Both empty checks still skip to the return.
        after.filter { it.opcode == Opcode.IF_EQZ }.forEach { skip ->
            assertEquals(Opcode.RETURN_VOID, after[skip.targetIndex()].opcode)
        }
    }

    @Test
    fun `a chat header whose item no longer describes its createdAt is refused`() {
        val method = chatHeader()
        val renamed = dateItem("DateViewHolderData(uuid=", ", formattedTimestamp=")
        val refused = assertThrows(PatchException::class.java) {
            method.hookChatDate { type -> renamed.takeIf { it.type == type } }
        }
        assertTrue(refused.message, refused.message!!.contains("createdAt"))
        assertThrows(PatchException::class.java) { chatHeader().hookChatDate { null } }
    }

    @Test
    fun `a chat header that writes text from anywhere but its item is left alone`() {
        assertNull(chatHeader(text = "const-string v2, \"Today\"").chatDateSite())
        assertNull(chatHeader(text = "iget-object v2, p0, $CHAT_DATE_CELL->cached:Ljava/lang/String;").chatDateSite())
        val refused = assertThrows(PatchException::class.java) {
            chatHeader(text = "const-string v2, \"Today\"").hookChatDate { describedItem }
        }
        assertTrue(refused.message, refused.message!!.contains("isn't where it was"))
    }
}
