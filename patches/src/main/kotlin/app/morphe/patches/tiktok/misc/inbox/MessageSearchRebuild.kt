/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SEARCH_REBUILD = "Lapp/morphe/extension/tiktok/inbox/MessageSearchRebuild;"
private const val CANCELLATION = "Ljava/util/concurrent/CancellationException;"

/**
 * TikTok's message search engine catching the local search index up with the messages saved on
 * the phone, in the scope it's handed. The Inbox search page and the chat history search call it
 * from their own scopes, which is why the walk stops when search closes. The one method that logs
 * this line.
 */
internal object MessageSearchCatchUpFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("updateSearchContentManually "),
    custom = { method, _ -> method.parameterTypes.size == 1 && !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * The engine's reset: deletes every message from the search index and sets the walk back to the
 * newest message. Answers null when the signed-in account has no engine.
 */
internal object MessageSearchResetFingerprint : Fingerprint(
    classFingerprint = MessageSearchCatchUpFingerprint,
    returnType = "Ljava/lang/Boolean;",
    parameters = listOf(),
    strings = listOf("deleteAllMessages: failed, "),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * The coroutine helper the engine cancels a scope with when it clears an account,
 * `CoroutineScope.cancel(cause)`: a static call taking the catch-up's scope type and a
 * CancellationException. Its class also makes scopes.
 */
internal fun scopeCancel(engine: ClassDef, scopeType: String): MethodReference {
    val cancels = engine.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            instruction.takeIf { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
                ?.getReference<MethodReference>()
                ?.takeIf { reference ->
                    reference.returnType == "V" &&
                        reference.parameterTypes.map(CharSequence::toString) == listOf(scopeType, CANCELLATION)
                }
        }
    }.distinctBy { it.toString() }
    return cancels.singleOrNull()
        ?: throw PatchException("Rebuild message search: ${engine.type} cancels a scope ${cancels.size} ways, not one")
}

/**
 * Everything the four bridges in the extension's MessageSearchRebuild call, found and checked
 * before any of them is written.
 *
 * [kit] is the class of [scopeCancel]. Its one static method that takes nothing and answers a
 * class implementing the scope type is `MainScope()`: a scope with its own job, which the
 * catch-up's launch runs in on the engine's own dispatcher. [classOf] looks a type up.
 */
internal class MessageSearchBridges(
    engine: ClassDef,
    catchUp: Method,
    reset: Method,
    kit: ClassDef,
    classOf: (String) -> ClassDef?,
) {
    private val engineType = engine.type
    private val scopeType = catchUp.parameterTypes.single().toString()
    private val catchUpName = catchUp.name
    private val resetName = reset.name
    private val singleton: String
    private val cancel = scopeCancel(engine, scopeType)
    private val makeScope: Method

    init {
        if (catchUp.definingClass != engineType || reset.definingClass != engineType) {
            throw PatchException("Rebuild message search: the reset and the catch-up are not both $engineType's")
        }
        if (!AccessFlags.PUBLIC.isSet(catchUp.accessFlags) || !AccessFlags.PUBLIC.isSet(reset.accessFlags)) {
            throw PatchException("Rebuild message search: the reset or the catch-up is not public")
        }
        if (cancel.definingClass != kit.type) {
            throw PatchException("Rebuild message search: ${kit.type} is not where ${cancel.name} lives")
        }
        // The engine is a singleton the Inbox search and the message store read.
        singleton = engine.fields.filter { field ->
            AccessFlags.STATIC.isSet(field.accessFlags) && AccessFlags.PUBLIC.isSet(field.accessFlags) &&
                field.type == engineType
        }.singleOrNull()?.name
            ?: throw PatchException("Rebuild message search: $engineType has no one public instance field")
        makeScope = kit.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                method.parameterTypes.isEmpty() &&
                classOf(method.returnType)?.interfaces?.contains(scopeType) == true
        }.singleOrNull()
            ?: throw PatchException("Rebuild message search: ${kit.type} has no one scope maker without arguments")
    }

    /** The smali for each bridge, by its name in the extension. Each starts at index 0 and returns. */
    val bodies: Map<String, String> = mapOf(
        "resetIndex" to """
            sget-object v0, $engineType->$singleton:$engineType
            invoke-virtual {v0}, $engineType->$resetName()Ljava/lang/Boolean;
            move-result-object v0
            if-eqz v0, :unavailable
            invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
            move-result v0
            if-eqz v0, :failed
            const/4 v0, 0x1
            return v0
            :failed
            const/4 v0, 0x0
            return v0
            :unavailable
            const/4 v0, -0x1
            return v0
        """,
        "newScope" to """
            invoke-static {}, ${makeScope.definingClass}->${makeScope.name}()${makeScope.returnType}
            move-result-object v0
            return-object v0
        """,
        "startIndex" to """
            sget-object v0, $engineType->$singleton:$engineType
            check-cast p0, $scopeType
            invoke-virtual {v0, p0}, $engineType->$catchUpName($scopeType)V
            return-void
        """,
        "cancelScope" to """
            check-cast p0, $scopeType
            const/4 v0, 0x0
            invoke-static {p0, v0}, $cancel
            return-void
        """,
    )

    /** Writes [bodies] over the bridge stubs: each stub with two more registers, the body at index 0. */
    fun bridged(stub: Method): MutableMethod {
        val body = bodies[stub.name] ?: throw PatchException("Rebuild message search: no bridge named ${stub.name}")
        return stub.cloneMutable(additionalRegisters = 2).also { it.addInstructionsWithLabels(0, body) }
    }
}

/** The engine, its reset and catch-up, and the coroutine helpers they need. */
internal fun messageSearchBridges(): MessageSearchBridges {
    val engine = MessageSearchCatchUpFingerprint.classDef
    val catchUp = MessageSearchCatchUpFingerprint.method
    val kit = classDefBy(scopeCancel(engine, catchUp.parameterTypes.single().toString()).definingClass)
    return MessageSearchBridges(engine, catchUp, MessageSearchResetFingerprint.method, kit) { classDefByOrNull(it) }
}

/** Replaces the extension's four stubs with [bridges]. */
internal fun installMessageSearchRebuild(bridges: MessageSearchBridges) {
    val extension = mutableClassDefBy(SEARCH_REBUILD)
    fun fill(name: String) {
        val stub = extension.methods.single { it.name == name }
        extension.methods.remove(stub)
        extension.methods.add(bridges.bridged(stub))
    }
    fill("resetIndex")
    fill("newScope")
    fill("startIndex")
    fill("cancelScope")
}
