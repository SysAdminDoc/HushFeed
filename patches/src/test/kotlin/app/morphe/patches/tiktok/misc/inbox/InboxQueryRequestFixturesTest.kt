package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Test

/** Concrete entry fences cover native widget requests, direct VMs, deferred closures and loaders. */
class InboxQueryRequestFixturesTest {
    @Test fun `all reviewed native request entries guard before effects and retain every original instruction`() {
        assertEquals(13, loadedInboxRequestSpecs.size)
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val taken = loadedInboxRequestSpecs.map { spec -> spec to spec.soleMatch(classes) }
            assertEquals("every spec takes its own method", taken.size, taken.map { it.second.second }.distinct().size)
            taken.forEach { (spec, match) ->
                val (owner, native) = match
                if (spec.closure) {
                    assertTrue(AccessFlags.STATIC.isSet(native.accessFlags))
                    assertEquals(listOf(owner.type), native.parameterTypes.map(CharSequence::toString))
                }
                val mutable = MutableMethod(native)
                val original = mutable.implementation!!.instructions.toList()
                val returns = original.filter { it.opcode == if (spec.closure) Opcode.RETURN_OBJECT else Opcode.RETURN_VOID }
                val returnExits = original.filterIsInstance<BuilderOffsetInstruction>().filter { it.target.location.instruction in returns }
                mutable.loadedRequestHook(spec)
                val after = mutable.implementation!!.instructions.toList()
                val guard = after[0].getReference<MethodReference>()!!
                assertEquals(INBOX_QUERY_EXTENSION, guard.definingClass)
                assertEquals(if (spec.closure) "beginLoadedClosureRequest" else if (spec.counted) "beginLoadedRequest" else "allowLoadedRequest", guard.name)
                val allowed = after[2] as BuilderOffsetInstruction
                assertSame(original[0], allowed.target.location.instruction)
                assertEquals(if (spec.closure) 5 else 4, allowed.target.location.index)
                assertEquals(1, (after[0] as RegisterRangeInstruction).registerCount)
                // The builder re-pads a switch payload after the insert, so a padding nop in front of it can go.
                val originalAfter = after.filter { candidate -> original.any { candidate === it } }.filterNot { it.opcode == Opcode.NOP }
                assertEquals(original.filterNot { it.opcode == Opcode.NOP }, originalAfter)
                assertEquals(if (spec.counted) returns.size else 0, after.count { it.getReference<MethodReference>()?.name in
                    listOf("returnedLoadedRequest", "returnedLoadedClosureRequest") })
                if (spec.counted) {
                    returnExits.forEach { exit ->
                        assertTrue("native return branches must execute counter cleanup", exit.target.location.instruction!!
                            .getReference<MethodReference>()?.name in listOf("returnedLoadedRequest", "returnedLoadedClosureRequest"))
                    }
                    returns.forEach { returned ->
                        val index = after.indexOf(returned)
                        assertEquals(INBOX_QUERY_EXTENSION, after[index - 1].getReference<MethodReference>()!!.definingClass)
                    }
                }
                if (spec.tracked) {
                    val launch = after.withIndex().single { it.value.isCoroutineLaunch() }
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, after[launch.index + 1].opcode)
                    val nativeResult = (after[launch.index + 1] as OneRegisterInstruction).registerA
                    val observed = after[launch.index + 2]
                    assertEquals(if (spec.closure) "onLoadedClosureRequestJob" else "onLoadedRequestJob", observed.getReference<MethodReference>()!!.name)
                    assertEquals(nativeResult, (observed as FiveRegisterInstruction).registerD)
                    assertEquals(1, after.count { it.getReference<MethodReference>()?.name in
                        listOf("onLoadedClosureRequestJob", "onLoadedRequestJob") })
                }
                assertEquals(original.mapNotNull { it.getReference<MethodReference>() },
                    after.mapNotNull { it.getReference<MethodReference>() }.filterNot { it.definingClass == INBOX_QUERY_EXTENSION })
            }
        }
    }

    @Test fun `request completion observes real native jobs including the load-more sibling job`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = dex.allClasses()
            val jobSupport = classes.single { it.type == "Lkotlinx/coroutines/JobSupport;" }
            // The launch answers a renamed coroutine class built on JobSupport.
            val loadMore = loadedInboxRequestSpecs.first { it.owner == INBOX_REGULAR_VM && it.tracked }.soleMatch(classes).second
            val job = loadMore.implementation!!.instructions.single { it.isCoroutineLaunch() }.getReference<MethodReference>()!!.returnType
            val byType = classes.associateBy { it.type }
            assertTrue("$job is a JobSupport", generateSequence(byType[job]) { byType[it.superclass] }.any { it.type == jobSupport.type })
            // InboxNativeRequests.jobFields reads each view model's own fields of that type.
            listOf(INBOX_REGULAR_VM, INBOX_CREATOR_VM).forEach { type ->
                val owner = byType.getValue(type)
                assertEquals(type, 2, owner.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == job })
                assertEquals("Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/BaseNotificationResultViewModel;", owner.superclass)
                assertTrue(owner.fields.none { it.type != job && generateSequence(byType[it.type]) { c -> byType[c.superclass] }
                    .any { c -> c.type == jobSupport.type } })
            }
            // InboxNativeRequests.completionRegistration: JobSupport's one public instance method
            // taking only a Function1 and answering a handle (invokeOnCompletion).
            val completions = jobSupport.methods.filter { method -> AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType != "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Lkotlin/jvm/functions/Function1;") }
            assertEquals(1, completions.size)
            assertEquals("Z", jobSupport.methods.single { it.name == "isCompleted" }.returnType)
            val unit = classes.single { it.type == "Lkotlin/Unit;" }
            assertEquals("Lkotlin/Unit;", unit.fields.single { it.name == "LIZ" }.type)
        }
    }

    private fun MultiDexContainer<out DexFile>.allClasses(): List<ClassDef> =
        dexEntryNames.flatMap { getEntry(it)!!.dexFile.classes }

    private fun InboxRequestSpec.soleMatch(classes: List<ClassDef>): Pair<ClassDef, Method> {
        val candidates = owner?.let { type -> classes.filter { it.type == type } } ?: classes
        val matches = candidates.flatMap { owner -> owner.methods.filter { fingerprint.takes(it, owner) }.map { owner to it } }
        assertEquals("$label takes ${matches.map { "${it.first.type}->${it.second.name}" }}", 1, matches.size)
        return matches.single()
    }
}
