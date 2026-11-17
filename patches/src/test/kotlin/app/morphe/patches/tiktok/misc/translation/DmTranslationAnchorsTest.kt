/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** No native call or live message is exercised by these read-only vendor fixtures. */
class DmTranslationAnchorsTest {
    companion object {
        private val cache = mutableMapOf<String, Map<String, ClassDef>>()
        private const val RESPONSE = "Lcom/ss/android/ugc/aweme/translation/model/MultiTranslationResultDM;"
        private const val RESULT = "Lcom/ss/android/ugc/aweme/translation/model/TranslationResultDM;"
    }

    @Test fun `the declared build has exactly the typed composer editor lifecycle`() {
        Fixtures.forEachDeclared { apk ->
            assertEquals("47.8.3", Fixtures.versionOf(apk))
            val owners = owners(apk)
            val targets = findDmPreviewTargets(owners::get)
            assertEquals(DM_ASSEM, targets.viewCreated.definingClass)
            assertEquals("onViewCreated", targets.viewCreated.name)
            assertEquals(DM_COMPOSER, targets.constructor.definingClass)
            assertEquals(9, targets.constructor.parameterTypes.size)
            assertEquals(15, targets.constructor.implementation!!.registerCount)
            assertEquals(DM_ABILITY, targets.constructor.parameterTypes[0].toString())
            assertEquals(DM_SESSION, targets.constructor.parameterTypes[4].toString())
            assertEquals("onCleared", targets.cleared.name)
            assertEquals(0, targets.editorStore.input)
            assertEquals(6, targets.editorStore.editor)
            assertEquals(DM_SESSION, owners.getValue("LX/12At;").superclass)
        }
    }

    @Test fun `capture calls follow real ownership stores and preserve every native lifecycle instruction`() {
        Fixtures.forEachDeclared { apk ->
            val targets = findDmPreviewTargets(owners(apk)::get)
            val editor = MutableMethod(targets.viewCreated)
            assertIntact(editor) { captureDmEditor(targets.editorStore) }
            val code = editor.implementation!!.instructions.toList()
            val store = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT &&
                it.getReference<FieldReference>()?.let { field -> field.definingClass == DM_INPUT && field.name == "LLJJIJIIJIL" } == true }
            val captured = code[store + 1]
            assertEquals("bindInput", captured.getReference<MethodReference>()!!.name)
            assertEquals(listOf("Ljava/lang/Object;", "Landroid/view/View;"),
                captured.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
            val registers = captured as FiveRegisterInstruction
            assertEquals(targets.editorStore.input, registers.registerC)
            assertEquals(targets.editorStore.editor, registers.registerD)
            code.forEachIndexed { index, instruction -> if (instruction.opcode == Opcode.RETURN_VOID) {
                val ready = code[index - 1]
                assertEquals("inputReady", ready.getReference<MethodReference>()!!.name)
                assertEquals(editor.implementation!!.registerCount - 2, (ready as RegisterRangeInstruction).startRegister)
                assertEquals(1, ready.registerCount)
            } }
            val composer = MutableMethod(targets.constructor)
            assertIntact(composer) { captureDmComposer() }
            val created = composer.implementation!!.instructions.single { it.getReference<MethodReference>()?.name == "composerCreated" }
            val args = created as FiveRegisterInstruction
            assertEquals(3, args.registerCount)
            val receiver = composer.implementation!!.registerCount - 10
            assertEquals(receiver, args.registerC)
            assertEquals(receiver + 1, args.registerD)
            assertEquals(receiver + 5, args.registerE)
            val cleared = MutableMethod(targets.cleared)
            assertIntact(cleared) { retireDmComposer() }
            assertEquals("composerCleared", cleared.implementation!!.instructions.first().getReference<MethodReference>()!!.name)
        }
    }

