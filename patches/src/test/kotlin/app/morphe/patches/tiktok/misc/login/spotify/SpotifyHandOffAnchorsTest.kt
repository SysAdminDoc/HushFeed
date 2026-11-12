/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.login.spotify

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Where Fix Spotify login holds TikTok, held to each declared build.
 *
 * <p>The fingerprint takes one method in the main dexes, the activity wrapper of
 * startActivityForResult, with a local free for the guard's answer. The Spotify SDK sits in the
 * music feature dex, a zip inside `lib/`: its sign-in hand-off builds the START_AUTH_FLOW intent
 * with the extras the extension reads and sends it for 1138 through that same method, and its
 * LoginActivity reads a result for 1138 in the shape the extension writes. Those three together
 * are what make the web page's answer land where the Spotify app's would have.
 */
class SpotifyHandOffAnchorsTest {
    private val featureDex = "lib/arm64-v8a/libdex_df_music_dsp.so"
    private val loginActivity = "Lcom/spotify/sdk/android/auth/LoginActivity;"

    @Test
    fun `the Spotify sign-in hand-off goes through the method the patch guards`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val main = classes(apk)
            val taken = main.values.flatMap { classDef ->
                classDef.methods.filter { ActivityStartForResultFingerprint.takes(it, classDef) }
            }
            assertEquals("$version: the fingerprint takes ${taken.map { "${it.definingClass}->${it.name}" }}",
                1, taken.size)
            val wrapper = taken.single()
            assertTrue("$version: the wrapper isn't static", AccessFlags.STATIC.isSet(wrapper.accessFlags))
            assertTrue("$version: no local for the guard's answer",
                wrapper.implementation!!.registerCount - wrapper.numberOfParameterRegisters >= 1)

            val feature = featureClasses(apk)
            val handOffs = feature.values.flatMap { it.methods }.filter { method ->
                "com.spotify.sso.action.START_AUTH_FLOW" in strings(method) && calls(method, wrapper)
            }
            assertEquals("$version: hand-offs through the wrapper ${handOffs.map { "${it.definingClass}->${it.name}" }}",
                1, handOffs.size)
            val handOff = handOffs.single()
            assertTrue("$version: the hand-off's extras moved: ${strings(handOff)}",
                strings(handOff).containsAll(listOf("CLIENT_ID", "REDIRECT_URI", "RESPONSE_TYPE", "SCOPES", "STATE")))
            assertTrue("$version: the hand-off isn't sent for 1138", 1138 in literals(handOff))

            val onResult = checkNotNull(feature[loginActivity]?.methods?.singleOrNull {
                it.name == "onActivityResult" && it.parameterTypes.map(CharSequence::toString) ==
                    listOf("I", "I", "Landroid/content/Intent;")
            }) { "$version: no $loginActivity->onActivityResult" }
            assertTrue("$version: LoginActivity doesn't wait for 1138", 1138 in literals(onResult))
            assertTrue("$version: LoginActivity reads another reply: ${strings(onResult)}",
                strings(onResult).containsAll(listOf("REPLY", "RESPONSE_TYPE", "code", "AUTHORIZATION_CODE", "STATE", "ERROR")))
        }
    }

    private fun classes(apk: File): Map<String, ClassDef> {
        val classes = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
        }
        return classes
    }

    private fun featureClasses(apk: File): Map<String, ClassDef> {
        val inner = ZipFile(apk).use { zip ->
            val entry = checkNotNull(zip.getEntry(featureDex)) { "${apk.name}: no $featureDex" }
            zip.getInputStream(entry).readBytes()
        }
        val classes = HashMap<String, ClassDef>()
        ZipInputStream(inner.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith(".dex")) continue
                for (classDef in DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(zip.readBytes())).classes) {
                    classes.putIfAbsent(classDef.type, classDef)
                }
            }
        }
        return classes
    }

    private fun strings(method: Method): List<String> =
        method.implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string }.orEmpty()

    private fun literals(method: Method): List<Int> =
        method.implementation?.instructions?.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.orEmpty()

    private fun calls(method: Method, target: Method): Boolean =
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == target.definingClass && it.name == target.name &&
                    it.parameterTypes.map(CharSequence::toString) == target.parameterTypes.map(CharSequence::toString)
            } == true
        } == true
}
