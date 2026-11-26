package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rail hold writes into TikTok's methods, instruction by instruction, on methods shaped
 * like 47.0.3's: which extension call each ask's answer goes through and in which register, where
 * each menu skip returns to, and that every branch TikTok had still lands where it did.
 */
class RailHoldHookTest {
    private val edge = "Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAssem;"
    private val like = "Lcom/ss/android/ugc/feed/platform/cell/ability/VideoDiggAssemAbility;"
    private val view = "Landroid/view/View;"
    private val onLongClick = "Landroid/view/View\$OnLongClickListener;"

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) =
        MutableMethod(ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).apply { addInstructionsWithLabels(0, smali) }

    private fun List<Instruction>.address(index: Int) = take(index).sumOf { it.codeUnits }

    private fun List<Instruction>.targetOf(index: Int): Int {
        val address = address(index) + (this[index] as OffsetInstruction).codeOffset
        return indices.first { address(it) == address }
    }

    /** Each branch of [before] against the instruction it lands on. */
    private fun branches(before: List<Instruction>) =
        before.indices.filter { before[it] is OffsetInstruction }.associate { before[it] to before[before.targetOf(it)] }

    private fun assertBranchesKept(kept: Map<Instruction, Instruction>, after: List<Instruction>) {
        kept.forEach { (branch, target) ->
            val index = after.indexOfFirst { it === branch }
            assertSame("a branch moved off its target", target, after[after.targetOf(index)])
        }
    }

    private fun assertExtensionCall(instruction: Instruction, name: String, parameters: List<String>, returnType: String) {
        val reference = instruction.getReference<MethodReference>()!!
        assertEquals(RAIL_HOLD_EXTENSION, reference.definingClass)
        assertEquals(name, reference.name)
        assertEquals(parameters, reference.parameterTypes.map(CharSequence::toString))
        assertEquals(returnType, reference.returnType)
    }

    /** 47.0.3's Z32 with its asks, the Favorites answer moved into v2 so a register slip shows. */
    private fun holdCheck(withFavorites: Boolean = true) = method(edge, "Z32", listOf("F", "F"), "Z", 10, """
        const/4 v6, 0x0
        const/4 v1, 0x1
        iget-object v0, p0, $edge->share:$SHARE_ABILITY
        invoke-interface { v0, p1, p2 }, $SHARE_ABILITY->gc1(FF)Z
        move-result v0
        if-ne v0, v1, :comment
        return v6
        :comment
        iget-object v0, p0, $edge->comment:$COMMENT_ABILITY
        invoke-interface { v0, p1, p2 }, $COMMENT_ABILITY->Z20(FF)Z
        move-result v0
        if-ne v0, v1, :like
        return v6
        :like
        iget-object v0, p0, $edge->like:$like
        invoke-interface { v0, p1, p2 }, $like->dp0(FF)Z
        move-result v0
        if-ne v0, v1, :favorite
        return v6
        :favorite
        ${if (withFavorites) """
        iget-object v0, p0, $edge->favorite:$FAVORITES_ABILITY
        invoke-interface { v0, p1, p2 }, $FAVORITES_ABILITY->go2(FF)Z
        move-result v2
        if-ne v2, v1, :allowed
        return v6
        """ else ""}
        :allowed
        return v1
    """)

    @Test
    fun `each ask's answer goes through its own extension call, in the ask's register, and Like's is left alone`() {
        val method = holdCheck()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)

        method.answerRailHitTests()

        val after = method.implementation!!.instructions.toList()
        assertEquals("two instructions for each of the three asks", before.size + 6, after.size)
        for ((ability, answer, register) in listOf(
            Triple(SHARE_ABILITY, "shareStopsHold", 0),
            Triple(COMMENT_ABILITY, "commentStopsHold", 0),
            Triple(FAVORITES_ABILITY, "favoritesStopsHold", 2),
        )) {
            val ask = after.indexOfFirst { it.getReference<MethodReference>()?.definingClass == ability }
            assertEquals(Opcode.MOVE_RESULT, after[ask + 1].opcode)
            val call = after[ask + 2]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertExtensionCall(call, answer, listOf("Z"), "Z")
            assertEquals("$answer reads the ask's own answer", register, (call as RegisterRangeInstruction).startRegister)
            assertEquals(1, call.registerCount)
            assertEquals(Opcode.MOVE_RESULT, after[ask + 3].opcode)
            assertEquals("$answer's answer goes back where TikTok reads it", register, (after[ask + 3] as OneRegisterInstruction).registerA)
            assertEquals("TikTok's own test of the answer follows", Opcode.IF_NE, after[ask + 4].opcode)
        }
        val likeAsk = after.indexOfFirst { it.getReference<MethodReference>()?.definingClass == like }
        assertEquals(Opcode.IF_NE, after[likeAsk + 2].opcode)
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a hold check that stopped asking a button is refused`() {
        assertThrows(PatchException::class.java) { holdCheck(withFavorites = false).answerRailHitTests() }
    }

    @Test
    fun `Comment's emoji row returns before anything of TikTok's runs when the extension says so`() {
        val method = method(VIDEO_COMMENT_ASSEM, "fs", listOf(), "V", 15, """
            iget-object v0, p0, $VIDEO_COMMENT_ASSEM->LLJJJJLIIL:Ljava/lang/Object;
            if-eqz v0, :done
            const-string v1, "$COMMENT_MENU_KEY"
            :done
            return-void
        """)
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)

        method.skipCommentMenuWhenHeld()

        val after = method.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC, after[0].opcode)
        assertExtensionCall(after[0], "skipCommentMenu", listOf(), "Z")
        assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals(Opcode.RETURN_VOID, after[3].opcode)
        assertEquals("no skip goes on to TikTok's first instruction", Opcode.NOP, after[after.targetOf(2)].opcode)
        assertEquals(after.targetOf(2) + 1, after.indexOfFirst { it === before[0] })
        before.forEachIndexed { index, instruction -> assertSame(instruction, after[after.size - before.size + index]) }
        assertBranchesKept(kept, after)
    }

    /** 0BJ9's shape: the log, the login check, the offer, and Unit loaded and returned mid-method. */
    private fun favoritesMenu(unitRegister: Int = 0) = method("LX/0AMX;", "invoke", listOf(), "Ljava/lang/Object;", 8, """
        const-string v0, "$FAVORITES_MENU_EVENT"
        invoke-static {}, LX/0Y17;->LJFF()Ljava/lang/Object;
        move-result-object v1
        if-eqz v1, :unit
        const-string/jumbo v0, "$FAVORITES_MENU_KEY"
        :unit
        sget-object v$unitRegister, Lkotlin/Unit;->LIZ:Lkotlin/Unit;
        return-object v0
        :late
        const/4 v2, 0x0
        goto :unit
    """)

    @Test
    fun `Favorites' offer jumps to its own Unit return when the extension says so`() {
        val method = favoritesMenu()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)
        val unit = before.first { it.opcode == Opcode.SGET_OBJECT }

        method.skipFavoritesMenuWhenHeld()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size + 3, after.size)
        assertEquals(Opcode.INVOKE_STATIC, after[0].opcode)
        assertExtensionCall(after[0], "skipFavoritesMenu", listOf(), "Z")
        assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, after[2].opcode)
        assertEquals(0, (after[2] as OneRegisterInstruction).registerA)
        assertSame("a skip lands on the lambda's Unit load", unit, after[after.targetOf(2)])
        before.forEachIndexed { index, instruction -> assertSame(instruction, after[index + 3]) }
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a Favorites offer whose Unit load isn't what it returns is refused`() {
        assertThrows(PatchException::class.java) { favoritesMenu(unitRegister = 1).skipFavoritesMenuWhenHeld() }
    }

    @Test
    fun `a Favorites offer with no register of its own to spare is refused`() {
        val bare = method("LX/0AMX;", "invoke", listOf(), "Ljava/lang/Object;", 1, """
            sget-object p0, Lkotlin/Unit;->LIZ:Lkotlin/Unit;
            return-object p0
        """)
        assertThrows(PatchException::class.java) { bare.skipFavoritesMenuWhenHeld() }
    }

    /** 47.0.3's hold listener, cut down: the check on a press, and the post dropped in two places. */
    private fun holdTouch(drops: Boolean = true) = method("LX/0ARw;", "onTouch", listOf(view, "Landroid/view/MotionEvent;"), "Z", 11, """
        const/4 v5, 0x0
        iget-object v4, p0, LX/0ARw;->LLJL:$EDGE_SPEEDUP_ABILITY
        invoke-virtual {p2}, Landroid/view/MotionEvent;->getActionMasked()I
        move-result v2
        if-nez v2, :later
        const/4 v0, 0x0
        invoke-interface {v4, v0, v0}, $EDGE_SPEEDUP_ABILITY->Z32(FF)Z
        move-result v0
        return v0
        :later
        iget-object v1, p0, LX/0ARw;->LL:Landroid/os/Handler;
        iget-object v0, p0, LX/0ARw;->LLJLL:Ljava/lang/Runnable;
        ${if (drops) "invoke-virtual {v1, v0}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V" else "invoke-virtual {v1, v0}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z"}
        iget-object v3, p0, LX/0ARw;->LL:Landroid/os/Handler;
        ${if (drops) "invoke-virtual {v3, v0}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V" else "nop"}
        return v5
    """)

    @Test
    fun `each of the hold listener's drops goes through the extension with TikTok's handler and post`() {
        val method = holdTouch()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)

        method.routeHoldDrops()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size, after.size)
        val drops = before.indices.filter { before[it].opcode == Opcode.INVOKE_VIRTUAL && before[it].getReference<MethodReference>()?.name == "removeCallbacks" }
        assertEquals(2, drops.size)
        for ((index, handler) in drops.zip(listOf(1, 3))) {
            assertEquals(Opcode.INVOKE_STATIC, after[index].opcode)
            assertExtensionCall(after[index], "holdDropped", listOf("Landroid/os/Handler;", "Ljava/lang/Runnable;"), "V")
            val call = after[index] as FiveRegisterInstruction
            assertEquals(2, call.registerCount)
            assertEquals("TikTok's handler", handler, call.registerC)
            assertEquals("TikTok's post", 0, call.registerD)
        }
        before.indices.filter { it !in drops }.forEach { assertSame(before[it], after[it]) }
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a hold listener that no longer drops its post is refused`() {
        assertThrows(PatchException::class.java) { holdTouch(drops = false).routeHoldDrops() }
    }

    @Test
    fun `each of Share's long presses goes through the extension with TikTok's view and listener`() {
        val method = method(VIDEO_SHARE_ASSEM, "onViewCreated", listOf(view), "V", 4, """
            new-instance v0, LX/08lz;
            invoke-direct {v0, p0}, LX/08lz;-><init>($VIDEO_SHARE_ASSEM)V
            invoke-virtual {p1, v0}, $view->setOnLongClickListener($onLongClick)V
            const v1, 0x7f0c0000
            invoke-virtual {p1, v1}, $view->findViewById(I)$view
            move-result-object v1
            invoke-virtual {v1, v0}, $view->setOnLongClickListener($onLongClick)V
            return-void
        """)
        val before = method.implementation!!.instructions.toList()

        method.routeLongClicks("setShareLongClick")

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size, after.size)
        for ((index, registers) in listOf(2 to (3 to 0), 6 to (1 to 0))) {
            assertEquals(Opcode.INVOKE_STATIC, after[index].opcode)
            assertExtensionCall(after[index], "setShareLongClick", listOf(view, onLongClick), "V")
            val call = after[index] as FiveRegisterInstruction
            assertEquals(2, call.registerCount)
            assertEquals("the view", registers.first, call.registerC)
            assertEquals("TikTok's listener", registers.second, call.registerD)
        }
        before.indices.filter { it != 2 && it != 6 }.forEach { assertSame(before[it], after[it]) }
    }

    @Test
    fun `a view setup with no long press is refused`() {
        val method = method(VIDEO_SHARE_ASSEM, "onViewCreated", listOf(view), "V", 2, "return-void")
        assertThrows(PatchException::class.java) { method.routeLongClicks("setShareLongClick") }
    }

    private val function2 = "Lkotlin/jvm/functions/Function2;"

    /**
     * 47.9.3's Favorites listener as a press lands, cut down: the icon check unboxed into v5, so a
     * register slip shows, then the long press's post.
     */
    private fun favoritesTouch(checks: Int = 1) = method("LX/0BGU;", "onTouch", listOf(view, "Landroid/view/MotionEvent;"), "Z", 13, """
        const/4 v8, 0x0
        invoke-virtual {p2}, Landroid/view/MotionEvent;->getAction()I
        move-result v1
        if-nez v1, :done
        iget-object v0, p0, LX/0BGU;->check:$function2
        if-eqz v0, :post
        ${"""
        invoke-interface {v0, p1, p2}, $function2->invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v6
        check-cast v6, Ljava/lang/Boolean;
        invoke-virtual {v6}, Ljava/lang/Boolean;->booleanValue()Z
        move-result v5
        if-nez v5, :post
        """.repeat(checks)}
        goto :done
        :post
        iget-object v2, p0, LX/0BGU;->timer:Ljava/lang/Runnable;
        :done
        return v8
    """)

    @Test
    fun `Favorites' icon check goes through the extension in its own register`() {
        val method = favoritesTouch()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)
        val moveAt = before.indexOfFirst { it.opcode == Opcode.MOVE_RESULT && (it as OneRegisterInstruction).registerA == 5 }

        method.letFavoritesPressAnywhere()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size + 2, after.size)
        val call = after[moveAt + 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertExtensionCall(call, "favoritesPressCounts", listOf("I"), "Z")
        assertEquals(5, (call as RegisterRangeInstruction).startRegister)
        assertEquals(1, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT, after[moveAt + 2].opcode)
        assertEquals(5, (after[moveAt + 2] as OneRegisterInstruction).registerA)
        assertSame("TikTok's branch on the answer comes next", before[moveAt + 1], after[moveAt + 3])
        (0..moveAt).forEach { assertSame(before[it], after[it]) }
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a Favorites listener with no icon check, or two, is refused`() {
        assertThrows(PatchException::class.java) { favoritesTouch(checks = 0).letFavoritesPressAnywhere() }
        assertThrows(PatchException::class.java) { favoritesTouch(checks = 2).letFavoritesPressAnywhere() }
    }

    private val onTouch = "Landroid/view/View\$OnTouchListener;"
    private val onClick = "Landroid/view/View\$OnClickListener;"

    /**
     * 47.9.3's Favorites bind (A4), cut down: the touch listener taken off the button in v5, then
     * signed in the touch listener with the long press, signed out only the plain click, given
     * the button from v4 so a register slip shows.
     */
    private fun favoritesBind(clears: Int = 1, plainClicks: Int = 1, jumpOntoClick: Boolean = false): MutableMethod {
        val clear = """
            const/4 v0, 0x0
            invoke-virtual {v5, v0}, $view->setOnTouchListener($onTouch)V
        """
        val build = """
            new-instance v0, LX/0Dgw;
            invoke-direct {v0, p0}, LX/0Dgw;-><init>($VIDEO_FAVORITE_ASSEM)V
        """
        val click = "invoke-static {v0, v4}, LX/00iY;->W7($onClick$view)V\n"
        val plain = (1..plainClicks).joinToString("") { build + (if (it == 1 && jumpOntoClick) ":click\n" else "") + click }
        return method(VIDEO_FAVORITE_ASSEM, "A4", listOf("Ljava/lang/Object;"), "V", 8, """
            const/4 v1, 0x1
            invoke-virtual {p0}, $VIDEO_FAVORITE_ASSEM->getContentView()$view
            move-result-object v5
            ${clear.repeat(clears)}
            invoke-virtual {p0}, $VIDEO_FAVORITE_ASSEM->getContext()Landroid/content/Context;
            invoke-virtual {p0}, $VIDEO_FAVORITE_ASSEM->isLogin()Z
            move-result v0
            if-eqz v0, :plain
            new-instance v3, Lqua/a;
            invoke-direct {v3}, Lqua/a;-><init>()V
            invoke-virtual {v5, v3}, $view->setOnTouchListener($onTouch)V
            ${if (jumpOntoClick) "goto :click" else "goto :done"}
            :plain
            move-object v4, v5
            ${plain.ifEmpty { "nop" }}
            :done
            const-string v2, "$FAVORITES_BIND_LABEL"
            return-void
        """)
    }

    @Test
    fun `Favorites' bind hands the button to the extension after the clear and before the plain click`() {
        val method = favoritesBind()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)
        val clearAt = before.indexOfFirst { it.setsTouch() }
        val plainAt = before.indexOfFirst { it.getReference<MethodReference>()?.name == "W7" }

        method.giveFavoritesItsOwnHold()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size + 2, after.size)
        assertSame(before[clearAt], after[clearAt])
        val bound = after[clearAt + 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, bound.opcode)
        assertExtensionCall(bound, "favoritesBound", listOf(view), "V")
        assertEquals("the button TikTok cleared", 5, (bound as RegisterRangeInstruction).startRegister)
        assertEquals(1, bound.registerCount)
        val clickAt = after.indexOfFirst { it === before[plainAt] }
        val clickOnly = after[clickAt - 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, clickOnly.opcode)
        assertExtensionCall(clickOnly, "favoritesClickOnly", listOf(view), "V")
        assertEquals("the button TikTok gives the click", 4, (clickOnly as RegisterRangeInstruction).startRegister)
        assertEquals(1, clickOnly.registerCount)
        assertSame("the listener's build comes right before", before[plainAt - 1], after[clickAt - 2])
        // Signed out reaches the click-only call; signed in, with TikTok's own long press, jumps past it.
        val signedOut = after.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertTrue(after.targetOf(signedOut) < clickAt - 1)
        val signedIn = after.indexOfFirst { it.opcode == Opcode.GOTO }
        assertTrue(after.targetOf(signedIn) > clickAt)
        assertEquals(before, after.filter { it.getReference<MethodReference>()?.definingClass != RAIL_HOLD_EXTENSION })
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a Favorites bind that clears twice, gives no plain click or two, or jumps onto it is refused`() {
        assertThrows(PatchException::class.java) { favoritesBind(clears = 2).giveFavoritesItsOwnHold() }
        assertThrows(PatchException::class.java) { favoritesBind(clears = 0).giveFavoritesItsOwnHold() }
        assertThrows(PatchException::class.java) { favoritesBind(plainClicks = 0).giveFavoritesItsOwnHold() }
        assertThrows(PatchException::class.java) { favoritesBind(plainClicks = 2).giveFavoritesItsOwnHold() }
        assertThrows(PatchException::class.java) { favoritesBind(jumpOntoClick = true).giveFavoritesItsOwnHold() }
    }

    private fun classDef(type: String, interfaces: List<String>, methods: List<ImmutableMethod> = emptyList()) =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", interfaces, null, null, null, methods)

    @Test
    fun `the Favorites timer leads to the one touch listener it was built for`() {
        val listener = classDef("LX/0BGU;", listOf("Landroid/view/View\$OnTouchListener;"))
        val menu = classDef("LX/0BFZ;", listOf("Lkotlin/jvm/functions/Function0;"))
        val constructor = ImmutableMethod(
            "LX/0BGG;", "<init>", listOf(listener.type, menu.type).map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null, null,
        )
        val timer = classDef("LX/0BGG;", listOf("Ljava/lang/Runnable;"), listOf(constructor))
        val types = listOf(listener, menu).associateBy { it.type }

        assertEquals("LX/0BGU;", favoritesTouchListenerType(timer) { types[it] })
        assertThrows(PatchException::class.java) { favoritesTouchListenerType(timer) { null } }
    }
}
