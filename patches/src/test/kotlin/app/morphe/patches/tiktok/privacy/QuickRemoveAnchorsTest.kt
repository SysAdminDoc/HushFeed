/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.patches.tiktok.interaction.publishdate.GridCellOpenFingerprint
import app.morphe.patches.tiktok.interaction.publishdate.ProfileTabCellOpenFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Tap a video to remove it (#401) hooks, held to each declared build: one cell tap on each
 * profile grid fragment, one Reposts cell tap whose cell, row and video it can read, and the
 * Reposts removal it reads the tab owner's uid from.
 */
class QuickRemoveAnchorsTest {
    @Test
    fun `each build has both grid taps, one Reposts cell tap and the Reposts owner uid`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val grid = mutableListOf<Method>()
            val tab = mutableListOf<Method>()
            val repostTaps = mutableListOf<Method>()
            val removes = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (GridCellOpenFingerprint.takes(method, classDef)) grid += method
                        if (ProfileTabCellOpenFingerprint.takes(method, classDef)) tab += method
                        if (RepostCellOpenFingerprint.takes(method, classDef)) repostTaps += method
                        if (RepostRemoveFingerprint.takes(method, classDef)) removes += method
                    }
                }
            }
            assertEquals("$version: profile grid taps $grid", 1, grid.size)
            assertEquals("$version: Liked and private grid taps $tab", 1, tab.size)
            assertEquals("$version: Reposts cell taps ${repostTaps.map { "${it.definingClass}->${it.name}" }}",
                1, repostTaps.size)
            assertEquals("$version: Reposts removals $removes", 1, removes.size)

            val tap = repostTaps.single()
            val parts = repostTapParts(tap)
            assertNotNull("$version: the Reposts cell tap's parts", parts)
            parts!!
            assertEquals("$version: the tap's cell and row are fields of the lambda", tap.definingClass,
                parts.cell.definingClass)
            assertEquals(tap.definingClass, parts.row.definingClass)
            assertEquals(REPOST_LIST_VIEW_MODEL, parts.viewModel.returnType)
            assertEquals("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", parts.video.type)
            assertEquals(parts.rowType, parts.video.definingClass)
            // The injection's four locals and the view all fit a five-register invoke.
            val registers = tap.implementation!!.registerCount
            val parameters = tap.parameterTypes.size
            assertTrue("$version: locals ${registers - parameters}", registers - parameters >= 4)
            assertTrue("$version: registers $registers", registers <= 16)

            val owner = repostOwnerField(removes.single())
            assertNotNull("$version: owner uid field", owner)
            assertEquals("Ljava/lang/String;", owner!!.type)
        }
    }
}
