/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a trace of each build found: the bar's dimension id and how many times the app reads it. */
private val TRACED = mapOf("47.7.3" to (0x7f090499 to 8))

/**
 * The bottom bar's height. Each of its three sources is found the way the patch finds it, held
 * to every declared build, and each hook is applied to the real method it would patch.
 */
class BottomBarHeightAnchorsTest {
    @Test
    fun `each declared build finds the bar's dimension, its reads, the divider and the nav height static`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val sites = bottomBarHeightSites({ app[it] }) { targets -> callers(app, targets) }

            TRACED[version]?.let { (dimension, reads) ->
                assertEquals("$version: dimension", dimension, sites.dimension)
                assertEquals("$version: reads", reads, sites.reads.size)
            }
            val owners = sites.reads.map { it.owner }.toSet()
            assertTrue("$version: reads $owners", owners.containsAll(BOTTOM_BAR_FRAMES + BOTTOM_BAR_ACTIVITY_FRAME))
            assertTrue("$version: a whole pixel read", sites.reads.any { it.type == "I" })

            // The divider and the stub, in both inflaters.
            val values = sites.literals.map { literal ->
                val load = literal.method.implementation!!.instructions.elementAt(literal.index)
                literal.owner to Float.fromBits((load as NarrowLiteralInstruction).narrowLiteral)
            }
            BOTTOM_BAR_FRAMES.forEach { type ->
                assertEquals("$version: $type literals", listOf(47f, 45f), values.filter { it.first == type }.map { it.second })
            }

            // The nav height: stored, then less 47 into a static of its own.
            val nav = sites.navHeight
            assertEquals("I", nav.field.type)
            assertEquals("<clinit>", nav.method.name)
            val clinit = nav.method.implementation!!.instructions.toList()
            assertEquals(LOW_SCREEN_AB, clinit.first().getReference<MethodReference>()?.definingClass)
            assertEquals(Opcode.SPUT, clinit[nav.after - 1].opcode)
            assertNotEquals("$version: the extra lands elsewhere", nav.field.name, clinit[nav.after - 1].getReference<FieldReference>()!!.name)

            checkFrameHooks(version, sites, sites.reads.first { it.owner == BOTTOM_BAR_FRAMES.first() })
            checkNavHook(version, nav)
            checkCellHook(version, app)
        }
    }

    /** The tab icon, the class whose badge setters Feed tab navigation answers, hands itself over once built. */
    private fun checkCellHook(version: String, app: Map<String, ClassDef>) {
        val cells = app.values.filter { classDef ->
            classDef.methods.any { it.name == "getCountDotView" } && classDef.methods.any { it.name == "getRedDotVIew" }
        }
        assertEquals("$version: tab icon classes ${cells.map { it.type }}", 1, cells.size)
        val method = MutableMethod(barCellConstructor(cells.single()))
        val size = method.implementation!!.instructions.size
        method.holdBarCellContent()
        val after = method.implementation!!.instructions.toList()
        assertEquals(size + 1, after.size)
        val returnIndex = after.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        val call = after[returnIndex - 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("onBarCell", call.getReference<MethodReference>()!!.name)
        val tab = method.implementation!!.registerCount - method.parameters.size - 1
        assertEquals("$version: the tab itself", tab, (call as RegisterRangeInstruction).startRegister)
        assertEquals(1, call.registerCount)
    }

    private fun checkFrameHooks(version: String, sites: BottomBarHeightSites, read: BarRead) {
        val hooks = sites.hooksByMethod().getValue(read.owner to read.method)
        val method = MutableMethod(read.method)
        val size = method.implementation!!.instructions.size
        method.addBottomBarHooks(hooks)
        val after = method.implementation!!.instructions.toList()
        assertEquals(size + 2 * hooks.size, after.size)

        // Every hook that goes in ahead of the read moves it down by its two instructions.
        val at = read.result + 1 + 2 * hooks.count { it.first < read.result + 1 }
        assertEquals(Opcode.MOVE_RESULT, after[at - 1].opcode)
        val call = after[at]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(read.register, (call as RegisterRangeInstruction).startRegister)
        assertEquals(1, call.registerCount)
        val target = call.getReference<MethodReference>()!!
        assertEquals(BOTTOM_BAR_HEIGHT_EXTENSION, target.definingClass)
        assertEquals("$version: the bar's read", "barPixels", target.name)
        assertEquals(read.register, (after[at + 1] as OneRegisterInstruction).registerA)

        val placed = after.indices.filter { after[it].getReference<MethodReference>()?.name == "aboveBar" }
        assertEquals("$version: the divider and the stub", 2, placed.size)
        placed.forEach { assertEquals("$version: what aboveBar takes", Opcode.CONST_HIGH16, after[it - 1].opcode) }
    }

    private fun checkNavHook(version: String, nav: NavHeightStatic) {
        val method = MutableMethod(nav.method)
        method.adjustNavHeight(nav)
        val after = method.implementation!!.instructions.toList()
        assertEquals(
            "$version: the nav height hook",
            listOf(Opcode.SGET, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.SPUT),
            after.subList(nav.after, nav.after + 4).map { it.opcode },
        )
        assertEquals(nav.field.name, after[nav.after].getReference<FieldReference>()!!.name)
        assertEquals("navHeight", after[nav.after + 1].getReference<MethodReference>()!!.name)
        assertEquals(nav.field.name, after[nav.after + 3].getReference<FieldReference>()!!.name)
    }

    @Test
    fun `an inflater without the divider leaves the height out`() {
        val apk = Fixtures.declared().firstOrNull { it.isFile } ?: return
        val app = load(apk)
        val frame = app.getValue(BOTTOM_BAR_FRAMES.first())
        val broken = frame.withLiteralMoved(47f, "0x42380000")
        val failure = assertThrows(PatchException::class.java) {
            bottomBarHeightSites({ if (it == frame.type) broken else app[it] }) { targets ->
                callers(app, targets).map { if (it.type == frame.type) broken else it }
            }
        }
        assertTrue(failure.message, failure.message!!.contains("divider 47 dp up"))
    }

    /** The class with each `const/high16` of [value] loading [replacement] instead. */
    private fun ClassDef.withLiteralMoved(value: Float, replacement: String): ClassDef = ImmutableClassDef(
        type, accessFlags, superclass, interfaces, sourceFile, annotations, fields,
        methods.map { method ->
            val code = method.implementation?.instructions?.toList() ?: return@map method
            val indices = code.indices.filter { index ->
                code[index].opcode == Opcode.CONST_HIGH16 &&
                    Float.fromBits((code[index] as NarrowLiteralInstruction).narrowLiteral) == value
            }
            if (indices.isEmpty()) return@map method
            MutableMethod(method).apply {
                indices.forEach { index ->
                    replaceInstruction(index, "const/high16 v${(code[index] as OneRegisterInstruction).registerA}, $replacement")
                }
            }
        },
    )

    /** The classes holding a call to a target, each a full method descriptor or a type. */
    private fun callers(app: Map<String, ClassDef>, targets: Collection<String>): List<ClassDef> =
        app.values.filter { classDef ->
            classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    val reference = instruction.getReference<MethodReference>() ?: return@any false
                    val descriptor = "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"
                    descriptor in targets || reference.definingClass in targets
                } == true
            }
        }

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
