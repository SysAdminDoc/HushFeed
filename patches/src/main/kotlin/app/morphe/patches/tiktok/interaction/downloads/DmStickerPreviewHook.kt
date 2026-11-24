/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val DM_STICKER_HANDLER = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/gif/DefaultStickerClickHandler;"
internal const val DM_STICKER_TEMPLATE = "Lcom/ss/android/ugc/aweme/im/message/template/card/StickerTemplate;"
internal const val DM_STICKER_ABILITY = "Lcom/ss/android/ugc/aweme/im/messagelist/api/ability/MessageListStickerClickAbility;"
private const val IM_STICKER_API = "Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;"
private const val SAVER = "Lapp/morphe/extension/tiktok/download/StickerGallerySaver;"

internal data class DmStickerPreviewAnchor(val method: Method, val previewCall: Int, val service: MethodReference)

/** The message sticker tap that builds a unified preview and ends with its preview service call. */
internal fun dmStickerPreviewAnchor(handler: ClassDef): DmStickerPreviewAnchor {
    if (handler.type != DM_STICKER_HANDLER || DM_STICKER_ABILITY !in handler.interfaces) {
        throw PatchException("Downloads: unexpected DM sticker click owner")
    }
    val matches = handler.methods.mapNotNull { method ->
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V"
            || method.parameterTypes.size != 4 || method.parameterTypes[0] != "Landroid/view/View;"
            || !method.parameterTypes[1].toString().startsWith("L")
            || !method.parameterTypes[2].toString().startsWith("L")
            || method.parameterTypes[3] != DM_STICKER_TEMPLATE) return@mapNotNull null
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        if (instructions.none { it.getReference<StringReference>()?.string == "chat_general_sticker_click" }) {
            return@mapNotNull null
        }
        val getter = instructions.mapNotNull { it.getReference<MethodReference>() }.singleOrNull {
            it.definingClass == IM_STICKER_API && it.name == "getImStickerInputPanelService"
                && it.parameterTypes.isEmpty()
        } ?: return@mapNotNull null
        val calls = instructions.withIndex().filter { (_, instruction) ->
            val reference = instruction.getReference<MethodReference>()
            instruction.opcode in setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
                && reference?.definingClass == getter.returnType && reference.returnType == "V"
                && reference.parameterTypes.size == 4
        }
        val call = calls.singleOrNull() ?: return@mapNotNull null
        if (instructions.getOrNull(call.index + 1)?.opcode != Opcode.RETURN_VOID) return@mapNotNull null
        DmStickerPreviewAnchor(method, call.index, call.value.getReference<MethodReference>()!!)
    }
    return matches.singleOrNull()
        ?: throw PatchException("Downloads: expected one DM sticker preview tap, found ${matches.size}")
}

/** Never put a second preview over a future native implementation that already owns a sheet. */
internal fun isEmptyDmPreview(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    return instructions.any { it.opcode == Opcode.RETURN_VOID } && instructions.all {
        when (it.opcode) {
            Opcode.IGET_OBJECT, Opcode.RETURN_VOID -> true
            Opcode.INVOKE_VIRTUAL -> it.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Ljava/lang/Object;" && reference.name == "getClass"
                    && reference.parameterTypes.isEmpty()
            } == true
            else -> false
        }
    }
}

internal fun MutableMethod.addDmStickerPreviewHook(previewCall: Int) {
    requireLocals("Downloads DM sticker preview", 2)
    // The service returns void straight into RETURN_VOID, so these locals are dead. p1 and p4
    // are the tapped view and its template; context, message IDs and send callbacks never cross.
    addInstructions(previewCall + 1, """
        move-object/from16 v0, p1
        move-object/from16 v1, p4
        invoke-static {v0, v1}, $SAVER->openDmStickerPreview(Landroid/view/View;Ljava/lang/Object;)V
    """)
}

