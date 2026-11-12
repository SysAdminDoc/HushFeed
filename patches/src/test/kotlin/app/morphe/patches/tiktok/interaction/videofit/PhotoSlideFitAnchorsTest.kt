/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videofit

import app.morphe.patcher.patch.patch
import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Fit the video to the screen hooks for photo slideshows (#188), held to each declared
 * build: exactly one scale-type chooser, one container crop question and one display mode answer,
 * the chooser and the mode answer with a local register to spare, the crop question with two, a
 * single container View field to read, and a frame small enough for the invoke that hands the
 * container over.
 */
class PhotoSlideFitAnchorsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `each declared build has one slide scale chooser, one container crop question and one mode answer`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val scale = mutableListOf<Method>()
            val crop = mutableListOf<Method>()
            val whole = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (SlideScaleTypeFingerprint.takes(method, classDef)) scale += method
                        if (SlideContainerCropFingerprint.takes(method, classDef)) crop += method
                        if (ShowsWholeImageFingerprint.takes(method, classDef)) whole += method
                    }
                }
            }
            assertEquals("$version: scale choosers ${scale.map { it.definingClass }}", 1, scale.size)
            assertEquals("$version: crop questions ${crop.map { it.definingClass }}", 1, crop.size)
            assertEquals("$version: mode answers ${whole.map { it.definingClass }}", 1, whole.size)
            // Instance, no parameters: one parameter register, so the rest are locals.
            assertTrue("$version: the mode answer has no local", whole.single().implementation!!.registerCount - 1 >= 1)

            val chooser = scale.single()
            // Static (aspect, layout variant): two parameter registers, so the rest are locals.
            assertTrue("$version: the chooser has no local", chooser.implementation!!.registerCount - 2 >= 1)

            val question = crop.single()
            val registers = question.implementation!!.registerCount
            // Instance (this, aspect): two parameter registers.
            assertTrue("$version: the crop question has $registers registers, fewer than two locals", registers - 2 >= 2)
            assertTrue("$version: the invoke cannot name registers past v15", registers <= 16)
            assertNotNull("$version: no single container View field", question.containerViewField())
            assertEquals("Z", question.returnType)
        }
    }

    /**
     * The hooks go in on each declared build and assemble: the chooser asks the extension for the
     * aspect it was given and leaves with the fit scale, the crop question reads its container
     * and asks, and each falls through to TikTok's own first instruction on a no.
     */
    @Test
    fun `the hooks assemble on each declared build and ask the extension first`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = patch(name = "photo slide probe") {
                execute {
                    assertTrue("$version: the hooks went in", hookPhotoSlides(version))
                    val scale = SlideScaleTypeFingerprint.method.implementation!!.instructions.toList()
                    assertEquals(
                        "$version: the chooser's guard",
                        listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT),
                        scale.take(5).map { it.opcode },
                    )
                    val ask = scale[0].getReference<MethodReference>()!!
                    assertEquals("Lapp/morphe/extension/tiktok/interaction/PhotoSlideFit;->fitOnScreen(F)I",
                        "${ask.definingClass}->${ask.name}(${ask.parameterTypes.joinToString("")})${ask.returnType}")

                    val crop = SlideContainerCropFingerprint.method.implementation!!.instructions.toList()
                    assertEquals(
                        "$version: the crop question's guard",
                        listOf(Opcode.IGET_OBJECT, Opcode.MOVE_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                        crop.take(7).map { it.opcode },
                    )
                    val container = crop[2].getReference<MethodReference>()!!
                    assertEquals("Lapp/morphe/extension/tiktok/interaction/PhotoSlideFit;->fitInContainer(Landroid/view/View;F)I",
                        "${container.definingClass}->${container.name}(${container.parameterTypes.joinToString("")})${container.returnType}")

                    val whole = ShowsWholeImageFingerprint.method.implementation!!.instructions.toList()
                    assertEquals(
                        "$version: the mode answer's guard",
                        listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                        whole.take(5).map { it.opcode },
                    )
                    val mode = whole[0].getReference<MethodReference>()!!
                    assertEquals("Lapp/morphe/extension/tiktok/interaction/PhotoSlideFit;->showsWholeImage()I",
                        "${mode.definingClass}->${mode.name}(${mode.parameterTypes.joinToString("")})${mode.returnType}")
                    checked = true
                }
            }
            SlideScaleTypeFingerprint.clearMatch()
            SlideContainerCropFingerprint.clearMatch()
            ShowsWholeImageFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `only a static two way scale choice is taken as the chooser`() {
        assertTrue(chooser(static = true, picks = listOf("CENTER_CROP", "FIT_CENTER")).isSlideScaleChoice())
        assertFalse("not static", chooser(static = false, picks = listOf("CENTER_CROP", "FIT_CENTER")).isSlideScaleChoice())
        assertFalse("picks a third", chooser(static = true, picks = listOf("CENTER_CROP", "FIT_CENTER", "FIT_XY")).isSlideScaleChoice())
        assertFalse("picks one", chooser(static = true, picks = listOf("FIT_CENTER")).isSlideScaleChoice())
    }

    @Test
    fun `only an enum getter reading its own value is taken as the mode answer`() {
        val mode = "LX/0Mode;"
        val getter = modeGetter(mode, reads = mode)
        assertTrue(ShowsWholeImageFingerprint.takes(getter, owner(mode, "Ljava/lang/Enum;", getter)))
        assertFalse("not an enum", ShowsWholeImageFingerprint.takes(getter, owner(mode, "Ljava/lang/Object;", getter)))
        val elsewhere = modeGetter(mode, reads = "LX/0Other;")
        assertFalse("reads another class's value",
            ShowsWholeImageFingerprint.takes(elsewhere, owner(mode, "Ljava/lang/Enum;", elsewhere)))
    }

    /** The display mode getter cut down to what is matched: it reads one of its enum's values. */
    private fun modeGetter(type: String, reads: String): Method = ImmutableMethod(
        type, "getShowsWholeImage", emptyList(), "Z", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(
            2,
            listOf(
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(reads, "V5", reads)),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
            null, null,
        ),
    )

    private fun owner(type: String, superclass: String, method: Method) = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, superclass, emptyList(), null, emptySet(), emptyList(), listOf(method),
    )

    /** A chooser cut down to its scale type reads: one `sget-object` per pick, one returned. */
    private fun chooser(static: Boolean, picks: List<String>): Method {
        val type = SCALE_TYPE
        val body = picks.map {
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(type, it, type))
        } + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        return ImmutableMethod(
            "LX/0Util;", "LIZ",
            listOf("F", "I").map { ImmutableMethodParameter(it, null, null) }, type,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(3, body, null, null),
        )
    }
}
