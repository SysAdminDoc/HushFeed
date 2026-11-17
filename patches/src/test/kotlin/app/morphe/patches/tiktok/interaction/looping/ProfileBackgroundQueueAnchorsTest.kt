package app.morphe.patches.tiktok.interaction.looping

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the profile page's background queue stands on (#292), held to each declared build: the
 * registrar reads TikTok's queue key, every read of that key is either moved to Hushfeed's key or
 * kept on purpose, both key wrappers and both queue hooks type-check, and the mode hook catches
 * every path into the mode's answer.
 */
class ProfileBackgroundQueueAnchorsTest {
    private fun classes(apk: java.io.File): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
    }

    private fun Method.holds(text: String) = implementation?.instructions?.any {
        it.getReference<StringReference>()?.string == text
    } == true

    private fun scopeOf(all: List<ClassDef>): Method {
        val byType = all.associateBy { it.type }
        val scope = queueScope(byType.getValue(SCOPED_REGISTRAR)) { byType[it] }
        assertNotNull("the registrar no longer reads the queue key", scope)
        return scope!!
    }

    @Test
    fun `the registrar reads the queue key and both wrappers type-check`() {
        Fixtures.forEachDeclared { apk ->
            val scope = scopeOf(classes(apk))
            assertTrue(scope.isQueueScope())
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(backgroundScopeWrapper(scope)))
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(foregroundScopeWrapper(scope)))
        }
    }

    /**
     * #292 on the S22: a page opened from a profile's grid had no profile uid at any key read, so
     * it never got a key. The page parameters name that profile as the page's user instead, on
     * the same class the key reads its profile uid from (where the wrapper calls both), and the
     * detail page's intent parser fills it from the "userid" extra.
     */
    @Test
    fun `the page parameters name a profile grid's user where the wrapper reads it`() {
        Fixtures.forEachDeclared { apk ->
            val all = classes(apk)
            val scope = scopeOf(all)
            val params = scope.parameterTypes.single().toString()
            val profileRead = scope.implementation!!.instructions
                .mapNotNull { it.getReference<MethodReference>() }
                .first { it.name == "getFromProfileUid" }
            assertEquals(params, profileRead.definingClass)
            val byType = all.associateBy { it.type }
            assertEquals(1, byType.getValue(params).methods.count { it.isPageUidRead() })
            val fills = all.asSequence().flatMap { it.methods.asSequence() }.any { method ->
                method.holds("userid") && method.implementation!!.instructions.any { call ->
                    call.getReference<MethodReference>()?.let { it.definingClass == params && it.name == "setUid" } == true
                }
            }
            assertTrue("no detail parser fills $PAGE_UID from \"userid\"", fills)
        }
    }

    @Test
    fun `every read of the queue key is moved or kept on purpose`() {
        Fixtures.forEachDeclared { apk ->
            val all = classes(apk)
            val scope = scopeOf(all)
            val starts = mutableMapOf<String, Int>()
            val screen = mutableListOf<String>()
            val bar = mutableListOf<String>()
            val unknown = mutableListOf<String>()
            for (owner in all) for (method in owner.methods) {
                if (method.queueScopeCalls(scope).isEmpty()) continue
                val where = "${owner.type}->${method.name}"
                when {
                    owner.type in listOf(SCOPED_REGISTRAR, SCOPED_COMPONENT, LIST_PANEL) ->
                        starts.merge(owner.type, 1, Int::plus)
                    method.handsScopeToOwnerToken(scope) && method.holds(OWNER_ON_SELECT) ->
                        starts.merge(OWNER_ON_SELECT, 1, Int::plus)
                    method.handsScopeToOwnerToken(scope) && method.holds(OWNER_ON_PLAY) ->
                        starts.merge(OWNER_ON_PLAY, 1, Int::plus)
                    method.holds(FOREGROUND_NEXT_SKIPPED) -> screen.add(where)
                    // The collection's bottom bar, and the static test for whether to put it up.
                    owner.type == BOTTOM_COLLECTION_BAR -> bar.add(where)
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
                        method.parameterTypes.map(CharSequence::toString) == scope.parameterTypes.map(CharSequence::toString) ->
                        bar.add(where)
                    else -> unknown.add(where)
                }
            }
            assertEquals("reads of the key no patch side accounts for", emptyList<String>(), unknown)
            for (start in listOf(SCOPED_REGISTRAR, SCOPED_COMPONENT, LIST_PANEL, OWNER_ON_SELECT, OWNER_ON_PLAY)) {
                assertTrue("no starting read at $start: $starts", (starts[start] ?: 0) > 0)
            }
            assertEquals("TikTok's own next video: $screen", 1, screen.size)
            assertTrue("the bottom bar's reads: $bar", bar.any { it.startsWith(BOTTOM_COLLECTION_BAR) })
        }
    }

    @Test
    fun `the queue's end of a video takes the limit hook`() {
        Fixtures.forEachDeclared { apk ->
            val ends = classes(apk).flatMap { it.methods }.filter { it.holds(QUEUE_END) && it.holds(QUEUE_END_STATE) }
            assertEquals("ends: ${ends.map { "${it.definingClass}->${it.name}" }}", 1, ends.size)
            val read = ends.single().queueEndRead()
            assertNotNull("the end of a video changed shape", read)
            val patched = MutableMethod(ends.single())
            patched.addInstructions(read!!.at, queueEndHook(read))
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
        }
    }

    /**
     * #292 on the S22: with the profile key in place the video stopped on Home. TikTok's trigger
     * skipped the profile page's queue as "session_disabled" (its switch turns a queue on for your
     * own collections only) after passing over the one-video session. The arming hook goes in on
     * the trigger's check of the session, so every path into that check meets it.
     */
    @Test
    fun `the trigger's session check takes the arming hook`() {
        Fixtures.forEachDeclared { apk ->
            val all = classes(apk)
            val triggers = all.flatMap { it.methods }.filter { it.holds(QUEUE_SESSION_OFF) && it.holds(QUEUE_ARMED) }
            assertEquals("triggers: ${triggers.map { "${it.definingClass}->${it.name}" }}", 1, triggers.size)
            val check = triggers.single().queueSessionCheck()
            assertNotNull("the trigger no longer asks its session before \"$QUEUE_SESSION_OFF\"", check)
            val session = all.single { it.type == check!!.sessionType }
            assertTrue("${session.type} holds no session state", session.fields.any { it.type == QUEUE_SESSION_STATE })
            assertTrue(
                "${session.type} holds no scene",
                session.fields.any { it.type == "Lcom/ss/android/ugc/aweme/backgroundplay/ScopedBgPlayScene;" },
            )
            val patched = MutableMethod(triggers.single())
            patched.addInstructionsAtControlFlowLabel(check!!.at, queueArmHook(check))
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
            val code = patched.implementation!!.instructions.toList()
            assertEquals("armProfileQueue", code[check.at].getReference<MethodReference>()?.name)
            assertEquals(check.sessionType, code[check.at + 1].getReference<MethodReference>()?.definingClass)
        }
    }

    @Test
    fun `the mode hook sits where every path meets`() {
        Fixtures.forEachDeclared { apk ->
            val modes = classes(apk).flatMap { it.methods }.filter {
                it.returnType == "Ljava/lang/Object;" && it.holds(QUEUE_MODE_KEY)
            }
            assertEquals("mode reads: ${modes.map { "${it.definingClass}->${it.name}" }}", 1, modes.size)
            val (at, hook) = modes.single().queueModeHook()
            val patched = MutableMethod(modes.single())
            patched.addInstructionsAtControlFlowLabel(at, hook)
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
            val code = patched.implementation!!.instructions.toList()
            assertEquals("collectionMode", code[at].getReference<MethodReference>()?.name)
            val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
            val targets = code.indices.filter { code[it] is OffsetInstruction }
                .map { addresses.indexOf(addresses[it] + (code[it] as OffsetInstruction).codeOffset) }
            assertTrue("no early out reaches the hook: $targets", at in targets)
            assertTrue("an early out skips the hook: $targets", targets.none { it in at + 1..at + 2 })
        }
    }
}
