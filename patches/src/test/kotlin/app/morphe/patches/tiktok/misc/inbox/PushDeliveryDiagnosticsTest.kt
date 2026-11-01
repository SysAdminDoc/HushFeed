/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipFile

/** Reads the three native DEX entries only, then applies the real insertion function. */
class PushDeliveryDiagnosticsTest {
    private val classes: List<ClassDef> by lazy {
        val owners = PUSH_NATIVE_METHODS.map { it.substringBefore("->") }.toSet()
        ZipFile(Fixtures.apkOf("47.6.3")).use { apk ->
            listOf("classes2.dex", "classes8.dex", "classes21.dex").flatMap { name ->
                apk.getInputStream(apk.getEntry(name)).buffered().use { input ->
                    DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).classes.filter { it.type in owners }
                }
            }
        }
    }
    private fun native(descriptor: String): Method = classes.flatMap { it.methods }.single { it.pushDescriptor() == descriptor }
    private fun probe(instruction: Instruction) = instruction.getReference<MethodReference>()?.definingClass == PUSH_REPORT

    @Test fun diagnosticsRemainAnUnnamedDependency() {
        assertNull(pushDeliveryDiagnosticsPatch.name)
        assertTrue(pushDeliveryDiagnosticsPatch in notificationControlsPatch.dependencies)
    }

    @Test fun onlyTheExactGlobalProfileUsesNativeAnchors() {
        assertTrue(supportsNativePushProfile("com.zhiliaoapp.musically", "47.6.3"))
        assertFalse(supportsNativePushProfile("com.ss.android.ugc.trill", "47.6.3"))
        assertFalse(supportsNativePushProfile("com.zhiliaoapp.musically", "47.4.3"))
        assertFalse(supportsNativePushProfile(null, "47.6.3"))
        assertFalse(supportsNativePushProfile("com.zhiliaoapp.musically", null))
    }

    @Test fun fixturePlansPreserveEveryNativeArgumentBranchAndHandler() {
        val plans = planNativePushDiagnostics(classes)
        assertEquals(PUSH_NATIVE_METHODS, plans.keys.map { it.pushDescriptor() }.toSet())
        for ((source, plan) in plans) {
            val method = MutableMethod(source)
            val before = shape(method)
            val originalHandlerKinds = handlerKinds(method)
            val registers = method.implementation!!.registerCount
            method.applyPushObservations(plan)
            assertEquals(registers, method.implementation!!.registerCount)
            assertTrue("Native flow changed in " + source.pushDescriptor(), before == shape(method))
            val added = method.implementation!!.instructions.filter(::probe)
            assertEquals(plan.size, added.size)
            for (instruction in added) {
                val call = instruction.getReference<MethodReference>()!!
                assertTrue(call.parameterTypes.isEmpty())
                assertEquals("V", call.returnType)
                assertEquals(0, (instruction as FiveRegisterInstruction).registerCount)
            }
            assertEquals("Native handler entry opcodes changed", originalHandlerKinds, handlerKinds(method))
        }
    }

    @Test fun cacheAndServerAcceptanceAreDifferentSourceBranches() {
        val plan = planPushObservations(native(TOKEN_UPLOAD))
        val cached = plan.single { it.event == "uploadCached" }
        val attempted = plan.single { it.event == "uploadAttempted" }
        val accepted = plan.single { it.event == "uploadAccepted" }
        assertTrue(cached.index < attempted.index)
        assertTrue(attempted.index < accepted.index)
        assertEquals(8, plan.count { it.event == "uploadException" })
        assertTrue(plan.filter { it.event == "uploadException" }.none { it.atLabel })
    }

    @Test fun aSignatureOnlyCompletionStubDoesNotQualify() {
        val method = MutableMethod(native(TOKEN_RESULT))
        while (method.implementation!!.instructions.size > 1) method.implementation!!.removeInstruction(0)
        method.replaceInstruction(0, "return-void")
        assertThrows(PatchException::class.java) { planPushObservations(method) }
    }

    @Test fun missingSuccessPredicateAndMissingCacheCallbackFailClosed() {
        val missingSuccess = MutableMethod(native(TOKEN_UPLOAD))
        val success = missingSuccess.implementation!!.instructions.indexOfFirst { it.getReference<StringReference>()?.string == "success" }
        missingSuccess.replaceInstruction(success, "nop")
        assertThrows(PatchException::class.java) { planPushObservations(missingSuccess) }
        val missingCache = MutableMethod(native(TOKEN_UPLOAD))
        val cache = planPushObservations(missingCache).single { it.event == "uploadCached" }.index
        missingCache.replaceInstruction(cache, "nop")
        assertThrows(PatchException::class.java) { planPushObservations(missingCache) }
    }

    @Test fun wrongCompletionBranchAndMissingHandlerCannotQualify() {
        val method = MutableMethod(native(TOKEN_RESULT))
        val branch = method.implementation!!.instructions.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        method.replaceInstruction(branch, "nop")
        assertThrows(PatchException::class.java) { planPushObservations(method) }
        val handler = MutableMethod(native(NATIVE_MESSAGE))
        val call = handler.implementation!!.instructions.indexOfFirst {
            it.getReference<MethodReference>()?.definingClass == "Lcom/ss/android/ugc/awemepushlib/manager/MessageShowHandler;"
        }
        handler.replaceInstruction(call, "nop")
        assertThrows(PatchException::class.java) { planPushObservations(handler) }
    }

    @Test fun invertedSuccessBranchCannotBeLabeledAccepted() {
        val method = MutableMethod(native(TOKEN_UPLOAD))
        val equals = method.implementation!!.instructions.indexOfFirst {
            it.getReference<MethodReference>()?.let { reference -> reference.definingClass == "Ljava/lang/String;" && reference.name == "equals" } == true
        }
        val branch = method.implementation!!.instructions[equals + 2] as BuilderInstruction21t
        method.implementation!!.replaceInstruction(equals + 2, BuilderInstruction21t(Opcode.IF_NEZ, branch.registerA, branch.target))
        assertThrows(PatchException::class.java) { planPushObservations(method) }
    }

    @Test fun missingAndDuplicateOwnersCannotQualify() {
        assertThrows(PatchException::class.java) { planNativePushDiagnostics(classes.drop(1)) }
        assertThrows(PatchException::class.java) { planNativePushDiagnostics(classes + classes.first()) }
    }

    private fun addresses(instructions: List<Instruction>): List<Int> {
        var address = 0
        return instructions.map { instruction -> address.also { address += instruction.codeUnits } } + address
    }

    private fun handlerKinds(method: Method): List<Opcode> {
        val instructions = method.implementation!!.instructions.toList()
        val offsets = addresses(instructions)
        return method.implementation!!.tryBlocks.flatMap { block -> block.exceptionHandlers.map { handler ->
            instructions[offsets.indexOf(handler.handlerCodeAddress)].opcode
        } }
    }

    /** Strip probe calls and normalize branch/try targets back to the original instruction positions. */
    private fun shape(method: Method): Pair<List<String>, List<String>> {
        val implementation = method.implementation!!
        val instructions = implementation.instructions.toList()
        val addresses = addresses(instructions)
        val retained = instructions.indices.filterNot { probe(instructions[it]) }
        fun target(address: Int): Int {
            var index = addresses.indexOf(address)
            check(index >= 0)
            while (index < instructions.size && probe(instructions[index])) index++
            return if (index == instructions.size) retained.size else retained.indexOf(index).also { check(it >= 0) }
        }
        val body = retained.map { index ->
            val instruction = instructions[index]
            val registers = when (instruction) {
                is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
                is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
                is ThreeRegisterInstruction -> listOf(instruction.registerA, instruction.registerB, instruction.registerC)
                is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
                is OneRegisterInstruction -> listOf(instruction.registerA)
                else -> emptyList()
            }
            val opcode = if (instruction.opcode.name.startsWith("GOTO")) "GOTO" else instruction.opcode.name
            val reference = instruction.getReference<Reference>()?.toString().orEmpty()
            val literal = (instruction as? WideLiteralInstruction)?.wideLiteral?.toString().orEmpty()
            val destination = (instruction as? OffsetInstruction)?.let { target(addresses[index] + it.codeOffset) }?.toString().orEmpty()
            listOf(opcode, registers.toString(), reference, literal, destination).joinToString("|")
        }
        val handlers = implementation.tryBlocks.map { block ->
            listOf(target(block.startCodeAddress), target(block.startCodeAddress + block.codeUnitCount),
                block.exceptionHandlers.map { it.exceptionType to target(it.handlerCodeAddress) }).toString()
        }
        return body to handlers
    }
}
