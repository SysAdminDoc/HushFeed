/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videofit

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.instanceOf
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val SCALE_TYPE = "Landroid/widget/ImageView\$ScaleType;"
private const val SLIDES = "Lapp/morphe/extension/tiktok/interaction/PhotoSlideFit;"
private const val PATCH = "Fit video to the screen"
private const val VIEW = "Landroid/view/View;"
private const val CONSTRAINT_LAYOUT = "Landroidx/constraintlayout/widget/ConstraintLayout;"

/**
 * The photo post's choice of how a slide sits in its view. The photo feed cell asks one static
 * helper for each slide: the slide's width over its height and a layout variant go in, and a
 * scale type comes out, crop to cover for a slide taller than about 9:16 and fit for the rest.
 * The helper lives in an obfuscated utility class, so it is found by its shape, which is the
 * only static method in the app that takes a float, answers a scale type and picks between
 * exactly those two.
 */
internal object SlideScaleTypeFingerprint : Fingerprint(
    returnType = SCALE_TYPE,
    parameters = listOf("F", "L"),
    // One of the two values the custom check needs, so the patcher's index finds the readers.
    filters = listOf(fieldAccess(definingClass = SCALE_TYPE, name = "FIT_CENTER", type = SCALE_TYPE)),
    custom = { method, _ -> method.isSlideScaleChoice() },
)

/**
 * The same question asked again once the cell goes full screen, where a slide that is taller
 * than its container is cropped to cover it. An instance method on the cell's layout helper that
 * takes the slide's aspect and answers whether to crop: it reads the display's width and the
 * container's height and holds a ConstraintLayout, and nothing else in the app has that shape.
 */
internal object SlideContainerCropFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("F"),
    // The check the custom one ends on, so the patcher's index finds the methods holding one.
    filters = listOf(instanceOf(CONSTRAINT_LAYOUT)),
    custom = { method, _ -> method.isSlideContainerCrop() },
)

/**
 * The photo display mode's answer to whether it shows every slide whole. TikTok puts each photo
 * post in one of a handful of modes, kept as an enum, and lets the mode's layout overrule the
 * scale the chooser above picked: most modes crop a slide taller than 9:16 to the full screen
 * cell, and only one answers yes here, which puts every slide on the fit scale. The getter keeps
 * its name in the app, so it is found by that, on an enum, reading one of the enum's own values.
 */
internal object ShowsWholeImageFingerprint : Fingerprint(
    name = "getShowsWholeImage",
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef -> classDef.superclass == "Ljava/lang/Enum;" && method.readsOwnValue() },
)

/** Whether a method reads one of its own class's static values, as an enum's getter does. */
internal fun Method.readsOwnValue(): Boolean =
    implementation?.instructions?.any {
        it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.definingClass == definingClass && field.type == definingClass
        } == true
    } == true

internal fun Method.isSlideScaleChoice(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags)) return false
    if (parameterTypes.size != 2 || parameterTypes[0] != "F") return false
    val chosen = implementation?.instructions
        ?.filter { it.opcode == Opcode.SGET_OBJECT }
        ?.mapNotNull { it.getReference<FieldReference>() }
        ?.filter { it.definingClass == SCALE_TYPE && it.type == SCALE_TYPE }
        ?.map { it.name }
        ?.toSet() ?: return false
    return chosen == setOf("CENTER_CROP", "FIT_CENTER")
}

internal fun Method.isSlideContainerCrop(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags)) return false
    if (parameterTypes.size != 1 || parameterTypes[0] != "F") return false
    val body = implementation ?: return false
    val calls = body.instructions.mapNotNull { it.getReference<MethodReference>() }.map { it.name }.toSet()
    if ("getDisplayMetrics" !in calls || "getHeight" !in calls) return false
    return body.instructions.any {
        it.opcode == Opcode.INSTANCE_OF && it.getReference<TypeReference>()?.type == CONSTRAINT_LAYOUT
    }
}

/**
 * The view field the crop question reads the container's height from: the one object field of
 * type View the method reads off its own instance. Null when there is not exactly one, which the
 * patch refuses rather than guess.
 */
internal fun Method.containerViewField(): FieldReference? {
    val reads = implementation?.instructions
        ?.filter { it.opcode == Opcode.IGET_OBJECT }
        ?.mapNotNull { it.getReference<FieldReference>() }
        ?.filter { it.definingClass == definingClass && it.type == VIEW }
        ?.distinctBy { it.name }
        ?: return null
    return reads.singleOrNull()
}

/**
 * Hooks the three places TikTok decides to crop a photo slide to cover its cell (#188), so a
 * slide it would cut a lot off on a squarer screen is shown whole instead. All three are required
 * on a build the patch is declared for, where a miss would leave photo posts cropped without a
 * word, and left out on any other, which keeps the video fit. All are found and checked before
 * any is written. Takes the version so a test can stand in for a build that isn't declared.
 */
internal fun hookPhotoSlides(versionName: String): Boolean {
    val declaredBuild = versionName in declaredVersions()
    val scale = if (declaredBuild) SlideScaleTypeFingerprint.method else SlideScaleTypeFingerprint.methodOrNull
    val crop = if (declaredBuild) SlideContainerCropFingerprint.method else SlideContainerCropFingerprint.methodOrNull
    val whole = if (declaredBuild) ShowsWholeImageFingerprint.method else ShowsWholeImageFingerprint.methodOrNull
    if (scale == null || crop == null || whole == null) return false

    scale.requireLocals(PATCH, 1)
    whole.requireLocals(PATCH, 1)
    // The crop question reads its container off its own instance. The injected read keeps the
    // container in one local and the aspect in a second, so it needs two locals, and a frame
    // that lets the invoke below name the second one.
    val container = checkNotNull(crop.containerViewField()) {
        "$PATCH: the slide crop question has no single View field to read the container from."
    }
    crop.requireLocals(PATCH, 2)
    check(crop.implementation!!.registerCount <= 16) {
        "$PATCH: ${crop.definingClass}->${crop.name} holds ${crop.implementation!!.registerCount} " +
            "registers, and the invoke that hands the container over cannot name one past v15."
    }

    // A slide TikTok would crop to cover its cell gets the fit scale instead when the extension
    // says the screen is square enough for that crop to cut real picture.
    scale.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p0 }, $SLIDES->fitOnScreen(F)I
            move-result v0
            if-eqz v0, :morphe_slide_stock
            sget-object v0, $SCALE_TYPE->FIT_CENTER:$SCALE_TYPE
            return-object v0
        """,
        ExternalLabel("morphe_slide_stock", scale.getInstruction(0)),
    )
    crop.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${container.definingClass}->${container.name}:${container.type}
            move/from16 v1, p1
            invoke-static { v0, v1 }, $SLIDES->fitInContainer(Landroid/view/View;F)I
            move-result v0
            if-eqz v0, :morphe_slide_crop
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("morphe_slide_crop", crop.getInstruction(0)),
    )
    // The display mode's layout overrules the two answers above, so on a squarish screen every
    // mode says it shows slides whole, which is the one that keeps the fit scale.
    whole.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SLIDES->showsWholeImage()I
            move-result v0
            if-eqz v0, :morphe_slide_mode
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("morphe_slide_mode", whole.getInstruction(0)),
    )
    return true
}
