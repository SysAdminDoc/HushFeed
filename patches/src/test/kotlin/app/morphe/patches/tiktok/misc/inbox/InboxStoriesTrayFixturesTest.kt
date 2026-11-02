/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Test

private const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"

/**
 * InboxFilter knows the Inbox's stories tray by its class before its first cell has bound (#315),
 * since the row has no id then and no titles. That only holds while one list on the declared build
 * declares the getter it looks for.
 */
class InboxStoriesTrayFixturesTest {
    @Test
    fun `one list on each declared build declares a story highlight count`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes[classDef.type] = classDef
            }
            val trays = classes.values.filter { classDef ->
                descendsFromRecyclerView(classDef, classes) && classDef.methods.any { method ->
                    method.name == "getStoryHighlightCount" &&
                        method.parameterTypes.isEmpty() &&
                        method.returnType == "I"
                }
            }
            assertEquals("${Fixtures.versionOf(apk)}: ${trays.map { it.type }}", 1, trays.size)
        }
    }

    private fun descendsFromRecyclerView(classDef: ClassDef, classes: Map<String, ClassDef>): Boolean {
        var parent = classDef.superclass
        while (parent != null) {
            if (parent == RECYCLER_VIEW) return true
            parent = classes[parent]?.superclass
        }
        return false
    }
}
