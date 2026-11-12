/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val ACCOUNT_CLEANUP_SOURCES = "Lapp/morphe/extension/tiktok/privacy/AccountCleanupSources;"
private const val SERVICE_MANAGER = "Lcom/ss/android/ugc/aweme/framework/services/ServiceManager;"

/** The profile's Liked tab request. */
internal const val LIKED_LIST_PATH = "/aweme/v1/aweme/favorite/"
/** Adds a video to Favorites or takes it out. */
internal const val COLLECT_PATH = "/aweme/v1/aweme/collect/"
/** The Favorites page's video list. */
internal const val LIST_COLLECTION_PATH = "/aweme/v1/aweme/listcollection/"
/** What the Favorites page's "unavailable" notice sends to clear saved videos deleted since. */
internal const val REMOVE_UNAVAILABLE_PATH = "/aweme/v1/aweme/collect/removeinvalid"

@Suppress("unused")
val accountCleanupPatch = bytecodePatch(
    name = "Account cleanup",
    description = "Adds Remove all reposts, Remove all likes and Clear Favorites to Hushfeed " +
        "settings > Privacy. Each one takes items off your account one at a time through TikTok's " +
        "own requests, with a pause between them, and stops on the first error.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())
    execute {
        val liked = mutableSetOf<String>()
        val favorites = mutableSetOf<String>()
        val unavailable = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (isLikedListService(classDef)) liked += classDef.type
            if (isFavoritesService(classDef)) favorites += classDef.type
            if (isUnavailableFavoritesService(classDef)) unavailable += classDef.type
        }
        // The repost and like services keep their names and the extension reaches them itself.
        // These three are renamed in every build, so their bridges make them the way TikTok does.
        fillServiceBridge("likedListService",
            liked.singleOrPatchException("Account cleanup: liked videos list service"), "liked videos list")
        fillServiceBridge("favoritesService",
            favorites.singleOrPatchException("Account cleanup: Favorites service"), "Favorites")
        fillServiceBridge("unavailableFavoritesService",
            unavailable.singleOrPatchException("Account cleanup: unavailable Favorites service"),
            "unavailable Favorites")
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAccountCleanup()V")
    }
}

/** The bridge's name comes first so ExtensionBridgeLookupTest can read it at each call. */
private fun fillServiceBridge(name: String, service: String, what: String) = run {
    // Only a method that casts to the service can make it, and the patcher's type index finds
    // those without reading the rest of TikTok.
    val recipe = Fingerprint(filters = listOf(checkCast(service))).matchAllOrNull().orEmpty()
        .flatMap { match -> serviceRecipes(match.originalMethod) }
        .filter { (made, _) -> made == service }
        .map { (_, recipe) -> recipe }
        .distinct()
        .singleOrPatchException("Account cleanup: how TikTok makes the $what service")
    val sources = mutableClassDefBy(ACCOUNT_CLEANUP_SOURCES)
    val original = sources.methods.filter { it.name == name }
        .singleOrPatchException("Account cleanup: extension $name bridge")
    val bridge = original.cloneMutable(additionalRegisters = 2)
    sources.methods.remove(original)
    sources.methods.add(bridge)
    bridge.makeService(service, recipe)
}

/**
 * How TikTok makes one of its request services: the network factory taken from ServiceManager
 * by its class, a client for one host from that factory, and the service from the client.
 */
internal data class ServiceRecipe(
    val factory: MethodReference,
    val factoryCall: Opcode,
    val host: FieldReference,
    val create: MethodReference,
    val createCall: Opcode,
)

private fun Method.annotatedWith(path: String) = annotations.any { annotation ->
    annotation.elements.any { (it.value as? StringEncodedValue)?.value == path }
}

/** The query or form name each parameter's request annotation gives it. */
private fun Method.requestNames(): List<String?> = parameters.map { parameter ->
    parameter.annotations.firstNotNullOfOrNull { annotation ->
        annotation.elements.firstNotNullOfOrNull { (it.value as? StringEncodedValue)?.value }
    }
}

/** The service behind the profile's Liked tab: its list request takes a secure uid and a cursor. */
internal fun isLikedListService(classDef: ClassDef) = AccessFlags.INTERFACE.isSet(classDef.accessFlags) &&
    classDef.methods.any { method ->
        method.annotatedWith(LIKED_LIST_PATH) &&
            method.requestNames().containsAll(listOf("sec_user_id", "max_cursor", "count"))
    }

/** The service the Favorites page lists with and takes a video out of Favorites with. */
internal fun isFavoritesService(classDef: ClassDef) = AccessFlags.INTERFACE.isSet(classDef.accessFlags) &&
    classDef.methods.any { it.annotatedWith(COLLECT_PATH) && it.requestNames() == listOf("aweme_id", "action") } &&
    classDef.methods.any { it.annotatedWith(LIST_COLLECTION_PATH) }

