/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hide rows on LIVE profile cards (#487) reads each card cell's module through TikTok's own module
 * enum, whose constant names it keeps. Held to each declared build: the card's containers each ask
 * their cells to bind through one cell base, the hook fits after every answer with nothing jumping
 * onto it, the base answers its module and holds its view, every offered row is a constant of the
 * module enum that some cell carries, and the bridges type-check.
 */
class LiveProfileCardAnchorsTest {
    private fun classesOf(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    private fun containersOf(classes: Map<String, ClassDef>) = classes.values.flatMap { cls ->
        cls.methods.filter { it.isCardContainerBind() && cardCellBinds(it).isNotEmpty() }.map { cls to it }
    }

    @Test
    fun `the card's containers, cell base and module enum resolve on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            val containers = containersOf(classes)
            assertTrue("$version: no profile card containers", containers.isNotEmpty())
            val route = liveCardRoute(containers.map { it.second }) { classes[it] }
            val base = classes.getValue(route.cellBase)
            assertTrue("$version: the cell base is abstract", AccessFlags.ABSTRACT.isSet(base.accessFlags))
            assertEquals("$version: the module enum", "Ljava/lang/Enum;", classes.getValue(route.componentType).superclass)
            assertTrue("$version: ${route.view}", route.view.endsWith(":Landroid/view/View;"))

            // Each offered row is a constant some cell under the base carries.
            val names = moduleFields(classes.getValue(route.componentType))
            val carried = classes.values.filter { extendsBase(it, route.cellBase, classes) }.flatMap { cell ->
                cell.methods.flatMap { method ->
                    method.implementation?.instructions?.toList()?.mapNotNull {
                        val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                        if (it.opcode == Opcode.SGET_OBJECT && field?.definingClass == route.componentType) names[field.name] else null
                    } ?: emptyList()
                }
            }.toSet()
            LIVE_CARD_ROWS.forEach { row ->
                assertTrue("$version: no card cell carries $row (cells carry $carried)", row in carried)
            }
        }
    }

    @Test
    fun `the hook fits after every cell's answer and stays straight-line`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            containersOf(classesOf(apk)).forEach { (owner, method) ->
                val where = "$version: ${owner.type}->${method.name}"
                val binds = cardCellBinds(method)
                val targets = method.implementation!!.targetIndexes()
                binds.forEach { bind ->
                    assertTrue("$where: the cell's register ${bind.cell}", bind.cell <= 15)
                    assertTrue("$where: the answer's register ${bind.result}", bind.result <= 15)
                    assertTrue("$where: the cell and the answer share v${bind.cell}", bind.cell != bind.result)
                    assertFalse("$where: something jumps past the answer at ${bind.moveResult + 1}", bind.moveResult + 1 in targets)
                }

                val before = method.implementation!!.instructions.toList()
                val hooked = MutableMethod(method)
                assertEquals(where, binds.size, hooked.hookCardCellBinds())
                val after = hooked.implementation!!.instructions.toList()
                assertEquals("$where: two lines per cell", before.size + 2 * binds.size, after.size)
                assertEquals("$where: no branch added",
                    before.count { it is OffsetInstruction }, after.count { it is OffsetInstruction })
                val calls = after.indices.filter {
                    ((after[it] as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                        call.definingClass == LIVE_PROFILE_CARD_EXTENSION && call.name == "keep"
                    } == true
                }
                assertEquals(where, binds.size, calls.size)
                calls.forEach { at ->
                    assertEquals("$where: the cell bind comes first", Opcode.INVOKE_VIRTUAL, after[at - 2].opcode)
                    assertEquals("$where: then its answer moves out", Opcode.MOVE_RESULT, after[at - 1].opcode)
                    val call = after[at] as FiveRegisterInstruction
                    val bind = after[at - 2] as FiveRegisterInstruction
                    assertEquals("$where: the cell handed over", bind.registerC, call.registerC)
                    assertEquals("$where: its answer", (after[at - 1] as OneRegisterInstruction).registerA, call.registerD)
                    assertEquals("$where: the extension's answer replaces it", Opcode.MOVE_RESULT, after[at + 1].opcode)
                    assertEquals(where, call.registerD, (after[at + 1] as OneRegisterInstruction).registerA)
                }
                assertEquals("$where: ${BridgeTypeFlow.check(hooked)}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
            }
        }
    }

    @Test
    fun `the bridges read the module and the view off a cell and type-check`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            val route = liveCardRoute(containersOf(classes).map { it.second }) { classes[it] }

            val component = componentBridge(stub("componentOf"), route)
            val read = component.implementation!!.instructions.single { it.opcode == Opcode.INVOKE_VIRTUAL }
            assertEquals(version, route.component, ((read as ReferenceInstruction).reference as MethodReference).toString())
            assertEquals("$version: ${BridgeTypeFlow.check(component)}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(component))

            val view = cellViewBridge(stub("cellView"), route)
            val field = view.implementation!!.instructions.single { it.opcode == Opcode.IGET_OBJECT }
            assertEquals(version, route.view, ((field as ReferenceInstruction).reference as FieldReference).toString())
            assertEquals("$version: ${BridgeTypeFlow.check(view)}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(view))

            listOf(component, view).forEach { bridge ->
                val casts = bridge.implementation!!.instructions.filter { it.opcode == Opcode.CHECK_CAST }
                assertEquals(version, listOf(route.cellBase), casts.map { ((it as ReferenceInstruction).reference as TypeReference).type })
            }
        }
    }

    @Test
    fun `a missing step on the route fails the patch and says which`() {
        assertRefused("containers weren't found") { liveCardRoute(emptyList()) { null } }
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            val containers = containersOf(classes).map { it.second }
            assertRefused("cell class wasn't found") { liveCardRoute(containers) { null } }
            val base = liveCardRoute(containers) { classes[it] }.cellBase
            assertRefused("module wasn't found") {
                liveCardRoute(containers) { type -> classes[type]?.takeIf { type == base } }
            }
        }
    }

    @Test
    fun `LIVE controls hooks the card and the extension offers the same rows`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val patch = File(root, "app/morphe/patches/tiktok/interaction/live/LiveControlsPatch.kt").readText()
        assertTrue("the card's rows aren't hooked", patch.contains("hookLiveProfileCardRows()"))

        val extensionPath = "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/live/LiveProfileCard.java"
        val extension = (File("../$extensionPath").takeIf { it.isFile } ?: File(extensionPath)).readText()
        val rows = Regex("""ROWS = \{([^}]*)}""").find(extension)
        assertNotNull("the extension's ROWS", rows)
        assertEquals(LIVE_CARD_ROWS, Regex("\"([A-Z_]+)\"").findAll(rows!!.groupValues[1]).map { it.groupValues[1] }.toList())
    }

    /** The module enum's constants by field: each constant's name is the last string loaded before it's stored. */
    private fun moduleFields(modules: ClassDef): Map<String, String> {
        val names = mutableMapOf<String, String>()
        modules.methods.filter { it.name == "<clinit>" }.forEach { method ->
            var last: String? = null
            method.implementation?.instructions?.forEach {
                val reference = (it as? ReferenceInstruction)?.reference
                if (reference is StringReference) last = reference.string
                if (it.opcode == Opcode.SPUT_OBJECT && reference is FieldReference &&
                    reference.type == modules.type && last != null
                ) {
                    names[reference.name] = last!!
                }
            }
        }
        return names
    }

    private fun extendsBase(cls: ClassDef, base: String, classes: Map<String, ClassDef>): Boolean {
        var type: String? = cls.superclass
        var depth = 0
        while (type != null && depth++ < 16) {
            if (type == base) return true
            type = classes[type]?.superclass
        }
        return false
    }

    /** The extension's bridge as compiled: a static (Object)Object answering null. */
    private fun stub(name: String) = MutableMethod(
        ImmutableMethod(
            LIVE_PROFILE_CARD_EXTENSION,
            name,
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            "Ljava/lang/Object;",
            AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, "const/4 v0, 0x0\nreturn-object v0") }

    private fun assertRefused(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal: $message")
        } catch (expected: PatchException) {
            assertTrue(expected.message!!, expected.message!!.contains(message))
        }
    }

    /** Every instruction index a branch, a switch case or a catch handler lands on. */
    private fun MethodImplementation.targetIndexes(): Set<Int> {
        val instructions = instructions.toList()
        val addresses = IntArray(instructions.size + 1)
        for (index in instructions.indices) addresses[index + 1] = addresses[index] + instructions[index].codeUnits
        fun indexAt(address: Int) = addresses.indexOfFirst { it == address }.takeIf { it in instructions.indices }
        val targets = mutableSetOf<Int>()
        instructions.forEachIndexed { index, instruction ->
            if (instruction !is OffsetInstruction) return@forEachIndexed
            val target = addresses[index] + instruction.codeOffset
            if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                val payload = indexAt(target)?.let { instructions[it] } as? SwitchPayload ?: return@forEachIndexed
                payload.switchElements.forEach { element -> indexAt(addresses[index] + element.offset)?.let(targets::add) }
            } else {
                indexAt(target)?.let(targets::add)
            }
        }
        tryBlocks.forEach { block -> block.exceptionHandlers.forEach { handler -> indexAt(handler.handlerCodeAddress)?.let(targets::add) } }
        return targets
    }
}
