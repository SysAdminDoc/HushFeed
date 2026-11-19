/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.ResourceIds
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Save media in a sticker message's long-press menu (#470), held to every declared build: the
 * rows lambda the patch hooks belongs to sticker messages alone, its pieces resolve, the bridges
 * call only public members TikTok has, and the hook hands the finished rows over at the return.
 */
class DmStickerMenuRowFixturesTest {
    @Test
    fun `one rows lambda takes the fingerprint and only the sticker message cells reach it`() {
        forEachBuild { apk, classes, _ ->
            val matches = rowsLambdas(classes)
            assertEquals(apk, 1, matches.size)
            val (owner, _) = matches.single()
            val inits = owner.methods.filter { it.name == "<init>" }
            assertEquals(apk, 1, inits.size)
            assertTrue("$apk: the lambda is made with the message's sticker template",
                DM_STICKER_TEMPLATE in inits.single().parameterTypes.map(CharSequence::toString))

            // Whoever makes the lambda reads the message as a sticker first, and only the sticker
            // message cells call the makers: no other message's menu gets the row.
            val makers = classes.flatMap { classDef ->
                classDef.methods.filter { method -> method.code().any { it.opcode == Opcode.NEW_INSTANCE && it.type() == owner.type } }
            }
            assertTrue("$apk: something makes the rows lambda", makers.isNotEmpty())
            for (maker in makers) {
                assertTrue("$apk: ${maker.name} picks the sticker template out of the message first",
                    maker.code().any { it.opcode == Opcode.CONST_CLASS && it.type() == DM_STICKER_TEMPLATE })
            }
            val wanted = makers.map { it.signature() }.toSet()
            val callers = classes.filter { classDef ->
                classDef.methods.any { method -> method.code().any { it.getReference<MethodReference>()?.signature() in wanted } }
            }.map { it.type.substringBefore('$') }.toSet()
            assertEquals(apk, setOf(STICKER_SEND_CELL, STICKER_RECEIVE_CELL), callers)
        }
    }

    @Test
    fun `the menu pieces resolve and every member the bridges name is public on the build`() {
        forEachBuild { apk, classes, byType ->
            val (owner, rows) = rowsLambdas(classes).single()
            val menu = dmStickerMenu(rows, owner) { byType[it] }
            assertEquals(apk, rows.code().lastIndex, menu.returnIndex)
            assertTrue("$apk: the hook's locals fit a plain invoke", menu.scratch.all { it <= 15 })
            val bridges = dmStickerMenuBridges(menu)
            assertEquals(apk, setOf("dmStickerMenuRow", "dmStickerMenuDone", "dismissDmStickerMenu"), bridges.map { it.name }.toSet())
            for (bridge in bridges) {
                assertEquals("$apk: ${bridge.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
                for (instruction in bridge.code()) {
                    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                        is MethodReference -> {
                            val definition = publicClass(apk, byType, reference.definingClass)
                            val method = definition.methods.singleOrNull { it.signature() == reference.signature() }
                            assertNotNull("$apk: ${bridge.name} calls ${reference.signature()}", method)
                            assertTrue("$apk: ${reference.signature()} is public", AccessFlags.PUBLIC.isSet(method!!.accessFlags))
                        }
                        is FieldReference -> {
                            val field = publicClass(apk, byType, reference.definingClass).fields.singleOrNull {
                                it.name == reference.name && it.type == reference.type
                            }
                            assertNotNull("$apk: ${bridge.name} reads $reference", field)
                            assertTrue("$apk: $reference is public static",
                                AccessFlags.PUBLIC.isSet(field!!.accessFlags) && AccessFlags.STATIC.isSet(field.accessFlags))
                        }
                        is TypeReference -> publicClass(apk, byType, reference.type)
                        else -> {}
                    }
                }
            }
            // The row takes TikTok's own sticker Save action, so the menu sorts it in beside that row.
            val row = bridges.single { it.name == "dmStickerMenuRow" }.code()
            assertEquals(apk, DM_MENU_ROW_ACTION, row.mapNotNull { it.getReference<FieldReference>() }.first { it.type == menu.action }.name)
            assertTrue("$apk: TikTok builds its own sticker Save row with that action", classes.any { classDef ->
                classDef.methods.any { method ->
                    method.returnType == menu.row && method.code().any {
                        it.getReference<FieldReference>()?.let { field -> field.definingClass == menu.action && field.name == DM_MENU_ROW_ACTION } == true
                    }
                }
            })
        }
    }

