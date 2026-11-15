/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.pip

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.live.LIVE_PLAY_FRAGMENT
import app.morphe.takes
import app.morphe.util.getReference
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private const val EXTENSION = "Lapp/morphe/extension/tiktok/playback/PictureInPicture;"
private const val COMPONENT_ACTIVITY = "Landroidx/activity/ComponentActivity;"
private const val ANDROID_NAME = 0x01010003
private const val ANDROID_CONFIG_CHANGES = 0x0101001f

/** screenLayout, screenSize and smallestScreenSize: what shrinking into the window changes. */
private const val WINDOW_SIZE_CHANGES = 0x100 or 0x400 or 0x800

/**
 * Picture-in-picture hooks the two windows a feed video plays in: leaving (onUserLeaveHint) and
 * the window opening or closing (onPictureInPictureModeChanged). Both are inherited, so the patch
 * walks each window's superclass chain to the declaration Android runs, hooks it once however
 * many windows share it, and lets the detail pager open the window in the manifest.
 */
class PictureInPictureAnchorsTest {
    @Test
    fun `each declared build hands leaving and the window change over first`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            val hooks = pictureInPictureHooks { classes[it] }
            PICTURE_IN_PICTURE_WINDOWS.forEach { window ->
                val chain = generateSequence(window) { classes[it]?.superclass }.toList()
                assertTrue("${apk.name}: no leave hook above $window: $chain",
                    hooks.leaveHints.any { it.definingClass in chain })
                assertTrue("${apk.name}: no window change hook above $window: $chain",
                    hooks.modeChanges.any { it.definingClass in chain })
            }
            // Read off all three builds: the windows share TikTok's renamed base activity's
            // onUserLeaveHint, and nothing below androidx overrides the window change.
            assertEquals("${apk.name}: leave hooks ${hooks.leaveHints.map { it.definingClass }}", 1, hooks.leaveHints.size)
            assertEquals("${apk.name}", listOf(COMPONENT_ACTIVITY), hooks.modeChanges.map { it.definingClass })

