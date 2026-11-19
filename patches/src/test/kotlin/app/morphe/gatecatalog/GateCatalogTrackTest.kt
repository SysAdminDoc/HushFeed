/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.gatecatalog

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the catalog generator's register walk credits a getter call with, where code branches. */
class GateCatalogTrackTest {
    private fun method(registers: Int, smali: String): MutableMethod = MutableMethod(ImmutableMethod("Lapp/morphe/Example;", "example",
        emptyList(), "V", AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, smali)
    }

    /** Puts instructions [from] until [to] (by index) in a catch-all try block handled at [handler]. */
    private fun MutableMethod.tryBlock(from: Int, to: Int, handler: Int) = apply {
        val code = implementation!!
        code.addCatch(code.newLabelForIndex(from), code.newLabelForIndex(to), code.newLabelForIndex(handler))
    }

    /** The one string argument of every static `get(String)`, in code order; null where the walk can't tell. */
    private fun keys(method: Method): List<String?> {
        val keys = ArrayList<String?>()
        GateCatalogGenerator.track(method) { call ->
            if (call.method.name == "get") keys += (call.args.firstOrNull() as? GateCatalogGenerator.Str)?.text
        }
        return keys
    }

    @Test fun `a read at a jump target takes the key the jump carried`() {
        // 47.8.3 FullFeedFragmentPanel.F: share_guide is read at a jump target, and the walk
        // credited it with the string the fallthrough path had loaded.
        val read = method(3, """
            const-string v0, "share_guide"
            if-eqz v2, :read
            const-string v0, "awe_share_guide_type_log"
            invoke-static { v0 }, Lapp/morphe/Example;->log(Ljava/lang/String;)V
            goto :end
            :read
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            :end
            return-void
        """)
        assertEquals(listOf("share_guide"), keys(read))
    }

    @Test fun `paths that load different keys leave the register unknown where they meet`() {
        val join = method(3, """
            const-string v0, "a"
            if-eqz v2, :join
            const-string v0, "b"
            :join
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            return-void
        """)
        assertEquals(listOf<String?>(null), keys(join))
    }

    @Test fun `a key both paths agree on survives the join`() {
        val join = method(3, """
            const-string v0, "kept"
            if-eqz v2, :join
            const/4 v1, 0x1
            :join
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            return-void
        """)
        assertEquals(listOf("kept"), keys(join))
    }

    @Test fun `code after a goto starts from nothing it didn't load`() {
        val after = method(3, """
            const-string v0, "stale"
            goto :end
            :unreached
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            :end
            return-void
        """)
        assertEquals(listOf<String?>(null), keys(after))
    }

    @Test fun `a key loaded above code a backward goto shares survives it`() {
        // 47.9.1 LX/1CUF;->LLJJJJ loads ttec_anchor_live_start_api_request_config first, and D8
        // put a block both branches share above the branch that jumps back to it.
        val shared = method(3, """
            const-string v0, "kept"
            if-eqz v2, :other
            const/4 v1, 0x1
            :shared
            const/4 v2, 0x0
            goto :read
            :other
            const/4 v1, 0x0
            goto :shared
            :read
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            return-void
        """)
        assertEquals(listOf("kept"), keys(shared))
    }

    @Test fun `a key a loop changes is unknown at the loop's head`() {
        val loop = method(3, """
            const-string v0, "first"
            :head
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            const-string v0, "second"
            if-eqz v2, :head
            return-void
        """)
        assertEquals(listOf<String?>(null), keys(loop))
    }

    @Test fun `a key loaded above a try block survives where the catch path rejoins`() {
        val caught = method(3, """
            const-string v0, "kept"
            invoke-static { }, Lapp/morphe/Example;->risky()V
            goto :after
            :handler
            move-exception v1
            :after
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            return-void
        """).tryBlock(from = 1, to = 2, handler = 3)
        assertEquals(listOf("kept"), keys(caught))
    }

    @Test fun `a catch handler agrees only with what its whole try block held`() {
        val caught = method(3, """
            const-string v0, "a"
            invoke-static { }, Lapp/morphe/Example;->risky()V
            const-string v0, "b"
            invoke-static { }, Lapp/morphe/Example;->risky()V
            return-void
            :handler
            move-exception v1
            invoke-static { v0 }, Lapp/morphe/Example;->get(Ljava/lang/String;)V
            return-void
        """).tryBlock(from = 1, to = 4, handler = 5)
        assertEquals(listOf<String?>(null), keys(caught))
    }

    @Test fun `a static call's arguments line up with its parameters past a wide one`() {
        val wide = method(4, """
            const-wide v2, 0x7L
            const-string v0, "wide_key"
            invoke-static { v2, v3, v0 }, Lapp/morphe/Example;->get(JLjava/lang/String;)V
            return-void
        """)
        val calls = ArrayList<GateCatalogGenerator.Call>()
        GateCatalogGenerator.track(wide) { call -> calls += call }
        val args = calls.single().args
        assertEquals(2, args.size)
        assertEquals(7L, (args[0] as GateCatalogGenerator.Num).bits)
        assertEquals("wide_key", (args[1] as GateCatalogGenerator.Str).text)
    }
}