    @Test
    fun `the hook hands the finished rows, the template and the menu over at the return`() {
        forEachBuild { apk, classes, byType ->
            val (owner, stock) = rowsLambdas(classes).single()
            val menu = dmStickerMenu(stock, owner) { byType[it] }
            val hooked = MutableMethod(stock)
            hooked.addDmStickerMenuHook(menu)
            val before = stock.code().filter { it.opcode != Opcode.NOP }
            val after = hooked.code().filter { it.opcode != Opcode.NOP }
            assertEquals("$apk gains three lines", before.size + 3, after.size)
            val call = after.indexOfFirst { it.getReference<MethodReference>()?.name == "addDmStickerSaveRow" }
            assertEquals("$apk: the hook sits right before the return", after.lastIndex - 1, call)
            assertEquals(apk, Opcode.RETURN_OBJECT, after.last().opcode)
            assertEquals("$apk keeps TikTok's rows as they were", before.map(::shape), (after.subList(0, call - 2) + after.last()).map(::shape))
            assertEquals(apk, listOf(menu.template, menu.builder), after.subList(call - 2, call).map { it.getReference<FieldReference>().toString() })
            val registers = after[call] as FiveRegisterInstruction
            val list = hooked.implementation!!.registerCount - 1
            assertEquals(apk, listOf(list) + menu.scratch, listOf(registers.registerC, registers.registerD, registers.registerE))
            assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }

    /** StickerGallerySaver looks the row's icon up by name; the shop row's own icon is a raw resource too. */
    @Test
    fun `TikTok's download arrow is a raw resource on every declared build`() {
        val app = checkNotNull(AppCompatibilities.tiktok().single().packageName)
        Fixtures.forEachDeclared { apk ->
            assertEquals(apk.name, 1, ResourceIds.read(apk, "raw").getValue(app)["icon_arrow_down_to_line"]?.size)
        }
    }

    private fun rowsLambdas(classes: List<ClassDef>): List<Pair<ClassDef, Method>> = classes.flatMap { classDef ->
        classDef.methods.filter { DmStickerMenuRowsFingerprint.takes(it, classDef) }.map { classDef to it }
    }

    private fun publicClass(apk: String, byType: Map<String, ClassDef>, type: String): ClassDef {
        val definition = byType[type]
        assertNotNull("$apk: TikTok defines $type", definition)
        assertTrue("$apk: $type is public", AccessFlags.PUBLIC.isSet(definition!!.accessFlags))
        return definition
    }

    private fun forEachBuild(check: (String, List<ClassDef>, Map<String, ClassDef>) -> Unit) {
        Fixtures.forEachDeclared { apk: File ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            check(apk.name, classes, classes.associateBy { it.type })
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
    private fun Instruction.type() = getReference<TypeReference>()?.type
    private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /** An instruction's opcode and what it names, with a widened goto read as a goto. */
    private fun shape(instruction: Instruction): String {
        val kind = when (instruction.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> instruction.opcode
        }
        return "$kind ${(instruction as? ReferenceInstruction)?.reference ?: ""}"
    }

    private companion object {
        const val STICKER_SEND_CELL = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/gif/StoreStickerSendPowerCell"
        const val STICKER_RECEIVE_CELL = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/gif/StoreStickerReceivePowerCell"
    }
}
