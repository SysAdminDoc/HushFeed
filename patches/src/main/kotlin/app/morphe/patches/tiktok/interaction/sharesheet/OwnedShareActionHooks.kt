/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.downloads.addShareActionToSnapshot
import app.morphe.patches.tiktok.interaction.downloads.snapshotActionFields
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classesCalling
import app.morphe.util.cloneMutable
import app.morphe.util.extendsClass
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.readOnly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val OWNED_SHARE_ACTIONS = "Lapp/morphe/extension/tiktok/share/OwnedShareActions;"
internal const val SHARE_BLOCK_AUTHOR = "Lapp/morphe/extension/tiktok/share/ShareBlockAuthor;"
internal const val SHARE_SAVE_COVER = "Lapp/morphe/extension/tiktok/share/ShareSaveCover;"
internal const val SHARE_LIVE_QUALITY = "Lapp/morphe/extension/tiktok/share/ShareLiveQuality;"
internal const val SHARE_LIVE_BUTTONS = "Lapp/morphe/extension/tiktok/share/ShareLiveButtons;"
internal const val SHARE_MAKE_STICKER = "Lapp/morphe/extension/tiktok/share/ShareMakeSticker;"
internal const val SHARE_SAVE_EPISODES = "Lapp/morphe/extension/tiktok/share/ShareSaveEpisodes;"
private const val BLOCK = "Owned share actions"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val BASE_PACKAGE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"
private const val CONTEXT = "Landroid/content/Context;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val IMAGE = "Landroid/widget/ImageView;"
private const val TEXT = "Landroid/widget/TextView;"
private const val TUX_ICON = "Lcom/bytedance/tux/icon/TuxIconView;"
private const val RESOURCES = "Landroid/content/res/Resources;"
private val CLICK_PARAMETERS = listOf(CONTEXT, BASE_PACKAGE)

internal fun Method.isReportActionConstructor(): Boolean {
    val parameters = parameterTypes.map(CharSequence::toString)
    return name == "<init>" && returnType == "V" && parameters.size == 6 &&
        parameters[0] == AWEME && parameters[1].startsWith("L") &&
        parameters.drop(2) == listOf(STRING, STRING, ACTIVITY, "Landroidx/fragment/app/Fragment;")
}

private fun Method.constantString(value: String): Boolean {
    if (parameterTypes.isNotEmpty() || returnType != STRING || AccessFlags.STATIC.isSet(accessFlags)) return false
    val code = implementation?.instructions?.toList() ?: return false
    return code.size == 2 && code[0].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
        code[0].getReference<StringReference>()?.string == value && code[1].opcode == Opcode.RETURN_OBJECT
}

internal fun ClassDef.reportKey(): Method = methods.single { it.name == "key" && it.constantString("report") }
internal fun ClassDef.reportLabelHelper(): MethodReference {
    val binder = methods.single { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(TEXT) }
    return binder.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }.single {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString).let { parameters ->
            parameters.size == 2 && parameters[0] in interfaces && parameters[1] == TEXT
        }
    }
}

