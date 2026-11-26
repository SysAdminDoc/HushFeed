/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
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
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val STICKER_CREATOR = "Lapp/morphe/extension/tiktok/comment/StickerCreator;"
internal const val FAVOURITE_STICKER_PAGE = "Lcom/ss/android/ugc/aweme/im/sticker/basic/panel/BasicFavouriteStickerPageCell;"
private const val IM_STICKER_API = "Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;"
private const val STICKER_ITEM = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"
private const val PHOTO_TO_STICKER_RESPONSE = "Lcom/ss/android/ugc/aweme/comment/easteregg/model/PhotoToStickerResponse;"
private const val STICKER_UPLOAD_CONFIG_API = "Lcom/ss/android/ugc/aweme/subscription/pgc/jsbridge/upload/StickerUploadConfigApi;"
private const val STICKER_ACCOUNT_SERVICE = "Lcom/ss/android/ugc/aweme/IAccountService;"
private const val STICKER_OBJECT = "Ljava/lang/Object;"
private const val STICKER_STRING = "Ljava/lang/String;"
private const val PROFILE_USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
private const val PROFILE_STICKER_TAB_MODEL = "Lcom/ss/android/ugc/aweme/profile/model/StickerTab;"
internal const val PROFILE_STICKER_TAB = "Lapp/morphe/extension/tiktok/comment/ProfileStickerTab;"
internal const val PROFILE_STICKER_PAGE =
    "Lcom/ss/android/ugc/profile/business/message/fragment/StickerStoreProfileListFragment;"

private fun MethodReference.stickerDescriptor() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.stickerDescriptor() = "$definingClass->$name:$type"

private fun Method.stickerMethodCalls(): List<MethodReference> =
    implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference } ?: emptyList()

/**
 * StickerApiV2's im/sticker/save: type, uri, width, height, tags, source, create source, the
 * canvas text (null when there is none, new on 47.9.3) and the continuation. R8 renames it
 * (LJII on TikTok 47.9.3, LIZIZ on 48.0.2).
 */
internal fun MethodReference.isStickerSaveCall(): Boolean {
    val parameters = parameterTypes.map { it.toString() }
    return definingClass == STICKER_API && returnType == STICKER_OBJECT && parameters.size == 9 &&
        parameters.subList(0, 8) ==
        listOf("I", STICKER_STRING, "I", "I", "Ljava/util/List;", STICKER_STRING, "Ljava/lang/Integer;", STICKER_STRING)
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
    // The call the custom check looks for, so the patcher's index finds its one caller.
    filters = listOf(methodCall(definingClass = STICKER_UPLOAD_CONFIG_API, name = "getUploadConfig")),
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
 * The static getter of TikTok's StickerApiV2 client, which takes it off a lazy (0Cae.LIZJ on
 * TikTok 47.9.3, 0F4G.LIZJ on 48.0.2). Up to 47.9.3 the bridges read it, the save and the Saved
 * store calls out of the DM canvas saver (0kNa.LIZIZ, "dm_ai_canvas"). 48.0.2 dropped that saver
 * and every call to im/sticker/save, though StickerApiV2 still declares the save, so each piece
 * now comes from where it lives: this getter, the API interface and Photo to sticker's add to Saved.
 */
internal object StickerApiGetterFingerprint : Fingerprint(
    returnType = STICKER_API,
    parameters = listOf(),
    filters = listOf(checkCast(STICKER_API)),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Photo to sticker's last step (17eQ.invokeSuspend on TikTok 47.9.3, 12QJ.invokeSuspend on
 * 48.0.2): once a comment photo is saved as a sticker, it adds the answer to Saved through the IM
 * sticker store, the same holder, getter, store and add the canvas saver used.
 */
internal object StickerSavedStoreFingerprint : Fingerprint(
    returnType = STICKER_OBJECT,
    filters = listOf(
        fieldAccess(definingClass = PHOTO_TO_STICKER_RESPONSE, name = "stickerItem", type = STICKER_ITEM),
        methodCall(definingClass = IM_STICKER_API, name = "getImStickerStoreService"),
    ),
)

/** One call a bridge makes, with the invoke TikTok makes it with. */
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
 * Reads every call the bridges need: the sticker API from its [apiGetter], the save from the
 * [api] interface that declares it, and the Saved store calls from Photo to sticker's [store] step.
 * Checks the two suspend calls take the same continuation, since one proxy answers both.
 */
