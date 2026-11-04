package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The rendered-list seams must retain the native model, metadata and completion trajectories. */
class InboxQueryPublicationFixturesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `publication replaces only the List argument before native mask defaults and dispatch`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val (owner, native) = InboxListPublication.soleMatch(classes)
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val receiver = mutable.implementation!!.registerCount - 6
            mutable.loadedPublicationHook()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size + 2, after.size)
            assertEquals("onLoadedPublication", after[0].getReference<MethodReference>()!!.name)
            val call = after[0] as RegisterRangeInstruction
            assertEquals(receiver, call.startRegister)
            assertEquals(6, call.registerCount)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[1].opcode)
            assertEquals(receiver + 1, (after[1] as OneRegisterInstruction).registerA)
            original.forEachIndexed { index, instruction -> assertSame(instruction, after[index + 2]) }
            assertTrue(original.any { it.getReference<MethodReference>()?.name == "setValue" })
            assertTrue(original.any { it.getReference<MethodReference>()?.name == "postValue" })
            val aggregate = classes.aggregate()
            assertTrue(original.any { it.getReference<MethodReference>()?.definingClass == aggregate })
            assertEquals("Lcom/bytedance/ies/powerlist/ListState;", owner.type)
        }
    }

    @Test fun `native wrapper metadata and allocated diff generation are observed without replacement`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val stub = InboxStubPublication.soleMatch(classes).second
            val (differ, submit) = InboxDiffSubmission.soleMatch(classes)
            val metadataType = submit.parameterTypes[3].toString()
            val mutableStub = MutableMethod(stub)
            val originalStub = mutableStub.implementation!!.instructions.toList()
            mutableStub.loadedStubHook(metadataType)
            val stubCode = mutableStub.implementation!!.instructions.toList()
            assertEquals(originalStub.size + 1, stubCode.size)
            val receipt = stubCode.indexOfFirst { it.extensionCall("onLoadedStubPublication") }
            assertEquals(metadataType, stubCode[receipt - 1].getReference<MethodReference>()!!.definingClass)
            assertEquals("<init>", stubCode[receipt - 1].getReference<MethodReference>()!!.name)
            val metadata = (stubCode[receipt - 1] as RegisterRangeInstruction).startRegister
            assertEquals(metadata, (stubCode[receipt] as FiveRegisterInstruction).registerE)
            assertEquals(originalStub, stubCode.filterNot { it.extensionCall("onLoadedStubPublication") })

            val mutableSubmit = MutableMethod(submit)
            val originalSubmit = mutableSubmit.implementation!!.instructions.toList()
            mutableSubmit.loadedDiffSubmissionHook()
            val submitted = mutableSubmit.implementation!!.instructions.toList()
            val submission = submitted.indexOfFirst { it.extensionCall("onLoadedDiffSubmitted") }
            assertEquals(Opcode.IPUT, submitted[submission - 1].opcode)
            val generation = submitted[submission - 1].getReference<FieldReference>()!!
            assertEquals(differ.type to "I", generation.definingClass to generation.type)
            assertEquals(5, (submitted[submission] as RegisterRangeInstruction).registerCount)
            assertEquals(originalSubmit, submitted.filterNot { it.extensionCall("onLoadedDiffSubmitted") })
        }
    }

    @Test fun `diff acceptance follows real dispatch and every listener exit reaches it before native completions`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val (owner, native) = InboxDiffAcceptance.soleMatch(classes)
            assertEquals(InboxDiffSubmission.soleMatch(classes).first.type, owner.type)
            val (result, metadata, callback) = native.parameterTypes.map(CharSequence::toString)
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val completion = original.withIndex().single { it.value.getReference<FieldReference>()?.let { field ->
                field.definingClass == metadata && field.type == "Ljava/util/List;" } == true }.index
            val exits = original.filterIsInstance<BuilderOffsetInstruction>().filter { it.target.location.index == completion }
            assertTrue("the native listener loop has a branch directly to completion delivery", exits.isNotEmpty())
            mutable.loadedDiffAcceptanceHook()
            val after = mutable.implementation!!.instructions.toList()
            val accepted = after.indexOfFirst { it.extensionCall("onLoadedDiffAccepted") }
            assertEquals(completion, accepted)
            assertSame(original[completion], after[accepted + 1])
            exits.forEach { assertEquals(accepted, it.target.location.index) }
            val dispatched = after.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == result && method.parameterTypes.map(CharSequence::toString) == listOf(callback) } == true }
            val completed = after.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == "Ljava/lang/Runnable;" && method.name == "run" } == true }
            assertTrue(dispatched in 0 until accepted)
            assertTrue(completed > accepted)
            assertEquals(original, after.filterNot { it.extensionCall("onLoadedDiffAccepted") })
            val registers = after[accepted] as FiveRegisterInstruction
            assertEquals(mutable.implementation!!.registerCount - 4, registers.registerC)
            assertEquals(registers.registerC + 2, registers.registerD)
        }
    }

    @Test fun `native async generation rejection happens before list acceptance or diff dispatch`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val (differ, submit) = InboxDiffSubmission.soleMatch(classes)
            val counter = submit.implementation!!.instructions.single { it.opcode == Opcode.IPUT &&
                it.getReference<FieldReference>()?.definingClass == differ.type }.getReference<FieldReference>()!!
            // The diff's worker runnable compares the generation it was posted with to the current one.
            val native = classes.filter { "Ljava/lang/Runnable;" in it.interfaces }.flatMap { it.methods }.single { method ->
                method.implementation?.instructions?.any { it.opcode == Opcode.IGET && it.getReference<FieldReference>() == counter } == true
            }
            val code = MutableMethod(native).implementation!!.instructions.toList()
            val generation = code.indexOfFirst { it.getReference<FieldReference>() == counter }
            val rejection = code.filterIsInstance<BuilderOffsetInstruction>().single { it.opcode == Opcode.IF_NE }
            assertTrue(code.indexOf(rejection) > generation)
            assertEquals(Opcode.RETURN_VOID, rejection.target.location.instruction!!.opcode)
            val listWrite = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>()?.let { field ->
                field.definingClass == differ.type && field.type == "Ljava/util/List;" } == true }
            val metadata = submit.parameterTypes[3].toString()
            val dispatch = code.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == differ.type && metadata in method.parameterTypes.map(CharSequence::toString) } == true }
            assertTrue(listWrite > code.indexOf(rejection))
            assertTrue(dispatch > listWrite && dispatch < rejection.target.location.index)
        }
    }

    @Test fun `indexed callbacks keep the native helper and pair while current row clicks keep the notice getter`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val closure = InboxIndexedCallback.soleMatch(classes).second
            val mutable = MutableMethod(closure)
            val original = mutable.implementation!!.instructions.toList()
            val index = original.indexOfFirst { it.isIndexedListCall() }
            val call = original[index] as FiveRegisterInstruction
            mutable.loadedIndexedHook()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size, after.size)
            original.forEachIndexed { i, instruction -> if (i != index) assertSame(instruction, after[i]) }
            assertTrue(after[index].extensionCall("onLoadedIndexedCallback"))
            val bridge = after[index] as FiveRegisterInstruction
            assertEquals(4, bridge.registerCount)
            assertEquals(call.registerC, bridge.registerD)
            assertEquals(call.registerD, bridge.registerE)
            assertEquals(call.registerE, bridge.registerF)

            val (cell, click) = InboxNotificationClick.soleMatch(classes.filter { it.type == TEMPLATE_NOTIFICATION_CELL })
            val noticeGetter = cell.methods.single { it.parameterTypes.isEmpty() &&
                it.returnType == "Lcom/ss/android/ugc/aweme/notice/repo/list/bean/MusNotice;" }
            val mutableClick = MutableMethod(click)
            val originalClick = mutableClick.implementation!!.instructions.toList()
            mutableClick.loadedGuard("allowLoadedNotificationClick")
            val clickCode = mutableClick.implementation!!.instructions.toList()
            assertEquals(originalClick, clickCode.drop(4))
            assertTrue(clickCode.any { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == cell.type && method.name == noticeGetter.name } == true })
            val entry = clickCode[2] as BuilderOffsetInstruction
            assertSame(originalClick[0], entry.target.location.instruction)
        }
    }

    @Test fun `both indexed live-data reads use the accepted aggregate without changing other native work`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val native = InboxVisibleIndices.soleMatch(classes.filter { it.type == INBOX_RESULT_FRAGMENT }).second
            val aggregate = classes.aggregate()
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            mutable.loadedVisibleHook()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size + 8, after.size)
            assertTrue(after[0].extensionCall("allowLoadedVisibleIndices"))
            val reads = after.withIndex().filter { it.value.getReference<FieldReference>()?.let { field ->
                field.definingClass == aggregate && field.type == "Ljava/util/List;" } == true }
            assertEquals(2, reads.size)
            reads.forEach { assertTrue(after[it.index + 1].extensionCall("onLoadedIndexedList")) }
            val originalCalls = original.mapNotNull { it.getReference<MethodReference>() }
            assertEquals(originalCalls, after.mapNotNull { it.getReference<MethodReference>() }
                .filterNot { it.definingClass == INBOX_QUERY_EXTENSION })
        }
    }

    @Test fun `pull and manual refresh entry guards precede every native flag listener and animation`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val spring = InboxSpringRefresh.soleMatch(classes).first
            listOf(Triple(false, InboxSpringRefresh, "allowLoadedRefresh"),
                Triple(true, InboxSpringManualRefresh, "allowLoadedManualRefresh")).forEach { (manual, fingerprint, hook) ->
                val (owner, native) = fingerprint.soleMatch(classes)
                assertEquals(spring.type, owner.type)
                val mutable = MutableMethod(native)
                val original = mutable.implementation!!.instructions.toList()
                mutable.loadedRefreshHook()
                val after = mutable.implementation!!.instructions.toList()
                assertTrue(after[0].extensionCall(hook))
                assertEquals(original, after.drop(4))
                assertEquals(Opcode.RETURN_VOID, after[3].opcode)
                assertSame(original[0], (after[2] as BuilderOffsetInstruction).target.location.instruction)
                val firstFlag = after.indexOfFirst { it.opcode == Opcode.IPUT_BOOLEAN }
                assertTrue(firstFlag > 3)
                if (manual) {
                    val refreshCall = after.indexOfFirst { it.getReference<MethodReference>()?.name == "setRefreshing" }
                    val manualFlag = after.indexOfFirst { it.opcode == Opcode.IPUT_BOOLEAN &&
                        it.getReference<FieldReference>()?.definingClass == spring.type }
                    val animation = after.indexOfFirst { it.getReference<MethodReference>()?.let { ref ->
                        ref.definingClass == "Landroid/animation/ValueAnimator;" && ref.name == "start" } == true }
                    assertTrue(refreshCall > 3 && manualFlag > refreshCall && animation > manualFlag)
                } else {
                    // The refresh listener hears about it only after the layout stores its state.
                    assertTrue(after.indexOfFirst { it.opcode == Opcode.INVOKE_INTERFACE } > firstFlag)
                    assertEquals(2, (after[0] as RegisterRangeInstruction).registerCount)
                }
            }
        }
    }

    @Test fun `synthetic index gate repeats the exact native getter and field without touching the fragment`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val native = InboxIndexedCallback.soleMatch(dex.allClasses()).second
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val bridge = loadedIndicesGate(mutable)
            assertEquals(INBOX_RESULT_FRAGMENT, bridge.definingClass)
            assertEquals(INBOX_INDICES_GATE, bridge.name)
            assertTrue(bridge.parameterTypes.isEmpty())
            assertEquals("Z", bridge.returnType)
            val gate = bridge.implementation!!.instructions.toList()
            assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IGET_BOOLEAN, Opcode.RETURN),
                gate.map { it.opcode })
            assertEquals(original[0].getReference<MethodReference>(), gate[0].getReference<MethodReference>())
            assertEquals(original[2].getReference<FieldReference>(), gate[2].getReference<FieldReference>())
            assertEquals("enable", gate[2].getReference<FieldReference>()!!.name)
            assertFalse(gate.any { it.getReference<MethodReference>()?.definingClass == INBOX_RESULT_FRAGMENT })
            val disabled = original[3] as BuilderOffsetInstruction
            assertEquals(Opcode.IF_EQZ, disabled.opcode)
            assertEquals("Lkotlin/Unit;", disabled.target.location.instruction!!.getReference<FieldReference>()!!.definingClass)
            mutable.loadedIndexedHook()
            assertSame(original[3], mutable.implementation!!.instructions[3])
            assertTrue(mutable.implementation!!.instructions.indexOfFirst { it.extensionCall("onLoadedIndexedCallback") } > 3)
        }
    }

    @Test fun `the patch finds the pull refresh in the manual refresh's class and guards that one`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val (spring, native) = InboxSpringRefresh.soleMatch(Fixtures.dexContainer(apk, Opcodes.getDefault()).allClasses())
            var checked = false
            val probe = bytecodePatch(name = "inbox refresh probe") {
                execute {
                    loadedInboxPublicationSeams().install()
                    val refresh = mutableClassDefBy(spring.type).methods.single { it.name == native.name &&
                        it.parameterTypes.map(CharSequence::toString) == native.parameterTypes.map(CharSequence::toString) }
                    assertTrue("$version: the pull refresh guard", refresh.implementation!!.instructions[0].extensionCall("allowLoadedRefresh"))
                    checked = true
                }
            }
            listOf(InboxIndexedCallback, InboxDiffSubmission, InboxDiffAcceptance, InboxListPublication, InboxStubPublication,
                InboxFragmentPublication, InboxVisibleIndices, InboxNotificationClick, InboxSpringRefresh, InboxSpringManualRefresh,
            ).forEach { it.clearMatch() }
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            assertTrue("$version: the probe never ran", checked)
        }
    }

    private fun MultiDexContainer<out DexFile>.allClasses(): List<ClassDef> =
        dexEntryNames.flatMap { getEntry(it)!!.dexFile.classes }

    private fun Fingerprint.soleMatch(classes: List<ClassDef>): Pair<ClassDef, Method> {
        val matches = classes.flatMap { owner -> owner.methods.filter { takes(it, owner) }.map { owner to it } }
        assertEquals("${javaClass.simpleName} takes ${matches.map { "${it.first.type}->${it.second.name}" }}", 1, matches.size)
        return matches.single()
    }

    /** PowerStub.onChanged casts its argument to the list aggregate (a renamed class on each build). */
    private fun List<ClassDef>.aggregate(): String = InboxStubPublication.soleMatch(this).second.implementation!!
        .instructions.first { it.opcode == Opcode.CHECK_CAST }.getReference<TypeReference>()!!.type
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.extensionCall(name: String): Boolean =
    getReference<MethodReference>()?.let { it.definingClass == INBOX_QUERY_EXTENSION && it.name == name } == true
