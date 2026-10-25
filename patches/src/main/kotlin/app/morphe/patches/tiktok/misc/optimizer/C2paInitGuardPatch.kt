/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** TikTok's content credential (C2PA) service, the only caller of the SDK's start. */
internal const val C2PA_SERVICE_CLASS_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/TTC2PAServiceImpl;"

/** The SDK itself: its start loads libtt_c2pa_sdk through Librarian from a class initializer. */
internal const val C2PA_SDK_CLASS_DESCRIPTOR = "Lcom/bytedance/ttc2pa/TTC2PA;"

/**
 * The content credential service's start (issue #211): `TTC2PAServiceImpl.init(wrapper)` stores
 * the wrapper in its field, then starts the SDK one way or the other, and the SDK's class
 * initializer loads libtt_c2pa_sdk. The photo picker a comment reply opens starts the service on
 * a pool thread, so with the library emptied the load fails as an uncaught error and TikTok dies
 * into safe mode. Pinned to the class, since the name is TikTok's own and not obfuscated.
 */
internal object C2paServiceInitFingerprint : Fingerprint(
    definingClass = C2PA_SERVICE_CLASS_DESCRIPTOR,
    name = "init",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = C2PA_SDK_CLASS_DESCRIPTOR,
        ),
    ),
)

/**
 * Keeps TikTok alive once Remove content credential and card scanner assets has emptied the
 * C2PA library: the service's init stores its wrapper and returns before the SDK start that
 * would load the library. The service then answers every read and sign with its own "Must call
 * init before use" result, which TikTok already handles, so the comment photo picker opens and
 * a picked photo just carries no credential (issue #211). Dependency-only: a bundle without the
 * asset strip leaves the service alone.
 */
internal val c2paInitGuardPatch = bytecodePatch {
    execute {
        C2paServiceInitFingerprint.method.apply {
            // The store has to stay: the service reads the wrapper back when it classifies a
            // picked file, and that path never starts the SDK.
            val first = implementation?.instructions?.firstOrNull()
            if (first?.opcode != Opcode.IPUT_OBJECT) {
                throw PatchException("C2PA service init no longer stores its wrapper first: ${first?.opcode}")
            }
            addInstruction(1, "return-void")
        }
    }
}
