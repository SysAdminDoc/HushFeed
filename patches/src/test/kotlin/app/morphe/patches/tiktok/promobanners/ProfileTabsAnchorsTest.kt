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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Hide profile tabs hooks, held to each declared build: the pager's number for each tab name,
 * read from the method that turns a profile's components into tab types, and the pager's two tab
 * lists, each read by the pager at a move-result the filter can follow without landing inside a
 * branch.
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

            val tabTypes = profileTabTypes(types.single().first)
            assertEquals("$version: Videos is the pager's 0 in $tabTypes", "profile_tab_public_post", tabTypes[0])
            for (name in CHECKLIST_NAMES) {
                assertTrue("$version: $name has a pager number in $tabTypes", name in tabTypes.values)
            }
            EXPECTED_TYPES[version]?.let { assertEquals("$version: the pager's numbers", it, tabTypes) }

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
            ProfileTabHooks(tabTypes, MutableMethod(stub()), sites).also { hooks ->
                hooks.install()
                val bridge = hooks.stub.implementation!!.instructions.toList()
                assertEquals(listOf(Opcode.CONST_STRING, Opcode.RETURN_OBJECT), bridge.take(2).map { it.opcode })
                val written = bridge[0].getReference<StringReference>()!!.string
                assertEquals("$version: the bridge hands every pair",
                    tabTypes.entries.joinToString(",") { "${it.key}=${it.value}" }, written)
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

    @Test
    fun `a number loaded on the way to a name's own is not taken for it`() {
        // Music's branch loads 14 into another register for a service lookup before its own 3,
        // and the showcase adds its 12 on the spot with no jump after it.
        val reader = ImmutableMethod(
            "LX/0OHg;", "LIZIZ", emptyList(), "Ljava/util/List;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(
                3,
                listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("profile_tab_public_post")),
                    equals(),
                    ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                    ImmutableInstruction10t(Opcode.GOTO, 2),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("profile_tab_music")),
                    equals(),
                    ImmutableInstruction21s(Opcode.CONST_16, 2, 14),
                    ImmutableInstruction11n(Opcode.CONST_4, 1, 3),
                    ImmutableInstruction10t(Opcode.GOTO, 2),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("profile_tab_ecommerce_showcase")),
                    equals(),
                    ImmutableInstruction21s(Opcode.CONST_16, 1, 12),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("profile_tab_used_elsewhere")),
                    ImmutableInstruction11n(Opcode.CONST_4, 1, 7),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ),
                null,
                null,
            ),
        )
        assertEquals(
            sortedMapOf(0 to "profile_tab_public_post", 3 to "profile_tab_music", 12 to "profile_tab_ecommerce_showcase"),
            profileTabTypes(reader),
        )
    }

    private fun equals() = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0,
        ImmutableMethodReference("Ljava/lang/String;", "equals", listOf("Ljava/lang/Object;"), "Z"),
    )

    /** The extension's bridge as javac leaves it: return null. */
    private fun stub(): Method = ImmutableMethod(
        PROFILE_TABS, "tabNamesNative", emptyList(), "Ljava/lang/String;",
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

        /** The names the checklist's rows hide (ProfileTabs.ROWS in the extension). */
        val CHECKLIST_NAMES = listOf(
            "profile_tab_private", "profile_tab_repost", "profile_tab_favorite", "profile_tab_like",
            "profile_tab_ecommerce_showcase", "profile_tab_ecommerce_store", "profile_tab_music",
            "profile_tab_effect", "profile_tab_short_drama", "profile_tab_drama_download",
            "profile_tab_pine_drama_watch_history", "profile_tab_sticker_store",
        )

        /**
         * 47.7.3's numbers, read off the tab type reader's disassembly (LX/0OHg;->LIZIZ) by hand:
         * the constant each name's branch loads before it joins the list. Your own profile's strip
         * logged 0, 16, 21, 10, 15, 6, 2 for Videos, Stickers, AI theater, Private, Reposts,
         * Favorites and Liked.
         */
        val EXPECTED_TYPES = mapOf(
            "47.7.3" to sortedMapOf(
                0 to "profile_tab_public_post", 2 to "profile_tab_like", 3 to "profile_tab_music",
                4 to "profile_tab_effect", 6 to "profile_tab_favorite", 10 to "profile_tab_private",
                12 to "profile_tab_ecommerce_showcase", 14 to "profile_tab_ecommerce_store",
                15 to "profile_tab_repost", 16 to "profile_tab_sticker_store", 17 to "profile_tab_business_page",
                18 to "profile_tab_short_drama", 19 to "profile_tab_live_game_org_account",
                20 to "profile_tab_pine_drama_watch_history", 21 to "profile_tab_cast",
                22 to "profile_tab_drama_download",
            ),
        )
    }
}