            hooks.leaveHints.forEach { method ->
                val mutable = MutableMethod(method)
                val body = mutable.implementation!!.instructions.map { it.opcode }
                mutable.handLeaveHintToPictureInPicture()
                assertHandsOverFirst(mutable, body, "onUserLeaveHint", listOf("Landroid/app/Activity;"), parameterRegisters = 1)
            }
            hooks.modeChanges.forEach { method ->
                val mutable = MutableMethod(method)
                val body = mutable.implementation!!.instructions.map { it.opcode }
                mutable.handWindowChangeToPictureInPicture()
                assertHandsOverFirst(mutable, body, "onModeChanged", listOf("Landroid/app/Activity;", "Z"), parameterRegisters = 3)
            }
        }
    }

    @Test
    fun `each declared build declares both windows and lets them change size without a rebuild`() {
        Fixtures.forEachDeclared { apk ->
            val activities = activitiesOf(apk)
            PICTURE_IN_PICTURE_WINDOWS.map { it.removePrefix("L").removeSuffix(";").replace('/', '.') }.forEach { name ->
                val configChanges = activities[name]
                assertTrue("${apk.name}: $name isn't in the manifest", configChanges != null)
                assertEquals("${apk.name}: $name rebuilds when its window shrinks (configChanges ${Integer.toHexString(configChanges!!)})",
                    WINDOW_SIZE_CHANGES, configChanges and WINDOW_SIZE_CHANGES)
            }
        }
    }

    @Test
    fun `the walk takes the nearest instance declaration with code`() {
        val classes = mapOf(
            classOf("LX/Feed;", "LX/Static;"),
            classOf("LX/Static;", "LX/Abstract;", leaveHint(static = true)),
            classOf("LX/Abstract;", "LX/Base;", leaveHint(implemented = false)),
            classOf("LX/Base;", "Landroid/app/Activity;", leaveHint()),
        )
        assertEquals("LX/Base;", firstDeclaration("LX/Feed;", { classes[it] }, Method::isUserLeaveHint)?.definingClass)

        assertNull(firstDeclaration("LX/Feed;", { classes[it] }, Method::isPictureInPictureModeChange))
        val looped = mapOf(classOf("LX/A;", "LX/B;"), classOf("LX/B;", "LX/A;"))
        assertNull(firstDeclaration("LX/A;", { looped[it] }, Method::isUserLeaveHint))
    }

    @Test
    fun `a declaration both windows share is hooked once, and a window without one stops the patch`() {
        val (feed, detail) = PICTURE_IN_PICTURE_WINDOWS
        val shared = mapOf(
            classOf(feed, "LX/Middle;"),
            classOf("LX/Middle;", "LX/Base;"),
            classOf(detail, "LX/Base;"),
            classOf("LX/Base;", COMPONENT_ACTIVITY, leaveHint()),
            classOf(COMPONENT_ACTIVITY, "Landroid/app/Activity;", windowChange()),
        )
        val hooks = pictureInPictureHooks { shared[it] }
        assertEquals(listOf("LX/Base;"), hooks.leaveHints.map { it.definingClass })
        assertEquals(listOf(COMPONENT_ACTIVITY), hooks.modeChanges.map { it.definingClass })

        val detailOnItsOwn = shared + classOf(detail, "Landroid/app/Activity;")
        assertThrows(PatchException::class.java) { pictureInPictureHooks { detailOnItsOwn[it] } }
    }

    @Test
    fun `the manifest lets both windows open picture-in-picture and leaves the rest alone`() {
        val xml = manifest(
            """
            <activity android:name="com.ss.android.ugc.aweme.main.MainActivity" android:supportsPictureInPicture="true"/>
            <activity android:name=".detail.ui.DetailActivity" android:configChanges="0xff0"/>
            <activity android:name="com.ss.android.ugc.aweme.live.LivePlayActivity"/>
            """,
            packageName = "com.ss.android.ugc.aweme",
        )
        allowPictureInPicture(xml)
        val activities = (0 until xml.getElementsByTagName("activity").length).map {
            xml.getElementsByTagName("activity").item(it) as Element
        }
        assertEquals(listOf("true", "true", ""), activities.map { it.getAttribute("android:supportsPictureInPicture") })
        assertEquals("0xff0", activities[1].getAttribute("android:configChanges"))
    }

    @Test
    fun `a manifest without the detail pager stops the patch`() {
        val xml = manifest("""<activity android:name="com.ss.android.ugc.aweme.main.MainActivity"/>""")
        assertThrows(PatchException::class.java) { allowPictureInPicture(xml) }
    }

    @Test
    fun `each declared build finds TikTok's LIVE window switch and sets it as a room comes up`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            val switch = liveWindowSwitch { classes[it] }
            assertEquals("${apk.name}: the switch is written through its own setting", switch.field.type, switch.write.definingClass)
            assertTrue("${apk.name}: the room page's check reads another setting",
                switch.fieldSmali in liveWindowCheckReads { classes[it] })

            val resume = classes.getValue(LIVE_PLAY_FRAGMENT).methods.single {
                it.name == "onResume" && it.parameterTypes.isEmpty() && it.implementation != null
            }
            val mutable = MutableMethod(resume)
            val body = mutable.implementation!!.instructions.map { it.opcode }
            mutable.keepLiveWindowSwitch(switch)
            val instructions = mutable.implementation!!.instructions.toList()
            assertEquals("${apk.name}: TikTok's onResume changed", body, instructions.drop(6).map { it.opcode })
            assertEquals(
                listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.SGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.NOP),
                instructions.take(6).map { it.opcode },
            )
            val ask = instructions[0].getReference<MethodReference>()!!
            assertEquals(EXTENSION, ask.definingClass)
            assertEquals("liveWindowSwitch", ask.name)
            assertEquals(switch.write.name, instructions[4].getReference<MethodReference>()!!.name)
            // v0 and v1 are locals, below p0.
            assertTrue("${apk.name}: the write lands on this", mutable.implementation!!.registerCount - 1 >= 2)
        }
    }

    @Test
    fun `a prompt writing a setting the room page doesn't check stops the patch`() {
        val checked = "LX/Prefs;->open:LX/Pref;"
        fun classes(written: String) = mapOf(
            LIVE_WINDOW_PROMPT to ImmutableClassDef(
                LIVE_WINDOW_PROMPT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
                listOf(code(LIVE_WINDOW_PROMPT, "onClick", listOf("Landroid/view/View;"), "V", false,
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("select_type")),
                    ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, ImmutableFieldReference("LX/Prefs;", written, "LX/Pref;")),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0,
                        ImmutableMethodReference("LX/Pref;", "set", listOf("Ljava/lang/Object;"), "V")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
            ),
            LIVE_PLAY_FRAGMENT to ImmutableClassDef(
                LIVE_PLAY_FRAGMENT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
                listOf(code(LIVE_PLAY_FRAGMENT, "check", emptyList(), "Ljava/lang/String;", false,
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("encrypted room")),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                        ImmutableMethodReference("LX/Gate;", "open", emptyList(), "Z")),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(LIVE_WINDOW_OFF_REASON)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
            ),
            "LX/Gate;" to ImmutableClassDef(
                "LX/Gate;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
                listOf(code("LX/Gate;", "open", emptyList(), "Z", true,
                    ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference("LX/Prefs;", "open", "LX/Pref;")),
                    ImmutableInstruction11x(Opcode.RETURN, 0))),
            ),
        )
        val right = classes("open")
        assertEquals(checked, liveWindowSwitch { right[it] }.fieldSmali)
        val wrong = classes("audio")
        assertThrows(PatchException::class.java) { liveWindowSwitch { wrong[it] } }
        assertThrows(PatchException::class.java) { liveWindowSwitch { if (it == LIVE_WINDOW_PROMPT) null else right[it] } }
    }

    @Test
    fun `each declared build hands the LIVE window's tablet answer to the extension`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            val check = liveWindowLargeScreenCheck { classes[it] }
            val stock = check.method.implementation!!.instructions.toList()
            assertEquals("${apk.name}: the answer comes from TikTok's tablet service",
                HOST_PAD, stock[check.result - 1].getReference<MethodReference>()!!.definingClass)
            val mutable = MutableMethod(check.method)
            mutable.keepLiveWindowOnLargeScreens(check.result)
            val patched = mutable.implementation!!.instructions.toList()
            val answer = (stock[check.result] as OneRegisterInstruction).registerA
            val hand = patched[check.result + 1]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hand.opcode)
            assertEquals(answer, (hand as RegisterRangeInstruction).startRegister)
            assertEquals(1, hand.registerCount)
            val ask = hand.getReference<MethodReference>()!!
            assertEquals(EXTENSION, ask.definingClass)
            assertEquals("liveWindowLargeScreen", ask.name)
            assertEquals(Opcode.MOVE_RESULT, patched[check.result + 2].opcode)
            assertEquals(answer, (patched[check.result + 2] as OneRegisterInstruction).registerA)
            assertEquals("${apk.name}: TikTok's device check changed",
                stock.map { it.opcode }.filter { it != Opcode.NOP },
                (patched.take(check.result + 1) + patched.drop(check.result + 3)).map { it.opcode }.filter { it != Opcode.NOP })
        }
    }

    @Test
    fun `a device check that doesn't ask the tablet service once, or drops the answer, stops the patch`() {
        val ask = ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference(HOST_PAD, "isPad", emptyList(), "Z"))
        val keep = ImmutableInstruction11x(Opcode.MOVE_RESULT, 0)
        val right = largeScreenClasses(ask, keep)
        val found = liveWindowLargeScreenCheck { right[it] }
        assertEquals("LX/Device;", found.method.definingClass)
        assertEquals(1, found.result)

        val none = largeScreenClasses(ImmutableInstruction11x(Opcode.MOVE_RESULT, 0))
        assertThrows(PatchException::class.java) { liveWindowLargeScreenCheck { none[it] } }
        val twice = largeScreenClasses(ask, keep, ask, keep)
        assertThrows(PatchException::class.java) { liveWindowLargeScreenCheck { twice[it] } }
        val dropped = largeScreenClasses(ask, ImmutableInstruction10x(Opcode.NOP))
        assertThrows(PatchException::class.java) { liveWindowLargeScreenCheck { dropped[it] } }
    }

    @Test
    fun `each declared build hands the page-select step's paused answer over on every path that sets it`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            val taken = classes.values.flatMap { classDef ->
                classDef.methods.filter { FeedPageSelectPlayFingerprint.takes(it, classDef) }
            }
            assertEquals("${apk.name}: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val step = taken.single()
            val result = pageSelectPausedResult(step)
            val mutable = MutableMethod(step)
            val stock = mutable.implementation!!.instructions.toList()
            val answer = (stock[result] as OneRegisterInstruction).registerA
            // TikTok's other two answers (yes and no) jump straight past the call.
            val jumps = stock.filterIsInstance<BuilderOffsetInstruction>().filter { it.target.location.index == result + 1 }
            assertTrue("${apk.name}: ${jumps.size} paths jump past the paused call", jumps.size >= 2)
            val stockOpcodes = stock.map { it.opcode }

            mutable.keepPageSelectPlayingInWindow(result)
            val patched = mutable.implementation!!.instructions.toList()
            val hand = patched[result + 1]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hand.opcode)
            assertEquals(answer, (hand as RegisterRangeInstruction).startRegister)
            assertEquals(1, hand.registerCount)
            val ask = hand.getReference<MethodReference>()!!
            assertEquals(EXTENSION, ask.definingClass)
            assertEquals("pageSelectPaused", ask.name)
            assertEquals(listOf("I"), ask.parameterTypes.map(CharSequence::toString))
            assertEquals(Opcode.MOVE_RESULT, patched[result + 2].opcode)
            assertEquals(answer, (patched[result + 2] as OneRegisterInstruction).registerA)
            jumps.forEach { assertEquals("${apk.name}: a path skips the hook", result + 1, it.target.location.index) }
            assertEquals("${apk.name}: TikTok's page-select step changed",
                stockOpcodes.filter { it != Opcode.NOP },
                (patched.take(result + 1) + patched.drop(result + 3)).map { it.opcode }.filter { it != Opcode.NOP })
        }
    }

    @Test
    fun `a page-select step that doesn't ask isPaused once, or drops the answer, stops the patch`() {
        val ask = ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("LX/Page;", "isPaused", emptyList(), "Z"))
        val keep = ImmutableInstruction11x(Opcode.MOVE_RESULT, 1)
        val other = ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("LX/Page;", "isPaused", emptyList(), "V"))
        fun step(vararg body: Instruction) =
            code("LX/Step;", "LJ", listOf("I", "Z"), "V", false, *body, ImmutableInstruction10x(Opcode.RETURN_VOID))

        assertEquals(2, pageSelectPausedResult(step(other, ask, keep)))
        assertThrows(PatchException::class.java) { pageSelectPausedResult(step(other, keep)) }
        assertThrows(PatchException::class.java) { pageSelectPausedResult(step(ask, keep, ask, keep)) }
        assertThrows(PatchException::class.java) { pageSelectPausedResult(step(ask, ImmutableInstruction10x(Opcode.NOP))) }
    }

    /** A room page whose window check's gate asks LX/Device; first, with [device] before its return. */
    private fun largeScreenClasses(vararg device: Instruction): Map<String, ClassDef> = mapOf(
        LIVE_PLAY_FRAGMENT to ImmutableClassDef(
            LIVE_PLAY_FRAGMENT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
            listOf(code(LIVE_PLAY_FRAGMENT, "check", emptyList(), "Ljava/lang/String;", false,
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("encrypted room")),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                    ImmutableMethodReference("LX/Gate;", "open", emptyList(), "Z")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(LIVE_WINDOW_OFF_REASON)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
        ),
        "LX/Gate;" to ImmutableClassDef(
            "LX/Gate;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
            listOf(code("LX/Gate;", "open", emptyList(), "Z", true,
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                    ImmutableMethodReference("LX/Device;", "fits", emptyList(), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0))),
        ),
        "LX/Device;" to ImmutableClassDef(
            "LX/Device;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
            listOf(code("LX/Device;", "fits", emptyList(), "Z", true,
                *device, ImmutableInstruction11x(Opcode.RETURN, 0))),
        ),
    )

    private fun code(
        owner: String,
        name: String,
        parameters: List<String>,
        returnType: String,
        static: Boolean,
        vararg instructions: Instruction,
    ): Method = ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
        ImmutableMethodImplementation(4, instructions.toList(), null, null),
    )

    /** The call is a prefix: TikTok's own handler, super call and listeners included, runs after it. */
    private fun assertHandsOverFirst(
        method: MutableMethod,
        originalBody: List<Opcode>,
        name: String,
        parameters: List<String>,
        parameterRegisters: Int,
    ) {
        val instructions = method.implementation!!.instructions.toList()
        assertEquals("the hook changed TikTok's own handler", originalBody, instructions.drop(1).map { it.opcode })
        val first = instructions.first()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, first.opcode)
        val range = first as RegisterRangeInstruction
        assertEquals("the call takes the wrong registers", parameters.size, range.registerCount)
        assertEquals("the call does not start at p0",
            method.implementation!!.registerCount - parameterRegisters, range.startRegister)
        val target = first.getReference<MethodReference>()!!
        assertEquals(EXTENSION, target.definingClass)
        assertEquals(name, target.name)
        assertEquals(parameters, target.parameterTypes.map(CharSequence::toString))
    }

    private fun classesOf(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }

    /** Each declared activity's name and configChanges flags. */
    private fun activitiesOf(apk: File): Map<String, Int> {
        val bytes = ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        val found = mutableMapOf<String, Int>()
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT || parser.name != "activity") continue
            var name: String? = null
            var configChanges = 0
            for (i in 0 until parser.attributeCount) {
                when (parser.getAttributeNameResourceId(i)) {
                    ANDROID_NAME -> name = parser.getAttributeStringValue(i)
                    ANDROID_CONFIG_CHANGES -> configChanges = parser.getAttributeIntValue(i)
                }
            }
            if (name != null) found[name] = configChanges
        }
        return found
    }

    private fun manifest(entries: String, packageName: String = "com.zhiliaoapp.musically"): Document {
        val xml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
                <application>
                    $entries
                </application>
            </manifest>
        """.trimIndent()
        // Not namespace aware, the way the patcher's document reads a decoded manifest.
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun classOf(type: String, superclass: String, vararg methods: (String) -> Method): Pair<String, ClassDef> =
        type to ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, null, null, null, emptyList(),
            methods.map { it(type) },
        )

    private fun leaveHint(static: Boolean = false, implemented: Boolean = true): (String) -> Method =
        method("onUserLeaveHint", emptyList(), static, implemented)

    private fun windowChange(): (String) -> Method =
        method("onPictureInPictureModeChanged", listOf("Z", "Landroid/content/res/Configuration;"), static = false, implemented = true)

    private fun method(name: String, parameters: List<String>, static: Boolean, implemented: Boolean): (String) -> Method = { owner ->
        ImmutableMethod(
            owner, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or
                (if (implemented) 0 else AccessFlags.ABSTRACT.value),
            null, null,
            if (implemented) {
                ImmutableMethodImplementation(parameters.size + 1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
            } else {
                null
            },
        )
    }
}
