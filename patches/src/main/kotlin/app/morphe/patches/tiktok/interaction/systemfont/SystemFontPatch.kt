/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.systemfont

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Use system font"
private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/font/SystemFont;"
private const val EMOJI_EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/font/SystemEmoji;"
private const val TYPEFACE = "Landroid/graphics/Typeface;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val PAINT = "Landroid/graphics/Paint;"

/** The variable-font build: `([FontVariationAxis, weight, italic) -> Typeface`. */
private const val VARIATION_SHAPE = "[Landroid/graphics/fonts/FontVariationAxis;IZ"

/** The static asset build: `(String path) -> Typeface`. */
private const val ASSET_SHAPE = "Ljava/lang/String;"

/** The variable font's asset name, present in every engine implementation's pool. */
private const val ENGINE_MARKER = "font/TikTokSans-VF.otf"

private object FontEngineFingerprint : Fingerprint(
    strings = listOf(ENGINE_MARKER),
    custom = { _, classDef -> isFontEngine(classDef) },
)

private object EmojiGlyphCheckFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(
        methodCall(
            definingClass = PAINT,
            name = "hasGlyph",
            returnType = "Z",
        ),
    ),
    custom = { method, classDef -> !classDef.type.startsWith(EXTENSION_ROOT) && isEmojiGlyphCheck(method) },
)

private object WebViewPageFinishedFingerprint : Fingerprint(
    name = "onPageFinished",
    parameters = listOf(WEB_VIEW, STRING),
    returnType = "V",
    custom = { method, classDef ->
        !classDef.type.startsWith(EXTENSION_ROOT) &&
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation != null
    },
)

private object WebViewPageCommitVisibleFingerprint : Fingerprint(
    name = "onPageCommitVisible",
    parameters = listOf(WEB_VIEW, STRING),
    returnType = "V",
    custom = { method, classDef ->
        !classDef.type.startsWith(EXTENSION_ROOT) &&
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation != null
    },
)

@Suppress("unused")
val systemFontPatch = bytecodePatch(
    name = "Use system font",
    description = "Shows TikTok's text in your phone's own font or in a .ttf or .otf font file " +
        "you import, instead of TikTok's, and can use your phone's emoji too. Starts off. Turn " +
        "it on in Hushfeed settings > App, then restart TikTok.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSystemFont()V",
        )

        // TikTok's text font engine builds every interface typeface, and its two producing
        // methods are the variable-font builder and the static asset loader. The class holds no
        // name a patch can carry, so it is found by having both method shapes and naming the
        // variable font. Both implementations (the modern and the pre-26 fallback) match, which
        // is what we want: the running build uses one of them. The icon, gift and mention fonts
        // load elsewhere and never reach this engine. TikTok's static nine-parameter typeface
        // factory and its Compose font loader both build through the engine's interface, so
        // hooking them as well would only answer the same faces twice; SystemFontAnchorsTest
        // holds that. An imported font file rides the same exit: the extension decides there.
        //
        // The same pass finds the emoji switch's anchor (see isEmojiGlyphCheck), so the app's
        // classes are walked once for both.
        val engines = FontEngineFingerprint.matchAll()
            .map { it.originalClassDef }
            .distinctBy { it.type }
        if (engines.isEmpty()) {
            throw PatchException(
                "Use system font: no font engine class found (a class building a Typeface from " +
                    "font variation axes and from an asset path, naming $ENGINE_MARKER).",
            )
        }

        engines.forEach { engine ->
            val mutableEngine = mutableClassDefBy(engine)
            engine.methods
                .filter { it.engineShape() == VARIATION_SHAPE || it.engineShape() == ASSET_SHAPE }
                .forEach { method ->
                    mutableEngine.methods
                        .first { it.name == method.name && it.parameterTypes == method.parameterTypes }
                        .systemizeReturns()
                }
        }

        // Activity center, Watch history and the other history pages are TikTok web pages in a
        // WebView, drawn in TikTok's web font, so the engine never sees them (#7). Every
        // page-finished and page-visible callback of TikTok's WebViewClients hands the page to
        // the extension, which styles TikTok's own pages and leaves every other site alone.
        hookWebPageCallbacks(PATCH, "$WEB_FONT_CLASS_DESCRIPTOR->onPageFinished($WEB_VIEW$STRING)V")

        // Use system emoji. TikTok's fonts carry no emoji, so an emoji already falls through to
        // the device's emoji font, except where androidx EmojiCompat steps in: TikTok starts it
        // at boot with the downloadable font (Google's Noto Color Emoji on most phones), and it
        // wraps any emoji the device's font can't draw in a span drawn with that font. Whether
        // the device can draw one is the glyph check's answer, and the processor wraps an emoji
        // only on false. The extension answers true first while the switch is on, so nothing is
        // wrapped. Anything else runs the check as TikTok wrote it.
        val glyphChecks = EmojiGlyphCheckFingerprint.matchAll().map { it.originalMethod }
        val glyphCheck = glyphChecks.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one emoji glyph check (a method answering a boolean from a " +
                "CharSequence, a start, an end and the emoji, that calls $PAINT_HAS_GLYPH), " +
                "found ${glyphChecks.size}",
        )
        mutableClassDefBy(glyphCheck.definingClass).findMutableMethodOf(glyphCheck).guardAtEntry(
            PATCH,
            "invoke-static {}, $EMOJI_EXTENSION_CLASS_DESCRIPTOR->leaveToDevice()Z",
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}

