/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Hide profile tabs hooks, held to each declared build: the class that reads a profile's tab
 * types, public with one static name map, and the pager's two tab lists, each read by the pager at
 * a move-result the filter can follow without landing inside a branch.
 */
class ProfileTabsAnchorsTest {
    @Test
    fun `the tab names and both tab lists are where the hooks expect them`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val types = mutableListOf<Pair<Method, ClassDef>>()
            val orders = mutableListOf<Pair<Method, ClassDef>>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (ProfileTabTypesFingerprint.takes(method, classDef)) types += method to classDef
                        if (ProfileTabOrderFingerprint.takes(method, classDef)) orders += method to classDef
                    }
                }
            }
            assertEquals("$version: tab type readers ${types.map { it.first.definingClass }}", 1, types.size)
            assertEquals("$version: tab order reads ${orders.map { it.first.definingClass }}", 1, orders.size)

            val owner = types.single().second
            assertTrue("$version: ${owner.type} is public", AccessFlags.PUBLIC.isSet(owner.accessFlags))
            val names = owner.methods.filter(::isProfileTabNameMap)
            assertEquals("$version: name maps on ${owner.type}", 1, names.size)

            val (order, pagerClass) = orders.single()
            val pager = pagerClass.methods.map(::MutableMethod)
            val lists = profileTabLists(pager, order)
            assertEquals("$version: tab lists ${lists.map { it.name }}", 2, lists.size)
            assertTrue("$version: one list takes the User", lists.any { it.parameterTypes.single().toString() == USER })
            assertTrue("$version: one list takes the id", lists.any { it.parameterTypes.single().toString() == "Ljava/lang/String;" })

            val sites = profileTabSites(pager, lists)
            assertTrue("$version: reads of the tab lists $sites", sites.size >= 2)
            sites.forEach { (method, index) ->
                assertFalse("$version: ${method.name} branches to $index + 1", method.isBranchTarget(index + 1))
            }

            val before = sites.associate { (method, _) -> method to method.implementation!!.instructions.toList() }
            ProfileTabHooks(MutableMethod(names.single()), MutableMethod(stub()), sites).also { hooks ->
                hooks.install()
                assertTrue("$version: the name map is opened", AccessFlags.PUBLIC.isSet(hooks.names.accessFlags))
                val bridge = hooks.stub.implementation!!.instructions.toList()
                assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                    bridge.take(3).map { it.opcode })
                val asked = bridge[0].getReference<MethodReference>()!!
                assertEquals(owner.type, asked.definingClass)
                assertEquals(names.single().name, asked.name)
            }
            sites.groupBy({ it.first }, { it.second }).forEach { (method, indices) ->
                val after = method.implementation!!.instructions.toList()
                assertEquals("$version: ${method.name} grew by the filter at each read",
                    before.getValue(method).size + 2 * indices.size, after.size)
                // Each earlier site sits where it was found, a later one two further per filter before it.
                indices.sorted().forEachIndexed { done, index ->
                    val result = index + 2 * done
                    val register = (after[result] as OneRegisterInstruction).registerA
                    val call = after[result + 1]
                    assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    val filter = call.getReference<MethodReference>()!!
                    assertEquals(PROFILE_TABS, filter.definingClass)
                    assertEquals("filter", filter.name)
                    assertEquals("$version: ${method.name} hands the list it read", register, (call as RegisterRangeInstruction).startRegister)
                    assertEquals(1, call.registerCount)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, after[result + 2].opcode)
                    assertEquals("$version: and keeps what comes back", register, (after[result + 2] as OneRegisterInstruction).registerA)
                }
            }
        }
    }

    /** The extension's bridge as javac leaves it: return null. */
    private fun stub(): Method = ImmutableMethod(
        PROFILE_TABS, "tabNamesNative", emptyList(), "Ljava/util/Map;",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(
            1,
            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            null,
            null,
        ),
    )

    private companion object {
        const val USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
    }
}
