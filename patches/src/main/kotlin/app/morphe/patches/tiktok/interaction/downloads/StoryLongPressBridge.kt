/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.extendsClass
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val ANDROID_VIEW = "Landroid/view/View;"
private const val TOUCH_LISTENER = "Landroid/view/View\$OnTouchListener;"
private const val RUNNABLE = "Ljava/lang/Runnable;"

/**
 * The story press-and-hold timer, found by what it is rather than what it is called.
 *
 * <p>It was `LX/0UXs;` with its view `LX/049A;` and the view field `LL`, all three written into
 * this patch along with the exact registers its body happened to use. It is `LX/0QpH;` on 46.7.3
 * and `LX/0Qep;` on 46.8.3.
 *
 * <p>The shape does not move: a Runnable holding one field, that field a View subclass which
 * holds a field back of the Runnable's own type, built from that view, with one plain `()V`
 * method beside `run`. Two classes in the app have that shape and the other one's view is a
 * drawing surface, full of Paints and RectFs; the story view is the one that holds an
 * `OnTouchListener`, which is a platform name and cannot be renamed.
 */
internal fun resolveStoryLongPressTimer(): MutableMethod {
    val found = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (RUNNABLE !in classDef.interfaces) return@classDefForEach
        val field = classDef.fields.singleOrNull() ?: return@classDefForEach
        val view = classDefByOrNull(field.type) ?: return@classDefForEach
        // The view's own fields before its superclass walk, which looks up every ancestor of
        // every one-field Runnable's field.
        if (view.fields.none { it.type == TOUCH_LISTENER }) return@classDefForEach
        if (view.fields.none { it.type == classDef.type }) return@classDefForEach
        if (!extendsAndroidView(view)) return@classDefForEach
        if (classDef.methods.none {
                it.name == "<init>" && it.parameterTypes.toList() == listOf(field.type)
            }
        ) {
            return@classDefForEach
        }
        val timer = classDef.methods.singleOrNull {
            it.name != "<init>" && it.name != "run" && it.returnType == "V" &&
                it.parameterTypes.none() && AccessFlags.STATIC.value and it.accessFlags == 0
        } ?: return@classDefForEach
        found += classDef to timer
    }
    if (found.size != 1) {
        throw PatchException(
            "Advanced downloads: expected one story press-and-hold timer, a Runnable holding the " +
                "touch view that holds it back, found ${found.size}.",
        )
    }
    val (classDef, timer) = found.single()
    return mutableClassDefBy(classDef).findMutableMethodOf(timer)
}

/** Whether the class reaches `android.view.View` through what it extends. */
private fun extendsAndroidView(classDef: ClassDef): Boolean =
    extendsClass(classDef.type, ANDROID_VIEW)

/**
 * The timer's body is TikTok's pause and then its hold panel. The pause comes first: with the
 * monitor the view holds, it marks the view as holding, so the lift plays the story on, and calls
 * the monitor's pause. Then, monitor or not, the view is read again and the panel opens. The save
 * goes where both meet, so a hold still pauses the story as it always does and the save takes the
 * panel's place. It used to go before the pause, and a story kept playing under the finger and
 * under the story checklist it opened (#393).
 *
 * @return TikTok's pause call, an interface method beside which sits the release.
 */
