/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val RINGS = "Lapp/morphe/extension/tiktok/feed/AvatarRings;"
private const val AVATAR = "Lcom/ss/android/ugc/aweme/feed/assem/avatar/"
internal const val AVATAR_USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
private const val AVATAR_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val AVATAR_LIVE_DATA_ADAPTER = "Lcom/ss/android/ugc/aweme/live/avatar/AvatarLiveDataAdapter;"

/** The story status every story ring keys on, a plain field getter on every build. */
internal object UserStoryStatusFingerprint : Fingerprint(
    definingClass = AVATAR_USER,
    name = "getStoryStatus",
    parameters = emptyList(),
    returnType = "I",
)

/**
 * The feed avatar's bind: it attaches FeedAvatarLiveAssem when the author live check says yes
 * and FeedAvatarSocialPublishAssem when the author has a story. R8 renames it (bs on 47.0.3, Pr
 * on 47.1.x); it is the wrap's only VideoItemParams-to-void method that names the live assem.
 */
internal object FeedAvatarWrapBindFingerprint : Fingerprint(
    definingClass = "${AVATAR}FeedAvatarAssemWrap;",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
    returnType = "V",
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.CONST_CLASS &&
                ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type ==
                "${AVATAR}FeedAvatarLiveAssem;"
        } == true
    },
)

private fun MethodReference.isAuthorLiveCheck() =
    returnType == "Z" && parameterTypes.map { it.toString() } == listOf(AVATAR_AWEME, AVATAR_USER)

/**
 * The author live check the feed avatar's bind calls: one static (Aweme, User)Z method, renamed
 * by R8 on every build (09A7.LIZIZ on 47.0.3, 0963.LIZIZ on 47.1.3, 0967.LIZIZ on 47.1.4).
 */
internal fun Method.authorLiveCheck(): MethodReference {
    val calls = implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.isAuthorLiveCheck() }
        .distinctBy { it.toString() }
    return calls.singleOrNull()
        ?: throw IllegalStateException("Feed avatar bind calls ${calls.size} author live checks: $calls")
}

/**
 * TikTok's social avatar entry, the class behind every profile picture that can carry a story
 * ring: its mode computation logs this tag and takes the User (#351).
 */
internal object SocialAvatarModeFingerprint : Fingerprint(
    parameters = listOf(AVATAR_USER),
    strings = listOf("computeAndSetAvatarMode: "),
)

private fun ClassDef.enumNames() =
    fields.filter { AccessFlags.ENUM.isSet(it.accessFlags) }.map { it.name }.toSet()

private fun Field.smali() = "$definingClass->$name:$type"

/** The entry's `(status, Z) -> mode` method and the field naming the screen it sits on. */
internal class SocialAvatarAnchors(val mode: Method, val scene: Field)

internal fun socialAvatarAnchors(entry: ClassDef, classOf: (String) -> ClassDef?): SocialAvatarAnchors {
    val modes = entry.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
            method.parameterTypes.map { it.toString() } == listOf("I", "Z") &&
            classOf(method.returnType)?.enumNames()?.containsAll(listOf("ON", "OFF", "NONE")) == true
    }
    val scenes = entry.fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            classOf(field.type)?.enumNames()?.containsAll(listOf("FEED", "COMMENT", "OTHER_PROFILE")) == true
    }
    if (modes.size != 1 || scenes.size != 1) {
        throw IllegalStateException("${entry.type}: ${modes.size} ring mode methods, ${scenes.size} scene fields")
    }
    return SocialAvatarAnchors(modes.single(), scenes.single())
}

/**
 * AvatarLiveDataAdapter's status method (the one reading User.isLive), its config field and the
 * config's scene number: 1 on profiles, 8 in chats, no config at all in comments and other lists.
 */
internal class LiveAdapterAnchors(val status: Method, val config: Field, val scene: Field)

