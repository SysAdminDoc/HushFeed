package app.morphe.patches.tiktok.interaction.looping

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Keep the next videos in full screen stands on, held to each declared build: the Full screen
 * button's handler sets the viewer's list in the shape the hook looks for, the viewer's first page
 * is built where the whole list can go in, TikTok's viewer test can be called from the extension,
 * and the adapter the extension reads keeps getCount and getItem under their real names and public.
 */
class FullScreenNextVideosAnchorsTest {
    private fun classes(apk: java.io.File): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
    }

    @Test
    fun `the Full screen button sets the viewer's list where the hook can reach it`() {
        Fixtures.forEachDeclared { apk ->
            val entrance = classes(apk).single { it.type == FULL_SCREEN_ENTRANCE }
            val handlers = entrance.methods.filter {
                it.returnType == "V" && it.parameterTypes.map { type -> type.toString() } == listOf("I", "I", "I", "Ljava/lang/String;")
            }
            assertEquals("handlers: ${handlers.map { it.name }}", 1, handlers.size)
            val set = handlers.single().fullScreenListSet()
            assertNotNull("the handler no longer clears and fills one ArrayList", set)
        }
    }

    @Test
    fun `the viewer's first page takes the whole list and moves the read position`() {
        Fixtures.forEachDeclared { apk ->
            val all = classes(apk)
            val viewModel = all.single { it.type == FULL_SCREEN_VIEW_MODEL }
            val pages = viewModel.methods.filter {
                it.returnType == "Ljava/util/List;" && it.parameterTypes.isEmpty() &&
                    it.implementation?.instructions?.any { code ->
                        code.getReference<StringReference>()?.string == FULL_SCREEN_FIRST_PAGE_TAG
                    } == true
            }
            assertEquals("first pages: ${pages.map { it.name }}", 1, pages.size)
            val native = pages.single()
            val page = native.fullScreenFirstPage()
            assertNotNull("the first page's shape changed", page)
            val holder = all.single { it.type == page!!.list.definingClass }
            val position = holder.fullScreenReadPosition()
            assertNotNull("${holder.type} has no read position", position)
            assertTrue("${holder.type} isn't public", AccessFlags.PUBLIC.isSet(holder.accessFlags))
            assertTrue(
                "the read position isn't public",
                AccessFlags.PUBLIC.isSet(holder.fields.single { it.type == "J" }.accessFlags),
            )
            val patched = MutableMethod(native)
            patched.addInstructions(page!!.at, fullScreenFirstPageHook(page, position!!))
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
        }
    }

    @Test
    fun `the extension can call TikTok's viewer test`() {
        Fixtures.forEachDeclared { apk ->
            val all = classes(apk)
            val tests = all.flatMap { owner -> owner.methods.filter { it.isViewerTest() }.map { owner to it } }
            assertEquals("viewer tests: ${tests.map { it.second.name }}", 1, tests.size)
            val (owner, test) = tests.single()
            assertTrue("${owner.type} isn't public", AccessFlags.PUBLIC.isSet(owner.accessFlags))
            assertTrue("${test.name} isn't public", AccessFlags.PUBLIC.isSet(test.accessFlags))
            val bridge = fullScreenPlayableBridge(test, AccessFlags.STATIC.value)
            assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
        }
    }

    @Test
    fun `the pager's adapter keeps getCount and getItem`() {
        Fixtures.forEachDeclared { apk ->
            val all = classes(apk)
            val adapters = all.filter { type ->
                type.methods.any { it.name == "getItem" && it.returnType == FULL_SCREEN_VIDEO && it.parameterTypes.map { p -> p.toString() } == listOf("I") } &&
                    type.methods.any { it.name == "getCount" && it.returnType == "I" && it.parameterTypes.isEmpty() } &&
                    type.methods.any { it.name == "setData" && it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/util/List;") }
            }
            assertTrue("no adapter holds videos under getItem and getCount", adapters.isNotEmpty())
            for (adapter in adapters) {
                for (name in listOf("getItem", "getCount")) {
                    val method = adapter.methods.single { it.name == name && (name != "getItem" || it.returnType == FULL_SCREEN_VIDEO) }
                    assertTrue("${adapter.type}.$name isn't public", AccessFlags.PUBLIC.isSet(method.accessFlags))
                }
            }
        }
    }

    private fun Method.isViewerTest() =
        returnType == "Z" && parameterTypes.map { it.toString() } == listOf(FULL_SCREEN_VIDEO) &&
            AccessFlags.STATIC.isSet(accessFlags) &&
            implementation?.instructions?.any { it.getReference<StringReference>()?.string == "landscape_override_exp" } == true
}
