/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.originalaudio

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play original audio's anchors on each declared build: the feed model's getters for a post's dub
 * tracks and for its dubbed copy, the two ways a dub reaches the player. Each hook is applied to
 * the real getter, and neither field may have another reader in the app that plays or saves
 * video, or a dub could still get around it.
 */
class OriginalAudioAnchorsTest {
    @Test
    fun `the dub track getter is on every build and its answer passes through the extension`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = classOf(apk) ?: error("$version: no $AUDIO")
            val taken = classDef.methods.filter { DubTracksFingerprint.takes(it, classDef) }
            assertEquals("$version: dub track getters matched", 1, taken.size)

            val getter = MutableMethod(taken.single())
            val before = getter.implementation!!.instructions.toList()
            val read = before.first().getReference<FieldReference>()
            assertEquals("$version: the getter reads the dub tracks first", "TtsInfos", read?.name)

            getter.passDubTracksThroughExtension()
            val after = getter.implementation!!.instructions.toList()
            assertEquals("$version: a call and its result added", before.size + 2, after.size)
            val back = after.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            val returned = (after[back] as OneRegisterInstruction).registerA
            val call = after[back - 2]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$version: the call reads the returned register", returned, (call as RegisterRangeInstruction).startRegister)
            val target = call.getReference<MethodReference>()!!
            assertEquals(ORIGINAL_AUDIO, target.definingClass)
            assertEquals("dubTracks", target.name)
            assertEquals(listOf("Ljava/util/List;"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("Ljava/util/List;", target.returnType)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[back - 1].opcode)
            assertEquals(returned, (after[back - 1] as OneRegisterInstruction).registerA)
        }
    }

    @Test
    fun `nothing but the getter and the model's own methods reads the dub tracks`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val readers = readersOf(apk, AUDIO, "TtsInfos")
            // toString only prints it; ConvertHelper copies the model into its proto twin.
            val others = readers.filterNot {
                it == "$AUDIO->getTtsInfos" || it == "$AUDIO->toString" ||
                    it.startsWith("Lcom/ss/android/ugc/tiktok/ConvertHelper;->")
            }
            assertTrue("$version: other readers of the dub tracks: $others", others.isEmpty())
            assertTrue("$version: the getter reads them", "$AUDIO->getTtsInfos" in readers)
        }
    }

    @Test
    fun `the dubbed copy's getter is on every build and its answer passes through the extension`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = classOf(apk, AWEME) ?: error("$version: no $AWEME")
            val taken = classDef.methods.filter { TranslatedVideoFingerprint.takes(it, classDef) }
            assertEquals("$version: dubbed copy getters matched", 1, taken.size)

            val getter = MutableMethod(taken.single())
            val before = getter.implementation!!.instructions.toList()
            val read = before.first().getReference<FieldReference>()
            assertEquals("$version: the getter reads the dubbed copy first", "translatedVideo", read?.name)

            getter.passTranslatedVideoThroughExtension()
            val after = getter.implementation!!.instructions.toList()
            assertEquals("$version: a call, its result and a cast added", before.size + 3, after.size)
            val back = after.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            val returned = (after[back] as OneRegisterInstruction).registerA
            val call = after[back - 3]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$version: the call reads the returned register", returned, (call as RegisterRangeInstruction).startRegister)
            val target = call.getReference<MethodReference>()!!
            assertEquals(ORIGINAL_AUDIO, target.definingClass)
            assertEquals("translatedVideo", target.name)
            assertEquals(listOf("Ljava/lang/Object;"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("Ljava/lang/Object;", target.returnType)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[back - 2].opcode)
            assertEquals(returned, (after[back - 2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.CHECK_CAST, after[back - 1].opcode)
            assertEquals(returned, (after[back - 1] as OneRegisterInstruction).registerA)
            assertEquals(TRANSLATED_VIDEO, after[back - 1].getReference<TypeReference>()?.type)
        }
    }

    /** What the player and a save both read, so a null dubbed copy gives them the post's own video. */
    @Test
    fun `a post's video reaches its dubbed copy only through the getter`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = classOf(apk, AWEME) ?: error("$version: no $AWEME")
            val getVideo = classDef.methods.single { it.name == "getVideo" && it.parameterTypes.isEmpty() }
            val code = getVideo.implementation!!.instructions.toList()
            assertTrue(
                "$version: getVideo doesn't ask the getter",
                code.any { it.getReference<MethodReference>()?.name == "getTranslatedVideo" },
            )
            assertTrue(
                "$version: getVideo has no way to the post's own video",
                code.any { it.getReference<FieldReference>()?.name == "_video" },
            )
            val readers = readersOf(apk, AWEME, "translatedVideo")
            val others = readers.filterNot {
                it == "$AWEME->getTranslatedVideo" || it.startsWith("Lcom/ss/android/ugc/tiktok/ConvertHelper;->")
            }
            assertTrue("$version: other readers of the dubbed copy: $others", others.isEmpty())
        }
    }

    @Test
    fun `a getter with no return stops the patch`() {
        val getter = getter(listOf(read(0), ImmutableInstruction10x(Opcode.RETURN_VOID)), registers = 2)
        val failure = assertThrows(PatchException::class.java) { getter.passDubTracksThroughExtension() }
        assertTrue(failure.message!!.contains("no return"))
    }

    @Test
    fun `an answer in a high register is handed over with a range call`() {
        // iget-object only reaches v0 to v15, so the list moves up to v20 before it is returned.
        val getter = getter(
            listOf(read(0), ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 20, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 20)),
            registers = 24,
        )
        getter.passDubTracksThroughExtension()
        val code = getter.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[2].opcode)
        assertEquals(20, (code[2] as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[3].opcode)
        assertEquals(20, (code[3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_OBJECT, code[4].opcode)
    }

    private fun read(register: Int) = ImmutableInstruction22c(
        Opcode.IGET_OBJECT, register, register + 1,
        ImmutableFieldReference(AUDIO, "TtsInfos", "Ljava/util/List;"),
    )

    private fun getter(code: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>, registers: Int) =
        MutableMethod(
            ImmutableMethod(
                AUDIO, "getTtsInfos", emptyList(), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(registers, code, null, null),
            ),
        )

    private fun classOf(apk: java.io.File, type: String = AUDIO): ClassDef? {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    /** Every method of the build that reads the object field, as `class->method`. */
    private fun readersOf(apk: java.io.File, owner: String, field: String): Set<String> {
        val readers = HashSet<String>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) {
                    val reads = method.implementation?.instructions?.any { instruction ->
                        (instruction.opcode == Opcode.IGET_OBJECT) &&
                            ((instruction as ReferenceInstruction).reference as? FieldReference)?.let {
                                it.definingClass == owner && it.name == field
                            } == true
                    } == true
                    if (reads) readers += "${classDef.type}->${method.name}"
                }
            }
        }
        return readers
    }
}
