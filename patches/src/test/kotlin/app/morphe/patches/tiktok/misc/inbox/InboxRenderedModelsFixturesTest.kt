package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Original-DEX proof for the field-only list selection shared by seed and diagnostics. R8 renames the
 * PowerList helper classes every build (47.4.3 LX/09Mk; differ, 47.5.3 LX/09EW;), so they are read off
 * the adapter's own getItemCount and aggregate reads; the member names it reads stay pinned.
 */
class InboxRenderedModelsFixturesTest {
    @Test fun `current adapter schema declares each exact typed field and loading state hierarchy`() {
        Fixtures.forEachDeclared { apk ->
            val schema = Schema.of(Fixtures.dexContainer(apk, Opcodes.getDefault()).let { dex ->
                dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes } })
            schema.fields().forEach { (owner, name, type) ->
                val field = schema.classOf(owner).fields.single { it.name == name }
                assertEquals("$owner->$name", type, field.type)
                assertFalse(AccessFlags.STATIC.isSet(field.accessFlags))
            }
            assertEquals(schema.state, schema.classOf(schema.refresh).superclass)
            assertEquals(schema.state, schema.classOf(schema.empty).superclass)
            assertEquals("Ljava/lang/Enum;", schema.classOf(schema.phase).superclass)
            listOf("Refresh", "Next", "Prev").forEach { name ->
                val field = schema.classOf(schema.phase).fields.single { it.name == name }
                assertEquals(schema.phase, field.type)
                assertTrue(AccessFlags.STATIC.isSet(field.accessFlags))
            }
        }
    }

    @Test fun `the extension reads exactly the classes and fields this fixture proves`() {
        // InboxRenderedModels names them by hand, and the 47.4.3 retarget left them on 47.1.4, which
        // turned Search loaded activity off. Read them from the source so the next retarget fails here.
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val source = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/inbox/InboxRenderedModels.java").readText()
        Fixtures.forEachDeclared { apk ->
            val schema = Schema.of(Fixtures.dexContainer(apk, Opcodes.getDefault()).let { dex ->
                dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes } })
            val types = Regex("""type\("([^"]+)", loader\)""").findAll(source)
                .map { "L" + it.groupValues[1].replace('.', '/') + ";" }.toList()
            assertEquals(listOf(ADAPTER, schema.diff, schema.config, STUB, schema.loading, schema.model,
                schema.state, schema.phase, schema.refresh, schema.empty), types)
            val roles = mapOf("adapter" to ADAPTER, "diff" to schema.diff, "config" to schema.config, "stub" to STUB,
                "loading" to schema.loading, "model" to schema.model, "state" to schema.state, "phase" to schema.phase,
                "List.class" to "Ljava/util/List;", "boolean.class" to "Z", "Class.class" to "Ljava/lang/Class;")
            val reads = Regex("""field\((\w+), "(\w+)", ([\w.]+), false\)""").findAll(source).map {
                Triple(roles.getValue(it.groupValues[1]), it.groupValues[2], roles.getValue(it.groupValues[3])) }.toList()
            assertEquals(schema.fields(), reads)
        }
    }

    @Test fun `native list mode joins primary then extra stubs and applies loading predicates to state`() {
        Fixtures.forEachDeclared { apk ->
            val schema = Schema.of(Fixtures.dexContainer(apk, Opcodes.getDefault()).let { dex ->
                dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes } })
            assertEquals(listOf("LLLIIII", "LJ", "LLLI", "LJIIJJI"), schema.count.fields().map { it.name })
            val stubs = schema.adapterMethod(schema.aggregate.calls().first { it.definingClass == ADAPTER })
            assertEquals("Ljava/util/List;", stubs.returnType)
            val primary = schema.adapterMethod(stubs.calls().first())
            assertEquals(STUB, primary.returnType)
            assertEquals(listOf("LLJJJJLIIL"), stubs.fields().map { it.name })
            assertTrue(stubs.calls().any { method -> method.parameterTypes.map(CharSequence::toString) ==
                listOf("Ljava/util/Collection;", "Ljava/lang/Iterable;") && method.returnType == "Ljava/util/List;" })
            assertEquals(listOf("LLJJJJJIL"), primary.fields().map { it.name })

            val aggregate = schema.aggregate.implementation!!.instructions.toList()
            assertEquals(listOf("LLJZIJLIL", "LLLIIIL", "LLJLLIL", "LLJJIJI", "LIZIZ", "LIZIZ", "Refresh", "LLJLLIL"),
                aggregate.mapNotNull { it.getReference<FieldReference>()?.name })
            val stateRead = aggregate.single { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == schema.model && field.name == "LIZIZ" } == true } as TwoRegisterInstruction
            val phaseRead = aggregate.single { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == schema.state && field.name == "LIZIZ" } == true } as TwoRegisterInstruction
            assertEquals(stateRead.registerA, phaseRead.registerB)
            val predicates = aggregate.filter { it.opcode == Opcode.INSTANCE_OF }
            assertEquals(listOf(schema.refresh, schema.empty), predicates.map { it.getReference<TypeReference>()!!.type })
            predicates.forEach { assertEquals(stateRead.registerA, (it as TwoRegisterInstruction).registerB) }
            assertTrue(aggregate.any { it.opcode == Opcode.IF_NE })
            assertTrue(aggregate.any { it.opcode == Opcode.XOR_INT_LIT8 && (it as NarrowLiteralInstruction).narrowLiteral == 1 })
        }
    }

    /** The helper classes as the adapter's own code reads them. */
    private class Schema(private val byType: Map<String, ClassDef>) {
        val adapter = classOf(ADAPTER)
        val count = adapter.methods.single { it.name == "getItemCount" }
        val config = count.fields()[0].type
        val diff = count.fields()[2].type
        val aggregate = adapterMethod(count.calls().single { it.definingClass == ADAPTER })
        private val reads = aggregate.fields()
        val loading = reads[2].type
        val model = reads[3].type
        val state = reads[4].type
        val phase = reads[5].type
        private val predicates = aggregate.implementation!!.instructions
            .filter { it.opcode == Opcode.INSTANCE_OF }.map { it.getReference<TypeReference>()!!.type }
        val refresh = predicates[0]
        val empty = predicates[1]

        /** Owner, name and type of each instance field the extension reads, in its read order. */
        fun fields() = listOf(
            Triple(ADAPTER, "LLLI", diff), Triple(diff, "LJIIJJI", "Ljava/util/List;"),
            Triple(ADAPTER, "LLLIIII", config), Triple(config, "LJ", "Z"),
            Triple(ADAPTER, "LLJJJJJIL", STUB), Triple(ADAPTER, "LLJJJJLIIL", "Ljava/util/List;"),
            Triple(STUB, "LLJZIJLIL", "Ljava/util/List;"), Triple(ADAPTER, "LLLIIIL", "Ljava/lang/Class;"),
            Triple(ADAPTER, "LLJLLIL", loading), Triple(loading, "LLJJIJI", model),
            Triple(model, "LIZIZ", state), Triple(state, "LIZIZ", phase)
        )
        fun classOf(type: String) = byType.getValue(type)
        fun adapterMethod(reference: MethodReference): Method = adapter.methods.single {
            it.name == reference.name && it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString) }

        companion object { fun of(classes: List<ClassDef>) = Schema(classes.associateBy { it.type }) }
    }

    private companion object {
        const val ADAPTER = "Lcom/bytedance/ies/powerlist/PowerAdapter;"
        const val STUB = "Lcom/bytedance/ies/powerlist/PowerStub;"

        fun Method.fields(): List<FieldReference> = implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
        fun Method.calls(): List<MethodReference> = implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
    }
}
