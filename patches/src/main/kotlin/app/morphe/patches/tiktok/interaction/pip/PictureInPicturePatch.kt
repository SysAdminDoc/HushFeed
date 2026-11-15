/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.pip

import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.sessionPlaybackBridgePatch
import app.morphe.patches.tiktok.interaction.live.LIVE_PLAY_FRAGMENT
import app.morphe.patches.tiktok.interaction.live.LivePlayFragmentResumeFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.findMutableMethodOf
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/morphe/extension/tiktok/playback/PictureInPicture;"

/** The feed, and the pager a video opened from a profile, search or a sound plays in. */
internal val PICTURE_IN_PICTURE_WINDOWS = listOf(
    "Lcom/ss/android/ugc/aweme/main/MainActivity;",
    "Lcom/ss/android/ugc/aweme/detail/ui/DetailActivity;",
)

internal fun Method.isUserLeaveHint() =
    name == "onUserLeaveHint" && parameterTypes.isEmpty() && returnType == "V"

internal fun Method.isPictureInPictureModeChange() =
    name == "onPictureInPictureModeChanged" && returnType == "V" &&
        parameterTypes.map(CharSequence::toString) == listOf("Z", "Landroid/content/res/Configuration;")

/**
 * The first class from [start] up that declares an instance method [matches] with code, which is
 * the one Android runs for that window: TikTok's renamed base activity for onUserLeaveHint
 * (X.03s4 on 47.0.3, X.03uF on 47.1.3, X.03uJ on 47.1.4) and androidx's ComponentActivity for the
 * window change. Null when the walk leaves the app's own classes first.
 */
internal fun firstDeclaration(
    start: String,
    classDefByOrNull: (String) -> ClassDef?,
    matches: (Method) -> Boolean,
): Method? {
    val seen = mutableSetOf<String>()
    var type: String? = start
    while (type != null && seen.add(type)) {
        val classDef = classDefByOrNull(type) ?: return null
        classDef.methods.firstOrNull {
            it.implementation != null && !AccessFlags.STATIC.isSet(it.accessFlags) && matches(it)
        }?.let { return it }
        type = classDef.superclass
    }
    return null
}

/** Where each hook goes: one method per declaration, however many windows share it. */
internal data class PictureInPictureHooks(val leaveHints: List<Method>, val modeChanges: List<Method>)

internal fun pictureInPictureHooks(classDefByOrNull: (String) -> ClassDef?): PictureInPictureHooks {
    fun declarations(what: String, matches: (Method) -> Boolean) = PICTURE_IN_PICTURE_WINDOWS.map { window ->
        firstDeclaration(window, classDefByOrNull, matches)
            ?: throw PatchException("Picture-in-picture: nothing between $window and Activity declares $what")
    }.distinctBy { it.definingClass }
    return PictureInPictureHooks(
        declarations("onUserLeaveHint()", Method::isUserLeaveHint),
        declarations("onPictureInPictureModeChanged(boolean, Configuration)", Method::isPictureInPictureModeChange),
    )
}

/** Leaving hands the window to the extension first, while the player still reports it playing. */
internal fun MutableMethod.handLeaveHintToPictureInPicture() = addInstruction(
    0,
    "invoke-static/range { p0 .. p0 }, $EXTENSION->onUserLeaveHint(Landroid/app/Activity;)V",
)

/** The window opening or closing reaches the extension before TikTok's own listeners. */
internal fun MutableMethod.handWindowChangeToPictureInPicture() = addInstruction(
    0,
    "invoke-static/range { p0 .. p1 }, $EXTENSION->onModeChanged(Landroid/app/Activity;Z)V",
)

/**
 * Lets both windows open a picture-in-picture window. The feed activity already declares it for
 * TikTok's LIVE window on every supported build; the detail pager doesn't. Both already take size
 * changes themselves (configChanges covers screen size, smallest width and layout), so shrinking
 * into the window doesn't rebuild either.
 */
