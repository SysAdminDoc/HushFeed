package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact message tap, native empty preview service and encrypted image framing on declared APKs. */
class DmStickerPreviewFixturesTest {
    @Test
    fun `the received sticker tap ends in an empty native service and only passes its view and template`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            fun classes(wanted: (ClassDef) -> Boolean) = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.filter(wanted).toList()
            val handler = classes { it.type == DM_STICKER_HANDLER }.single()
            val anchor = dmStickerPreviewAnchor(handler)
            val native = anchor.method.implementation!!.instructions.toList()
            val call = native[anchor.previewCall] as FiveRegisterInstruction
            assertEquals(Opcode.INVOKE_INTERFACE, native[anchor.previewCall].opcode)
            assertEquals(5, call.registerCount)
            assertEquals(Opcode.RETURN_VOID, native[anchor.previewCall + 1].opcode)

            val implementations = classes { anchor.service.definingClass in it.interfaces }
            assertEquals(2, implementations.size)
            implementations.forEach { definition ->
                val service = definition.methods.single { it.name == anchor.service.name && it.parameterTypes == anchor.service.parameterTypes }
                assertTrue("${definition.type} now owns a native preview", isEmptyDmPreview(service))
            }
            val patched = MutableMethod(anchor.method)
            patched.addDmStickerPreviewHook(anchor.previewCall)
            val injected = patched.implementation!!.instructions.toList()[anchor.previewCall + 3].getReference<MethodReference>()!!
            assertEquals("openDmStickerPreview", injected.name)
            assertEquals(listOf("Landroid/view/View;", "Ljava/lang/Object;"), injected.parameterTypes.map(CharSequence::toString))

