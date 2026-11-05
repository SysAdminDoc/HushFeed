package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #159: the provider answering dark only reached the panels' navigation bar, because TikTok adds
 * the dark LIVE theme to the room's theme without force. The panels' inflaters now go through
 * the extension, which wraps them in the panel's own overlay with force. These hold the anchors
 * on every declared build: both themed bases keep their inflater and their getThemeOverlay
 * (which the extension calls by name), every return takes the four-bit hook, LIVE's sheet page
 * still extends the fragment base (the extension finds sheet pages by that name), and the older
 * quality list keeps its onViewCreated for the readable-rows hook. TikTok's generic sheets over a
 * room (Share, Contact) inflate through the room's own theme, so the room's setTheme takes a hook
 * after its last room theme (each one re-applies TikTok's light app theme), and TikTok's dark app
 * theme is read from its own dark mode code. The profile card's bio link is drawn in the bio
 * view's own text color, so the link color its call resolves goes to the extension with the view,
 * which has to be a TextView. The card's labels come with the server's light card colors, so the
 * label list and the strip drawing it go to the extension right before the card walks them, and
 * the label model keeps the fields the extension sets by name. The card's upcoming event box is a
 * fixed white shape under text in the card theme's colors, so the event module's bind hands the
 * box to the extension right after reading it, before its null check. The group chat row puts the
 * same kind of shape on itself, so each of its module's setBackgroundResource calls on the row's
 * own field hands the row over right after. The ranking dialogs that take TikTok's light overlay with force
 * instead of the dark one keep their names, and their pages and lists stay fragments: plain ones
 * take the room's inflater, so androidx's fragment inflater takes a hook of its own, and the
 * extension finds them by the public getParentFragment and getContext it calls by name. The
 * ranking's tab strip reads its colors through its page's getContext(), the room, so both tab
 * states hand that context and the page to the extension first, and the team list's bottom bar,
 * the page's reach_top20 chevron and every list's bar tag icon do the same with their own. A
 * list's bottom row tints its info icon through the context the list hands it, so that context
 * and the row's text go to the extension before the row runs. LIVE's list rows skip the list's
 * inflater, so the row binders' base asks the extension for each row before TikTok's code runs.
 * Spark's pages read light or dark from the theme name of the context they open in, so TikTok's
 * Spark theme lookup and its page color lookup hand that context to the extension first.
 */
class LiveDarkPanelsAnchorsTest {
    private fun classes(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    @Test
    fun `each declared build keeps the themed panel inflaters the hook wraps`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            for ((type, fingerprint) in listOf(
                LIVE_THEMED_DIALOG to LiveThemedDialogInflaterFingerprint,
                LIVE_THEMED_FRAGMENT to LiveThemedFragmentInflaterFingerprint,
            )) {
                val base = classes[type] ?: throw AssertionError("${apk.name}: $type is gone")
                val found = base.methods.filter { fingerprint.takes(it, base) }
                assertEquals("${apk.name}: $type inflater", 1, found.size)
                val returns = inflaterReturns(found.single())
                assertTrue("${apk.name}: $type returns no inflater", returns.isNotEmpty())

                val overlay = base.methods.singleOrNull {
                    it.name == "getThemeOverlay" && it.returnType == "I" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
                }
                assertTrue("${apk.name}: $type has no public getThemeOverlay(Context)",
                    overlay != null && AccessFlags.PUBLIC.isSet(overlay.accessFlags))

                // The hook as the patch writes it: each return hands its inflater over first, and
                // the result keeps the verifier's typing.
                val hooked = MutableMethod(found.single())
                hookPanelInflater(hooked)
                val code = hooked.implementation!!.instructions.toList()
                val hookedReturns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
                assertEquals("${apk.name}: $type returns", returns.size, hookedReturns.size)
                for ((index, instruction) in hookedReturns) {
                    val call = (code[index - 2] as? ReferenceInstruction)?.reference as? MethodReference
                    assertEquals("${apk.name}: $type return at $index", "darkLivePanel", call?.name)
                    assertEquals((instruction as OneRegisterInstruction).registerA, (code[index - 1] as OneRegisterInstruction).registerA)
                }
                assertEquals("${apk.name}: $type", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
            }
            assertEquals("${apk.name}: LIVE's sheet page base", LIVE_THEMED_FRAGMENT, classes[LIVE_SHEET_FRAGMENT]?.superclass)
        }
    }

    @Test
    fun `each declared build keeps the room theme and TikTok's dark app theme the room hook uses`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val room = classes[LIVE_PLAY_ACTIVITY] ?: throw AssertionError("${apk.name}: $LIVE_PLAY_ACTIVITY is gone")
            val found = room.methods.filter { LivePlayActivityThemeFingerprint.takes(it, room) }
            assertEquals("${apk.name}: the room's setTheme", 1, found.size)

            // The hook as the patch writes it: right after the last room theme, in the range form,
            // handing over the room itself, on the join's label so a skipped cold start style
            // still reaches it, and before the room's color scheme styles.
            val hooked = MutableMethod(found.single())
            hookRoomTheme(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "darkLiveRoom"
            }
            assertTrue("${apk.name}: no hook in the room's setTheme", hook > 0)
            val call = code[hook]
            assertEquals("${apk.name}: the hook's call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("${apk.name}: the hook hands over the room", found.single().implementation!!.registerCount - 2,
                (call as RegisterRangeInstruction).startRegister)
            val before = code[hook - 1]
            assertEquals("${apk.name}: the hook follows a room theme", "setTheme",
                ((before as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(Opcode.INVOKE_SUPER, before.opcode)
            assertTrue("${apk.name}: branches past the last room theme miss the hook", code[hook + 1].location.labels.isEmpty())
            if ("47.4.3" in apk.name) {
                // style/y2, the cold start style, is the last room theme in the code's flow; the
                // one placed at the method's end (aeq) jumps back above it.
                val y2 = (hook - 1 downTo maxOf(0, hook - 8)).any { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == 0x7f12036b }
                assertTrue("${apk.name}: the hook follows the cold start style", y2)
                assertTrue("${apk.name}: the cold start join", call.location.labels.isNotEmpty())
            }
            val accents = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "applyStyle"
            }
            assertTrue("${apk.name}: a color scheme style lands before the hook", accents < 0 || accents > hook)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // TikTok's dark mode for its screens, found once, with the style it sets.
            val dark = classes.values.flatMap { owner -> owner.methods.filter { AppDarkThemeFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: TikTok's dark mode", 1, dark.size)
            val theme = appDarkTheme(dark.single())!!
            // On 47.4.3 that's style/yb, which aapt2 shows X.060v.LIZ setting (2026-10-18).
            if ("47.4.3" in apk.name) assertEquals("${apk.name}: the dark app theme", 0x7f120375, theme)
        }
    }

    @Test
    fun `each declared build keeps the room's configuration change and AppCompat's theme rework the second room hook follows`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val room = classes[LIVE_PLAY_ACTIVITY] ?: throw AssertionError("${apk.name}: $LIVE_PLAY_ACTIVITY is gone")
            val found = room.methods.filter { LivePlayActivityConfigurationFingerprint.takes(it, room) }
            assertEquals("${apk.name}: the room's onConfigurationChanged", 1, found.size)

            // The hook as the patch writes it: right after the super call, in the range form,
            // handing over the room itself.
            val hooked = MutableMethod(found.single())
            hookRoomConfiguration(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "darkLiveRoom"
            }
            assertTrue("${apk.name}: no hook in the room's onConfigurationChanged", hook > 0)
            val call = code[hook]
            assertEquals("${apk.name}: the hook's call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("${apk.name}: the hook hands over the room", found.single().implementation!!.registerCount - 2,
                (call as RegisterRangeInstruction).startRegister)
            val before = code[hook - 1]
            assertEquals(Opcode.INVOKE_SUPER, before.opcode)
            assertEquals("${apk.name}: the hook follows the super call", "onConfigurationChanged",
                ((before as ReferenceInstruction).reference as MethodReference).name)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // What the hook answers: AppCompat's night mode update calls setTheme with the
            // activity's theme and then applies that theme again with force.
            val rework = classes["Landroidx/appcompat/app/AppCompatDelegateImpl;"]?.methods?.toList().orEmpty().filter { method ->
                val names = method.implementation?.instructions?.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref -> "${ref.definingClass}->${ref.name}" }
                }.orEmpty()
                val update = names.indexOf("Landroid/content/res/Resources;->updateConfiguration")
                val setTheme = names.indexOf("Landroid/content/Context;->setTheme")
                val apply = names.indexOf("Landroid/content/res/Resources\$Theme;->applyStyle")
                update >= 0 && setTheme > update && apply > setTheme
            }
            assertEquals("${apk.name}: AppCompat's theme rework", 1, rework.size)
        }
    }

    @Test
    fun `the configuration hook refuses a room whose onConfigurationChanged skips its super`() {
        val method = MutableMethod(ImmutableMethod(LIVE_PLAY_ACTIVITY, "onConfigurationChanged",
            listOf(ImmutableMethodParameter("Landroid/content/res/Configuration;", null, null)), "V",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(2, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, "return-void")
        }
        assertThrows(PatchException::class.java) { hookRoomConfiguration(method) }
    }

    @Test
    fun `each declared build keeps Spark's page theme decision the spark hook answers first`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val spark = classes[SPARK_CONTEXT] ?: throw AssertionError("${apk.name}: $SPARK_CONTEXT is gone")
            val found = spark.methods.filter { SparkPageThemeFingerprint.takes(it, spark) }
            assertEquals("${apk.name}: Spark's page theme decision", 1, found.size)
            val decision = found.single()
            val theme = classes[decision.returnType] ?: throw AssertionError("${apk.name}: ${decision.returnType} is gone")
            assertTrue("${apk.name}: the decision answers an enum", AccessFlags.ENUM.isSet(theme.accessFlags))
            for (constant in listOf("DARK", "LIGHT")) {
                assertTrue("${apk.name}: $constant", theme.fields.any { it.name == constant && it.type == theme.type })
            }
            // Its answer reaches the page color and the theme name the page's Lynx view reads,
            // called through SparkContext's superclass (X.08Bv on 47.5.3), which declares it too.
            val holders = setOf(SPARK_CONTEXT, spark.superclass)
            val readers = spark.methods.filter { method ->
                method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                        ref.definingClass in holders && ref.name == decision.name && ref.returnType == decision.returnType
                    } == true
                } == true
            }
            assertTrue("${apk.name}: the page color reads it", readers.any { it.returnType == "Ljava/lang/Integer;" })
            assertTrue("${apk.name}: the theme name reads it", readers.any { it.returnType == "Ljava/lang/String;" })

            // The hook as the patch writes it: the page and its context go to the extension
            // first, and a yes answers LIGHT before Spark's own decision runs.
            val hooked = MutableMethod(decision)
            hookSparkPage(hooked, theme)
            val code = hooked.implementation!!.instructions.toList()
            val page = decision.implementation!!.registerCount - 2
            assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
            val ask = (code[0] as ReferenceInstruction).reference as MethodReference
            assertEquals("sparkKeepsStock", ask.name)
            assertEquals(listOf("Ljava/lang/Object;", "Landroid/content/Context;"), ask.parameterTypes.map(CharSequence::toString))
            assertEquals("${apk.name}: the page and its context", page, (code[0] as RegisterRangeInstruction).startRegister)
            assertEquals(2, (code[0] as RegisterRangeInstruction).registerCount)
            assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
            assertEquals(Opcode.IF_EQZ, code[2].opcode)
            assertEquals("${apk.name}: a no runs Spark's own decision", 5, ((code[2] as BuilderOffsetInstruction).target.location.index))
            assertEquals(Opcode.SGET_OBJECT, code[3].opcode)
            val light = (code[3] as ReferenceInstruction).reference as FieldReference
            assertEquals("LIGHT", light.name)
            assertEquals(theme.type, light.definingClass)
            assertEquals(Opcode.RETURN_OBJECT, code[4].opcode)
            assertEquals((code[3] as OneRegisterInstruction).registerA, (code[4] as OneRegisterInstruction).registerA)
            assertEquals("${apk.name}: Spark's own decision follows", decision.implementation!!.instructions.first().opcode, code[5].opcode)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }

    @Test
    fun `LIVE controls hands Spark's page theme decision to the spark hook`() {
        val root = java.io.File("src/main/kotlin").takeIf { it.isDirectory } ?: java.io.File("patches/src/main/kotlin")
        val source = java.io.File(root, "app/morphe/patches/tiktok/interaction/live/LiveControlsPatch.kt").readText()
        val execute = source.substring(source.indexOf("    execute {"))
        assertTrue("the decision isn't found", execute.contains("val sparkPage = SparkPageThemeFingerprint.method\n"))
        assertTrue("the decision isn't hooked", execute.contains("hookSparkPage(sparkPage, classDefBy(sparkPage.returnType))\n"))
        assertTrue("a blanket Spark theme swap is back", "sparkContext" !in source)
    }

    private val sparkTheme = "Lapp/morphe/SparkTheme;"

    private fun decision(
        parameter: String = "Landroid/content/Context;",
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        owner: String = SPARK_CONTEXT,
        registers: Int = 3,
    ) = MutableMethod(ImmutableMethod(owner, "LJIIZILJ", listOf(ImmutableMethodParameter(parameter, null, null)),
        sparkTheme, flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, "const/4 v0, 0x0\nreturn-object v0")
    }

    private fun theme(type: String = sparkTheme, constant: String = "LIGHT") = com.android.tools.smali.dexlib2.immutable.ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value, "Ljava/lang/Enum;", null, null, null,
        listOf(com.android.tools.smali.dexlib2.immutable.ImmutableField(
            type, constant, type, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value,
            null, null, null,
        )),
        null,
    )

    @Test
    fun `the spark hook takes only Spark's instance decision of a context with a LIGHT answer`() {
        hookSparkPage(decision(), theme())
        assertThrows("a String", PatchException::class.java) { hookSparkPage(decision("Ljava/lang/String;"), theme()) }
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        assertThrows("a static one", PatchException::class.java) { hookSparkPage(decision(flags = static), theme()) }
        assertThrows("another class", PatchException::class.java) { hookSparkPage(decision(owner = "Lapp/morphe/Other;"), theme()) }
        assertThrows("no local register", PatchException::class.java) { hookSparkPage(decision(registers = 2), theme()) }
        assertThrows("another answer type", PatchException::class.java) { hookSparkPage(decision(), theme("Lapp/morphe/Other;")) }
        assertThrows("no LIGHT", PatchException::class.java) { hookSparkPage(decision(), theme(constant = "DARK")) }
    }

    @Test
    fun `each declared build keeps the profile card's bio link color the bio hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val header = classes[LIVE_PROFILE_HEADER] ?: throw AssertionError("${apk.name}: $LIVE_PROFILE_HEADER is gone")
            val found = header.methods.filter { LiveProfileBioFingerprint.takes(it, header) }
            assertEquals("${apk.name}: the bio", 1, found.size)
            val link = bioLinkColor(found.single())!!
            if ("47.4.3" in apk.name) {
                // attr/f33, the LIVE link color, is what the call resolves (2026-10-18).
                val attr = found.single().implementation!!.instructions.toList()[link.call - 1]
                assertEquals("${apk.name}: the link's color", 0x7f061ec4, (attr as NarrowLiteralInstruction).narrowLiteral)
            }

            // The hook as the patch writes it: right after the color call's result, the extension
            // gets the link color and the bio view, before the color span takes the color.
            val hooked = MutableMethod(found.single())
            hookBioLink(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val color = (code[link.call] as ReferenceInstruction).reference as MethodReference
            assertEquals("${apk.name}: the color call stays", listOf("I", "Landroid/content/Context;"),
                color.parameterTypes.map(CharSequence::toString))
            assertEquals(Opcode.MOVE_RESULT, code[link.call + 1].opcode)
            assertEquals(link.color, (code[link.call + 1] as OneRegisterInstruction).registerA)
            val hook = code[link.call + 2]
            val call = (hook as ReferenceInstruction).reference as MethodReference
            assertEquals("${apk.name}: the hook's call", "bioLinkColor", call.name)
            assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
            val args = hook as com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
            assertEquals(listOf(link.color, link.view), listOf(args.registerC, args.registerD))
            val span = (code[link.call + 3] as ReferenceInstruction).reference as? MethodReference
            assertEquals("${apk.name}: the color span takes the color next", "Landroid/text/style/ForegroundColorSpan;", span?.definingClass)
            assertEquals("<init>", span?.name)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // The extension only colors a TextView, so the bio view has to be one.
            val suffix = code.drop(link.call).firstNotNullOf {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { ref -> ref.name == "setSuffixText" }
            }
            assertEquals("${apk.name}: the bio view", "Landroid/widget/TextView;", outermost(classes, suffix.definingClass))
        }
    }

    @Test
    fun `each declared build keeps the profile card's labels the labels hook lightens`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val header = classes[LIVE_PROFILE_HEADER] ?: throw AssertionError("${apk.name}: $LIVE_PROFILE_HEADER is gone")
            val found = header.methods.filter { LiveProfileLabelsFingerprint.takes(it, header) }
            assertEquals("${apk.name}: the labels", 1, found.size)
            val labels = cardLabels(found.single())!!
            val original = found.single().implementation!!.instructions.toList()
            val context = (labels.call - 1 downTo 0).first {
                ((original[it] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getContext"
            }
            assertEquals(labels.strip, (original[context] as FiveRegisterInstruction).registerC)
            if ("47.4.3" in apk.name) {
                // The strip is the badge strip id/mts finds, X/0wKt, which the card reads its label
                // manager off just before (2026-10-19).
                val read = (context - 1 downTo 0).first {
                    original[it].opcode == Opcode.IGET_OBJECT && (original[it] as TwoRegisterInstruction).registerB == labels.strip
                }
                assertEquals("${apk.name}: the strip", "LX/11zz;", ((original[read] as ReferenceInstruction).reference as FieldReference).definingClass)
            }

            // The hook as the patch writes it: right before the card walks the labels, the
            // extension gets the label list and the strip.
            val hooked = MutableMethod(found.single())
            hookCardLabels(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code[labels.call]
            assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
            assertEquals("${apk.name}: the hook's call", "cardLabels", ((hook as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(listOf(labels.labels, labels.strip), (hook as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            val iterator = code[labels.call + 1]
            assertEquals("${apk.name}: the iterator() comes next", "iterator", ((iterator as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(labels.labels, (iterator as FiveRegisterInstruction).registerC)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // The extension sets the label's background color by name, and makes the background
            // itself with its no-argument constructor when the server sent none.
            val label = classes[PROFILE_CARD_LABEL] ?: throw AssertionError("${apk.name}: $PROFILE_CARD_LABEL is gone")
            val type = label.fields.single { it.name == "background" }.type
            val background = classes[type] ?: throw AssertionError("${apk.name}: $type is gone")
            assertEquals("${apk.name}: the color", "Ljava/lang/String;", background.fields.single { it.name == "colorCode" }.type)
            assertNotNull("${apk.name}: the image", background.fields.singleOrNull { it.name == "image" })
            assertTrue("${apk.name}: the constructor", background.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() })
        }
    }

    @Test
    fun `each declared build keeps the profile card's event box the event hook fills`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val found = classes.values.flatMap { owner -> owner.methods.filter { LiveProfileEventFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: the event module's bind", 1, found.size)
            val bind = found.single()
            val event = eventBox(bind)!!
            val original = bind.implementation!!.instructions.toList()
            val read = (original[event.at - 1] as ReferenceInstruction).reference as FieldReference
            assertEquals("${apk.name}: the box", "Landroid/view/ViewGroup;", read.type)
            if ("47.4.3" in apk.name) {
                // X/0wEX finds the box as id/hwn, the ConstraintLayout layout/ha3 draws on
                // drawable/e9l, a white shape (2026-10-20).
                assertEquals("${apk.name}: the event module", "LX/0xx1;", bind.definingClass)
                val module = classes.getValue(bind.definingClass)
                val hwn = module.methods.any { method ->
                    method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 0x7f0a2aa9 } == true
                }
                assertTrue("${apk.name}: the module finds id/hwn", hwn)
            }

            // The hook as the patch writes it: right after the box's read and before its null
            // check, the extension gets the box.
            val hooked = MutableMethod(bind)
            hookEventBox(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code[event.at]
            assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
            assertEquals("${apk.name}: the hook's call", "eventBox", ((hook as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(event.box, (hook as FiveRegisterInstruction).registerC)
            assertEquals(Opcode.IGET_OBJECT, code[event.at - 1].opcode)
            assertEquals("${apk.name}: the null check comes next", Opcode.IF_EQZ, code[event.at + 1].opcode)
            assertEquals(event.box, (code[event.at + 1] as OneRegisterInstruction).registerA)
            assertTrue("${apk.name}: a branch skips the hook", code[event.at + 1].location.labels.isEmpty())
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }

    /** An event module's bind reading the host's events, then [box] and what follows it. */
    private fun eventBind(box: String, events: Boolean = true) = MutableMethod(ImmutableMethod("Lapp/morphe/Event;", "LJIIJ",
        listOf(ImmutableMethodParameter(LIVE_USER, null, null), ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
        "Z", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(5, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            ${if (events) "iget-object v0, p1, $LIVE_USER->upcomingEventList:Ljava/util/List;" else "const/4 v0, 0x0"}
            $box
            const/4 v0, 0x1
            return v0
            :skip
            const/4 v0, 0x0
            return v0
        """)
    }

    @Test
    fun `the event hook takes only a box read its null check follows`() {
        val read = "iget-object v1, p0, Lapp/morphe/Event;->box:Landroid/view/ViewGroup;"
        val event = eventBox(eventBind("$read\nif-eqz v1, :skip"))!!
        assertEquals(listOf(2, 1), listOf(event.at, event.box))
        assertNull("no events read", eventBox(eventBind("$read\nif-eqz v1, :skip", events = false)))
        assertNull("no null check", eventBox(eventBind("$read\nif-nez v1, :skip")))
        assertNull("another register checked", eventBox(eventBind("$read\nif-eqz v0, :skip")))
        assertNull("another class's box", eventBox(eventBind(
            "iget-object v1, p0, Lapp/morphe/Other;->box:Landroid/view/ViewGroup;\nif-eqz v1, :skip")))
        assertNull("a branch lands on the check", eventBox(eventBind(
            "if-eqz v0, :check\n$read\n:check\nif-eqz v1, :skip")))
    }

    @Test
    fun `each declared build keeps the profile card's badge tile the badge hook lays over white`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val found = classes.values.flatMap { owner -> owner.methods.filter { LiveProfileBadgeFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: the badge bind", 1, found.size)
            val bind = found.single()
            val calls = badgeTileCalls(bind)!!
            if ("47.7.2" in apk.name) {
                // X/11kh.LJJIIZI(User)Z: its tile is its lazy LLJJL cast to View, given a border
                // around the server's art, a server color, or a border alone.
                assertEquals("${apk.name}: the badge module", "LX/11kh;", bind.definingClass)
                assertEquals("${apk.name}: one call per background", 3, calls.size)
            }

            // The hook as the patch writes it: each call becomes the extension's, on the same
            // two registers, and nothing else in the bind moves.
            val original = bind.implementation!!.instructions.toList()
            val hooked = MutableMethod(bind)
            hookBadgeTiles(hooked)
            val code = hooked.implementation!!.instructions.toList()
            assertEquals("${apk.name}: nothing moves", original.size, code.size)
            calls.forEach { at ->
                val before = original[at] as FiveRegisterInstruction
                val hook = code[at] as FiveRegisterInstruction
                assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
                assertEquals("${apk.name}: the hook's call", "badgeTile", ((hook as ReferenceInstruction).reference as MethodReference).name)
                assertEquals(listOf(2, before.registerC, before.registerD), listOf(hook.registerCount, hook.registerC, hook.registerD))
            }
            val untouched = original.indices.filter { it !in calls }.map { original[it].opcode to code[it].opcode }
            assertTrue("${apk.name}: only the tile's calls change", untouched.all { (was, now) -> was == now })
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }

    @Test
    fun `each declared build hands the room's long-press menu to the extension before the room shows it`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val share = classes[LIVE_HOST_SHARE] ?: throw AssertionError("${apk.name}: no LIVE host share")
            val factory = share.methods.filter { LiveLongPressMenuFingerprint.takes(it, share) }
            assertEquals("${apk.name}: the menu's factory", 1, factory.size)

            // The hook as the patch writes it: each returned menu goes to the extension first,
            // from the return's own register, and nothing else in the factory moves.
            val original = factory.single().implementation!!.instructions.toList()
            val hooked = MutableMethod(factory.single())
            hookLongPressMenu(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
            assertTrue("${apk.name}: a return", returns.isNotEmpty())
            assertEquals("${apk.name}: one call per return", original.size + returns.size, code.size)
            returns.forEach { at ->
                val hook = code[at - 1] as FiveRegisterInstruction
                assertEquals("${apk.name}: the hook before the return", "liveMenu",
                    ((hook as ReferenceInstruction).reference as MethodReference).name)
                assertEquals("${apk.name}: the menu it returns", (code[at] as OneRegisterInstruction).registerA, hook.registerC)
            }
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
        assertThrows(PatchException::class.java) {
            hookLongPressMenu(badgeBind("nop"))
        }
    }

    /** A badge bind that reads its tile from [owner]'s lazy field and casts it to [cast], then runs [after]. */
    private fun badgeBind(
        after: String,
        cast: String = "Landroid/view/View;",
        owner: String = "Lapp/morphe/Badge;",
        before: String = "",
        value: String = "getValue",
    ) = MutableMethod(ImmutableMethod("Lapp/morphe/Badge;", "LJIJI",
        listOf(ImmutableMethodParameter(LIVE_BADGE, null, null)), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(6, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            new-instance v3, Landroid/graphics/drawable/GradientDrawable;
            invoke-direct {v3}, Landroid/graphics/drawable/GradientDrawable;-><init>()V
            $before
            iget-object v0, p0, $owner->tile:Lkotlin/Lazy;
            invoke-interface {v0}, Lkotlin/Lazy;->$value()Ljava/lang/Object;
            move-result-object v1
            check-cast v1, $cast
            iget-object v2, p1, Lcom/bytedance/android/live/base/model/user/CombineBadgeStruct;->background:Lcom/bytedance/android/live/base/model/user/CombineBadgeBackground;
            $after
            return-void
        """)
    }

    @Test
    fun `the badge hook takes only calls the tile alone reaches`() {
        val set = "invoke-virtual {v1, v3}, Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V"
        assertEquals(listOf(7), badgeTileCalls(badgeBind(set)))
        assertEquals("each branch's call", listOf(8, 9), badgeTileCalls(badgeBind("if-eqz v2, :other\n$set\n:other\n$set")))
        assertEquals("a later view in the same register isn't the tile", listOf(7),
            badgeTileCalls(badgeBind("$set\nnew-instance v1, Landroid/view/View;\n$set")))
        assertNull("another view can reach the call", badgeTileCalls(badgeBind(
            "if-eqz v2, :call\nnew-instance v1, Landroid/view/View;\n:call\n$set")))
        assertNull("the method's start can reach the call", badgeTileCalls(badgeBind(
            "goto :end\n:call\n$set\nreturn-void\n:end\ngoto :call", before = "if-eqz p1, :call")))
        assertNull("no call", badgeTileCalls(badgeBind("nop")))
        assertNull("a range call", badgeTileCalls(badgeBind(
            "invoke-virtual/range {v1 .. v2}, Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V")))
        assertNull("not a view", badgeTileCalls(badgeBind(set, cast = "Landroid/widget/TextView;")))
        assertNull("another class's field", badgeTileCalls(badgeBind(set, owner = "Lapp/morphe/Other;")))
        assertNull("not a lazy's value", badgeTileCalls(badgeBind(set, value = "get")))
        assertNull("a plain call beside a range one", badgeTileCalls(badgeBind(
            "$set\ninvoke-virtual/range {v1 .. v2}, Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V")))
        assertNull("no badge background read", badgeTileCalls(badgeBind(set).apply {
            replaceInstruction(6, "const/4 v2, 0x0")
        }))

        val hooked = badgeBind(set)
        hookBadgeTiles(hooked)
        val hook = hooked.implementation!!.instructions.toList()[7] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
        assertEquals(listOf(1, 3), listOf(hook.registerC, hook.registerD))
        assertThrows(PatchException::class.java) { hookBadgeTiles(badgeBind("nop")) }
    }

    @Test
    fun `each declared build keeps the profile card's group chat row the event box fill reaches`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val found = classes.values.flatMap { owner -> owner.methods.filter { LiveProfileGroupChatFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: the group chat bind", 1, found.size)
            val rows = classes.getValue(found.single().definingClass).methods.map { MutableMethod(it) }
            val calls = rows.associateWith { groupChatBackgrounds(it) }.filterValues { it.isNotEmpty() }
            if ("47.4.3" in apk.name) {
                // X/0wJT puts drawable/e9l on its row in LJIJ and drawable/e9i in the bind's
                // branch for a host with no group yet (2026-10-20).
                assertEquals("${apk.name}: the group chat module", "LX/0fHj;", found.single().definingClass)
                assertEquals("${apk.name}: one call each", mapOf("LJIIJ" to listOf(54), "LJIJ" to listOf(3)),
                    calls.mapKeys { it.key.name }.toSortedMap())
            }

            // The hook as the patch writes it: the extension's call right after each of TikTok's,
            // on the same receiver, and nothing else in the class changes.
            val originals = rows.associateWith { it.implementation?.instructions?.toList().orEmpty() }
            hookGroupChat(rows)
            rows.forEach { method ->
                val original = originals.getValue(method)
                val at = calls[method].orEmpty()
                val code = method.implementation?.instructions?.toList().orEmpty()
                assertEquals("${apk.name}: ${method.name} grows by its hooks", original.size + at.size, code.size)
                at.forEachIndexed { done, call ->
                    val hook = code[call + done + 1] as FiveRegisterInstruction
                    assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
                    assertEquals("${apk.name}: the hook's call", "eventBox", ((hook as ReferenceInstruction).reference as MethodReference).name)
                    assertEquals(listOf(1, (original[call] as FiveRegisterInstruction).registerC), listOf(hook.registerCount, hook.registerC))
                }
                if (at.isNotEmpty()) assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
            }
        }
    }

    /** A group chat row method that reads its row from [owner]'s field, then runs [after]. */
    private fun groupChatRow(after: String, owner: String = "Lapp/morphe/Group;", type: String = "Landroid/view/ViewGroup;") =
        MutableMethod(ImmutableMethod("Lapp/morphe/Group;", "LJIJ", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null, null, ImmutableMethodImplementation(4, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, """
                iget-object v1, p0, $owner->row:$type
                if-eqz v1, :end
                const v0, 0x7f0418aa
                $after
                :end
                return-void
            """)
        }

    @Test
    fun `the group chat hook takes only calls the row alone reaches`() {
        val set = "invoke-virtual {v1, v0}, Landroid/view/View;->setBackgroundResource(I)V"
        assertEquals(listOf(3), groupChatBackgrounds(groupChatRow(set)))
        assertEquals("a later view in the same register isn't the row", listOf(3),
            groupChatBackgrounds(groupChatRow("$set\nnew-instance v1, Landroid/view/View;\n$set")))
        assertEquals("another view can reach the call", emptyList<Int>(), groupChatBackgrounds(groupChatRow(
            "if-eqz v0, :call\nnew-instance v1, Landroid/view/View;\n:call\n$set")))
        assertEquals("another class's field", emptyList<Int>(), groupChatBackgrounds(groupChatRow(set, owner = "Lapp/morphe/Other;")))
        assertEquals("not a ViewGroup field", emptyList<Int>(), groupChatBackgrounds(groupChatRow(set, type = "Landroid/view/View;")))
        assertEquals("another call", emptyList<Int>(), groupChatBackgrounds(groupChatRow(
            "invoke-virtual {v1, v0}, Landroid/view/View;->setBackgroundColor(I)V")))
        assertEquals("a range call", emptyList<Int>(), groupChatBackgrounds(groupChatRow(
            "invoke-virtual/range {v0 .. v1}, Landroid/view/View;->setBackgroundResource(I)V")))

        val hooked = groupChatRow(set)
        hookGroupChat(listOf(hooked))
        val code = hooked.implementation!!.instructions.toList()
        val hook = code[4] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
        assertEquals(listOf(1, 1), listOf(hook.registerCount, hook.registerC))
        assertEquals("the null check still skips both", 5, (code[1] as BuilderOffsetInstruction).target.location.index)
        assertEquals(Opcode.RETURN_VOID, code[5].opcode)
        assertThrows(PatchException::class.java) { hookGroupChat(listOf(groupChatRow("nop"))) }
    }

    @Test
    fun `each declared build keeps the ranking dialogs the extension leaves stock`() {
        // LivePanelTheme.STOCK_PANELS names these: they paint fixed light backgrounds themselves,
        // so they take TikTok's light overlay with force instead of the dark one. A rename would
        // put them back under the dark one unnoticed.
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            for (type in listOf(
                "Lcom/bytedance/android/live/rank/impl/list/RankListDialog;",
                "Lcom/bytedance/android/live/rank/impl/list/view/PopularityRankingEducationDialog;",
            )) {
                assertTrue("${apk.name}: $type is gone", type in classes)
                val chain = generateSequence(type) { classes[it]?.superclass }.toList()
                assertTrue("${apk.name}: $type isn't a themed LIVE dialog", LIVE_THEMED_DIALOG in chain)
            }
            // Its pages and their lists are found by their parent fragments; if they stopped being
            // fragments, neither inflater hook would see them and the white titles would be back.
            if ("47.4.3" in apk.name) {
                for (type in listOf(
                    "Lcom/bytedance/android/live/rank/impl/list/fragment/page/RankPageFragment;",
                    "Lcom/bytedance/android/live/rank/impl/list/fragment/list/GiftListFragment;",
                )) {
                    assertTrue("${apk.name}: $type is gone", type in classes)
                    val chain = generateSequence(type) { classes[it]?.superclass }.toList()
                    assertTrue("${apk.name}: $type isn't a fragment", ANDROIDX_FRAGMENT in chain)
                }
            }
        }
    }

    @Test
    fun `each declared build keeps the fragment inflater the page hook wraps`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val fragment = classes[ANDROIDX_FRAGMENT] ?: throw AssertionError("${apk.name}: $ANDROIDX_FRAGMENT is gone")
            val found = fragment.methods.filter { FragmentInflaterFingerprint.takes(it, fragment) }
            assertEquals("${apk.name}: the fragment inflater", 1, found.size)

            // The hook as the patch writes it: right after onGetLayoutInflater's answer, before
            // the fragment stores it and returns it.
            val hooked = MutableMethod(found.single())
            hookFragmentInflater(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "darkLivePage"
            }
            assertTrue("${apk.name}: no hook in the fragment inflater", hook > 1)
            assertEquals("onGetLayoutInflater", ((code[hook - 2] as ReferenceInstruction).reference as MethodReference).name)
            val answer = (code[hook - 1] as OneRegisterInstruction).registerA
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
            assertEquals(answer, (code[hook + 1] as OneRegisterInstruction).registerA)
            val stored = code.drop(hook + 2).first { it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.RETURN_OBJECT }
            assertEquals("${apk.name}: the fragment stores the hooked inflater", Opcode.IPUT_OBJECT, stored.opcode)
            assertEquals(answer, (stored as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // LivePanelTheme walks up by these, and takes a ranking page's view, by name.
            for (name in listOf("getParentFragment", "getContext", "getView")) {
                val method = fragment.methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() }
                assertTrue("${apk.name}: the fragment has no public $name()",
                    method != null && AccessFlags.PUBLIC.isSet(method.accessFlags))
            }
        }
    }

    @Test
    fun `each declared build keeps the ranking tab strip's context the tab hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            for ((field, fingerprint) in listOf(
                "selectedTextColor" to RankTabPickedFingerprint,
                "defaultTextColor" to RankTabUnpickedFingerprint,
            )) {
                val found = classes.values.flatMap { owner -> owner.methods.filter { fingerprint.takes(it, owner) } }
                assertEquals("${apk.name}: the ranking tab strip's $field", 1, found.size)
                val tab = rankTabContext(found.single(), field)!!
                assertNotEquals("${apk.name}: the context moved over the page", tab.page, tab.context)
                val code = found.single().implementation!!.instructions.toList()
                // The page the extension walks up from has to be a fragment.
                val page = ((code.take(tab.call).last {
                    it.opcode == Opcode.IGET_OBJECT && (it as OneRegisterInstruction).registerA == tab.page
                } as ReferenceInstruction).reference as FieldReference).type
                assertTrue("${apk.name}: the tab strip's page isn't a fragment",
                    ANDROIDX_FRAGMENT in generateSequence(page) { classes[it]?.superclass }.toList())
                if ("47.4.3" in apk.name) {
                    assertEquals("${apk.name}: the tab strip's page",
                        "Lcom/bytedance/android/live/rank/impl/list/fragment/page/RankPageFragment;", page)
                }

                // The hook as the patch writes it: right after the context's move-result, the
                // extension's answer goes back into the same register before anything reads it.
                val hooked = MutableMethod(found.single())
                hookRankTab(hooked, field)
                val written = hooked.implementation!!.instructions.toList()
                assertEquals(Opcode.MOVE_RESULT_OBJECT, written[tab.call + 1].opcode)
                val hook = written[tab.call + 2]
                assertEquals("${apk.name}: the hook's call", Opcode.INVOKE_STATIC, hook.opcode)
                assertEquals("stockContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
                val args = hook as FiveRegisterInstruction
                assertEquals(listOf(tab.page, tab.context), listOf(args.registerC, args.registerD))
                assertEquals(Opcode.MOVE_RESULT_OBJECT, written[tab.call + 3].opcode)
                assertEquals(tab.context, (written[tab.call + 3] as OneRegisterInstruction).registerA)
                assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
            }
        }
    }

    @Test
    fun `each declared build keeps the team list's bottom bar contexts the page hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val team = classes[TEAM_RANK_LIST] ?: throw AssertionError("${apk.name}: $TEAM_RANK_LIST is gone")
            // The extension walks up from it by the fragment's getParentFragment.
            assertTrue("${apk.name}: the team list isn't a fragment",
                ANDROIDX_FRAGMENT in generateSequence(TEAM_RANK_LIST) { classes[it]?.superclass }.toList())
            val found = team.methods.filter { TeamRankListViewFingerprint.takes(it, team) }
            assertEquals("${apk.name}: the team list's onViewCreated", 1, found.size)
            assertPageHooked(apk.name, found.single(), if ("47.4.3" in apk.name) 3 else null)
        }
    }

    @Test
    fun `each declared build keeps the ranking lists' bottom bar tag icon the page hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val list = classes[RANK_LIST] ?: throw AssertionError("${apk.name}: $RANK_LIST is gone")
            // The team list builds on it, so the trending tab's bar is drawn through it.
            assertTrue("${apk.name}: the team list left the ranking lists' base",
                RANK_LIST in generateSequence(TEAM_RANK_LIST) { classes[it]?.superclass }.toList())
            val found = list.methods.filter { RankListBottomTagFingerprint.takes(it, list) }
            assertEquals("${apk.name}: the bottom bar's tag", 1, found.size)
            // The icon's drawable and its tint, each through its own getContext().
            assertPageHooked(apk.name, found.single(), if ("47.4.3" in apk.name) 2 else null)
        }
    }

    @Test
    fun `each declared build keeps the ranking page's reach_top20 chevron the page hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val page = classes[RANK_PAGE] ?: throw AssertionError("${apk.name}: $RANK_PAGE is gone")
            // The extension walks up from it by the fragment's getParentFragment.
            assertTrue("${apk.name}: the ranking page isn't a fragment",
                ANDROIDX_FRAGMENT in generateSequence(RANK_PAGE) { classes[it]?.superclass }.toList())
            val found = page.methods.filter { RankPageReachTopFingerprint.takes(it, page) }
            assertEquals("${apk.name}: the reach_top20 row", 1, found.size)
            // The chevron's drawable and its tint, asked of the copy of p0 in v7 since p0 is past v15.
            val known = "47.4.3" in apk.name
            assertPageHooked(apk.name, found.single(), if (known) 2 else null, if (known) 7 else null)
        }
    }

    @Test
    fun `each declared build keeps the ranking row's info icon the gap hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val found = classes.values.flatMap { owner -> owner.methods.filter { RankGapInfoIconFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: the bottom row's info icon", 1, found.size)
            val method = found.single()
            val (context, text) = gapInfoIcon(method)!!
            if ("47.4.3" in apk.name) assertEquals("${apk.name}: p1 and p4", 14 to 17, context to text)
            // The extension finds the context only through a row text that's a View.
            assertTrue("${apk.name}: the row text isn't a TextView", "Landroid/widget/TextView;" in
                generateSequence(method.parameterTypes[3].toString()) { classes[it]?.superclass }.toList())
            assertGapHooked(apk.name, method, context, text)
        }
    }

    /**
     * [method] hooked as the patch writes it: before anything else, the row text is copied into v0
     * and handed to the extension with the context, whose answer goes back into the context's own
     * register.
     */
    private fun assertGapHooked(apk: String, method: Method, context: Int, text: Int) {
        val hooked = MutableMethod(method)
        hookGapInfoIcon(hooked)
        val written = hooked.implementation!!.instructions.toList()
        val copy = written[0] as TwoRegisterInstruction
        assertEquals("$apk: the text's copy", Opcode.MOVE_OBJECT_FROM16, copy.opcode)
        assertEquals(listOf(0, text), listOf(copy.registerA, copy.registerB))
        val hook = written[1] as FiveRegisterInstruction
        assertEquals("stockViewContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(listOf(0, context), listOf(hook.registerC, hook.registerD))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, written[2].opcode)
        assertEquals(context, (written[2] as OneRegisterInstruction).registerA)
        assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
    }

    /**
     * A bottom row shaped like 47.4.3's in [registers] registers: it reads the gap's FAQ, asks a
     * copy of the row text (p4) what it shows and tints the icon through the context (p1) with
     * [lookup], with [after] past the tint, or runs [body] in place of all that.
     */
    private fun gapRow(
        registers: Int = 19,
        lookup: String = "const v0, 0x7f061f39\ninvoke-static { v0, p1 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I",
        after: String = "",
        body: String = """
            move-object/from16 v11, p5
            iget-object v1, v11, $RANK_GAP->bottomBarFaq:Ljava/lang/Object;
            if-eqz v1, :done
            move-object/from16 v5, p4
            invoke-virtual { v5 }, Landroidx/appcompat/widget/AppCompatTextView;->getText()Ljava/lang/CharSequence;
            $lookup
            $after
        """,
    ) = MutableMethod(ImmutableMethod("Lapp/morphe/Gap;", "LJII",
        listOf("Landroid/content/Context;", "Lcom/bytedance/ies/sdk/datachannel/DataChannel;",
            "Lcom/bytedance/android/live/rank/impl/list/controller/RankListController;", "Landroidx/appcompat/widget/AppCompatTextView;",
            RANK_GAP).map { ImmutableMethodParameter(it, null, null) },
        "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            $body
            :done
            return-void
        """)
    }

    @Test
    fun `the gap hook takes only a row text and context it can hand over safely`() {
        assertEquals(14 to 17, gapInfoIcon(gapRow()))
        assertGapHooked("a row", gapRow(), 14, 17)
        assertNotNull("Context methods take the wrapper", gapInfoIcon(gapRow(after =
            "invoke-virtual { p1 }, Landroid/content/Context;->getResources()Landroid/content/res/Resources;")))
        assertNull("no lookup", gapInfoIcon(gapRow(lookup = "")))
        assertNull("a call that takes the context alone", gapInfoIcon(gapRow(lookup =
            "invoke-static { p1 }, Lapp/morphe/Gap;->tint(Landroid/content/Context;)I")))

        // In 21 registers p1 is v16, which the hook's four-bit call can't take.
        val wide = "const v15, 0x7f061f39\ninvoke-static/range { v15 .. v16 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I"
        assertNull("a context past v15", gapInfoIcon(gapRow(registers = 21, lookup = wide)))
        // In 6 registers every one is a parameter: v0 is `this`, which the hook's copy would overwrite.
        val bare = """
            iget-object p3, p5, $RANK_GAP->bottomBarFaq:Ljava/lang/Object;
            if-eqz p3, :done
            invoke-virtual { p4 }, Landroidx/appcompat/widget/AppCompatTextView;->getText()Ljava/lang/CharSequence;
            const p2, 0x7f061f39
            invoke-static { p2, p1 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I
        """
        assertNull("no local to copy the text through", gapInfoIcon(gapRow(registers = 6, body = bare)))
        assertEquals("the same row with one local", 2 to 5, gapInfoIcon(gapRow(registers = 7, body = bare)))
        for (after in listOf(
            "check-cast p1, Landroid/app/Activity;",
            "move-object v4, p1",
            "invoke-static { p1 }, Lapp/morphe/Gap;->keep(Ljava/lang/Object;)V",
            "const/4 p1, 0x0",
            // The copy the text call is asked of holds something else too.
            "new-instance v5, Ljava/lang/Object;",
            "const/4 v1, 0x0\nmove-object/from16 p4, v1",
        )) {
            assertNull(after, gapInfoIcon(gapRow(after = after)))
        }
    }

    /**
     * [method]'s contexts as [pageContexts] finds them, [expected] of them when given, hooked as the
     * patch writes it: right after each move-result, the extension's answer goes back into the
     * same register, each earlier hook two instructions long, handed the register its getContext()
     * asked, p0 or a copy of it ([page] when given).
     */
    private fun assertPageHooked(apk: String, method: Method, expected: Int?, page: Int? = null) {
        val contexts = pageContexts(method)
        if (expected != null) assertEquals("$apk: ${method.name}'s contexts", expected, contexts.size)
        val code = method.implementation!!.instructions.toList()
        val self = method.implementation!!.registerCount - method.parameterTypes.size - 1
        val hooked = MutableMethod(method)
        hookPageContexts(hooked)
        val written = hooked.implementation!!.instructions.toList()
        contexts.forEachIndexed { done, (result, context) ->
            val asked = (code[result - 1] as FiveRegisterInstruction).registerC
            if (page != null) assertEquals("$apk: context $done's page", page, asked)
            assertTrue("$apk: context $done is asked of v$asked, not this", asked == self || code.any {
                it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == asked && it.registerB == self
            })
            val at = result + 2 * done
            assertEquals("$apk: context $done", Opcode.MOVE_RESULT_OBJECT, written[at].opcode)
            val hook = written[at + 1] as FiveRegisterInstruction
            assertEquals("stockContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(listOf(asked, context), listOf(hook.registerC, hook.registerD))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, written[at + 2].opcode)
            assertEquals(context, (written[at + 2] as OneRegisterInstruction).registerA)
        }
        assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
    }

    /** A page in 20 registers, p0 being v19, that copies p0 into v7 and asks v7 for its context. */
    private fun copiedPageMethod(after: String = "") = MutableMethod(ImmutableMethod("Lapp/morphe/Page;", "Nd",
        emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(20, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            move-object/from16 v7, p0
            invoke-virtual { v7 }, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;
            move-result-object v1
            const v0, 0x7f061dd8
            invoke-static { v0, v1 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I
            $after
            return-void
        """)
    }

    @Test
    fun `the page hook hands a copy of this past v15 along with its context`() {
        val page = copiedPageMethod()
        assertEquals(listOf(2 to 1), pageContexts(page))
        hookPageContexts(page)
        val hook = page.implementation!!.instructions.toList()[3] as FiveRegisterInstruction
        assertEquals("stockContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(listOf(7, 1), listOf(hook.registerC, hook.registerD))
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(page))
    }

    @Test
    fun `a register that holds something else too isn't taken for this`() {
        // v7 also gets a plain object, so the context asked of it may not be the page's own.
        assertThrows(PatchException::class.java) {
            pageContexts(copiedPageMethod(after = "new-instance v7, Ljava/lang/Object;"))
        }
    }

    /** A page method asking its own context once and handing it to [lookup] with a resource id. */
    private fun pageMethod(lookup: String) = MutableMethod(ImmutableMethod("Lapp/morphe/Page;", "RX",
        emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(3, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            invoke-virtual { p0 }, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;
            move-result-object v1
            const v0, 0x7f061f40
            invoke-static { v0, v1 }, $lookup
            return-void
        """)
    }

    @Test
    fun `the page hook takes a context handed to a color or drawable lookup`() {
        assertEquals("a color", listOf(1 to 1), pageContexts(pageMethod("LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I")))
        assertEquals("a drawable", listOf(1 to 1), pageContexts(pageMethod("LX/0Jvn;->LIZLLL(ILandroid/content/Context;)LX/0Jvv;")))
        for (lookup in listOf(
            "LX/0Jvn;->keep(ILandroid/content/Context;)V",
            "LX/0Jvn;->pick(ILandroid/content/Context;)Z",
            "LX/0Jvn;->tint(Landroid/content/Context;I)I",
        )) {
            assertThrows(lookup, PatchException::class.java) { pageContexts(pageMethod(lookup)) }
        }
    }

    @Test
    fun `each declared build keeps the LIVE row binders' base the row hook asks first`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val found = classes.values.flatMap { owner ->
                owner.methods.filter { LiveRowBinderFingerprint.takes(it, owner) }.map { owner to it }
            }
            assertEquals("${apk.name}: the row binders' base", 1, found.size)
            val (owner, make) = found.single()
            val binder = rowBinder(make, owner)!!
            if ("47.4.3" in apk.name) {
                assertEquals("${apk.name}: the row binders' base", "LX/10fG;", owner.type)
                assertEquals(listOf("LIZIZ", "LIZJ", "LJIIIIZZ"),
                    listOf(binder.layout.name, binder.attach.name, binder.holder.name))
                // The trending tab's rows come from the ranking's row binder, which builds on it.
                assertTrue("${apk.name}: the ranking's row binder left the base",
                    owner.type in generateSequence("LX/1PLB;") { classes[it]?.superclass }.toList())
            }

            // The hook as the patch writes it: the extension asked first, its row handed to the
            // binder's own holder call and returned, and a null answer landing on TikTok's code.
            val hooked = MutableMethod(make)
            hookRowBinder(hooked, owner)
            val code = hooked.implementation!!.instructions.toList()
            val self = hooked.implementation!!.registerCount - 3
            assertEquals("${apk.name}: the hook",
                listOf(Opcode.IGET, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ,
                    Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                code.take(8).map { it.opcode })
            assertEquals(binder.layout, (code[0] as ReferenceInstruction).reference)
            assertEquals(binder.attach, (code[1] as ReferenceInstruction).reference)
            val ask = code[2] as FiveRegisterInstruction
            assertEquals("stockRow", ((ask as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(listOf(self + 2, 0, 1), listOf(ask.registerC, ask.registerD, ask.registerE))
            val holder = code[5] as FiveRegisterInstruction
            assertEquals(binder.holder, (holder as ReferenceInstruction).reference)
            assertEquals(listOf(self, 0, self + 2), listOf(holder.registerC, holder.registerD, holder.registerE))
            assertEquals(8, (code[4] as BuilderOffsetInstruction).target.location.index)
            assertEquals(make.implementation!!.instructions.first().opcode, code[8].opcode)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }

    /** A tab strip shaped like 47.4.3's, its page's context in [context], with [after] past the color call. */
    private fun tabStrip(context: String = "v2", after: String = "") = MutableMethod(ImmutableMethod("Lapp/morphe/Tab;", "LIZIZ",
        listOf(ImmutableMethodParameter(SUB_RANK_TAB, null, null)), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(6, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            iget-object v1, p1, $SUB_RANK_TAB->selectedTextColor:Ljava/lang/String;
            iget-object v0, p0, Lapp/morphe/Tab;->page:$ANDROIDX_FRAGMENT
            invoke-virtual { v0 }, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;
            move-result-object $context
            if-nez $context, :tinted
            return-void
            :tinted
            const/4 v3, 0x1
            invoke-static { v3, $context }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I
            $after
            return-void
        """)
    }

    @Test
    fun `the tab hook takes only a context it can swap for the extension's`() {
        val tab = rankTabContext(tabStrip(), "selectedTextColor")!!
        assertEquals(listOf(2, 0, 2), listOf(tab.call, tab.page, tab.context))
        assertNotNull("Context methods take the wrapper", rankTabContext(tabStrip(after =
            "invoke-virtual { v2 }, Landroid/content/Context;->getResources()Landroid/content/res/Resources;"), "selectedTextColor"))
        assertNotNull("a Context after a long", rankTabContext(tabStrip(after =
            "invoke-static { v0, v1, v2 }, Lapp/morphe/Tab;->at(JLandroid/content/Context;)V"), "selectedTextColor"))
        assertNull("another field", rankTabContext(tabStrip(), "defaultTextColor"))

        assertNull("the context over the page", rankTabContext(tabStrip(context = "v0"), "selectedTextColor"))
        for (read in listOf(
            "check-cast v2, Landroid/app/Activity;",
            "invoke-static { v2 }, Lapp/morphe/Tab;->keep(Ljava/lang/Object;)V",
            "invoke-static/range { v2 .. v2 }, Lapp/morphe/Tab;->keep(Ljava/lang/Object;)V",
            // The long takes v0 and v1, so v2 is the Object.
            "invoke-static { v0, v1, v2 }, Lapp/morphe/Tab;->at(JLjava/lang/Object;)V",
            "iput-object v2, p0, Lapp/morphe/Tab;->kept:Landroid/content/Context;",
            "move-object v4, v2",
            // A write to v1 that reads v2 as its third register.
            "aget-object v1, v1, v2",
            "filled-new-array { v2 }, [Landroid/content/Context;",
        )) {
            assertNull(read, rankTabContext(tabStrip(after = read), "selectedTextColor"))
        }
    }

    /** The first type above [type] that isn't in the APK: the framework class it builds on. */
    private fun outermost(classes: Map<String, ClassDef>, type: String) =
        generateSequence(type) { classes[it]?.superclass }.first { it !in classes }

    @Test
    fun `each declared build keeps both quality lists' view setup`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            listOf(
                LIVE_DEFINITION_DIALOG to LiveDefinitionListFingerprint,
                LIVE_TALENT_DEFINITION_DIALOG to LiveTalentDefinitionListFingerprint,
            ).forEach { (type, fingerprint) ->
                val dialog = classes[type] ?: throw AssertionError("${apk.name}: $type is gone")
                val found = dialog.methods.filter { fingerprint.takes(it, dialog) }
                assertEquals("${apk.name}: $type onViewCreated", 1, found.size)
                // The view sits above v15 on 47.1.4, so the hook takes the range form; the inline
                // compiler drops a four-bit call to a register that doesn't fit, silently.
                val hooked = MutableMethod(found.single())
                hookQualityList(hooked)
                val first = hooked.implementation!!.instructions.first()
                assertEquals("${apk.name}: $type the hook's call", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                val view = (first as com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction).startRegister
                assertEquals("${apk.name}: $type the hook reads the view", found.single().implementation!!.registerCount - 2, view)
                assertEquals("readableQualityList", ((first as ReferenceInstruction).reference as MethodReference).name)
            }
        }
    }
}