internal fun allowPictureInPicture(xml: Document) {
    val manifest = xml.documentElement
    val packageName = manifest.getAttribute("package")
    val wanted = PICTURE_IN_PICTURE_WINDOWS.map { it.removePrefix("L").removeSuffix(";").replace('/', '.') }.toSet()
    val found = mutableSetOf<String>()
    val activities = xml.getElementsByTagName("activity")
    for (index in 0 until activities.length) {
        val activity = activities.item(index) as Element
        val declared = activity.getAttribute("android:name")
        val name = if (declared.startsWith(".")) packageName + declared else declared
        if (name !in wanted) continue
        activity.setAttribute("android:supportsPictureInPicture", "true")
        found += name
    }
    val missing = wanted - found
    if (missing.isNotEmpty()) throw PatchException("Picture-in-picture: the manifest doesn't declare $missing")
}

/** The prompt TikTok shows a viewer to choose a small window, audio or nothing for LIVEs, real-named on 47.x. */
internal const val LIVE_WINDOW_PROMPT = "Lcom/bytedance/android/livesdk/pip/PipSwitchDialog;"

/** The reason the room page's check gives when TikTok's LIVE window switch is off, among others. */
internal const val LIVE_WINDOW_OFF_REASON = "pip not open or not visible"

/** The reason the room page's check gives just before it reads the switch. */
private const val LIVE_WINDOW_SWITCH_NEXT = "encrypted room"

/** TikTok's LIVE window switch (a stored setting), and the call that writes it. */
internal data class LiveWindowSwitch(val field: FieldReference, val write: MethodReference) {
    val fieldSmali get() = "${field.definingClass}->${field.name}:${field.type}"
    val writeSmali get() = "${write.definingClass}->${write.name}(Ljava/lang/Object;)V"
}

private fun FieldReference.key() = "$definingClass->$name:$type"

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * The switch TikTok's LIVE window waits on (#413). Leaving a LIVE opens TikTok's small window only
 * while it's on, and viewers are offered it in one prompt, after buying coins on the web. Picking
 * the window there writes it first after the prompt names the choice: a `sget-object` of the
 * setting and an `invoke-virtual` of its write with the value. The room page's own check, the
 * static call it makes after "encrypted room", has to read that same setting, or this isn't the
 * switch that opens the window.
 */
internal fun liveWindowSwitch(classDefByOrNull: (String) -> ClassDef?): LiveWindowSwitch {
    val prompt = classDefByOrNull(LIVE_WINDOW_PROMPT)
        ?: throw PatchException("Picture-in-picture: TikTok's LIVE window prompt $LIVE_WINDOW_PROMPT is gone")
    val click = prompt.methods.singleOrNull {
        it.name == "onClick" && it.implementation != null &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")
    } ?: throw PatchException("Picture-in-picture: $LIVE_WINDOW_PROMPT has no single onClick(View)")
    val instructions = click.implementation!!.instructions.toList()
    val choice = instructions.indexOfFirst { it.string() == "select_type" }
    if (choice < 0) throw PatchException("Picture-in-picture: the LIVE window prompt no longer names its choice")
    val switch = (choice + 1 until instructions.size - 1).firstNotNullOfOrNull { index ->
        settingWrite(instructions[index], instructions[index + 1])
    } ?: throw PatchException("Picture-in-picture: the LIVE window prompt writes no setting after its choice")

    val read = liveWindowCheckReads(classDefByOrNull)
    if (switch.field.key() !in read) {
        throw PatchException(
            "Picture-in-picture: the LIVE window prompt writes ${switch.field.key()}, and the room " +
                "page's check reads $read instead",
        )
    }
    return switch
}

