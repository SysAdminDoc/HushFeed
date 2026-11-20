package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RESULT = "LX/CacheLoadResult;"
private const val PAYLOAD = "LX/CachePayload;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;"

/** The cache result handoff shared by TikTok 46.7.3 and 46.8.3. */
class CacheDeliveryHookTest {
    private val payloadField = ImmutableFieldReference(RESULT, "value", PAYLOAD)
    private val awemeField = ImmutableFieldReference(PAYLOAD, "aweme", AWEME)
    private val successField = ImmutableFieldReference(RESULT, "success", "Z")

    /**
     * 47.8.3 declares the payload field final, and the normalizer that nulls it is another class,
     * so ART threw IllegalAccessError at the write on some phones seconds after launch (#472).
     */
    @Test
    fun `the payload field stops being final so the normalizer can null it, and nothing else changes`() {
        val finalPublic = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val result = MutableClass(
            ImmutableClassDef(
                RESULT, finalPublic, "Ljava/lang/Object;", null, null, null,
                listOf(
                    ImmutableField(RESULT, "value", PAYLOAD, finalPublic, null, null, null),
                    ImmutableField(RESULT, "success", "Z", finalPublic, null, null, null),
                ),
                emptyList(),
            ),
        )
        result.letOtherClassesWrite(payloadField)
        assertEquals(AccessFlags.PUBLIC.value, result.fields.single { it.name == "value" }.accessFlags)
        assertEquals(finalPublic, result.fields.single { it.name == "success" }.accessFlags)
        assertEquals(finalPublic, result.accessFlags)
    }

    @Test
    fun `the rebuilt chain turns a rejected payload into the native empty-result signal`() {
        val method = normalizer()
        method.filterNormalizedCacheDelivery(payloadField, awemeField)
        val result = CacheResult(payload = CachePayload(Any()))

        val calls = runHook(method, result, keep = false)

        assertNull(result.payload)
        assertEquals(1, calls)
    }

    @Test
    fun `the rebuilt chain preserves accepted and already empty results`() {
        val acceptedPayload = CachePayload(Any())
        val accepted = CacheResult(payload = acceptedPayload)
        val acceptedMethod = normalizer().apply {
            filterNormalizedCacheDelivery(payloadField, awemeField)
        }
        assertEquals(1, runHook(acceptedMethod, accepted, keep = true))
        assertSame(acceptedPayload, accepted.payload)

        val empty = CacheResult(payload = null)
        val emptyMethod = normalizer().apply {
            filterNormalizedCacheDelivery(payloadField, awemeField)
        }
        assertEquals(0, runHook(emptyMethod, empty, keep = false))
        assertNull(empty.payload)
    }

