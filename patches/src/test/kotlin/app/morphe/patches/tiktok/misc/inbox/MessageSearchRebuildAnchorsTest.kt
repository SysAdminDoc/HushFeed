/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Rebuild message search calls TikTok's message search engine (its reset and its catch-up) and
 * the coroutine helpers it makes and cancels scopes with. Each is found from a log line and the
 * shapes around it, so every declared build is held here to have exactly one of each, public
 * where the extension calls it, and the four bridges written over the extension's stubs are held
 * to call what's there and to pass ART's register typing.
 */
class MessageSearchRebuildAnchorsTest {
    private val extension = "Lapp/morphe/extension/tiktok/inbox/MessageSearchRebuild;"

    private fun matches(classes: List<ClassDef>, fingerprint: Fingerprint) = classes.flatMap { classDef ->
        classDef.methods.filter { fingerprint.takes(it, classDef) }.map { classDef to it }
    }

    private class Build(val version: String, val classes: List<ClassDef>) {
        val byType = classes.associateBy { it.type }
    }

    private fun eachBuild(check: (Build) -> Unit) = Fixtures.forEachDeclared { apk ->
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        check(Build(Fixtures.versionOf(apk), container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .toList()))
    }

    private fun bridges(build: Build): MessageSearchBridges {
        val (engine, catchUp) = matches(build.classes, MessageSearchCatchUpFingerprint).single()
        val reset = matches(build.classes, MessageSearchResetFingerprint).single().second
        val kit = build.byType.getValue(scopeCancel(engine, catchUp.parameterTypes.single().toString()).definingClass)
        return MessageSearchBridges(engine, catchUp, reset, kit) { build.byType[it] }
    }

    /** The extension's stubs as javac and d8 leave them: one register, the unpatched answer. */
    private fun stub(name: String, parameters: List<String>, returnType: String, body: String) =
        MutableMethod(ImmutableMethod(extension, name, parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType, AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(1, emptyList(), null, null))).apply { addInstructionsWithLabels(0, body) }

    private fun stubs() = listOf(
        stub("resetIndex", emptyList(), "I", "const/4 v0, -0x1\nreturn v0"),
        stub("newScope", emptyList(), "Ljava/lang/Object;", "const/4 v0, 0x0\nreturn-object v0"),
        stub("startIndex", listOf("Ljava/lang/Object;"), "V", "return-void"),
        stub("cancelScope", listOf("Ljava/lang/Object;"), "V", "return-void"),
    )

    @Test
    fun `each declared build has one search engine with one reset, one catch-up and one way to cancel`() {
        eachBuild { build ->
            val version = build.version
            val catchUps = matches(build.classes, MessageSearchCatchUpFingerprint)
            assertEquals("$version catch-up", 1, catchUps.size)
            val (engine, catchUp) = catchUps.single()
            val resets = matches(build.classes, MessageSearchResetFingerprint)
            assertEquals("$version reset", 1, resets.size)
            assertEquals("$version: the reset is the engine's", engine.type, resets.single().first.type)
            assertTrue("$version: the engine is a singleton", engine.fields.count {
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.type == engine.type
            } == 1)
            val scopeType = catchUp.parameterTypes.single().toString()
            val cancel = scopeCancel(engine, scopeType)
            val kit = build.byType[cancel.definingClass]
            assertNotNull("$version: ${cancel.definingClass} is in the app", kit)
            val declared = kit!!.methods.single { it.name == cancel.name && it.parameterTypes == cancel.parameterTypes }
            assertTrue("$version: the cancel is public and static",
                AccessFlags.PUBLIC.isSet(declared.accessFlags) && AccessFlags.STATIC.isSet(declared.accessFlags))
            assertTrue("$version: the scope type is an interface",
                AccessFlags.INTERFACE.isSet(build.byType.getValue(scopeType).accessFlags))
            bridges(build)
        }
    }

    @Test
    fun `the bridges call what each build has and pass the register typing`() {
        eachBuild { build ->
            val version = build.version
            val bridges = bridges(build)
            for (stub in stubs()) {
                val filled = bridges.bridged(stub)
                assertEquals("$version ${stub.name} problems", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(filled))
                assertEquals("$version ${stub.name} registers", 3, filled.implementation!!.registerCount)
                val code = filled.implementation!!.instructions.toList()
                val calls = code.mapNotNull { it.getReference<MethodReference>() }
                    .filter { it.definingClass != "Ljava/lang/Boolean;" && it.definingClass != extension }
                assertTrue("$version ${stub.name} calls TikTok", calls.isNotEmpty())
                calls.forEach { call ->
                    val owner = build.byType[call.definingClass]
                    assertNotNull("$version: ${call.definingClass} is in the app", owner)
                    val method: Method? = owner!!.methods.singleOrNull {
                        it.name == call.name && it.returnType == call.returnType &&
                            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
                    }
                    assertNotNull("$version ${stub.name}: $call is declared", method)
                    assertTrue("$version ${stub.name}: $call is public", AccessFlags.PUBLIC.isSet(method!!.accessFlags))
                }
                code.mapNotNull { it.getReference<FieldReference>() }.forEach { read ->
                    val field = build.byType.getValue(read.definingClass).fields.singleOrNull { it.name == read.name && it.type == read.type }
                    assertNotNull("$version ${stub.name}: $read is declared", field)
                    assertTrue("$version ${stub.name}: $read is public and static",
                        AccessFlags.PUBLIC.isSet(field!!.accessFlags) && AccessFlags.STATIC.isSet(field.accessFlags))
                }
            }
        }
    }

    @Test
    fun `a build without a scope maker stops the patch before anything is written`() {
        eachBuild { build ->
            val (engine, catchUp) = matches(build.classes, MessageSearchCatchUpFingerprint).single()
            val reset = matches(build.classes, MessageSearchResetFingerprint).single().second
            val kit = build.byType.getValue(scopeCancel(engine, catchUp.parameterTypes.single().toString()).definingClass)
            try {
                MessageSearchBridges(engine, catchUp, reset, kit) { null }
                fail("${build.version}: built bridges with no scope maker")
            } catch (expected: PatchException) {
                assertTrue(expected.message!!, expected.message!!.contains("scope maker"))
            }
        }
    }
}
