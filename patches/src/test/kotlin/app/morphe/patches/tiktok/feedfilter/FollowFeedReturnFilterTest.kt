package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Following loader's filter hook. Every `return-object` of the loader becomes a jump to a
 * trailing block, past every try range, that filters and returns the same register. A filter call
 * left in front of a return inside a try block hands the handler the register state at that
 * return, and on 47.6.3 the handler returns a register that holds a List on the branch into the
 * return, so Android 16 refused the class.
 */
class FollowFeedReturnFilterTest {
    private val followFeedList = "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;"
    private val extension = "Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;"
    private val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

    @Test
    fun `every declared fixture routes each loader return through a filter block past its try blocks`() {
        Fixtures.forEachDeclared { apk ->
            val loaders = load(apk).values.flatMap { it.methods }.filter { it.isFollowFeedLoader() }
            assertEquals("loaders ${loaders.map { "${it.definingClass}->${it.name}" }}", 1, loaders.size)
            val clean = loaders.single()
            val cleanCode = clean.implementation!!
            val cleanInstructions = cleanCode.instructions.toList()
            val cleanAddresses = addresses(cleanInstructions)
            val returnsInTry = cleanInstructions.withIndex().filter { (index, instruction) ->
                instruction.opcode == Opcode.RETURN_OBJECT &&
                    cleanCode.tryBlocks.any { cleanAddresses[index] in it.range() }
            }
            // The trap needs a return inside a try range, which the loader has had since 47.6.3.
            assertTrue("a return inside a try block", returnsInTry.isNotEmpty())

            val method = MutableMethod(clean)
            method.filterFollowFeedReturns()
            assertFiltered(cleanInstructions, tryShapes(cleanCode), method)
        }
    }

    @Test
    fun `a return that ends a try block whose handler returns another register jumps out of the range`() {
        val method = loaderShape()
        val code = method.implementation!!
        val cleanInstructions = code.instructions.toList()
        val cleanTries = tryShapes(code)
        assertEquals(1, cleanTries.size)

        method.filterFollowFeedReturns()

        assertFiltered(cleanInstructions, cleanTries, method)
        // The handler lands on the null return's jump, so a caught failure is filtered too.
        val handler = code.tryBlocks.single().exceptionHandlers.single().handlerCodeAddress
        val instructions = code.instructions.toList()
        val handlerIndex = addresses(instructions).indexOf(handler)
        assertTrue(instructions[handlerIndex].opcode in gotos)
        assertEquals(1, (instructions[cleanInstructions.size + 2] as RegisterRangeInstruction).startRegister)
    }

    @Test
    fun `a loader that never returns an object stops the patch`() {
        val method = smali(
            """
                new-instance v0, Ljava/lang/IllegalStateException;
                invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V
                throw v0
            """,
        )
        val error = assertThrows(PatchException::class.java) { method.filterFollowFeedReturns() }
        assertTrue(error.message!!, error.message!!.contains("never returns an object"))
    }

    /**
     * Every original return is a jump to a trailing block that filters and returns its register,
     * no block sits in a try range, the try blocks and handlers kept their addresses, and nothing
     * else moved.
     */
    private fun assertFiltered(
        cleanInstructions: List<Instruction>,
        cleanTries: List<Triple<Int, Int, List<Pair<String?, Int>>>>,
        method: MutableMethod,
    ) {
        val code = method.implementation!!
        val instructions = code.instructions.toList()
        val addresses = addresses(instructions)
        val returns = cleanInstructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_OBJECT }
            .map { it.index to (it.value as OneRegisterInstruction).registerA }
        val registers = returns.map { it.second }.distinct()

        assertEquals("try blocks", cleanTries, tryShapes(code))
        assertEquals("two instructions per returned register", cleanInstructions.size + 2 * registers.size, instructions.size)
        cleanInstructions.indices.filter { index -> returns.none { it.first == index } }.forEach { index ->
            assertEquals("instruction $index", cleanInstructions[index].opcode, instructions[index].opcode)
        }
        assertEquals("filter calls", registers.size, instructions.count { it.isFilterCall() })
        assertEquals("returns", registers.size, instructions.count { it.opcode == Opcode.RETURN_OBJECT })

