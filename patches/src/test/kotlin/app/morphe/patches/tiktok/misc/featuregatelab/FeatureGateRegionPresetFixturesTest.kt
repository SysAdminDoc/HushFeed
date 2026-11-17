package app.morphe.patches.tiktok.misc.featuregatelab

import app.morphe.Fixtures
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native readers behind the scrolling comments and several-photos presets on TikTok 47.8.3
 * (#98): which key each reads, through which AB read, and which values turn the feature on.
 */
class FeatureGateRegionPresetFixturesTest {
    private val app by lazy {
        val container = Fixtures.dexContainer(Fixtures.apkOf("47.8.3"), Opcodes.getDefault())
        val owners = setOf(
            DANMAKU_READ, DANMAKU_HOLDER, DANMAKU_MASTER, LANDSCAPE_READ, DANMAKU_SERVICE, AWEME,
            PHOTOS_READ, PHOTOS_GATE, PHOTO_COUNT, PUBLISH, KEYBOARD, TAB_READ, AB_READS, AB_CALLBACK,
            TAG_TRIGGER, FEED_TAG_GATE, FEED_TAG_READ, AFTER_BLOCK_HOLDER, AFTER_BLOCK_READ, VISION_GATE, VISION_READ,
        )
        container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.filter { it.type in owners }
        }.associateBy { it.type }.also { assertEquals(owners, it.keys) }
    }

    @Test
    fun `danmaku_exp is the INT read whose groups one and two turn scrolling comments on`() {
        val read = method(DANMAKU_READ, "invoke")
        assertEquals(listOf("danmaku_exp"), read.strings())
        assertEquals(AB_INT_READ, read.calls().single { it.returnType == "I" }.toString())

        val holder = method(DANMAKU_HOLDER, "<clinit>")
        assertTrue("the holder's lazy group and master", holder.created().containsAll(listOf(DANMAKU_READ, DANMAKU_MASTER)))
        assertEquals("the master is read back as a boolean", listOf("$DANMAKU_HOLDER->LIZLLL:LX/02q1;"),
            method(DANMAKU_HOLDER, "LIZIZ").fields())
        assertEquals("the service asks the master", "$DANMAKU_HOLDER->LIZIZ()Z",
            method(DANMAKU_SERVICE, "LJIIIIZZ").calls().single().toString())

        // The master keeps TikTok's own strategy switch and ends on group 1 or group 2.
        val master = method(DANMAKU_MASTER, "invoke")
        assertTrue("TikTok's strategy switch still decides first", "strategy_control_enable_danmaku" in master.strings())
        val code = master.implementation!!.instructions.toList()
        val groupReads = code.indices.filter { code[it].getReference<MethodReference>()?.toString() == "$DANMAKU_HOLDER->LIZ()I" }
        assertEquals("the group is read twice at the end", 2, groupReads.size)
        val first = groupReads[0] + 2
        val second = groupReads[1] + 3
        assertEquals(Opcode.IF_EQ, code[first].opcode)
        assertEquals(Opcode.IF_EQ, code[second].opcode)
        // The first comparison's 1 is also what the shared landing passes to Boolean.valueOf.
        assertEquals("the first comparison is with 1", 1, literalIn(code, first, (code[first] as TwoRegisterInstruction).registerB))
        assertEquals("the second comparison is with 2", 2, literalIn(code, second, (code[second] as TwoRegisterInstruction).registerB))
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val landing = { at: Int -> addresses[at] + (code[at] as OffsetInstruction).codeOffset }
        assertEquals("both comparisons land in the same place", landing(first), landing(second))
        assertEquals("Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;",
            code[addresses.indexOf(landing(first))].getReference<MethodReference>().toString())
    }

    @Test
    fun `landscape videos count only after the master and only for value one`() {
        val read = method(LANDSCAPE_READ, LANDSCAPE_METHOD)
        assertEquals(listOf("landscape_support_danmaku"), read.strings())
        assertEquals(AB_INT_READ, read.calls().single { it.returnType == "I" }.toString())
        for (master in listOf(false, true)) for (value in -1..3) {
            assertEquals("master=$master value=$value", master && value == 1, run(read) { call, _ ->
                when (call.toString()) {
                    "LX/0ASZ;->LJIIIIZZ()Z" -> if (master) 1 else 0
                    "Lsp4/m;->LJIIIZ()Lsp4/m;" -> Any()
                    AB_INT_READ -> value
                    "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;" -> null
                    else -> error("Unexpected call: $call")
                }
            })
        }
    }

    @Test
    fun `each video still carries its own server flag for scrolling comments`() {
        assertEquals(listOf("$AWEME->isSupportDanmaku:Z"), method(AWEME, "isSupportDanmaku").fields())
        val perVideo = method(DANMAKU_SERVICE, "LJIIIZ").calls().map { it.toString() }
        assertEquals("the video's flag is read before the master", "$AWEME->isSupportDanmaku()Z", perVideo.first())
        assertTrue("$DANMAKU_HOLDER->LIZIZ()Z" in perVideo)
    }

    @Test
    fun `comment_multi_photo_group turns several photos on for group two only`() {
        val read = method(PHOTOS_READ, "invoke")
        assertEquals(listOf("comment_multi_photo_group"), read.strings())
        assertEquals(AB_INT_READ, read.calls().single { it.returnType == "I" }.toString())
        assertTrue(method(PHOTOS_GATE, "<clinit>").created().contains(PHOTOS_READ))

        val gate = method(PHOTOS_GATE, "LIZ")
        for (group in -1..4) {
            assertEquals("group $group", group == 2, run(gate) { call, _ ->
                when (call.toString()) {
                    "LX/02q1;->getValue()Ljava/lang/Object;" -> group
                    "Ljava/lang/Number;->intValue()I" -> group
                    else -> error("Unexpected call: $call")
                }
            })
        }
        // 47.8.3 names 47.7.3's iJ dJ, the same signature and the same first read.
        for ((owner, name) in listOf(KEYBOARD to "dJ", KEYBOARD to "onViewCreated")) {
            assertTrue("$owner $name reads the gate", reads(owner, name, "$PHOTOS_GATE->LIZ()Z"))
        }
        // Publishing (47.7.3's x53, now g53) asks a helper that takes the photo count, and the
        // helper asks the gate when there's more than one photo.
        assertTrue("$PUBLISH g53 counts the photos", reads(PUBLISH, "g53", "$PHOTO_COUNT->LIZ(I)Z"))
        assertTrue("$PHOTO_COUNT reads the gate", reads(PHOTO_COUNT, "LIZ", "$PHOTOS_GATE->LIZ()Z"))
    }

    @Test
    fun `a paused video's search tag needs enable_vision_search and the feed tag at one`() {
        for ((read, key) in listOf(VISION_READ to "enable_vision_search", FEED_TAG_READ to "visual_search_enable_feed_tag",
            AFTER_BLOCK_READ to "show_tag_after_circle_search_block")) {
            val invoke = method(read, "invoke")
            assertEquals(listOf(key), invoke.strings())
            assertEquals(AB_INT_READ, invoke.calls().single { it.returnType == "I" }.toString())
        }
        for ((holder, read) in listOf(VISION_GATE to VISION_READ, FEED_TAG_GATE to FEED_TAG_READ,
            AFTER_BLOCK_HOLDER to AFTER_BLOCK_READ)) {
            assertTrue("$holder keeps $read as its lazy", method(holder, "<clinit>").created().contains(read))
        }

        // The tag is mounted only when TikTok's visual search part is there and both gates pass.
        val trigger = method(TAG_TRIGGER, "zq")
        for (loaded in listOf(false, true)) for (feedTag in listOf(false, true)) for (vision in listOf(false, true)) {
            assertEquals("loaded=$loaded feedTag=$feedTag vision=$vision", loaded && feedTag && vision,
                run(trigger) { call, _ ->
                    when (call.toString()) {
                        "$VISUAL_SEARCH_LOADED->LIZIZ()Z" -> loaded.toInt()
                        "Ljava/lang/Object;->getClass()Ljava/lang/Class;" -> Any()
                        "$FEED_TAG_GATE->LIZ()Z" -> feedTag.toInt()
                        "$VISION_GATE->LIZ()Z" -> vision.toInt()
                        else -> error("Unexpected call: $call")
                    }
                })
        }

        val vision = method(VISION_GATE, "LIZ")
        for (value in -1..3) {
            assertEquals("enable_vision_search=$value", value == 1, run(vision) { call, args ->
                when (call.toString()) {
                    "LX/02q1;->getValue()Ljava/lang/Object;" -> value
                    "Ljava/lang/Number;->intValue()I" -> args.single()
                    else -> error("Unexpected call: $call")
                }
            })
        }

        // The feed tag at 1 comes first. A home page that blocks Circle to Search then takes
        // show_tag_after_circle_search_block at 1. Anything else falls to the server's Crowd: none
        // at all lets it through, and so does enableVTag (or enableFallbackVTag) from 100 to 199.
        val gate = method(FEED_TAG_GATE, "LIZ")
        for (feedTag in 0..2) for (blocked in listOf(false, true)) for (after in 0..2) for (crowd in listOf(null, 0, 150)) {
            val expected = feedTag == 1 && ((blocked && after == 1) || crowd == null || crowd == 150)
            val field = { read: FieldReference, _: Any? ->
                when (read.toString()) {
                    "$CROWD->enableVTag:Ljava/lang/Integer;", "$CROWD->enableFallbackVTag:Ljava/lang/Integer;" -> Boxed(crowd!!)
                    else -> error("Unexpected field read: $read")
                }
            }
            assertEquals("feedTag=$feedTag blocked=$blocked after=$after crowd=$crowd", expected, run(gate, field) { call, args ->
                when (call.toString()) {
                    "LX/02q1;->getValue()Ljava/lang/Object;" -> when (args.single()) {
                        "$FEED_TAG_GATE->LIZIZ:LX/02q1;" -> feedTag
                        "$AFTER_BLOCK_HOLDER->LIZ:LX/02q1;" -> after
                        else -> error("Unexpected lazy: ${args.single()}")
                    }
                    "Ljava/lang/Number;->intValue()I" -> args.single()
                    "Ljava/lang/Integer;->intValue()I" -> (args.single() as Boxed).value
                    "$SERVICES->get()$SERVICES" -> Any()
                    "$SERVICES->getService(Ljava/lang/Class;)Ljava/lang/Object;" -> Any()
                    "Lcom/ss/android/ugc/aweme/spi/IHomePageService;->LJ()Z" -> blocked.toInt()
                    "Ljava/lang/Object;->getClass()Ljava/lang/Class;" -> Any()
                    "LX/0Brd;->LIZ()$CROWD" -> if (crowd == null) null else Any()
                    else -> error("Unexpected call: $call")
                }
            })
        }
    }

    @Test
    fun `the home tab rows are a TabConfig read through the typed object getter the Lab hooks`() {
        val read = method(TAB_READ, "invoke")
        assertEquals(listOf("tab_fcp_config_v2"), read.strings())
        assertTrue("read as a TabConfig", read.implementation!!.instructions.any {
            it.opcode == Opcode.CONST_CLASS && it.getReference<TypeReference>()?.type == TAB_CONFIG
        })
        assertEquals("$AB_READS->LJIJJLI(ZLjava/lang/String;ILjava/lang/Class;Ljava/lang/Object;I)Ljava/lang/Object;",
            read.calls().single { it.returnType == "Ljava/lang/Object;" }.toString())
        // That read's callback goes on to the App AB class's (String, boolean, boolean, Class, int)
        // getter, the shape FeatureGateLabPatch hooks for OBJECT rules.
        assertTrue("the callback reads through LJIJ", app.getValue(AB_CALLBACK).methods.any { callback ->
            callback.implementation?.let { callback.calls() }.orEmpty()
                .any { it.toString() == "$AB_READS->LJIJ(ZLjava/lang/String;Ljava/lang/Class;I)Ljava/lang/Object;" }
        })
        val typed = method(AB_READS, "LJIJ").calls().single { it.returnType == "Ljava/lang/Object;" }
        assertEquals(listOf("Ljava/lang/String;", "Z", "Z", "Ljava/lang/Class;", "I"),
            typed.parameterTypes.map { it.toString() })
    }

    private fun method(owner: String, name: String): Method = app.getValue(owner).methods.single { it.name == name }

    private fun reads(owner: String, name: String, call: String) = app.getValue(owner).methods
        .filter { it.name == name }.any { candidate -> candidate.calls().any { it.toString() == call } }

    private fun Method.calls() = implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }

    private fun Method.strings() = implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string }

    private fun Method.fields() = implementation!!.instructions.mapNotNull { it.getReference<FieldReference>()?.toString() }

    private fun Method.created() = implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { it.getReference<TypeReference>()?.type }

    /** The literal last written to [register] before [index], in code order. */
    private fun literalIn(code: List<Instruction>, index: Int, register: Int): Int {
        for (i in index - 1 downTo 0) {
            val writer = code[i]
            if (writer is OneRegisterInstruction && writer.registerA == register && writer.opcode.setsRegister()) {
                return (writer as? NarrowLiteralInstruction)?.narrowLiteral ?: error("v$register comes from ${writer.opcode}")
            }
        }
        error("v$register is never written")
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    /** A boxed Integer a native gate null-checks before unboxing it. */
    private data class Boxed(val value: Int)

    /**
     * Runs a small native gate to its return, with each call answered by [answer] (booleans as
     * 0 and 1) and each object field read by [field]. Boolean.valueOf passes its argument
     * through, and a static object read leaves the field's name in its register.
     */
    private fun run(
        method: Method,
        field: (FieldReference, Any?) -> Any? = { read, _ -> error("Unexpected field read: $read") },
        answer: (MethodReference, List<Any?>) -> Any?,
    ): Boolean {
        val implementation = method.implementation!!
        val code = implementation.instructions.toList()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }.dropLast(1)
        val registers = arrayOfNulls<Any>(implementation.registerCount)
        var result: Any? = null
        var index = 0
        repeat(200) {
            val instruction = code[index]
            var branch = false
            when (instruction.opcode) {
                Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST -> registers[(instruction as OneRegisterInstruction).registerA] =
                    (instruction as NarrowLiteralInstruction).narrowLiteral
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                    registers[(instruction as OneRegisterInstruction).registerA] = instruction.getReference<StringReference>()!!.string
                Opcode.SGET_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] =
                    instruction.getReference<FieldReference>()!!.toString()
                Opcode.CONST_CLASS -> registers[(instruction as OneRegisterInstruction).registerA] = Any()
                Opcode.IGET_OBJECT -> {
                    instruction as TwoRegisterInstruction
                    registers[instruction.registerA] = field(instruction.getReference<FieldReference>()!!, registers[instruction.registerB])
                }
                Opcode.CHECK_CAST -> Unit
                Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE,
                Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE_RANGE -> {
                    val call = instruction.getReference<MethodReference>()!!
                    val args = when (instruction) {
                        is RegisterRangeInstruction -> (0 until instruction.registerCount).map { registers[instruction.startRegister + it] }
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                            instruction.registerF, instruction.registerG).take(instruction.registerCount).map { registers[it] }
                        else -> error("Unexpected invoke shape")
                    }
                    result = if (call.toString() == "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;") args.single()
                    else answer(call, args)
                }
                Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = result
                Opcode.IF_EQ, Opcode.IF_NE -> {
                    instruction as TwoRegisterInstruction
                    val equal = registers[instruction.registerA] == registers[instruction.registerB]
                    branch = if (instruction.opcode == Opcode.IF_EQ) equal else !equal
                }
                Opcode.IF_GT, Opcode.IF_GE -> {
                    instruction as TwoRegisterInstruction
                    val a = registers[instruction.registerA] as Int
                    val b = registers[instruction.registerB] as Int
                    branch = if (instruction.opcode == Opcode.IF_GT) a > b else a >= b
                }
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val value = registers[(instruction as OneRegisterInstruction).registerA]
                    val zero = value == null || value == 0
                    branch = if (instruction.opcode == Opcode.IF_EQZ) zero else !zero
                }
                Opcode.GOTO, Opcode.GOTO_16 -> branch = true
                Opcode.RETURN, Opcode.RETURN_OBJECT -> return (registers[(instruction as OneRegisterInstruction).registerA] as Int) != 0
                else -> error("Unexpected native gate instruction: ${instruction.opcode}")
            }
            index = if (branch) addresses.indexOf(addresses[index] + (instruction as OffsetInstruction).codeOffset)
                .also { check(it >= 0) { "Native gate branch does not target an instruction" } } else index + 1
        }
        error("Native gate did not return")
    }

    private companion object {
        const val AB_INT_READ = "Lsp4/m;->LJIIJJI(IILjava/lang/String;Z)I"
        const val DANMAKU_READ = "LX/0IJF;"
        const val DANMAKU_HOLDER = "LX/0IJE;"
        const val DANMAKU_MASTER = "LX/0IJI;"
        const val LANDSCAPE_READ = "Lkotlin/jvm/internal/AFwS164S0000000_36_I1;"
        const val LANDSCAPE_METHOD = "invoke\$275"
        const val DANMAKU_SERVICE = "Lcom/ss/android/ugc/aweme/service/DanmakuServiceImpl;"
        const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        const val PHOTOS_READ = "LX/0HPi;"
        const val PHOTOS_GATE = "LX/0HPh;"
        const val PHOTO_COUNT = "LX/1Lf2;"
        const val PUBLISH = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/viewmodel/CommentPublishViewModel;"
        const val KEYBOARD = "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/PortraitInputKeyboard;"
        const val TAB_READ = "LX/04Tb;"
        const val TAB_CONFIG = "Lcom/ss/android/ugc/aweme/experiment/TabConfig;"
        const val AB_READS = "Lsp4/m;"
        const val AB_CALLBACK = "LX/03ee;"
        const val TAG_TRIGGER = "Lcom/ss/android/ugc/aweme/search/entry/visualsearch/ui/SearchVisualTagComponentTrigger;"
        const val VISUAL_SEARCH_LOADED = "LX/0B1l;"
        const val FEED_TAG_GATE = "LX/0ApU;"
        const val FEED_TAG_READ = "LX/0ApV;"
        const val AFTER_BLOCK_HOLDER = "LX/0ApW;"
        const val AFTER_BLOCK_READ = "LX/0ApX;"
        const val VISION_GATE = "LX/0ApY;"
        const val VISION_READ = "LX/0ApZ;"
        const val CROWD = "Lcom/ss/android/ugc/aweme/config/Crowd;"
        const val SERVICES = "Lcom/ss/android/ugc/aweme/framework/services/ServiceManager;"
    }
}
