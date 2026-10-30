/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val RECALL_PARAMETERS = listOf(
    "Ljava/lang/String;",
    "Ljava/lang/String;",
    "Lcom/bytedance/im/core/proto/MessageBody;",
    "I",
)

/**
 * Keep unsent messages puts its question in front of the one method on TikTok's recall updater
 * that applies a recall, and has it hand back the updater's own "applied" result when the answer
 * is yes. It also takes hold of the updater when TikTok makes it, so a later clear has one to
 * hand the recalls back to, and rewrites the content getter of TikTok's message so a kept
 * message can say it was unsent. The method, the constructor and the result types are obfuscated,
 * so each is found by what it does. All of them are held to each declared build here, where a
 * rename would otherwise only show on a phone as an unsent message that disappears anyway.
 */
class KeepUnsentAnchorsTest {
    @Test
    fun `one method applies a recall and the result it hands back is found on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val matches = ArrayList<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        // The parameters are checked first, so the strings are only read on a few.
                        if (method.parameterTypes == RECALL_PARAMETERS && RecallApplyFingerprint.takes(method, classDef)) {
                            matches.add(classDef to method)
                        }
                    }
                }
            }
            assertEquals("$version: recall methods matched", 1, matches.size)

            val (owner, method) = matches.single()
            val applied = appliedResultTypeIn(method)
            val result = container.dexEntryNames.firstNotNullOfOrNull { entry ->
                container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == applied }
            } ?: error("$version: the applied result $applied is not in the APK")
            assertTrue("$version: ${result.type} has no constructor taking an Integer", result.methods.any {
                it.name == "<init>" && it.parameterTypes == listOf("Ljava/lang/Integer;")
            })
            assertTrue("$version: the recall method is no longer on a class of its own: ${owner.type}", owner.type != applied)

            // A clear hands the recalls back to the updater TikTok made, so it must be made by one constructor
            // that begins with its super call, where the patch takes hold of it.
            val constructors = owner.methods.filter { it.name == "<init>" && it.implementation != null }
            assertEquals("$version: constructors on the updater", 1, constructors.size)
            val first = constructors.single().implementation!!.instructions.first()
            assertEquals("$version: the updater's constructor starts with its super call",
                Opcode.INVOKE_DIRECT, first.opcode)
            assertEquals("<init>", first.getReference<MethodReference>()?.name)
        }
    }

    @Test
    fun `the message content getter is found once and returns from a local`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val getters = ArrayList<Method>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    // Only the message class has a protobuf copy of its content next to the getter.
                    if (classDef.methods.none { it.name == "getContentPB" }) continue
                    for (method in classDef.methods) {
                        if (MessageContentFingerprint.takes(method, classDef)) getters.add(method)
                    }
                }
            }
            assertEquals("$version: content getters matched", 1, getters.size)

            val body = getters.single().implementation!!
            val returns = body.instructions.filter { it.opcode == Opcode.RETURN_OBJECT }
            assertEquals("$version: returns in the content getter", 1, returns.size)
            val register = (returns.single() as OneRegisterInstruction).registerA
            val locals = body.registerCount - getters.single().parameterTypes.size - 1
            assertTrue("$version: the getter returns from v$register, not one of its $locals locals", register < locals)
        }
    }
}
