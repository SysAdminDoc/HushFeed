package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a long press on Comment or Share plays at the hold speed, held to every fixture.
 *
 * <p>TikTok's hold gesture sits on the feed cell and, before it starts, asks each side button
 * whether the press is on it, a press on one being no hold. The patch answers the Comment, Share
 * and Favorites asks, returns early from the methods that open Comment's emoji row and Favorites'
 * collection offer, and wraps the long presses Share sets for its own menu.
 */
class RailHoldAnchorsTest {
    @Test
    fun `the hold check asks Comment, Share and Favorites on every fixture, each answer moved into a register`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val edge = build.byType.getValue("Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAssem;")
            val checks = edge.methods.filter { method ->
                method.returnType == "Z" && method.parameterTypes.map(CharSequence::toString) == listOf("F", "F") &&
                    method.accessFlags == (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
            }
            assertEquals("${apk.name}: the hold check is ${checks.map { it.name }}", 1, checks.size)
            val check = checks.single()
            assertTrue("${apk.name}: the hold check no longer asks Comment", check.railHitTests(COMMENT_ABILITY).isNotEmpty())
            assertTrue("${apk.name}: the hold check no longer asks Share", check.railHitTests(SHARE_ABILITY).isNotEmpty())
            assertTrue("${apk.name}: the hold check no longer asks Favorites", check.railHitTests(FAVORITES_ABILITY).isNotEmpty())
        }
    }

    @Test
    fun `Comment's emoji row opens in one method a long click reaches, on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> CommentMenuFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { it.second.name }}", 1, taken.size)
            val menu = taken.single().second
            // 46.2.3 calls it from a static onLongClick$0 bridge on the listener, 47.0.3 from onLongClick.
            val longClicks = build.methods.filter { (classDef, method) ->
                method.name.startsWith("onLongClick") && "Landroid/view/View\$OnLongClickListener;" in classDef.interfaces &&
                    method.implementation?.instructions?.any { instruction ->
                        instruction.getReference<MethodReference>()?.let {
                            it.definingClass == VIDEO_COMMENT_ASSEM && it.name == menu.name && it.parameterTypes.isEmpty()
                        } == true
                    } == true
            }.toList()
            assertTrue("${apk.name}: no long-click listener opens the row through ${menu.name}", longClicks.isNotEmpty())
        }
    }

    @Test
    fun `Favorites' collection offer is one lambda ending in Unit, run after its press is marked long, on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> FavoritesMenuFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val instructions = taken.single().second.implementation!!.instructions.toList()
            val unit = instructions.indices.filter { index ->
                instructions[index].opcode == Opcode.SGET_OBJECT &&
                    instructions[index].getReference<FieldReference>()?.type == "Lkotlin/Unit;" &&
                    instructions.getOrNull(index + 1)?.opcode == Opcode.RETURN_OBJECT
            }
            assertTrue("${apk.name}: the offer no longer returns Unit", unit.isNotEmpty())

            // TikTok's touch listener on Favorites saves the video when a press ends that its timer
            // didn't mark long. The timer marks it before it runs the offer, so skipping the offer
            // still leaves letting go unsaved. The timer names itself in a trace label.
            val timers = build.methods.filter { (classDef, method) ->
                method.name == "run" && "Ljava/lang/Runnable;" in classDef.interfaces &&
                    method.implementation?.instructions?.any {
                        (it.getReference<StringReference>()?.string ?: "").startsWith("QuickFavoriteManager")
                    } == true
            }.toList()
            assertEquals("${apk.name}: the Favorites timer is ${timers.map { it.first.type }}", 1, timers.size)
            val timer = timers.single().second.implementation!!.instructions.toList()
            val marks = timer.indexOfFirst { it.opcode == Opcode.IPUT_BOOLEAN }
            val runs = timer.indexOfFirst {
                it.opcode == Opcode.INVOKE_INTERFACE && it.getReference<MethodReference>()?.let { call ->
                    call.definingClass == "Lkotlin/jvm/functions/Function0;" && call.name == "invoke"
                } == true
            }
            assertTrue("${apk.name}: the timer no longer marks the press before the offer", marks in 0 until runs)
        }
    }

    @Test
    fun `Favorites' touch listener asks one icon check before its long press, on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val timers = build.methods.filter { (classDef, method) -> FavoritesTouchTimerFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${timers.map { "${it.first.type}->${it.second.name}" }}", 1, timers.size)
            val listener = build.byType.getValue(favoritesTouchListenerType(timers.single().first) { build.byType[it] })
            val onTouch = listener.methods.single { it.name == "onTouch" }
            // The check gates the post that starts the long press, so its answer is branched on
            // and nothing else reads it (#499).
            assertEquals("${apk.name}: ${listener.type} asks the icon check", 1, onTouch.favoritesPressChecks().size)
            // The same listener runs the collection offer, through the timer.
            val menu = build.methods.filter { (classDef, method) -> FavoritesMenuFingerprint.takes(method, classDef) }.single().first.type
            val timerTakes = timers.single().first.methods.single { it.name == "<init>" }.parameterTypes.map(CharSequence::toString)
            assertTrue("${apk.name}: the timer runs $timerTakes, not the offer $menu", menu in timerTakes)
            val instructions = onTouch.implementation!!.instructions.toList()
            val answer = onTouch.favoritesPressChecks().single()
            assertTrue("${apk.name}: the check's answer isn't branched on next",
                instructions[answer + 1].opcode in listOf(Opcode.IF_EQZ, Opcode.IF_NEZ))
        }
    }

    @Test
    fun `Favorites' bind clears the button once and gives it only a click when signed out, on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val assem = build.byType.getValue(VIDEO_FAVORITE_ASSEM)
            val taken = assem.methods.filter { FavoritesBindFingerprint.takes(it, assem) }
            assertEquals("${apk.name}: ${taken.map { it.name }}", 1, taken.size)
            val bind = taken.single()
            val sites = bind.favoritesBindSites()
            val instructions = bind.implementation!!.instructions.toList()
            // The touch listener that starts TikTok's long press goes on the same button, after the clear.
            val installs = instructions.indices.filter { instructions[it].setsTouch() && it != sites.clearAt }
            assertEquals("${apk.name}: the bind sets ${installs.size} touch listeners besides the clear", 1, installs.size)
            assertEquals(sites.clearButton, (instructions[installs.single()] as FiveRegisterInstruction).registerC)
            assertTrue("${apk.name}: the clear no longer comes first", sites.clearAt < installs.single() && sites.clearAt < sites.plainClickAt)
            assertEquals("${apk.name}: the plain click goes to another view", sites.clearButton, sites.plainClickButton)
            // Signed out, the login check jumps straight to the plain click (#499).
            val login = instructions.indexOfFirst {
                it.getReference<MethodReference>()?.let { call -> call.name == "isLogin" && call.returnType == "Z" } == true
            }
            assertTrue("${apk.name}: the bind no longer asks whether you're signed in", login >= 0)
            assertEquals(Opcode.MOVE_RESULT, instructions[login + 1].opcode)
            val signedOut = instructions[login + 2]
            assertEquals("${apk.name}: the login answer isn't tested for no", Opcode.IF_EQZ, signedOut.opcode)
            assertTrue("${apk.name}: signed out doesn't reach the plain click",
                instructions.runsOnto(instructions.targetOf(login + 2), sites.plainClickAt))
        }
    }

    private fun List<Instruction>.address(index: Int) = take(index).sumOf { it.codeUnits }

    private fun List<Instruction>.targetOf(index: Int): Int {
        val address = address(index) + (this[index] as OffsetInstruction).codeOffset
        return indices.first { address(it) == address }
    }

    /** Whether straight-line code and plain gotos from [from] get to [to] before anything else branches or returns. */
    private fun List<Instruction>.runsOnto(from: Int, to: Int): Boolean {
        var at = from
        repeat(size) {
            if (at == to) return true
            val instruction = this[at]
            at = when {
                instruction.opcode in listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) -> targetOf(at)
                instruction is OffsetInstruction || !instruction.opcode.canContinue() -> return false
                else -> at + 1
            }
        }
        return false
    }

    @Test
    fun `the hold's touch listener is one method on every fixture, dropping its post in each way a press ends`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> HoldTouchFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val instructions = taken.single().second.implementation!!.instructions.toList()
            val drops = instructions.filter { it.dropsCallbacks() }
            assertTrue("${apk.name}: the listener drops its post in ${drops.size} places", drops.size >= 3)
            drops.forEach { drop ->
                assertEquals("${apk.name}: a drop takes the handler and the post", 2, (drop as FiveRegisterInstruction).registerCount)
            }
            assertTrue("${apk.name}: the listener no longer runs the hold check", instructions.any { it.asksHoldCheck() })
        }
    }

    @Test
    fun `Share's view setup sets its long presses, each on a view with a listener, on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> ShareViewCreatedFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val sites = taken.single().second.implementation!!.instructions.filter { it.setsLongClick() }
            assertTrue("${apk.name}: Share sets no long press", sites.isNotEmpty())
            sites.forEach { site ->
                val call = site as FiveRegisterInstruction
                assertEquals("${apk.name}: Share's long press takes the view and the listener", 2, call.registerCount)
            }
        }
    }

    @Test
    fun `the patch answers the three asks and skips each button's menu`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/gesture/LongPressPatch.kt").readText()
        assertTrue("the hold check's asks are not answered", source.contains("EdgeSpeedupEligibilityFingerprint.method.answerRailHitTests()"))
        assertTrue("Comment's emoji row is not skipped", source.contains("CommentMenuFingerprint.method.skipCommentMenuWhenHeld()"))
        assertTrue("Favorites' offer is not skipped", source.contains("FavoritesMenuFingerprint.method.skipFavoritesMenuWhenHeld()"))
        assertTrue("Favorites' icon check is not answered", source.contains(".letFavoritesPressAnywhere()"))
        assertTrue("Favorites' plain click gets no hold", source.contains("FavoritesBindFingerprint.method.giveFavoritesItsOwnHold()"))
        assertTrue("a dropped hold doesn't forget its passes", source.contains("HoldTouchFingerprint.method.routeHoldDrops()"))
        assertTrue("Share's long presses are not wrapped", source.contains("ShareViewCreatedFingerprint.method.routeLongClicks(\"setShareLongClick\")"))
    }

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
