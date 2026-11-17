/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FILTER = "Lapp/morphe/extension/tiktok/inbox/InboxFilter;"

/**
 * What the two stories tray switches of #449 hook, held to each declared build: the binds of the
 * tray's two LIVE cells and of its Widget cell. Each cell goes to the extension before TikTok binds
 * it, the LIVE ones with the item, and TikTok's own bind follows untouched.
 */
class InboxTrayCellsAnchorsTest {
    private val liveCells = listOf(
        INBOX_LIVE_CELL to InboxLiveBindFingerprint,
        INBOX_LIVE_DOUBLE_CELL to InboxLiveDoubleBindFingerprint,
    )

    @Test
    fun `each declared build has the three cells and each fingerprint takes exactly its bind`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            for ((type, fingerprint) in liveCells + (WIDGET_GUIDE_CELL to StoryWidgetGuideBindFingerprint)) {
                val bind = bindOf(version, classes, type, fingerprint)
                assertFalse("$version: $type's bind is static", AccessFlags.STATIC.isSet(bind.accessFlags))
                assertEquals("$version: $type", 1, bind.parameterTypes.size)
            }
        }
    }

    @Test
    fun `the item each LIVE cell binds answers isUnfollow`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            for ((type, fingerprint) in liveCells) {
                val bind = bindOf(version, classes, type, fingerprint)
                val cast = bind.implementation!!.instructions.first { it.opcode == Opcode.CHECK_CAST }
                val itemType = cast.getReference<TypeReference>()!!.type
                var current: ClassDef? = classes[itemType]
                var answers = false
                while (current != null && !answers) {
                    answers = current.methods.any {
                        it.name == "isUnfollow" && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
                            !AccessFlags.STATIC.isSet(it.accessFlags)
                    }
                    current = current.superclass?.let(classes::get)
                }
                assertTrue("$version: $type binds $itemType, which has no isUnfollow()", answers)
            }
        }
    }

    @Test
    fun `each cell goes to the extension first and its bind keeps its body`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            for ((type, fingerprint) in liveCells) {
                val native = bindOf(version, classes, type, fingerprint)
                val (first, body) = hooked(native) { reportInboxLiveBind("Hide inbox items") }
                val call = first.getReference<MethodReference>()!!
                assertEquals(FILTER, call.definingClass)
                assertEquals("onLiveCellBound", call.name)
                assertEquals(
                    listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
                    call.parameterTypes.map(CharSequence::toString),
                )
                val registers = first as FiveRegisterInstruction
                assertEquals("$version: $type hands over the cell and the item", 2, registers.registerCount)
                assertEquals(body.registerCount - 2, registers.registerC)
                assertEquals(body.registerCount - 1, registers.registerD)
            }
            val native = bindOf(version, classes, WIDGET_GUIDE_CELL, StoryWidgetGuideBindFingerprint)
            val (first, body) = hooked(native) { reportWidgetGuideBind("Hide inbox items") }
            val call = first.getReference<MethodReference>()!!
            assertEquals(FILTER, call.definingClass)
            assertEquals("onWidgetGuideBound", call.name)
            assertEquals(listOf("Ljava/lang/Object;"), call.parameterTypes.map(CharSequence::toString))
            assertEquals(1, (first as FiveRegisterInstruction).registerCount)
            assertEquals(body.registerCount - 2, first.registerC)
        }
    }

    /** Hooks a copy of [native] and checks the hook is the one new instruction, ahead of TikTok's body. */
    private fun hooked(
        native: com.android.tools.smali.dexlib2.iface.Method,
        hook: MutableMethod.() -> Unit,
    ): Pair<com.android.tools.smali.dexlib2.iface.instruction.Instruction, com.android.tools.smali.dexlib2.iface.MethodImplementation> {
        val before = native.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
        val method = MutableMethod(native)
        method.hook()
        val after = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
        assertEquals(Opcode.INVOKE_STATIC, after.first().opcode)
        assertEquals(before.size + 1, after.size)
        assertEquals(before.map { it.opcode }, after.drop(1).map { it.opcode })
        return after.first() to native.implementation!!
    }

    private fun bindOf(
        version: String,
        classes: Map<String, ClassDef>,
        type: String,
        fingerprint: Fingerprint,
    ): com.android.tools.smali.dexlib2.iface.Method {
        val classDef = classes[type]
        assertNotNull("$version: no $type", classDef)
        val taken = classDef!!.methods.filter { fingerprint.takes(it, classDef) }
        assertEquals("$version: $type binds matched ${taken.map { it.name }}", 1, taken.size)
        assertEquals("onBindItemView", taken.single().name)
        return taken.single()
    }

    private val cellTypes = setOf(INBOX_LIVE_CELL, INBOX_LIVE_DOUBLE_CELL, WIDGET_GUIDE_CELL)

    /** The three cells, then each class a LIVE cell casts its item to and that class's superclasses. */
    private fun classesOf(apk: java.io.File): Map<String, ClassDef> {
        val found = HashMap<String, ClassDef>()
        var wanted: Set<String> = cellTypes
        repeat(4) {
            if (wanted.isEmpty()) return found
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type in wanted) found.putIfAbsent(classDef.type, classDef)
                }
            }
            wanted = found.values.flatMap { classDef ->
                val casts = if (classDef.type in cellTypes) {
                    classDef.methods.flatMap { method ->
                        method.implementation?.instructions?.filter { it.opcode == Opcode.CHECK_CAST }
                            ?.mapNotNull { it.getReference<TypeReference>()?.type } ?: emptyList()
                    }
                } else {
                    emptyList()
                }
                casts + listOfNotNull(classDef.superclass)
            }.filter { it !in found && it.startsWith("L") && !it.startsWith("Ljava/") && !it.startsWith("Landroid") }.toSet()
        }
        return found
    }
}
