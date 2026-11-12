package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player layer hooks, held to each TikTok build the bundle declares: one static helper each,
 * the reparents taking the TTPlayerSurface, the parent layer and the buffer size (and the first
 * whether it shows), the recycle taking the player's id and then the TTPlayerSurface, which is the
 * order the extension reads its registers in.
 */
class PlayerLayerAnchorsTest {
    @Test
    fun `each player layer hook takes one static helper`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val seen = HashSet<String>()
            val reparent = mutableListOf<String>()
            val shown = mutableListOf<String>()
            val detach = mutableListOf<String>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (!seen.add(classDef.type)) continue
                    for (method in classDef.methods) {
                        val name = "${classDef.type}->${method.name}${method.parameterTypes}"
                        if (PlayerLayerReparentFingerprint.takes(method, classDef)) reparent += name
                        if (PlayerLayerShownFingerprint.takes(method, classDef)) shown += name
                        if (PlayerLayerDetachFingerprint.takes(method, classDef)) detach += name
                    }
                }
            }
            assertEquals("reparent helpers taken: $reparent", 1, reparent.size)
            assertTrue(reparent[0], reparent[0].endsWith("[$PLAYER_SURFACE, Landroid/view/SurfaceControl;, I, I, Z, J]"))
            assertEquals("Android 13 reparent helpers taken: $shown", 1, shown.size)
            assertTrue(shown[0], shown[0].contains("[$PLAYER_SURFACE, Landroid/view/SurfaceControl;, I, I, J, "))
            assertEquals("recycle helpers taken: $detach", 1, detach.size)
            assertTrue(detach[0], detach[0].endsWith("[J, $PLAYER_SURFACE]"))
            assertTrue(
                "the three are one helper class's",
                setOf(reparent[0], shown[0], detach[0]).map { it.substringBefore("->") }.toSet().size == 1,
            )
        }
    }

    /**
     * #259: the LIVE player's setSurfaceControl, and in it the two calls (SurfaceManager and its
     * own) that hand the stream its layer's Surface, each with the parent and the size still in
     * the locals the method copied them to, low enough for a plain invoke.
     */
    @Test
    fun `the LIVE hook goes before both calls that hand the stream its Surface`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Method>()
            for (entry in container.dexEntryNames) {
                val classDef = container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == LIVE_MANAGER } ?: continue
                classDef.methods.filterTo(taken) { LiveLayerFingerprint.takes(it, classDef) }
                break
            }
            assertEquals("setSurfaceControl methods taken: ${taken.map { it.name }}", 1, taken.size)

            val calls = liveLayerCalls(taken[0])
            assertEquals("calls handing the stream its Surface: $calls", 2, calls.size)
            val first = calls[0]
            assertTrue("$calls", calls.all { it.parent == first.parent && it.width == first.width && it.height == first.height })
            assertEquals("$first", 3, setOf(first.parent, first.width, first.height).size)
            assertTrue("$calls", calls.all { listOf(it.parent, it.surface, it.width, it.height).all { register -> register <= 15 } })
        }
    }
}
