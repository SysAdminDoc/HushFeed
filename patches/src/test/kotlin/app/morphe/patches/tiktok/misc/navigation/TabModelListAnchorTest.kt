package app.morphe.patches.tiktok.misc.navigation

import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab list getters, whose names R8 assigns and whose bodies are identical apart from the
 * method each calls to fill the list. What separates them is the scene that filler reports.
 */
class TabModelListAnchorTest {
    private val assem = "Lcom/ss/android/ugc/aweme/main/assems/tabs/TabAbilityAssem;"
    private val list = "Ljava/util/concurrent/CopyOnWriteArrayList;"

    @Test
    fun `each getter is picked by the scene the method filling its list reports`() {
        val top = getter("W52", "Io", "LL")
        val bottom = getter("OA", "Go", "LLILLL")
        val classDef = assem(top, bottom, filler("Io", "scene_get_top_tab"), filler("Go", "scene_get_bottom_tab"))

        assertTrue(matchesTop(top, classDef))
        assertFalse(matchesTop(bottom, classDef))
        assertTrue(matchesBottom(bottom, classDef))
        assertFalse(matchesBottom(top, classDef))
    }

    @Test
    fun `a getter with a cached fast path is picked by the scene its filler reaches`() {
        // 47.6.3: both getters hand back a cached list first when a flag says so, and the top
        // filler reports its scene through the fillers it calls rather than in its own body.
        val top = MutableMethod(
            ImmutableMethod(
                assem, "Zc2", emptyList(), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, LX/168l;->LIZJ()Z
                    move-result v0
                    if-eqz v0, :fill
                    iget-object v0, p0, $assem->LLLFFI:LX/1QpT;
                    if-eqz v0, :fill
                    iget-object v0, v0, LX/1QpT;->LJIIZILJ:Ljava/lang/ThreadLocal;
                    invoke-virtual { v0 }, Ljava/lang/ThreadLocal;->get()Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, Ljava/util/List;
                    if-eqz v0, :fill
                    return-object v0
                    :fill
                    invoke-virtual { p0 }, $assem->Bp()V
                    iget-object v0, p0, $assem->LLJJJ:$list
                    return-object v0
                """,
            )
        }
        val bottom = getter("IB", "xp", "LLJJJIL")
        val classDef = assem(
            top, bottom,
            caller("Bp", "Fp", "Xp", "Ep"),
            filler("Fp", "scene_get_top_tab"), filler("Xp", "nothing_at_all"), filler("Ep", "scene_get_top_tab"),
            filler("xp", "scene_get_bottom_tab"),
        )

        assertTrue(matchesTop(top, classDef))
        assertFalse(matchesBottom(top, classDef))
        assertTrue(matchesBottom(bottom, classDef))
        assertFalse(matchesTop(bottom, classDef))
    }

    @Test
    fun `a getter that checks its list and rebuilds it is not one of them`() {
        // The two longer getters beside them read several lists, ask one whether it is empty and
        // hand a rebuild to an executor before returning.
        val rebuild = MutableMethod(
            ImmutableMethod(
                assem, "w9", emptyList(), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-virtual { p0 }, $assem->Bp()V
                    iget-object v0, p0, $assem->LLJJJJ:$list
                    invoke-virtual { v0 }, $list->isEmpty()Z
                    move-result v0
                    if-eqz v0, :done
                    iget-object v2, p0, $assem->LLJJJJ:$list
                    iget-object v1, p0, $assem->LLJJJJJIL:$list
                    invoke-static { v2, v1 }, LX/1Cfr;->LIZ(Ljava/util/List;Ljava/util/List;)V
                    :done
                    iget-object v0, p0, $assem->LLJJJJ:$list
                    return-object v0
                """,
            )
        }
        val classDef = assem(rebuild, filler("Bp", "scene_get_top_tab"))

        assertFalse(matchesTop(rebuild, classDef))
    }

    @Test
    fun `a getter that does more than hand back its list is not one of them`() {
        // Three of the five list getters on the class read their list, check it and rebuild it.
        val busy = MutableMethod(
            ImmutableMethod(
                assem, "r9", emptyList(), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            addInstructions(
                """
                    invoke-virtual { p0 }, $assem->Io()V
                    iget-object v0, p0, $assem->LLILZ:$list
                    invoke-virtual { v0 }, $list->isEmpty()Z
                    move-result v0
                    return-object v0
                """,
            )
        }
        val classDef = assem(busy, filler("Io", "scene_get_top_tab"))

        assertFalse(matchesTop(busy, classDef))
    }

    @Test
    fun `a getter that returns its list and then does more is not one of them`() {
        // The three instructions have to be the whole body. A longer method that opens with them
        // returns at the third, so what follows is reached by a branch and belongs to something
        // else this must not rewrite.
        val longer = MutableMethod(
            ImmutableMethod(
                assem, "W52", emptyList(), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            addInstructions(
                """
                    invoke-virtual { p0 }, $assem->Io()V
                    iget-object v0, p0, $assem->LL:$list
                    return-object v0
                    const/4 v0, 0x0
                    return-object v0
                """,
            )
        }
        val classDef = assem(longer, filler("Io", "scene_get_top_tab"))

        assertFalse(matchesTop(longer, classDef))
    }

    @Test
    fun `two getters answering to one scene are both refused`() {
        val first = getter("W52", "Io", "LL")
        val second = getter("r9", "Io", "LLILZ")
        val classDef = assem(first, second, filler("Io", "scene_get_top_tab"))

        assertFalse(matchesTop(first, classDef))
        assertFalse(matchesTop(second, classDef))
    }

    @Test
    fun `a filler that reports no scene answers to neither`() {
        val orphan = getter("W52", "Io", "LL")
        val classDef = assem(orphan, filler("Io", "scene_get_something_else"))

        assertFalse(matchesTop(orphan, classDef))
        assertFalse(matchesBottom(orphan, classDef))
    }

    private fun matchesTop(method: Method, classDef: ClassDef) =
        TopTabModelListFingerprint.custom!!(method, classDef)

    private fun matchesBottom(method: Method, classDef: ClassDef) =
        BottomTabModelListFingerprint.custom!!(method, classDef)

    /** Fill the list, read it, hand it back: the whole body of the two getters that matter. */
    private fun getter(name: String, fills: String, field: String) = MutableMethod(
        ImmutableMethod(
            assem, name, emptyList(), "Ljava/util/List;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply {
        addInstructions(
            """
                invoke-virtual { p0 }, $assem->$fills()V
                iget-object v0, p0, $assem->$field:$list
                return-object v0
            """,
        )
    }

    /** A filler that only calls other fillers, as the top one does on 47.6.3. */
    private fun caller(name: String, vararg calls: String) = MutableMethod(
        ImmutableMethod(
            assem, name, emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(1, emptyList(), null, null),
        ),
    ).apply {
        addInstructions(
            calls.joinToString("\n") { "invoke-virtual { p0 }, $assem->$it()V" } + "\nreturn-void",
        )
    }

    private fun filler(name: String, scene: String) = MutableMethod(
        ImmutableMethod(
            assem, name, emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply {
        addInstructions(
            """
                const-string v0, "$scene"
                return-void
            """,
        )
    }

    private fun assem(vararg methods: Method) = ImmutableClassDef(
        assem,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        null,
        null,
        null,
        null,
        methods.toList(),
    )
}
