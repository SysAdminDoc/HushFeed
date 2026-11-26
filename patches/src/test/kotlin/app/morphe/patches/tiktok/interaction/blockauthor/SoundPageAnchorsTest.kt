/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Block sound on sound pages rests on, held to each declared build: the sound page keeps its
 * name and its own onResume, and still reads the sound's id from the intent's "id" extra, which is
 * the id the button records.
 */
class SoundPageAnchorsTest {
    @Test
    fun `the sound page resumes on its own and reads the sound id from the intent, on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val page = classes.filter { it.type == MUSIC_DETAIL_ACTIVITY }.distinctBy { it.type }
            assertEquals("$version: MusicDetailActivity classes", 1, page.size)
            val pageClass = page.single()

            val resumes = pageClass.methods.filter { MusicDetailActivityOnResumeFingerprint.takes(it, pageClass) }
            assertEquals("$version: the page's own onResume", 1, resumes.size)

            val readsId = pageClass.methods.any { method ->
                val instructions = method.implementation?.instructions?.toList().orEmpty()
                instructions.withIndex().any { (index, instruction) ->
                    val string = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    // Straight from the intent, or (47.9.3) through the page's own static
                    // (Intent, String) -> String reader.
                    string == "id" && instructions.drop(index + 1).take(3).any {
                        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                        call != null && call.returnType == "Ljava/lang/String;" &&
                            (call.definingClass == "Landroid/content/Intent;" && call.name == "getStringExtra" ||
                                call.parameterTypes.map { type -> type.toString() } ==
                                listOf("Landroid/content/Intent;", "Ljava/lang/String;"))
                    }
                }
            }
            assertTrue("$version: the sound page no longer reads its sound from the \"id\" extra", readsId)
        }
    }

    private companion object {
        const val MUSIC_DETAIL_ACTIVITY = "Lcom/ss/android/ugc/aweme/music/ui/MusicDetailActivity;"
    }
}