internal fun liveAdapterAnchors(adapter: ClassDef, classOf: (String) -> ClassDef?): LiveAdapterAnchors {
    val statuses = adapter.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && !AccessFlags.BRIDGE.isSet(method.accessFlags) &&
            !AccessFlags.SYNTHETIC.isSet(method.accessFlags) && method.returnType != "V" &&
            method.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Object;") &&
            method.implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() ==
                    "$AVATAR_USER->isLive()Z"
            } == true
    }
    val configType = adapter.methods.singleOrNull { method ->
        method.name == "<init>" && method.parameterTypes.size == 1 &&
            method.parameterTypes.single().toString() != "Ljava/lang/Object;"
    }?.parameterTypes?.single()?.toString()
    val config = adapter.fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == configType }
    val scene = configType?.let(classOf)?.fields
        ?.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "I" }
        ?.singleOrNull()
    if (statuses.size != 1 || config == null || scene == null) {
        throw IllegalStateException(
            "${adapter.type}: ${statuses.size} live status methods, config $configType, scene $scene",
        )
    }
    return LiveAdapterAnchors(statuses.single(), config, scene)
}

@Suppress("unused")
val avatarRingsPatch = bytecodePatch(
    name = "Remove avatar rings",
    description = "Takes the story and LIVE rings off profile pictures, so tapping a picture " +
        "opens the profile instead of a story or LIVE. It can also open a LIVE creator's Story " +
        "from their profile picture or their chat in the Inbox. Starts off. Turn it on in " +
        "Hushfeed settings > Feed screen.",
    default = true,
) {
    category("Feed")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        UserStoryStatusFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(index, """
                    invoke-static/range { v$register .. v$register }, $RINGS->storyStatus(I)I
                    move-result v$register
                """)
            }
        }

        val liveCheck = FeedAvatarWrapBindFingerprint.method.authorLiveCheck()
        mutableClassDefBy(liveCheck.definingClass).methods.single { method ->
            method.name == liveCheck.name && method.returnType == liveCheck.returnType &&
                method.parameterTypes.map { it.toString() } == liveCheck.parameterTypes.map { it.toString() }
        }.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(index, """
                    invoke-static/range { v$register .. v$register }, $RINGS->authorLive(Z)Z
                    move-result v$register
                """)
            }
        }

        // #351: every story ring is drawn by the social avatar entry from a status it is handed,
        // and the entry knows its screen. The status goes through the extension with the screen
        // at the top of the mode method, while its parameters are still the caller's.
        val entry = SocialAvatarModeFingerprint.classDef
        val social = socialAvatarAnchors(entry) { classDefByOrNull(it) }
        entry.methods.single { it.name == social.mode.name &&
            it.parameterTypes.map { type -> type.toString() } == listOf("I", "Z") }.apply {
            val parameters = 3 // this, the status and the flag
            check(implementation!!.registerCount - parameters >= 2) { "${entry.type}->$name has no two locals" }
            addInstructions(0, """
                move/from16 v0, p1
                move-object/from16 v1, p0
                iget-object v1, v1, ${social.scene.smali()}
                invoke-static/range { v0 .. v1 }, $RINGS->storyStatusIn(ILjava/lang/Object;)I
                move-result v0
                move/from16 p1, v0
            """)
        }

        // Every LIVE ring off the feed asks AvatarLiveDataAdapter, built with the scene number of
        // its screen. The top of its status method asks the extension with that number, and a yes
        // answers null, TikTok's own "not live", before the User or Aweme is read.
        val adapterClass = mutableClassDefBy(AVATAR_LIVE_DATA_ADAPTER)
        val live = liveAdapterAnchors(adapterClass) { classDefByOrNull(it) }
        adapterClass.methods.single { it.name == live.status.name &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;") &&
            it.returnType == live.status.returnType }.apply {
            check(implementation!!.registerCount - 2 >= 2) { "${adapterClass.type}->$name has no two locals" }
            addInstructionsWithLabels(0, """
                const/4 v0, -0x1
                move-object/from16 v1, p0
                iget-object v1, v1, ${live.config.smali()}
                if-eqz v1, :ask
                iget v0, v1, ${live.scene.smali()}
                :ask
                invoke-static { v0 }, $RINGS->hideLiveIn(I)Z
                move-result v0
                if-eqz v0, :keep
                const/4 v0, 0x0
                return-object v0
            """, ExternalLabel("keep", getInstruction(0)))
        }

        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAvatarRings()V")

        // #233: the profile and Inbox pictures open a LIVE creator's Story first, on its own switch.
        hookStoryOverLiveOn(packageMetadata.versionName)
    }
}
