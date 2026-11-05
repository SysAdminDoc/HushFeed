/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.remoteconfig

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Freeze TikTok's experiments returns early from, held to each declared build.
 *
 * <p>The two chunk fingerprints take one method each and the whole-response one takes its one or
 * two copies, all different methods, each void with a local for the switch's answer and nothing
 * jumping to its first instruction, so the guard in front of it can't be stepped over. The optimized chunk store is called only from the chunk observer and the two
 * lambdas that hand it a chunk, so holding it holds every optimized chunk and nothing else.
 */
class RemoteConfigStoreAnchorsTest {
    private companion object {
        /** How many copies of the whole-response store each declared build has, read from its dex. */
        val RESPONSE_STORE_COPIES = mapOf("47.7.2" to 2)
    }

    @Test
    fun `the three server settings stores resolve on each build and can take a guard`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)

            fun taken(fingerprint: Fingerprint) = classes.values.flatMap { classDef ->
                classDef.methods.filter { fingerprint.takes(it, classDef) }
            }
            fun named(methods: List<Method>) = methods.map { "${it.definingClass}->${it.name}" }

            // The whole-response store is the same code twice on 47.6.3, one per request type, and
            // the patch holds each copy. A new build declares its own count here.
            val responses = taken(ResponseStoreFingerprint)
            val copies = RESPONSE_STORE_COPIES[version]
                ?: throw AssertionError("$version: say how many whole-response stores it has, ResponseStoreFingerprint takes ${named(responses)}")
            assertEquals("$version: ResponseStoreFingerprint takes ${named(responses)}", copies, responses.size)
            val chunks = listOf(ChunkStoreFingerprint, OptimizedChunkStoreFingerprint).map { fingerprint ->
                val methods = taken(fingerprint)
                assertEquals("$version: ${fingerprint.javaClass.simpleName} takes ${named(methods)}", 1, methods.size)
                methods.single()
            }
            val stores = responses + chunks
            stores.forEach { assertGuardable(version, it) }
            assertEquals("$version: two fingerprints take the same store", stores.size, named(stores).toSet().size)

            val optimized = chunks[1]
            assertNotEquals("$version: the optimized store sits in the chunk store's class",
                chunks[0].definingClass, optimized.definingClass)
            val callers = sortedSetOf<String>()
            for (classDef in classes.values) {
                for (method in classDef.methods) {
                    val calls = method.implementation?.instructions ?: continue
                    if (calls.any { instruction ->
                            instruction.getReference<MethodReference>()?.let {
                                it.definingClass == optimized.definingClass && it.name == optimized.name &&
                                    it.parameterTypes.size == optimized.parameterTypes.size
                            } == true
                        }
                    ) callers += "${method.definingClass}->${method.name}"
                }
            }
            assertTrue("$version: nothing calls the optimized store", callers.isNotEmpty())
            assertTrue("$version: the optimized store is called from $callers",
                // 47.7.2's R8 names the two lambdas' method plain accept, without the $ suffix.
                callers.all { it.endsWith("->onNext") || it.endsWith("->accept") || it.contains("->accept$") })
        }
    }

    private fun assertGuardable(version: String, store: Method) {
        val at = "$version: ${store.definingClass}->${store.name}"
        assertEquals("$at returns ${store.returnType}", "V", store.returnType)
        val body = store.implementation!!
        assertTrue("$at has no local for the switch's answer",
            body.registerCount - store.numberOfParameterRegisters >= 1)
        val instructions = body.instructions.toList()
        val addresses = instructions.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertFalse("$at: something jumps back to the first instruction",
            instructions.indices.any { i ->
                val branch = instructions[i] as? OffsetInstruction
                branch != null && addresses[i] + branch.codeOffset == 0
            })
        assertFalse("$at: an exception handler lands on the first instruction",
            body.tryBlocks.any { block -> block.exceptionHandlers.any { it.handlerCodeAddress == 0 } })
    }
}
