package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StoryLongPressBridgeTest {
    private fun method(type: String, name: String, registers: Int, smali: String) = MutableMethod(ImmutableMethod(
        type, name, emptyList(), "V",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null),
    )).apply { addInstructionsWithLabels(0, smali) }

    /** Each branch's source and target index, by code address. */
    private fun branches(instructions: List<Instruction>) = instructions.mapIndexedNotNull { index, instruction ->
        if (instruction !is OffsetInstruction) null else {
            val address = instructions.take(index).sumOf { it.codeUnits } + instruction.codeOffset
            index to instructions.indices.first { instructions.take(it).sumOf { ins -> ins.codeUnits } == address }
        }
    }

    private fun assertStaticCall(instruction: Instruction, owner: String, name: String, parameters: List<String>, vararg registers: Int) {
        assertEquals(Opcode.INVOKE_STATIC, instruction.opcode)
        val call = instruction as FiveRegisterInstruction
        assertEquals(registers.toList(), listOf(call.registerC, call.registerD, call.registerE).take(call.registerCount))
        val reference = (instruction as ReferenceInstruction).reference as MethodReference
        assertEquals(owner, reference.definingClass)
        assertEquals(name, reference.name)
        assertEquals(parameters, reference.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", reference.returnType)
    }

    @Test
    fun theSaveTakesThePanelsPlaceAfterTikToksPause() {
        // Clean 46.2.3 LX/0Qet.LIZ, including its monitor/interceptor branches. 47.7.3's
        // LX/0754 is the same body under other names.
        val method = method("LX/0TwT;", "LIZ", 4, """
            iget-object v1, p0, LX/0TwT;->LL:LX/049E;
            iget-object v0, v1, LX/049E;->LLJIJIL:LX/0RUf;
            const/4 v2, 0x1
            if-eqz v0, :listener
            iput-boolean v2, v1, LX/049E;->LLJJ:Z
            invoke-interface { v0 }, LX/0RUf;->Mf()V
            iget-object v0, p0, LX/0TwT;->LL:LX/049E;
            invoke-virtual { v0 }, Landroid/view/View;->getParent()Landroid/view/ViewParent;
            move-result-object v0
            if-eqz v0, :listener
            invoke-interface { v0, v2 }, Landroid/view/ViewParent;->requestDisallowInterceptTouchEvent(Z)V
            :listener
            iget-object v1, p0, LX/0TwT;->LL:LX/049E;
            iget-object v0, v1, LX/049E;->LLIZLLLIL:LX/0PeW;
            if-eqz v0, :done
            iget-object v0, v1, LX/049E;->LLJILJIL:LX/0QS2;
            if-eqz v0, :notify
            invoke-interface { v0 }, LX/0QS2;->gi()Z
            move-result v0
            if-eqz v0, :notify
            return-void
            :notify
            iget-object v0, p0, LX/0TwT;->LL:LX/049E;
            invoke-virtual { v0 }, Landroid/view/View;->getParent()Landroid/view/ViewParent;
            move-result-object v0
            if-eqz v0, :coordinates
            invoke-interface { v0, v2 }, Landroid/view/ViewParent;->requestDisallowInterceptTouchEvent(Z)V
            :coordinates
            iget-object v0, p0, LX/0TwT;->LL:LX/049E;
            iget-object v2, v0, LX/049E;->LLIZLLLIL:LX/0PeW;
            iget v1, v0, LX/049E;->LL:F
            iget v0, v0, LX/049E;->LLILLL:F
            invoke-interface { v2, v1, v0 }, LX/0PeW;->LIZ(FF)V
            :done
            return-void
        """)
        val original = method.implementation!!.instructions.toList()
        val targets = branches(original)
        // The panel half, where a timer without a monitor goes: its view read, then the listener read.
        val meet = 11

        val pause = method.interceptStoryLongPress()

        assertEquals("LX/0RUf;", pause.definingClass)
        assertEquals("Mf", pause.name)
        val instructions = method.implementation!!.instructions.toList()
        assertEquals(4, method.implementation!!.registerCount)
        assertEquals(original.size + 4, instructions.size)
        // TikTok's pause runs untouched before the save is asked.
        (0..meet).forEach { assertSame(original[it], instructions[it]) }
        assertStaticCall(instructions[meet + 1], "Lapp/morphe/extension/tiktok/download/StoryDownloads;",
            "onNativeLongPress", listOf("Landroid/view/View;"), 1)
        assertEquals(Opcode.MOVE_RESULT, instructions[meet + 2].opcode)
        assertEquals(0, (instructions[meet + 2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, instructions[meet + 3].opcode)
        assertEquals(0, (instructions[meet + 3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_VOID, instructions[meet + 4].opcode)
        // Unanswered, the panel opens as before.
        val branchAddress = instructions.take(meet + 3).sumOf { it.codeUnits }
        assertEquals(instructions.take(meet + 5).sumOf { it.codeUnits },
            branchAddress + (instructions[meet + 3] as OffsetInstruction).codeOffset)
        original.drop(meet + 1).forEachIndexed { index, instruction ->
            assertSame(instruction, instructions[meet + 1 + index + 4])
        }
        // Both ways into the panel half still land on its view read, ahead of the save.
        targets.forEach { (from, to) ->
            val index = if (from > meet) from + 4 else from
            val target = if (to > meet) to + 4 else to
            assertEquals(instructions.take(target).sumOf { it.codeUnits },
                instructions.take(index).sumOf { it.codeUnits } +
                    (instructions[index] as OffsetInstruction).codeOffset)
        }
    }

    @Test
    fun theReleaseAsksThePickerFirstAndNamesItself() {
        // 47.7.3's LongPressMonitorComponent.Gb, cut to its first lines: v5 is this.
        val method = method("Lcom/ss/android/ugc/aweme/story/feed/collection/component/LongPressMonitorComponent;", "Gb", 6, """
            invoke-virtual { v5 }, Lcom/ss/android/ugc/aweme/story/feed/collection/component/LongPressMonitorComponent;->Ti()Z
            move-result v0
            if-nez v0, :go
            return-void
            :go
            const/4 v3, 0x0
            iput-boolean v3, v5, Lcom/ss/android/ugc/aweme/story/feed/collection/component/LongPressMonitorComponent;->LLJLILLLLZIIL:Z
            return-void
        """)
        val original = method.implementation!!.instructions.toList()

        method.holdReleaseForPicker()

        val instructions = method.implementation!!.instructions.toList()
        assertEquals(6, method.implementation!!.registerCount)
        assertEquals(original.size + 5, instructions.size)
        assertEquals(Opcode.CONST_STRING, instructions[0].opcode)
        assertEquals(0, (instructions[0] as OneRegisterInstruction).registerA)
        assertEquals("Gb", ((instructions[0] as ReferenceInstruction).reference as StringReference).string)
        assertStaticCall(instructions[1], "Lapp/morphe/extension/tiktok/download/StoryPicker;", "holdRelease",
            listOf("Ljava/lang/Object;", "Ljava/lang/String;"), 5, 0)
        assertEquals(Opcode.MOVE_RESULT, instructions[2].opcode)
        assertEquals(Opcode.IF_EQZ, instructions[3].opcode)
        assertEquals(Opcode.RETURN_VOID, instructions[4].opcode)
        assertEquals(instructions.take(5).sumOf { it.codeUnits },
            instructions.take(3).sumOf { it.codeUnits } + (instructions[3] as OffsetInstruction).codeOffset)
        original.forEachIndexed { index, instruction -> assertSame(instruction, instructions[index + 5]) }
    }
}
