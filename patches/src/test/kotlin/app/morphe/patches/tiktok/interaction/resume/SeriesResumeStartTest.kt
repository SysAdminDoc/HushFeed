/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.BridgeTypeFlow
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PineDrama's start method, PlayerController.w2 on 47.5.3, asks the drama app-diff store where an
 * episode starts whenever the caller has no start, and the unpause path ("resumePlay") never has
 * one, so a paused episode played over from the series' start point (#156). The hook goes on the
 * line after that answer and takes its registers from the method rather than by number.
 */
class SeriesResumeStartTest {
    private val controller = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
    private val protocol = "Lcom/ss/android/ugc/aweme/drama/IDramaOfflineFeedAppDiffProtocol;"
    private val manager = "LX/F1x;"
    private val state = "LX/EzD;"

    @Test
    fun `the hook takes the answer, the caller's start and tag, and two dead locals`() {
        val start = start()
        val site = seriesResumeStartSiteIn(start)
        assertEquals("the line after the answer", answerIndexOf(start) + 1, site.index)
        assertEquals("app-diff answer", 2, site.appDiff)
        assertEquals("caller's start, p1", 4, site.requested)
        assertEquals("tag, p3", 6, site.tag)
        assertEquals("manager scratch", 0, site.manager)
        assertEquals("paused scratch", 1, site.paused)
    }

    @Test
    fun `a local the host reads after the answer is left alone`() {
        // v0 still holds the event type and is read again, so the hook works in v1 and v3.
        val start = start(locals = 4, afterAnswer = "sput-object v0, LX/Hold;->LIZ:Ljava/lang/String;")
        val site = seriesResumeStartSiteIn(start)
        assertEquals(listOf(1, 3), listOf(site.manager, site.paused))
        assertEquals("p1 moves with the locals", 5, site.requested)
        assertEquals("p3 moves with the locals", 7, site.tag)
    }

    @Test
    fun `no two free locals is refused`() {
        val start = start(afterAnswer = "sput-object v0, LX/Hold;->LIZ:Ljava/lang/String;")
        assertRefused("no two free locals") { seriesResumeStartSiteIn(start) }
    }

    @Test
    fun `a start method that never asks the store is refused`() {
        val start = start(ask = false)
        assertRefused("doesn't ask the drama app-diff store exactly once") { seriesResumeStartSiteIn(start) }
    }

    @Test
    fun `an answer the method doesn't keep is refused`() {
        val start = start(keepAnswer = false)
        assertRefused("doesn't keep the app-diff store's answer") { seriesResumeStartSiteIn(start) }
    }

    @Test
    fun `a jump onto the line after the answer is refused`() {
        val start = start(jumpOntoAnswer = true)
        assertRefused("jumps onto the line after the app-diff answer") { seriesResumeStartSiteIn(start) }
    }

    @Test
    fun `the paused check reads the field and finds isPaused on a super-interface`() {
        val classes = classes()
        val check = playerPausedCheckIn(classes.getValue(controller)) { classes[it] }
        assertEquals("$controller->mPlayerManager:$manager", check.field)
        assertEquals(manager, check.managerType)
        assertTrue("an interface is invoked through invoke-interface", check.throughInterface)
    }

    @Test
    fun `a manager that can't answer isPaused is refused`() {
        val classes = classes(stateAnswersPaused = false)
        assertRefused("can't answer isPaused()") {
            playerPausedCheckIn(classes.getValue(controller)) { classes[it] }
        }
    }

    @Test
    fun `a controller with no player manager field is refused`() {
        val classes = classes(managerField = "mPlayer")
        assertRefused("has no mPlayerManager field") {
            playerPausedCheckIn(classes.getValue(controller)) { classes[it] }
        }
    }

