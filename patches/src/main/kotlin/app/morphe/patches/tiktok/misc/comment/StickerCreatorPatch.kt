/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.sharesheet.SHARE_MAKE_STICKER
import app.morphe.patches.tiktok.interaction.sharesheet.addOwnedShareAction
import app.morphe.patches.tiktok.interaction.sharesheet.ownedShareActionsPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val STICKER_CREATOR = "Lapp/morphe/extension/tiktok/comment/StickerCreator;"
internal const val FAVOURITE_STICKER_PAGE = "Lcom/ss/android/ugc/aweme/im/sticker/basic/panel/BasicFavouriteStickerPageCell;"
private const val IM_STICKER_API = "Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;"
private const val STICKER_ITEM = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"
private const val STICKER_UPLOAD_CONFIG_API = "Lcom/ss/android/ugc/aweme/subscription/pgc/jsbridge/upload/StickerUploadConfigApi;"
private const val STICKER_ACCOUNT_SERVICE = "Lcom/ss/android/ugc/aweme/IAccountService;"
private const val STICKER_OBJECT = "Ljava/lang/Object;"
private const val STICKER_STRING = "Ljava/lang/String;"

private fun MethodReference.stickerDescriptor() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.stickerDescriptor() = "$definingClass->$name:$type"

private fun Method.stickerMethodCalls(): List<MethodReference> =
    implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference } ?: emptyList()

/**
 * StickerApiV2's im/sticker/save: type, uri, width, height, tags, source, create source and the
 * continuation. R8 renames it (LJIIJJI on TikTok 47.5.3).
 */
internal fun MethodReference.isStickerSaveCall(): Boolean {
    val parameters = parameterTypes.map { it.toString() }
    return definingClass == STICKER_API && returnType == STICKER_OBJECT && parameters.size == 8 &&
        parameters.subList(0, 7) == listOf("I", STICKER_STRING, "I", "I", "Ljava/util/List;", STICKER_STRING, "Ljava/lang/Integer;")
}

/** The Saved page of the comment sticker panel, which the patch gives a Create button. */
internal object FavouriteStickerPageFingerprint : Fingerprint(
    definingClass = FAVOURITE_STICKER_PAGE,
    name = "onCreateItemView",
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/view/ViewGroup;"),
)

/**
 * The suspend fun asking im/upload_config for an ImageX token, the only caller of
 * StickerUploadConfigApi.getUploadConfig (182P.LIZ on TikTok 47.5.3).
 */
internal object StickerUploadConfigFingerprint : Fingerprint(
    returnType = STICKER_OBJECT,
    parameters = listOf("L"),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.stickerMethodCalls().any { it.definingClass == STICKER_UPLOAD_CONFIG_API && it.name == "getUploadConfig" }
    },
)

/**
 * The server parameter the comment image uploader hands ImageX (182R.LIZ on TikTok 47.5.3).
 * A sibling builds the same string from a cached account holder, so the store
 * region read through IAccountService is what tells them apart.
 */
internal object StickerServerParameterFingerprint : Fingerprint(
    returnType = STICKER_STRING,
    parameters = listOf(),
    strings = listOf("&StoreRegion="),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.stickerMethodCalls().any { it.definingClass == STICKER_ACCOUNT_SERVICE && it.name == "getStoreRegionUpperCase" }
    },
)

/**
 * The DM canvas saver (14hO.LIZIZ on TikTok 47.5.3): it saves a
 * drawing through im/sticker/save and adds the answer to Saved. Every call the bridges make comes
 * out of it.
 */
internal object CanvasStickerSaverFingerprint : Fingerprint(
    returnType = STICKER_OBJECT,
    parameters = listOf("Landroid/content/Context;", STICKER_STRING, "L", "Z"),
    strings = listOf("dm_ai_canvas"),
    custom = { method, _ ->
        val calls = method.stickerMethodCalls()
        calls.any { it.isStickerSaveCall() } &&
            calls.any { it.definingClass == IM_STICKER_API && it.name == "getImStickerStoreService" }
    },
)

/** One call the canvas saver makes, with the invoke it makes it with. */
internal data class StickerCall(val invoke: String, val reference: MethodReference)

