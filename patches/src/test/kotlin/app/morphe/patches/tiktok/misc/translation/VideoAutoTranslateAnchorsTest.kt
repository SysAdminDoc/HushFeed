/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What keeps videos in their own language (#194), held to each declared build: one static getter
 * reads TikTok's Always translate key, one static `()Z` in its class answers that state's `isOn()`
 * and gets the extension in front, the class's comment translation switch asks the stored switch
 * and the extension's last published state instead of that read, nothing outside the class but the
 * captions asks the read, and the state the setter publishes is built through the extension.
 */
class VideoAutoTranslateAnchorsTest {
    @Test
    fun `each declared build has one read, comment defaults and a published state, all hooked`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val states = classes.flatMap { owner -> owner.methods.filter { it.isMasterTranslationState() }.map { owner to it } }
            assertEquals("$version: getters of Always translate", 1, states.size)
            val (owner, state) = states.single()

            val hooks = alwaysTranslateHooks(owner.methods.toList(), state)
            assertEquals("$version: TikTok's comment switch asks Always translate twice", 2, hooks.commentDefaults.size)

            // The guarded read answers every caller outside its class. Each one has to be about
            // videos, so a new caller here gets read before it's taken in.
            val outside = classes.filter { it.type != owner.type }.flatMap { caller ->
                caller.methods.filter { it.callsTo(hooks.read).isNotEmpty() }.map { caller.type }
            }
            assertEquals("$version: callers of the read outside its class", listOf(CAPTION_KEVA_SERVICE), outside)

            // commentDefault answers the last state built. Only the setter may build one in this
            // class, so the comment default's own ask of the stored switch never builds one first.
            val builders = owner.methods.filter { method ->
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == hooks.stateType
                } == true
            }.map { it.name }
            assertEquals("$version: methods building the published state", owner.methods.filter { it.isMasterTranslationSetter() }.map { it.name }, builders)

            val before = hooks.read.implementation!!.instructions.toList()
            val guarded = MutableMethod(hooks.read)
            guarded.keepVideosOriginal()
            val after = guarded.implementation!!.instructions.toList()
            assertGuarded(version, after)
            assertEquals(
                "$version: TikTok's own instructions follow the guard, in order",
                before.map { it.opcode },
                after.drop(GUARD_SIZE).map { it.opcode },
            )

            for (comment in hooks.commentDefaults) {
                assertRedirected("$version ${comment.name}", comment, hooks.read, state, hooks.isOn)
            }

            val stateClass: ClassDef = classes.single { it.type == hooks.stateType }
            val constructor = publishedStateConstructor(stateClass.methods.toList(), hooks.stateType)
            assertFiltered(version, constructor)
        }
    }

    @Test
    fun `the getter is the static one holding the key`() {
        assertTrue(state().isMasterTranslationState())
        assertFalse("the setter", state(parameters = listOf("Ljava/lang/String;", "Z"), returns = "V").isMasterTranslationState())
        assertFalse("an instance method", state(static = false).isMasterTranslationState())
        assertFalse("another key", state(key = COMMENT_TRANSLATION_KEY).isMasterTranslationState())
        assertFalse("a boolean answer", state(returns = "Z").isMasterTranslationState())
    }

    @Test
    fun `the read is a static boolean answering the state's isOn`() {
        val read = read()
        assertTrue(read.isMasterTranslationRead(STATE))
        assertEquals("isOn", read.isOnAskedBy(STATE).name)
        val hooked = MutableMethod(read)
        hooked.keepVideosOriginal()
        assertGuarded("synthetic", hooked.implementation!!.instructions.toList())
    }

    @Test
    fun `anything else is not the read`() {
        assertFalse("another state getter", read(getter = ImmutableMethodReference(OWNER, "LIZIZ", listOf(), STATE_CLASS)).isMasterTranslationRead(STATE))
        assertFalse("the getter of another class", read(getter = ImmutableMethodReference("LX/0abc;", "LJII", listOf(), STATE_CLASS)).isMasterTranslationRead(STATE))
        assertFalse("another answer", read(answer = "isOff").isMasterTranslationRead(STATE))
        assertFalse("an instance method", read(static = false).isMasterTranslationRead(STATE))
        assertFalse("an int answer", read(returns = "I").isMasterTranslationRead(STATE))
        assertFalse("isOn asked of another register", read(askedOf = 1).isMasterTranslationRead(STATE))
    }

    @Test
    fun `the comment switch's default asks the stored switch, and branches still land on it`() {
        val comment = comment()
        assertTrue(comment.isCommentTranslationDefault(READ))
        val redirected = assertRedirected("synthetic", comment, READ, STATE, IS_ON)
        val branch = redirected.filterIsInstance<BuilderOffsetInstruction>().single()
        assertEquals("the branch that landed on the read lands on the stored switch", 2, branch.target.location.index)
    }

    @Test
    fun `a default asking the read twice has both calls redirected, each into its own register`() {
        val comment = comment(twice = true)
        assertTrue(comment.isCommentTranslationDefault(READ))
        assertEquals(2, comment.callsTo(READ).size)
        assertRedirected("synthetic twice", comment, READ, STATE, IS_ON)
    }

    @Test
    fun `only a comment default that takes the answer is redirected`() {
        assertFalse("no comment key", comment(key = MASTER_TRANSLATION_KEY).isCommentTranslationDefault(READ))
        assertFalse("doesn't call the read", comment(calls = STATE).isCommentTranslationDefault(READ))
        assertFalse("drops the answer", comment(takesAnswer = false).isCommentTranslationDefault(READ))
        assertThrows(PatchException::class.java) {
            MutableMethod(comment(takesAnswer = false)).readStoredSwitch(READ, STATE, IS_ON)
        }
    }

    @Test
    fun `the setter stores the key and publishes one kind of state`() {
        assertTrue(setter().isMasterTranslationSetter())
        assertEquals(PUBLISHED, setter().publishedStateType())
        assertFalse("doesn't store it", setter(stores = false).isMasterTranslationSetter())
        assertFalse("another key", setter(key = COMMENT_TRANSLATION_KEY).isMasterTranslationSetter())
        assertFalse("other parameters", setter(parameters = listOf("Z")).isMasterTranslationSetter())
        assertNull("two kinds of state", setter(alsoBuilds = "LX/0other;").publishedStateType())
    }

    @Test
    fun `the owner's pieces are picked out, and anything unexpected stops the patch`() {
        val hooks = alwaysTranslateHooks(owner(), STATE)
        assertEquals("LJI", hooks.read.name)
        assertEquals(listOf("LIZLLL"), hooks.commentDefaults.map { it.name })
        assertEquals(PUBLISHED, hooks.stateType)
        assertEquals(IS_ON.name, hooks.isOn.name)

        fun refuses(why: String, methods: List<Method>) {
            assertThrows(why, PatchException::class.java) { alwaysTranslateHooks(methods, STATE) }
        }
        refuses("no read", owner().filterNot { it.name == "LJI" })
        refuses("two reads", owner() + read(name = "LJIJ"))
        refuses("nothing asks the read", owner().filterNot { it.name == "LIZLLL" })
        refuses("something other than comments asks the read", owner() + comment(name = "LJIIJ", key = "key_other"))
        refuses("no setter", owner().filterNot { it.name == "LJIILJJIL" })
        refuses("a setter building two kinds of state", owner().filterNot { it.name == "LJIILJJIL" } + setter(alsoBuilds = "LX/0other;"))
    }

    @Test
    fun `the published state is built through the extension`() {
        val constructor = publishedState()
        assertSame(constructor, publishedStateConstructor(listOf(constructor, publishedState(parameters = listOf("Z"))), PUBLISHED))
        assertThrows(PatchException::class.java) { publishedStateConstructor(listOf(publishedState(parameters = listOf("Z"))), PUBLISHED) }
        assertThrows(PatchException::class.java) { publishedStateConstructor(listOf(constructor, constructor), PUBLISHED) }
        assertFiltered("synthetic", constructor)
    }

    private fun assertGuarded(version: String, after: List<Instruction>) {
        val call = after[0]
        assertEquals("$version: the extension is asked first", Opcode.INVOKE_STATIC, call.opcode)
        val target = call.getReference<MethodReference>()!!
        assertEquals(VIDEO_AUTO_TRANSLATE, target.definingClass)
        assertEquals("keepOriginal", target.name)
        assertEquals("Z", target.returnType)
        assertEquals(0, target.parameterTypes.size)
        assertEquals(
            "$version: a yes leaves with false, a no falls through",
            listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN, Opcode.NOP),
            after.subList(1, GUARD_SIZE).map { it.opcode },
        )
        assertEquals("$version: the branch tests the answer",
            (after[1] as OneRegisterInstruction).registerA, (after[2] as OneRegisterInstruction).registerA)
        assertEquals("$version: a no skips to TikTok's own code", GUARD_SIZE - 1, (after[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$version: the answer is false", 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$version: and it is the one returned",
            (after[3] as OneRegisterInstruction).registerA, (after[4] as OneRegisterInstruction).registerA)
    }

    /** Each call to the read became the state's isOn(), answered into the same register, and nothing else moved. */
    private fun assertRedirected(
        label: String,
        method: Method,
        read: MethodReference,
        state: MethodReference,
        isOn: MethodReference,
    ): List<Instruction> {
        val before = method.implementation!!.instructions.toList()
        val calls = method.callsTo(read)
        assertTrue("$label: calls the read", calls.isNotEmpty())
        val redirected = MutableMethod(method)
        redirected.readStoredSwitch(read, state, isOn)
        val after = redirected.implementation!!.instructions.toList()
        assertTrue("$label: still calls the read", redirected.callsTo(read).isEmpty())

        val expected = before.flatMapIndexed { index, it ->
            when {
                index in calls -> listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL_RANGE)
                index - 1 in calls -> listOf(it.opcode, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT)
                else -> listOf(it.opcode)
            }
        }
        assertEquals("$label: the read's calls grew into the stored switch, the rest stayed", expected, after.map { it.opcode })

        calls.forEachIndexed { nth, original ->
            val at = original + 4 * nth
            val getter = after[at].getReference<MethodReference>()!!
            assertEquals("$label: asks the state", listOf(state.definingClass, state.name, state.returnType),
                listOf(getter.definingClass, getter.name, getter.returnType))
            val answer = (before[original + 1] as OneRegisterInstruction).registerA
            assertEquals("$label: the state lands where the answer did", answer, (after[at + 1] as OneRegisterInstruction).registerA)
            val ask = after[at + 2] as RegisterRangeInstruction
            assertEquals("$label: isOn() is asked of that state", listOf(answer, 1), listOf(ask.startRegister, ask.registerCount))
            assertEquals(isOn, ask.getReference<MethodReference>())
            assertEquals("$label: the answer is taken as before", answer, (after[at + 3] as OneRegisterInstruction).registerA)
            val pass = after[at + 4] as RegisterRangeInstruction
            assertEquals("$label: the stored switch goes to the extension", listOf(answer, 1), listOf(pass.startRegister, pass.registerCount))
            val target = pass.getReference<MethodReference>()!!
            assertEquals(listOf(VIDEO_AUTO_TRANSLATE, "commentDefault", "Z", listOf("Z")),
                listOf(target.definingClass, target.name, target.returnType, target.parameterTypes.map { it.toString() }))
            assertEquals("$label: and its answer is the default", answer, (after[at + 5] as OneRegisterInstruction).registerA)
        }
        return after
    }

    /** The value goes through the extension first and comes back in the same parameter. */
    private fun assertFiltered(version: String, constructor: Method) {
        val before = constructor.implementation!!.instructions.toList()
        val filtered = MutableMethod(constructor)
        filtered.publishStateThroughExtension()
        val after = filtered.implementation!!.instructions.toList()
        val value = constructor.implementation!!.registerCount - 2 // p1, after this

        val call = after[0] as RegisterRangeInstruction
        assertEquals("$version: the extension sees the value first", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(listOf(value, 1), listOf(call.startRegister, call.registerCount))
        val target = call.getReference<MethodReference>()!!
        assertEquals(listOf(VIDEO_AUTO_TRANSLATE, "publishedState", "Z", listOf("Z")),
            listOf(target.definingClass, target.name, target.returnType, target.parameterTypes.map { it.toString() }))
        assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$version: and its answer is the value", value, (after[1] as OneRegisterInstruction).registerA)
        assertEquals("$version: TikTok's constructor follows", before.map { it.opcode }, after.drop(2).map { it.opcode })
    }

    /** The getter in miniature: the key loaded, then the state object returned. */
    private fun state(
        static: Boolean = true,
        parameters: List<String> = listOf(),
        returns: String = STATE_CLASS,
        key: String = MASTER_TRANSLATION_KEY,
    ): Method {
        val instructions = listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        return method("LJII", static, parameters, returns, instructions)
    }

    /** The read as 47.4.3 has it past its cached answer: take the state, ask isOn(), return it. */
    private fun read(
        name: String = "LJI",
        getter: MethodReference = STATE,
        answer: String = "isOn",
        static: Boolean = true,
        returns: String = "Z",
        askedOf: Int = 0,
    ): Method {
        val instructions = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, getter),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, askedOf, 0, 0, 0, 0,
                ImmutableMethodReference(STATE_CLASS, answer, listOf(), "Z")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )
        return method(name, static, listOf(), returns, instructions)
    }

    /**
     * The comment switch's getter in miniature: its key loaded, a branch landing on the call, the
     * read asked, its answer taken into v0 and returned.
     */
    private fun comment(
        name: String = "LIZLLL",
        key: String = COMMENT_TRANSLATION_KEY,
        calls: MethodReference = READ,
        takesAnswer: Boolean = true,
        twice: Boolean = false,
    ): Method {
        val instructions = buildList {
            add(ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference(key)))
            add(ImmutableInstruction21t(Opcode.IF_EQZ, 1, 2))
            add(ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, calls))
            add(if (takesAnswer) ImmutableInstruction11x(Opcode.MOVE_RESULT, 0) else ImmutableInstruction10x(Opcode.NOP))
            if (twice) {
                add(ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, calls))
                add(ImmutableInstruction11x(Opcode.MOVE_RESULT, 1))
            }
            add(ImmutableInstruction11x(Opcode.RETURN, 0))
        }
        return method(name, true, listOf(), "Z", instructions)
    }

    /** Always translate's setter in miniature: the published state built, then the key stored. */
    private fun setter(
        parameters: List<String> = listOf("Ljava/lang/String;", "Z"),
        key: String = MASTER_TRANSLATION_KEY,
        stores: Boolean = true,
        alsoBuilds: String? = null,
    ): Method {
        val instructions = buildList {
            for (type in listOfNotNull(PUBLISHED, alsoBuilds)) {
                add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(type)))
                add(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 0, 1, 1, 0, 0,
                    ImmutableMethodReference(type, "<init>", listOf("Z", "Ljava/lang/String;"), "V")))
            }
            add(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key)))
            if (stores) {
                add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 1, 0, 1, 0, 0,
                    ImmutableMethodReference("Lcom/bytedance/keva/Keva;", "storeBoolean", listOf("Ljava/lang/String;", "Z"), "V")))
            }
            add(ImmutableInstruction10x(Opcode.RETURN_VOID))
        }
        return method("LJIILJJIL", true, parameters, "V", instructions)
    }

    /** The published state's constructor in miniature: the value stored, then return. */
    private fun publishedState(parameters: List<String> = listOf("Z", "Ljava/lang/String;")): Method {
        val instructions = listOf(
            ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 1, 0, ImmutableFieldReference(PUBLISHED, "LIZ", "Z")),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableMethod(
            PUBLISHED, "<init>", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
            ImmutableMethodImplementation(1 + parameters.size, instructions, null, null),
        )
    }

    /** The owner as 47.4.3 has it, cut down to the pieces the hook picks from. */
    private fun owner(): List<Method> = listOf(state(), read(), comment(), setter())

    private fun method(name: String, static: Boolean, parameters: List<String>, returns: String, instructions: List<Instruction>) =
        ImmutableMethod(
            OWNER, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            // Three registers, and the parameters on top of them, so v1 and v2 exist for the miniatures.
            ImmutableMethodImplementation(3 + parameters.size + (if (static) 0 else 1), instructions, null, null),
        )

    private companion object {
        const val OWNER = "LX/08Wk;"
        const val STATE_CLASS = "LX/0AZk;"
        const val PUBLISHED = "LX/0Ozi;"

        /** The captions' Keva service, whose one call to the read decides whether captions translate. */
        const val CAPTION_KEVA_SERVICE = "Lcom/ss/android/ugc/aweme/service/CaptionKevaServiceImpl;"
        val STATE = ImmutableMethodReference(OWNER, "LJII", listOf(), STATE_CLASS)
        val READ = ImmutableMethodReference(OWNER, "LJI", listOf(), "Z")
        val IS_ON = ImmutableMethodReference(STATE_CLASS, "isOn", listOf(), "Z")

        /** invoke, move-result, if-eqz, const/4, return, nop. */
        const val GUARD_SIZE = 6
    }
}
