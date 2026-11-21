/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #482 on each declared build: the offline update task's size-only asks for the unwatched videos,
 * the loader they reach, its query and the two counts that answer it, and the loader as the
 * patch leaves it, which the verifier's typing has to accept.
 */
class OfflineUnwatchedCountAnchorsTest {
    @Test
    fun `each declared build counts the update task's unwatched videos without loading them`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = HashMap<String, ClassDef>()
            val tasks = mutableListOf<Method>()
            val saved = mutableListOf<Method>()
            val watched = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    classes[classDef.type] = classDef
                    for (method in classDef.methods) {
                        if (OfflineUpdateTaskFingerprint.takes(method, classDef)) tasks += method
                        if (SavedOfflineCountQueryFingerprint.takes(method, classDef)) saved += method
                        if (WatchedOfflineCountQueryFingerprint.takes(method, classDef)) watched += method
                    }
                }
            }
            for ((label, found) in listOf("update tasks" to tasks, "saved counts" to saved, "watched counts" to watched)) {
                assertEquals("$version: $label ${found.map { it.definingClass + "->" + it.name }}", 1, found.size)
            }
            val task = tasks.single()

            val found = unwatchedCountSites(task, saved.single(), watched.single(), classes::get, "Custom offline videos limit")
            assertTrue("$version: no size-only ask in the update task", found.sites.isNotEmpty())
            assertNotEquals("$version: the two counts are one query", found.saved.name, found.watched.name)
            val code = task.implementation!!.instructions.toList()
            for (site in found.sites) {
                assertEquals("$version: ask $site", signatureOf(found.loader), signatureOf(code[site].getReference<MethodReference>()!!))
            }

            // The extension calls the loader from its own package.
            val owner = classes.getValue(found.loader.definingClass)
            val loader = owner.methods.single { it.name == found.loader.name && it.parameterTypes.isEmpty() }
            assertTrue("$version: ${owner.type} is not public", AccessFlags.PUBLIC.isSet(owner.accessFlags))
            assertTrue(
                "$version: the loader is not public static",
                AccessFlags.PUBLIC.isSet(loader.accessFlags) && AccessFlags.STATIC.isSet(loader.accessFlags),
            )

            // The loader as the patch leaves it: the counts in front, TikTok's own code after.
            val before = loader.implementation!!.instructions.toList()
            assertEquals("$version: the stock loader", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(loader))
            val patched = MutableMethod(loader).apply { countInFrontOfQuery(found) }
            val after = patched.implementation!!.instructions.toList()
            assertEquals(
                "$version: ${BridgeTypeFlow.check(patched)}",
                emptyList<BridgeTypeFlow.Problem>(),
                BridgeTypeFlow.check(patched),
            )
            assertEquals(before.size + 10, after.size)
            assertEquals(before.map { it.opcode }, (after.take(found.query) + after.drop(found.query + 10)).map { it.opcode })
            val added = after.subList(found.query, found.query + 10)
            assertEquals("counting", added[0].getReference<MethodReference>()!!.name)
            assertEquals(Opcode.IF_EQZ, added[2].opcode)
            assertEquals(signatureOf(found.saved), signatureOf(added[3].getReference<MethodReference>()!!))
            assertEquals(signatureOf(found.watched), signatureOf(added[5].getReference<MethodReference>()!!))
            assertEquals("unwatched", added[7].getReference<MethodReference>()!!.name)
            assertEquals(Opcode.RETURN_OBJECT, added[9].opcode)
            // The if-eqz goes on to TikTok's query, which is still followed by its own result.
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[found.query + 11].opcode)
            assertEquals(
                (before[found.query + 1] as OneRegisterInstruction).registerA,
                (added[1] as OneRegisterInstruction).registerA,
            )

            val retargeted = MutableMethod(task).apply { countAtSizeOnlyLoads(found) }.implementation!!.instructions.toList()
            for (site in found.sites) {
                val call = retargeted[site].getReference<MethodReference>()!!
                assertEquals(UNWATCHED_COUNT_EXTENSION, call.definingClass)
                assertEquals("countedUnwatched", call.name)
            }
            assertEquals(code.size, retargeted.size)
        }
    }

    @Test
    fun `an ask whose list is read again, or only checked, is left to load the videos`() {
        val task = method(
            4,
            "Ljava/lang/Object;",
            """
                invoke-static {}, $LOADER
                move-result-object v0
                invoke-interface {v0}, Ljava/util/List;->size()I
                move-result v1
                invoke-interface {v0}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;
                invoke-static {}, $LOADER
                move-result-object v0
                invoke-interface {v0}, Ljava/util/List;->size()I
                move-result v1
                check-cast v0, Ljava/util/ArrayList;
                invoke-static {}, $LOADER
                move-result-object v0
                invoke-interface {v0}, Ljava/util/List;->size()I
                move-result v1
                const/4 v0, 0x0
                invoke-static {}, $LOADER
                move-result-object v2
                invoke-interface {v2}, Ljava/util/List;->size()I
                move-result v2
                return-object v0
            """,
        )
        assertEquals(listOf(10, 15), sizeOnlyLoads(task).map { it.first })
    }

    @Test
    fun `a loader whose query is branched onto, covered or answered into a read register is refused`() {
        val clean = """
            invoke-static {}, $ACCESS_GETTER
            move-result-object v1
            const-wide/16 v2, 0x0
            const-wide/16 v4, 0x0
            const-string v6, "uid"
            invoke-interface/range {v1 .. v6}, $LIST_QUERY
            move-result-object v0
            return-object v0
        """
        assertEquals(5, unwatchedQuery(method(7, "Ljava/util/List;", clean)))

        val branched = clean.replace("const-string v6, \"uid\"", "const-string v6, \"uid\"\n if-eqz v6, :ask\n :ask")
        assertNull("a branch lands on the query", unwatchedQuery(method(7, "Ljava/util/List;", branched)))

        val intoItsReceiver = clean.replace("move-result-object v0\n            return-object v0", "move-result-object v1\n            return-object v1")
        assertNull("the answer goes into the receiver", unwatchedQuery(method(7, "Ljava/util/List;", intoItsReceiver)))

        val wideFrame = clean.replace("{v1 .. v6}", "{v16 .. v21}").replace("v1\n", "v16\n")
            .replace("v2, 0x0", "v17, 0x0").replace("v4, 0x0", "v19, 0x0").replace("v6, \"uid\"", "v21, \"uid\"")
        assertNull("the receiver can't take a plain call's count", unwatchedQuery(method(22, "Ljava/util/List;", wideFrame)))
    }

    @Test
    fun `the counts' arguments are matched to the list's by where each is bound`() {
        fun query(swapped: Boolean) = method(
            10,
            "I",
            """
                const/4 v1, 0x3
                const/4 v0, 0x1
                invoke-virtual {v3, v0, v9}, $BIND_STRING
                const/4 v0, 0x2
                invoke-virtual {v3, v0, ${if (swapped) "v7, v8" else "v5, v6"}}, $BIND_LONG
                invoke-virtual {v3, v1, ${if (swapped) "v5, v6" else "v7, v8"}}, $BIND_LONG
                const/4 v0, 0x0
                return v0
            """,
            listOf("J", "J", "Ljava/lang/String;"),
            static = false,
        )
        assertEquals(mapOf(1 to 2, 2 to 0, 3 to 1), boundParameters(query(swapped = false)))
        assertEquals(mapOf(1 to 2, 2 to 1, 3 to 0), boundParameters(query(swapped = true)))
    }

    private fun method(
        registers: Int,
        returnType: String,
        smali: String,
        parameters: List<String> = emptyList(),
        static: Boolean = true,
    ) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Offline;", "load", parameters.map {
                ImmutableMethodParameter(it, null, null)
            }, returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun signatureOf(reference: MethodReference) =
        "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

    private companion object {
        const val LOADER = "Lfixture/Offline;->unwatched()Ljava/util/List;"
        const val ACCESS_GETTER = "Lfixture/Offline;->access()Lfixture/Access;"
        const val LIST_QUERY = "Lfixture/Access;->list(JJLjava/lang/String;)Ljava/util/List;"
        const val BIND_STRING = "Lfixture/Statement;->bind(ILjava/lang/String;)V"
        const val BIND_LONG = "Lfixture/Statement;->bind(IJ)V"
    }
}