    @Test fun `the single chat scoped ability reaches the constructor as the actual InputFieldVM`() {
        Fixtures.forEachDeclared { apk ->
            val owners = owners(apk)
            assertTrue(DM_ABILITY in owners.getValue(DM_INPUT).interfaces)
            val scopeFactory = owners.getValue("LY/AObjectS181S0100000_28_I1;").methods.single { it.name == "invoke\$145" }
            assertTrue(scopeFactory.implementation!!.instructions.any { it.getReference<StringReference>() == null &&
                it.getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()?.type == DM_ABILITY })
            assertTrue(calls(scopeFactory).any { it.definingClass == "LX/04LO;" && it.name == "getAbility" })
            assertTrue(calls(scopeFactory).any { it.definingClass == "LX/03AM;" && it.name == "LLLLLLZZ" })
            val factory = owners.getValue("LX/03AM;").methods.single { it.name == "LLLLLLZZ" }
            val call = factory.implementation!!.instructions.single { it.getReference<MethodReference>()?.let { target ->
                target.definingClass == DM_COMPOSER && target.name == "<init>"
            } == true } as RegisterRangeInstruction
            assertEquals(10, call.registerCount)
            assertEquals(DM_ABILITY, factory.parameterTypes[0].toString())
            assertEquals(DM_SESSION, factory.parameterTypes[4].toString())
        }
    }

    @Test fun `the raw request has only text language and exclusion fields and exact response schemas`() {
        Fixtures.forEachDeclared { apk ->
            val owners = owners(apk)
            val request = owners.getValue("LX/16ZB;").methods.single { it.name == "LJ" }
            assertEquals("/aweme/v1/translation/dm/", annotation(request.annotations, "LX/05jG;"))
            assertTrue(request.annotations.any { it.type == "LX/08Eu;" })
            assertEquals(listOf("original_list", "target_lang", "dnt_lang_list"),
                request.parameters.map { annotation(it.annotations, "LX/05mF;") })
            assertEquals(List(3) { "Ljava/lang/String;" }, request.parameterTypes.map(CharSequence::toString))
            assertEquals("LX/03AS;", request.returnType)
            for ((owner, expected) in mapOf(
                RESPONSE to mapOf("statusCode" to "status_code", "statusMsg" to "status_msg", "results" to "translated_list"),
                RESULT to mapOf("translationStatusCode" to "translation_status_code", "originalText" to "original_text",
                    "translatedText" to "translated_text", "textExtraList" to "text_extra_list"),
                "Lcom/ss/android/ugc/aweme/translation/repo/BizCompTranslationContent;" to mapOf("originalText" to "original_text",
                    "sourceLang" to "source_lang", "textExtraList" to "text_extra_list"),
            )) {
                assertEquals(expected, owners.getValue(owner).fields.filter { it.name in expected }.associate { it.name to annotation(it.annotations, "LX/039y;") })
            }
        }
    }

