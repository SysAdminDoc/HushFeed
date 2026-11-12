package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.navigation.writes
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams the Feed filter takes the Ask Tako surfaces off the search results page through, held
 * to what the patch assumes on every retained fixture: the served tab list response keeps its name,
 * its two list getters keep theirs, each opens with exactly one read of the tabList field and
 * nothing else on the response reads that field, the tab info keeps the key and name getters the
 * extension reads, and the fragment that inflates the strip keeps the onViewCreated the pill is
 * hidden from. On each declared build the "People also ask Tako" card in the results keeps the
 * onViewCreated it is hidden from.
 */
class SearchTabAnchorsTest {
    private val tabList = "Lcom/ss/android/ugc/aweme/search/pages/result/common/tabs/core/model/SearchDynamicTabList;"
    private val tabInfo = "Lcom/ss/android/ugc/aweme/search/pages/result/common/tabs/core/model/SearchDynamicTabInfo;"
    private val containerFragment = "Lcom/ss/android/ugc/aweme/search/pages/core/ui/fragment/SearchContainerFragment;"
    private val takoSugCard = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/tako/SearchTakoSugCardAssem;"
    private val searchCardHolder = "Lcom/ss/android/ugc/aweme/search/arch/v2/protocol/card/parser/SearchCardViewHolder;"

