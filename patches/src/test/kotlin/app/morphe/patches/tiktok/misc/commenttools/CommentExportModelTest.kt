package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Export comments reads off TikTok's comment model, held to every declared build.
 *
 * The export adds no hook. It reads the comments the search box already collects, by field name,
 * so a build that renames one of these would export an empty column instead of failing. This says
 * each name is still there with the type the writer expects.
 */
class CommentExportModelTest {
    private val comment = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"

    private val expected = mapOf(
        "cid" to "Ljava/lang/String;",
        "text" to "Ljava/lang/String;",
        "user" to "Lcom/ss/android/ugc/aweme/profile/model/User;",
        "createTime" to "I",
        "diggCount" to "I",
        "replyCount" to "I",
        "replyCommentTotal" to "J",
        "replyComments" to "Ljava/util/List;",
        "rootCommentId" to "Ljava/lang/String;",
        "stickPosition" to "I",
        "authorPin" to "Z",
        "isAuthorDigged" to "Z",
        "commentLanguage" to "Ljava/lang/String;",
        "imageList" to "Ljava/util/List;",
        "awemeId" to "Ljava/lang/String;",
    )

    @Test
    fun `the comment model keeps every field the export reads on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val found = HashMap<String, String>()
            for (entry in container.dexEntryNames) {
                val classDef = container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == comment } ?: continue
                classDef.instanceFields.forEach { found[it.name] = it.type }
                break
            }
            assertNotNull("no Comment class", found.takeIf { it.isNotEmpty() })
            expected.forEach { (name, type) -> assertEquals("Comment.$name", type, found[name]) }
        }
    }

    /**
     * The reply call ReplyPages makes, read the way it reads it: a shared CommentApiV2 in a static
     * field, a suspend method on its interface annotated with the reply path, and the four queries
     * paging needs among its parameters. A build that renames any of them leaves an export with
     * only the replies the sheet showed, so this fails first.
     */
    @Test
    fun `the reply call keeps the shape the export pages through on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val api = classes.getValue("Lcom/ss/android/ugc/aweme/commentv2/commentlist/CommentApiV2;")
            assertTrue("no shared CommentApiV2", api.staticFields.any { it.type == api.type })
            val paged = api.interfaces.mapNotNull(classes::get).flatMap { it.virtualMethods }.filter { method ->
                value(method.annotations) == "/aweme/v1/comment/list/reply/" && method.returnType == "Ljava/lang/Object;"
            }.filter { method ->
                val queries = method.parameters.dropLast(1).associate { value(it.annotations) to it.type }
                val continuation = classes[method.parameterTypes.last().toString()]
                queries["comment_id"] == "Ljava/lang/String;" && queries["item_id"] == "Ljava/lang/String;" &&
                    queries["cursor"] in setOf("J", "I", "Ljava/lang/Long;", "Ljava/lang/Integer;") &&
                    queries["count"] in setOf("I", "J", "Ljava/lang/Integer;", "Ljava/lang/Long;") &&
                    continuation != null && AccessFlags.INTERFACE.isSet(continuation.accessFlags)
            }
            assertEquals("paged reply calls on ${Fixtures.versionOf(apk)}", 1, paged.size)

            val list = classes.getValue("Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;")
            val getters = list.virtualMethods.filter { it.parameterTypes.isEmpty() }.associate { it.name to it.returnType }
            assertEquals("Ljava/util/List;", getters["getItems"])
            assertEquals("J", getters["getCursor"])
            assertEquals("Z", getters["isHasMore"])
        }
    }

    private fun value(annotations: Set<Annotation>): String? = annotations.firstNotNullOfOrNull { annotation ->
        (annotation.elements.firstOrNull { it.name == "value" }?.value as? StringEncodedValue)?.value
    }

    private fun classes(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk)
        val result = HashMap<String, ClassDef>()
        for (entry in container.dexEntryNames) {
            for (owner in container.getEntry(entry)!!.dexFile.classes) result[owner.type] = owner
        }
        return result
    }
}
