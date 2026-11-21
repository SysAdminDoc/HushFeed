/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SHARE_EXT_SERVICE = "Lcom/ss/android/ugc/aweme/share/ShareExtServiceImpl;"
private const val SHARE_INFO_KT = "Lcom/ss/android/ugc/aweme/comment/model/CommentShareInfoKt;"

/**
 * Share any comment (#481) answers 0 from a comment's two share blocks: the reason it can't go
 * to a chat, and its share ACL's code. Held to every declared build: each getter is a plain field
 * read, only TikTok's share checks ask them, a chat share is still refused for a comment whose
 * status rules it out, and the shared link is built on the phone from the video's link.
 */
class ShareAnyCommentAnchorsTest {
    @Test
    fun `both share blocks are plain getters and the guard answers 0 in front of each`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val build = Build(apk)
            for ((fingerprint, field) in listOf(
                CommentDmShareBlockFingerprint to "disableShareToDmReason",
                CommentShareAclCodeFingerprint to "code",
            )) {
                val taken = build.methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }.toList()
                assertEquals("$version: ${fingerprint.javaClass.simpleName}", 1, taken.size)
                val getter = taken.single().second
                val stock = getter.implementation!!.instructions.toList()
                assertEquals("$version: $field getter", listOf(Opcode.IGET, Opcode.RETURN), stock.map { it.opcode })
                assertEquals("$version: $field getter's field", field, stock[0].getReference<FieldReference>()!!.name)

                val patched = MutableMethod(getter)
                patched.answerShareable()
                val after = patched.implementation!!.instructions.toList()
                assertEquals(
                    "$version: the guard on $field",
                    listOf(
                        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN,
                        Opcode.NOP, Opcode.IGET, Opcode.RETURN,
                    ),
                    after.map { it.opcode },
                )
                val asked = after[0].getReference<MethodReference>()!!
                assertEquals(SHARE_ANY_COMMENT_CLASS_DESCRIPTOR, asked.definingClass)
                assertEquals("allow", asked.name)
            }
        }
    }

    @Test
    fun `only TikTok's share checks ask the blocks, and the chat check rules out a comment's status first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val build = Build(apk)
            val dmAskers = build.callers(SHARE_BLOCK_COMMENT, "getDisableShareToDmReason")
            assertEquals("$version: chat share askers ${dmAskers.map { it.second.name }}", 1, dmAskers.size)
            val dmCheck = dmAskers.single().second.implementation!!.instructions.toList()
            val statusAt = dmCheck.indexOfFirst { it.getReference<MethodReference>()?.name == "getStatus" }
            val blockAt = dmCheck.indexOfFirst { it.getReference<MethodReference>()?.name == "getDisableShareToDmReason" }
            assertTrue("$version: the chat check reads the comment's status before its block", statusAt in 0 until blockAt)
            assertTrue(
                "$version: the chat check is still TikTok's long_press_to_share_comment gate",
                dmCheck.any { it.getReference<StringReference>()?.string == "long_press_to_share_comment" },
            )

            val aclAskers = build.callers(SHARE_BLOCK_ACL, "getCode")
            assertEquals("$version: ACL askers ${aclAskers.map { "${it.first.type}->${it.second.name}" }}", 2, aclAskers.size)
            assertTrue(
                "$version: TikTok's comment share check asks the ACL",
                aclAskers.any { (classDef, method) ->
                    classDef.type == SHARE_EXT_SERVICE && method.returnType == "Z" &&
                        method.parameterTypes.map(CharSequence::toString) == listOf(SHARE_BLOCK_COMMENT)
                },
            )
            assertTrue(
                "$version: the long-press share step asks the ACL of the info it fetched",
                aclAskers.any { (_, method) ->
                    method.implementation!!.instructions.any {
                        it.getReference<StringReference>()?.string == "comment_external_share_disable"
                    }
                },
            )
        }
    }

    @Test
    fun `the shared link is the video's own link with the comment's id, built on the phone`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val build = Build(apk)
            val update = build.byType.getValue(SHARE_INFO_KT).methods.single { it.name == "updateShareInfo" }
            val code = update.implementation!!.instructions.toList()
            assertTrue(
                "$version: the link starts from the video's share link",
                code.any { it.getReference<MethodReference>()?.name == "getShareUrl" },
            )
            assertTrue(
                "$version: the comment's id goes on the link",
                code.any { it.getReference<StringReference>()?.string == "share_comment_id" },
            )
            assertTrue(
                "$version: the comment's share info takes the link",
                code.any { it.getReference<MethodReference>()?.name?.startsWith("setUrl") == true },
            )
        }
    }

    /** One fixture's classes by type; methods are walked on each ask, never held. */
    private class Build(apk: java.io.File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }

        /** Methods outside the getter's own class that call it. */
        fun callers(owner: String, name: String): List<Pair<ClassDef, Method>> = methods.filter { (classDef, method) ->
            classDef.type != owner && method.implementation?.instructions?.any { instruction ->
                instruction.getReference<MethodReference>()?.let { it.definingClass == owner && it.name == name } == true
            } == true
        }.toList()
    }
}