    @Test
    fun `the installed hook asks the player and keeps the extension's answer`() {
        val start = start()
        val classes = classes()
        val stockSize = start.implementation!!.instructions.count()
        start.installSeriesResumeStart(
            seriesResumeStartSiteIn(start),
            playerPausedCheckIn(classes.getValue(controller)) { classes[it] },
        )

        val code = start.implementation!!.instructions.toList()
        assertEquals("seven lines go in", stockSize + 7, code.size)
        val hook = code.subList(answerIndexOf(start) + 1, answerIndexOf(start) + 8)
        assertEquals(
            listOf(
                Opcode.IGET_OBJECT, Opcode.CONST_4, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE,
                Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
            ),
            hook.map { it.opcode },
        )
        assertEquals("mPlayerManager", hook[0].getReference<FieldReference>()?.name)
        assertEquals("isPaused", hook[3].getReference<MethodReference>()?.name)
        val call = hook[5].getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/interaction/ResumeVideoAfterScrollPatch;", call.definingClass)
        assertEquals("seriesResumeStart", call.name)
        assertEquals(listOf("I", "I", "Ljava/lang/String;", "I"), call.parameterTypes.map { it.toString() })
        assertEquals("the answer comes back into the host's register", 2, (hook[6] as OneRegisterInstruction).registerA)
        assertEquals(
            "the host's own check still reads it next",
            Opcode.IF_LEZ,
            code[answerIndexOf(start) + 8].opcode,
        )
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(start))
    }

    @Test
    fun `a manager class rather than an interface is invoked virtually`() {
        val classes = classes(managerIsInterface = false)
        assertFalse(playerPausedCheckIn(classes.getValue(controller)) { classes[it] }.throughInterface)
    }

    private fun answerIndexOf(start: MutableMethod): Int =
        start.implementation!!.instructions.toList().indexOfFirst {
            it.getReference<MethodReference>()?.definingClass == protocol
        } + 1

    /** w2 as PineDrama 47.5.3 has it, in [locals] locals plus the controller and three parameters. */
    private fun start(
        locals: Int = 3,
        afterAnswer: String = "",
        ask: Boolean = true,
        keepAnswer: Boolean = true,
        jumpOntoAnswer: Boolean = false,
    ) = MutableMethod(
        ImmutableMethod(
            controller,
            "w2",
            listOf("I", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;")
                .map { ImmutableMethodParameter(it, null, null) },
            "I",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(locals + 4, emptyList(), null, null),
        ),
    ).apply {
        val question = if (ask) {
            "invoke-interface {v1, p2, v0}, $protocol->LJJI(Lcom/ss/android/ugc/aweme/feed/model/Aweme;Ljava/lang/String;)I"
        } else {
            "invoke-static {p2, v0}, LX/Hold;->LJJI(Lcom/ss/android/ugc/aweme/feed/model/Aweme;Ljava/lang/String;)I"
        }
        addInstructions(
            0,
            """
                if-lez p1, :ask
                ${if (jumpOntoAnswer) "goto :answered" else "return p1"}
                :ask
                sget-object v0, $protocol->LIZIZ:LX/F3z;
                invoke-virtual {v0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
                invoke-static {}, LX/F3z;->LIZ()$protocol
                move-result-object v1
                iget-object v0, p0, Lcom/ss/android/ugc/aweme/feed/controller/BaseController;->mEventType:Ljava/lang/String;
                $question
                ${if (keepAnswer) "move-result v2" else "const/4 v2, 0x0"}
                :answered
                $afterAnswer
                if-lez v2, :none
                new-instance v1, Ljava/lang/StringBuilder;
                invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
                invoke-virtual {v1, p3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                const-string v0, " appDiff initialStartTimeMs="
                invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
                return v2
                :none
                return p1
            """,
        )
    }

    /** The controller, its manager interface, and the interface the manager takes isPaused from. */
    private fun classes(
        stateAnswersPaused: Boolean = true,
        managerField: String = "mPlayerManager",
        managerIsInterface: Boolean = true,
    ): Map<String, ClassDef> {
        val interfaceFlags = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value
        fun abstractMethod(owner: String, name: String, returns: String) = ImmutableMethod(
            owner, name, emptyList(), returns,
            AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
        )
        val stateClass = ImmutableClassDef(
            state, interfaceFlags, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                abstractMethod(state, if (stateAnswersPaused) "isPaused" else "isMute", "Z"),
                abstractMethod(state, "isPlaying", "Z"),
            ),
        )
        val managerClass = ImmutableClassDef(
            manager,
            if (managerIsInterface) interfaceFlags else AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;", listOf(state), null, null, null,
            listOf(abstractMethod(manager, "LIZIZ", "V")),
        )
        val controllerClass = ImmutableClassDef(
            controller, AccessFlags.PUBLIC.value, "Lcom/ss/android/ugc/aweme/feed/controller/BaseController;",
            null, null, null,
            listOf(ImmutableField(controller, managerField, manager, AccessFlags.PUBLIC.value, null, null, null)),
            null,
        )
        return listOf(stateClass, managerClass, controllerClass).associateBy { it.type }
    }

    private fun assertRefused(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal naming \"$expected\"")
        } catch (refused: Exception) {
            assertTrue(
                "refused with ${refused::class.simpleName}: ${refused.message}",
                refused is PatchException || refused is IllegalStateException,
            )
            assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains(expected))
        }
    }
}
