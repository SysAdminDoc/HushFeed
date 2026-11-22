/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val CAPTION_TRANSLATION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CaptionTranslation;"
internal const val CLIENT_TEXT_WRAPPER = "Lcom/ss/android/ugc/aweme/editpost/ClientTextWrapper;"
private const val CAPTION_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/**
 * The comment list's caption row binding its video (#488). The row is a view of its own that
 * reads the caption from the video it's handed: `setDesc` takes the text from `getDesc()` and the
 * hashtag and mention spans from `getTextExtra()`. It's the one method taking a video, the row's
 * event params and a string, in a LinearLayout, that loads "graphic_detail" (the photo page's name
 * for the same row). Its one caller is the caption row cell's bind.
 */
internal object CaptionRowViewBindFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(CAPTION_AWEME, "L", "Ljava/lang/String;"),
    strings = listOf("graphic_detail"),
    custom = { _, classDef -> classDef.superclass == "Landroid/widget/LinearLayout;" },
)

/**
 * How the feed caption picks up a rich translation: the one static (video, state) check that
 * reads a cached translation's ClientTextWrapper protocol. It asks TikTok's translation service
 * whether this video's caption shows translated (Always translate, the video's own See
 * translation or See original, and the Don't translate list), reads the description repo's cache
 * for TikTok's target language, and takes the ClientText (protocol 2) or the plain text
 * (protocol 0) off the cached value.
 */
internal object CaptionRichTranslationFingerprint : Fingerprint(
    isStatic = true,
    returnType = "Z",
    parameters = listOf(CAPTION_AWEME, "L"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET, definingClass = CLIENT_TEXT_WRAPPER, name = "translationProtocol"),
    ),
)

/**
 * The same check for the plain description repo: static, (video, state) to boolean, asking one
 * service method about the video (the rich check's own), reading one cached value off a repo for
 * TikTok's target language, and taking a String off it. The photo title's check asks the service
 * twice about the video, and the rich one reads ClientTextWrapper, so neither is taken.
 */
internal object CaptionPlainTranslationFingerprint : Fingerprint(
    isStatic = true,
    returnType = "Z",
    parameters = listOf(CAPTION_AWEME, "L"),
    custom = { method, _ -> method.plainCaptionTranslator() },
)

/** Everything one of the feed caption's translation checks reads, by its own references. */
internal class CaptionTranslatorReads(
    /** The translation service's singleton. */
    val service: FieldReference,
    /** The service's (video, null) answer: does TikTok show this video's caption translated. */
    val decision: MethodReference,
    /** The service's target language. */
    val language: MethodReference,
    /** The repo's singleton, and its cache read for (video, language). */
    val repo: FieldReference,
    val cacheGet: MethodReference,
    /** The cached value's type, its translated text and (rich only) its ClientTextWrapper. */
    val value: String,
    val text: FieldReference,
    val wrapper: FieldReference?,
)

private fun Instruction.methodRef(): MethodReference? = getReference<MethodReference>()
private fun Instruction.fieldRef(): FieldReference? = getReference<FieldReference>()
private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)

private fun Instruction.isDecision() = opcode == Opcode.INVOKE_VIRTUAL && methodRef()?.let {
    it.returnType == "Z" && it.params() == listOf(CAPTION_AWEME, "Ljava/lang/Boolean;")
} == true

private fun Instruction.isCacheGet() = opcode == Opcode.INVOKE_VIRTUAL && methodRef()?.let {
    it.returnType.startsWith("L") && it.params() == listOf("Ljava/lang/Object;", "Ljava/lang/String;")
} == true

private fun Instruction.readsClientTextWrapper() = fieldRef()?.let {
    it.definingClass == CLIENT_TEXT_WRAPPER || it.type == CLIENT_TEXT_WRAPPER
} == true

/** Calls on [owner] that take the video. */
private fun List<Instruction>.asksAbout(owner: String) = count { instruction ->
    instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.methodRef()?.let {
        it.definingClass == owner && CAPTION_AWEME in it.params()
    } == true
}

/**
 * The reads of a caption translation check, or null when the method isn't that shape: one
 * decision call, the service's target language, a repo singleton handed straight to a cache
 * read, the result cast to the cached value's type, and a String read off that type.
 */
internal fun Method.captionTranslatorReads(): CaptionTranslatorReads? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val decisions = instructions.filter { it.isDecision() }
    if (decisions.size != 1) return null
    val decision = decisions.single().methodRef()!!
    val serviceType = decision.definingClass
    if (instructions.asksAbout(serviceType) != 1) return null
    val service = instructions.firstNotNullOfOrNull { instruction ->
        instruction.fieldRef()?.takeIf { instruction.opcode == Opcode.SGET_OBJECT && it.type == serviceType }
    } ?: return null
    val language = instructions.firstNotNullOfOrNull { instruction ->
        instruction.methodRef()?.takeIf {
            instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == serviceType &&
                it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        }
    } ?: return null
    val get = instructions.indexOfFirst { it.isCacheGet() }
    if (get < 0 || instructions.count { it.isCacheGet() } != 1) return null
    val receiver = (instructions[get] as FiveRegisterInstruction).registerC
    val repo = (get - 1 downTo 0).firstNotNullOfOrNull { index ->
        val instruction = instructions[index]
        instruction.fieldRef()?.takeIf {
            instruction.opcode == Opcode.SGET_OBJECT && (instruction as OneRegisterInstruction).registerA == receiver
        }
    } ?: return null
    if (instructions.getOrNull(get + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val cast = instructions.getOrNull(get + 2)?.takeIf { it.opcode == Opcode.CHECK_CAST } ?: return null
    val value = cast.getReference<TypeReference>()?.type ?: return null
    val reads = instructions.mapNotNull { instruction ->
        instruction.fieldRef()?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == value }
    }
    val text = reads.filter { it.type == "Ljava/lang/String;" }.distinct().singleOrNull() ?: return null
    val wrapper = reads.filter { it.type == CLIENT_TEXT_WRAPPER }.distinct().singleOrNull()
    return CaptionTranslatorReads(service, decision, language, repo, instructions[get].methodRef()!!, value, text, wrapper)
}

