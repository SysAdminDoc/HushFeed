/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val REQUEST_BODY = "Lcom/bytedance/im/core/proto/RequestBody;"

/**
 * Hide read receipts puts its question in front of the two methods every chat request handler of
 * TikTok's IM SDK sends its request through. Their names are obfuscated, so they are found by what
 * they take. Each is held to be found exactly once on each declared build, where a rename or a
 * second method of the same shape would otherwise only show on a phone, as a "Seen" that still
 * goes out or a request that is held back by mistake.
 */
class HideReadReceiptsAnchorsTest {
    @Test
    fun `the request sender is found once on each build and returns a long`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val matches = ArrayList<Pair<ClassDef, Method>>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.parameterTypes.any { it.toString() == REQUEST_BODY } &&
                            ChatRequestSendFingerprint.takes(method, classDef)
                        ) matches.add(classDef to method)
                    }
                }
            }
            assertEquals("$version: request senders matched", 1, matches.size)
            val (owner, method) = matches.single()
            assertEquals("$version: the sender returns the request id", "J", method.returnType)
            assertTrue("$version: the sender has a body to put the question in front of", method.implementation != null)
            // The question writes v0 and v1 for the answer of a skipped request.
            val body = method.implementation!!
            val locals = body.registerCount - (method.parameterTypes.size + 1)
            assertTrue("$version: ${owner.type} has only $locals locals", locals >= 2)
        }
    }

    @Test
    fun `the sender with headers is found once on each build and returns nothing`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val matches = ArrayList<Pair<ClassDef, Method>>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.parameterTypes.any { it.toString() == REQUEST_BODY } &&
                            ChatRequestSendWithHeadersFingerprint.takes(method, classDef)
                        ) matches.add(classDef to method)
                    }
                }
            }
            assertEquals("$version: senders with headers matched", 1, matches.size)
            val (owner, method) = matches.single()
            assertEquals("$version: it returns nothing", "V", method.returnType)
            val locals = method.implementation!!.registerCount - (method.parameterTypes.size + 1)
            assertTrue("$version: ${owner.type} has only $locals locals", locals >= 1)
        }
    }
}
