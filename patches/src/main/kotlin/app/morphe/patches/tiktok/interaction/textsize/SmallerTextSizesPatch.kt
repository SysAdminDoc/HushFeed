/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.textsize

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Smaller text sizes"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/font/SmallerTextSizes;"
private const val TUX_ICON_VIEW = "Lcom/bytedance/tux/icon/TuxIconView;"

@Suppress("unused")
val smallerTextSizesPatch = bytecodePatch(
    name = PATCH,
    description = "Adds three stops below the smallest size on TikTok's own Text size slider, " +
        "for smaller text and more on screen. Starts off. Turn it on in Hushfeed settings > " +
        "App, then pick a stop in Settings and privacy > Accessibility > Text size.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSmallerTextSizes()V",
        )

        // TikTok's text size manager: one int mode field and two methods that turn it into the
        // interface's factor. With an extra stop picked, both answer that stop's factor.
        val manager = TextSizeModeLoadFingerprint.classDef
        val factors = manager.methods.filter { it.isModeFactor(manager.type) }
        if (factors.size != 2) {
            throw PatchException("$PATCH: expected 2 text size factor methods, found ${factors.size}.")
        }
        val modeFields = factors.map { it.modeField(manager.type) }.distinct()
        val mode = modeFields.singleOrNull()
            ?: throw PatchException("$PATCH: the factor methods read different mode fields: $modeFields.")
        factors.forEach { factor ->
            factor.requireLocals(PATCH, 1)
            factor.addInstructionsWithLabels(
                0,
                """
                    sget v0, $mode
                    invoke-static {v0}, $EXTENSION->useExtraStop(I)Z
                    move-result v0
                    if-eqz v0, :morphe_keep_text_size_factor
                    invoke-static {}, $EXTENSION->extraStopFactor()F
                    move-result v0
                    return v0
                """,
                ExternalLabel("morphe_keep_text_size_factor", factor.getInstruction(0)),
            )
        }

        // The slider works in positions. Its highest position is set once, when it is built.
        val slider = TextSizePageViewCreatedFingerprint.method.instructionsList()
            .mapNotNull { it.getReference<MethodReference>() }
            .firstOrNull { it.name == "setOnSliderListener" }
            ?.definingClass
            ?: throw PatchException("$PATCH: the Text size page wires no slider.")
        val sliderClass = mutableClassDefBy(slider)
        val maxField = sliderClass.methods
            .firstOrNull { it.name == "getValueTo" && it.parameterTypes.isEmpty() && it.returnType == "I" }
            ?.instructionsList()
            ?.firstNotNullOfOrNull { ins ->
                ins.takeIf { it.opcode == Opcode.IGET }?.getReference<FieldReference>()?.name
            }
            ?: throw PatchException("$PATCH: the slider $slider has no getValueTo field.")
        sliderClass.methods.filter { it.name == "<init>" }.forEach { init ->
            init.mapIntField(Opcode.IPUT, maxField, after = false, mapper = "sliderMax")
        }

        // The page opens on the saved mode; it becomes a position.
        TextSizePageCreateFingerprint.method.mapIntField(
            Opcode.SGET, mode, after = true, mapper = "modeToPosition",
        )

        // The page's preview of a position, the (I)V method that styles its four sample icons.
        val preview = TextSizePageCreateFingerprint.classDef.methods.singleOrNull { method ->
            method.parameterTypes.map { it.toString() } == listOf("I") && method.returnType == "V" &&
                method.instructionsList().any { ins ->
                    ins.opcode == Opcode.NEW_ARRAY && ins.getReference<TypeReference>()
                        ?.type == "[$TUX_ICON_VIEW"
                }
        } ?: throw PatchException("$PATCH: the Text size page's preview method was not found.")
        preview.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $EXTENSION->positionToMode(I)I
                move-result p1
            """,
        )

        // Confirming saves the position as TikTok's mode. The value goes through the extension,
        // which keeps the extra stop and hands TikTok its own mode, 0 for every extra stop.
        TextSizeConfirmFingerprint.method.apply {
            val instructions = instructionsList()
            val store = instructions.indices.firstOrNull { index ->
                val ins = instructions[index]
                ins.getReference<MethodReference>()?.let {
                    it.definingClass == "Lcom/bytedance/keva/Keva;" && it.name == "storeInt"
                } == true && instructions.subList(maxOf(0, index - 3), index).any { previous ->
                    previous.getReference<StringReference>()?.string == TEXT_SIZE_MODE_KEY
                }
            } ?: throw PatchException("$PATCH: the confirm callback stores no text size mode.")
            val value = (instructions[store] as FiveRegisterInstruction).registerE
            addInstructions(
                store,
                """
                    invoke-static/range {v$value .. v$value}, $EXTENSION->commitPosition(I)I
                    move-result v$value
                """,
            )
        }
    }
}

private fun Method.instructionsList(): List<Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun Method.isModeFactor(owner: String): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "F" && parameterTypes.isEmpty() &&
        instructionsList().any { ins ->
            ins.opcode == Opcode.SGET && ins.getReference<FieldReference>()?.let {
                it.definingClass == owner && it.type == "I"
            } == true
        }

private fun Method.modeField(owner: String): String =
    instructionsList().firstNotNullOf { ins ->
        ins.takeIf { it.opcode == Opcode.SGET }?.getReference<FieldReference>()
            ?.takeIf { it.definingClass == owner && it.type == "I" }
            ?.let { "${it.definingClass}->${it.name}:${it.type}" }
    }

/**
 * Passes an int through the extension's [mapper] right after a read, or right before a write, of
 * the first access to [field], given as a bare name or as `Lowner;->name:type`.
 */
private fun MutableMethod.mapIntField(opcode: Opcode, field: String, after: Boolean, mapper: String) {
    val instructions = instructionsList()
    val index = instructions.indexOfFirst { ins ->
        ins.opcode == opcode && ins.getReference<FieldReference>()?.let {
            it.name == field || "${it.definingClass}->${it.name}:${it.type}" == field
        } == true
    }
    if (index < 0) throw PatchException("$PATCH: $name never touches $field.")
    val register = when (val ins = instructions[index]) {
        is TwoRegisterInstruction -> ins.registerA
        is OneRegisterInstruction -> ins.registerA
        else -> throw PatchException("$PATCH: unexpected instruction at $name[$index].")
    }
    addInstructions(
        if (after) index + 1 else index,
        """
            invoke-static/range {v$register .. v$register}, $EXTENSION->$mapper(I)I
            move-result v$register
        """,
    )
}