/** The plain description check's shape: the reads above, no ClientTextWrapper anywhere. */
internal fun Method.plainCaptionTranslator(): Boolean {
    val instructions = implementation?.instructions ?: return false
    if (instructions.any { it.readsClientTextWrapper() }) return false
    return captionTranslatorReads() != null
}

/** Whether [repo]'s class keys its cache by the video's description language. */
internal fun ClassDef.readsDescriptionLanguage(): Boolean = methods.any { method ->
    method.implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.methodRef()?.let {
            it.definingClass == CAPTION_AWEME && it.name == "getDescLanguage"
        } == true
    } == true
}

/** Both checks' reads, held to agree on the service before anything is written. */
internal class CaptionTranslationNative(val rich: CaptionTranslatorReads, val plain: CaptionTranslatorReads)

internal fun captionTranslationNative(
    richCheck: Method,
    plainCheck: Method,
    classOf: (String) -> ClassDef?,
): CaptionTranslationNative {
    fun fail(why: String): Nothing = throw PatchException("Comment tools: caption translation: $why")
    val rich = richCheck.captionTranslatorReads() ?: fail("${richCheck.definingClass}->${richCheck.name} isn't a caption translation check")
    rich.wrapper ?: fail("the rich check no longer reads a ClientTextWrapper off its cached value")
    val plain = plainCheck.captionTranslatorReads() ?: fail("${plainCheck.definingClass}->${plainCheck.name} isn't a caption translation check")
    if (plain.decision != rich.decision || plain.service != rich.service || plain.language != rich.language) {
        fail("the plain check asks another service (${plain.decision} against ${rich.decision})")
    }
    for (repo in listOf(rich.repo, plain.repo)) {
        val repoClass = classOf(repo.definingClass) ?: fail("no ${repo.definingClass}")
        if (!repoClass.readsDescriptionLanguage()) fail("${repo.definingClass} isn't keyed by the description's language")
    }
    if (rich.repo == plain.repo) fail("both checks read one repo")
    return CaptionTranslationNative(rich, plain)
}

/**
 * Hands the caption row's video to the extension as the row binds, and binds whatever comes back:
 * the video itself, or a copy carrying the caption TikTok shows translated on the video, with that
 * translation's own spans. Straight-line at the first instruction; nothing jumps there.
 */
internal fun MutableMethod.resolveCaptionRowTranslation(): CommentToolsWrite {
    if (AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("Comment tools: $definingClass->$name is static, so p1 isn't the video.")
    }
    if (parameterTypes.firstOrNull()?.toString() != CAPTION_AWEME) {
        throw PatchException("Comment tools: $definingClass->$name no longer takes the video first.")
    }
    val method = this
    return {
        method.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $CAPTION_TRANSLATION_CLASS_DESCRIPTOR->captionAweme(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $CAPTION_AWEME
            """,
        )
    }
}

/**
 * Rewrites the extension's bridges to read TikTok's own translation state: the service's answer
 * and target language, and the two description repos' cached values. Resolve [native] first.
 */
internal fun installCaptionTranslationBridges(native: CaptionTranslationNative) {
    val extension = mutableClassDefBy(CAPTION_TRANSLATION_CLASS_DESCRIPTOR)
    fun bridge(name: String) = extension.methods.singleOrNull { it.name == name }
        ?: throw PatchException("Comment tools: missing caption translation bridge $name")
    fun rewrite(original: MutableMethod, locals: Int, body: String) {
        val method = original.cloneMutable(additionalRegisters = locals)
        method.addInstructions(0, body)
        extension.methods.remove(original)
        extension.methods.add(method)
    }
    fun cachedRead(reads: CaptionTranslatorReads, field: FieldReference) = """
        sget-object v0, ${reads.repo}
        invoke-virtual { v0, p0, p1 }, ${reads.cacheGet}
        move-result-object v0
        instance-of v1, v0, ${reads.value}
        if-eqz v1, :none
        check-cast v0, ${reads.value}
        iget-object v0, v0, $field
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """
    val rich = native.rich
    val plain = native.plain
    val shows = bridge("nativeShowsTranslated")
    val language = bridge("nativeLanguage")
    val richWrapper = bridge("nativeRichWrapper")
    val richText = bridge("nativeRichText")
    val plainText = bridge("nativePlainText")
    rewrite(shows, 2, """
        instance-of v0, p0, $CAPTION_AWEME
        if-eqz v0, :no
        check-cast p0, $CAPTION_AWEME
        sget-object v0, ${rich.service}
        const/4 v1, 0x0
        invoke-virtual { v0, p0, v1 }, ${rich.decision}
        move-result v0
        return v0
        :no
        const/4 v0, 0x0
        return v0
    """)
    rewrite(language, 1, """
        sget-object v0, ${rich.service}
        invoke-virtual { v0 }, ${rich.language}
        move-result-object v0
        return-object v0
    """)
    rewrite(richWrapper, 2, cachedRead(rich, rich.wrapper!!))
    rewrite(richText, 2, cachedRead(rich, rich.text))
    rewrite(plainText, 2, cachedRead(plain, plain.text))
}