/** Follow the interface String result handed directly to TextView.setText, not every String read. */
internal fun ClassDef.reportLabel(helper: Method): Method {
    val code = helper.implementation!!.instructions.toList()
    val getter = code.indices.mapNotNull { index ->
        if (index < 2 || code[index].opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val setter = code[index].getReference<MethodReference>() ?: return@mapNotNull null
        val target = code[index] as? FiveRegisterInstruction ?: return@mapNotNull null
        if (setter.definingClass != TEXT || setter.name != "setText" || setter.returnType != "V" ||
            setter.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/CharSequence;")) return@mapNotNull null
        val result = code[index - 1] as? OneRegisterInstruction ?: return@mapNotNull null
        if (code[index - 1].opcode != Opcode.MOVE_RESULT_OBJECT || result.registerA != target.registerD ||
            code[index - 2].opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
        code[index - 2].getReference<MethodReference>()?.takeIf {
            it.definingClass in interfaces && it.returnType == STRING && it.parameterTypes.isEmpty()
        }
    }.distinctBy { it.toString() }.single()
    return methods.single { it.name == getter.name && it.constantString("") }
}

/** The real Report click invokes its own report implementation; shared dispatch methods don't. */
internal fun ClassDef.reportClick(): Method = methods.single { method ->
    AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == CLICK_PARAMETERS &&
        method.implementation?.instructions?.any { instruction ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) false
            else instruction.getReference<MethodReference>()?.let { called ->
                called.definingClass == type && called.returnType == "V" &&
                    called.parameterTypes.map(CharSequence::toString) == CLICK_PARAMETERS &&
                    called.name != method.name
            } == true
        } == true
}

internal fun ClassDef.isNativeReportAction(): Boolean {
    if (!AccessFlags.PUBLIC.isSet(accessFlags) || AccessFlags.INTERFACE.isSet(accessFlags) ||
        AccessFlags.ABSTRACT.isSet(accessFlags)) return false
    if (methods.count { it.isReportActionConstructor() && AccessFlags.PUBLIC.isSet(it.accessFlags) } != 1 ||
        methods.none { it.name == "key" && it.constantString("report") } ||
        fields.none { it.type == AWEME && !AccessFlags.STATIC.isSet(it.accessFlags) }) return false
    return methods.any { it.constantString("") } && runCatching { reportLabelHelper(); reportClick() }.isSuccess
}

internal object NativeReportActionFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(AWEME, "L", STRING, STRING, ACTIVITY, "Landroidx/fragment/app/Fragment;"),
    filters = listOf(fieldAccess(opcode = Opcode.IPUT_OBJECT, type = AWEME)),
    custom = { method, classDef -> method.isReportActionConstructor() && classDef.isNativeReportAction() },
)

/** Preserve the original body for every native Report instance the extension doesn't own. */
internal fun MutableMethod.answerOwnedShareObject(member: String, resultType: String) {
    guardAtEntry(
        BLOCK,
        "invoke-static/range { p0 .. p0 }, $OWNED_SHARE_ACTIONS->owns($OBJECT)Z",
        """
            invoke-static/range { p0 .. p0 }, $OWNED_SHARE_ACTIONS->$member($OBJECT)$resultType
            move-result-object v0
            return-object v0
        """,
    )
}

internal fun MutableMethod.takeOwnedShareClick() {
    guardAtEntry(
        BLOCK,
        "invoke-static/range { p0 .. p2 }, $OWNED_SHARE_ACTIONS->click($OBJECT$CONTEXT$OBJECT)Z",
        "return-void",
    )
}

internal data class OwnedShareIconBind(val index: Int, val actionRegister: Int, val imageRegister: Int)

/**
 * The common icon store follows the same action's package callback, after all drawable branches.
 * The share grid stores into an ImageView and the long-press panel's list into a TuxIconView, so
 * [isImageView] answers for ImageView subclasses too.
 */
internal fun Method.ownedShareIconBind(
    action: ClassDef,
    isImageView: (String) -> Boolean = { it == IMAGE },
): OwnedShareIconBind? {
    if (name != "onBindViewHolder" || returnType != "V" ||
        parameterTypes.map(CharSequence::toString) != listOf("Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;", "I")) return null
    val code = implementation?.instructions?.toList() ?: return null
    val stores = code.indices.filter { index ->
        val called = code[index].getReference<MethodReference>()
        code[index].opcode == Opcode.INVOKE_VIRTUAL && called != null && called.name == "setImageDrawable" &&
            called.parameterTypes.map(CharSequence::toString) == listOf("Landroid/graphics/drawable/Drawable;") &&
            isImageView(called.definingClass)
    }
    if (stores.size != 1) return null
    val index = stores.single()
    if (index < 2) return null
    val store = code[index] as? FiveRegisterInstruction ?: return null
    val image = code[index - 1] as? TwoRegisterInstruction ?: return null
    val field = code[index - 1].getReference<FieldReference>() ?: return null
    val callback = code[index - 2].getReference<MethodReference>() ?: return null
    val invocation = code[index - 2] as? FiveRegisterInstruction ?: return null
    if (code[index - 1].opcode != Opcode.IGET_OBJECT || !isImageView(field.type) || image.registerA != store.registerC ||
        code[index - 2].opcode != Opcode.INVOKE_INTERFACE || callback.definingClass !in action.interfaces ||
        callback.returnType != "V" || callback.parameterTypes.map(CharSequence::toString) != listOf(BASE_PACKAGE) ||
        invocation.registerC == store.registerC) return null
    return OwnedShareIconBind(index, invocation.registerC, store.registerC)
}

internal fun MutableMethod.bindOwnedShareIcon(bind: OwnedShareIconBind) {
    addInstructions(bind.index + 1,
        "invoke-static { v${bind.actionRegister}, v${bind.imageRegister} }, $OWNED_SHARE_ACTIONS->bindIcon($OBJECT$IMAGE)V")
}

/** [keyIndex] is the move-result-object that takes the action's key for the framed-key test. */
internal data class OwnedShareFrameCheck(val keyIndex: Int, val actionRegister: Int, val keyRegister: Int)

/**
 * A binder draws TikTok's icon frame (its themed circle under a TuxIconDrawable) only for an action
 * whose key is on its framed-key list, and a bare resource drawable for any other, so an owned
 * carrier's own key left it no frame to put its glyph in (S22, 2026-10-12). The list is read as
 * `sget-object` of a static String[], the key, and an index-of whose result overwrites the key's
 * register at once, so the key read there feeds that one test and nothing else.
 */
internal fun Method.ownedShareFrameCheck(action: ClassDef): OwnedShareFrameCheck? {
    val code = implementation?.instructions?.toList() ?: return null
    val checks = (0 until code.size - 4).mapNotNull { at ->
        val list = code[at].takeIf { it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.type == "[$STRING" }
            as? OneRegisterInstruction ?: return@mapNotNull null
        val read = code[at + 1] as? FiveRegisterInstruction ?: return@mapNotNull null
        val key = code[at + 1].getReference<MethodReference>()
        if (code[at + 1].opcode != Opcode.INVOKE_INTERFACE || key?.name != "key" || key.returnType != STRING ||
            key.parameterTypes.isNotEmpty() || key.definingClass !in action.interfaces) return@mapNotNull null
        val result = code[at + 2].takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
            ?: return@mapNotNull null
        val test = code[at + 3] as? FiveRegisterInstruction ?: return@mapNotNull null
        val call = code[at + 3].getReference<MethodReference>()
        if (code[at + 3].opcode != Opcode.INVOKE_STATIC || call?.returnType != "I" ||
            call.parameterTypes.map(CharSequence::toString) != listOf(OBJECT, "[$OBJECT") ||
            test.registerCount != 2 || test.registerC != result.registerA || test.registerD != list.registerA) return@mapNotNull null
        val found = code[at + 4].takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
            ?: return@mapNotNull null
        // A key that lands in the action's own register leaves nothing to ask the carrier with.
        if (found.registerA != result.registerA || read.registerC == result.registerA ||
            read.registerC > 15 || result.registerA > 15) return@mapNotNull null
        OwnedShareFrameCheck(at + 2, read.registerC, result.registerA)
    }
    return checks.singleOrNull()
}

internal fun MutableMethod.frameOwnedShareIcon(check: OwnedShareFrameCheck) {
    addInstructions(check.keyIndex + 1, """
        invoke-static { v${check.actionRegister}, v${check.keyRegister} }, $OWNED_SHARE_ACTIONS->framedKey($OBJECT$STRING)$STRING
        move-result-object v${check.keyRegister}
    """)
}

/** A menu row view's bind: the row model type it draws, and the view's icon and text fields. */
internal data class OwnedMenuRowBind(val model: String, val iconField: FieldReference, val textField: FieldReference)

/**
 * LIVE's long-press menu (#359) copies each action into a row model of its own and a row view draws
 * only that model, so the carrier's label and icon hooks never ran there and an owned row said
 * "Report" under Report's flag (S22, 2026-11-18). The row view's bind takes the model and a flag,
 * sets the model's icon resource on a TuxIconView field, and sets the model's own text, or its text
 * resource's when that's empty, on a TextView field. Both fields are read off the view itself.
 */
internal fun Method.ownedMenuRowBind(): OwnedMenuRowBind? {
    val parameters = parameterTypes.map(CharSequence::toString)
    if (returnType != "V" || AccessFlags.STATIC.isSet(accessFlags) || parameters.size != 2 ||
        !parameters[0].startsWith("L") || parameters[1] != "Z") return null
    val body = implementation ?: return null
    val code = body.instructions.toList()
    // this, the model and the flag are the last three registers.
    val self = body.registerCount - 3
    // The hook reads both again at each return, so neither may be reused for anything else.
    if (code.any { write ->
            val first = (write as? OneRegisterInstruction)?.registerA ?: return@any false
            write.opcode.setsRegister() && (first in self - 1..self + 1) && (first != self - 1 || write.opcode.setsWideRegister())
        }) return null
    fun calls(index: Int, owner: String, name: String) = code[index].opcode == Opcode.INVOKE_VIRTUAL &&
        code[index].getReference<MethodReference>()?.let { it.definingClass == owner && it.name == name } == true
    val icons = code.indices.filter { calls(it, TUX_ICON, "setIconRes") }
    val texts = code.indices.filter { calls(it, TEXT, "setText") }
    if (icons.size != 1 || texts.size != 1 || code.indices.none { calls(it, RESOURCES, "getText") }) return null
    val icon = viewField(code, icons.single(), self) ?: return null
    val text = viewField(code, texts.single(), self) ?: return null
    if (icon.type != TUX_ICON || text.type == TUX_ICON) return null
    return OwnedMenuRowBind(parameters[0], icon, text)
}

/** The field of the bind's own view that the call at [at] is made on, read off `this` before it. */
private fun Method.viewField(code: List<Instruction>, at: Int, self: Int): FieldReference? {
    val target = (code[at] as? FiveRegisterInstruction)?.registerC ?: return null
    for (index in at - 1 downTo 0) {
        val instruction = code[index]
        if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != target) continue
        if (instruction.opcode != Opcode.IGET_OBJECT) return null
        val field = instruction.getReference<FieldReference>() ?: return null
        return field.takeIf { (instruction as TwoRegisterInstruction).registerB == self && it.definingClass == definingClass }
    }
    return null
}

