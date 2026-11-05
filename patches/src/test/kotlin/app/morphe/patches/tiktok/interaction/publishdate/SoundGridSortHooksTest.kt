/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.InputStream
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sound page sort's hooks (#180) on each declared build: the detail page grid adapter hands its
 * label over as it's built and both its lists through the extension, the sound page's cell click
 * hands the extension its fragment, and the extension's `detailModel` is filled with the detail
 * fragment's getter for the list the video page plays. The probe merges the built extension, since
 * that last hook rewrites one of its methods, so this runs in the partition that reads the bundle.
 */
class SoundGridSortHooksTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `the sound page hooks assemble on each declared build`() {
        assertTrue("no built extension on the test classpath", OWNER.getResource(EXTENSION) != null)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = bytecodePatch(name = "sound grid sort probe") {
                extendWith(Supplier<InputStream> { OWNER.getResourceAsStream(EXTENSION)!! })
                execute {
                    fun ops(m: Method) = m.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.toList()
                    fun smali(ref: MethodReference) =
                        "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                    val setBefore = ops(DetailGridSetDataFingerprint.method).map { it.opcode }
                    val pageBefore = ops(DetailGridPageFingerprint.method).map { it.opcode }
                    val initBefore = ops(DetailGridInitFingerprint.method)
                    val openBefore = ops(SoundCellOpenFingerprint.method).map { it.opcode }
                    clearSoundMatches()
                    assertTrue("$version: the hooks went in", hookSoundGridSort(version))
                    clearSoundMatches()

                    val setAfter = ops(DetailGridSetDataFingerprint.method)
                    assertEquals(
                        "$GRID_SORT_TYPE->soundData(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;",
                        smali(setAfter.first().getReference<MethodReference>()!!),
                    )
                    assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT) + setBefore, setAfter.map { it.opcode })
                    val pageAfter = ops(DetailGridPageFingerprint.method)
                    assertEquals(
                        "$GRID_SORT_TYPE->soundPage(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;",
                        smali(pageAfter.first().getReference<MethodReference>()!!),
                    )
                    assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT) + pageBefore, pageAfter.map { it.opcode })

                    val initAfter = ops(DetailGridInitFingerprint.method)
                    val returns = initBefore.count { it.opcode == Opcode.RETURN_VOID }
                    assertTrue("$version: the constructor returns", returns > 0)
                    assertEquals("$version: one more per return", initBefore.size + returns, initAfter.size)
                    initAfter.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.forEach { (index, _) ->
                        assertEquals(Opcode.INVOKE_STATIC_RANGE, initAfter[index - 1].opcode)
                        assertEquals(
                            "$GRID_SORT_TYPE->soundGrid(Ljava/lang/Object;Ljava/lang/String;)V",
                            smali(initAfter[index - 1].getReference<MethodReference>()!!),
                        )
                    }

                    val openAfter = ops(SoundCellOpenFingerprint.method)
                    assertEquals(
                        "$GRID_SORT_TYPE->beforeOpen(Ljava/lang/Object;)V",
                        smali(openAfter.first().getReference<MethodReference>()!!),
                    )
                    assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE) + openBefore, openAfter.map { it.opcode })

                    val stub = classDefBy(GRID_SORT_TYPE).methods.single { it.name == "detailModel" }
                    val filled = ops(stub)
                    assertEquals(
                        listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                        filled.take(4).map { it.opcode },
                    )
                    val getter = filled[1].getReference<MethodReference>()!!
                    assertEquals(
                        "$version: the detail fragment's getter",
                        "Lcom/ss/android/ugc/aweme/challenge/ui/DetailAwemeListFragment;",
                        getter.definingClass,
                    )
                    assertTrue("$version: a getter with no arguments", getter.parameterTypes.isEmpty())
                    checked = true
                }
            }
            clearSoundMatches()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            clearSoundMatches()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    private fun clearSoundMatches() {
        DetailGridTagFingerprint.clearMatch()
        DetailGridSetDataFingerprint.clearMatch()
        DetailGridPageFingerprint.clearMatch()
        DetailGridInitFingerprint.clearMatch()
        DetailCellOpenFingerprint.clearMatch()
        SoundCellOpenFingerprint.clearMatch()
    }

    private companion object {
        const val EXTENSION = "/extensions/tiktok.mpe"
        const val GRID_SORT_TYPE = "Lapp/morphe/extension/tiktok/feed/GridSort;"
        val OWNER = SoundGridSortHooksTest::class.java
    }
}
