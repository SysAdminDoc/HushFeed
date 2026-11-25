/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.searchsuggestions

import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.classDefByStrings
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.namedRegisters
import app.morphe.patches.tiktok.shared.guardAtEntry
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/search/SearchSuggestions;"
private const val PLUGGABLE_SPI = "Lcom/ss/android/ugc/aweme/framework/services/PluggableExtentionKt;"
/** TikTok's feature check takes (Fragment, String, Z). */
private val FEATURE_CHECK_PARAMETERS = listOf("Landroidx/fragment/app/Fragment;", "Ljava/lang/String;", "Z")

/** The two downloadable features the search rewards services live in. */
internal val SEARCH_REWARDS_FEATURES = setOf("coin", "incentive")

private val SEARCH_REWARDS_PLACEHOLDER =
    Regex("^Lcom/ss/android/ugc/aweme/search/df/api/(cointask|incentivetask)/.+/dummyspi/Dummy\\w+;$")

/** The placeholders for the services that put the coin and the banner on the search pages. */
internal val SEARCH_REWARDS_VIEW_PLACEHOLDERS = setOf(
    "Lcom/ss/android/ugc/aweme/search/df/api/cointask/core/spi/dummyspi/DummySearchCoinTaskControlService;",
    "Lcom/ss/android/ugc/aweme/search/df/api/incentivetask/spi/dummyspi/DummySearchIncentiveTaskBridgeService;",
)

/** A search rewards service getter: the placeholder it falls back to and where its feature check's answer lands. */
internal data class SearchRewardsGetter(val placeholder: String, val loadedResultIndex: Int)

/**
 * TikTok downloads its search rewards code (the points banner, the coin and their tasks) only
 * where the program runs, and the base APK carries a real-named placeholder for each service.
 * Each service comes from a lazy getter of one shape: it asks whether the "coin" or "incentive"
 * feature is loaded and returns the placeholder if not, then asks pluggableSpi for the real
 * service and falls back to the placeholder again. The coin's service never passes through the
 * static accessor [isSearchRewardsAccessor] covers, which is why the coin stayed (#21). Returns
 * where the first answer lands, or null for any other method.
 */
internal fun searchRewardsGetter(method: Method): SearchRewardsGetter? {
    if (method.returnType != "Ljava/lang/Object;") return null
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val placeholder = instructions.mapNotNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.NEW_INSTANCE }?.getReference<TypeReference>()?.type
            ?.takeIf(SEARCH_REWARDS_PLACEHOLDER::matches)
    }.distinct().singleOrNull() ?: return null
    val asksForRealService = instructions.any { instruction ->
        instruction.getReference<MethodReference>()?.let {
            it.definingClass == PLUGGABLE_SPI && it.name.startsWith("pluggableSpi")
        } == true
    }
    if (!asksForRealService) return null

    val checkIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.getReference<MethodReference>()?.let {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == FEATURE_CHECK_PARAMETERS
        } == true
    }
    if (checkIndex < 0 || instructions.getOrNull(checkIndex + 1)?.opcode != Opcode.MOVE_RESULT) return null
    // The feature's name is the check's String argument, loaded just before it. The receiver
    // comes first and no parameter is wide, so it sits one register past its parameter's place.
    val check = instructions[checkIndex]
    val keyParameter = check.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString)
        .indexOf("Ljava/lang/String;")
    val keyRegister = check.namedRegisters()[keyParameter + 1]
    val key = instructions.subList(0, checkIndex).lastOrNull {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            (it as OneRegisterInstruction).registerA == keyRegister
    }?.getReference<StringReference>()?.string
    if (key !in SEARCH_REWARDS_FEATURES) return null
    return SearchRewardsGetter(placeholder, checkIndex + 1)
}

/**
 * Every search rewards service getter, found through the classes that load a feature name. A
 * build where the coin's or the banner's getter can't be found fails here, before anything is
 * written.
 */
