/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.downloads.CollectionGridHolderBindFingerprint
import app.morphe.patches.tiktok.interaction.downloads.VIEW_HOLDER
import app.morphe.patches.tiktok.interaction.downloads.boundPostField
import app.morphe.patches.tiktok.interaction.downloads.gridPickProblem
import app.morphe.patches.tiktok.interaction.downloads.hookGridPickFromField
import app.morphe.patches.tiktok.interaction.engagement.ProfileGridHolderBindFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round the corners on profile grids (#487) reads the profile grid cell's root view at the top
 * of its holder's bind and hands it over, straight-line. Held to each declared build: the bind is
 * one method of a RecyclerView.ViewHolder with a local to spare, nothing jumps to its first
 * instruction past the hand-over, and the holder's itemView is a public View field to read.
 */
class GridCornersAnchorsTest {
    @Test
    fun `the grid holder bind takes the corners hook at its top on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .associateBy { it.type }
            val binds = classes.values.flatMap { cls ->
                cls.methods.filter { ProfileGridHolderBindFingerprint.takes(it, cls) }
            }
            assertEquals("$version: grid holder binds", 1, binds.size)
            val bind = binds.single()
            assertNull("$version: ${bind.definingClass}->${bind.name}", bind.gridPickProblem { classes[it]?.superclass })
            assertFalse("$version: something jumps to the bind's first instruction, past the hand-over",
                0 in bind.implementation!!.targetIndexes())
            val itemView = classes.getValue(VIEW_HOLDER).fields.singleOrNull { it.name == "itemView" && it.type == "Landroid/view/View;" }
            assertNotNull("$version: ViewHolder has no itemView", itemView)
            assertTrue("$version: itemView isn't public", AccessFlags.PUBLIC.isSet(itemView!!.accessFlags))
        }
    }

    @Test
    fun `Always show publish date hands the grid cells over`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/publishdate/AlwaysShowPublishDatePatch.kt").readText()
        assertTrue("the grid cells aren't handed over",
            source.contains("ProfileGridHolderBindFingerprint.method.hookGridCorners(ProfileGridHolderBindFingerprint.originalMethod)"))
        assertTrue("the collection folder cells aren't handed over",
            source.contains("CollectionGridHolderBindFingerprint.method.hookCollectionGridCorners(CollectionGridHolderBindFingerprint.originalMethod)"))
    }

    @Test
    fun `the collection folder bind takes the corners hook past its opening read on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .associateBy { it.type }
            val owners = classes.values.filter { cls -> cls.methods.any { CollectionGridHolderBindFingerprint.takes(it, cls) } }
            assertEquals("$version: collection folder holder binds", 1, owners.size)
            val owner = owners.single()
            val bind = owner.methods.single { CollectionGridHolderBindFingerprint.takes(it, owner) }
            val post = bind.boundPostField()!!
            assertEquals("$version: the hook's spot", 1, bind.collectionCornersIndex(post))
            assertFalse("$version: something jumps to the post check, past the hand-over",
                1 in bind.implementation!!.targetIndexes())
            val superclassOf: (String) -> String? = { classes[it]?.superclass }

            // Corners first, then Advanced downloads: the opening read is still first, so
            // Advanced downloads' fingerprint still takes the bind and reads the same post field.
            val cornersFirst = MutableMethod(bind).apply { hookCollectionGridCorners(bind, superclassOf) }
            assertTrue("$version: Advanced downloads' fingerprint no longer takes the bind",
                CollectionGridHolderBindFingerprint.takes(cornersFirst, owner))
            assertEquals("$version: the post Advanced downloads reads", post.toString(), cornersFirst.boundPostField()?.toString())
            assertEquals("$version: the opening read moved", describe(bind.implementation!!.instructions.first()),
                describe(cornersFirst.implementation!!.instructions.first()))
            assertCornersCall("$version, corners first", cornersFirst, 1)
            cornersFirst.hookGridPickFromField(superclassOf)
            assertCornersCall("$version, then the picker", cornersFirst, 4)

            // Advanced downloads first: the hook still lands right after TikTok's own read, not
            // inside the picker's hand-over, which reads the same field.
            val pickerFirst = MutableMethod(bind).apply { hookGridPickFromField(superclassOf) }
            pickerFirst.hookCollectionGridCorners(bind, superclassOf)
            assertCornersCall("$version, picker first", pickerFirst, 4)
            val picker = pickerFirst.implementation!!.instructions.toList()[2]
            assertEquals("$version: the picker's hand-over was split", Opcode.INVOKE_STATIC, picker.opcode)
            assertEquals("$version", "bind", ((picker as ReferenceInstruction).reference as MethodReference).name)
        }
    }

    @Test
    fun `a bind without the opening read and its check is refused`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val owner = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .single { cls -> cls.methods.any { CollectionGridHolderBindFingerprint.takes(it, cls) } }
            val post = owner.methods.single { CollectionGridHolderBindFingerprint.takes(it, owner) }.boundPostField()!!
            val constructor = owner.methods.first { it.name == "<init>" }
            assertNull("$version: the holder's constructor", constructor.collectionCornersIndex(post))
        }
    }

    /** An instruction's opcode, registers and reference, the same whichever dexlib2 class holds it. */
    private fun describe(instruction: Instruction): String {
        val registers = (instruction as? TwoRegisterInstruction)?.let { "v${it.registerA}, v${it.registerB}" }
            ?: (instruction as? OneRegisterInstruction)?.let { "v${it.registerA}" }.orEmpty()
        return "${instruction.opcode} $registers ${(instruction as? ReferenceInstruction)?.reference}"
    }

    /** The corners call sits at [at] in [method], a range call over `this` alone, and the post check follows it. */
    private fun assertCornersCall(what: String, method: Method, at: Int) {
        val code = method.implementation!!.instructions.toList()
        val call = code[at]
        assertEquals("$what: the corners call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val target = (call as ReferenceInstruction).reference as MethodReference
        assertEquals(what, GRID_CORNERS_CLASS_DESCRIPTOR, target.definingClass)
        assertEquals(what, "collectionCell", target.name)
        val range = call as RegisterRangeInstruction
        assertEquals(what, 1, range.registerCount)
        assertEquals("$what: the holder", method.implementation!!.registerCount - 1, range.startRegister)
        assertEquals("$what: what follows the call", Opcode.IF_NEZ, code[at + 1].opcode)
        assertEquals("$what: what comes before the call", Opcode.IGET_OBJECT, code[at - 1].opcode)
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
