/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patches.tiktok.shared.valueIn
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val KEEP_UNSENT = "Lapp/morphe/extension/tiktok/inbox/KeepUnsent;"
private const val KEEP_UNSENT_PATCH = "Keep unsent messages"

/**
 * The method on TikTok's recall updater that turns a message into an unsent one. Both ways a
 * recall reaches the chat, the message's own extension map and a status message, end here with
 * the id of the message to recall, the id of whoever recalled it, the message body and its
 * type. The class and method names are obfuscated, so the method is found by its parameters
 * and the three log lines only this method writes.
 */
internal object RecallApplyFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Lcom/bytedance/im/core/proto/MessageBody;", "I"),
    strings = listOf("RecallMessageUpdater", "No server message id is found.", "msg to recalled is not found: "),
)

/**
 * The getter the chat reads a message's content from, a JSON string that holds the text of a
 * text message. The IM SDK's message class keeps the names of its public methods, though not
 * its own name, so the class is told apart by the getters it has next to this one.
 */
internal object MessageContentFingerprint : Fingerprint(
    name = "getContent",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    custom = { _, classDef ->
        classDef.methods.any { it.name == "getContentPB" } &&
            classDef.methods.any { it.name == "getLocalExtValue" } &&
            classDef.methods.any { it.name == "getMsgId" && it.returnType == "J" }
    },
)

/**
 * The result type the method hands back when it has applied a recall. The method builds two
 * result objects from a null Integer: the one for a recall with no server message id, which
 * comes first, and the one for a recall it applied, which comes last, just before the method
 * returns. The names are obfuscated, so the type is read out of the method.
 */
internal fun appliedResultTypeIn(method: Method): String {
    val instructions = method.implementation?.instructions?.toList()
        ?: throw PatchException("$KEEP_UNSENT_PATCH: the recall method has no body")
    val built = instructions.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.NEW_INSTANCE) return@mapNotNull null
        val type = instruction.getReference<TypeReference>()?.type ?: return@mapNotNull null
        val constructedFromNull = instructions.drop(index + 1).take(4).any { next ->
            next.opcode == Opcode.INVOKE_DIRECT && next.getReference<MethodReference>()?.let {
                it.name == "<init>" && it.definingClass == type && it.parameterTypes == listOf("Ljava/lang/Integer;")
            } == true
        }
        type.takeIf { constructedFromNull }
    }.toList()
    if (built.size != 2 || built[0] == built[1]) {
        throw PatchException(
            "$KEEP_UNSENT_PATCH: expected two result types built from a null Integer, found " +
                "${built.size} (${built.joinToString()}).",
        )
    }
    return built.last()
}

@Suppress("unused")
val keepUnsentMessagesPatch = bytecodePatch(
    name = "Keep unsent messages",
    description = "Keeps a message in your chat when the other person unsends it, instead of " +
        "removing it, and can mark it as unsent. A button removes the kept messages. Messages you " +
        "unsend yourself are still removed. Starts off. Turn it on in Hushfeed settings > Inbox.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // 1. The recall itself: ask before it is applied, and leave as if it had been.
        RecallApplyFingerprint.method.apply {
            // The question writes v0 for its answer, and bringing p3 and p4 within reach takes two more locals.
            requireLocals(KEEP_UNSENT_PATCH, 2)
            val applied = appliedResultTypeIn(this)
            guardAtEntry(
                KEEP_UNSENT_PATCH,
                callThroughLocals(
                    KEEP_UNSENT_PATCH,
                    "invoke-static",
                    "$KEEP_UNSENT->shouldKeep(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;I)Z",
                    objectIn("p0"),
                    objectIn("p1"),
                    objectIn("p2"),
                    objectIn("p3"),
                    valueIn("p4"),
                ),
                """
                    new-instance v0, $applied
                    const/4 v1, 0x0
                    invoke-direct {v0, v1}, $applied-><init>(Ljava/lang/Integer;)V
                    return-object v0
                """,
            )
        }

        // 2. The updater itself, so a clear later has one to hand the recalls back to.
        val updaterType = RecallApplyFingerprint.originalClassDef.type
        val constructors = mutableClassDefBy(updaterType).methods.filter { it.name == "<init>" && it.implementation != null }
        if (constructors.size != 1) {
            throw PatchException("$KEEP_UNSENT_PATCH: expected one constructor on $updaterType, found ${constructors.size}.")
        }
        constructors.single().apply {
            val first = implementation!!.instructions.first()
            check(first.opcode == Opcode.INVOKE_DIRECT && first.getReference<MethodReference>()?.name == "<init>") {
                "$KEEP_UNSENT_PATCH: the constructor of $updaterType no longer starts with its super call."
            }
            addInstruction(1, "invoke-static {p0}, $KEEP_UNSENT->updaterCreated(Ljava/lang/Object;)V")
        }

        // 2b. The IM SDK's core object the updater works through, so a clear can make an updater
        // after TikTok has restarted, when none has been made yet. Its type is the updater's
        // fourth constructor parameter.
        val coreType = constructors.single().parameterTypes.getOrNull(3)?.toString()
            ?: throw PatchException("$KEEP_UNSENT_PATCH: the constructor of $updaterType has no fourth parameter.")
        val coreConstructors = mutableClassDefBy(coreType).methods.filter { it.name == "<init>" && it.implementation != null }
        if (coreConstructors.size != 1) {
            throw PatchException("$KEEP_UNSENT_PATCH: expected one constructor on $coreType, found ${coreConstructors.size}.")
        }
        coreConstructors.single().apply {
            val first = implementation!!.instructions.first()
            check(first.opcode == Opcode.INVOKE_DIRECT && first.getReference<MethodReference>()?.name == "<init>") {
                "$KEEP_UNSENT_PATCH: the constructor of $coreType no longer starts with its super call."
            }
            addInstruction(1, "invoke-static {p0}, $KEEP_UNSENT->imCreated(Ljava/lang/Object;)V")
        }

        // 3. The content getter, so a kept message can say it was unsent.
        MessageContentFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
            check(returns.size == 1) { "$KEEP_UNSENT_PATCH: the content getter has ${returns.size} returns, expected one." }
            val (index, returned) = returns.single()
            val register = (returned as OneRegisterInstruction).registerA
            val locals = implementation!!.registerCount - numberOfParameterRegisters
            check(register < locals && register <= 15) {
                "$KEEP_UNSENT_PATCH: the content getter returns from v$register, which is not a local it can rewrite."
            }
            addInstructions(
                index,
                """
                    invoke-static {p0, v$register}, $KEEP_UNSENT->markContent(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """,
            )
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableKeepUnsent()V",
        )
    }
}
