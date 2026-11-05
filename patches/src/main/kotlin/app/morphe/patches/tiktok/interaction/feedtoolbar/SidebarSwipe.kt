/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide feed LIVE button"
private const val HOME_SIDEBAR_SERVICE = "Lcom/ss/android/ugc/aweme/spi/IHomeSidebarService;"
private const val FEATURE_CONTROLS = "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

/** One ask: [answer] is the index of the move-result holding the service's yes or no in [register]. */
internal data class SidebarSwipeAsk(val answer: Int, val register: Int, val helper: String)

/**
 * Where TikTok asks the home sidebar service whether the side menu is on offer before it lets a
 * right swipe from For You go to the side menu page (#213), so a no sends the swipe to the next
 * tab. The feed's edge-swipe helper holds the hot-area test `(FF)Z` and a lazy holder of the
 * service, and every ask reads the service out of that holder, null-checks it and calls its plain
 * `()Z`. 47.6.3 asked in one helper method (X.0Ot6.LIZJ); 47.7.2's R8 inlined that method into
 * the pager's touch dispatch and both swipe handlers, so each copy is found by this shape and
 * hooked where it reads the answer. The helper is renamed on every build; [classOf] finds it so
 * its hot-area test can be checked.
 */
internal fun sidebarSwipeAsks(method: Method, classOf: (String) -> ClassDef?): List<SidebarSwipeAsk> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { index ->
        val ask = code[index]
        if (index < 5 || index + 1 >= code.size || ask.opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
        val asked = ask.getReference<MethodReference>() ?: return@mapNotNull null
        if (asked.definingClass != HOME_SIDEBAR_SERVICE || asked.returnType != "Z" || asked.parameterTypes.isNotEmpty()) {
            return@mapNotNull null
        }
        val service = (ask as FiveRegisterInstruction).takeIf { it.registerCount == 1 }?.registerC ?: return@mapNotNull null
        val answer = code[index + 1]
        if (answer.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        val nullCheck = code[index - 1]
        val cast = code[index - 2]
        val unwrapped = code[index - 3]
        val unwrap = code[index - 4]
        val read = code[index - 5]
        val holder = read.getReference<FieldReference>()
        val getValue = unwrap.getReference<MethodReference>()
        val shaped = nullCheck.opcode == Opcode.IF_EQZ && (nullCheck as OneRegisterInstruction).registerA == service &&
            cast.opcode == Opcode.CHECK_CAST && (cast as OneRegisterInstruction).registerA == service &&
            cast.getReference<TypeReference>()?.type == HOME_SIDEBAR_SERVICE &&
            unwrapped.opcode == Opcode.MOVE_RESULT_OBJECT && (unwrapped as OneRegisterInstruction).registerA == service &&
            unwrap.opcode == Opcode.INVOKE_INTERFACE && (unwrap as FiveRegisterInstruction).registerCount == 1 &&
            unwrap.registerC == service && getValue?.name == "getValue" && getValue.parameterTypes.isEmpty() &&
            getValue.returnType == "Ljava/lang/Object;" &&
            read.opcode == Opcode.IGET_OBJECT && (read as TwoRegisterInstruction).registerA == service &&
            holder != null && holder.type == getValue.definingClass
        if (!shaped) return@mapNotNull null
        // The hot-area test sits in the helper beside the holder: two floats in, a yes or no out.
        val hotArea = classOf(holder!!.definingClass)?.methods?.any { sibling ->
            sibling.returnType == "Z" && sibling.parameterTypes.map { it.toString() } == listOf("F", "F")
        } == true
        if (!hotArea) return@mapNotNull null
        SidebarSwipeAsk(index + 1, (answer as OneRegisterInstruction).registerA, holder.definingClass)
    }
}

/** Every method asking the sidebar service a plain `()Z`; [sidebarSwipeAsks] picks the swipe asks among them. */
internal object SidebarSwipeFingerprint : Fingerprint(
    filters = listOf(methodCall(definingClass = HOME_SIDEBAR_SERVICE, returnType = "Z", parameters = emptyList())),
)

/** Filters each ask's answer through the swipe switch, right after TikTok reads it. Later asks first. */
internal fun MutableMethod.filterSidebarSwipeAsks(asks: List<SidebarSwipeAsk>) {
    asks.sortedByDescending { it.answer }.forEach { ask ->
        addInstructions(
            ask.answer + 1,
            """
                invoke-static/range { v${ask.register} .. v${ask.register} }, $FEATURE_CONTROLS->sidebarSwipeOffered(Z)Z
                move-result v${ask.register}
            """,
        )
    }
}

/**
 * Turns the right swipe to the side menu off while its switch is on (#213), and says whether the
 * hook went in. Required on a declared build, where the anchors test holds it, and left out with
 * a note on any other, so a reworked pager there doesn't take the LIVE switches down with it. The
 * settings row shows only when the hook went in. Takes the version so a test can stand in for a
 * build that isn't declared.
 */
internal fun BytecodePatchContext.hookSidebarSwipeOn(versionName: String): Boolean {
    if (!hookSidebarSwipe(versionName)) return false
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, " +
            "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedSidebarSwipe()V",
    )
    return true
}

/** The guard alone, without the settings flag that shows the row, so a probe patch can run it. */
internal fun BytecodePatchContext.hookSidebarSwipe(versionName: String): Boolean {
    val sites = SidebarSwipeFingerprint.matchAllOrNull().orEmpty()
        .filterNot { it.originalClassDef.type.startsWith("Lapp/morphe/extension/") }
        .map { match -> match to sidebarSwipeAsks(match.originalMethod) { classDefByOrNull(it) } }
        .filter { (_, asks) -> asks.isNotEmpty() }
    if (sites.isEmpty()) {
        if (versionName in declaredVersions()) {
            throw PatchException("$PATCH: no side menu swipe check found on $versionName.")
        }
        println("[$PATCH] Left out the side menu swipe on $versionName: no swipe check found.")
        return false
    }
    val helpers = sites.flatMap { (_, asks) -> asks.map { it.helper } }.toSet()
    if (helpers.size != 1) throw PatchException("$PATCH: the swipe asks read ${helpers.size} different helpers: $helpers.")
    sites.forEach { (match, asks) -> match.method.filterSidebarSwipeAsks(asks) }
    return true
}
