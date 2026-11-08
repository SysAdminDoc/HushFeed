package app.morphe.patches.tiktok.misc.comment

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Create stickers (#156, #88): the upload config, the server parameter, the canvas saver and the
 * Saved page (182P, 182R, 14hO on 47.5.3). TikTok's fixtures hold what the patch relies on.
 */
class StickerCreatorPatchTest {
    private val fingerprints = listOf(
        FavouriteStickerPageFingerprint,
        StickerUploadConfigFingerprint,
        StickerServerParameterFingerprint,
        CanvasStickerSaverFingerprint,
    )

    @Test
    fun `each fingerprint takes one method in every retained fixture`() {
        fixtures { apk, classes ->
            for (fingerprint in fingerprints) {
                assertEquals("$apk: ${fingerprint.javaClass.simpleName}", 1, taken(fingerprint, classes).size)
            }
        }
    }

    @Test
    fun `the bridges call what the canvas saver calls and pass the verifier's typing`() {
        val lines = mapOf("uploadConfig" to 4, "uploadServerParameter" to 3, "saveSticker" to 6, "addToSaved" to 8, "continuationType" to 2)
        fixtures { apk, classes ->
            val calls = stickerCreatorCalls(
                taken(StickerUploadConfigFingerprint, classes).single(),
                taken(StickerServerParameterFingerprint, classes).single(),
                taken(CanvasStickerSaverFingerprint, classes).single(),
            )
            val methods = classes.flatMap { classDef -> classDef.methods.map { it.signature() } }.toSet()
            val fields = classes.flatMap { classDef -> classDef.fields.map { "${it.definingClass}->${it.name}:${it.type}" } }.toSet()
            val bridges = stickerCreatorBridges(calls)
            assertEquals(apk, lines.keys, bridges.map { it.name }.toSet())
            for (bridge in bridges) {
                assertEquals("$apk: ${bridge.name} keeps every line", lines.getValue(bridge.name), bridge.implementation!!.instructions.count())
                assertEquals("$apk: ${bridge.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
                for (reference in bridge.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference }) {
                    when (reference) {
                        is MethodReference -> assertTrue("$apk: ${bridge.name} calls ${reference.signature()}", reference.signature() in methods)
                        is FieldReference -> assertTrue("$apk: ${bridge.name} reads ${reference.name}", "${reference.definingClass}->${reference.name}:${reference.type}" in fields)
                    }
                }
            }
            assertTrue("$apk: the continuation ${calls.continuation}", classes.any { it.type == calls.continuation })
        }
    }

    /** The shipped dex has R8's public static stubs, not the source's package-private ones. */
    @Test
    fun `the bridges replace public static stubs with their flags kept and refuse an instance stub`() {
        val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        fixtures { apk, classes ->
            val calls = stickerCreatorCalls(
                taken(StickerUploadConfigFingerprint, classes).single(),
                taken(StickerServerParameterFingerprint, classes).single(),
                taken(CanvasStickerSaverFingerprint, classes).single(),
            )
            val stubs = stickerCreatorBridges(calls) { publicStatic }
            val extension = MutableClass(stickerCreator(stubs))
            extension.swapStickerStubs(calls)
            assertEquals(apk, stubs.map { it.name }.toSet(), extension.methods.map { it.name }.toSet())
            for (bridge in extension.methods) {
                assertEquals("$apk: ${bridge.name}", publicStatic, bridge.accessFlags)
                assertTrue("$apk: ${bridge.name} is the bridge", stubs.none { stub -> stub === bridge })
            }

            val instance = stickerCreatorBridges(calls) { name ->
                if (name == "addToSaved") AccessFlags.PUBLIC.value else publicStatic
            }
            val refused = assertThrows(PatchException::class.java) {
                MutableClass(stickerCreator(instance)).swapStickerStubs(calls)
            }
            assertEquals(apk, "Create stickers: the addToSaved stub isn't static", refused.message)
        }
    }

    private fun stickerCreator(methods: List<MutableMethod>) = ImmutableClassDef(
        STICKER_CREATOR, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
        emptyList(), null, emptySet(), emptyList(), methods,
    )

    @Test
    fun `the Saved page goes to StickerCreator right before it's returned`() {
        fixtures { apk, classes ->
            val method = MutableMethod(taken(FavouriteStickerPageFingerprint, classes).single())
            method.addStickerCreatorButton()
            val code = method.implementation!!.instructions.toList()
            val returned = code.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            assertHook(apk, code[returned - 1], (code[returned] as OneRegisterInstruction).registerA)
            assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }

    @Test
    fun `a branch onto the page's return reaches the hook too`() {
        val method = MutableMethod(
            ImmutableMethod(
                "LX/Page;", "onCreateItemView", listOf(ImmutableMethodParameter("Landroid/view/ViewGroup;", null, null)),
                "Landroid/view/View;", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        )
        method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                if-eqz p1, :done
                move-object v0, p1
                :done
                return-object v0
            """,
        )
        method.addStickerCreatorButton()
        val code = method.implementation!!.instructions.toList()
        val branch = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        val target = branch.target.location.index
        assertHook("branch", code[target], 0)
        assertEquals(Opcode.RETURN_OBJECT, code[target + 1].opcode)
    }

    @Test
    fun `the Stickers tab gate's owned answer goes through ProfileStickerTab with the profile, on every path to that return`() {
        fixtures { apk, classes ->
            val gates = taken(ProfileStickerTabFingerprint, classes)
            assertEquals("$apk: Stickers tab gates", 1, gates.size)
            val method = MutableMethod(gates.single())
            val stockReturns = method.implementation!!.instructions.count { it.opcode == Opcode.RETURN }
            method.keepProfileStickerTab()
            val code = method.implementation!!.instructions.toList()
            val hooks = code.indices.filter {
                val call = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
                call != null && call.definingClass == PROFILE_STICKER_TAB
            }
            assertEquals("$apk: one hook", 1, hooks.size)
            val hook = code[hooks.single()]
            val call = (hook as ReferenceInstruction).reference as MethodReference
            assertEquals(apk, "show(ILjava/lang/Object;)Z", "${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
            val registers = hook as FiveRegisterInstruction
            val result = (code[hooks.single() + 1] as OneRegisterInstruction).registerA
            assertEquals(apk, Opcode.MOVE_RESULT, code[hooks.single() + 1].opcode)
            assertEquals("$apk: owned answer in, the profile second", result to method.implementation!!.registerCount - 1,
                registers.registerC to registers.registerD)
            assertEquals(apk, Opcode.RETURN, code[hooks.single() + 2].opcode)
            assertEquals("$apk: returns what the hook said", result, (code[hooks.single() + 2] as OneRegisterInstruction).registerA)
            assertEquals("$apk: no return added or lost", stockReturns, code.count { it.opcode == Opcode.RETURN })
            for (branch in code.filterIsInstance<BuilderOffsetInstruction>()) {
                assertTrue("$apk: nothing jumps past the hook to the return", branch.target.location.index != hooks.single() + 2)
            }
            assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }

    @Test
    fun `a gate that writes over the profile before the owned return stops the patch`() {
        val method = MutableMethod(
            ImmutableMethod(
                "LX/Gate;", "LIZ", listOf(ImmutableMethodParameter("Lcom/ss/android/ugc/aweme/profile/model/User;", null, null)),
                "Z", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        )
        method.addInstructions(
            0,
            """
                sget-object v0, Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;->LIZ:Ljava/lang/Object;
                check-cast v0, Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;
                invoke-interface { v0 }, Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;->getImStickerStoreService()LX/Store;
                move-result-object v0
                const/4 p0, 0x0
                invoke-interface { v0, p0 }, LX/Store;->LJIILIIL(Ljava/lang/String;)Z
                move-result v1
                return v1
            """,
        )
        val refused = assertThrows(PatchException::class.java) { method.keepProfileStickerTab() }
        assertEquals("Create stickers: the Stickers tab gate writes over the profile", refused.message)
    }

    @Test
    fun `the Stickers tab page's curated set answer goes through ProfileStickerTab with the uid the curated page gets`() {
        fixtures { apk, classes ->
            val pages = taken(ProfileStickerPageFingerprint, classes)
            assertEquals("$apk: Stickers tab pages", 1, pages.size)
            val method = MutableMethod(pages.single())
            method.keepProfileStickerPage()
            val code = method.implementation!!.instructions.toList()
            val hooks = code.indices.filter {
                val call = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
                call != null && call.definingClass == PROFILE_STICKER_TAB
            }
            assertEquals("$apk: one hook", 1, hooks.size)
            val hook = hooks.single()
            val call = (code[hook] as ReferenceInstruction).reference as MethodReference
            assertEquals(apk, "curatedPage(ILjava/lang/String;)Z", "${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
            val check = (code[hook - 2] as ReferenceInstruction).reference as MethodReference
            assertEquals("$apk: right after the store's Z(Z) check", "(Z)Z", "(${check.parameterTypes.joinToString("")})${check.returnType}")
            assertEquals(apk, Opcode.MOVE_RESULT, code[hook - 1].opcode)
            val result = (code[hook - 1] as OneRegisterInstruction).registerA
            assertEquals(apk, Opcode.MOVE_RESULT, code[hook + 1].opcode)
            assertEquals("$apk: the answer goes back where TikTok reads it", result, (code[hook + 1] as OneRegisterInstruction).registerA)
            val curated = (hook + 2 until code.size).first {
                val page = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
                page != null && page.returnType == "Landroidx/fragment/app/Fragment;" && page.parameterTypes.size == 3
            }
            assertTrue("$apk: TikTok branches on the answer before the curated page", (hook + 2 until curated).any {
                code[it].opcode == Opcode.IF_EQZ && (code[it] as OneRegisterInstruction).registerA == result
            })
            val registers = code[hook] as FiveRegisterInstruction
            assertEquals("$apk: the answer first, then the uid the curated page gets", result to (code[curated] as FiveRegisterInstruction).registerD,
                registers.registerC to registers.registerD)
            assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(method))
        }
    }

    @Test
    fun `a Stickers tab page that writes over the uid before the curated page stops the patch`() {
        val refused = assertThrows(PatchException::class.java) {
            stickerPage(
                """
                    const/4 v1, 0x0
                    if-eqz v0, :old
                """,
            ).keepProfileStickerPage()
        }
        assertEquals("Create stickers: the Stickers tab page writes over the uid before the curated set page", refused.message)
    }

    @Test
    fun `a branch onto the code right after the curated set check stops the patch`() {
        val refused = assertThrows(PatchException::class.java) {
            stickerPage(
                """
                    :again
                    if-eqz v0, :old
                """,
                "if-nez v3, :again",
            ).keepProfileStickerPage()
        }
        assertEquals("Create stickers: something lands right after the curated set check", refused.message)
    }

    /** The page's shape: store check into v0, [between], the curated page with the uid in v1, [after]. */
    private fun stickerPage(between: String, after: String = "") = MutableMethod(
        ImmutableMethod(
            PROFILE_STICKER_PAGE, "onViewCreated",
            listOf(ImmutableMethodParameter("Landroid/view/View;", null, null), ImmutableMethodParameter("Landroid/os/Bundle;", null, null)),
            "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(7, emptyList(), null, null),
        ),
    ).apply {
        addInstructions(
            0,
            """
                sget-object v0, Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;->LIZ:Ljava/lang/Object;
                check-cast v0, Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;
                invoke-interface { v0 }, Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;->getImStickerStoreService()LX/Store;
                move-result-object v2
                const-string v1, "uid"
                const/4 v3, 0x0
                invoke-interface { v2, v3 }, LX/Store;->LJIIL(Z)Z
                move-result v0
                $between
                invoke-interface { v2, v1, v1, v3 }, LX/Store;->LJJIIJ(Ljava/lang/String;Ljava/lang/String;Z)Landroidx/fragment/app/Fragment;
                move-result-object v3
                $after
                :old
                return-void
            """,
        )
    }

    private fun assertHook(what: String, hook: Instruction, register: Int) {
        val target = (hook as ReferenceInstruction).reference as MethodReference
        val range = hook as RegisterRangeInstruction
        assertEquals(what, Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals(what, "$STICKER_CREATOR->onFavouritesPage", "${target.definingClass}->${target.name}")
        assertEquals(what, register to 1, range.startRegister to range.registerCount)
    }

    private fun taken(fingerprint: Fingerprint, classes: List<ClassDef>) =
        classes.flatMap { classDef -> classDef.methods.filter { fingerprint.takes(it, classDef) } }

    private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun fixtures(check: (String, List<ClassDef>) -> Unit) {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            check(apk.name, container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes })
        }
    }
}
