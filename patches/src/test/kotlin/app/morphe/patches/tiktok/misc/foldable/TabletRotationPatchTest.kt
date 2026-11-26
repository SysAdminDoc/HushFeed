/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.foldable

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A profile opened from search and a comment's picture ask the extension how to turn (#490). */
class TabletRotationPatchTest {
    private fun classesOf(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }

    @Test
    fun `each declared build has TikTok's landscape check and the profile's onCreate`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk).values
            for (fingerprint in listOf(SupportLandscapeFingerprint, ProfileHostCreateFingerprint)) {
                val taken = classes.flatMap { classDef ->
                    classDef.methods.filter { fingerprint.takes(it, classDef) }.map { "${classDef.type}->${it.name}" }
                }
                assertEquals("$version ${fingerprint.javaClass.simpleName}", 1, taken.size)
            }
        }
    }

    @Test
    fun `each declared build's profile asks to follow the device on every way out of onCreate`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val profile = classesOf(apk)[PROFILE_HOST_ACTIVITY] ?: return@forEachDeclared fail("$version has no profile host")
            val create = MutableClass(profile).methods.single { ProfileHostCreateFingerprint.takes(it, profile) }
            val stock = create.implementation!!.instructions.map { it.opcode }
            val returns = stock.count { it == Opcode.RETURN_VOID }

            create.followAfterCreate()

            val code = create.implementation!!.instructions.toList()
            val follows = code.indices.filter { code[it].calls("follow") }
            assertEquals(version, returns, follows.size)
            follows.forEach { at ->
                assertEquals("$version: the follow call isn't right before a return", Opcode.RETURN_VOID, code[at + 1].opcode)
                val range = code[at] as RegisterRangeInstruction
                assertEquals("$version: the follow call doesn't take this", create.implementation!!.registerCount - 2, range.startRegister)
                assertEquals(1, range.registerCount)
            }
            assertEquals("$version: TikTok's own onCreate changed", stock, code.filterIndexed { i, _ -> i !in follows }.map { it.opcode })
        }
    }

    @Test
    fun `each declared build's comment picture asks the extension before every orientation request`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val photo = classesOf(apk)[COMMENT_PHOTO_ACTIVITY] ?: return@forEachDeclared fail("$version has no comment picture")
            val requests = commentPhotoRequests(MutableClass(photo).methods)
            val stockOverride = requests.override.implementation!!.instructions.toList()
            val superCalls = superOrientationRequests(requests.override).map { stockOverride[it] as FiveRegisterInstruction }
            val stockDirect = requests.direct.mapValues { (method, _) -> method.implementation!!.instructions.map { it.opcode } }

            requests.override.askThroughTabletRotation()
            requests.direct.forEach { (method, indexes) ->
                indexes.sortedDescending().forEach { method.requestThroughOverride(it, COMMENT_PHOTO_ACTIVITY) }
            }

            val override = requests.override.implementation!!.instructions.toList()
            assertEquals("$version: two lines per request", stockOverride.size + 2 * superCalls.size, override.size)
            val asks = superOrientationRequests(requests.override)
            assertEquals("$version: the override's requests", superCalls.size, asks.size)
            asks.zip(superCalls).forEach { (at, superCall) ->
                val ask = override[at - 2]
                assertTrue("$version: the override doesn't ask the extension first", ask.calls("orientation"))
                ask as FiveRegisterInstruction
                assertEquals(listOf(superCall.registerC, superCall.registerD), listOf(ask.registerC, ask.registerD))
                assertEquals(Opcode.MOVE_RESULT, override[at - 1].opcode)
                assertEquals("$version: the answer isn't what the override hands its superclass",
                    superCall.registerD, (override[at - 1] as OneRegisterInstruction).registerA)
            }

            requests.direct.forEach { (method, indexes) ->
                val code = method.implementation!!.instructions.toList()
                assertEquals("$version ${method.name} still asks its superclass", emptyList<Int>(), superOrientationRequests(method))
                indexes.forEach { at ->
                    val call = code[at]
                    assertEquals(Opcode.INVOKE_VIRTUAL, call.opcode)
                    val target = call.getReference<MethodReference>()!!
                    assertEquals("$version ${method.name}", COMMENT_PHOTO_ACTIVITY to "setRequestedOrientation",
                        target.definingClass to target.name)
                }
                val kept = code.map { if (it.opcode == Opcode.INVOKE_VIRTUAL && it.calls("setRequestedOrientation")) Opcode.INVOKE_SUPER else it.opcode }
                assertEquals("$version ${method.name} changed beyond its requests", stockDirect.getValue(method), kept)
            }
        }
    }

    @Test
    fun `a comment picture that moved on fails the patch`() {
        val ok = request("setRequestedOrientation", "const/4 v0, 0x1\ninvoke-super {p0, v0}, LX/Base;->setRequestedOrientation(I)V\nreturn-void")
        val direct = request("onResume", "const/4 v0, 0x1\ninvoke-super {p0, v0}, LX/Base;->setRequestedOrientation(I)V\nreturn-void", params = emptyList())
        val plain = request("onResume", "return-void", params = emptyList())
        val noSuper = request("setRequestedOrientation", "return-void")
        // 48.0.2's shape: hand on what it's asked when TikTok lets the screen turn, portrait otherwise.
        val gated = request("setRequestedOrientation", "if-eqz p1, :portrait\ninvoke-super {p0, p1}, LX/Base;->setRequestedOrientation(I)V\nreturn-void\n" +
            ":portrait\nconst/4 v0, 0x1\ninvoke-super {p0, v0}, LX/Base;->setRequestedOrientation(I)V\nreturn-void")
        val thrice = request("setRequestedOrientation", List(3) { "invoke-super {p0, p1}, LX/Base;->setRequestedOrientation(I)V" }.joinToString("\n") + "\nreturn-void")
        assertEquals(listOf(direct), commentPhotoRequests(listOf(ok, direct)).direct.keys.toList())
        assertEquals(listOf(1, 4), superOrientationRequests(commentPhotoRequests(listOf(gated, direct)).override))
        gated.askThroughTabletRotation()
        val asked = gated.implementation!!.instructions.toList()
        assertEquals("both requests ask the extension", listOf(3, 8), superOrientationRequests(gated))
        assertTrue(asked[1].calls("orientation") && asked[6].calls("orientation"))
        for (methods in listOf(listOf(direct), listOf(noSuper, direct), listOf(ok, plain), listOf(thrice, direct))) {
            try {
                commentPhotoRequests(methods)
                fail("took ${methods.map { it.name }}")
            } catch (expected: PatchException) {
            }
        }
    }

    /**
     * The stub as the bundle carries it: D8 answers its `false` through p0 and gives it no
     * register of its own, which a write into v0 would have landed on.
     */
    @Test
    fun `the bridge built on the bundled stub answers with TikTok's own check`() {
        val stub = extensionStub()
        val check = ImmutableMethod("LX/078q;", "LIZ", stub.parameters, "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null)
        val bridge = landscapeBridge(stub, check)
        val code = bridge.implementation!!.instructions.toList()
        val registers = bridge.implementation!!.registerCount
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("LX/078q;->LIZ", code[0].getReference<MethodReference>()!!.let { "${it.definingClass}->${it.name}" })
        val call = code[0] as RegisterRangeInstruction
        assertEquals("the check doesn't get the activity", (registers - 1) to 1, call.startRegister to call.registerCount)
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.RETURN), code.subList(1, 3).map { it.opcode })
        val answer = (code[1] as OneRegisterInstruction).registerA
        assertTrue("the answer lands on the activity", answer < registers - 1)
        assertEquals(answer, (code[2] as OneRegisterInstruction).registerA)

        val instance = ImmutableMethod("LX/078q;", "LIZ", check.parameters, "Z", AccessFlags.PUBLIC.value, null, null, null)
        try {
            landscapeBridge(stub, instance)
            fail("wrote the bridge onto an instance method")
        } catch (expected: PatchException) {
        }
    }

    private fun extensionStub(): Method {
        val stream = javaClass.getResourceAsStream("/extensions/tiktok.mpe") ?: throw AssertionError("no built extension on the test classpath")
        val file = java.io.File.createTempFile("tiktok-extension", ".dex").apply { deleteOnExit() }
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        val container = Fixtures.dexContainer(file, Opcodes.getDefault())
        val extension = container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .single { it.type == TABLET_ROTATION_EXTENSION }
        return extension.methods.single { it.name == "tikTokSupportsLandscape" }
    }

    private fun Instruction.calls(name: String) =
        getReference<MethodReference>()?.let { it.definingClass == TABLET_ROTATION_EXTENSION && it.name == name } == true ||
            (name == "setRequestedOrientation" && getReference<MethodReference>()?.name == name)

    private fun request(name: String, smali: String, params: List<String> = listOf("I")) = MutableMethod(
        ImmutableMethod(COMMENT_PHOTO_ACTIVITY, name, params.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(1 + 1 + params.size, emptyList(), null, null)),
    ).apply { addInstructionsWithLabels(0, smali) }
}