/** Draws an owned carrier's label and glyph on a menu row after TikTok's own bind, at every return. */
internal fun MutableMethod.bindOwnedMenuRow(bind: OwnedMenuRowBind) {
    val body = implementation ?: throw PatchException("$BLOCK: the menu row bind has no body")
    val self = body.registerCount - 3
    if (self < 2 || self + 1 > 15) throw PatchException("$BLOCK: the menu row bind has no room for its hook")
    val returns = body.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("$BLOCK: the menu row bind doesn't return")
    val icon = bind.iconField
    val text = bind.textField
    returns.reversed().forEach { at ->
        addInstructionsAtControlFlowLabel(at, """
            iget-object v0, p0, ${icon.definingClass}->${icon.name}:${icon.type}
            iget-object v1, p0, ${text.definingClass}->${text.name}:${text.type}
            invoke-static { p1, v0, v1 }, $OWNED_SHARE_ACTIONS->bindMenuRow($OBJECT$IMAGE$TEXT)V
        """)
    }
}

/** The action's text resource getter, which the native text helper falls back on for an empty label. */
internal fun Method.reportTextResource(interfaces: Collection<String>): String =
    implementation!!.instructions.mapNotNull { instruction ->
        instruction.getReference<MethodReference>()?.takeIf {
            instruction.opcode == Opcode.INVOKE_INTERFACE && it.definingClass in interfaces &&
                it.returnType == "I" && it.parameterTypes.isEmpty()
        }?.name
    }.distinct().single()

