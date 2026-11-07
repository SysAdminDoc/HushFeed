/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val AVATAR_VIEW = "Lcom/ss/android/ugc/aweme/avatar/AvatarComponentView;"
private const val BUSINESS = "LX/09t2;"
private const val CHAT_ROW_VH = "Lcom/ss/android/ugc/aweme/im/chatlist/impl/ui/viewholder/chatlistvo/NewChatListSingleChatVH;"

/**
 * What the Story first switch (#233) hooks, held to each declared build: exactly one tap on the
 * profile picture that checks the picture's map for LIVE, with the ask to StoryOverLive right in
 * front of that check and TikTok's own check left behind it for a no. Likewise exactly one tap on
 * an Inbox chat's picture, asked at the top of the branch its LIVE room id opens.
 */
class StoryOverLiveAnchorsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `each declared build has one profile picture tap that checks for LIVE first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (ProfileAvatarClickFingerprint.takes(method, classDef)) taken += method
                    }
                }
            }
            assertEquals("$version: profile picture taps ${taken.map { it.definingClass + "->" + it.name }}", 1, taken.size)
        }
    }

    @Test
    fun `the hook assembles on each declared build and asks ahead of TikToks LIVE check`() {
        val hookOpcodes = listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = bytecodePatch(name = "story over live probe") {
                execute {
                    val method = ProfileAvatarClickFingerprint.method
                    val site = storyOverLiveSite(method)!!
                    val stock = method.implementation!!.instructions.toList()
                    val load = stock[site.index]
                    assertEquals("$version: the hook sits on the LIVE key", "LIVE", load.getReference<FieldReference>()!!.name)
                    assertTrue(
                        "$version: something in TikTok's code jumps onto the LIVE check, past the hook",
                        stock.none { (it as? BuilderOffsetInstruction)?.target?.location?.instruction === load },
                    )
                    val kept = stock.take(site.index).count { it.opcode != Opcode.NOP }
                    val before = stock.filter { it.opcode != Opcode.NOP }.map { it.opcode }
                    val stockProblems = BridgeTypeFlow.check(method).map { it.message }

                    assertTrue("$version: the hook went in", hookStoryOverLive(version))

                    val after = method.implementation!!.instructions.toList()
                    val at = after.indexOf(load)
                    val hook = after.subList(at - hookOpcodes.size, at)
                    assertEquals("$version: the ask", hookOpcodes, hook.map { it.opcode })
                    assertEquals("$version: the tapped view goes in", site.view, (hook[0] as TwoRegisterInstruction).registerB)
                    val ask = hook[1].getReference<MethodReference>()!!
                    assertEquals(
                        "Lapp/morphe/extension/tiktok/feed/StoryOverLive;->openStory(Ljava/util/Map;Landroid/view/View;)Z",
                        "${ask.definingClass}->${ask.name}(${ask.parameterTypes.joinToString("")})${ask.returnType}",
                    )
                    val call = hook[1] as FiveRegisterInstruction
                    assertEquals("$version: with the picture's map", site.map, call.registerC)
                    assertEquals("$version: and the view", site.scratch, call.registerD)
                    assertSame("$version: a no runs TikTok's LIVE check", load, (hook[3] as BuilderOffsetInstruction).target.location.instruction)
                    assertEquals(
                        "$version: TikTok's own code around the hook",
                        before.take(kept) + hookOpcodes + before.drop(kept),
                        after.filter { it.opcode != Opcode.NOP }.map { it.opcode },
                    )
                    assertEquals("$version: the hook types as TikTok's code did", stockProblems, BridgeTypeFlow.check(method).map { it.message })
                    checked = true
                }
            }
            run(apk, probe)
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `each declared build has one Inbox picture tap that opens the LIVE first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (InboxAvatarClickFingerprint.takes(method, classDef)) taken += method
                    }
                }
            }
            assertEquals("$version: Inbox picture taps ${taken.map { it.definingClass + "->" + it.name }}", 1, taken.size)
        }
    }

    @Test
    fun `the Inbox hook assembles on each declared build and asks at the top of the LIVE branch`() {
        val hookOpcodes = listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = bytecodePatch(name = "inbox story over live probe") {
                execute {
                    val method = InboxAvatarClickFingerprint.method
                    val site = inboxStorySite(method)!!
                    val stock = method.implementation!!.instructions.toList()
                    val load = stock[site.index]
                    assertEquals("$version: the hook sits on the picture's load", site.picture, load.getReference<FieldReference>())
                    assertEquals("$version: right after the LIVE room id check", Opcode.IF_EQZ, stock[site.index - 1].opcode)
                    assertTrue(
                        "$version: something in TikTok's code jumps into the LIVE branch, past the hook",
                        stock.none { (it as? BuilderOffsetInstruction)?.target?.location?.instruction === load },
                    )
                    val kept = stock.take(site.index).count { it.opcode != Opcode.NOP }
                    val before = stock.filter { it.opcode != Opcode.NOP }.map { it.opcode }
                    val stockProblems = BridgeTypeFlow.check(method).map { it.message }

                    assertTrue("$version: the hooks went in", hookStoryOverLive(version))

                    val after = method.implementation!!.instructions.toList()
                    val at = after.indexOf(load)
                    val hook = after.subList(at - hookOpcodes.size, at)
                    assertEquals("$version: the ask", hookOpcodes, hook.map { it.opcode })
                    assertEquals("$version: the picture goes in", site.picture, hook[0].getReference<FieldReference>())
                    assertEquals("$version: off the listener", site.listener, (hook[0] as TwoRegisterInstruction).registerB)
                    assertEquals("$version: into the load's own register", site.register, (hook[0] as TwoRegisterInstruction).registerA)
                    val ask = hook[1].getReference<MethodReference>()!!
                    assertEquals(
                        "Lapp/morphe/extension/tiktok/feed/StoryOverLive;->openChatStory(Landroid/view/View;)Z",
                        "${ask.definingClass}->${ask.name}(${ask.parameterTypes.joinToString("")})${ask.returnType}",
                    )
                    assertSame("$version: a no runs the LIVE branch", load, (hook[3] as BuilderOffsetInstruction).target.location.instruction)
                    assertEquals(
                        "$version: TikTok's own code around the hook",
                        before.take(kept) + hookOpcodes + before.drop(kept),
                        after.filter { it.opcode != Opcode.NOP }.map { it.opcode },
                    )
                    assertEquals("$version: the hook types as TikTok's code did", stockProblems, BridgeTypeFlow.check(method).map { it.message })
                    checked = true
                }
            }
            run(apk, probe)
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `only an instance tap whose LIVE branch starts with its own picture is taken`() {
        val site = inboxStorySite(inboxTap())!!
        assertEquals("the picture's load", 3, site.index)
        assertEquals("the load's register", 3, site.register)
        assertEquals("the listener, this", 4, site.listener)
        assertEquals(AVATAR_VIEW, site.picture.type)

        assertNull("a static method", inboxStorySite(inboxTap(static = true)))
        assertNull("a LIVE id off another class", inboxStorySite(inboxTap(liveFrom = "LX/Other;")))
        assertNull("a check that isn't on the id", inboxStorySite(inboxTap(checked = 1)))
        assertNull("a branch that loads something else", inboxStorySite(inboxTap(pictureType = "Landroid/view/View;")))
        assertNull("a picture off another object", inboxStorySite(inboxTap(pictureFrom = 0)))
    }

    private fun run(apk: File, probe: BytecodePatch) {
        ProfileAvatarClickFingerprint.clearMatch()
        InboxAvatarClickFingerprint.clearMatch()
        Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
            patcher += setOf(probe)
            runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
        }
        ProfileAvatarClickFingerprint.clearMatch()
        InboxAvatarClickFingerprint.clearMatch()
    }

    /**
     * The Inbox tap cut down to its LIVE branch's start: the row off this, its LIVE room id, the
     * `if-eqz` on it, then the listener's picture. Four locals and two parameters, this in v4.
     */
    private fun inboxTap(
        static: Boolean = false,
        liveFrom: String = CHAT_ROW_VH,
        checked: Int = 0,
        pictureType: String = AVATAR_VIEW,
        pictureFrom: Int = 4,
    ): Method {
        val code = listOf<Instruction>(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 4, ImmutableFieldReference("LX/Tap;", "LL", CHAT_ROW_VH)),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, ImmutableFieldReference(liveFrom, "LLLZZ", "Ljava/lang/Long;")),
            ImmutableInstruction21t(Opcode.IF_EQZ, checked, 3),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, pictureFrom, ImmutableFieldReference("LX/Tap;", "LLJJI", pictureType)),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        return ImmutableMethod(
            "LX/Tap;", "onClick", listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)), "V",
            flags, null, null,
            ImmutableMethodImplementation(6, code, null, null),
        )
    }

    @Test
    fun `only a static tap that checks the pictures map for LIVE with the view untouched is taken`() {
        val site = storyOverLiveSite(tap())!!
        assertEquals("the LIVE key's load", 2, site.index)
        assertEquals("the map", 5, site.map)
        assertEquals("the load's own register", 0, site.scratch)
        assertEquals("the view, the last parameter", 7, site.view)

        assertNull("an instance method", storyOverLiveSite(tap(static = false)))
        assertNull("no view last", storyOverLiveSite(tap(lastParameter = "Ljava/lang/Object;")))
        assertNull("a map from elsewhere", storyOverLiveSite(tap(mapFrom = "getBusinessCache")))
        assertNull("the STORY check", storyOverLiveSite(tap(key = "STORY")))
        assertNull("the view written first", storyOverLiveSite(tap(viewWritten = true)))
    }

    /**
     * The tap cut down to its LIVE check: the picture's map, then `map.containsKey(LIVE)`. Six
     * locals and two parameters, the listener in v6 and the view in v7.
     */
    private fun tap(
        static: Boolean = true,
        lastParameter: String = "Landroid/view/View;",
        mapFrom: String = "getCurrentActiveBusiness",
        key: String = "LIVE",
        viewWritten: Boolean = false,
    ): Method {
        val code = mutableListOf<Instruction>(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0,
                ImmutableMethodReference(AVATAR_VIEW, mapFrom, listOf(), "Ljava/util/Map;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(BUSINESS, key, BUSINESS)),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 5, 0, 0, 0, 0,
                ImmutableMethodReference("Ljava/util/Map;", "containsKey", listOf("Ljava/lang/Object;"), "Z")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        if (viewWritten) code.add(0, ImmutableInstruction11n(Opcode.CONST_4, 7, 0))
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        return ImmutableMethod(
            "LY/ACListener;", "onClick\$124",
            listOf("LY/ACListener;", lastParameter).map { ImmutableMethodParameter(it, null, null) }, "V",
            flags, null, null,
            ImmutableMethodImplementation(8, code, null, null),
        )
    }
}
