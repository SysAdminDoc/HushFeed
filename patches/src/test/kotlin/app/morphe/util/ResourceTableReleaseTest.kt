/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import java.lang.ref.WeakReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STYLE_ITEM = "com.reandroid.arsc.item.StyleItem"
private const val TABLE_BLOCK = "com.reandroid.arsc.chunk.TableBlock"
private const val BLOCK = "com.reandroid.arsc.base.Block"

/**
 * Whether ARSCLib's shared style footer (StyleItem's static END_BLOCK) reaches a resource table
 * through its parents, which keeps that table and everything it holds in memory (#48).
 */
internal fun styleFooterReachesATable(): Boolean {
    val footer = Class.forName(STYLE_ITEM).getDeclaredField("END_BLOCK").apply { isAccessible = true }.get(null)
    val parent = Class.forName(BLOCK).getMethod("getParent")
    var block: Any? = footer
    while (block != null) {
        if (block.javaClass.name == TABLE_BLOCK) return true
        block = parent.invoke(block)
    }
    return false
}

/**
 * The patcher drops its parsed resource table on a small heap, and ARSCLib's static style footer
 * kept it alive anyway: 139 MB for TikTok 47.9.3's universal APK, held to the end of the run
 * (#48). ARSCLib is the patcher's runtime dependency, so it's reached by name here too.
 */
class ResourceTableReleaseTest {
    /**
     * A table with one style in its string pool, the way a parsed table holds its styles, that
     * nothing but the footer refers to once this returns.
     */
    private fun droppedTable(): WeakReference<Any> {
        val table = Class.forName(TABLE_BLOCK).getConstructor().newInstance()
        val pool = table.javaClass.getMethod("getStringPool").invoke(table)
        // Building the style is what makes it the footer's parent, as when a table is parsed.
        val style = Class.forName(STYLE_ITEM).getConstructor().newInstance()
        Class.forName(BLOCK).getMethod("setParent", Class.forName(BLOCK)).invoke(style, pool)
        return WeakReference(table)
    }

    /** Whether [reference] is cleared after a few collections. */
    private fun collected(reference: WeakReference<Any>): Boolean {
        repeat(10) {
            if (reference.get() == null) return true
            System.gc()
            Thread.sleep(50)
        }
        return reference.get() == null
    }

    @Test
    fun `a dropped table stays reachable through the style footer until it is released`() {
        val table = droppedTable()
        assertTrue("the footer's parents reach the table", styleFooterReachesATable())
        assertFalse("ARSCLib's footer no longer keeps a dropped table, so this release has nothing to do",
            collected(table))

        releaseResourceTable()

        assertFalse("the footer still reaches a table", styleFooterReachesATable())
        assertTrue("the dropped table was not collected", collected(table))
    }
}
