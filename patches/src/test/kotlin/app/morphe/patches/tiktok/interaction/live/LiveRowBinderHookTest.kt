package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private const val BINDER = "Lcom/example/Binder;"
private const val HOLDER = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"
private const val MAKE_HOLDER = "$BINDER->holder(Landroid/view/View;Landroid/view/ViewGroup;)$HOLDER"
private const val READ_PRELOAD = "sget-object v3, $LIVE_ROW_PRELOAD->INSTANCE:$LIVE_ROW_PRELOAD"

/**
 * The LIVE row binder's and the team list's shape checks on hand-written methods (#159). The row
 * binder's base is found by its (int, Boolean) constructor's two fields and its own holder call on
 * `this` and the list, and the hook asks the extension for the row before any of TikTok's code.
 * A binder whose parts don't line up, that leaves the hook too few registers or that does more with
 * its holder than hand it back, is refused. Each
 * getContext() a page asks of itself for a color call takes the page hook, and a context the
 * extension's wrapper could break, or a branch that would skip a hook, is refused. The real build
 * is covered by LiveDarkPanelsAnchorsTest.
 */
class LiveRowBinderHookTest {

    @Test
    fun `the row binder's shape is found and hooked`() {
        val make = makeRow()
        val parts = rowBinder(make, binder())!!
        assertEquals(listOf("layout", "attach", "holder"), listOf(parts.layout.name, parts.attach.name, parts.holder.name))

        hookRowBinder(make, binder())
        val code = make.implementation!!.instructions.toList()
        assertEquals(
            listOf(Opcode.IGET, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            code.take(8).map { it.opcode },
        )
        // p0 is v4 and p2 is v6 in seven registers.
        assertEquals(listOf(0, 4), listOf((code[0] as TwoRegisterInstruction).registerA, (code[0] as TwoRegisterInstruction).registerB))
        assertEquals("layout", ((code[0] as ReferenceInstruction).reference as FieldReference).name)
        assertEquals(listOf(1, 4), listOf((code[1] as TwoRegisterInstruction).registerA, (code[1] as TwoRegisterInstruction).registerB))
        assertEquals("attach", ((code[1] as ReferenceInstruction).reference as FieldReference).name)
        val ask = code[2] as FiveRegisterInstruction
        assertEquals("stockRow", ((ask as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(listOf(3, 6, 0, 1), listOf(ask.registerCount, ask.registerC, ask.registerD, ask.registerE))
        val holder = code[5] as FiveRegisterInstruction
        assertEquals(MAKE_HOLDER, ((holder as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals(listOf(4, 0, 6), listOf(holder.registerC, holder.registerD, holder.registerE))
        assertEquals(0, (code[7] as OneRegisterInstruction).registerA)
        // A null answer lands on TikTok's own first instruction.
        assertEquals(8, (code[4] as BuilderOffsetInstruction).target.location.index)
        assertEquals("layout", ((code[8] as ReferenceInstruction).reference as FieldReference).name)
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(make))
    }

    @Test
    fun `a binder whose constructor doesn't keep both arguments in its own fields is refused`() {
        assertNull("no (int, Boolean) constructor", rowBinder(makeRow(), binder(constructor(
            parameters = listOf("I"),
            body = "iput p1, p0, $BINDER->layout:I\nreturn-void",
        ))))
        assertNull("the layout in another class", rowBinder(makeRow(), binder(constructor(
            layout = "iput p1, p0, Lcom/example/Other;->layout:I",
        ))))
        assertNull("the attach flag from another argument", rowBinder(makeRow(), binder(constructor(
            registers = 4,
            attach = "const/4 v0, 0x0\niput-object v0, p0, $BINDER->attach:Ljava/lang/Boolean;",
        ))))
    }

    @Test
    fun `a binder that doesn't make its row from its own layout and holder call is refused`() {
        assertNull("another layout field", rowBinder(makeRow(layout = "$BINDER->other:I"), binder()))
        assertNull("a lookalike base that never reads LIVE's preload settings", rowBinder(makeRow(preload = ""), binder()))
        assertNull("the holder made on another object", rowBinder(makeRow(make = "invoke-virtual {v3, v0, p2}, $MAKE_HOLDER"), binder()))
        assertNull("the holder made for another list", rowBinder(makeRow(make = "invoke-virtual {p0, v0, v3}, $MAKE_HOLDER"), binder()))
        assertNull("another class's holder call", rowBinder(makeRow(make =
            "invoke-virtual {p0, v0, p2}, Lcom/example/Other;->holder(Landroid/view/View;Landroid/view/ViewGroup;)$HOLDER"), binder()))
        assertNull("two holder calls", rowBinder(makeRow(make =
            "invoke-virtual {p0, v0, p2}, $MAKE_HOLDER\n" +
                "invoke-virtual {p0, v0, p2}, $BINDER->other(Landroid/view/View;Landroid/view/ViewGroup;)$HOLDER"), binder()))
    }

    @Test
    fun `a binder with fewer than two free registers is refused`() {
        // p0 is v1 in four registers, so only v0 is free.
        val make = method(
            BINDER, "make", listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;"), HOLDER, 4,
            """
                sget-object v0, $LIVE_ROW_PRELOAD->INSTANCE:$LIVE_ROW_PRELOAD
                iget v0, p0, $BINDER->layout:I
                invoke-static {v0, p2}, LX/0JT2;->LJ(ILandroid/view/ViewGroup;)Landroid/view/View;
                move-result-object v0
                invoke-virtual {p0, v0, p2}, $MAKE_HOLDER
                move-result-object v0
                return-object v0
            """,
        )
        rowBinder(make, binder())!!
        assertThrows(PatchException::class.java) { hookRowBinder(make, binder()) }
    }

    @Test
    fun `a binder with its list above v15 is refused`() {
        // p0 is v14 and p2 is v16 in seventeen registers. A four-bit call can't reach the list, so
        // TikTok's would be a range call, which isn't the holder call the binder is found by.
        val make = method(
            BINDER, "make", listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;"), HOLDER, 17,
            """
                $READ_PRELOAD
                iget v1, p0, $BINDER->layout:I
                move-object/from16 v2, p2
                invoke-static {v1, v2}, LX/0JT2;->LJ(ILandroid/view/ViewGroup;)Landroid/view/View;
                move-result-object v15
                invoke-virtual/range {v14 .. v16}, $MAKE_HOLDER
                move-result-object v0
                return-object v0
            """,
        )
        assertNull(rowBinder(make, binder()))
        assertThrows(PatchException::class.java) { hookRowBinder(make, binder()) }
    }

    @Test
    fun `a binder that does more with its holder than hand it back is refused`() {
        val tagged = makeRow(handBack = "move-result-object v0\n" +
            "invoke-virtual {v0}, $HOLDER->toString()Ljava/lang/String;\nreturn-object v0")
        rowBinder(tagged, binder())!!
        assertThrows(PatchException::class.java) { hookRowBinder(tagged, binder()) }
        val other = makeRow(handBack = "move-result-object v1\nreturn-object v0")
        rowBinder(other, binder())!!
        assertThrows(PatchException::class.java) { hookRowBinder(other, binder()) }
        val dropped = makeRow(handBack = "const/4 v0, 0x0\nreturn-object v0")
        rowBinder(dropped, binder())!!
        assertThrows(PatchException::class.java) { hookRowBinder(dropped, binder()) }
    }

    @Test
    fun `each context a page asks of itself for a color takes the hook`() {
        // The color id going into v1 ends the first context's life there.
        val page = page(after = """
            const v1, 0x7f061f38
            invoke-virtual {p0}, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;
            move-result-object v0
            invoke-static {v1, v0}, LX/0Jvl;->LIZIZ(ILandroid/content/Context;)I
            move-result v0
        """)
        assertEquals(listOf(1 to 1, 7 to 0), pageContexts(page))

        hookPageContexts(page)
        val code = page.implementation!!.instructions.toList()
        // p0 is v2 in five registers; the first hook moves the second one down by two.
        for ((result, context) in listOf(1 to 1, 9 to 0)) {
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[result].opcode)
            val hook = code[result + 1] as FiveRegisterInstruction
            assertEquals("stockContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(listOf(2, context), listOf(hook.registerC, hook.registerD))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[result + 2].opcode)
            assertEquals(context, (code[result + 2] as OneRegisterInstruction).registerA)
        }
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(page))
    }

    @Test
    fun `a page whose contexts the wrapper could break is refused`() {
        assertThrows("the context over the page", PatchException::class.java) { pageContexts(page(context = "p0")) }
        assertThrows("a cast to Activity", PatchException::class.java) {
            pageContexts(page(after = "check-cast v1, Landroid/app/Activity;"))
        }
        assertThrows("kept in a field", PatchException::class.java) {
            pageContexts(page(after = "iput-object v1, p0, Lcom/example/Page;->kept:Landroid/content/Context;"))
        }
        assertThrows("no color call", PatchException::class.java) {
            pageContexts(page(color = "invoke-static {v0, v1}, Lcom/example/Page;->keep(ILandroid/content/Context;)V\nconst/4 v0, 0x0"))
        }
        // Only the page's own context is asked for: a view's isn't, so this page has none.
        assertThrows("the view's context", PatchException::class.java) {
            pageContexts(page(ask = "invoke-virtual {p1}, Landroid/view/View;->getContext()Landroid/content/Context;"))
        }
    }

    @Test
    fun `a branch landing right after a context is refused`() {
        val page = page(before = "if-eqz p1, :colored", color = ":colored\nconst v0, 0x7f061f36\n" +
            "invoke-static {v0, v1}, LX/0Jvl;->LIZIZ(ILandroid/content/Context;)I\nmove-result v0")
        pageContexts(page)
        assertThrows(PatchException::class.java) { hookPageContexts(page) }
    }

    /** A row binder's class holding only [constructor], the part [rowBinder] reads of it. */
    private fun binder(constructor: MutableMethod = constructor()) = ImmutableClassDef(
        BINDER, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
        null, null, null, null, listOf(constructor),
    )

    /** A binder base's constructor shaped like 47.4.3's, in three registers unless told. */
    private fun constructor(
        layout: String = "iput p1, p0, $BINDER->layout:I",
        attach: String = "iput-object p2, p0, $BINDER->attach:Ljava/lang/Boolean;",
        parameters: List<String> = listOf("I", "Ljava/lang/Boolean;"),
        registers: Int = 3,
        body: String = "invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n$layout\n$attach\nreturn-void",
    ) = method(BINDER, "<init>", parameters, "V", registers, body, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)

    /** A binder base's row maker shaped like 47.4.3's fallback, in seven registers: p0 is v4, p2 is v6. */
    private fun makeRow(
        layout: String = "$BINDER->layout:I",
        make: String = "invoke-virtual {p0, v0, p2}, $MAKE_HOLDER",
        preload: String = READ_PRELOAD,
        handBack: String = "move-result-object v0\nreturn-object v0",
    ) = method(
        BINDER, "make", listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;"), HOLDER, 7,
        """
            iget v1, p0, $layout
            $preload
            const/4 v2, 0x0
            invoke-static {v1, p2, v2}, LX/0JT2;->LJIILL(ILandroid/view/ViewGroup;Z)Landroid/view/View;
            move-result-object v0
            $make
            $handBack
        """,
    )

    /** A page's onViewCreated coloring a text through its own context in [context], in five registers: p0 is v2. */
    private fun page(
        context: String = "v1",
        ask: String = "invoke-virtual {p0}, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;",
        before: String = "",
        color: String = "const v0, 0x7f061f36\ninvoke-static {v0, $context}, LX/0Jvl;->LIZIZ(ILandroid/content/Context;)I\nmove-result v0",
        after: String = "",
    ) = method(
        "Lcom/example/Page;", "onViewCreated", listOf("Landroid/view/View;", "Landroid/os/Bundle;"), "V", 5,
        "$before\n$ask\nmove-result-object $context\n$color\n$after\nreturn-void",
    )

    private fun method(
        owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ) = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n")) }
}
