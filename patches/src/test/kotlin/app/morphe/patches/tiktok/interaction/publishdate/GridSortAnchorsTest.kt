/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the grid sort (#180) hooks, held to each declared build: the profile grid adapter's
 * `setData(List)` and `setDataAfterLoadMore(List)` are each found once, in one class, and the
 * hooks assemble in front of TikTok's code with the list register handed back.
 */
class GridSortAnchorsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `each declared build has one grid adapter with both list methods`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val data = mutableListOf<Method>()
            val page = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (GridAdapterSetDataFingerprint.takes(method, classDef)) data += method
                        if (GridAdapterPageFingerprint.takes(method, classDef)) page += method
                    }
                }
            }
            assertEquals("$version: setData ${data.map { it.definingClass }}", 1, data.size)
            assertEquals("$version: setDataAfterLoadMore ${page.map { it.definingClass }}", 1, page.size)
            assertEquals("$version: one class", data.single().definingClass, page.single().definingClass)
        }
    }

    @Test
    fun `the hooks assemble on each declared build and hand the sorted list on`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = bytecodePatch(name = "grid sort probe") {
                execute {
                    fun ops(m: Method) = m.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.toList()
                    val setBefore = ops(GridAdapterSetDataFingerprint.method).map { it.opcode }
                    val pageBefore = ops(GridAdapterPageFingerprint.method).map { it.opcode }
                    GridAdapterSetDataFingerprint.clearMatch()
                    GridAdapterPageFingerprint.clearMatch()
                    assertTrue("$version: the hooks went in", hookGridSort(version))
                    GridAdapterSetDataFingerprint.clearMatch()
                    GridAdapterPageFingerprint.clearMatch()
                    val setAfter = ops(GridAdapterSetDataFingerprint.method)
                    val pageAfter = ops(GridAdapterPageFingerprint.method)

                    val ask = setAfter.first().getReference<MethodReference>()!!
                    assertEquals(
                        "Lapp/morphe/extension/tiktok/feed/GridSort;->data(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;",
                        "${ask.definingClass}->${ask.name}(${ask.parameterTypes.joinToString("")})${ask.returnType}",
                    )
                    assertEquals(
                        listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT) + setBefore,
                        setAfter.map { it.opcode },
                    )
                    val more = pageAfter.first().getReference<MethodReference>()!!
                    assertEquals(
                        "Lapp/morphe/extension/tiktok/feed/GridSort;->page(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;",
                        "${more.definingClass}->${more.name}(${more.parameterTypes.joinToString("")})${more.returnType}",
                    )
                    assertEquals(
                        listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT) + pageBefore,
                        pageAfter.map { it.opcode },
                    )
                    checked = true
                }
            }
            GridAdapterSetDataFingerprint.clearMatch()
            GridAdapterPageFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            GridAdapterSetDataFingerprint.clearMatch()
            GridAdapterPageFingerprint.clearMatch()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `each declared build hands the extension the tab number its Liked tab uses`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val inits = mutableListOf<Method>()
            val data = mutableListOf<Method>()
            val names = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (GridAdapterInitFingerprint.takes(method, classDef)) inits += method
                        if (GridAdapterSetDataFingerprint.takes(method, classDef)) data += method
                        if (GridTabNameFingerprint.takes(method, classDef)) names += method
                    }
                }
            }
            assertEquals("$version: adapter constructors ${inits.map { it.parameterTypes }}", 1, inits.size)
            assertEquals("$version: the adapter's own", data.single().definingClass, inits.single().definingClass)
            assertTrue("$version: the constructor keeps its int in one field", gridTabField(inits.single()) != null)
            assertTrue("$version: no tab name method", names.isNotEmpty())
            for (name in names) {
                assertEquals("$version: ${name.definingClass}->${name.name} names tab 1 the favorite tab", 1, favoriteTabNumber(name))
            }

            var checked = false
            val probe = bytecodePatch(name = "grid tab probe") {
                execute {
                    fun ops(m: Method) = m.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.toList()
                    val before = ops(GridAdapterInitFingerprint.method)
                    GridAdapterInitFingerprint.clearMatch()
                    assertTrue("$version: the hook went in", hookGridTab(version))
                    GridAdapterInitFingerprint.clearMatch()
                    val after = ops(GridAdapterInitFingerprint.method)
                    val returns = before.count { it.opcode == Opcode.RETURN_VOID }
                    assertEquals("$version: three more per return", before.size + 3 * returns, after.size)
                    after.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.forEach { (index, _) ->
                        assertEquals(
                            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET, Opcode.INVOKE_STATIC),
                            after.subList(index - 3, index).map { it.opcode },
                        )
                        val call = after[index - 1].getReference<MethodReference>()!!
                        assertEquals(
                            "Lapp/morphe/extension/tiktok/feed/GridSort;->tab(Ljava/lang/Object;I)V",
                            "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}",
                        )
                    }
                    checked = true
                }
            }
            GridAdapterInitFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            GridAdapterInitFingerprint.clearMatch()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `each declared build has one detail page grid adapter and a sound page click that reaches its page model`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val data = mutableListOf<Method>()
            val page = mutableListOf<Method>()
            val inits = mutableListOf<Method>()
            val clicks = mutableListOf<Method>()
            val soundClicks = mutableListOf<Method>()
            val superclasses = mutableMapOf<String, String?>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    superclasses[classDef.type] = classDef.superclass
                    for (method in classDef.methods) {
                        if (DetailGridSetDataFingerprint.takes(method, classDef)) data += method
                        if (DetailGridPageFingerprint.takes(method, classDef)) page += method
                        if (DetailGridInitFingerprint.takes(method, classDef)) inits += method
                        if (DetailCellOpenFingerprint.takes(method, classDef)) clicks += method
                        if (SoundCellOpenFingerprint.takes(method, classDef)) soundClicks += method
                    }
                }
            }
            assertEquals("$version: setData ${data.map { it.definingClass }}", 1, data.size)
            assertEquals("$version: setDataAfterLoadMore ${page.map { it.definingClass }}", 1, page.size)
            assertEquals("$version: constructors ${inits.map { it.parameterTypes }}", 1, inits.size)
            assertEquals("$version: one class", data.single().definingClass, page.single().definingClass)
            assertEquals("$version: one class", data.single().definingClass, inits.single().definingClass)
            assertTrue("$version: the constructor keeps its label in p1", keepsFirstParameter(inits.single()))

            assertEquals("$version: detail clicks ${clicks.map { it.definingClass }}", 1, clicks.size)
            assertEquals("$version: sound clicks ${soundClicks.map { it.definingClass }}", 1, soundClicks.size)
            val getter = detailModelGetterIn(clicks.single())
            assertTrue("$version: no page model getter in the detail click", getter != null)
            assertEquals("$version: the fragment's own getter", clicks.single().definingClass, getter!!.definingClass)
            val chain = generateSequence(soundClicks.single().definingClass) { superclasses[it] }.toList()
            assertTrue("$version: the sound fragment is a detail fragment $chain", getter.definingClass in chain)
        }
    }

    @Test
    fun `only a detail page grid constructor that keeps its label is hooked at its end`() {
        val keeps = constructor(
            ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 1, 0, 0, 0, 0, ImmutableMethodReference("Ljava/lang/Object;", "<init>", listOf(), "V")),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("x")),
        )
        assertTrue(keepsFirstParameter(keeps))
        // regs 3: v0 local, v1 this, v2 the label.
        val overwrites = constructor(ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("x")))
        assertFalse("a constructor that writes over its label", keepsFirstParameter(overwrites))
    }

    private fun constructor(vararg code: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Method =
        ImmutableMethod(
            "LX/0Detail;", "<init>",
            listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
            ImmutableMethodImplementation(3, code.toList() + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null),
        )

    /** The literal a tab name method compares the tab number to before naming it the favorite tab. */
    private fun favoriteTabNumber(method: Method): Int? {
        val code = method.implementation!!.instructions.toList()
        val starts = IntArray(code.size)
        var at = 0
        code.forEachIndexed { index, instruction ->
            starts[index] = at
            at += instruction.codeUnits
        }
        for (index in 1 until code.size) {
            val branch = code[index]
            val literal = code[index - 1]
            if (branch.opcode != Opcode.IF_EQ) continue
            if (literal.opcode != Opcode.CONST_4 && literal.opcode != Opcode.CONST_16) continue
            if ((literal as OneRegisterInstruction).registerA != (branch as TwoRegisterInstruction).registerB) continue
            val target = starts.indexOf(starts[index] + (branch as OffsetInstruction).codeOffset)
            if (target < 0) continue
            val named = code.drop(target).take(6).mapNotNull { it.getReference<StringReference>()?.string }
            if (named.any { it.endsWith("_favorite_tab") }) return (literal as NarrowLiteralInstruction).narrowLiteral
        }
        return null
    }

    @Test
    fun `each declared build has one cell click per profile grid fragment and hands each the fragment first`() {
        // The Liked tab's grid has its own click; hooking only the other one left its video page
        // in TikTok's order under a sort or a search.
        val clicks = listOf(GridCellOpenFingerprint, ProfileTabCellOpenFingerprint)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (click in clicks) {
                val found = mutableListOf<Method>()
                for (entry in container.dexEntryNames) {
                    for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                        for (method in classDef.methods) {
                            if (click.takes(method, classDef)) found += method
                        }
                    }
                }
                val name = click.javaClass.simpleName
                assertEquals("$version: $name takes ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)
                assertFalse("$version: $name has no fragment to hand over", AccessFlags.STATIC.isSet(found.single().accessFlags))
            }

            var checked = false
            val probe = bytecodePatch(name = "grid open probe") {
                execute {
                    fun ops(m: Method) = m.implementation!!.instructions.filter { it.opcode != Opcode.NOP }.toList()
                    val before = clicks.map { click -> ops(click.method).map { it.opcode } }
                    clicks.forEach { it.clearMatch() }
                    assertTrue("$version: both hooks went in", hookGridOpen(version))
                    clicks.forEach { it.clearMatch() }
                    for ((index, click) in clicks.withIndex()) {
                        val after = ops(click.method)
                        val call = after.first().getReference<MethodReference>()!!
                        assertEquals(
                            "Lapp/morphe/extension/tiktok/feed/GridSort;->beforeOpen(Ljava/lang/Object;)V",
                            "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}",
                        )
                        assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE) + before[index], after.map { it.opcode })
                        // p0 is the first of the method's last registers, the ones its this and parameters arrive in.
                        val hook = after.first() as RegisterRangeInstruction
                        val ins = 1 + click.method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                        assertEquals("$version: ${click.javaClass.simpleName} hands over p0, the fragment",
                            click.method.implementation!!.registerCount - ins, hook.startRegister)
                        assertEquals(1, hook.registerCount)
                    }
                    checked = true
                }
            }
            clicks.forEach { it.clearMatch() }
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            clicks.forEach { it.clearMatch() }
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `each declared build has one grid page loader with its load-more message`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (GridLoadAfterRefreshFingerprint.takes(method, classDef)) found += method
                    }
                }
            }
            assertEquals("$version: ${found.map { it.definingClass }}", 1, found.size)
            val loader = gridPageLoaderIn(found.single())
            assertTrue("$version: no page loader call with a literal message", loader != null)
            assertEquals("$version: loader shape", listOf("I", "I", "Ljava/lang/String;"), loader!!.method.parameterTypes.map { it.toString() })
            assertEquals("$version: the grid fragment's own class", found.single().definingClass, loader.method.definingClass)
        }
    }

    @Test
    fun `only the loader call with a literal message loaded just before it is taken`() {
        val loader = ImmutableMethodReference("LX/0Frag;", "MY", listOf("I", "I", "Ljava/lang/String;"), "V")
        val own = ImmutableMethodReference("LX/0Frag;", "gZ", listOf("I", "I", "Ljava/lang/String;"), "V")
        val found = gridPageLoaderIn(
            caller(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 4),
                ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("load_more_after_refresh")),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 2, 0, 3, 1, 0, own),
                ImmutableInstruction31i(Opcode.CONST, 0, 0x52c63c8e),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 2, 0, 3, 1, 0, loader),
            ),
        )
        assertEquals("MY", found!!.method.name)
        assertEquals(0x52c63c8e, found.message)
        assertEquals("LX/0Frag;->MY(IILjava/lang/String;)V", found.smali)

        val literalInAnotherRegister = gridPageLoaderIn(
            caller(
                ImmutableInstruction31i(Opcode.CONST, 3, 0x52c63c8e),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 2, 0, 3, 1, 0, loader),
            ),
        )
        assertEquals(null, literalInAnotherRegister)
    }

    private fun caller(vararg code: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Method =
        ImmutableMethod(
            "LX/0Frag;", "afterRefresh", listOf(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(5, code.toList() + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null),
        )

    @Test
    fun `only a list method of the class that logs the grid adapter tag is taken`() {
        assertTrue(GridAdapterSetDataFingerprint.takes(method("setData"), adapter(tagged = true)))
        assertTrue(GridAdapterPageFingerprint.takes(method("setDataAfterLoadMore"), adapter(tagged = true)))
        assertFalse("another name", GridAdapterSetDataFingerprint.takes(method("addData"), adapter(tagged = true)))
        assertFalse("static", GridAdapterSetDataFingerprint.takes(method("setData", static = true), adapter(tagged = true)))
        assertFalse("another adapter", GridAdapterSetDataFingerprint.takes(method("setData"), adapter(tagged = false)))
    }

    private fun method(name: String, static: Boolean = false): Method =
        ImmutableMethod(
            "LX/0Grid;", name, listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
            null, null, null,
        )

    /** The class with the cell builder that logs under the tag, or one that doesn't. */
    private fun adapter(tagged: Boolean): ImmutableClassDef {
        val tag = if (tagged) "Profile_AwemeAdapter" else "SomethingElse"
        val builder = ImmutableMethod(
            "LX/0Grid;", "onCreateBasicViewHolder", listOf(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(
                1,
                listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(tag)),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )
        return ImmutableClassDef(
            "LX/0Grid;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(builder),
        )
    }
}