internal fun searchRewardsGetters(): List<Pair<MutableMethod, SearchRewardsGetter>> {
    val found = SEARCH_REWARDS_FEATURES
        .flatMap { feature -> classDefByStrings(feature) }
        .distinctBy { it.type }
        .flatMap { classDef ->
            classDef.methods.mapNotNull { method ->
                searchRewardsGetter(method)?.let { Triple(classDef, method, it) }
            }
        }
    val missing = SEARCH_REWARDS_VIEW_PLACEHOLDERS - found.map { it.third.placeholder }.toSet()
    if (missing.isNotEmpty()) {
        throw PatchException("Hide search suggestions: no rewards service getter falls back to ${missing.joinToString()}.")
    }
    return found.map { (classDef, method, getter) ->
        mutableClassDefBy(classDef).findMutableMethodOf(method) to getter
    }
}

internal const val COIN_HOT_WORD_SERVICE =
    "Lcom/ss/android/ugc/aweme/search/service/ISearchCoinIncentiveHotWordService;"
internal const val COIN_HOT_WORD_IMPL =
    "Lcom/ss/android/ugc/aweme/search/common/communicate/SearchCoinIncentiveHotWordServiceImpl;"
internal const val COIN_HOT_WORD_OFF =
    "Lcom/ss/android/ugc/aweme/search/service/SearchCoinIncentiveHotWordServiceDowngradeImpl;"

/**
 * The points bubble by the search icon ("Type 12 searches to get 864 points", #522) is a popup
 * the coin hot-word service builds, and that service ships in the base APK, so the feature check
 * above never sees it. TikTok also ships a switched-off copy of it, the one it falls back to
 * when the service can't be had, and every answer of that copy is empty: no word, false, null,
 * or its empty popup. Returns each method of the real service that the interface declares, after
 * checking the copy has the same one. Fails before anything is written if either has changed.
 */
internal fun coinHotWordServiceMethods(): List<MutableMethod> {
    val declared = classDefBy(COIN_HOT_WORD_SERVICE).methods.filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) }
    if (declared.isEmpty()) throw PatchException("Hide search suggestions: the coin hot-word service declares nothing.")
    val off = classDefBy(COIN_HOT_WORD_OFF)
    val real = mutableClassDefBy(COIN_HOT_WORD_IMPL)
    return declared.map { method ->
        if (off.methods.none { it.sameSignatureAs(method) && it.implementation != null }) {
            throw PatchException("Hide search suggestions: the switched-off coin hot-word service has no ${method.name}.")
        }
        real.methods.firstOrNull { it.sameSignatureAs(method) && it.implementation != null }
            ?: throw PatchException("Hide search suggestions: the coin hot-word service has no ${method.name}.")
    }
}

private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/** With search rewards hidden, hands the call to TikTok's switched-off service and returns its answer. */
internal fun MutableMethod.answerLikeTheSwitchedOffHotWordService() {
    val parameters = parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(accessFlags) || parameters.size > 4 || parameters.any { it == "J" || it == "D" } ||
        returnType == "J" || returnType == "D" || implementation!!.registerCount > 16
    ) {
        throw PatchException("Hide search suggestions: the coin hot-word service's $name no longer fits a plain call.")
    }
    val arguments = (listOf("v0") + parameters.indices.map { "p${it + 1}" }).joinToString(", ")
    val answer = when {
        returnType == "V" -> "return-void"
        returnType.startsWith("L") || returnType.startsWith("[") -> "move-result-object v0\nreturn-object v0"
        else -> "move-result v0\nreturn v0"
    }
    guardAtEntry(
        "Hide search suggestions",
        "invoke-static {}, $EXTENSION->shouldHideRewards()Z",
        """
            new-instance v0, $COIN_HOT_WORD_OFF
            invoke-direct {v0}, $COIN_HOT_WORD_OFF-><init>()V
            invoke-virtual {$arguments}, $COIN_HOT_WORD_OFF->$name(${parameters.joinToString("")})$returnType
            $answer
        """,
    )
}

/** Passes the getter's "is it loaded" answer through the switch, so hidden rewards read as not downloaded. */
internal fun MutableMethod.askBeforeLoadingSearchRewards(getter: SearchRewardsGetter) {
    val loaded = getInstruction<OneRegisterInstruction>(getter.loadedResultIndex).registerA
    addInstructions(
        getter.loadedResultIndex + 1,
        """
            invoke-static/range {v$loaded .. v$loaded}, $EXTENSION->filterRewardsLoaded(Z)Z
            move-result v$loaded
        """,
    )
}
