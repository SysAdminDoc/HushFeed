/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RESULT_FRAGMENT = "Lcom/ss/android/ugc/aweme/notification/v2/NotificationResultFragment;"
private const val RESULT_DATA = "Lcom/ss/android/ugc/aweme/notification/v2/assem/ResultFragmentData;"
private const val GROUP_FILTER = "Lcom/ss/android/ugc/aweme/notice/repo/list/bean/GroupFilterStruct;"

/**
 * Split activity list: its hooks, the androidx fragment calls it resolves for its bridge, and the
 * constructors and fields ActivitySplit builds its two lists from, held to the declared build.
 */
class SplitActivityListAnchorsTest {
    @Test
    fun `the page view hook and the type pager's select are one method each`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val page = classes.getValue(NOTIFICATION_FRAGMENT_V2)
            assertEquals("${apk.name}: page view hooks", 1, page.methods.count { ActivityPageViewCreatedFingerprint.takes(it, page) })
            val pagers = classes.values.filter { it.isTypeListPager() }
            assertEquals("${apk.name}: type pagers", 1, pagers.size)
            val pager = pagers.single()
            assertEquals(1, pager.methods.count { TypeListPagerSelectFingerprint.takes(it, pager) })
            assertTrue(pager.methods.any { it.name == "getCurrentItem" && it.parameterTypes.isEmpty() && it.returnType == "I" })
        }
    }

    @Test
    fun `the fragment bridge resolves each androidx call to exactly one method`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val replace = classes.values.flatMap { c -> c.methods.filter { FragmentTransactionReplaceFingerprint.takes(it, c) } }
            val remove = classes.values.flatMap { c -> c.methods.filter { BackStackRecordRemoveFingerprint.takes(it, c) } }
            assertEquals("${apk.name}: replace anchors", 1, replace.size)
            assertEquals("${apk.name}: remove anchors", 1, remove.size)
            // resolve() checks every count itself and fails with the call that moved.
            val calls = FragmentBridge.resolve(replace.single(), remove.single()) { classes.getValue(it) }.calls()
            assertEquals(5, calls.toSet().size)
            val fragment = classes.getValue("Landroidx/fragment/app/Fragment;")
            listOf("getChildFragmentManager", "getArguments", "setArguments", "getView").forEach { name ->
                assertTrue("${apk.name}: Fragment.$name", fragment.methods.any { it.name == name })
            }
        }
    }

    @Test
    fun `a result list can be built the way ActivitySplit builds it`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val list = classes.getValue(RESULT_FRAGMENT)
            assertTrue(list.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags) })
            assertTrue(list.methods.any { it.name == "onPageSelected" && it.parameterTypes.map(CharSequence::toString) == listOf("I") && AccessFlags.PUBLIC.isSet(it.accessFlags) })
            val filter = classes.getValue(GROUP_FILTER)
            assertTrue(filter.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I", "I", "I", "I", "Ljava/lang/Integer;") })
            listOf("noticeGroup" to "I", "titleResId" to "I", "groupIconId" to "I", "titleId" to "I", "descId" to "I", "placeHolderResId" to "Ljava/lang/Integer;")
                .forEach { (name, type) -> assertTrue("${apk.name}: GroupFilterStruct.$name", filter.fields.any { it.name == name && it.type == type }) }
            val data = classes.getValue(RESULT_DATA)
            assertTrue(data.methods.any { it.name == "<init>" && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameterTypes.map(CharSequence::toString).let { p ->
                p.size == 5 && p[0] == "Z" && p[1] == "Z" && p[3] == GROUP_FILTER && p[4] == "Ljava/lang/Integer;" } })
            listOf("isCreator" to "Z", "isAllTab" to "Z", "groupFilter" to GROUP_FILTER, "collapsedListType" to "Ljava/lang/Integer;")
                .forEach { (name, type) -> assertTrue("${apk.name}: ResultFragmentData.$name", data.fields.any { it.name == name && it.type == type }) }
        }
    }

    @Test
    fun `the emoji catalog publish is one method`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val publish = classes.values.flatMap { c -> c.methods.filter { EmojiCatalogPublishFingerprint.takes(it, c) } }
            assertEquals("${apk.name}: emoji catalog publish", 1, publish.size)
        }
    }

    private fun classes(apk: java.io.File): Map<String, ClassDef> {
        val dex: MultiDexContainer<out DexFile> = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }
}
