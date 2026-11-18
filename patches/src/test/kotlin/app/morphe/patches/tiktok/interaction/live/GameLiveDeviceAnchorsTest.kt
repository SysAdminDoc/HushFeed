/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.spoof.region.COMMON_PARAMS_HANDLER
import app.morphe.patches.tiktok.misc.spoof.region.CommonParamsBuilderFingerprint
import app.morphe.patches.tiktok.misc.spoof.region.CommonParamsHandlerFingerprint
import app.morphe.patches.tiktok.misc.spoof.region.commonParamsFill
import app.morphe.takes
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report a newer phone to game LIVE's anchors on each declared build (issue #459): the LIVE API
 * still asks for the three setup paths GameLiveDevice.SETUP_PATHS names, the common-parameter
 * builder still puts the phone's model and brand under the two keys it swaps, and the hook lands
 * around the handler's fill, alone or next to Region spoof's hook in either order.
 */
class GameLiveDeviceAnchorsTest {
    @Test
    fun `the LIVE API still asks for the setup paths, create_info for the room's qualities`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val signatures = HashMap<String, MutableList<String>>()
            forEachMethod(apk) { _, method ->
                if (method.annotations.isEmpty()) return@forEachMethod
                val path = method.annotations.flatMap { it.elements }
                    .mapNotNull { (it.value as? StringEncodedValue)?.value }
                    .firstOrNull { it in SETUP_PATHS } ?: return@forEachMethod
                val signature = method.annotations.firstOrNull { it.type == SIGNATURE }?.elements
                    ?.firstOrNull { it.name == "value" }?.value as? ArrayEncodedValue
                signatures.getOrPut(path) { ArrayList() } +=
                    signature?.value.orEmpty().joinToString("") { (it as StringEncodedValue).value }
            }
            for (path in SETUP_PATHS) assertTrue("$version: no LIVE API call for $path", path in signatures)
            assertTrue(
                "$version: create_info no longer answers with the room's create info: ${signatures[CREATE_INFO]}",
                signatures[CREATE_INFO]!!.any { ROOM_CREATE_INFO in it },
            )
            val model = classOf(apk, ROOM_CREATE_INFO) ?: error("$version: no $ROOM_CREATE_INFO")
            assertTrue(
                "$version: the room's create info has no quality map",
                model.fields.any { it.name == "pushStreamInfoMap" && it.type == "Ljava/util/Map;" },
            )
        }
    }

    @Test
    fun `the common-parameter builder puts the phone's model and brand under the swapped keys`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val builders = ArrayList<Method>()
            forEachMethod(apk) { classDef, method ->
                // The parameter shape first: the strings check reads the whole body.
                if (method.parameterTypes.size == 4 && method.parameterTypes[2] == "Ljava/util/Map;" &&
                    CommonParamsBuilderFingerprint.takes(method, classDef)
                ) {
                    builders += method
                }
            }
            assertEquals("$version: common-parameter builders matched", 1, builders.size)
            val code = builders.single().implementation!!.instructions.toList()
            for (key in listOf("device_type", "device_brand")) {
                val at = code.indexOfFirst {
                    it.opcode.let { op -> op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO } &&
                        it.getReference<StringReference>()?.string == key
                }
                assertTrue("$version: the builder no longer names $key", at >= 0)
                val put = code[at + 1]
                assertEquals("$version: $key isn't put right after it's named", Opcode.INVOKE_INTERFACE, put.opcode)
                assertEquals(MAP_PUT, put.getReference<MethodReference>()?.toString())
                assertEquals(
                    "$version: $key isn't the put's key",
                    (code[at] as OneRegisterInstruction).registerA,
                    (put as FiveRegisterInstruction).registerD,
                )
            }
            for (field in listOf("MODEL", "BRAND")) {
                assertTrue(
                    "$version: the builder no longer reads Build.$field",
                    code.any {
                        it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.let { ref ->
                            ref.definingClass == "Landroid/os/Build;" && ref.name == field
                        } == true
                    },
                )
            }
        }
    }

    @Test
    fun `the path goes in before the map is made and the filled map comes out after the fill`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val handler = handlerOf(apk, version)
            val before = commonParamsFill(handler)!!
            handler.reportGameLiveSetup()
            assertHooked(version, handler.implementation!!.instructions.toList(), before.mapAt, before.fillAt, before.mapRegister)
            val after = commonParamsFill(handler)
            assertNotNull("$version: the handler's shape no longer reads after the hook", after)
            assertEquals(before.mapAt + PATH_BLOCK, after!!.mapAt)
            assertEquals(before.fillAt + PATH_BLOCK, after.fillAt)
        }
    }

    @Test
    fun `Region spoof's hook and this one land together in either order`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            for (regionFirst in listOf(true, false)) {
                val handler = handlerOf(apk, version)
                val original = commonParamsFill(handler)!!
                if (regionFirst) handler.regionSpoofHook()
                handler.reportGameLiveSetup()
                if (!regionFirst) handler.regionSpoofHook()

                val code = handler.implementation!!.instructions.toList()
                val made = code.indexOfFirst {
                    it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == "Ljava/util/LinkedHashMap;" &&
                        (it as OneRegisterInstruction).registerA == original.mapRegister
                }
                val fill = commonParamsFill(handler)!!
                assertEquals("$version, region first $regionFirst: the map", made, fill.mapAt)
                val order = "$version, region first $regionFirst"
                assertEquals(order, original.mapAt + 2 * PATH_BLOCK, made)
                assertEquals(order, listOf(true, true), listOf(REGION_PATH, GAME_LIVE_PATH).map { name ->
                    (made - 2 * PATH_BLOCK until made).any { calls(code[it], name, original.mapRegister) }
                })
                val afterFill = (fill.fillAt + 1..fill.fillAt + 2).map { code[it].getReference<MethodReference>()?.name }
                assertTrue("$order: after the fill $afterFill", "requestDone" in afterFill && "requestParams" in afterFill)
                assertTrue(order, (fill.fillAt + 1..fill.fillAt + 2).any { calls(code[it], GAME_LIVE_PARAMS, original.mapRegister) })
            }
        }
    }

    private fun assertHooked(version: String, code: List<Instruction>, mapAt: Int, fillAt: Int, map: Int) {
        val read = code[mapAt]
        assertEquals("$version: the path read starts off the request", Opcode.IGET_OBJECT, read.opcode)
        assertEquals(map, (read as OneRegisterInstruction).registerA)
        assertTrue("$version: requestPath", calls(code[mapAt + PATH_BLOCK - 1], GAME_LIVE_PATH, map))
        assertEquals("$version: the map is made right after", Opcode.NEW_INSTANCE, code[mapAt + PATH_BLOCK].opcode)
        assertEquals(Opcode.INVOKE_STATIC, code[fillAt + PATH_BLOCK].opcode)
        assertTrue("$version: requestParams right after the fill", calls(code[fillAt + PATH_BLOCK + 1], GAME_LIVE_PARAMS, map))
    }

    private fun calls(instruction: Instruction, method: String, register: Int): Boolean =
        instruction.opcode == Opcode.INVOKE_STATIC &&
            instruction.getReference<MethodReference>()?.toString() == method &&
            (instruction as FiveRegisterInstruction).registerCount == 1 && instruction.registerC == register

    /** Region spoof's own insertions in the same handler, as RegionSpoofPatch makes them. */
    private fun MutableMethod.regionSpoofHook() {
        val fill = commonParamsFill(this)!!
        val register = "v${fill.mapRegister}"
        addInstruction(fill.fillAt + 1, "invoke-static {}, $REGION->requestDone()V")
        addInstructionsAtControlFlowLabel(
            fill.mapAt,
            """
                iget-object $register, p1, ${fill.requestField}
                iget-object $register, $register, ${fill.urlField}
                invoke-virtual { $register }, ${fill.pathGetter}
                move-result-object $register
                invoke-static { $register }, $REGION->requestPath(Ljava/lang/String;)V
            """,
        )
    }

    private fun handlerOf(apk: File, version: String): MutableMethod {
        val classDef = classOf(apk, COMMON_PARAMS_HANDLER) ?: error("$version: no $COMMON_PARAMS_HANDLER")
        val taken = classDef.methods.filter { CommonParamsHandlerFingerprint.takes(it, classDef) }
        assertEquals("$version: common-parameter handlers matched", 1, taken.size)
        return MutableMethod(taken.single())
    }

    private fun classOf(apk: File, type: String): ClassDef? {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    private fun forEachMethod(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                classDef.methods.forEach { method -> visit(classDef, method) }
            }
        }
    }

    private companion object {
        /** GameLiveDevice.SETUP_PATHS as the LIVE API writes them. */
        const val CREATE_INFO = "/webcast/room/create_info/"
        val SETUP_PATHS = setOf(CREATE_INFO, "/webcast/room/create/", "/webcast/room/push_stream_info/")
        const val SIGNATURE = "Ldalvik/annotation/Signature;"
        const val ROOM_CREATE_INFO = "Lcom/bytedance/android/live/broadcast/model/RoomCreateInfo;"
        const val MAP_PUT = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        const val REGION = "Lapp/morphe/extension/tiktok/spoof/region/RegionSpoof;"
        const val REGION_PATH = "$REGION->requestPath(Ljava/lang/String;)V"
        const val GAME_LIVE_PATH = "$GAME_LIVE_DEVICE->requestPath(Ljava/lang/String;)V"
        const val GAME_LIVE_PARAMS = "$GAME_LIVE_DEVICE->requestParams(Ljava/util/Map;)V"
        /** The path read and its call: two field reads, the getter, its result, the call. */
        const val PATH_BLOCK = 5
    }
}