/** [index] is a row model's constructor; the action it was read off and the model are in those registers. */
internal data class OwnedMenuRowBuild(val index: Int, val actionRegister: Int, val modelRegister: Int)

/**
 * Where a menu builds an action's row model: the model's constructor takes the action's text
 * resource, read off the action just before it.
 */
internal fun Method.ownedMenuRowBuilds(model: String, textResource: String, interfaces: Collection<String>): List<OwnedMenuRowBuild> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    return (2 until code.size).mapNotNull { at ->
        val init = code[at].getReference<MethodReference>()
        if (code[at].opcode != Opcode.INVOKE_DIRECT || init?.definingClass != model || init.name != "<init>") return@mapNotNull null
        val build = code[at] as FiveRegisterInstruction
        val result = code[at - 1] as? OneRegisterInstruction ?: return@mapNotNull null
        val read = code[at - 2] as? FiveRegisterInstruction ?: return@mapNotNull null
        val getter = code[at - 2].getReference<MethodReference>() ?: return@mapNotNull null
        if (code[at - 1].opcode != Opcode.MOVE_RESULT || code[at - 2].opcode != Opcode.INVOKE_INTERFACE ||
            getter.name != textResource || getter.returnType != "I" || getter.parameterTypes.isNotEmpty() ||
            getter.definingClass !in interfaces || read.registerCount != 1) return@mapNotNull null
        val arguments = listOf(build.registerD, build.registerE, build.registerF, build.registerG).take(build.registerCount - 1)
        // The resource can't have landed on the action, which the hook still needs after the build.
        if (result.registerA !in arguments || result.registerA == read.registerC || build.registerC == read.registerC ||
            read.registerC > 15 || build.registerC > 15) return@mapNotNull null
        OwnedMenuRowBuild(at, read.registerC, build.registerC)
    }
}