internal class StickerCreatorCalls(
    val uploadConfig: MethodReference,
    val serverParameter: MethodReference,
    val stickerApi: StickerCall,
    val save: StickerCall,
    val storeHolder: FieldReference,
    val storeApi: StickerCall,
    val storeService: StickerCall,
    val addToStore: StickerCall,
) {
    val continuation: String get() = save.reference.parameterTypes.last().toString()
}

private fun Opcode.stickerInvoke(): String? = when (this) {
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> "invoke-static"
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> "invoke-virtual"
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> "invoke-interface"
    else -> null
}

/**
 * Reads every call the bridges need from the canvas saver, and checks the two suspend calls take
 * the same continuation, since one proxy answers both.
 */
internal fun stickerCreatorCalls(uploadConfig: Method, serverParameter: Method, saver: Method): StickerCreatorCalls {
    val instructions = saver.implementation?.instructions?.toList()
        ?: throw PatchException("Create stickers: the canvas saver has no code")

    fun call(what: String, wanted: (MethodReference) -> Boolean): StickerCall {
        val found = instructions.mapNotNull { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            val invoke = instruction.opcode.stickerInvoke()
            if (reference != null && invoke != null && wanted(reference)) StickerCall(invoke, reference) else null
        }.distinct()
        if (found.size != 1) throw PatchException("Create stickers: ${found.size} $what calls in the canvas saver, expected 1")
        return found.single()
    }

    val save = call("im/sticker/save") { it.isStickerSaveCall() }
    val stickerApi = call("sticker API") { it.returnType == STICKER_API && it.parameterTypes.isEmpty() }
    val storeService = call("sticker store") {
        it.definingClass == IM_STICKER_API && it.name == "getImStickerStoreService" && it.parameterTypes.isEmpty()
    }
    val storeApi = call("IMStickerApi getter") {
        it.returnType == IM_STICKER_API && it.parameterTypes.isEmpty() && it.definingClass != IM_STICKER_API
    }
    val addToStore = call("add to Saved") {
        it.definingClass == storeService.reference.returnType && it.returnType == "V" &&
            it.parameterTypes.map { type -> type.toString() } == listOf(STICKER_ITEM)
    }
    val holders = instructions.filter { it.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass == IM_STICKER_API && it.type == storeApi.reference.definingClass }
        .distinct()
    if (holders.size != 1) throw PatchException("Create stickers: ${holders.size} IMStickerApi holders in the canvas saver, expected 1")

    if (stickerApi.invoke != "invoke-static") throw PatchException("Create stickers: the sticker API getter isn't static")
    for (call in listOf(storeApi, storeService, addToStore)) {
        if (call.invoke == "invoke-static") throw PatchException("Create stickers: ${call.reference.name} is called static")
    }
    val calls = StickerCreatorCalls(uploadConfig, serverParameter, stickerApi, save, holders.single(), storeApi, storeService, addToStore)
    val configContinuation = uploadConfig.parameterTypes.singleOrNull()?.toString()
    if (configContinuation != calls.continuation) {
        throw PatchException("Create stickers: the upload config takes $configContinuation, the save ${calls.continuation}")
    }
    return calls
}