            val slot = classes { it.type == SLOT }.single()
            val taps = slot.methods.filter { method -> method.references().any { it.definingClass == DM_STICKER_ABILITY && it.name == anchor.method.name } }
            assertEquals(1, taps.size)
            val code = taps.single().implementation!!.instructions.toList()
            var previews = 0
            code.forEachIndexed { index, instruction ->
                val reference = instruction.getReference<MethodReference>()
                if (reference?.definingClass != DM_STICKER_ABILITY || reference.name != anchor.method.name) return@forEachIndexed
                previews++
                val viewRegister = (instruction as FiveRegisterInstruction).registerD
                val viewRead = (index - 1 downTo 1).first { previous ->
                    code[previous].opcode == Opcode.MOVE_RESULT_OBJECT && (code[previous] as OneRegisterInstruction).registerA == viewRegister
                }
                assertEquals("only the sticker bubble is passed to the tap", ROUNDING_VIEW,
                    code[viewRead - 1].getReference<MethodReference>()!!.returnType)
            }
            assertTrue(previews > 0)
            // The owning view explicitly installs OnClickListener, not a long press or composer action.
            val created = slot.methods.single { it.name == "onViewCreated" }
            assertTrue(created.references().any { reference ->
                reference.parameterTypes.map(CharSequence::toString) == listOf(ROUNDING_VIEW, "Landroid/view/View\$OnClickListener;")
            })
        }
    }

    @Test
    fun `image components keep raw mirrors and optional keys and native decoding authenticates a 12 byte nonce`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val imageTypes = setOf(DM_STICKER_TEMPLATE, IMAGE)
            val found = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { definition -> definition.type in imageTypes || definition.methods.any(::decryptSignature) }.toList()
            val image = found.single { it.type == IMAGE }
            assertEquals("Ljava/util/List;", image.fields.single { it.name == "urls" }.type)
            assertEquals("Ljava/lang/String;", image.fields.single { it.name == "decryptKey" }.type)
            assertEquals(IMAGE, found.single { it.type == DM_STICKER_TEMPLATE }.fields.single { it.name == "imageComponent" }.type)
            val decryptors = found.flatMap { owner -> owner.methods.filter(::decryptSignature).map { owner to it } }
                .filter { (_, method) -> method.strings().contains("AES/GCM/NoPadding") }
            assertEquals(1, decryptors.size)
            val (owner, decryptor) = decryptors.single()
            assertTrue(owner.methods.any { it.strings().contains("%%secretKey=") })
            val code = decryptor.implementation!!.instructions.toList()
            assertTrue(decryptor.strings().contains("AES"))
            val calls = decryptor.references()
            assertTrue(calls.any { it.definingClass == "Ljavax/crypto/Cipher;" && it.name == "doFinal" && it.returnType == "[B" })
            assertTrue(calls.none { it.definingClass == "Ljavax/crypto/Cipher;" && it.name == "updateAAD" })
            val slices = code.withIndex().filter { (_, instruction) ->
                instruction.getReference<MethodReference>()?.let { it.definingClass == "Ljava/util/Arrays;" && it.name == "copyOfRange" } == true
            }
            assertEquals(2, slices.size)
            val first = slices[0].value as FiveRegisterInstruction
            val second = slices[1].value as FiveRegisterInstruction
            assertEquals(first.registerD, second.registerE)
            val nonceSize = code.take(slices[0].index).lastOrNull {
                it is NarrowLiteralInstruction && it is OneRegisterInstruction && it.registerA == first.registerD
            }
            assertNotNull(nonceSize)
            assertEquals(12, (nonceSize as NarrowLiteralInstruction).narrowLiteral)
            assertTrue(calls.any { it.definingClass == "Ljavax/crypto/spec/IvParameterSpec;" && it.name == "<init>" })
            assertTrue(calls.any { it.definingClass == "Ljavax/crypto/spec/SecretKeySpec;" && it.name == "<init>" })
        }
    }

    /**
     * #470: tapping a sticker made from a video opened no save box. The chat bubble sends those
     * taps through their own method, which ends in the same preview service; the save box goes
     * after that call, reads the bubble's view and template, and TikTok's early exits skip it.
     */
    @Test
    fun `a sticker made from a video opens the save box after TikTok's own preview call`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val all = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val byType = all.associateBy { it.type }
            val handler = byType.getValue(DM_STICKER_HANDLER)
            val anchor = dmStickerPreviewAnchor(handler)
            val video = dmStickerVideoTap(handler, anchor.service) { byType[it] }
            assertNotNull(apk.name, video)
            video!!
            assertEquals(apk.name, "Landroid/view/View;", video.view.type)
            assertEquals(apk.name, DM_STICKER_TEMPLATE, video.template.type)

            // The bubble's own tap makes that click from the rounded sticker view and hands it over.
            val maker = handler.methods.single { method ->
                method.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == video.click } == true
            }
            assertTrue(apk.name, maker.references().any { it.name == video.method.name && it.definingClass == DM_STICKER_HANDLER })
            assertEquals(apk.name, listOf("Landroid/view/View;", DM_STICKER_TEMPLATE),
                maker.parameterTypes.map(CharSequence::toString).let { listOf(it.first(), it.last()) })
            val slot = byType.getValue(SLOT)
            val tap = slot.methods.single { method -> method.references().any { it.definingClass == DM_STICKER_ABILITY && it.name == maker.name } }
            val code = tap.implementation!!.instructions.toList()
            code.forEachIndexed { index, instruction ->
                val reference = instruction.getReference<MethodReference>()
                if (reference?.definingClass != DM_STICKER_ABILITY || reference.name != maker.name) return@forEachIndexed
                val viewRegister = (instruction as FiveRegisterInstruction).registerD
                val viewRead = (index - 1 downTo 1).first { previous ->
                    code[previous].opcode == Opcode.MOVE_RESULT_OBJECT && (code[previous] as OneRegisterInstruction).registerA == viewRegister
                }
                assertEquals("${apk.name}: only the sticker bubble is passed to the video tap", ROUNDING_VIEW,
                    code[viewRead - 1].getReference<MethodReference>()!!.returnType)
            }

            val patched = MutableMethod(video.method)
            patched.addDmStickerVideoTapHook(video)
            val before = video.method.implementation!!.instructions.toList()
            val after = patched.implementation!!.instructions.toList()
            assertEquals("${apk.name} gains the hook and its landing", before.size + 8, after.size)
            val call = after.indexOfFirst { it.getReference<MethodReference>()?.name == "openDmStickerPreview" }
            assertEquals(apk.name, video.previewCall + 7, call)
            assertEquals(apk.name, Opcode.RETURN_VOID, after[video.previewCall + 9].opcode)
            assertEquals(apk.name, listOf("Landroid/view/View;", "Ljava/lang/Object;"),
                after[call].getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
            // Only TikTok's preview call falls into the hook. Every branch of TikTok's own still lands
            // where it did, so an early exit returns without the save box.
            val offsets = after.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
            val hook = (video.previewCall + 1..video.previewCall + 8).map { offsets[it] }.toSet()
            after.forEachIndexed { index, instruction ->
                if (index in video.previewCall + 1..video.previewCall + 8 || instruction !is OffsetInstruction) return@forEachIndexed
                assertTrue("${apk.name}: ${instruction.opcode} at $index lands in the hook", offsets[index] + instruction.codeOffset !in hook)
            }
            assertEquals(apk.name, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(patched))
        }
    }

    private fun Method.references() = implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() }.orEmpty()
    private fun Method.strings() = implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string }.orEmpty()
    private fun decryptSignature(method: Method) = method.returnType == "Landroid/util/Pair;"
        && method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/io/InputStream;", "J", "Ljava/lang/String;")

    private companion object {
        const val SLOT = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/gif/StoreStickerCardReusedUISlot;"
        const val ROUNDING_VIEW = "Lcom/ss/android/ugc/aweme/views/RoundingCornerLayout;"
        const val IMAGE = "Lcom/ss/android/ugc/aweme/im/message/template/component/ImageComponent;"
    }
}
