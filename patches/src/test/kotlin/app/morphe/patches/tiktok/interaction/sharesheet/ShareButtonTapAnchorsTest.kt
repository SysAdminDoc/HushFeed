/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.gesture.VIDEO_SHARE_ASSEM
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native-only tests: the Share button press, the item it shares, and the views it listens to. */
class ShareButtonTapAnchorsTest {
    private data class Found(
        val owner: ClassDef,
        val clicks: List<Method>,
        val created: List<Method>,
        val delegateCompats: Set<String>,
    )

    companion object {
        private val cache = mutableMapOf<String, Found>()
        private const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"
        private const val CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"
    }

    @Test fun `the press reads the cell's own item the way TikTok's sheet does`() {
        Fixtures.forEachDeclared { apk ->
            val click = find(apk).clicks.single()
            val read = click.shareItemRead()
            assertNotNull("the press no longer reads its cell's item", read)
            assertEquals("Lcom/bytedance/assem/arch/reused/ReusedAssem;", read!!.state.definingClass)
            assertEquals("Ljava/lang/Object;", read.value.type)
            val code = click.implementation!!.instructions.toList()
            val cast = code.indexOfFirst {
                it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == VIDEO_ITEM_PARAMS
            }
            assertEquals("the cast item is where the post comes from", "getAweme",
                code[cast + 1].getReference<MethodReference>()?.name)
            // TikTok reads the same pair again where it builds the sheet, so the hook's video is the sheet's.
            val stateReads = code.count { it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>() == read.state }
            assertTrue("TikTok's sheet reads the item another way", stateReads >= 2)
        }
    }

    @Test fun `the press has a local for the answer and names its view in a five register call`() {
        Fixtures.forEachDeclared { apk ->
            val body = find(apk).clicks.single().implementation!!
            assertTrue(body.registerCount <= 16)
            assertTrue(body.registerCount - 2 >= 1)
        }
    }

    @Test fun `the assem listens to its content view, its root and the views inside, all with itself`() {
        Fixtures.forEachDeclared { apk ->
            val created = find(apk).created.single()
            val sites = created.clickListenerSites()
            val code = created.implementation!!.instructions.toList()
            assertTrue("the content view and the root, listener first", sites.count {
                code[it.index].getReference<MethodReference>()!!.parameterTypes.first().toString() == CLICK_LISTENER
            } == 2)
            assertTrue("and the button inside, view first", sites.any {
                code[it.index].getReference<MethodReference>()!!.parameterTypes.last().toString() == CLICK_LISTENER
            })
            // Every one names the same listener register, the assem's copy of this.
            assertEquals(1, sites.map { it.listener }.toSet().size)
            assertNull("the item read matches a method that has none", created.shareItemRead())
        }
    }

    /**
     * TikTok hands the Share button its own screen reader delegate after listening to it, which
     * replaced the label the listener site gave (the S22 read TikTok's plain click until the
     * cell's first press, 2026-11-22). The hook binds after that call too.
     */
    @Test fun `TikTok's screen reader delegate lands on a view the assem listens to, after it listens`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val created = found.created.single()
            val code = created.implementation!!.instructions.toList()
            val delegated = created.accessibilityDelegateSites { it in found.delegateCompats }
            assertTrue("no ViewCompat delegate set in onViewCreated", delegated.isNotEmpty())
            val listened = created.clickListenerSites().associateBy { idOf(code, it.index, it.view) }
            for (site in delegated) {
                val id = idOf(code, site.index, site.view)
                assertNotNull("the delegate's view comes from findViewById", id)
                val listener = listened[id]
                assertNotNull("the delegate goes on a view the assem listens to", listener)
                assertTrue("after the listener set, so a bind at the listener alone is replaced",
                    site.index > listener!!.index)
            }
        }
    }

    @Test fun `the hook labels every view right after the assem listens to it and after TikTok's delegate`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val stock = found.created.single()
            val listened = stock.clickListenerSites().size
            val delegated = stock.accessibilityDelegateSites { it in found.delegateCompats }.size
            val hooked = MutableMethod(stock).apply { bindShareLabels { it in found.delegateCompats } }
            val code = hooked.implementation!!.instructions.toList()
            val binds = code.indices.filter { at ->
                val call = code[at].getReference<MethodReference>()
                call != null && call.definingClass == SHARE_BUTTON_TAP && call.name == "bind"
            }
            assertEquals("one bind per listener site and per delegate set", listened + delegated, binds.size)
            assertTrue("no ViewCompat delegate set to follow", delegated > 0)
            for (site in hooked.clickListenerSites() + hooked.accessibilityDelegateSites { it in found.delegateCompats }) {
                val bind = code[site.index + 1]
                assertTrue("a site at ${site.index} has no bind right after it", site.index + 1 in binds)
                assertEquals("the bind names the site's view", site.view, (bind as RegisterRangeInstruction).startRegister)
                assertEquals(1, bind.registerCount)
            }
        }
    }

    /** The resource id a view register was found by, reading back from the call at [index]. */
    private fun idOf(code: List<Instruction>, index: Int, register: Int): Int? {
        var at = index - 1
        while (at >= 0) {
            val instruction = code[at]
            if (instruction.opcode == Opcode.MOVE_RESULT_OBJECT && (instruction as OneRegisterInstruction).registerA == register) break
            at--
        }
        val find = code.getOrNull(at - 1) ?: return null
        if (find.getReference<MethodReference>()?.name != "findViewById") return null
        val idRegister = (find as FiveRegisterInstruction).registerD
        for (back in at - 2 downTo 0) {
            val load = code[back]
            if (load is NarrowLiteralInstruction && load is OneRegisterInstruction && load.registerA == idRegister) {
                return load.narrowLiteral
            }
        }
        return null
    }

    private fun find(apk: File): Found = synchronized(cache) {
        cache.getOrPut(apk.absolutePath) {
            var owner: ClassDef? = null
            val clicks = mutableListOf<Method>()
            val created = mutableListOf<Method>()
            val compats = mutableSetOf<String>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.isAccessibilityDelegateCompat()) compats += classDef.type
                if (classDef.type != VIDEO_SHARE_ASSEM) continue
                owner = classDef
                for (method in classDef.methods) {
                    if (ShareButtonClickFingerprint.takes(method, classDef)) clicks += method
                    if (ShareButtonViewCreatedFingerprint.takes(method, classDef)) created += method
                }
            }
            Found(owner ?: error("$VIDEO_SHARE_ASSEM is missing from ${apk.name}"), clicks, created, compats)
        }
    }
}