/** Hands each row model built from an action to the extension with that action, right after it's built. */
internal fun MutableMethod.noteOwnedMenuRows(builds: List<OwnedMenuRowBuild>) {
    builds.sortedByDescending { it.index }.forEach { build ->
        addInstructions(build.index + 1,
            "invoke-static { v${build.actionRegister}, v${build.modelRegister} }, $OWNED_SHARE_ACTIONS->menuRow($OBJECT$OBJECT)V")
    }
}

/** The menu row view's bind, found by its shape. */
internal object OwnedMenuRowBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Z"),
    filters = listOf(
        methodCall(definingClass = TUX_ICON, name = "setIconRes"),
        methodCall(definingClass = RESOURCES, name = "getText"),
        methodCall(definingClass = TEXT, name = "setText"),
    ),
    custom = { method, _ -> method.ownedMenuRowBind() != null },
)

internal fun Method.withOwnedShareBody(action: ClassDef): MutableMethod {
    val constructor = action.methods.single { it.isReportActionConstructor() }
    check(AccessFlags.STATIC.isSet(accessFlags) && returnType == OBJECT &&
        parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY, OBJECT)) { "$BLOCK: invalid native factory" }
    return cloneMutable(additionalRegisters = 7).apply {
        addInstructions(
            0,
            """
                check-cast p1, $AWEME
                new-instance v0, ${action.type}
                move-object/from16 v1, p1
                const/4 v2, 0x0
                const-string v3, ""
                move-object v4, v3
                move-object/from16 v5, p0
                const/4 v6, 0x0
                invoke-direct/range { v0 .. v6 }, ${action.type}-><init>(${constructor.parameterTypes.joinToString("")})V
                return-object v0
            """,
        )
    }
}

