/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BEAN = "Lcom/ss/android/ugc/aweme/notice/repo/list/bean/"
private const val UI = "Lcom/ss/android/ugc/aweme/notice/repo/list/uitemplate/"

/**
 * Group likes per comment: the page handler it hooks, and every field ActivityLikeBundler reads or
 * writes by name, held to the declared build so a rename fails here rather than on a phone.
 */
class GroupActivityLikesAnchorsTest {
    @Test
    fun `the page handler is one method that takes the response and a load reason with LOAD_MORE`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val vm = dex.classOf(NOTIFICATION_RESULT_VM_DESCRIPTOR)
            val handlers = vm.methods.filter { NotificationPageHandlerFingerprint.takes(it, vm) }
            assertEquals("${apk.name}: page handlers", 1, handlers.size)
            val handler = handlers.single()
            assertTrue(!AccessFlags.STATIC.isSet(handler.accessFlags))
            val reason = dex.classOf(handler.parameterTypes[1].toString())
            assertEquals("Ljava/lang/Enum;", reason.superclass)
            val constants = reason.fields.filter { AccessFlags.ENUM.isSet(it.accessFlags) }.map { it.name }.toSet()
            assertTrue("${apk.name}: load reasons $constants", constants.containsAll(setOf("LOAD_MORE", "FIRST_REFRESH", "REFRESH")))
            assertTrue(vm.methods.any { it.name == "getGroupType" && it.parameterTypes.isEmpty() && it.returnType == "I" })
        }
    }

    @Test
    fun `the fields the bundler reads and writes keep their names and types`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            fun field(owner: String, name: String, type: String) {
                // Declared on the class or a superclass, the way Reflect.field looks it up.
                var current: ClassDef? = dex.classOf(owner)
                while (current != null) {
                    val found = current.fields.firstOrNull { it.name == name && !AccessFlags.STATIC.isSet(it.accessFlags) }
                    if (found != null) {
                        assertEquals("${apk.name}: $owner.$name", type, found.type)
                        return
                    }
                    current = current.superclass?.let { superclass -> dex.classOrNull(superclass) }
                }
                throw AssertionError("${apk.name}: $owner has no field $name")
            }
            field(NOTICE_LISTS_RESPONSE_DESCRIPTOR, "notices", "Ljava/util/List;")
            field("${BEAN}NoticeItems;", "items", "Ljava/util/List;")
            field("${BEAN}MusNotice;", "nid", "Ljava/lang/String;")
            field("${BEAN}MusNotice;", "type", "I")
            field("${BEAN}MusNotice;", "hasRead", "Z")
            field("${BEAN}MusNotice;", "templateNotice", "${BEAN}GeneralTemplateNotice;")
            field("${BEAN}GeneralTemplateNotice;", "legacySubType", "Ljava/lang/Integer;")
            field("${BEAN}GeneralTemplateNotice;", "hasRead", "Z")
            field("${BEAN}GeneralTemplateNotice;", "extraData", "${BEAN}NoticeExtraData;")
            field("${BEAN}GeneralTemplateNotice;", "uiTemplate", "${UI}NoticeUITemplate;")
            field("${BEAN}NoticeExtraData;", "aweme", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;")
            field("${BEAN}NoticeExtraData;", "comment", "Lcom/ss/android/ugc/aweme/comment/model/Comment;")
            field("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "cid", "Ljava/lang/String;")
            field("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "awemeId", "Ljava/lang/String;")
            field("${UI}NoticeUITemplate;", "titleTemplate", "${UI}TitleTemplate;")
            field("${UI}TitleTemplate;", "mergeCount", "I")
            field("${UI}TitleTemplate;", "fromUsers", "Ljava/util/List;")
            assertTrue(dex.classOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;").methods
                .any { it.name == "getAid" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" })
            assertTrue(dex.classOf("Lcom/bytedance/ies/powerlist/PowerCell;").methods
                .any { it.name == "getAttachView\$powerlist_release" && it.parameterTypes.isEmpty() })
        }
    }

    @Test
    fun `the notification cell's plain bind is one method taking the item`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val cell = dex.classOf(TEMPLATE_NOTIFICATION_CELL)
            val binds = cell.methods.filter { InboxNotificationNormalBind.takes(it, cell) }
            assertEquals("${apk.name}: plain binds", 1, binds.size)
            assertEquals(1, binds.single().parameterTypes.size)
        }
    }

    private fun MultiDexContainer<out DexFile>.classOrNull(type: String): ClassDef? =
        dexEntryNames.asSequence().flatMap { getEntry(it)!!.dexFile.classes.asSequence() }.firstOrNull { it.type == type }

    private fun MultiDexContainer<out DexFile>.classOf(type: String): ClassDef =
        classOrNull(type) ?: throw AssertionError("no class $type")
}