private const val EXTENSION_ROOT = "Lapp/morphe/extension/"
private const val PAINT_HAS_GLYPH = "$PAINT->hasGlyph(Ljava/lang/String;)Z"
private const val WEB_FONT_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/font/WebFont;"
private const val WEB_VIEW = "Landroid/webkit/WebView;"
private const val WEB_VIEW_CLIENT = "Landroid/webkit/WebViewClient;"
private const val STRING = "Ljava/lang/String;"
private val PAGE_CALLBACK_NAMES = setOf("onPageFinished", "onPageCommitVisible")

/**
 * Calls [target], a static `(WebView, String)V` extension method, at the top of every
 * `onPageFinished` and `onPageCommitVisible` of TikTok's WebViewClients, with the page's WebView
 * and URL. Use system font and AMOLED dark theme both style TikTok's web pages this way, and
 * when both are applied each callback calls both.
 */
internal fun BytecodePatchContext.hookWebPageCallbacks(patch: String, target: String) {
    fun isClient(type: String): Boolean {
        var current: String? = classDefByOrNull(type)?.superclass
        repeat(32) {
            val parent = current ?: return false
            if (parent == WEB_VIEW_CLIENT) return true
            current = classDefByOrNull(parent)?.superclass
        }
        return false
    }
    val candidateCallbacks = (WebViewPageFinishedFingerprint.matchAll() + WebViewPageCommitVisibleFingerprint.matchAll())
        .map { it.originalMethod }
        .distinct()
    val pageCallbacks = candidateCallbacks.filter { isClient(it.definingClass) }
    if (pageCallbacks.isEmpty()) {
        throw PatchException(
            "$patch: no WebViewClient onPageFinished or onPageCommitVisible implementation found.",
        )
    }
    pageCallbacks.forEach { callback ->
        mutableClassDefBy(callback.definingClass).findMutableMethodOf(callback).addInstruction(
            0,
            "invoke-static/range { p1 .. p2 }, $target",
        )
    }
}

/**
 * The `onPageFinished(WebView, String)` and `onPageCommitVisible(WebView, String)` methods with
 * code in every app class descended from WebViewClient, through any number of TikTok's own
 * subclasses. Shared with the fixture test, so the test holds the patch's own rule to each build.
 */
internal fun webPageCallbacks(classes: Iterable<ClassDef>): List<Method> {
    val superOf = classes.associate { it.type to it.superclass }
    fun isClient(type: String): Boolean {
        var current = superOf[type]
        // A bound instead of a seen-set: a real hierarchy is a few levels deep.
        repeat(32) {
            val parent = current ?: return false
            if (parent == WEB_VIEW_CLIENT) return true
            current = superOf[parent]
        }
        return false
    }
    return classes
        .filter { !it.type.startsWith(EXTENSION_ROOT) && isClient(it.type) }
        .flatMap { classDef ->
            classDef.methods.filter { method ->
                method.name in PAGE_CALLBACK_NAMES && method.returnType == "V" &&
                    method.implementation != null &&
                    !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.parameterTypes.map { it.toString() } == listOf(WEB_VIEW, STRING)
            }
        }
}

/**
 * Whether [method] is androidx EmojiCompat's glyph check: it answers a boolean from a
 * CharSequence, a start and an end and one more value (the emoji's metadata, or the version it
 * was added in), and it calls `Paint.hasGlyph(String)`.
 *
 * On 47.0.3, 47.1.3 and 47.1.4 R8 folded emoji2 1.3.0's default glyph checker into its processor,
 * so this is the processor's `hasGlyph(CharSequence, int, int, TypefaceEmojiRasterizer)`, the only
 * method in the app that calls `Paint.hasGlyph`. Were the checker left on its own, its
 * `hasGlyph(CharSequence, int, int, int)` would match instead, and answering true there means the
 * same thing. Shared with the fixture test, so the test holds the patch's own rule to each build.
 */
internal fun isEmojiGlyphCheck(method: Method): Boolean {
    if (method.returnType != "Z") return false
    val parameters = method.parameterTypes
    if (parameters.size != 4 || parameters[0].toString() != CHAR_SEQUENCE ||
        parameters[1].toString() != "I" || parameters[2].toString() != "I"
    ) {
        return false
    }
    return method.implementation?.instructions?.any { instruction ->
        instruction.getReference<MethodReference>()?.let { reference ->
            reference.definingClass == PAINT && reference.name == "hasGlyph" &&
                reference.returnType == "Z" &&
                reference.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;")
        } == true
    } == true
}

/**
 * Whether [classDef] is one of TikTok's text font engines: it has both build methods and names the
 * variable font. Shared with the fixture test, so the test holds the patch's own rule to each build.
 */
internal fun isFontEngine(classDef: ClassDef): Boolean {
    if (classDef.type.startsWith(EXTENSION_ROOT)) return false
    val hasVariation = classDef.methods.any { it.engineShape() == VARIATION_SHAPE }
    val hasAsset = classDef.methods.any { it.engineShape() == ASSET_SHAPE }
    if (!hasVariation || !hasAsset) return false
    return classDef.methods.any { method ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<StringReference>()?.string == ENGINE_MARKER
        } == true
    }
}

/** The parameter signature of an engine build method that returns a Typeface, else null-ish. */
internal fun Method.engineShape(): String? {
    if (returnType != TYPEFACE) return null
    return parameterTypes.joinToString("").takeIf { it == VARIATION_SHAPE || it == ASSET_SHAPE }
}

/** Sends every returned Typeface through the extension, which swaps it when the switch is on. */
private fun MutableMethod.systemizeReturns() {
    findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->systemize($TYPEFACE)$TYPEFACE
                move-result-object v$register
            """,
        )
    }
}
