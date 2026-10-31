/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.copyids

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.sharesheet.ShareSnapshotFingerprint
import app.morphe.patches.tiktok.interaction.sharesheet.resolveSharePanel
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/CopyIds;"
internal const val SIGNATURE_COMPONENT =
    "Lcom/ss/android/ugc/profile/platform/business/header/business/bio/business/signature/base/ProfileHeaderBaseSignatureComponent;"
internal const val TUX_TEXT_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"
internal const val BASE_SHARE_PACKAGE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"
internal const val SHARE_PANEL_MARKER = "share_panel_action"
internal const val SHARE_APPS_MARKER = "share_panel_channel"

/**
 * The share sheet's apps panel attach: `0IQ2` on 47.1.4, a FrameLayout whose onAttachedToWindow
 * names `share_panel_channel` and inflates the apps list. The profile sheet in Settings and
 * privacy has only this panel and Send to, no action panel.
 */
internal fun resolveShareAppsPanel(classes: Iterable<ClassDef>): Method {
    val candidates = classes.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == "onAttachedToWindow" && method.returnType == "V" && method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.implementation?.instructions?.any {
                    it.getReference<StringReference>()?.string == SHARE_APPS_MARKER
                } == true
        }.map { classDef to it }
    }
    val (owner, method) = candidates.singleOrNull()
        ?: throw PatchException("Copy bio and IDs: expected one share apps panel attach method, found ${candidates.size}.")
    if (owner.superclass != "Landroid/widget/FrameLayout;") {
        throw PatchException("Copy bio and IDs: the share apps panel is no longer a FrameLayout.")
    }
    return method
}

/**
 * The profile header's bio bind, handed the signature and its extra spans: `Q53` on 47.0.3, `D73`
 * on 47.1.3 and 47.1.4. It's the item's one public (String, List) method returning nothing.
 */
internal object BioBindFingerprint : Fingerprint(
    definingClass = SIGNATURE_COMPONENT,
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/List;"),
)

/**
 * The bio on someone else's profile, with TikTok's translation: a sibling of the own-profile item
 * with its own bind (`d23` on 47.5.3) and its own text view. Hooking only the base item left other
 * people's bios without the long-press copy and without tappable @names (#247).
 */
internal const val TRANSLATED_SIGNATURE_COMPONENT =
    "Lcom/ss/android/ugc/profile/platform/business/header/business/bio/business/signature/base/ProfileHeaderSignatureTranslationComponent;"

internal object TranslatedBioBindFingerprint : Fingerprint(
    // Final as well as public on 47.5.3, so the flags aren't pinned: it's the class's one (String, List) method.
    definingClass = TRANSLATED_SIGNATURE_COMPONENT,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/List;"),
)

/** The text view a bio bind draws the signature in: the first TuxTextView field of its own class it reads. */
internal fun Method.bioTextField(): FieldReference? = implementation?.instructions?.firstNotNullOfOrNull { instruction ->
    if (instruction.opcode != Opcode.IGET_OBJECT) return@firstNotNullOfOrNull null
    instruction.getReference<FieldReference>()?.takeIf { it.type == TUX_TEXT_VIEW && it.definingClass == definingClass }
}

/** The profile card a tap on a viewer's or host's name opens inside a LIVE room, real-named on 47.5.3. */
internal const val LIVE_PROFILE_CARD = "Lcom/bytedance/android/livesdk/profile/cell/HeaderCell;"
internal const val LIVE_CARD_USER = "Lcom/bytedance/android/live/base/model/user/User;"
private const val LIVE_CARD_BIO = "Lwebcast/api/room/GetRoomProfileCardResponse\$Bio;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

private fun Method.invokes(): List<Pair<Int, MethodReference>> =
    implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        index to call
    }?.toList().orEmpty()

/**
 * Where the card hands its bio view the text to show: each `setOriginalText(CharSequence)` call
 * (the card makes three, for no bio, a bio and a translated bio) whose view and text sit in
 * plain non-range registers, in a method that reads the bio's description.
 */
internal fun liveBioSets(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val readsBio = code.any { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            instruction.getReference<FieldReference>()?.let { it.definingClass == LIVE_CARD_BIO && it.name == "description" } == true
    }
    if (!readsBio) return emptyList()
    return method.invokes().filter { (index, call) ->
        call.name == "setOriginalText" && call.parameterTypes.map { it.toString() } == listOf(CHAR_SEQUENCE) &&
            call.returnType == "V" && code[index].opcode == Opcode.INVOKE_VIRTUAL
    }.map { it.first }
}

/**
 * Where the card's user bind puts the name and the username on screen: a `setText(CharSequence)`
 * whose text is the result of a static call taking the card's User and returning a String, the
 * move-result right before it. The bind has two, the display name and the username.
 */
internal fun liveNameSets(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return method.invokes().filter { (index, call) ->
        if (index < 2 || code[index].opcode != Opcode.INVOKE_VIRTUAL) return@filter false
        if (call.name != "setText" || call.parameterTypes.map { it.toString() } != listOf(CHAR_SEQUENCE)) return@filter false
        val text = (code[index] as FiveRegisterInstruction).registerD
        val result = code[index - 1]
        val producer = (code[index - 2] as? ReferenceInstruction)?.reference as? MethodReference
        result.opcode == Opcode.MOVE_RESULT_OBJECT && (result as OneRegisterInstruction).registerA == text &&
            code[index - 2].opcode == Opcode.INVOKE_STATIC && producer != null &&
            producer.parameterTypes.map { it.toString() } == listOf(LIVE_CARD_USER) && producer.returnType == "Ljava/lang/String;"
    }.map { it.first }
}

