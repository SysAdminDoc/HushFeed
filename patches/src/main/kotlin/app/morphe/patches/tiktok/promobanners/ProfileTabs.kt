/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.optimizer.isBranchTarget
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PROFILE_TABS = "Lapp/morphe/extension/tiktok/profile/ProfileTabs;"
private const val TAB_LIST = "Ljava/util/List;"
private const val PROFILE_TAB_USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
private const val TAB_NAME_PREFIX = "profile_tab_"
private const val VIDEOS_TAB = "profile_tab_public_post"

/**
 * TikTok's reading of a profile's server-sent components into the tab types the pager lays out:
 * each component's name, profile_tab_<kind>, which stays put between builds, matched in turn and
 * turned into the pager's number for that tab.
 */
internal object ProfileTabTypesFingerprint : Fingerprint(
    returnType = TAB_LIST,
    parameters = listOf(PROFILE_TAB_USER, TAB_LIST),
    strings = listOf(VIDEOS_TAB, "repost_biz"),
)

/** The profile pager's read of the saved tab order, on the class that lays out a profile's tabs. */
internal object ProfileTabOrderFingerprint : Fingerprint(
    returnType = TAB_LIST,
    parameters = emptyList(),
    strings = listOf("profile_tabs_order"),
)

private val TAB_TYPE_LOADS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)
private val TAB_TYPE_JUMPS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

private fun Instruction.isTabTypeLoad() = opcode in TAB_TYPE_LOADS

/**
 * The pager's number for each tab name, read from the tab type reader. Each name is compared to
 * the component's and then loaded as a number into one register, the one most names load just
 * before jumping to where the number joins the list. The first such load after a name, before the
 * next name, is its number: some names check a service first, music loads an unrelated number
 * on the way, and the shop showcase adds its number on the spot.
 *
 * TikTok keeps a second map in the same class, from the component ids to the same names. Its
 * numbers are not the pager's, so it can't be used here.
 */
internal fun profileTabTypes(method: Method): Map<Int, String> {
    val instructions = method.implementation?.instructions?.toList().orEmpty()
    val register = instructions.indices
        .filter { instructions[it].isTabTypeLoad() && instructions.getOrNull(it + 1)?.opcode in TAB_TYPE_JUMPS }
        .groupingBy { (instructions[it] as OneRegisterInstruction).registerA }
        .eachCount().maxByOrNull { it.value }?.key ?: return emptyMap()
    val names = instructions.indices.mapNotNull { index ->
        val name = instructions[index].takeIf { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
            ?.getReference<StringReference>()?.string?.takeIf { it.startsWith(TAB_NAME_PREFIX) }
            ?: return@mapNotNull null
        val compare = instructions.getOrNull(index + 1)?.getReference<MethodReference>()
        if (compare?.definingClass != "Ljava/lang/String;" || compare.name != "equals") return@mapNotNull null
        index to name
    }
    val types = sortedMapOf<Int, String>()
    names.forEachIndexed { at, (start, name) ->
        val end = names.getOrNull(at + 1)?.first ?: instructions.size
        val load = (start + 1 until end).firstOrNull {
            instructions[it].isTabTypeLoad() && (instructions[it] as OneRegisterInstruction).registerA == register
        } ?: return@forEachIndexed
        val type = (instructions[load] as NarrowLiteralInstruction).narrowLiteral
        val known = types.putIfAbsent(type, name)
        if (known != null && known != name) {
            throw PatchException("Profile tab type $type reads as both $known and $name.")
        }
    }
    return types
}

/**
 * The pager's two tab lists, for a profile it has the User of and for one it only has the id of:
 * the methods of its class that start from the saved order and return a List.
 */
internal fun profileTabLists(pager: Iterable<Method>, order: Method): List<Method> = pager.filter { method ->
    method.returnType == TAB_LIST && method.parameterTypes.size == 1 &&
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == order.definingClass && it.name == order.name &&
                    it.parameterTypes.isEmpty() && it.returnType == TAB_LIST
            } == true
        } == true
}

/**
 * Where the pager's own methods read one of the tab lists, as the method and the index of the
 * move-result that takes the list. Calls from other classes only look a type up, so they keep
 * TikTok's list.
 */
internal fun <M : Method> profileTabSites(pager: Iterable<M>, lists: List<Method>): List<Pair<M, Int>> =
    pager.flatMap { method ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        instructions.indices.mapNotNull { index ->
            val called = instructions[index].getReference<MethodReference>() ?: return@mapNotNull null
            if (lists.none { it.definingClass == called.definingClass && it.name == called.name &&
                    it.parameterTypes.map(CharSequence::toString) == called.parameterTypes.map(CharSequence::toString) }) {
                return@mapNotNull null
            }
            val result = instructions.getOrNull(index + 1)
            if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
            method to index + 1
        }
    }

internal class ProfileTabHooks(val types: Map<Int, String>, val stub: MutableMethod, val sites: List<Pair<MutableMethod, Int>>)

/**
 * Everything Hide profile tabs needs, found before anything is written: the pager's number for
 * each tab name, handed to the extension's bridge, and each read of a tab list in the pager,
 * where the list goes through the extension before the pager lays out a tab for each entry.
 */
internal fun resolveProfileTabHooks(patch: String): ProfileTabHooks {
    val types = try {
        profileTabTypes(ProfileTabTypesFingerprint.method)
    } catch (problem: PatchException) {
        throw PatchException("$patch: ${problem.message}")
    }
    if (types[0] != VIDEOS_TAB || types.size < 4) {
        throw PatchException("$patch: could not read the profile tab types, got $types.")
    }
    val stub = mutableClassDefBy(PROFILE_TABS).methods.filter { it.name == "tabNamesNative" }
        .singleOrPatchException("$patch: extension tabNamesNative bridge")

    val pager = ProfileTabOrderFingerprint.classDef.methods
    val lists = profileTabLists(pager, ProfileTabOrderFingerprint.method)
    if (lists.size != 2) throw PatchException("$patch: the profile pager has ${lists.size} tab lists, not two.")
    val sites = profileTabSites(pager, lists)
    sites.forEach { (method, index) ->
        if (method.isBranchTarget(index + 1)) {
            throw PatchException("$patch: ${method.name} branches to where its tab list would be filtered.")
        }
    }
    if (sites.isEmpty()) throw PatchException("$patch: nothing in the profile pager reads its tab lists.")
    return ProfileTabHooks(types, stub, sites)
}

/** The pager's numbers and their names as the extension reads them: 0=profile_tab_public_post,2=... */
internal fun Map<Int, String>.encodeProfileTabTypes(): String =
    entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

internal fun ProfileTabHooks.install() {
    stub.addInstructions(
        0,
        """
            const-string v0, "${types.encodeProfileTabTypes()}"
            return-object v0
        """,
    )
    // Last index first in each method, so the earlier ones stay where they were found.
    sites.sortedByDescending { it.second }.forEach { (method, index) ->
        val register = (method.implementation!!.instructions.elementAt(index) as OneRegisterInstruction).registerA
        method.addInstructions(
            index + 1,
            """
                invoke-static/range { v$register .. v$register }, $PROFILE_TABS->filter($TAB_LIST)$TAB_LIST
                move-result-object v$register
            """,
        )
    }
}