/** A setting read into a register and written through it on the very next instruction. */
private fun settingWrite(get: Instruction, put: Instruction): LiveWindowSwitch? {
    if (get.opcode != Opcode.SGET_OBJECT || put.opcode != Opcode.INVOKE_VIRTUAL) return null
    val field = (get as ReferenceInstruction).reference as? FieldReference ?: return null
    val write = (put as ReferenceInstruction).reference as? MethodReference ?: return null
    if ((put as FiveRegisterInstruction).registerC != (get as OneRegisterInstruction).registerA) return null
    if (write.definingClass != field.type || write.returnType != "V") return null
    if (write.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;")) return null
    return LiveWindowSwitch(field, write)
}

/** The settings the room page's window check reads, through the static call after "encrypted room". */
internal fun liveWindowCheckReads(classDefByOrNull: (String) -> ClassDef?): Set<String> =
    liveWindowGate(classDefByOrNull).implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.SGET_OBJECT) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as? FieldReference)?.key()
    }.toSet()

/** TikTok's tablet and foldable service, real-named on 47.x. */
internal const val HOST_PAD = "Lcom/bytedance/android/livesdkapi/host/user/IHostPad;"

/** The static no-argument boolean call [instruction] makes, if it is one. */
private fun staticGate(instruction: Instruction): MethodReference? {
    if (instruction.opcode != Opcode.INVOKE_STATIC) return null
    val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return null
    return reference.takeIf { it.parameterTypes.isEmpty() && it.returnType == "Z" }
}

private fun ((String) -> ClassDef?).method(reference: MethodReference, what: String): Method =
    this(reference.definingClass)?.methods?.firstOrNull {
        it.name == reference.name && it.parameterTypes.isEmpty() && it.returnType == "Z" && it.implementation != null
    } ?: throw PatchException("Picture-in-picture: the LIVE window $what ${reference.definingClass}->${reference.name} is gone")

/** The tablet question inside the LIVE window's device check: the method and the move-result after it. */
internal data class LargeScreenCheck(val method: Method, val result: Int)

/**
 * Where TikTok's LIVE window asks whether this is a tablet or foldable (#413). The room page's
 * gate calls its device check first, and that check refuses the window when [HOST_PAD] answers
 * yes. TikTok's answer includes the largest window it has ever been shown in, so a phone that
 * once ran it in a big window counts as a tablet for good.
 */
internal fun liveWindowLargeScreenCheck(classDefByOrNull: (String) -> ClassDef?): LargeScreenCheck {
    val gate = liveWindowGate(classDefByOrNull)
    val first = gate.implementation!!.instructions.firstNotNullOfOrNull(::staticGate)
        ?: throw PatchException("Picture-in-picture: the LIVE window gate calls no device check")
    val check = classDefByOrNull.method(first, "device check")
    val instructions = check.implementation!!.instructions.toList()
    val asks = instructions.indices.filter { index ->
        val instruction = instructions[index]
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode == Opcode.INVOKE_INTERFACE && reference?.definingClass == HOST_PAD &&
            reference.parameterTypes.isEmpty() && reference.returnType == "Z"
    }
    val ask = asks.singleOrNull()
        ?: throw PatchException("Picture-in-picture: the LIVE window device check asks $HOST_PAD ${asks.size} times")
    if (instructions.getOrNull(ask + 1)?.opcode != Opcode.MOVE_RESULT) {
        throw PatchException("Picture-in-picture: the LIVE window device check drops its tablet answer")
    }
    return LargeScreenCheck(check, ask + 1)
}

/**
 * Hands the device check's tablet answer to the extension, which says no while Keep watching in
 * a small window is on, so the window opens on any screen Android allows it on.
 */
internal fun MutableMethod.keepLiveWindowOnLargeScreens(result: Int) {
    val register = (implementation!!.instructions[result] as OneRegisterInstruction).registerA
    addInstructions(
        result + 1,
        """
            invoke-static/range { v$register .. v$register }, $EXTENSION->liveWindowLargeScreen(I)I
            move-result v$register
        """,
    )
}