/** The card setting up its bio. */
internal object LiveCardBioFingerprint : Fingerprint(
    definingClass = LIVE_PROFILE_CARD,
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ -> liveBioSets(method).isNotEmpty() },
)

/** The card's user bind: the one method that takes its User and sets the name and the username. */
internal object LiveCardNamesFingerprint : Fingerprint(
    definingClass = LIVE_PROFILE_CARD,
    returnType = "Z",
    parameters = listOf(LIVE_CARD_USER, "Ljava/lang/Object;"),
    custom = { method, _ -> liveNameSets(method).size == 2 },
)

/**
 * Hands the LIVE profile card's views to the extension. Each hook goes right before the call that
 * uses its two registers, at that call's control flow label, so a branch to the call reaches the
 * hook with those registers still holding the view and the text. The hook writes no register.
 */
internal fun hookLiveCard(bio: MutableMethod, names: MutableMethod) {
    val bioCalls = liveBioSets(bio)
    if (bioCalls.isEmpty()) throw PatchException("Copy bio and IDs: the LIVE profile card's bio wasn't found.")
    val nameCalls = liveNameSets(names)
    if (nameCalls.size != 2) throw PatchException("Copy bio and IDs: the LIVE profile card's name and username weren't found.")
    fun hook(method: MutableMethod, indexes: List<Int>, target: String) {
        indexes.sortedDescending().forEach { index ->
            val call = method.getInstruction<FiveRegisterInstruction>(index)
            method.addInstructionsAtControlFlowLabel(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD} }, $EXTENSION->$target(Landroid/view/View;Ljava/lang/CharSequence;)V",
            )
        }
    }
    hook(bio, bioCalls, "onLiveBio")
    hook(names, nameCalls, "onLiveText")
    // The user comes first in the bind, in p1, which nothing has written yet.
    names.addInstruction(0, "invoke-static/range { p1 .. p1 }, $EXTENSION->onLiveUser(Ljava/lang/Object;)V")
}


/** The share model builder's one BaseSharePackage field, the package the sheet is built from. */
internal fun ClassDef.sharePackageField(): Field? = fields.singleOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == BASE_SHARE_PACKAGE
}

@Suppress("unused")
val copyIdsPatch = bytecodePatch(
    name = "Copy bio and IDs",
    description = "Lets you long-press a bio to copy it, makes the @names in a bio open those " +
        "profiles on a tap, and adds share sheet buttons that copy a username, user ID or video " +
        "ID. On a profile card inside a LIVE room, long-press the bio or the name to copy it, long-press the username for the user ID, or tap the copy mark by the username. On by default. Turn it off in Hushfeed settings > App.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCopyIds()V",
        )

        listOf(BioBindFingerprint, TranslatedBioBindFingerprint).forEach { fingerprint ->
            fingerprint.method.apply {
                val bioText = bioTextField()
                    ?: throw PatchException("Copy bio and IDs: $definingClass's bio bind reads no text view.")
                // p0 and p1 are copied down first, so a larger frame can't push them past v15.
                if (implementation!!.registerCount - 3 < 2) {
                    throw PatchException("Copy bio and IDs: $definingClass's bio bind has no two free registers.")
                }
                addInstructions(
                    0,
                    """
                        move-object/from16 v0, p0
                        iget-object v0, v0, $definingClass->${bioText.name}:$TUX_TEXT_VIEW
                        move-object/from16 v1, p1
                        invoke-static { v0, v1 }, $EXTENSION->onBio(Landroid/view/View;Ljava/lang/String;)V
                    """,
                )
            }
        }

        // The share model's constructor, before its super call: only p1, the builder, is read.
        ShareSnapshotFingerprint.method.apply {
            val builder = parameterTypes.single().toString()
            val packageField = classDefBy(builder).sharePackageField()
                ?: throw PatchException("Copy bio and IDs: $builder holds no single BaseSharePackage.")
            if (implementation!!.registerCount - 2 < 1) {
                throw PatchException("Copy bio and IDs: the share model has no free register.")
            }
            addInstructions(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v0, v0, $builder->${packageField.name}:$BASE_SHARE_PACKAGE
                    invoke-static { v0 }, $EXTENSION->onSharePackage(Ljava/lang/Object;)V
                """,
            )
        }

        hookLiveCard(
            LiveCardBioFingerprint.method,
            LiveCardNamesFingerprint.method,
        )

        val panel = resolveSharePanel(classDefByStrings(SHARE_PANEL_MARKER))
        mutableClassDefBy(panel.definingClass).findMutableMethodOf(panel).addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION->onSharePanel(Landroid/view/View;)V",
        )
        val apps = resolveShareAppsPanel(classDefByStrings(SHARE_APPS_MARKER))
        mutableClassDefBy(apps.definingClass).findMutableMethodOf(apps).addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION->onAppsPanel(Landroid/view/View;)V",
        )
    }
}
