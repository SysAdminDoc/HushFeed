package app.morphe.patches.tiktok.feedfilter

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PineDrama's drama tab asks for its feed with requests of its own and converts the answer
 * straight into SeriesFeedResp, so the list never passed FeedItemList.getItems and its ads
 * reached the page (#294). The hook filters `feedItemList` at every return of the constructor,
 * through p1, which nothing reads by then.
 */
class FeedFilterDramaFeedTest {
    private val hookOpcodes = listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT)

    @Test
    fun `every return filters the list and writes it back`() {
        val constructor = constructor(earlyReturn = true)
        val stock = constructor.implementation!!.instructions.count()
        constructor.filterDramaFeedOnConstruct()

        val code = constructor.implementation!!.instructions.toList()
        assertEquals("four lines at each of two returns", stock + 8, code.size)
        val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        assertEquals(2, returns.size)
        for (index in returns) {
            val hook = code.subList(index - 4, index)
            assertEquals(hookOpcodes, hook.map { it.opcode })
            assertEquals("feedItemList", hook[0].getReference<FieldReference>()?.name)
            val call = hook[1].getReference<MethodReference>()!!
            assertEquals("Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;", call.definingClass)
            assertEquals("filterDramaFeed", call.name)
            assertEquals("feedItemList", hook[3].getReference<FieldReference>()?.name)
        }
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(constructor))
    }

    @Test
    fun `a branch onto the return runs the hook first`() {
        val constructor = constructor(earlyReturn = false)
        constructor.filterDramaFeedOnConstruct()
        val code = constructor.implementation!!.instructions.toList()
        val branch = code.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals("the skip lands on the hook, not past it", Opcode.IGET_OBJECT, code[targetOf(code, branch)].opcode)
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(constructor))
    }

    @Test
    fun `a constructor that no longer takes the list first is refused`() {
        val constructor = constructor(first = "Ljava/lang/Boolean;")
        assertRefused("no longer takes the feed list first") { constructor.filterDramaFeedOnConstruct() }
    }

    @Test
    fun `a list parameter past v15 is refused`() {
        val constructor = constructor(locals = 15)
        assertRefused("past v15") { constructor.filterDramaFeedOnConstruct() }
    }

    @Test
    fun `each declared build has one drama feed constructor the hook fits`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.flatMap { classDef ->
                    classDef.methods.filter { it.implementation != null && SeriesFeedRespConstructorFingerprint.takes(it, classDef) }
                }
            }
            assertEquals("${apk.name}: ${taken.map { it.name }}", 1, taken.size)
            val constructor = MutableMethod(taken.single())
            constructor.filterDramaFeedOnConstruct()
            assertEquals("${apk.name}: ${BridgeTypeFlow.check(constructor)}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(constructor))
        }
    }

    /** SeriesFeedResp's constructor cut down to the list, a flag and a count, with no locals by default. */
    private fun constructor(
        first: String = "Ljava/util/List;",
        locals: Int = 0,
        earlyReturn: Boolean = false,
    ) = MutableMethod(
        ImmutableMethod(
            SERIES_FEED_RESP_CLASS_DESCRIPTOR,
            "<init>",
            listOf(first, "Ljava/lang/Boolean;", "I").map { ImmutableMethodParameter(it, null, null) },
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            null,
            null,
            ImmutableMethodImplementation(locals + 4, emptyList(), null, null),
        ),
    ).apply {
        // Past v15 a field instruction can't name p1 at all, and the refusal comes before the
        // body is read, so that frame gets only the super call.
        if (locals + 1 > 15) {
            addInstructions(0, "invoke-direct/range {p0 .. p0}, Ljava/lang/Object;-><init>()V\nreturn-void")
            return@apply
        }
        val written = if (first == "Ljava/util/List;") "feedItemList:Ljava/util/List;" else "hasMore:Ljava/lang/Boolean;"
        // The skip lands on a return either way, the case addInstructionsAtControlFlowLabel is for.
        addInstructions(
            0,
            """
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                iput-object p1, p0, $SERIES_FEED_RESP_CLASS_DESCRIPTOR->$written
                if-eqz p3, :done
                iput-object p2, p0, $SERIES_FEED_RESP_CLASS_DESCRIPTOR->isLimitedFree:Ljava/lang/Boolean;
                ${if (earlyReturn) "return-void" else ""}
                :done
                return-void
            """,
        )
    }

    private fun targetOf(code: List<Instruction>, index: Int): Int {
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        return addresses.indexOf(addresses[index] + (code[index] as OffsetInstruction).codeOffset)
    }

    private fun assertRefused(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal naming \"$expected\"")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains(expected))
        }
    }
}