internal fun hookOwnedShareActions() {
    val action = NativeReportActionFingerprint.classDef
    val helperReference = action.reportLabelHelper()
    val helper = mutableClassDefBy(helperReference.definingClass).methods.single {
        it.name == helperReference.name && it.returnType == helperReference.returnType &&
            it.parameterTypes.map(CharSequence::toString) == helperReference.parameterTypes.map(CharSequence::toString)
    }
    val label = action.reportLabel(helper) as MutableMethod
    val callers = classesCalling(action.interfaces)
    val imageViews = mutableMapOf<String, Boolean>()
    val isImageView = { type: String -> imageViews.getOrPut(type) { extendsClass(type, IMAGE) } }
    val iconBinders = mutableListOf<Pair<Method, OwnedShareIconBind>>()
    // LIVE's long-press menu draws rows from models of its own (#359).
    val rowBind = OwnedMenuRowBindFingerprint.method
    val menuRow = rowBind.ownedMenuRowBind() ?: throw PatchException("$BLOCK: the menu row bind changed shape")
    val textResource = helper.reportTextResource(action.interfaces)
    val rowBuilds = mutableListOf<Pair<Method, List<OwnedMenuRowBuild>>>()
    for (callerType in callers) {
        val classDef = classDefByOrNull(callerType) ?: continue
        // Read only, so a caller made mutable earlier doesn't keep a built copy of every body (#497).
        classDef.methods.map(Method::readOnly).forEach { method ->
            method.ownedShareIconBind(action, isImageView)?.let { bind -> iconBinders += method to bind }
            method.ownedMenuRowBuilds(menuRow.model, textResource, action.interfaces)
                .takeIf { it.isNotEmpty() }?.let { builds -> rowBuilds += method to builds }
        }
    }
    // The share grid's binder at least; the feed's long-press panel lists actions through a second one.
    if (iconBinders.isEmpty()) throw PatchException("$BLOCK: found no native action icon binder")
    if (rowBuilds.isEmpty()) throw PatchException("$BLOCK: found no menu row model built from an action")
    (action.reportKey() as MutableMethod).answerOwnedShareObject("key", STRING)
    label.answerOwnedShareObject("label", STRING)
    (action.reportClick() as MutableMethod).takeOwnedShareClick()
    iconBinders.forEach { (binder, bind) ->
        val frame = binder.ownedShareFrameCheck(action)
        mutableClassDefBy(binder.definingClass).findMutableMethodOf(binder).apply {
            // The icon store comes after the key test, so its index still holds once it's in.
            bindOwnedShareIcon(bind)
            // Without the test the glyph keeps its bare fallback, which is how it looked before.
            if (frame != null && frame.keyIndex < bind.index) frameOwnedShareIcon(frame)
        }
    }
    rowBind.bindOwnedMenuRow(menuRow)
    rowBuilds.forEach { (method, builds) ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).noteOwnedMenuRows(builds)
    }

    val bridges = mutableClassDefBy(OWNED_SHARE_ACTIONS)
    val original = bridges.methods.singleOrNull { it.name == "newAction" && it.parameterTypes.size == 2 }
        ?: throw PatchException("$BLOCK: no single native action factory")
    val bridge = original.withOwnedShareBody(action)
    bridges.methods.remove(original)
    bridges.methods.add(bridge)
}

/** Adds [extension]'s actions to every finished share sheet row; its carriers answer through the shared hooks. */
internal fun addOwnedShareAction(extension: String) {
    val snapshot = ShareSnapshotFingerprint.method
    snapshot.addShareActionToSnapshot(snapshot.snapshotActionFields(ShareSnapshotFingerprint.classDef), extension)
}

/**
 * The native Report carrier's key, label, click and icon hooks and its constructor bridge, which
 * Block this creator (Share sheet tools), Save cover (Advanced downloads) and Make sticker (Create
 * stickers) share. A build with several installs them once, and each patch adds its own action to
 * the finished row.
 */
internal val ownedShareActionsPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        hookOwnedShareActions()
    }
}