internal fun hookDmStickerPreview() {
    val handler = classDefByOrNull(DM_STICKER_HANDLER)
        ?: throw PatchException("Downloads: DM sticker click handler is missing")
    val anchor = dmStickerPreviewAnchor(handler)
    val implementations = mutableListOf<Method>()
    classDefForEach { definition ->
        if (anchor.service.definingClass in definition.interfaces) {
            implementations += definition.methods.filter {
                it.name == anchor.service.name && it.parameterTypes == anchor.service.parameterTypes
                    && it.returnType == anchor.service.returnType
            }
        }
    }
    if (implementations.isEmpty() || implementations.any { !isEmptyDmPreview(it) }) {
        throw PatchException("Downloads: the DM sticker preview service is no longer empty")
    }
    mutableClassDefBy(handler).findMutableMethodOf(anchor.method).addDmStickerPreviewHook(anchor.previewCall)
    val video = dmStickerVideoTap(handler, anchor.service) { classDefByOrNull(it) }
    if (video == null) {
        println("[Downloads] Left the save box off stickers made from a video: no video sticker tap found.")
        return
    }
    mutableClassDefBy(handler).findMutableMethodOf(video.method).addDmStickerVideoTapHook(video)
}

/**
 * The tap on a sticker made from a video (#470), which TikTok sends through a method of its own:
 * it takes one click object, logs "chat_video_sticker_click" and ends with the same preview
 * service call as the other sticker tap. [click] is the click made from a chat bubble, and
 * [view] and [template] its tapped view and sticker template.
 */
internal data class DmStickerVideoTap(
    val method: Method,
    val previewCall: Int,
    val click: String,
    val view: FieldReference,
    val template: FieldReference,
)

/** The video sticker tap in [handler], or null when TikTok no longer has one this shape. */
internal fun dmStickerVideoTap(handler: ClassDef, service: MethodReference, classOf: (String) -> ClassDef?): DmStickerVideoTap? {
    val matches = handler.methods.mapNotNull { method ->
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V"
            || method.parameterTypes.size != 1) return@mapNotNull null
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        if (instructions.none { it.getReference<StringReference>()?.string == "chat_video_sticker_click" }) {
            return@mapNotNull null
        }
        val call = instructions.withIndex().singleOrNull { (_, instruction) ->
            instruction.opcode in setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
                && instruction.getReference<MethodReference>()?.let { signature(it) == signature(service) } == true
        } ?: return@mapNotNull null
        if (instructions.getOrNull(call.index + 1)?.opcode != Opcode.RETURN_VOID) return@mapNotNull null
        // The click a chat bubble makes is the one that keeps the tapped view.
        val parameter = method.parameterTypes.single().toString()
        val clicks = instructions.filter { it.opcode == Opcode.INSTANCE_OF }
            .mapNotNull { it.getReference<TypeReference>()?.type?.let(classOf) }
            .filter { parameter in it.interfaces }
            .mapNotNull { click ->
                val view = click.fields.singleOrNull { it.type == "Landroid/view/View;" }
                val template = click.fields.singleOrNull { it.type == DM_STICKER_TEMPLATE }
                if (view == null || template == null || !AccessFlags.PUBLIC.isSet(view.accessFlags)
                    || !AccessFlags.PUBLIC.isSet(template.accessFlags)
                    || !AccessFlags.PUBLIC.isSet(click.accessFlags)) null
                else DmStickerVideoTap(method, call.index, click.type, view, template)
            }
        clicks.singleOrNull()
    }
    return matches.singleOrNull()
}

/**
 * Opens the save box after TikTok's own preview call on a video sticker tap, the way the other
 * sticker tap does. Only the call falls through to it: TikTok's early exits branch to the return
 * itself and skip it. A click from anything but a chat bubble carries no view and is left alone.
 */
internal fun MutableMethod.addDmStickerVideoTapHook(tap: DmStickerVideoTap) {
    requireLocals("Downloads DM video sticker preview", 2)
    // The service returns void straight into RETURN_VOID, so v0 and v1 are dead there.
    addInstructionsWithLabels(tap.previewCall + 1, """
        move-object/from16 v0, p1
        instance-of v1, v0, ${tap.click}
        if-eqz v1, :hushfeed_no_bubble
        check-cast v0, ${tap.click}
        iget-object v1, v0, ${tap.view}
        iget-object v0, v0, ${tap.template}
        invoke-static {v1, v0}, $SAVER->openDmStickerPreview(Landroid/view/View;Ljava/lang/Object;)V
        :hushfeed_no_bubble
        nop
    """)
}

// ---- Save media in the long-press menu of a sticker message (#470) ---------------------------