private fun stickerBridge(name: String, parameters: List<String>, returnType: String, locals: Int, accessFlags: Int, smali: String) =
    MutableMethod(
        ImmutableMethod(
            STICKER_CREATOR, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, accessFlags,
            null, null, ImmutableMethodImplementation(locals + parameters.size, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, smali) }

/** The bridge bodies StickerCreator's stubs get, by name. Each passes its arguments straight on. */
internal fun stickerCreatorBridges(
    calls: StickerCreatorCalls,
    flagsFor: (String) -> Int = { AccessFlags.STATIC.value },
): List<MutableMethod> {
    val continuation = calls.continuation
    return listOf(
        stickerBridge(
            "uploadConfig", listOf(STICKER_OBJECT), STICKER_OBJECT, 1, flagsFor("uploadConfig"),
            """
                check-cast p0, $continuation
                invoke-static { p0 }, ${calls.uploadConfig.stickerDescriptor()}
                move-result-object v0
                return-object v0
            """,
        ),
        stickerBridge(
            "uploadServerParameter", listOf(), STICKER_STRING, 1, flagsFor("uploadServerParameter"),
            """
                invoke-static { }, ${calls.serverParameter.stickerDescriptor()}
                move-result-object v0
                return-object v0
            """,
        ),
        // One local ahead of the eight arguments, so the API object and the arguments make one range.
        stickerBridge(
            "saveSticker",
            listOf("I", STICKER_STRING, "I", "I", "Ljava/util/List;", STICKER_STRING, "Ljava/lang/Integer;", STICKER_OBJECT),
            STICKER_OBJECT, 1, flagsFor("saveSticker"),
            """
                invoke-static { }, ${calls.stickerApi.reference.stickerDescriptor()}
                move-result-object v0
                check-cast p7, $continuation
                ${calls.save.invoke}/range { v0 .. v8 }, ${calls.save.reference.stickerDescriptor()}
                move-result-object v0
                return-object v0
            """,
        ),
        stickerBridge(
            "addToSaved", listOf(STICKER_OBJECT), "V", 1, flagsFor("addToSaved"),
            """
                sget-object v0, ${calls.storeHolder.stickerDescriptor()}
                ${calls.storeApi.invoke} { v0 }, ${calls.storeApi.reference.stickerDescriptor()}
                move-result-object v0
                ${calls.storeService.invoke} { v0 }, ${calls.storeService.reference.stickerDescriptor()}
                move-result-object v0
                check-cast p0, $STICKER_ITEM
                ${calls.addToStore.invoke} { v0, p0 }, ${calls.addToStore.reference.stickerDescriptor()}
                return-void
            """,
        ),
        stickerBridge(
            "continuationType", listOf(), "Ljava/lang/Class;", 1, flagsFor("continuationType"),
            """
                const-class v0, $continuation
                return-object v0
            """,
        ),
    )
}

/**
 * Puts the bridges in place of StickerCreator's stubs. Each bridge keeps its stub's flags: R8 makes
 * the Java package-private stubs public, and the extension's callers were built against that.
 */
internal fun MutableClass.swapStickerStubs(calls: StickerCreatorCalls) {
    val stubs = stickerCreatorBridges(calls).associate { shape ->
        val stub = methods.singleOrNull {
            it.name == shape.name && it.returnType == shape.returnType &&
                it.parameterTypes.map { type -> type.toString() } == shape.parameterTypes.map { type -> type.toString() }
        } ?: throw PatchException("Create stickers: StickerCreator has no ${shape.name} stub")
        if (!AccessFlags.STATIC.isSet(stub.accessFlags)) {
            throw PatchException("Create stickers: the ${shape.name} stub isn't static")
        }
        shape.name to stub
    }
    for (bridge in stickerCreatorBridges(calls) { name -> stubs.getValue(name).accessFlags }) {
        methods.remove(stubs.getValue(bridge.name))
        methods.add(bridge)
    }
}

/** Hands the Saved page to StickerCreator as it's returned, so Create goes on every new page. */
internal fun MutableMethod.addStickerCreatorButton() {
    val returns = implementation?.instructions?.withIndex()?.filter { it.value.opcode == Opcode.RETURN_OBJECT }
        ?: throw PatchException("Create stickers: the Saved page has no code")
    if (returns.size != 1) throw PatchException("Create stickers: ${returns.size} returns in the Saved page, expected 1")
    val (index, returned) = returns.single()
    val page = (returned as OneRegisterInstruction).registerA
    addInstructionsAtControlFlowLabel(
        index,
        "invoke-static/range { v$page .. v$page }, $STICKER_CREATOR->onFavouritesPage(Landroid/view/View;)V",
    )
}

@Suppress("unused")
val createStickersPatch = bytecodePatch(
    name = "Create stickers",
    description = "Adds a Create button to the Saved page of the comment sticker panel. Pick a photo and " +
        "it turns into one of your saved stickers. Make sticker in a video's share sheet does the same " +
        "with its cover. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Comments")
    // The native Report carrier, for the share sheet's Make sticker (#88).
    dependsOn(sharedExtensionPatch, ownedShareActionsPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val calls = stickerCreatorCalls(
            StickerUploadConfigFingerprint.originalMethod,
            StickerServerParameterFingerprint.originalMethod,
            CanvasStickerSaverFingerprint.originalMethod,
        )
        mutableClassDefBy(STICKER_CREATOR).swapStickerStubs(calls)
        FavouriteStickerPageFingerprint.method.addStickerCreatorButton()
        addOwnedShareAction(SHARE_MAKE_STICKER)
        registerForBuildDetails("enableStickerCreator")
    }
}
