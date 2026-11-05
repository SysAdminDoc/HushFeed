package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #265: Audio only in a LIVE's Video quality list. The newer picker reads the room's quality list
 * twice, once keeping only the qualities the player lists, so each read hands the list to the
 * extension with the room, and the filtering read's player keys go through it too. A switch to
 * "ao" goes through only while the player's EnableOnlyAudioInAoRes flag is on, which the message
 * loop checks before its refusal line and the sound-only check reads again, so every read of that
 * flag in both takes the extension's answer.
 */
class LiveAudioOnlyAnchorsTest {
    private fun classes(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    private fun name(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction?) =
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name

    @Test
    fun `each declared build keeps the newer picker's quality list reads the Audio only hook answers`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val dialog = classes[LIVE_TALENT_DEFINITION_DIALOG]
                ?: throw AssertionError("${apk.name}: $LIVE_TALENT_DEFINITION_DIALOG is gone")
            val found = dialog.methods.filter { LiveTalentDefinitionListFingerprint.takes(it, dialog) }
            assertEquals("${apk.name}: the picker's onViewCreated", 1, found.size)

            // The hook as the patch writes it: right after each read, the extension answers the
            // list from the room and the list, and where the rows are checked against the
            // player's keys, it answers those keys before the check reads them.
            val hooked = MutableMethod(found.single())
            val hookedReads = hookAudioOnlyQualities(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val reads = code.indices.filter { index ->
                code[index].opcode == Opcode.IGET_OBJECT &&
                    ((code[index] as ReferenceInstruction).reference as FieldReference).let {
                        it.definingClass == LIVE_STREAM_URL && it.name == "qualityList"
                    }
            }
            assertEquals("${apk.name}: every read is hooked", reads.size, hookedReads)
            if ("47.6.3" in apk.name) assertEquals("${apk.name}: one read per branch", 2, reads.size)
            var filtered = 0
            for (read in reads) {
                val list = (code[read] as TwoRegisterInstruction).registerA
                val room = (code.take(read).last { name(it) == "getStreamUrl" } as FiveRegisterInstruction).registerC
                val call = code[read + 1] as FiveRegisterInstruction
                assertEquals("${apk.name}: the hook after the read at $read", "qualities", name(call))
                assertEquals(listOf(room, list), listOf(call.registerC, call.registerD))
                assertEquals(Opcode.MOVE_RESULT_OBJECT, code[read + 2].opcode)
                assertEquals(list, (code[read + 2] as OneRegisterInstruction).registerA)
                var after = read + 3
                if (name(code[read + 3]) == "playerKeys") {
                    filtered++
                    val keys = code[read + 3] as FiveRegisterInstruction
                    assertEquals(list, keys.registerC)
                    assertEquals(keys.registerD, (code[read + 4] as OneRegisterInstruction).registerA)
                    val contains = code.drop(read + 5).first { name(it) == "contains" } as FiveRegisterInstruction
                    assertEquals("${apk.name}: the check reads the answered keys", keys.registerD, contains.registerC)
                    after = read + 5
                }
                // Labels stay on the instruction the hook went in front of.
                assertTrue("${apk.name}: a branch skips the hook at $read", code[after].location.labels.isEmpty())
            }
            if ("47.6.3" in apk.name) assertEquals("${apk.name}: the branch that checks the player's keys", 1, filtered)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // The extension makes the row with the row class's own no-argument constructor and
            // sets the fields the picker shows and switches by.
            val quality = classes["Lcom/bytedance/android/livesdkapi/depend/model/live/LiveCoreSDKData\$Quality;"]
                ?: throw AssertionError("${apk.name}: the quality row class is gone")
            assertTrue("${apk.name}: the row's constructor", quality.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() })
            for ((field, type) in listOf("sdkKey" to "Ljava/lang/String;", "name" to "Ljava/lang/String;", "level" to "I")) {
                assertTrue("${apk.name}: the row's $field", quality.fields.any { it.name == field && it.type == type })
            }
        }
    }

    @Test
    fun `each declared build keeps the player's audio switch flag both checks read`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val gates = classes.values.flatMap { owner -> owner.methods.filter { LiveAudioSwitchGateFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: the player's message loop", 1, gates.size)
            val flag = audioSwitchFlag(gates.single())
            assertEquals(VIDEO_LIVE_MANAGER, flag.definingClass)
            assertEquals("I", flag.type)
            if ("47.6.3" in apk.name) assertEquals("${apk.name}: the flag", "z1", flag.name)

            // It's the server's EnableOnlyAudioInAoRes: the player's settings parse stores it there.
            val manager = classes[VIDEO_LIVE_MANAGER] ?: throw AssertionError("${apk.name}: $VIDEO_LIVE_MANAGER is gone")
            val stored = manager.methods.any { method ->
                val code = method.implementation?.instructions?.toList().orEmpty()
                code.indices.any { at ->
                    ((code[at] as? ReferenceInstruction)?.reference as? StringReference)?.string == "EnableOnlyAudioInAoRes" &&
                        code.drop(at + 1).take(5).any {
                            it.opcode == Opcode.IPUT && ((it as ReferenceInstruction).reference as FieldReference).name == flag.name
                        }
                }
            }
            assertTrue("${apk.name}: the server setting no longer lands in ${flag.name}", stored)

            val soundOnly = manager.methods.filter { LiveAudioUrlGateFingerprint.takes(it, manager) }
            assertEquals("${apk.name}: the sound-only check", 1, soundOnly.size)
            for (method in listOf(gates.single(), soundOnly.single())) {
                // The hook as the patch writes it: right after each read, the extension answers
                // the flag into the same register before anything branches on it.
                val hooked = MutableMethod(method)
                val hookedReads = hookAudioSwitchFlag(hooked, flag)
                val code = hooked.implementation!!.instructions.toList()
                val reads = code.indices.filter { at ->
                    code[at].opcode == Opcode.IGET && ((code[at] as ReferenceInstruction).reference as FieldReference).name == flag.name
                }
                assertEquals("${apk.name}: ${method.name} reads", reads.size, hookedReads)
                if ("47.6.3" in apk.name) assertEquals("${apk.name}: ${method.name} reads", if (method == soundOnly.single()) 1 else 2, reads.size)
                for (read in reads) {
                    val register = (code[read] as TwoRegisterInstruction).registerA
                    val call = code[read + 1]
                    assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    assertEquals("switchAllowed", name(call))
                    assertEquals(register, (call as RegisterRangeInstruction).startRegister)
                    assertEquals(1, call.registerCount)
                    assertEquals(Opcode.MOVE_RESULT, code[read + 2].opcode)
                    assertEquals(register, (code[read + 2] as OneRegisterInstruction).registerA)
                    assertTrue("${apk.name}: a branch skips the hook at $read", code[read + 3].location.labels.isEmpty())
                }
                assertEquals("${apk.name}: ${method.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
            }
        }
    }

    @Test
    fun `each declared build keeps the older picker's row builder the Audio only hooks answer`() {
        val leaves = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW)
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val dialog = classes[LIVE_DEFINITION_DIALOG] ?: throw AssertionError("${apk.name}: $LIVE_DEFINITION_DIALOG is gone")
            val found = dialog.methods.filter { LiveDefinitionListFingerprint.takes(it, dialog) }
            assertEquals("${apk.name}: the older picker's onViewCreated", 1, found.size)

            // The dialog's copy: right after the builder answers, the extension answers the rows,
            // and those are the rows the dialog hands its list adapter.
            val hooked = MutableMethod(found.single())
            val builderCall = hookAudioOnlyPickerRows(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val call = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                    ref.definingClass == builderCall.definingClass && ref.name == builderCall.name
                } == true
            }
            val rows = (code[call + 1] as OneRegisterInstruction).registerA
            assertEquals("pickerRows", name(code[call + 2]))
            assertEquals(rows, (code[call + 2] as FiveRegisterInstruction).registerC)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[call + 3].opcode)
            assertEquals(rows, (code[call + 3] as OneRegisterInstruction).registerA)
            val adapter = code.indices.first { at ->
                at > call && code[at].opcode == Opcode.INVOKE_DIRECT_RANGE && name(code[at]) == "<init>" &&
                    ((code[at] as ReferenceInstruction).reference as MethodReference).parameterTypes.getOrNull(1)?.toString() == "Ljava/util/List;"
            }
            assertEquals("${apk.name}: the adapter takes the answered rows", rows, (code[adapter] as RegisterRangeInstruction).startRegister + 2)
            val rewritten = (call + 4 until adapter).filter { at ->
                code[at].opcode.setsRegister() && (code[at] as? OneRegisterInstruction)?.registerA == rows
            }
            assertEquals("${apk.name}: the rows are written over before the adapter", emptyList<Int>(), rewritten)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // The builder: both loads of the room meet at the note, which takes over the label
            // the first load jumps to when it finds a room.
            val owner = classes[builderCall.definingClass] ?: throw AssertionError("${apk.name}: the row builder's class is gone")
            val builder = owner.methods.single {
                it.name == builderCall.name && it.parameterTypes.map(CharSequence::toString) == builderCall.parameterTypes.map(CharSequence::toString)
            }
            assertTrue("${apk.name}: the builder is static", AccessFlags.STATIC.isSet(builder.accessFlags))
            val noted = MutableMethod(builder)
            val room = hookAudioOnlyPickerRoom(noted)
            val built = noted.implementation!!.instructions.toList()
            val note = built.indexOfFirst { name(it) == "noteRoom" }
            assertEquals(room, (built[note] as FiveRegisterInstruction).registerC)
            val loads = built.indices.filter { at ->
                built[at].opcode == Opcode.CHECK_CAST && ((built[at] as ReferenceInstruction).reference as TypeReference).type == LIVE_ROOM_TYPE
            }
            if ("47.6.3" in apk.name) assertEquals("${apk.name}: the channel's room and TikTok's current one", 2, loads.size)
            assertEquals("${apk.name}: the note right after the last load", loads.last() + 1, note)
            val jump = built[loads.first() + 1] as BuilderOffsetInstruction
            assertEquals("${apk.name}: a found room jumps to the note", note, jump.target.location.index)
            assertTrue("${apk.name}: nothing between the loads leaves", (loads.first() until note).none { built[it].opcode in leaves })
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(noted))

            // The extension makes its row with the rows' constructor that takes a quality row.
            assertTrue("${apk.name}: the builder makes rows from quality rows", builder.implementation!!.instructions.any {
                it.opcode == Opcode.INVOKE_DIRECT && name(it) == "<init>" &&
                    ((it as ReferenceInstruction).reference as MethodReference).parameterTypes.map(CharSequence::toString) ==
                    listOf("Lcom/bytedance/android/livesdkapi/depend/model/live/LiveCoreSDKData\$Quality;")
            })

            // The mark the extension sets on its row while the room plays Audio only is the field
            // the dialog's adapter draws the current row's dot from.
            val mark = audioOnlyPickerMark(builder)
            val adapterType = ((code[adapter] as ReferenceInstruction).reference as MethodReference).definingClass
            val binds = classes[adapterType]?.methods?.filter { it.name == "onBindViewHolder" }.orEmpty()
            assertTrue("${apk.name}: the adapter draws its dot from the mark", binds.any { bind ->
                bind.implementation!!.instructions.any {
                    val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                    it.opcode == Opcode.IGET_BOOLEAN && field?.definingClass == mark.definingClass && field.name == mark.name
                }
            })
        }
    }

    @Test
    fun `the older picker's mark is the flag set on a row made from a name and key`() {
        val make = "new-instance v1, LX/Row;\ninvoke-direct {v1, v0, v0}, LX/Row;-><init>(Ljava/lang/String;Ljava/lang/String;)V"
        assertEquals("current", audioOnlyPickerMark(builder("$make\niput-boolean v0, v1, LX/Row;->current:Z")).name)
        assertEquals("the same flag twice", "current", audioOnlyPickerMark(builder(
            "$make\niput-boolean v0, v1, LX/Row;->current:Z\n$make\niput-boolean v0, v1, LX/Row;->current:Z",
        )).name)
        assertThrows("no flag", PatchException::class.java) { audioOnlyPickerMark(builder(make)) }
        assertThrows("another object's flag", PatchException::class.java) {
            audioOnlyPickerMark(builder("$make\niput-boolean v0, v2, LX/Row;->current:Z"))
        }
        assertThrows("two flags", PatchException::class.java) {
            audioOnlyPickerMark(builder("$make\niput-boolean v0, v1, LX/Row;->current:Z\n$make\niput-boolean v0, v1, LX/Row;->auto:Z"))
        }
    }

    /** A static row builder that loads its room with [body]. */
    private fun builder(body: String) = MutableMethod(ImmutableMethod("LX/Builder;", "rows",
        listOf(ImmutableMethodParameter("Lcom/bytedance/ies/sdk/datachannel/DataChannel;", null, null)),
        "Ljava/util/List;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(4, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            const/4 v0, 0x0
            $body
            const/4 v2, 0x0
            return-object v2
        """)
    }

    @Test
    fun `the older picker's room note takes one room register and the dialog its builder's answer`() {
        assertEquals(1, hookAudioOnlyPickerRoom(builder("check-cast v1, $LIVE_ROOM_TYPE")))
        assertThrows("no room", PatchException::class.java) { hookAudioOnlyPickerRoom(builder("nop")) }
        assertThrows("two room registers", PatchException::class.java) {
            hookAudioOnlyPickerRoom(builder("check-cast v1, $LIVE_ROOM_TYPE\ncheck-cast v2, $LIVE_ROOM_TYPE"))
        }
        val dialog = { body: String ->
            MutableMethod(ImmutableMethod(LIVE_DEFINITION_DIALOG, "onViewCreated", emptyList(), "V", AccessFlags.PUBLIC.value,
                null, null, ImmutableMethodImplementation(3, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, "const/4 v0, 0x0\n$body\nreturn-void")
            }
        }
        val build = "invoke-static {v0}, LX/Builder;->rows(Lcom/bytedance/ies/sdk/datachannel/DataChannel;)Ljava/util/List;"
        assertEquals("rows", hookAudioOnlyPickerRows(dialog("$build\nmove-result-object v1")).name)
        assertThrows("no builder", PatchException::class.java) { hookAudioOnlyPickerRows(dialog("nop")) }
        assertThrows("the answer dropped", PatchException::class.java) { hookAudioOnlyPickerRows(dialog(build)) }
    }

    /** A picker that reads the room's quality list after [before]. */
    private fun picker(before: String) = MutableMethod(ImmutableMethod(LIVE_TALENT_DEFINITION_DIALOG, "onViewCreated",
        listOf(ImmutableMethodParameter("Landroid/view/View;", null, null), ImmutableMethodParameter("Landroid/os/Bundle;", null, null)),
        "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(5, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            const/4 v1, 0x0
            $before
            iget-object v0, v0, $LIVE_STREAM_URL->qualityList:Ljava/util/LinkedList;
            return-void
        """)
    }

    @Test
    fun `the picker hook takes only a list read straight off the room`() {
        val streamUrl = "invoke-virtual {v1}, $LIVE_ROOM_TYPE->getStreamUrl()$LIVE_STREAM_URL\nmove-result-object v0"
        assertEquals(1, hookAudioOnlyQualities(picker(streamUrl)))
        assertThrows("no room", PatchException::class.java) {
            hookAudioOnlyQualities(picker("const/4 v0, 0x0"))
        }
        assertThrows("the room's register written over", PatchException::class.java) {
            hookAudioOnlyQualities(picker("$streamUrl\nconst/4 v1, 0x0"))
        }
        assertThrows("no list read", PatchException::class.java) {
            hookAudioOnlyQualities(MutableMethod(ImmutableMethod(LIVE_TALENT_DEFINITION_DIALOG, "onViewCreated", emptyList(), "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(1, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, "return-void")
            })
        }
    }
}
