/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sensitivewarnings

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONTROLS = "Lapp/morphe/extension/tiktok/feed/SensitiveWarnings;"
private const val SEND_TASK = "Lcom/ss/android/ugc/aweme/im/message/template/service/SendMessageTemplateTask;"

/**
 * What hides the caution strip on a video shared into a chat (#258), held to each declared build:
 * one void method reads both labels from the card's extras, and after the patch each read goes
 * through chatCardLabel into the same register before anything else touches it.
 */
class ChatCardCautionAnchorsTest {
    @Test
    fun `each declared build has one chat card reading both labels and every label read passes through the switch`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = mutableListOf<Method>()
            val naming = mutableListOf<Naming>()
            walk(apk) { classDef, method ->
                val code = method.implementation ?: return@walk
                if (code.instructions.any { it.getReference<StringReference>()?.string == CHAT_CARD_LABEL_KEYS[0] }) {
                    naming += Naming(classDef, method)
                }
                if (ChatCardCautionFingerprint.takes(method, classDef)) taken += method
            }
            assertEquals("$version: taken ${taken.map { it.definingClass + "->" + it.name }} of $naming", 1, taken.size)
            // The writer (the share's send task) and the card are the only two that name the key
            // and run. 47.7.3 also ships the chat video stack's member card (0rT7->LJ()Z), which
            // reads both labels only to test them for emptiness, and which nothing in the build
            // calls: neither it, its interface 0rE5, nor the IVideoStackMemberCardService that
            // builds it is ever invoked. Any other method naming the key has to be that dead, so
            // the first build that wires the stack up fails here instead of showing the strip.
            val native = taken.single()
            val others = naming.filter {
                !(it.owners.first() == native.definingClass && it.name == native.name && it.parameters == native.parameterTypes.map(CharSequence::toString))
            }
            val called = invoked(apk, others.filter { it.returnType != SEND_TASK })
            val dead = others.filter { it.returnType != SEND_TASK && it !in called }
            assertEquals("$version: methods naming the caution key $naming, never called $dead", 2, naming.size - dead.size)
            assertEquals("$version: share writers naming the caution key $others", 1, others.count { it.returnType == SEND_TASK })

            val card = MutableMethod(taken.single())
            val before = card.implementation!!.instructions.size
            card.hideChatCardLabels("Skip content warnings")
            val after = card.implementation!!.instructions.toList()
            assertEquals("$version: two instructions per label", before + 4, after.size)
            for (key in CHAT_CARD_LABEL_KEYS) {
                val at = after.indexOfFirst { it.getReference<StringReference>()?.string == key }
                val read = (after[at + 2] as OneRegisterInstruction).registerA
                val call = after[at + 3]
                assertEquals("$version $key", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                val ref = call.getReference<MethodReference>()!!
                assertEquals(CONTROLS, ref.definingClass)
                assertEquals("chatCardLabel", ref.name)
                assertEquals(read, (call as RegisterRangeInstruction).startRegister)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, after[at + 4].opcode)
                assertEquals(read, (after[at + 4] as OneRegisterInstruction).registerA)
            }
        }
    }

    @Test
    fun `only a void method that gets both keys from a map is the card`() {
        fun method(lines: List<String>, returns: String = "V") = MutableMethod(
            ImmutableMethod(
                "Lfixture/Card;", "ho", listOf(ImmutableMethodParameter("Ljava/util/Map;", null, null)), returns,
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(6, emptyList(), null, null),
            ),
        ).apply {
            addInstructions(0, (lines + if (returns == "V") "return-void" else "const/4 v0, 0x0\nreturn-object v0").joinToString("\n"))
        }
        fun reads(key: String, register: Int) = listOf(
            "const-string v0, \"$key\"",
            "invoke-interface {p1, v0}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
            "move-result-object v$register",
        )
        val both = reads(CHAT_CARD_LABEL_KEYS[0], 2) + reads(CHAT_CARD_LABEL_KEYS[1], 1)

        assertTrue(isChatCardCaution(method(both)))
        assertFalse(isChatCardCaution(method(reads(CHAT_CARD_LABEL_KEYS[0], 2))))
        assertFalse(isChatCardCaution(method(both, returns = "Ljava/lang/Object;")))
        // Writing the key (a put) is the sender's side, not the card.
        val writes = listOf(
            "const-string v0, \"${CHAT_CARD_LABEL_KEYS[0]}\"",
            "invoke-interface {p1, v0, v0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "move-result-object v2",
        ) + reads(CHAT_CARD_LABEL_KEYS[1], 1)
        assertFalse(isChatCardCaution(method(writes)))

        val tight = method(reads(CHAT_CARD_LABEL_KEYS[0], 2))
        val untouched = tight.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { tight.hideChatCardLabels("Skip content warnings") }
        assertEquals(untouched, tight.implementation!!.instructions.toList())
    }

    /** A method naming the caution key, with every type a call to it could name. */
    private class Naming(classDef: ClassDef, method: Method) {
        val owners = listOfNotNull(classDef.type, classDef.superclass) + classDef.interfaces
        val name: String = method.name
        val parameters = method.parameterTypes.map(CharSequence::toString)
        val returnType: String = method.returnType
        override fun toString() = "${owners.first()}->$name"
    }

    /** Which of [methods] something in the build invokes, on its own class or a supertype. */
    private fun invoked(apk: File, methods: List<Naming>): Set<Naming> {
        if (methods.isEmpty()) return emptySet()
        val found = HashSet<Naming>()
        walk(apk) { _, method ->
            for (instruction in method.implementation?.instructions ?: return@walk) {
                val reference = instruction.getReference<MethodReference>() ?: continue
                for (target in methods) {
                    if (reference.name == target.name && reference.returnType == target.returnType &&
                        reference.definingClass in target.owners &&
                        reference.parameterTypes.map(CharSequence::toString) == target.parameters
                    ) found += target
                }
            }
        }
        return found
    }

    /** Every method of one build, walked once and never held (the dex has millions). */
    private fun walk(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) visit(classDef, method)
            }
        }
    }
}