private const val DM_MENU_OBJECT = "Ljava/lang/Object;"
private const val DM_MENU_STRING = "Ljava/lang/String;"
private const val DM_MENU_ENUM = "Ljava/lang/Enum;"
private const val DM_MENU_LIST = "Ljava/util/List;"
private const val DM_MENU_FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val DM_MENU_FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val DM_MENU_UNIT = "Lkotlin/Unit;"
/** The sticker shop row's own name. TikTok names its menu rows in English on every build. */
internal const val DM_MENU_STORE = "Store"
private const val DM_MENU_STORE_ACTION = "STICKER_STORE"
/** TikTok's own sticker Save. The added row takes its action so the menu sorts it in right after. */
internal const val DM_MENU_ROW_ACTION = "FAV_STICKER"
/** The action TikTok's menu closes itself on. */
internal const val DM_MENU_DISMISS = "DISMISS"
internal const val DM_MENU_STYLE = "PRIMARY"

/**
 * The rows lambda of a sticker message's long-press menu: it takes the list being built, adds
 * TikTok's own rows (the sticker shop row among them) and answers Unit.
 */
internal object DmStickerMenuRowsFingerprint : Fingerprint(
    name = "invoke",
    returnType = DM_MENU_OBJECT,
    parameters = listOf(DM_MENU_OBJECT),
    strings = listOf(DM_MENU_STORE),
    custom = { method, classDef -> dmStickerStoreRow(method, classDef) != null },
)

/** The sticker shop row: the action enum it names and the static factory that builds the row. */
internal data class DmStickerStoreRow(val name: Int, val action: String, val factory: MethodReference)

/**
 * The lambda's sticker shop row, read by shape: one "Store" literal, the shop action read from
 * its own enum, then the first static factory taking a name and an enum, whose row goes straight
 * into the list. Null for anything else.
 */
internal fun dmStickerStoreRow(method: Method, classDef: ClassDef): DmStickerStoreRow? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.name != "invoke" || method.returnType != DM_MENU_OBJECT
        || method.parameterTypes.map { it.toString() } != listOf(DM_MENU_OBJECT)) return null
    if (DM_MENU_FUNCTION1 !in classDef.interfaces.map { it.toString() }) return null
    if (classDef.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == DM_STICKER_TEMPLATE } != 1) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val names = code.withIndex().filter { (_, it) -> it.getReference<StringReference>()?.string == DM_MENU_STORE }
    val name = names.singleOrNull()?.index ?: return null
    val nameRegister = (code[name] as? OneRegisterInstruction)?.registerA ?: return null
    val action = code.getOrNull(name + 1)?.takeIf { it.opcode == Opcode.SGET_OBJECT }
        ?.getReference<FieldReference>()?.takeIf { it.name == DM_MENU_STORE_ACTION && it.type == it.definingClass } ?: return null
    val call = (name + 2 until code.size).firstOrNull { index ->
        val opcode = code[index].opcode
        (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) && code[index].getReference<MethodReference>()
            ?.parameterTypes?.map { it.toString() }?.take(2) == listOf(DM_MENU_STRING, DM_MENU_ENUM)
    } ?: return null
    val first = when (val invoke = code[call]) {
        is RegisterRangeInstruction -> invoke.startRegister
        is FiveRegisterInstruction -> invoke.registerC
        else -> return null
    }
    if (first != nameRegister || code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val add = code.getOrNull(call + 2)?.getReference<MethodReference>() ?: return null
    if (code[call + 2].opcode != Opcode.INVOKE_INTERFACE || add.definingClass != DM_MENU_LIST || add.name != "add") return null
    return DmStickerStoreRow(name, action.type, code[call].getReference<MethodReference>()!!)
}

/**
 * Everything the hook and the three bridges need, each piece read from the rows lambda and the
 * classes it reaches.
 *
 * @property returnIndex the lambda's one return, right after it loads Unit.
 * @property scratch two locals that are dead at the return.
 * @property template the lambda's StickerTemplate field.
 * @property builder the lambda's menu field, the one its rows close the menu through.
 * @property dismiss the menu's close call, taking an action and a callback.
 * @property row the menu row's constructor, with [rowParameters].
 */
