package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.Fixtures
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import java.io.BufferedInputStream
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

/** #233: the target UID belongs to the current native chat binding, independent of View reuse. */
class StoryOverLiveBindingFixturesTest {
    @Test fun `the extension reads the public UID assigned by the native avatar bind`() {
        val repo = File("..").takeIf { File(it, "extensions").isDirectory } ?: File(".")
        val source = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/feed/StoryOverLive.java").readText()
        val name = Regex("""BINDING_UID_METHOD\s*=\s*"([^"]+)"""").find(source)!!.groupValues[1]
        Fixtures.forEachDeclared { apk ->
            val avatar = classes(apk, setOf(AVATAR)).getValue(AVATAR)
            val getter = avatar.methods.single { it.name == name && it.parameterTypes.isEmpty() && it.returnType == STRING }
            assertTrue(AccessFlags.PUBLIC.isSet(getter.accessFlags))
            val code = getter.implementation!!.instructions.toList()
            assertEquals(listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), code.map { it.opcode })
            val field = code[0].getReference<FieldReference>()!!
            assertEquals(STRING, field.type)
            val bind = avatar.methods.single { it.name == BIND && it.parameterTypes.size == 6 }
            assertTrue(bind.implementation!!.instructions.any {
                it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>() == field
            })
        }
    }

    @Test fun `the chat row reuses its picture and passes its current inbox UID into that bind`() {
        Fixtures.forEachDeclared { apk ->
            val found = classes(apk, setOf(ROW, AVATAR))
            val row = found.getValue(ROW)
            val pictures = found.getValue(AVATAR).interfaces.toSet()
            // Where the bind's UID argument sits: 47.9.2 calls the picture's LIZIZ itself (picture,
            // owner, model, UID), 47.9.3 goes through its Kotlin default-arguments helper, a static
            // X.0YsR.LIZ(picture, UID, model, owner, ...) that calls LIZIZ on the picture.
            fun uidSlot(call: MethodReference): Int? = when {
                call.definingClass == AVATAR && call.name == BIND -> 3
                call.parameterTypes.size > 2 && call.parameterTypes[0].toString() in pictures &&
                    call.parameterTypes[1].toString() == STRING && forwardsToBind(apk, call, pictures) -> 1
                else -> null
            }
            val code = row.methods.mapNotNull { it.implementation?.instructions?.toList() }.single { instructions ->
                instructions.any { it.getReference<MethodReference>()?.let(::uidSlot) != null }
            }
            val bindAt = code.indexOfFirst { it.getReference<MethodReference>()?.let(::uidSlot) != null }
            val bind = code[bindAt] as RegisterRangeInstruction
            val uidAt = (0 until bindAt).last { code[it].getReference<MethodReference>()?.name == "getInboxUid" }
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[uidAt + 1].opcode)
            assertEquals(bind.startRegister + uidSlot(code[bindAt].getReference<MethodReference>()!!)!!,
                (code[uidAt + 1] as OneRegisterInstruction).registerA)
            val pictureAt = (0 until uidAt).last { code[it].getReference<MethodReference>()?.returnType == AVATAR }
            assertEquals(bind.startRegister, (code[pictureAt + 1] as OneRegisterInstruction).registerA)
            assertTrue(code.take(bindAt).none { it.opcode == Opcode.NEW_INSTANCE && it.getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()?.type == AVATAR })
        }
    }

    /** A static helper whose body calls the six-argument bind on one of the picture's interfaces. */
    private fun forwardsToBind(apk: File, call: MethodReference, pictures: Set<String>): Boolean {
        val helper = classes(apk, setOf(call.definingClass))[call.definingClass] ?: return false
        val method = helper.methods.singleOrNull {
            it.name == call.name && it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
        } ?: return false
        return AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation!!.instructions.any { instruction ->
            instruction.getReference<MethodReference>()?.let {
                it.definingClass in pictures && it.name == BIND && it.parameterTypes.size == 6
            } == true
        }
    }

    private fun classes(apk: File, wanted: Set<String>): Map<String, ClassDef> {
        val result = mutableMapOf<String, ClassDef>()
        ZipFile(apk).use { zip ->
            for (entry in zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }) {
                val dex = zip.getInputStream(entry).use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(it)) }
                for (type in dex.classes) if (type.type in wanted) result[type.type] = type
                if (result.keys.containsAll(wanted)) break
            }
        }
        return result
    }

    private companion object {
        const val STRING = "Ljava/lang/String;"
        const val AVATAR = "Lcom/ss/android/ugc/aweme/avatar/AvatarComponentView;"
        const val ROW = "Lcom/ss/android/ugc/aweme/im/chatlist/impl/ui/viewholder/chatlistvo/NewChatListSingleChatVH;"
        // The six-argument avatar bind (LifecycleOwner, Object, String, Function1, Z, Z): LJIIIIZZ
        // on 47.7.3, LIZIZ on 47.8.3, where LJIIIIZZ is now a ()Z.
        const val BIND = "LIZIZ"
    }
}