    @Test
    fun `the rebuilt chain calls the feed predicate with the cached Aweme`() {
        val method = normalizer().apply {
            filterNormalizedCacheDelivery(payloadField, awemeField)
        }
        val call = method.implementation!!.instructions.single {
            it.opcode == Opcode.INVOKE_STATIC_RANGE
        }
        val target = (call as ReferenceInstruction).reference as MethodReference

        assertEquals(EXTENSION, target.definingClass)
        assertEquals("shouldKeepNormalizedCache", target.name)
        assertEquals(listOf(AWEME), target.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", target.returnType)
        assertEquals(0, (call as RegisterRangeInstruction).startRegister)
        assertEquals(1, call.registerCount)
    }

    @Test
    fun `the normalizer refuses a frame that cannot encode its result parameter`() {
        assertThrows(PatchException::class.java) {
            normalizer(registers = 1).filterNormalizedCacheDelivery(payloadField, awemeField)
        }
        val high = assertThrows(PatchException::class.java) {
            normalizer(registers = 17).filterNormalizedCacheDelivery(payloadField, awemeField)
        }
        assertTrue(high.message!!.contains("cannot be named"))
    }

    @Test
    fun `a rejected reach-bottom result is marked unsuccessful`() {
        val rejected = CacheResult(success = true, payload = CachePayload(Any()))
        val rejectedMethod = reachBottom().apply {
            filterReachBottomCacheDelivery(payloadField, awemeField, successField)
        }
        assertEquals(1, runHook(rejectedMethod, rejected, keep = false, predicate = "shouldKeepReachBottomCache"))
        assertEquals(false, rejected.success)

        val accepted = CacheResult(success = true, payload = CachePayload(Any()))
        val acceptedMethod = reachBottom().apply {
            filterReachBottomCacheDelivery(payloadField, awemeField, successField)
        }
        assertEquals(1, runHook(acceptedMethod, accepted, keep = true, predicate = "shouldKeepReachBottomCache"))
        assertEquals(true, accepted.success)
    }

    @Test
    fun `the removed recommendation-card surface can be absent but cannot drift silently`() {
        assertNull(selectRecUserCardInsertion(insertion = null, surfacePresent = false))

        val insertion = normalizer()
        assertSame(insertion, selectRecUserCardInsertion(insertion, surfacePresent = true))

        val error = assertThrows(PatchException::class.java) {
            selectRecUserCardInsertion(insertion = null, surfacePresent = true)
        }
        assertTrue(error.message!!.contains("insertion method was not found"))
    }

    @Test
    fun `the recommendation-card selector accepts both known parameter orders`() {
        fun candidate(parameters: List<String>) = ImmutableMethod(
            "LX/RecUserCard;",
            "insert",
            parameters.map { ImmutableMethodParameter(it, null, null) },
            "Lkotlin/Pair;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            null,
        )

        assertTrue(
            candidate(
                listOf("I", "Ljava/util/List;", "Ljava/lang/String;", "Lkotlin/jvm/functions/Function0;"),
            ).isRecUserCardInsertion(),
        )
        assertTrue(
            candidate(
                listOf("I", "Ljava/lang/String;", "Ljava/util/List;", "Lkotlin/jvm/functions/Function0;"),
            ).isRecUserCardInsertion(),
        )
    }

    private fun normalizer(registers: Int = 2) = method(
        name = "normalize",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        registers = registers,
    )

    private fun reachBottom() = method(
        name = "deliver",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        registers = 4,
    )

    private fun method(name: String, accessFlags: Int, registers: Int) = MutableMethod(
        ImmutableMethod(
            "LX/CacheConsumer;",
            name,
            listOf(ImmutableMethodParameter(RESULT, null, null)),
            "V",
            accessFlags,
            null,
            null,
            ImmutableMethodImplementation(
                registers,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ),
    )

    /** Executes the small injected prefix until TikTok's original return instruction. */
    private fun runHook(
        method: MutableMethod,
        result: CacheResult,
        keep: Boolean,
        // Each cache route calls its own predicate, so a report can say which one ran.
        predicate: String = "shouldKeepNormalizedCache",
    ): Int {
        val instructions = method.implementation!!.instructions.toList()
        val addresses = IntArray(instructions.size)
        var address = 0
        instructions.forEachIndexed { index, instruction ->
            addresses[index] = address
            address += instruction.codeUnits
        }
        val indexAtAddress = addresses.withIndex().associate { it.value to it.index }
        val registers = arrayOfNulls<Any>(method.implementation!!.registerCount)
        registers[registers.lastIndex] = result
        var predicateResult = false
        var calls = 0
        var index = 0

        while (index < instructions.size) {
            val instruction = instructions[index]
            var next = index + 1
            when (instruction.opcode) {
                Opcode.NOP -> Unit
                Opcode.MOVE_OBJECT_FROM16 -> {
                    instruction as TwoRegisterInstruction
                    registers[instruction.registerA] = registers[instruction.registerB]
                }
                Opcode.IGET_OBJECT -> {
                    instruction as TwoRegisterInstruction
                    val field = (instruction as ReferenceInstruction).reference as FieldReference
                    registers[instruction.registerA] = when (field) {
                        payloadField -> (registers[instruction.registerB] as CacheResult).payload
                        awemeField -> (registers[instruction.registerB] as CachePayload).aweme
                        else -> error("Unexpected field read: $field")
                    }
                }
                Opcode.INVOKE_STATIC_RANGE -> {
                    val target = (instruction as ReferenceInstruction).reference as MethodReference
                    assertEquals(predicate, target.name)
                    val call = instruction as RegisterRangeInstruction
                    check(registers[call.startRegister] != null) { "The predicate was handed no Aweme" }
                    predicateResult = keep
                    calls++
                }
                Opcode.MOVE_RESULT ->
                    registers[(instruction as OneRegisterInstruction).registerA] = predicateResult
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val value = registers[(instruction as OneRegisterInstruction).registerA]
                    val zero = value == null || value == false || value == 0
                    val taken = instruction.opcode == Opcode.IF_EQZ && zero ||
                        instruction.opcode == Opcode.IF_NEZ && !zero
                    if (taken) {
                        val target = addresses[index] + (instruction as OffsetInstruction).codeOffset
                        next = indexAtAddress[target] ?: error("No instruction at branch target $target")
                    }
                }
                Opcode.CONST_4 ->
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        (instruction as WideLiteralInstruction).wideLiteral.toInt()
                Opcode.IPUT_OBJECT -> {
                    instruction as TwoRegisterInstruction
                    val owner = registers[instruction.registerB] as CacheResult
                    owner.payload = when (val value = registers[instruction.registerA]) {
                        0, null -> null
                        else -> value as CachePayload
                    }
                }
                Opcode.IPUT_BOOLEAN -> {
                    instruction as TwoRegisterInstruction
                    val owner = registers[instruction.registerB] as CacheResult
                    val value = registers[instruction.registerA]
                    owner.success = value != null && value != false && value != 0
                }
                Opcode.RETURN_VOID -> return calls
                else -> error("Unexpected instruction: ${instruction.opcode}")
            }
            index = next
        }
        error("The hook did not reach the host return")
    }

    private data class CacheResult(
        var success: Boolean = true,
        var payload: CachePayload?,
    )

    private data class CachePayload(val aweme: Any?)
}