internal data class DmStickerMenu(
    val returnIndex: Int,
    val scratch: List<Int>,
    val template: String,
    val builder: String,
    val builderType: String,
    val dismiss: String,
    val action: String,
    val unit: String,
    val row: String,
    val rowParameters: List<String>,
    val icon: String,
    val title: String,
    val style: String,
    val size: String,
)

/** Resolves [DmStickerMenu] from the rows lambda, or says which piece changed shape. */
internal fun dmStickerMenu(rows: Method, owner: ClassDef, classOf: (String) -> ClassDef?): DmStickerMenu {
    fun refuse(what: String): Nothing = throw PatchException("Downloads: the DM sticker menu $what")
    val store = dmStickerStoreRow(rows, owner) ?: refuse("rows lost their sticker shop row")
    val body = rows.implementation ?: refuse("rows have no code")
    val code = body.instructions.toList()
    val returnIndex = code.lastIndex
    if (code.count { it.opcode == Opcode.RETURN_OBJECT } != 1 || code[returnIndex].opcode != Opcode.RETURN_OBJECT) {
        refuse("rows no longer end in their one return")
    }
    val returned = (code[returnIndex] as OneRegisterInstruction).registerA
    val unit = code[returnIndex - 1].takeIf { it.opcode == Opcode.SGET_OBJECT && (it as OneRegisterInstruction).registerA == returned }
        ?.getReference<FieldReference>()?.takeIf { it.definingClass == DM_MENU_UNIT && it.type == DM_MENU_UNIT }
        ?: refuse("rows no longer answer Unit")
    val registers = body.registerCount
    if (registers > 16) refuse("rows use $registers registers, past v15")
    val self = registers - 2
    val list = registers - 1
    val cast = code[0]
    if (cast.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != list
        || cast.getReference<TypeReference>()?.type != DM_MENU_LIST) refuse("rows no longer cast their list first")
    for (index in 1..returnIndex) {
        val instruction = code[index]
        val written = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        val wide = instruction.opcode.setsWideRegister()
        if (instruction.opcode.setsRegister() && (written == self || written == list || (wide && written + 1 >= self))) {
            refuse("rows write over their lambda or list at $index")
        }
    }
    val scratch = (0 until self).filter { it != returned }.take(2)
    if (scratch.size != 2) refuse("rows have no two spare locals")
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val at = addresses[returnIndex]
    if (body.tryBlocks.any { at >= it.startCodeAddress && at < it.startCodeAddress + it.codeUnitCount }) {
        refuse("return sits inside a try block")
    }

    val template = owner.fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == DM_STICKER_TEMPLATE }
    val closers = owner.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.mapNotNull { field ->
        val menu = classOf(field.type) ?: return@mapNotNull null
        if (!AccessFlags.INTERFACE.isSet(menu.accessFlags) || !AccessFlags.PUBLIC.isSet(menu.accessFlags)) return@mapNotNull null
        val close = menu.methods.filter {
            it.returnType == "V" && it.parameterTypes.map { type -> type.toString() } == listOf(store.action, DM_MENU_FUNCTION0)
        }
        close.singleOrNull()?.let { field to it }
    }
    val (builder, dismiss) = closers.singleOrNull() ?: refuse("has ${closers.size} menus that close on an action")

    val action = classOf(store.action)
    if (action == null || !AccessFlags.ENUM.isSet(action.accessFlags) || !AccessFlags.PUBLIC.isSet(action.accessFlags)
        || listOf(DM_MENU_ROW_ACTION, DM_MENU_DISMISS).any { constant -> !action.hasPublicConstant(constant) }) {
        refuse("actions lost $DM_MENU_ROW_ACTION or $DM_MENU_DISMISS")
    }

    val rowClass = classOf(store.factory.returnType)
    val init = rowClass?.methods?.singleOrNull { it.name == "<init>" && it.parameterTypes.size == 8 }
    val rowParameters = init?.parameterTypes?.map { it.toString() }.orEmpty()
    if (rowClass == null || init == null || !AccessFlags.PUBLIC.isSet(rowClass.accessFlags) || !AccessFlags.PUBLIC.isSet(init.accessFlags)
        || rowParameters[0] != DM_MENU_STRING || rowParameters[1] != DM_MENU_ENUM || rowParameters[6] != "Z"
        || rowParameters[7] != DM_MENU_FUNCTION1) refuse("row lost its public constructor")
    val (iconType, titleType, styleType, sizeType) = rowParameters.subList(2, 6)

    // The factory makes the row's pieces itself, so the icon and title kinds it news up are TikTok's own.
    val made = classOf(store.factory.definingClass)?.methods?.toList().orEmpty().flatMap { method ->
        method.implementation?.instructions?.filter { it.opcode == Opcode.NEW_INSTANCE }
            ?.mapNotNull { it.getReference<TypeReference>()?.type }.orEmpty()
    }.distinct().mapNotNull(classOf)
    fun piece(kind: String, parameter: String) = made.filter { candidate ->
        kind in candidate.interfaces.map { it.toString() } && AccessFlags.PUBLIC.isSet(candidate.accessFlags) && candidate.hasPublicInit(parameter)
    }.singleOrNull()?.type ?: refuse("row lost its $parameter $kind piece")
    val icon = piece(iconType, "I")
    val title = piece(titleType, DM_MENU_STRING)

    val style = classOf(styleType)
    if (style == null || !AccessFlags.ENUM.isSet(style.accessFlags) || !AccessFlags.PUBLIC.isSet(style.accessFlags)
        || !style.hasPublicConstant(DM_MENU_STYLE)) refuse("row styles lost $DM_MENU_STYLE")
    val size = classOf(sizeType)
    if (size == null || !AccessFlags.PUBLIC.isSet(size.accessFlags) || !size.hasPublicInit(null)) refuse("row size lost its public constructor")

    return DmStickerMenu(
        returnIndex = returnIndex,
        scratch = scratch,
        template = "${template.definingClass}->${template.name}:${template.type}",
        builder = "${builder.definingClass}->${builder.name}:${builder.type}",
        builderType = builder.type,
        dismiss = "${dismiss.definingClass}->${dismiss.name}(${dismiss.parameterTypes.joinToString("")})V",
        action = store.action,
        unit = "${unit.definingClass}->${unit.name}:${unit.type}",
        row = store.factory.returnType,
        rowParameters = rowParameters,
        icon = icon,
        title = title,
        style = styleType,
        size = sizeType,
    )
}

