/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.Fixtures
import app.morphe.patches.tiktok.interaction.downloads.VIEW_HOLDER
import app.morphe.patches.tiktok.interaction.downloads.gridPickProblem
import app.morphe.patches.tiktok.interaction.engagement.ProfileGridHolderBindFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
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