    @Test fun `the API factory uses the native Retrofit origin and target and DNT come from the same service`() {
        Fixtures.forEachDeclared { apk ->
            val owners = owners(apk)
            val getter = owners.getValue("LX/1NQv;").methods.single { it.name == "LIZ" }
            assertTrue(getter.implementation!!.instructions.any { it.getReference<FieldReference>()?.definingClass == "LX/1NQw;" })
            val lazy = owners.getValue("LX/1NQw;").methods.single { it.name == "<clinit>" }
            assertTrue(calls(lazy).any { it.definingClass == "Lkotlin/jvm/internal/AFwS166S0000000_38_I1;" && it.name == "get\$arr\$" })
            val factory = owners.getValue("Lkotlin/jvm/internal/AFwS166S0000000_38_I1;").methods.single { it.name == "invoke\$476" }
            assertTrue(calls(factory).any { it.definingClass == "Lcom/ss/android/ugc/aweme/framework/services/ServiceManager;" && it.name == "getService" })
            assertTrue(factory.implementation!!.instructions.any { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == "LX/0UwZ;" && field.name == "LIZ" && field.type == "Ljava/lang/String;"
            } == true })
            assertTrue(calls(factory).any { it.definingClass == "Lcom/bytedance/ies/ugc/aweme/network/IRetrofitFactory;" && it.name == "LIZ" })
            assertTrue(calls(factory).any { it.definingClass == "LX/04VP;" && it.name == "create" })
            val origin = owners.getValue("LX/0UwZ;").methods.single { it.name == "<clinit>" }
            assertTrue(strings(origin).contains("https://"))
            assertTrue(origin.implementation!!.instructions.any { it.getReference<FieldReference>()?.definingClass == "LX/0366;" })
            val caller = owners.getValue("LX/1VPd;").methods.single { it.name == "LJIILJJIL" }
            assertTrue(calls(caller).any { it.definingClass == "LX/16ZB;" && it.name == "LJ" })
            assertTrue(calls(caller).any { it.definingClass == "LX/03q1;" && it.name == "LJZL" })
        }
    }

    @Test fun `both native adapters offer cancellable HTTP ownership before execute and enqueue`() {
        Fixtures.forEachDeclared { apk ->
            val owners = owners(apk)
            for ((adapter, operation, handle) in listOf(Triple("LX/05pW;", "execute", "LX/05ue;"), Triple("LX/08wf;", "enqueue", "LX/08wi;"))) {
                val subscribe = owners.getValue(adapter).methods.single { it.name == "LJLIIIL" }
                val calls = calls(subscribe)
                val before = calls.indexOfFirst { it.definingClass == "LX/03AV;" && it.name == "onSubscribe" }
                val after = calls.indexOfFirst { it.definingClass == "LX/05S6;" && it.name == operation }
                assertTrue(before >= 0 && after > before)
                val dispose = owners.getValue(handle).methods.single { it.name == "dispose" }
                assertEquals(listOf("LX/05S6;->cancel"), calls(dispose).map { "${it.definingClass}->${it.name}" })
                val isDisposed = owners.getValue(handle).methods.single { it.name == "isDisposed" }
                assertTrue(calls(isDisposed).any { it.definingClass == "LX/05S6;" && it.name == "isCanceled" })
            }
            for (name in listOf("onFailure", "onResponse")) {
                val callback = owners.getValue("LX/08wi;").methods.single { it.name == name }
                val methods = calls(callback)
                assertTrue(methods.indexOfFirst { it.name == "isCanceled" } in 0 until methods.indexOfFirst { it.definingClass == "LX/03AV;" })
            }
        }
    }

    @Test fun `native Send still owns message construction and the ordinary text watcher only updates input state`() {
        Fixtures.forEachDeclared { apk ->
            val owners = owners(apk)
            val send = owners.getValue(DM_COMPOSER).methods.single { it.name == "N43" }
            assertTrue(calls(send).any { it.definingClass == DM_ABILITY && it.name == "h31" })
            assertTrue(calls(send).any { it.definingClass == "LX/12CE;" && it.name == "tX" })
            // R8 merged the text watchers into one switch class (LX/12L3; on 47.8.3); afterTextChanged dispatches this composer's case to $3.
            val watcher = owners.getValue("LX/12L3;").methods.single { it.name == "afterTextChanged\$3" }
            assertTrue(calls(watcher).any { it.definingClass == DM_ASSEM && it.name == "hq" })
            assertFalse(calls(watcher).any { it.name in setOf("N43", "tX", "sq", "I43") })
            // 47.8.3 renamed the assembly's send from hq to sq; the CharSequence parameter is what pins it.
            val assemblySend = owners.getValue("Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/base/assems/input/sendmsg/SendMessageAssem;").methods.single { it.name == "sq" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;") }
            assertTrue(calls(assemblySend).any { it.name == "I43" })
            assertFalse(listOf(findDmPreviewTargets(owners::get).viewCreated, findDmPreviewTargets(owners::get).constructor).contains(send))
        }
    }

    @Test fun `duplicate editor stores a changed constructor and a wrong session fail before any hook`() {
        Fixtures.forEachDeclared { apk ->
            val owners = owners(apk)
            val original = findDmPreviewTargets(owners::get)
            val duplicate = MutableMethod(original.viewCreated)
            duplicate.addInstructions(0, "iget-object v1, p0, $DM_ASSEM->LLJJIJIL:LX/032f;\niput-object v1, v0, $DM_INPUT->LLJJIJIIJIL:Landroid/widget/EditText;")
            rejected { duplicate.dmEditorStore() }
            val input = owners.getValue(DM_INPUT)
            val changed = copy(input, fields = input.fields.filterNot { it.name == "LLJJIJIIJIL" })
            rejected { findDmPreviewTargets { if (it == DM_INPUT) changed else owners[it] } }
            val composer = owners.getValue(DM_COMPOSER)
            val wrongConstructor = MutableMethod(original.constructor)
            val sessionStore = wrongConstructor.implementation!!.instructions.indexOfFirst {
                it.getReference<FieldReference>()?.let { field -> field.definingClass == DM_COMPOSER &&
                    field.name == "LLJJLIIIJLLLLLLLZ" && field.type == DM_SESSION } == true
            }
            assertTrue(sessionStore >= 0)
            wrongConstructor.replaceInstruction(sessionStore, "iput-object p4, p0, $DM_COMPOSER->LLJJLIIIJLLLLLLLZ:$DM_SESSION")
            val changedComposer = copy(composer, methods = composer.methods.filterNot { it == original.constructor } + wrongConstructor)
            assertEquals(1, changedComposer.methods.count { it.name == "<init>" })
            val changedStore = changedComposer.methods.single { it.name == "<init>" }.implementation!!.instructions.elementAt(sessionStore)
            assertEquals(wrongConstructor.implementation!!.registerCount - 6, (changedStore as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA)
            rejected { findDmPreviewTargets { if (it == DM_COMPOSER) changedComposer else owners[it] } }
            val wrongSession = copy(owners.getValue("LX/12At;"), superclass = "Ljava/lang/Object;")
            rejected { findDmPreviewTargets { if (it == wrongSession.type) wrongSession else owners[it] } }
        }
    }

    private fun assertIntact(method: MutableMethod, change: MutableMethod.() -> Unit) {
        val registers = method.implementation!!.registerCount
        val tries = method.implementation!!.tryBlocks.size
        fun signature(method: Method) = method.implementation!!.instructions.filterNot { it.getReference<MethodReference>()?.definingClass == DM_PREVIEW }
            .map { it.opcode to (it.getReference<MethodReference>()?.toString() ?: it.getReference<FieldReference>()?.toString() ?: it.getReference<StringReference>()?.string) }
        val before = signature(method)
        method.change()
        assertEquals(registers, method.implementation!!.registerCount)
        assertEquals(tries, method.implementation!!.tryBlocks.size)
        assertEquals(before, signature(method))
    }

    private fun annotation(annotations: Set<Annotation>, type: String): String =
        ((annotations.single { it.type == type }.elements.single { it.name == "value" }).value as StringEncodedValue).value

    private fun calls(method: Method) = method.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
    private fun strings(method: Method) = method.implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string }.toSet()
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Changed native ownership was accepted") } catch (expected: PatchException) {}
    }

    private fun copy(owner: ClassDef, superclass: String? = owner.superclass,
        fields: Iterable<com.android.tools.smali.dexlib2.iface.Field> = owner.fields,
        methods: Iterable<Method> = owner.methods): ClassDef = ImmutableClassDef(owner.type, owner.accessFlags, superclass,
            owner.interfaces, owner.sourceFile, owner.annotations, fields, methods)

    private fun owners(apk: File): Map<String, ClassDef> = cache.getOrPut(apk.canonicalPath) {
        val container = Fixtures.dexContainer(apk)
        val result = mutableMapOf<String, ClassDef>()
        for (entry in container.dexEntryNames) {
            for (owner in container.getEntry(entry)!!.dexFile.classes) result[owner.type] = owner
        }
        result
    }
}