internal fun MutableMethod.interceptStoryLongPress(): MethodReference {
    check(parameterTypes.none() && returnType == "V" &&
        AccessFlags.STATIC.value and accessFlags == 0) {
        "Advanced downloads: unexpected native story timer signature."
    }
    // The view read and the read that follows it, rather than registers 1, 3 and 0. The body is
    // the same two instructions on 46.2.3, 46.7.3 and 46.8.3, and the registers were the same
    // too, which is exactly why writing them down looked safe.
    val viewField = getInstruction<ReferenceInstruction>(0).reference as? FieldReference
    val monitorRead = getInstruction<TwoRegisterInstruction>(1)
    check(getInstruction(0).opcode == Opcode.IGET_OBJECT && viewField != null &&
        viewField.definingClass == definingClass && monitorRead.opcode == Opcode.IGET_OBJECT &&
        monitorRead.registerB == getInstruction<TwoRegisterInstruction>(0).registerA) {
        "Advanced downloads: the native story timer no longer reads its view before its monitor."
    }
    val instructions = implementation!!.instructions
    val monitorCheck = instructions.indexOfFirst { it.opcode == Opcode.IF_EQZ }
    val pauseCall = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_INTERFACE }
    val pause = (instructions.getOrNull(pauseCall) as? ReferenceInstruction)?.reference as? MethodReference
    check(monitorCheck in 2 until pauseCall &&
        getInstruction<OneRegisterInstruction>(monitorCheck).registerA == monitorRead.registerA &&
        pause != null && pause.parameterTypes.isEmpty() && pause.returnType == "V") {
        "Advanced downloads: the native story timer no longer pauses the story through its monitor first."
    }
    // Where a timer with no monitor goes, past the pause: the panel half, which reads the view
    // again and then the panel's listener from it.
    val meet = (instructions[monitorCheck] as BuilderOffsetInstruction).target.location.index
    val viewRead = getInstruction<TwoRegisterInstruction>(meet)
    val listenerRead = getInstruction<TwoRegisterInstruction>(meet + 1)
    check(meet > pauseCall && viewRead.opcode == Opcode.IGET_OBJECT &&
        (getInstruction<ReferenceInstruction>(meet).reference as? FieldReference)?.let {
            it.definingClass == viewField.definingClass && it.name == viewField.name
        } == true && listenerRead.opcode == Opcode.IGET_OBJECT &&
        listenerRead.registerB == viewRead.registerA) {
        "Advanced downloads: the native story timer no longer reads its view again after the pause."
    }
    val view = viewRead.registerA
    // The register the next instruction is about to write is free to borrow for the answer.
    val scratch = listenerRead.registerA
    check(view != scratch && view < 16 && scratch < 16) {
        "Advanced downloads: the story timer's view and scratch registers do not fit the hook."
    }
    addInstructionsWithLabels(meet + 1, """
        invoke-static { v$view }, Lapp/morphe/extension/tiktok/download/StoryDownloads;->onNativeLongPress(Landroid/view/View;)Z
        move-result v$scratch
        if-eqz v$scratch, :native_story_hold
        return-void
    """, ExternalLabel("native_story_hold", getInstruction(meet + 1)))
    return pause!!
}

/**
 * The story hold's release, held back while the story checklist is up (#393). The timer's pause
 * call names the hold's listener, an interface of two plain methods: the pause, and the release
 * the view sends as the finger lifts. Every class answering to it gets the release hook.
 */
internal fun holdStoryReleaseForPicker(pause: MethodReference) {
    val listener = classDefByOrNull(pause.definingClass)
        ?: throw PatchException("Advanced downloads: the story hold's listener ${pause.definingClass} is missing.")
    val release = listener.methods.filter {
        it.name != pause.name && it.returnType == "V" && it.parameterTypes.isEmpty()
    }
    if (AccessFlags.INTERFACE.value and listener.accessFlags == 0 || release.size != 1) {
        throw PatchException(
            "Advanced downloads: expected the story hold's listener ${listener.type} to be an interface " +
                "with one release beside its pause ${pause.name}, found ${release.map { it.name }}.",
        )
    }
    val releaseName = release.single().name
    val monitors = mutableListOf<ClassDef>()
    classDefForEach { classDef -> if (listener.type in classDef.interfaces) monitors += classDef }
    if (monitors.isEmpty()) {
        throw PatchException("Advanced downloads: nothing answers to the story hold's listener ${listener.type}.")
    }
    for (monitor in monitors) {
        val method = monitor.methods.singleOrNull {
            it.name == releaseName && it.returnType == "V" && it.parameterTypes.isEmpty() && it.implementation != null
        } ?: throw PatchException("Advanced downloads: ${monitor.type} has no $releaseName() of its own.")
        mutableClassDefBy(monitor).findMutableMethodOf(method).holdReleaseForPicker()
    }
}

/** The release's first instructions: while the checklist is up, it returns before playing anything. */
internal fun MutableMethod.holdReleaseForPicker() {
    val registers = implementation!!.registerCount
    // An instance method without parameters: its last register is this, the ones below it free.
    val self = registers - 1
    check(parameterTypes.none() && AccessFlags.STATIC.value and accessFlags == 0 && self in 1..15) {
        "Advanced downloads: the story hold release $definingClass->$name has no room for the picker hook."
    }
    addInstructionsWithLabels(0, """
        const-string v0, "$name"
        invoke-static { v$self, v0 }, Lapp/morphe/extension/tiktok/download/StoryPicker;->holdRelease(Ljava/lang/Object;Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :story_release
        return-void
    """, ExternalLabel("story_release", getInstruction(0)))
}
