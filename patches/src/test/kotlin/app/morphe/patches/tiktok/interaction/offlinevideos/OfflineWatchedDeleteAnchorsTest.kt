/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Delete watched offline videos calls, held to each declared build: one clean-up and one
 * unwatched count by their fingerprints, the clean-up's question, delete and played-offline flag
 * read off it, the count's question found beside the watched one, and every one of them public
 * and static, since the extension calls them from its own package.
 */
class OfflineWatchedDeleteAnchorsTest {
    @Test
    fun `each declared build has the clean-up and the count, and every call the extension makes resolves`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val cleanups = mutableListOf<Method>()
            val queries = mutableListOf<Method>()
            walk(apk) { classDef, method ->
                if (OfflineWatchedDeleteFingerprint.takes(method, classDef)) cleanups += method
                if (UnwatchedOfflineCountQueryFingerprint.takes(method, classDef)) queries += method
            }
            assertEquals("$version: clean-ups ${cleanups.map { it.definingClass + "->" + it.name }}", 1, cleanups.size)
            assertEquals("$version: counts ${queries.map { it.definingClass + "->" + it.name }}", 1, queries.size)
            val cleanup = cleanups.single()
            val query = queries.single()

            val calls = watchedDeleteCalls(cleanup, "Custom offline videos limit")
            assertEquals("$version: the delete", cleanup.definingClass, calls.delete.definingClass)

            val wanted = setOf(cleanup.definingClass, calls.watched.definingClass, query.definingClass)
            val classes = mutableMapOf<String, ClassDef>()
            walk(apk) { classDef, _ -> if (classDef.type in wanted) classes[classDef.type] = classDef }
            val owner = classes.getValue(cleanup.definingClass)
            val holder = classes.getValue(calls.watched.definingClass)
            val queryTypes = setOf(query.definingClass) + classes.getValue(query.definingClass).interfaces
            val count = unwatchedCountGetter(holder, query, queryTypes, "Custom offline videos limit")
            assertNotEquals("$version: the count is the watched question", calls.watched.name, count.name)

            for (classDef in listOf(owner, holder)) {
                assertTrue("$version: ${classDef.type} is not public", AccessFlags.PUBLIC.isSet(classDef.accessFlags))
            }
            for ((label, reference) in listOf("watched" to calls.watched, "delete" to calls.delete, "count" to count)) {
                val declared = declaredIn(classes.getValue(reference.definingClass), reference)
                assertNotNull("$version: the $label call is not declared on ${reference.definingClass}", declared)
                assertTrue("$version: the $label call is not public static", isPublicStatic(declared!!.accessFlags))
            }
            val flag = owner.fields.singleOrNull { it.name == calls.consumed.name && it.type == "Z" }
            assertNotNull("$version: the played-offline flag is not declared on ${owner.type}", flag)
            assertTrue("$version: the played-offline flag is not public static", isPublicStatic(flag!!.accessFlags))
        }
    }

    private fun isPublicStatic(flags: Int) = AccessFlags.PUBLIC.isSet(flags) && AccessFlags.STATIC.isSet(flags)

    private fun declaredIn(classDef: ClassDef, reference: MethodReference): Method? = classDef.methods.singleOrNull {
        it.name == reference.name && it.returnType == reference.returnType &&
            it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
    }

    /** Every method of one build, walked once and never held (the dex has millions). */
    private fun walk(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) visit(classDef, method)
            }
        }
    }
}