/** The room page's window check's static gate: the call it makes after "encrypted room". */
internal fun liveWindowGate(classDefByOrNull: (String) -> ClassDef?): Method {
    val page = classDefByOrNull(LIVE_PLAY_FRAGMENT)
        ?: throw PatchException("Picture-in-picture: the LIVE room page $LIVE_PLAY_FRAGMENT is gone")
    val check = page.methods.singleOrNull { method ->
        method.returnType == "Ljava/lang/String;" && method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.any { it.string() == LIVE_WINDOW_OFF_REASON } == true
    } ?: throw PatchException("Picture-in-picture: the LIVE room page has no single window check")
    val instructions = check.implementation!!.instructions.toList()
    val reason = instructions.indexOfFirst { it.string() == LIVE_WINDOW_SWITCH_NEXT }
    if (reason < 0) throw PatchException("Picture-in-picture: the LIVE window check no longer names \"$LIVE_WINDOW_SWITCH_NEXT\"")
    val call = instructions.drop(reason + 1).firstNotNullOfOrNull(::staticGate)
        ?: throw PatchException("Picture-in-picture: the LIVE window check calls nothing after \"$LIVE_WINDOW_SWITCH_NEXT\"")
    return classDefByOrNull.method(call, "check's gate")
}

/**
 * Sets TikTok's LIVE window switch to what the extension answers as a LIVE room comes up, before
 * TikTok's own onResume runs. Null leaves it as it is. Uses two locals, at index 0 where none is live.
 */
internal fun MutableMethod.keepLiveWindowSwitch(switch: LiveWindowSwitch) {
    requireLocals("Picture-in-picture", 2)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $EXTENSION->liveWindowSwitch()Ljava/lang/Boolean;
            move-result-object v0
            if-eqz v0, :morphe_live_window_kept
            sget-object v1, ${switch.fieldSmali}
            invoke-virtual {v1, v0}, ${switch.writeSmali}
            :morphe_live_window_kept
            nop
        """,
    )
}

private val pictureInPictureManifestPatch = patch {
    execute {
        document("AndroidManifest.xml").use { xml -> allowPictureInPicture(xml) }
    }
}

@Suppress("unused")
val pictureInPicturePatch = bytecodePatch(
    name = "Picture-in-picture",
    description = "Keeps the video playing in a small window when you leave TikTok, so you " +
        "can keep watching while you use other apps. Starts off. Turn it on in Hushfeed settings " +
        "> Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch, sessionPlaybackBridgePatch, pictureInPictureManifestPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val hooks = pictureInPictureHooks { classDefByOrNull(it) }
        // Both run first, before TikTok's own handling: leaving decides whether to open the
        // window while the player still reports the video playing, and the window change only
        // acts for a window the extension opened, so TikTok's LIVE window stays TikTok's.
        hooks.leaveHints.forEach { method ->
            mutableClassDefBy(method.definingClass).findMutableMethodOf(method).handLeaveHintToPictureInPicture()
        }
        hooks.modeChanges.forEach { method ->
            mutableClassDefBy(method.definingClass).findMutableMethodOf(method).handWindowChangeToPictureInPicture()
        }
        // TikTok's own LIVE window opens only with its own switch on, which viewers are rarely
        // offered (#413). Each LIVE room page sets it from this one as the room comes up.
        LivePlayFragmentResumeFingerprint.method.keepLiveWindowSwitch(liveWindowSwitch { classDefByOrNull(it) })
        // Tablets, foldables and phones TikTok has filed as one get refused the window too.
        val largeScreen = liveWindowLargeScreenCheck { classDefByOrNull(it) }
        mutableClassDefBy(largeScreen.method.definingClass).findMutableMethodOf(largeScreen.method)
            .keepLiveWindowOnLargeScreens(largeScreen.result)
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePictureInPicture()V",
        )
    }
}
