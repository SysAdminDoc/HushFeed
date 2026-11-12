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
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PROFILE_TABS = "Lapp/morphe/extension/tiktok/profile/ProfileTabs;"
private const val TAB_LIST = "Ljava/util/List;"
private const val TAB_NAME_MAP = "Ljava/util/Map;"
private const val PROFILE_TAB_USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"

/**
 * TikTok's reading of a profile's server-sent components into the tab types the pager lays out.
 * Its class also keeps the map from each tab type to a name that stays put between builds,
 * profile_tab_<kind>, which the extension matches the picks against.
 */
internal object ProfileTabTypesFingerprint : Fingerprint(
    returnType = TAB_LIST,
    parameters = listOf(PROFILE_TAB_USER, TAB_LIST),
    strings = listOf("profile_tab_public_post", "repost_biz"),
)

/** The profile pager's read of the saved tab order, on the class that lays out a profile's tabs. */
internal object ProfileTabOrderFingerprint : Fingerprint(
    returnType = TAB_LIST,
    parameters = emptyList(),
    strings = listOf("profile_tabs_order"),
)

/** The map getter: the only static method on the class that takes nothing and returns a Map. */
internal fun isProfileTabNameMap(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
        method.returnType == TAB_NAME_MAP

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

internal class ProfileTabHooks(val names: MutableMethod, val stub: MutableMethod, val sites: List<Pair<MutableMethod, Int>>)

/**
 * Everything Hide profile tabs needs, found before anything is written: the map getter, opened
 * to the extension's bridge, and each read of a tab list in the pager, where the list goes
 * through the extension before the pager lays out a tab for each entry.
 */
internal fun resolveProfileTabHooks(patch: String): ProfileTabHooks {
    val owner = ProfileTabTypesFingerprint.classDef
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) {
        throw PatchException("$patch: ${owner.type} is not public, so the extension cannot read the tab names.")
    }
    val names = owner.methods.filter(::isProfileTabNameMap).singleOrPatchException("$patch: the profile tab name map")
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
    return ProfileTabHooks(names, stub, sites)
}

internal fun ProfileTabHooks.install() {
    names.accessFlags = names.accessFlags.toPublicAccessFlags()
    stub.addInstructions(
        0,
        """
            invoke-static {}, ${names.definingClass}->${names.name}()$TAB_NAME_MAP
            move-result-object v0
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