        returns.forEach { (index, register) ->
            val jump = instructions[index]
            assertTrue("return $index is a goto, not ${jump.opcode}", jump.opcode in gotos)
            val targetIndex = addresses.indexOf(addresses[index] + (jump as OffsetInstruction).codeOffset)
            assertTrue("block $targetIndex follows the original code", targetIndex >= cleanInstructions.size)
            val call = instructions[targetIndex]
            assertTrue("block $targetIndex starts with the filter call", call.isFilterCall())
            call as RegisterRangeInstruction
            assertEquals(register, call.startRegister)
            assertEquals(1, call.registerCount)
            val back = instructions[targetIndex + 1]
            assertEquals(Opcode.RETURN_OBJECT, back.opcode)
            assertEquals(register, (back as OneRegisterInstruction).registerA)
            code.tryBlocks.forEach { tryBlock ->
                assertTrue("filter call at ${addresses[targetIndex]} is outside the try at ${tryBlock.startCodeAddress}",
                    addresses[targetIndex] !in tryBlock.range())
            }
        }
    }

    private fun Instruction.isFilterCall(): Boolean {
        val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return opcode == Opcode.INVOKE_STATIC_RANGE && reference.definingClass == extension &&
            reference.name == "filter" && reference.parameterTypes.map { it.toString() } == listOf(followFeedList) &&
            reference.returnType == "V"
    }

    /** The loader as FollowFeedFingerprint reads it: static, returns the list, logs "feed", reads the items. */
    private fun Method.isFollowFeedLoader(): Boolean {
        if (accessFlags and AccessFlags.STATIC.value == 0 || returnType != followFeedList || parameterTypes.size < 2) {
            return false
        }
        var feed = false
        var items = false
        implementation?.instructions?.forEach { instruction ->
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is StringReference -> if (reference.string == "feed") feed = true
                is MethodReference -> if (reference.definingClass == followFeedList && reference.name == "getItems") items = true
                else -> {}
            }
        }
        return feed && items
    }

    /**
     * The 47.6.3 loader's shape: v1 is null, then holds a List; the loaded list in v0 returns
     * from the last instruction of a try block whose handler returns v1, and a branch into that
     * return skips the try's own null write, so v1 is a List there.
     */
    private fun loaderShape(): MutableMethod = smali(
        """
            const/4 v1, 0x0
            invoke-static {}, Lcom/example/Source;->items()Ljava/util/List;
            move-result-object v1
            invoke-static {}, Lcom/example/Source;->load()$followFeedList
            move-result-object v0
            if-nez p0, :done
            const/4 v1, 0x0
            invoke-static {v0}, Lcom/example/Source;->touch($followFeedList)V
            :done
            return-object v0
            return-object v1
        """,
    ).apply {
        implementation!!.run {
            addCatch("Ljava/lang/Exception;", newLabelForIndex(6), newLabelForIndex(9), newLabelForIndex(9))
        }
    }

    private fun smali(body: String) = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Loader;", "load", listOf(ImmutableMethodParameter("Z", null, null)), followFeedList,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(3, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun tryShapes(code: MethodImplementation): List<Triple<Int, Int, List<Pair<String?, Int>>>> =
        code.tryBlocks.map { tryBlock ->
            Triple(
                tryBlock.startCodeAddress,
                tryBlock.codeUnitCount,
                tryBlock.exceptionHandlers.map { it.exceptionType to it.handlerCodeAddress },
            )
        }

    private fun TryBlock<*>.range() = startCodeAddress until startCodeAddress + codeUnitCount

    private fun addresses(instructions: List<Instruction>): List<Int> {
        var address = 0
        return instructions.map { instruction -> address.also { address += instruction.codeUnits } }
    }

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