private fun ClassDef.hasPublicConstant(name: String) = fields.any {
    it.name == name && it.type == type && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
}

/** A public constructor taking [parameter] alone, or nothing when it's null. */
private fun ClassDef.hasPublicInit(parameter: String?) = methods.any {
    it.name == "<init>" && AccessFlags.PUBLIC.isSet(it.accessFlags)
        && it.parameterTypes.map { type -> type.toString() } == listOfNotNull(parameter)
}

private fun dmMenuBridge(name: String, parameters: List<String>, returnType: String, locals: Int, accessFlags: Int, smali: String) =
    MutableMethod(
        ImmutableMethod(
            SAVER, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, accessFlags,
            null, null, ImmutableMethodImplementation(locals + parameters.size, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, smali) }

/** The bodies StickerGallerySaver's three menu stubs get, each building what TikTok's own rows build. */
internal fun dmStickerMenuBridges(
    menu: DmStickerMenu,
    flagsFor: (String) -> Int = { AccessFlags.STATIC.value },
): List<MutableMethod> = listOf(
    dmMenuBridge(
        "dmStickerMenuRow", listOf(DM_MENU_STRING, DM_MENU_STRING, "I", DM_MENU_OBJECT), DM_MENU_OBJECT, 9,
        flagsFor("dmStickerMenuRow"),
        // The shop row's own build with a text title: the factory's PRIMARY style, its default size
        // and no second flag. The click is the extension's Function1, cast here for the constructor.
        """
            new-instance v0, ${menu.row}
            move-object v1, p0
            sget-object v2, ${menu.action}->$DM_MENU_ROW_ACTION:${menu.action}
            new-instance v3, ${menu.icon}
            invoke-direct { v3, p2 }, ${menu.icon}-><init>(I)V
            new-instance v4, ${menu.title}
            invoke-direct { v4, p1 }, ${menu.title}-><init>(Ljava/lang/String;)V
            sget-object v5, ${menu.style}->$DM_MENU_STYLE:${menu.style}
            new-instance v6, ${menu.size}
            invoke-direct { v6 }, ${menu.size}-><init>()V
            const/4 v7, 0x0
            move-object v8, p3
            check-cast v8, $DM_MENU_FUNCTION1
            invoke-direct/range { v0 .. v8 }, ${menu.row}-><init>(${menu.rowParameters.joinToString("")})V
            return-object v0
        """,
    ),
    dmMenuBridge(
        "dmStickerMenuDone", emptyList(), DM_MENU_OBJECT, 1, flagsFor("dmStickerMenuDone"),
        """
            sget-object v0, ${menu.unit}
            return-object v0
        """,
    ),
    dmMenuBridge(
        "dismissDmStickerMenu", listOf(DM_MENU_OBJECT), "V", 2, flagsFor("dismissDmStickerMenu"),
        // TikTok's own rows close the menu with no callback after the close.
        """
            check-cast p0, ${menu.builderType}
            sget-object v0, ${menu.action}->$DM_MENU_DISMISS:${menu.action}
            const/4 v1, 0x0
            invoke-interface { p0, v0, v1 }, ${menu.dismiss}
            return-void
        """,
    ),
)

/** Puts the menu bridges in place of StickerGallerySaver's stubs, each keeping its stub's flags as R8 left them. */
internal fun MutableClass.swapDmStickerMenuStubs(menu: DmStickerMenu) {
    val stubs = dmStickerMenuBridges(menu).associate { shape ->
        val stub = methods.singleOrNull {
            it.name == shape.name && it.returnType == shape.returnType &&
                it.parameterTypes.map { type -> type.toString() } == shape.parameterTypes.map { type -> type.toString() }
        } ?: throw PatchException("Downloads: StickerGallerySaver has no ${shape.name} stub")
        if (!AccessFlags.STATIC.isSet(stub.accessFlags)) {
            throw PatchException("Downloads: the ${shape.name} stub isn't static")
        }
        shape.name to stub
    }
    for (bridge in dmStickerMenuBridges(menu) { name -> stubs.getValue(name).accessFlags }) {
        methods.remove(stubs.getValue(bridge.name))
        methods.add(bridge)
    }
}

/**
 * Hands the finished rows, the template and the menu to the extension right before the lambda
 * returns. Straight-line code into two locals that are dead there, so TikTok's rows stay as they
 * were (#264), and at the return's label, so a branch onto it reaches the hook too.
 */
internal fun MutableMethod.addDmStickerMenuHook(menu: DmStickerMenu) {
    val (template, builder) = menu.scratch
    addInstructionsAtControlFlowLabel(
        menu.returnIndex,
        """
            iget-object v$template, p0, ${menu.template}
            iget-object v$builder, p0, ${menu.builder}
            invoke-static { p1, v$template, v$builder }, $SAVER->addDmStickerSaveRow(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)V
        """,
    )
}

internal fun hookDmStickerMenu() {
    val rows = DmStickerMenuRowsFingerprint.method
    val menu = dmStickerMenu(rows, DmStickerMenuRowsFingerprint.classDef) { classDefByOrNull(it) }
    mutableClassDefBy(SAVER).swapDmStickerMenuStubs(menu)
    rows.addDmStickerMenuHook(menu)
    val picker = DmStickerMenuPickerFingerprint.method
    picker.addDmStickerOtherRowsHooks(dmStickerOtherRows(picker, DmStickerMenuRowsFingerprint.classDef.type, menu.builderType))
}

// ---- The other sticker kinds' menus (#470) ----------------------------------------------------

/** The message extra TikTok keeps a sticker's kind in. */
internal const val DM_STICKER_TYPE_EXTRA = "a:sticker_type"

/**
 * The method that picks a sticker message's rows by the sticker's kind: it reads the message as
 * a StickerTemplate, then switches on [DM_STICKER_TYPE_EXTRA]. A static sticker and an animated
 * one get the rows lambda hooked above; a sticker made from a video, a photo sticker, a Tenor GIF
 * and an AI self sticker get a lambda of their own.
 */
internal object DmStickerMenuPickerFingerprint : Fingerprint(
    returnType = DM_MENU_LIST,
    strings = listOf(DM_STICKER_TYPE_EXTRA),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 2
            && method.implementation?.instructions?.any {
                it.opcode == Opcode.CONST_CLASS && it.getReference<TypeReference>()?.type == DM_STICKER_TEMPLATE
            } == true
    },
)

