/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.patch.patch
import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val SERVICE = "Lcom/ss/android/ugc/aweme/spi/IHomeSidebarService;"
private const val HELPER = "LX/0Helper;"
private const val LAZY = "LX/0Lazy;"

/**
 * What the side menu swipe switch (#213) hooks, held to each declared build: every place the pager
 * and the swipe handlers read the sidebar service out of the edge-swipe helper and ask it whether
 * the menu is on offer, with the answer filtered through the switch right after it is read and
 * TikTok's own code left as it was.
 */
class SidebarSwipeAnchorsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `each declared build has its side menu swipe asks`() {
        // 47.7.2 inlined 47.6.3's one helper method into the pager's touch dispatch and both
        // swipe handlers (2026-11-05).
        val expected = mapOf("47.7.3" to ("LX/07nx;" to 3))
        assertEquals(Fixtures.declaredVersions().toSet(), expected.keys)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
                .associateBy { it.type }
            val asks = classes.values.flatMap { owner -> owner.methods.flatMap { sidebarSwipeAsks(it) { type -> classes[type] } } }
            val (helper, count) = expected.getValue(version)
            assertEquals("$version: swipe asks", count, asks.size)
            assertEquals("$version: one helper", setOf(helper), asks.map { it.helper }.toSet())
        }
    }

    @Test
    fun `the hook assembles on each declared build and filters each answer right after TikTok reads it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = patch(name = "side menu swipe probe") {
                execute {
                    val before = SidebarSwipeFingerprint.matchAll().associate { match ->
                        match.originalMethod.let { "${it.definingClass}->${it.name}" } to
                            match.originalMethod.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.map { it.opcode }
                    }
                    SidebarSwipeFingerprint.clearMatch()
                    assertTrue("$version: the hook went in", hookSidebarSwipe(version))
                    var filtered = 0
                    SidebarSwipeFingerprint.matchAll().forEach { match ->
                        val key = match.method.let { "${it.definingClass}->${it.name}" }
                        val code = match.method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
                        val hooks = code.indices.filter {
                            code[it].getReference<MethodReference>()?.name == "sidebarSwipeOffered"
                        }
                        hooks.forEach { at ->
                            val answer = code[at - 1] as OneRegisterInstruction
                            assertEquals("$key: after the answer", Opcode.MOVE_RESULT, answer.opcode)
                            assertEquals("$key: the answer goes in", answer.registerA, (code[at] as RegisterRangeInstruction).startRegister)
                            assertEquals("$key: and comes back", answer.registerA, (code[at + 1] as OneRegisterInstruction).registerA)
                        }
                        filtered += hooks.size
                        val added = hooks.flatMap { listOf(it, it + 1) }.toSet()
                        assertEquals("$key: TikTok's own code", before.getValue(key),
                            code.filterIndexed { index, _ -> index !in added }.map { it.opcode })
                    }
                    assertEquals("$version: every ask filtered", 3, filtered)
                    checked = true
                }
            }
            SidebarSwipeFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            SidebarSwipeFingerprint.clearMatch()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `only a null-checked ask of the service out of the hot-area helper's holder is taken`() {
        val classes = mapOf(HELPER to helper(hotArea = true), "LX/0Plain;" to helper(hotArea = false, type = "LX/0Plain;"))
        fun asks(smali: String) = sidebarSwipeAsks(method(smali)) { classes[it] }
        assertEquals(listOf(SidebarSwipeAsk(6, 1, HELPER)), asks(ask()))
        assertEquals("another service", emptyList<SidebarSwipeAsk>(), asks(ask(service = "LX/0Other;")))
        assertEquals("no hot-area test", emptyList<SidebarSwipeAsk>(), asks(ask(owner = "LX/0Plain;")))
        assertEquals("no null check", emptyList<SidebarSwipeAsk>(), asks(ask().replace("if-eqz v0, :no\n", "")))
        assertEquals("a number, not a yes or no", emptyList<SidebarSwipeAsk>(), asks(ask(returns = "I")))
        assertEquals("not out of a holder", emptyList<SidebarSwipeAsk>(),
            asks(ask().replace("iget-object v0, p0, $HELPER->holder:$LAZY", "sget-object v0, $HELPER->holder:$LAZY")))
    }

    @Test
    fun `the filter takes the answer and hands it back in the same register`() {
        val method = method(ask())
        val asks = sidebarSwipeAsks(method) { if (it == HELPER) helper(hotArea = true) else null }
        method.filterSidebarSwipeAsks(asks)
        val code = method.implementation!!.instructions.toList()
        val call = code[7] as RegisterRangeInstruction
        assertEquals("sidebarSwipeOffered", call.getReference<MethodReference>()!!.name)
        assertEquals(listOf(1, 1), listOf(call.startRegister, call.registerCount))
        assertEquals(Opcode.MOVE_RESULT, code[8].opcode)
        assertEquals(1, (code[8] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[9].opcode)
    }

    private fun ask(service: String = SERVICE, owner: String = HELPER, returns: String = "Z") = """
        iget-object v0, p0, $owner->holder:$LAZY
        invoke-interface { v0 }, $LAZY->getValue()Ljava/lang/Object;
        move-result-object v0
        check-cast v0, $service
        if-eqz v0, :no
        invoke-interface { v0 }, $service->LIZLLL()$returns
        move-result v1
        if-eqz v1, :no
        const/4 v0, 0x1
        return v0
        :no
        const/4 v0, 0x0
        return v0
    """.trimIndent()

    private fun method(smali: String) = MutableMethod(ImmutableMethod("LX/0Pager;", "dispatch", emptyList(), "Z",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(3, emptyList(), null, null))).apply { addInstructionsWithLabels(0, smali) }

    /** The helper's class, with the `(FF)Z` hot-area test or without it. */
    private fun helper(hotArea: Boolean, type: String = HELPER): ClassDef {
        val methods: List<Method> = if (hotArea) {
            listOf(ImmutableMethod(type, "LIZIZ", listOf("F", "F").map { ImmutableMethodParameter(it, null, null) }, "Z",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null))
        } else {
            emptyList()
        }
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods)
    }
}