internal fun stickerCreatorCalls(
    uploadConfig: Method,
    serverParameter: Method,
    apiGetter: Method,
    api: ClassDef,
    store: Method,
): StickerCreatorCalls {
    val instructions = store.implementation?.instructions?.toList()
        ?: throw PatchException("Create stickers: the Saved store step has no code")

    fun call(what: String, wanted: (MethodReference) -> Boolean): StickerCall {
        val found = instructions.mapNotNull { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            val invoke = instruction.opcode.stickerInvoke()
            if (reference != null && invoke != null && wanted(reference)) StickerCall(invoke, reference) else null
        }.distinct()
        if (found.size != 1) throw PatchException("Create stickers: ${found.size} $what calls in the Saved store step, expected 1")
        return found.single()
    }

    if (api.type != STICKER_API) throw PatchException("Create stickers: ${api.type} is not the sticker API")
    val saves = api.methods.filter { it.isStickerSaveCall() }
    if (saves.size != 1) throw PatchException("Create stickers: the sticker API declares ${saves.size} im/sticker/save methods, expected 1")
    val saveInvoke = if (AccessFlags.INTERFACE.isSet(api.accessFlags)) "invoke-interface" else "invoke-virtual"
    val save = saves.single().let {
        StickerCall(saveInvoke, ImmutableMethodReference(it.definingClass, it.name, it.parameterTypes, it.returnType))
    }
    if (!AccessFlags.STATIC.isSet(apiGetter.accessFlags) || apiGetter.returnType != STICKER_API || apiGetter.parameterTypes.isNotEmpty()) {
        throw PatchException("Create stickers: the sticker API getter isn't static")
    }
    val stickerApi = StickerCall(
        "invoke-static",
        ImmutableMethodReference(apiGetter.definingClass, apiGetter.name, apiGetter.parameterTypes, apiGetter.returnType),
    )
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
    if (holders.size != 1) throw PatchException("Create stickers: ${holders.size} IMStickerApi holders in the Saved store step, expected 1")

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
        // The save takes the canvas text between the create source and the continuation, so the
        // API object, the seven arguments, a null text and the continuation are copied into one
        // range of ten locals ahead of the stub's eight arguments.
        stickerBridge(
            "saveSticker",
            listOf("I", STICKER_STRING, "I", "I", "Ljava/util/List;", STICKER_STRING, "Ljava/lang/Integer;", STICKER_OBJECT),
            STICKER_OBJECT, 10, flagsFor("saveSticker"),
            """
                invoke-static { }, ${calls.stickerApi.reference.stickerDescriptor()}
                move-result-object v0
                move/from16 v1, p0
                move-object/from16 v2, p1
                move/from16 v3, p2
                move/from16 v4, p3
                move-object/from16 v5, p4
                move-object/from16 v6, p5
                move-object/from16 v7, p6
                const/4 v8, 0x0
                move-object/from16 v9, p7
                check-cast v9, $continuation
                ${calls.save.invoke}/range { v0 .. v9 }, ${calls.save.reference.stickerDescriptor()}
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

/**
 * The profile's Stickers tab gate (0OB3.LIZIZ on TikTok 47.9.3, #214): static, takes the User, asks
 * the IM sticker store's Z(Z) check and on yes returns a helper of its own class that asks whether
 * the profile owns a sticker set. Otherwise it reads the profile's own showStickerTab flag. 47.6.3
 * read only the flag.
 */
internal fun Method.isProfileStickerTabGate(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "Z" ||
        parameterTypes.map { it.toString() } != listOf(PROFILE_USER)
    ) return false
    val references = implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference } ?: return false
    return references.any { it is MethodReference && it.definingClass == IM_STICKER_API && it.name == "getImStickerStoreService" } &&
        references.any { it is FieldReference && it.definingClass == PROFILE_STICKER_TAB_MODEL && it.name == "showStickerTab" }
}

internal object ProfileStickerTabFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(PROFILE_USER),
    custom = { method, _ -> method.isProfileStickerTabGate() },
)

/**
 * Hands the gate's owned-set answer to ProfileStickerTab right before it's returned, so a profile
 * whose own flag says to show the tab gets it again.
 */
internal fun MutableMethod.keepProfileStickerTab() {
    val code = implementation?.instructions?.toList()
        ?: throw PatchException("Create stickers: the Stickers tab gate has no code")
    val owned = code.indices.filter { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        code[index].opcode == Opcode.INVOKE_STATIC && call != null && call.definingClass == definingClass &&
            call.returnType == "Z" && call.parameterTypes.map { it.toString() } == listOf(PROFILE_USER) &&
            code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT && code.getOrNull(index + 2)?.opcode == Opcode.RETURN &&
            (code[index + 1] as OneRegisterInstruction).registerA == (code[index + 2] as OneRegisterInstruction).registerA
    }
    if (owned.size != 1) {
        throw PatchException("Create stickers: ${owned.size} owned sticker set returns in the Stickers tab gate, expected 1")
    }
    val returned = owned.single() + 2
    val result = (code[returned] as OneRegisterInstruction).registerA
    val user = implementation!!.registerCount - 1
    if (result > 15 || user > 15) throw PatchException("Create stickers: the Stickers tab gate's registers don't fit the hook")
    if (code.any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == user }) {
        throw PatchException("Create stickers: the Stickers tab gate writes over the profile")
    }
    addInstructionsAtControlFlowLabel(
        returned,
        """
            invoke-static { v$result, v$user }, $PROFILE_STICKER_TAB->show(ILjava/lang/Object;)Z
            move-result v$result
        """,
    )
}

/**
 * The Stickers tab's page on a profile, the no-argument method onViewCreated hands the work to, which
 * picks TikTok's curated set page or the sticker sets pager. It's the one that asks for the curated
 * page, by the (uid, view model key, is self) call that returns a Fragment.
 */
internal object ProfileStickerPageFingerprint : Fingerprint(
    definingClass = PROFILE_STICKER_PAGE,
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.returnType == "Landroidx/fragment/app/Fragment;" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(STICKER_STRING, STICKER_STRING, "Z")
            } == true
        } == true
    },
)

/**
 * Hands the page's curated-set experiment answer to ProfileStickerTab with the profile's uid, right
 * after it's read. 47.9.3 asks the sticker store's Z(Z) check, then on yes asks the store for the
 * curated page with (uid, view model key, is self); on no it builds the 47.6.3 sticker sets pager.
 */
internal fun MutableMethod.keepProfileStickerPage() {
    val code = implementation?.instructions?.toList()
        ?: throw PatchException("Create stickers: the Stickers tab page has no code")
    val store = code.firstNotNullOfOrNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)
            ?.takeIf { call -> call.definingClass == IM_STICKER_API && call.name == "getImStickerStoreService" }
    }?.returnType ?: throw PatchException("Create stickers: the Stickers tab page has no sticker store")
    fun storeCall(index: Int, returns: String, parameters: List<String>): Boolean {
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return code[index].opcode == Opcode.INVOKE_INTERFACE && call.definingClass == store && call.returnType == returns &&
            call.parameterTypes.map { it.toString() } == parameters
    }
    val checks = code.indices.filter { storeCall(it, "Z", listOf("Z")) && code.getOrNull(it + 1)?.opcode == Opcode.MOVE_RESULT }
    if (checks.size != 1) throw PatchException("Create stickers: ${checks.size} curated set checks in the Stickers tab page, expected 1")
    val answer = checks.single() + 1
    val curated = (answer + 1 until code.size).firstOrNull {
        storeCall(it, "Landroidx/fragment/app/Fragment;", listOf(STICKER_STRING, STICKER_STRING, "Z"))
    } ?: throw PatchException("Create stickers: the Stickers tab page never opens the curated set page")
    val result = (code[answer] as OneRegisterInstruction).registerA
    val uid = (code[curated] as? FiveRegisterInstruction)?.registerD
        ?: throw PatchException("Create stickers: the Stickers tab page's curated set call takes a register range")
    if (result > 15 || uid > 15) throw PatchException("Create stickers: the Stickers tab page's registers don't fit the hook")
    val overwritten = (answer + 1 until curated).any {
        val written = (code[it] as? OneRegisterInstruction)?.registerA ?: return@any false
        code[it].opcode.setsRegister() && (written == uid || (code[it].opcode.setsWideRegister() && written + 1 == uid))
    }
    if (overwritten) {
        throw PatchException("Create stickers: the Stickers tab page writes over the uid before the curated set page")
    }
    if (isBranchTarget(answer + 1)) throw PatchException("Create stickers: something lands right after the curated set check")
    addInstructions(
        answer + 1,
        """
            invoke-static { v$result, v$uid }, $PROFILE_STICKER_TAB->curatedPage(ILjava/lang/String;)Z
            move-result v$result
        """,
    )
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
        "it turns into one of your saved stickers. Make sticker in a video's share sheet opens TikTok's " +
        "sticker editor for any video, or uses its cover when the editor can't open. Profiles that have " +
        "stickers keep their Stickers tab, and it opens their sticker sets. Works as soon as you patch it " +
        "in, with no switch.",
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
            StickerApiGetterFingerprint.originalMethod,
            classDefBy(STICKER_API),
            StickerSavedStoreFingerprint.originalMethod,
        )
        mutableClassDefBy(STICKER_CREATOR).swapStickerStubs(calls)
        FavouriteStickerPageFingerprint.method.addStickerCreatorButton()
        ProfileStickerTabFingerprint.method.keepProfileStickerTab()
        ProfileStickerPageFingerprint.method.keepProfileStickerPage()
        addOwnedShareAction(SHARE_MAKE_STICKER)
        registerForBuildDetails("enableStickerCreator")
    }
}