/** One of the picker's returns that hands back another lambda's rows, and the registers the hook reads. */
internal data class DmStickerOtherRows(val returnIndex: Int, val rows: Int, val template: Int, val builder: Int)

/**
 * The picker's returns whose rows another lambda built, one that holds the message's template
 * and the menu too, so Save media can join them (#470: holding a sticker made from a video showed
 * no Save media). The rows lambda's own return already has the hook, and a lambda without the
 * template or the menu has nothing the row can use.
 */
internal fun dmStickerOtherRows(picker: Method, rowsLambda: String, builderType: String): List<DmStickerOtherRows> {
    fun refuse(what: String): Nothing = throw PatchException("Downloads: the DM sticker menu picker $what")
    val code = picker.implementation?.instructions?.toList() ?: refuse("has no code")
    val made = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == rowsLambda }
    if (made < 0) refuse("no longer makes the rows lambda")
    // The list maker the rows lambda goes through, which every other kind's lambda goes through too.
    val builds = code.drop(made).firstNotNullOfOrNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.INVOKE_STATIC }?.getReference<MethodReference>()?.takeIf {
            it.returnType == DM_MENU_LIST && it.parameterTypes.map(CharSequence::toString) == listOf(DM_MENU_FUNCTION1)
        }
    } ?: refuse("no longer builds the rows lambda's list")
    val found = mutableListOf<DmStickerOtherRows>()
    for (index in code.indices) {
        val call = code[index]
        if (call.opcode != Opcode.INVOKE_STATIC || call.getReference<MethodReference>()?.let(::signature) != signature(builds)) continue
        val result = code.getOrNull(index + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction ?: continue
        val back = code.getOrNull(index + 2)?.takeIf { it.opcode == Opcode.RETURN_OBJECT } as? OneRegisterInstruction ?: continue
        if (back.registerA != result.registerA) continue
        val function = registersOf(call).singleOrNull() ?: continue
        val init = (index - 1 downTo 0).firstOrNull { at ->
            code[at].getReference<MethodReference>()?.name == "<init>"
                && (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE)
                && registersOf(code[at]).firstOrNull() == function
        } ?: continue
        val constructor = code[init].getReference<MethodReference>()!!
        if (constructor.definingClass == rowsLambda) continue
        // Each parameter's first register, a long or double taking two.
        val arguments = registersOf(code[init]).drop(1)
        val starts = constructor.parameterTypes.map(CharSequence::toString)
            .runningFold(0) { at, type -> at + if (type == "J" || type == "D") 2 else 1 }
        val parameters = constructor.parameterTypes.map(CharSequence::toString)
        val templateAt = parameters.indexOf(DM_STICKER_TEMPLATE)
        val builderAt = parameters.indexOf(builderType)
        if (templateAt < 0 || builderAt < 0) continue
        val template = arguments[starts[templateAt]]
        val builder = arguments[starts[builderAt]]
        val written = (init + 1 until index + 2).any { at ->
            val register = (code[at] as? OneRegisterInstruction)?.registerA ?: return@any false
            code[at].opcode.setsRegister() && (register == template || register == builder
                || (code[at].opcode.setsWideRegister() && (register + 1 == template || register + 1 == builder)))
        }
        if (written) refuse("writes over the template or the menu before a return")
        if (listOf(result.registerA, template, builder).any { it > 15 }) refuse("keeps its rows past v15")
        found += DmStickerOtherRows(index + 2, result.registerA, template, builder)
    }
    if (found.isEmpty()) refuse("hands back no other sticker kind's rows")
    return found
}

private fun signature(reference: MethodReference) =
    "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    else -> emptyList()
}

/**
 * Hands each other kind's finished rows, its template and the menu to the extension right before
 * the picker returns them, at the return's label so a branch onto it reaches the hook too. The
 * three registers are the ones already there, so nothing of TikTok's changes.
 */
internal fun MutableMethod.addDmStickerOtherRowsHooks(returns: List<DmStickerOtherRows>) {
    for (other in returns.sortedByDescending { it.returnIndex }) {
        addInstructionsAtControlFlowLabel(
            other.returnIndex,
            "invoke-static { v${other.rows}, v${other.template}, v${other.builder} }, " +
                "$SAVER->addDmStickerSaveRow(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }
}
