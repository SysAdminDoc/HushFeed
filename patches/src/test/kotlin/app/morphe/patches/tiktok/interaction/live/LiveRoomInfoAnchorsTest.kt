/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The room ID and connection line (#487) reaches the current LIVE player's stats by the route
 * TikTok's own LIVE debug panel takes. Held to each declared build: the panel's text method is
 * found once, its route reads in order, every class, method and field on it exists with the call
 * kind the bridge uses, and the stats it ends on are the JSON the panel reads rtt:, delay: and
 * download_Speed: from.
 */
class LiveRoomInfoAnchorsTest {
    @Test
    fun `the debug panel's route to the player's stats resolves on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .associateBy { it.type }
            val panel = classes[LIVE_DEBUG_INFO_WIDGET]
            assertNotNull("$version: no LIVE debug panel", panel)
            val texts = panel!!.methods.filter { LiveDebugInfoTextFingerprint.takes(it, panel) }
            assertEquals("$version: debug panel text methods", 1, texts.size)
            val reads = livePlayerStatsReads(texts.single())

            val service = classes.getValue(reads.service)
            assertTrue("$version: ${reads.service} isn't an interface", AccessFlags.INTERFACE.isSet(service.accessFlags))
            val poolName = reads.poolGetter.substringAfter("->").substringBefore("(")
            val poolType = reads.poolGetter.substringAfter(")")
            assertTrue("$version: ${reads.poolGetter}", service.methods.any {
                it.name == poolName && it.parameterTypes.isEmpty() && it.returnType == poolType
            })
            assertEquals("$version: the pool getter's call kind", "invoke-interface", reads.poolInvoke)

            val playerName = reads.player.substringAfter("->").substringBefore(":")
            val playerType = reads.player.substringAfter(":")
            assertTrue("$version: ${reads.player}", classes.getValue(poolType).fields.any {
                it.name == playerName && it.type == playerType && !AccessFlags.STATIC.isSet(it.accessFlags)
            })
            val player = classes.getValue(playerType)
            val statsName = reads.stats.substringAfter("->").substringBefore("(")
            assertTrue("$version: ${reads.stats}", player.methods.any {
                it.name == statsName && it.parameterTypes.isEmpty() && it.returnType == "Lorg/json/JSONObject;"
            })
            assertEquals("$version: the stats call's kind",
                if (AccessFlags.INTERFACE.isSet(player.accessFlags)) "invoke-interface" else "invoke-virtual", reads.statsInvoke)
            assertTrue("$version: ${reads.lookup} takes the service class",
                reads.lookup.contains("(Ljava/lang/Class;)L"))
        }
    }

    @Test
    fun `a method without the route says which step is missing`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val panel = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .single { it.type == LIVE_DEBUG_INFO_WIDGET }
            val other = panel.methods.first { it.name == "getLayoutId" }
            try {
                livePlayerStatsReads(other)
                fail("getLayoutId has no route to the player's stats")
            } catch (expected: PatchException) {
                assertTrue(expected.message!!, expected.message!!.startsWith("LIVE controls: the debug panel's"))
            }
        }
    }

    @Test
    fun `LIVE controls notes each room page and installs the stats bridge`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/live/LiveControlsPatch.kt").readText()
        assertTrue("the room pages aren't handed to the line",
            source.contains("\$LIVE_ROOM_INFO_EXTENSION->noteRoomFragment(Ljava/lang/Object;)V"))
        assertTrue("the stats bridge isn't installed",
            source.contains("installLiveRoomInfoBridge(livePlayerStatsReads(LiveDebugInfoTextFingerprint.originalMethod))"))
    }
}
