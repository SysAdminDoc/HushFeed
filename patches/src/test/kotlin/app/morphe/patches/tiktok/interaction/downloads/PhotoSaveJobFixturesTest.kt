package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo save job Original Photo Mode hooks, held to every retained fixture through the
 * fingerprint's own predicate ([isPhotoSaveJob]). On 47.0.3 a photo post's Download never reaches
 * the video download start the older hook sits on, so with the switch on nothing of it ran (the
 * S22, 2026-09-23); every photo save runs this job instead. Exactly one method has its shape on
 * each build, and on 47.0.3 it is the one that logs the job it starts.
 */
class PhotoSaveJobFixturesTest {
    @Test
    fun `every fixture has exactly one photo save job`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val jobs = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.flatMap { classDef ->
                    classDef.methods.filter { it.isPhotoSaveJob(classDef) }
                }
            }
            assertEquals("${apk.name}: photo save jobs ${jobs.map { "${it.definingClass}->${it.name}" }}", 1, jobs.size)
            val job = jobs.single()
            assertTrue("${apk.name}: the job has a body to hook", job.implementation != null)
            if (Fixtures.versionOf(apk) in Fixtures.declaredVersions()) {
                val strings = job.implementation!!.instructions.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }
                assertTrue("${apk.name}: the job logs the save it starts, got $strings", "initializeJob aid=" in strings)
            }
        }
    }

    /** The image job above does not see Download video's separate native conversion entry. */
    @Test
    fun `each declared conversion is intercepted before native state with only the Aweme argument`() {
        val owners = mapOf("47.9.3" to "LX/0Cp2;")
        // The method-id entry call's name is the build's own (47.0.3 obfuscated it), although its owner/proto hold.
        val frameEntries = mapOf("47.9.3" to "push")
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val matches = container.dexEntryNames.asSequence().flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.asSequence().flatMap { owner ->
                    owner.methods.asSequence().filter { PhotoVideoConversionFingerprint.takes(it, owner) }
                }
            }.toList()
            assertEquals("conversion entries ${matches.map { "${it.definingClass}->${it.name}" }}", 1, matches.size)
            val native = matches.single()
            assertEquals(owners.getValue(Fixtures.versionOf(apk)), native.definingClass)
            assertEquals("LIZJ", native.name)
            assertTrue(AccessFlags.PUBLIC.isSet(native.accessFlags) && AccessFlags.STATIC.isSet(native.accessFlags))
            assertEquals(listOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;",
                "Lkotlin/jvm/functions/Function1;"), native.parameterTypes.map(CharSequence::toString))
            assertEquals("V", native.returnType)

            val method = MutableMethod(native)
            val original = method.implementation!!.instructions.toList()
            val registers = method.implementation!!.registerCount
            assertEquals("the captured native frame", 14, registers)
            val push = original.indexOfFirst {
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == "Lcom/bytedance/pumbaa/utility/method_id/MethodIDManager;" &&
                        ref.name == frameEntries.getValue(Fixtures.versionOf(apk)) &&
                        ref.parameterTypes.map(CharSequence::toString) == listOf("I") && ref.returnType == "Z"
                } == true
            }
            assertEquals("the native method-id frame starts at entry", 1, push)
            assertTrue("native conversion owns its active flag", original.any {
                it.opcode == Opcode.SPUT_BOOLEAN && it.getReference<FieldReference>()?.let { field ->
                    field.definingClass == native.definingClass && field.name == "LIZJ" && field.type == "Z"
                } == true
            })
            assertTrue("native conversion publishes video progress", original.any {
                it.getReference<StringReference>()?.string == "video_download_status"
            })
            assertTrue("the callback is a native video result", original.any {
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == "Lkotlin/jvm/functions/Function1;" && ref.name == "invoke"
                } == true
            })

            method.interceptPhotoVideoConversion()
            val hooked = method.implementation!!.instructions.toList()
            assertEquals(registers, method.implementation!!.registerCount)
            assertEquals(original.size + 4, hooked.size)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hooked[0].opcode)
            val call = hooked[0] as RegisterRangeInstruction
            assertEquals(1, call.registerCount)
            assertEquals("p0 is the Aweme, not the String or callback", registers - method.numberOfParameterRegisters,
                call.startRegister)
            val target = hooked[0].getReference<MethodReference>()!!
            assertEquals("Lapp/morphe/extension/tiktok/download/OriginalPhotos;", target.definingClass)
            assertEquals("startImageAsVideo", target.name)
            assertEquals(listOf("Ljava/lang/Object;"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", target.returnType)
            assertEquals(Opcode.MOVE_RESULT, hooked[1].opcode)
            assertEquals(0, (hooked[1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.IF_EQZ, hooked[2].opcode)
            assertEquals(0, (hooked[2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_VOID, hooked[3].opcode)
            val branchAddress = hooked.take(2).sumOf { it.codeUnits }
            assertEquals("off/live choices enter the original first instruction, before the method-id frame",
                hooked.take(4).sumOf { it.codeUnits }, branchAddress + (hooked[2] as OffsetInstruction).codeOffset)
            original.forEachIndexed { index, instruction -> assertSame(instruction, hooked[index + 4]) }
        }
    }

    /**
     * #197: the photo saves Hushfeed leaves to TikTok go through the extension first, with all ten
     * arguments as they came, so a held save can start again through the same entry. The
     * extension finds that entry and the job's post by reflection, so each is the only one of its shape.
     */
    @Test
    fun `each declared photo save job hands every argument to the extension first`() {
        val owners = mapOf("47.9.3" to "LX/0C6R;")
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val owner = classes.single { owner -> owner.methods.any { it.isPhotoSaveJob(owner) } }
            assertEquals(owners.getValue(Fixtures.versionOf(apk)), owner.type)

            val postTypes = mutableSetOf<String>()
            fun climb(type: String) {
                if (type == OBJECT || !postTypes.add(type)) return
                classes.firstOrNull { it.type == type }?.let { def ->
                    def.superclass?.let(::climb)
                    def.interfaces.forEach(::climb)
                }
            }
            climb(AWEME)
            val posts = owner.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type in postTypes }
            assertEquals("static fields that could hold the post ${posts.map { it.name }}", 1, posts.size)
            val entries = owner.methods.filter { method ->
                val types = method.parameterTypes.map(CharSequence::toString)
                AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && types.size == 10 &&
                    types[0] == owner.type && types[1] == STRING && types[2] == STRING && types[4] in postTypes &&
                    types[5] == "Ljava/util/Set;" && types[7] == "Z" && types[9] == "I"
            }
            assertEquals("entries reflection could take for the job ${entries.map { it.name }}", 1, entries.size)
            val job = entries.single()
            assertTrue(job.isPhotoSaveJob(owner))

            val method = MutableMethod(job)
            val original = method.implementation!!.instructions.toList()
            val registers = method.implementation!!.registerCount
            method.interceptPhotoSaveJob()
            val hooked = method.implementation!!.instructions.toList()
            assertEquals(registers, method.implementation!!.registerCount)
            assertEquals(original.size + 4, hooked.size)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hooked[0].opcode)
            val call = hooked[0] as RegisterRangeInstruction
            assertEquals("p0 to p9, in order", 10, call.registerCount)
            assertEquals(registers - 10, call.startRegister)
            val target = hooked[0].getReference<MethodReference>()!!
            assertEquals("Lapp/morphe/extension/tiktok/download/OriginalPhotos;", target.definingClass)
            assertEquals("startPhotoJob", target.name)
            // The live-video flag goes over as an int: a hook never takes a boolean.
            assertEquals(listOf(OBJECT, STRING, STRING, OBJECT, OBJECT, "Ljava/util/Set;", OBJECT, "I", OBJECT, "I"),
                target.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", target.returnType)
            assertEquals(Opcode.MOVE_RESULT, hooked[1].opcode)
            assertEquals(0, (hooked[1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.IF_EQZ, hooked[2].opcode)
            assertEquals(0, (hooked[2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_VOID, hooked[3].opcode)
            val branchAddress = hooked.take(2).sumOf { it.codeUnits }
            assertEquals("a save the extension lets through enters the original first instruction",
                hooked.take(4).sumOf { it.codeUnits }, branchAddress + (hooked[2] as OffsetInstruction).codeOffset)
            original.forEachIndexed { index, instruction -> assertSame(instruction, hooked[index + 4]) }
        }
    }

    /** #197: what TikTok's photo save copied to the gallery reaches the extension before each return. */
    @Test
    fun `each declared gallery copy is reported before every return`() {
        val owners = mapOf("47.9.3" to "LX/0UXQ;")
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val matches = container.dexEntryNames.asSequence().flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.asSequence().flatMap { owner ->
                    owner.methods.asSequence().filter { PhotoGalleryCopyFingerprint.takes(it, owner) }
                }
            }.toList()
            assertEquals("gallery copies ${matches.map { "${it.definingClass}->${it.name}" }}", 1, matches.size)
            val copy = matches.single()
            assertEquals(owners.getValue(Fixtures.versionOf(apk)), copy.definingClass)
            assertTrue("the copy writes photos to the gallery", copy.implementation!!.instructions.any {
                it.getReference<MethodReference>()?.parameterTypes?.map(CharSequence::toString) ==
                    listOf("Landroid/content/Context;", STRING, STRING, "Z", STRING, "I")
            })

            val method = MutableMethod(copy)
            val original = method.implementation!!.instructions.toList()
            val returns = original.count { it.opcode == Opcode.RETURN_OBJECT }
            assertTrue("the copy returns what landed", returns >= 1)
            val registers = method.implementation!!.registerCount
            method.recordPhotoGalleryCopy()
            val hooked = method.implementation!!.instructions.toList()
            assertEquals(registers, method.implementation!!.registerCount)
            assertEquals(original.size + 2 * returns, hooked.size)
            val addresses = hooked.runningFold(0) { address, instruction -> address + instruction.codeUnits }
            val tries = method.implementation!!.tryBlocks.map {
                it.startCodeAddress until it.startCodeAddress + it.codeUnitCount
            }
            hooked.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.RETURN_OBJECT) return@forEachIndexed
                val copyCall = hooked[index - 2]
                val call = hooked[index - 1]
                assertEquals(Opcode.INVOKE_STATIC_RANGE, copyCall.opcode)
                assertEquals(1, (copyCall as RegisterRangeInstruction).registerCount)
                assertEquals("the copy itself, p0", registers - method.numberOfParameterRegisters, copyCall.startRegister)
                val copyTarget = copyCall.getReference<MethodReference>()!!
                assertEquals("Lapp/morphe/extension/tiktok/download/NativePhotoSaves;", copyTarget.definingClass)
                assertEquals("copyEnding", copyTarget.name)
                assertEquals(listOf(OBJECT), copyTarget.parameterTypes.map(CharSequence::toString))
                assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
                assertEquals(1, (call as RegisterRangeInstruction).registerCount)
                assertEquals("the set that is returned", (instruction as OneRegisterInstruction).registerA, call.startRegister)
                val target = call.getReference<MethodReference>()!!
                assertEquals("Lapp/morphe/extension/tiktok/download/NativePhotoSaves;", target.definingClass)
                assertEquals("copied", target.name)
                assertEquals(listOf(OBJECT), target.parameterTypes.map(CharSequence::toString))
                // A handler around any of them would turn the report's own failure into the copy's.
                for (at in listOf(addresses[index - 2], addresses[index - 1], addresses[index])) {
                    assertTrue("the report and its return sit outside every try range", tries.none { at in it })
                }
            }
            assertEquals("everything else is left as it was",
                original.map { it.opcode }, hooked.filterNot {
                    it.getReference<MethodReference>()?.definingClass?.contains("NativePhotoSaves") == true
                }.map { it.opcode })
        }
    }

    /**
     * #197: TikTok's album copies for a DM or a share run the same gallery copy as the photo job.
     * The extension tells the job's own apart by the map it was handed: the job hands one of its
     * static maps, every other maker builds a map of its own.
     */
    @Test
    fun `only the photo job hands the gallery copy one of its static maps`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val job = classes.single { owner -> owner.methods.any { it.isPhotoSaveJob(owner) } }.type
            val copy = classes.single { owner -> owner.methods.any { PhotoGalleryCopyFingerprint.takes(it, owner) } }
            val init = copy.methods.single { it.name == "<init>" }
            val mapAt = init.parameterTypes.map(CharSequence::toString).indexOf("Ljava/util/Map;")
            assertTrue("the copy is handed a map", mapAt >= 0)

            val handed = mutableMapOf<String, String>()
            for (maker in classes) for (method in maker.methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                instructions.forEachIndexed { index, instruction ->
                    val ref = instruction.getReference<MethodReference>()
                    if (ref?.definingClass != copy.type || ref.name != "<init>") return@forEachIndexed
                    // The receiver is the first register, so the map is one further in.
                    val map = when (instruction) {
                        is RegisterRangeInstruction -> instruction.startRegister + mapAt + 1
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD,
                            instruction.registerE, instruction.registerF, instruction.registerG)[mapAt + 1]
                        else -> return@forEachIndexed
                    }
                    val write = instructions.subList(0, index).lastOrNull {
                        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == map
                    }
                    val field = write?.takeIf { it.opcode == Opcode.SGET_OBJECT }?.getReference<FieldReference>()
                    handed["${maker.type}->${method.name}"] =
                        if (field?.definingClass == job && field.type == "Ljava/util/Map;") "job's" else "own"
                }
            }
            val jobMakes = classes.single { it.type == job }.methods.flatMap { method ->
                method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
                    .mapNotNull { it.getReference<TypeReference>()?.type }
            }.toSet()
            val jobs = handed.filterValues { it == "job's" }.keys
            assertTrue("the photo job never hands its copy its map, so nothing would be recorded: $handed", jobs.isNotEmpty())
            assertTrue("only the job's own coroutines hand it its map: $handed",
                jobs.all { it.substringBefore("->") in jobMakes })
        }
    }

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"
        const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    }
}