    @Test
    fun `every retained fixture serves the search tab list through the two getters the patch filters`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val app = load(apk)
            val list = app[tabList] ?: error("${apk.name}: no $tabList")
            listOf("getTabList", "getSearchTabList").forEach { getter ->
                val methods = list.methods.filter {
                    it.name == getter && it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" && it.implementation != null
                }
                assertEquals("${apk.name}: $getter on the tab list response", 1, methods.size)
                val reads = methods.single().implementation!!.instructions.filter { it.isSearchTabListRead() }
                assertEquals("${apk.name}: reads of tabList in $getter", 1, reads.size)
                val register = (reads.single() as TwoRegisterInstruction).registerA
                assertTrue("${apk.name}: $getter reads the list into v$register, a low register", register < 16)
            }
            // A third reader on the response (a copy or a component of a data class) would hand
            // the strip the list past the filter.
            val readers = list.methods
                .filter { method -> method.implementation?.instructions?.any { it.isSearchTabListRead() } == true }
                .map { it.name }
                .sorted()
            assertEquals("${apk.name}: readers of tabList on the response", listOf("getSearchTabList", "getTabList"), readers)
            val info = app[tabInfo] ?: error("${apk.name}: no $tabInfo")
            listOf("getTabKey", "getTabName").forEach { getter ->
                assertEquals(
                    "${apk.name}: $getter on the tab info",
                    1,
                    info.methods.count { it.name == getter && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" },
                )
            }
        }
    }

    @Test
    fun `every retained fixture builds the strip in the container fragment's onViewCreated`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val app = load(apk)
            val fragment = app[containerFragment] ?: error("${apk.name}: no $containerFragment")
            val created = fragment.methods.filter {
                it.name == "onViewCreated" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "Landroid/os/Bundle;") &&
                    it.returnType == "V" &&
                    it.implementation != null
            }
            assertEquals("${apk.name}: onViewCreated(View, Bundle) on the search container fragment", 1, created.size)
            assertTrue(
                "${apk.name}: onViewCreated returns somewhere the pill hook can sit",
                created.single().implementation!!.instructions.any { it.opcode == Opcode.RETURN_VOID },
            )
        }
    }

    @Test
    fun `every declared build lays out the people also ask Tako card in an onViewCreated the hook can return through`() {
        Fixtures.forEachDeclared { apk ->
            val card = load(apk)[takoSugCard] ?: error("${apk.name}: no $takoSugCard")
            val created = card.methods.filter {
                it.name == "onViewCreated" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") &&
                    it.returnType == "V" &&
                    it.implementation != null
            }
            assertEquals("${apk.name}: onViewCreated(View) on the Tako questions card", 1, created.size)
            val implementation = created.single().implementation!!
            assertTrue(
                "${apk.name}: onViewCreated returns somewhere the card hook can sit",
                implementation.instructions.any { it.opcode == Opcode.RETURN_VOID },
            )
            // The hook hands p1 to the extension at each return, so nothing may write over it.
            val view = implementation.registerCount - 1
            assertTrue(
                "${apk.name}: onViewCreated keeps the view in p1 (v$view) to its returns",
                implementation.instructions.none { it.writes(view) },
            )
        }
    }

    @Test
    fun `every declared build builds its search card holders in one constructor the answer card hook can return through`() {
        Fixtures.forEachDeclared { apk ->
            val classes = load(apk)
            val holder = classes[searchCardHolder] ?: error("${apk.name}: no $searchCardHolder")
            val built = holder.methods.filter { SearchCardViewHolderConstructorFingerprint.takes(it, holder) }
            assertEquals("${apk.name}: search card holder constructors the fingerprint takes", 1, built.size)
            val code = built.single().implementation!!
            val instructions = code.instructions.toList()
            assertTrue("${apk.name}: the constructor returns somewhere the hook can sit", instructions.any { it.opcode == Opcode.RETURN_VOID })
            // p0, then one register for each of the six object parameters: the view is p1 and the
            // protocol p4, which the hook hands over with a plain invoke.
            val view = code.registerCount - 6
            val protocol = code.registerCount - 3
            assertTrue("${apk.name}: the protocol sits in v$protocol, past a plain invoke", protocol <= 15)
            assertTrue("${apk.name}: the constructor writes over its view or protocol",
                instructions.none { it.writes(view) || it.writes(protocol) })
            // The view is the holder's item view: the one the super constructor is handed.
            val superCall = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_DIRECT && (it as ReferenceInstruction).reference.let { reference ->
                    reference is MethodReference && reference.definingClass == holder.superclass &&
                        reference.name == "<init>" && reference.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")
                }
            }
            assertTrue("${apk.name}: no super constructor taking the view", superCall >= 0)
            val handed = (instructions[superCall] as FiveRegisterInstruction).registerD
            val source = instructions.subList(0, superCall).lastOrNull { it.writes(handed) }
            assertTrue("${apk.name}: the super constructor isn't handed p1",
                handed == view || (source?.opcode == Opcode.MOVE_OBJECT || source?.opcode == Opcode.MOVE_OBJECT_FROM16) &&
                    (source as TwoRegisterInstruction).registerB == view)
            val ancestors = generateSequence(holder.superclass) { classes[it]?.superclass }.take(12).toList()
            assertTrue("${apk.name}: the holder is no longer a RecyclerView ViewHolder: $ancestors",
                "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;" in ancestors)
            // A holder is built for one protocol: the protocol makes the holder's UI, and the holder
            // keeps it in a final field every bind goes through. A holder made for an AI answer only
            // ever shows AI answers, so its row can stay collapsed through rebinds.
            assertTrue("${apk.name}: the protocol no longer builds the holder's UI",
                instructions.any { instruction ->
                    instruction.opcode == Opcode.INVOKE_INTERFACE &&
                        (instruction as FiveRegisterInstruction).registerC == protocol &&
                        ((instruction as ReferenceInstruction).reference as MethodReference).let {
                            it.definingClass == SEARCH_CARD_PROTOCOL_CLASS_DESCRIPTOR && it.parameterTypes.isEmpty() &&
                                it.returnType == "Lcom/bytedance/assem/arch/reused/ReusedUIAssem;"
                        }
                })
            val kept = instructions
                .filter { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == protocol }
                .map { (it as ReferenceInstruction).reference as FieldReference }
            assertTrue("${apk.name}: the holder doesn't keep its protocol in a final field of its own",
                kept.any { field ->
                    field.definingClass == holder.type && holder.fields.any {
                        it.name == field.name && it.type == SEARCH_CARD_PROTOCOL_CLASS_DESCRIPTOR && AccessFlags.FINAL.isSet(it.accessFlags)
                    }
                })
        }
    }

    @Test
    fun `every declared build fills the default search tab list in one method the picked tabs leave too`() {
        Fixtures.forEachDeclared { apk ->
            val taken = load(apk).values.flatMap { classDef ->
                classDef.methods.filter { SearchDefaultTabsFillFingerprint.takes(it, classDef) }
            }
            assertEquals("${apk.name}: methods the default tabs fingerprint takes", 1, taken.size)
            val fills = taken.single().implementation!!.instructions.filter { it.isTabListFill() }
            assertTrue("${apk.name}: no fill of the default list", fills.isNotEmpty())
            fills.forEach { fill ->
                // The hook reads and writes back the source list, the second argument.
                assertEquals("${apk.name}: a fill takes two lists", 2, (fill as FiveRegisterInstruction).registerCount)
            }
        }
    }

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
