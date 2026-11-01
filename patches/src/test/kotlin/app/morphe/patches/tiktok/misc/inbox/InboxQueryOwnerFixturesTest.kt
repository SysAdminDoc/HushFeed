package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import org.junit.Assert.*
import org.junit.Test
import java.io.File

private const val FILTER = "Lapp/morphe/extension/tiktok/inbox/InboxFilter;"
private const val CELL = "Lcom/bytedance/ies/powerlist/PowerCell;"
private const val STUB = "Lcom/bytedance/ies/powerlist/PowerStub;"
private const val ADAPTER = "Lcom/bytedance/ies/powerlist/PowerAdapter;"
private const val FRAGMENT = "Lcom/ss/android/ugc/aweme/notification/v2/NotificationResultFragment;"
private const val WIDGET = "Lcom/ss/android/ugc/aweme/notification/v2/widget/NotificationWidget;"
private const val MUS_NOTICE = "Lcom/ss/android/ugc/aweme/notice/repo/list/bean/MusNotice;"

/** The current notification cell and VM identity contracts used by loaded-only search. */
class InboxQueryOwnerFixturesTest {
    @Test
    fun `both current native bind variants capture after success without replacing a listener or model`() {
        assertEquals(listOf("47.6.3"), Fixtures.declaredVersions())
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val cell = dex.classOf(TEMPLATE_NOTIFICATION_CELL)
            // PowerCell's item is a renamed class (47.4.3 LX/05u8;, 47.5.3 LX/05w2;): the binds are
            // told apart by the payload list alone, and each fingerprint takes its own and only it.
            val binds = cell.methods.filter { it.name == "onBindItemView" }
            assertEquals(2, binds.size)
            val item = dex.classOf(CELL).method("getItem").references<FieldReference>().single().type
            listOf(InboxNotificationNormalBind to listOf(item), InboxNotificationPayloadBind to listOf(item, "Ljava/util/List;"))
                .forEach { (fingerprint, parameters) ->
                    val native = binds.single { it.parameterTypes.map(CharSequence::toString) == parameters }
                    assertEquals(listOf(native), binds.filter { fingerprint.takes(it, cell) })
                    assertBindUnchanged(native)
                }
            // The current model getter is what native clicks read after a payload rebind/recycle.
            val powerCell = dex.classOf(CELL)
            assertEquals("item", powerCell.method("getItem").references<FieldReference>().single().name)
            assertEquals("attachView", powerCell.method("getAttachView\$powerlist_release").references<FieldReference>().single().name)
            assertEquals("stub", powerCell.method("getStub").references<FieldReference>().single().name)
            val noticeGetter = cell.methods.single { it.parameterTypes.isEmpty() && it.returnType == MUS_NOTICE }
            assertTrue(noticeGetter.references<MethodReference>().any { it.definingClass == CELL && it.name == "getItem" })
            assertTrue(cell.method("onClick").references<MethodReference>().any { it.definingClass == cell.type && it.name == noticeGetter.name })
        }
    }

    @Test
    fun `the observed VM is the native returned object and its lazy getter runs only once`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val widget = dex.classOf(WIDGET)
            val native = widget.methods.single { InboxNotificationVmGetter.takes(it, widget) }
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val receiver = mutable.implementation!!.registerCount - 1
            mutable.captureLoadedInboxVm()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size + original.count { it.opcode == Opcode.RETURN_OBJECT }, after.size)
            var source = 0
            after.forEachIndexed { index, instruction ->
                val reference = instruction.getReference<MethodReference>()
                if (reference?.definingClass != FILTER) { assertSame(original[source++], instruction); return@forEachIndexed }
                assertEquals("onNotificationVmObserved", reference.name)
                assertEquals(listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), reference.parameterTypes.map(CharSequence::toString))
                val call = instruction as FiveRegisterInstruction
                assertEquals(receiver, call.registerC)
                assertEquals(2, call.registerCount)
                assertEquals(Opcode.RETURN_OBJECT, after[index + 1].opcode)
                assertEquals((after[index + 1] as OneRegisterInstruction).registerA, call.registerD)
            }
            assertEquals(original.size, source)
            assertEquals(1, after.count { it.getReference<MethodReference>()?.definingClass == "Lcom/bytedance/assem/arch/viewModel/AssemVMLazy;" })
        }
    }

    @Test
    fun `native owner state and manager identities have the exact current signatures`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            // 47.5.3 renamed the PowerList classes (47.4.3 LX/09Mx; LX/09MC; LX/09LW; LX/09Nj;
            // LX/09Ni; LX/0NaB; and BX for the recycler getter). The member names come from the
            // extension itself: the 47.4.3 retarget left them on 47.1.4 and search went quiet.
            val names = extensionNames()
            val stub = dex.classOf(STUB)
            assertEquals("LX/09Ft;", stub.method(names.getValue("STUB_STATE")).returnType)
            assertEquals("LX/09FG;", stub.fields.single { it.name == names.getValue("STUB_ADAPTER") }.type)
            val adapter = dex.classOf(ADAPTER)
            assertEquals(STUB, adapter.methods.single { it.name == names.getValue("ADAPTER_PRIMARY_STUB") && it.parameterTypes.isEmpty() }.returnType)
            assertEquals("LX/09Fc;", adapter.fields.single { it.name == names.getValue("ADAPTER_DIFF") }.type)
            assertEquals("Lcom/bytedance/ies/powerlist/page/PowerPageLoader;",
                adapter.fields.single { it.name == names.getValue("ADAPTER_PAGER") }.type)
            val recycler = dex.classOf("LX/09F4;")
            assertEquals("LX/09Ft;", recycler.method("getState").returnType)
            val state = dex.classOf("LX/09Ft;")
            assertEquals("Lcom/bytedance/ies/powerlist/ListState;", state.superclass)
            assertEquals("Ljava/util/List;", state.fields.single { it.name == "LJ" }.type)
            assertEquals("Ljava/util/concurrent/locks/ReentrantLock;", state.fields.single { it.name == "LJFF" }.type)
            val fragment = dex.classOf(FRAGMENT)
            assertEquals(recycler.type, fragment.method(names.getValue("FRAGMENT_LIST")).returnType)
            val spring = dex.classOf("LX/09GH;")
            assertEquals(spring.type, fragment.method(names.getValue("FRAGMENT_SPRING")).returnType)
            assertEquals("LX/09GG;", spring.superclass)
            val constrainedParent = dex.classOf(spring.superclass!!)
            assertEquals("Landroid/widget/FrameLayout;", constrainedParent.superclass)
            assertTrue(constrainedParent.method("getNestedChild").references<MethodReference>()
                .any { it.name == "getChildCount" })
            assertTrue(constrainedParent.method("getNestedChild").references<MethodReference>()
                .any { it.definingClass == "Ljava/lang/IllegalArgumentException;" && it.name == "<init>" })
            assertEquals("LX/0La3;", fragment.method("FX").returnType)
            assertEquals("Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidgetContainer;",
                fragment.fields.single { it.name == names.getValue("FRAGMENT_CONTAINER") }.type)
        }
    }

    private fun assertBindUnchanged(native: Method) {
        val mutable = MutableMethod(native)
        val original = mutable.implementation!!.instructions.toList()
        val receiver = mutable.implementation!!.registerCount - mutable.parameterTypes.size - 1
        mutable.captureLoadedInboxOwner()
        val after = mutable.implementation!!.instructions.toList()
        assertEquals(original.size + original.count { it.opcode == Opcode.RETURN_VOID }, after.size)
        var source = 0
        after.forEachIndexed { index, instruction ->
            val reference = instruction.getReference<MethodReference>()
            if (reference?.definingClass != FILTER) { assertSame(original[source++], instruction); return@forEachIndexed }
            assertEquals("onNotificationRowBound", reference.name)
            val call = instruction as RegisterRangeInstruction
            assertEquals(receiver, call.startRegister)
            assertEquals(1, call.registerCount)
            assertEquals(Opcode.RETURN_VOID, after[index + 1].opcode)
        }
        assertEquals(original.size, source)
    }

    /** The TikTok member names InboxQueryOwners declares, each of which this test checks. */
    private fun extensionNames(): Map<String, String> {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val source = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/inbox/InboxQueryOwners.java").readText()
        val names = Regex("""static final String (\w+) = "(\w+)";""").findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("FRAGMENT_LIST", "FRAGMENT_SPRING", "FRAGMENT_CONTAINER", "ADAPTER_PRIMARY_STUB",
            "ADAPTER_DIFF", "ADAPTER_PAGER", "STUB_STATE", "STUB_ADAPTER"), names.keys)
        return names
    }

    private fun ClassDef.method(name: String): Method = methods.single { it.name == name }
    private fun MultiDexContainer<out DexFile>.classOf(type: String): ClassDef =
        dexEntryNames.flatMap { getEntry(it)!!.dexFile.classes }.single { it.type == type }
    private inline fun <reified T : Reference> Method.references(): List<T> = implementation!!.instructions.mapNotNull { it.getReference<T>() }
}
