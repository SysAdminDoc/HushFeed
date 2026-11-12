/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.InputStream
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Jump to a date on Liked grids (#350) on each declared build: the Liked list request and both
 * fragment refreshes are each found once, the request hands its ids and `max_cursor` to the
 * extension ahead of TikTok's code and takes the cursor back into the same registers, and the
 * extension's `refreshGrid` is filled with the profile tab's refresh (the one your own Liked tab
 * has) and then the older grid fragment's. The probe merges the built extension, since that last
 * hook rewrites one of its methods.
 */
class LikedJumpHooksTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `the Liked jump hooks assemble on each declared build`() {
        assertTrue("no built extension on the test classpath", OWNER.getResource(EXTENSION) != null)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val requests = mutableListOf<Method>()
            val tabs = mutableListOf<Method>()
            val refreshes = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (LikedListRequestFingerprint.takes(method, classDef)) requests += method
                        if (ProfileTabRefreshFingerprint.takes(method, classDef)) tabs += method
                        if (GridRefreshFingerprint.takes(method, classDef)) refreshes += method
                    }
                }
            }
            assertEquals("$version: Liked list requests ${requests.map { it.definingClass }}", 1, requests.size)
            assertEquals("$version: tab refreshes ${tabs.map { it.name }}", 1, tabs.size)
            assertEquals("$version: grid refreshes ${refreshes.map { it.name }}", 1, refreshes.size)
            assertTrue(
                "$version: a try range covers the request's first instruction",
                requests.single().implementation!!.tryBlocks.none { it.startCodeAddress == 0 },
            )

            var checked = false
            val probe = patch(name = "liked jump probe") {
                extendWith(Supplier<InputStream> { OWNER.getResourceAsStream(EXTENSION)!! })
                execute {
                    fun ops(m: Method) = m.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.toList()
                    fun smali(ref: MethodReference) =
                        "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                    val request = LikedListRequestFingerprint.method
                    val before = ops(request).map { it.opcode }
                    val registers = request.implementation!!.registerCount
                    val ins = 1 + 1 + 1 + 1 + 2 + 1 + 1 + 1 + 1 + 1
                    clearMatches()
                    assertTrue("$version: the hooks went in", hookLikedJump(version))
                    clearMatches()

                    val after = ops(LikedListRequestFingerprint.method)
                    assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_WIDE) + before, after.map { it.opcode })
                    assertEquals(
                        "Lapp/morphe/extension/tiktok/feed/LikedJump;->cursor(Ljava/lang/String;Ljava/lang/String;IJ)J",
                        smali(after.first().getReference<MethodReference>()!!),
                    )
                    val call = after.first() as RegisterRangeInstruction
                    val p1 = registers - ins + 1
                    assertEquals("$version: the ids and the cursor, p1 to p5", p1, call.startRegister)
                    assertEquals(5, call.registerCount)
                    assertEquals("$version: the cursor comes back into p4", p1 + 3, (after[1] as OneRegisterInstruction).registerA)

                    val stub = classDefBy(LIKED_JUMP_TYPE).methods.single { it.name == "refreshGrid" }
                    assertEquals(listOf("Ljava/lang/Object;", "I"), stub.parameterTypes.map { it.toString() })
                    val filled = ops(stub)
                    assertEquals(
                        listOf(
                            Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.CONST_STRING, Opcode.INVOKE_VIRTUAL,
                            Opcode.CONST_4, Opcode.RETURN,
                            Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT,
                            Opcode.RETURN,
                        ),
                        filled.take(13).map { it.opcode },
                    )
                    assertEquals(
                        "fresh_type_has_filter_video",
                        filled[3].getReference<StringReference>()!!.string,
                    )
                    assertEquals(
                        "$PROFILE_TAB->${tabs.single().name}(Ljava/lang/String;)Z",
                        smali(filled[4].getReference<MethodReference>()!!),
                    )
                    assertEquals(
                        "Lcom/ss/android/ugc/profile/business/profile/tab/AwemeListFragmentImpl;->${refreshes.single().name}()Z",
                        smali(filled[10].getReference<MethodReference>()!!),
                    )
                    checked = true
                }
            }
            clearMatches()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            clearMatches()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    private fun clearMatches() {
        LikedListRequestFingerprint.clearMatch()
        ProfileTabRefreshFingerprint.clearMatch()
        GridRefreshFingerprint.clearMatch()
    }

    private companion object {
        const val EXTENSION = "/extensions/tiktok.mpe"
        const val LIKED_JUMP_TYPE = "Lapp/morphe/extension/tiktok/feed/LikedJump;"
        const val PROFILE_TAB =
            "Lcom/ss/android/ugc/profile/platform/framework/aweme/profile/tab/profiletab/ProfileBaseAwemeListFragment;"
        val OWNER = LikedJumpHooksTest::class.java
    }
}