/** The service the Favorites page's "unavailable" notice clears deleted saved videos with, from a cursor. */
internal fun isUnavailableFavoritesService(classDef: ClassDef) = AccessFlags.INTERFACE.isSet(classDef.accessFlags) &&
    classDef.methods.any { it.annotatedWith(REMOVE_UNAVAILABLE_PATH) && it.requestNames() == listOf("cursor") }

/** A two-register call through an interface or a virtual method, or null. */
private fun Instruction.twoRegisterCall(): MethodReference? {
    if (opcode != Opcode.INVOKE_INTERFACE && opcode != Opcode.INVOKE_VIRTUAL) return null
    if ((this as FiveRegisterInstruction).registerCount != 2) return null
    return (this as ReferenceInstruction).reference as? MethodReference
}

private fun Instruction.classLoaded(): String? =
    if (opcode == Opcode.CONST_CLASS) ((this as ReferenceInstruction).reference as? TypeReference)?.type else null

private fun Instruction.isCreateCall(): Boolean {
    val call = twoRegisterCall() ?: return false
    return call.name == "create" && call.returnType == "Ljava/lang/Object;" &&
        call.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Class;")
}

/**
 * Each service [method] makes the way TikTok makes its request services, with the service's
 * type. The shape, in order: a static String field read (the host), a factory call taking it
 * whose answer becomes the client, `const-class` of the service, and the client's
 * `create(Class)` taking that class. The factory has to come from ServiceManager by its class
 * earlier in the method.
 */
internal fun serviceRecipes(method: Method): List<Pair<String, ServiceRecipe>> {
    val instructions = method.implementation?.instructions ?: return emptyList()
    if (instructions.none { it.isCreateCall() }) return emptyList()
    val code = instructions.toList()
    val found = mutableListOf<Pair<String, ServiceRecipe>>()
    for (index in 3 until code.size - 1) {
        val service = code[index].classLoaded() ?: continue
        val createCall = code[index + 1]
        if (!createCall.isCreateCall()) continue
        val create = createCall.twoRegisterCall()!!
        createCall as FiveRegisterInstruction
        if (createCall.registerD != (code[index] as OneRegisterInstruction).registerA) continue
        val client = code[index - 1]
        if (client.opcode != Opcode.MOVE_RESULT_OBJECT ||
            (client as OneRegisterInstruction).registerA != createCall.registerC) continue
        val factoryCall = code[index - 2]
        val factory = factoryCall.twoRegisterCall() ?: continue
        if (factory.returnType != create.definingClass ||
            factory.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;")) continue
        val hostRead = code[index - 3]
        if (hostRead.opcode != Opcode.SGET_OBJECT) continue
        val host = (hostRead as ReferenceInstruction).reference as FieldReference
        if (host.type != "Ljava/lang/String;" ||
            (hostRead as OneRegisterInstruction).registerA != (factoryCall as FiveRegisterInstruction).registerD) continue
        val before = code.subList(0, index - 3)
        if (before.none { it.classLoaded() == factory.definingClass }) continue
        if (before.none { instruction ->
                val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                called != null && called.definingClass == SERVICE_MANAGER && called.name == "getService"
            }) continue
        found += service to ServiceRecipe(
            ImmutableMethodReference.of(factory), factoryCall.opcode,
            ImmutableFieldReference.of(host),
            ImmutableMethodReference.of(create), createCall.opcode,
        )
    }
    return found
}

private fun callMnemonic(opcode: Opcode) = when (opcode) {
    Opcode.INVOKE_INTERFACE -> "invoke-interface"
    Opcode.INVOKE_VIRTUAL -> "invoke-virtual"
    else -> throw PatchException("Account cleanup: TikTok makes its services with $opcode")
}

private fun MethodReference.descriptor() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Fills a static bridge that answers Object with the service made the way [recipe] says, in two
 * scratch registers and straight-line code.
 */
internal fun MutableMethod.makeService(service: String, recipe: ServiceRecipe) {
    val factoryClass = recipe.factory.definingClass
    addInstructions(0, """
        invoke-static {}, $SERVICE_MANAGER->get()$SERVICE_MANAGER
        move-result-object v0
        const-class v1, $factoryClass
        invoke-virtual {v0, v1}, $SERVICE_MANAGER->getService(Ljava/lang/Class;)Ljava/lang/Object;
        move-result-object v0
        check-cast v0, $factoryClass
        sget-object v1, ${recipe.host.definingClass}->${recipe.host.name}:${recipe.host.type}
        ${callMnemonic(recipe.factoryCall)} {v0, v1}, ${recipe.factory.descriptor()}
        move-result-object v0
        const-class v1, $service
        ${callMnemonic(recipe.createCall)} {v0, v1}, ${recipe.create.descriptor()}
        move-result-object v0
        return-object v0
    """)
}
