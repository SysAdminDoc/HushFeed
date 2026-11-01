/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val STICKER_RECENTS = "Lapp/morphe/extension/tiktok/comment/StickerRecents;"
private const val RECENT_STICKER_ITEM = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"

/**
 * The recently used list's add: takes a sticker, returns nothing and caps the list at the page
 * size config's refreshCount (14gj.LJ on TikTok 47.5.3). The saved
 * stickers refresh reads refreshCount too, but takes no sticker.
 */
internal object RecentStickerAddFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(RECENT_STICKER_ITEM),
    custom = { method, _ ->
        method.implementation?.instructions?.any { it.getReference<FieldReference>()?.isStickerRefreshCount() == true } == true
    },
)

/**
 * A new recently used list going out: setValue(Object) on the flow just read from the store's
 * holder field, right before a return-void. [flow] and [list] are the call's two registers.
 */
internal data class RecentStickersWrite(val returnIndex: Int, val flow: Int, val list: Int, val holder: String)

private fun FieldReference.recentDescriptor() = "$definingClass->$name:$type"

internal fun Method.recentStickersWrites(): List<RecentStickersWrite> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.mapNotNull { index ->
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL || instructions.getOrNull(index + 1)?.opcode != Opcode.RETURN_VOID) {
            return@mapNotNull null
        }
        val setValue = call.getReference<MethodReference>() ?: return@mapNotNull null
        if (setValue.name != "setValue" || setValue.returnType != "V" ||
            setValue.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Object;")
        ) return@mapNotNull null
        val registers = call as FiveRegisterInstruction
        if (registers.registerCount != 2) return@mapNotNull null
        // The flow comes from the holder at most a couple of instructions earlier.
        val read = instructions.subList(maxOf(0, index - 3), index).lastOrNull {
            (it as? OneRegisterInstruction)?.registerA == registers.registerC
        } ?: return@mapNotNull null
        if (read.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val holder = read.getReference<FieldReference>()?.takeIf { it.type == setValue.definingClass } ?: return@mapNotNull null
        RecentStickersWrite(index + 1, registers.registerC, registers.registerD, holder.recentDescriptor())
    }
}

/** The add's one write of the list, the one that gets the save hook. */
internal fun Method.recentStickersAddWrite(): RecentStickersWrite =
    recentStickersWrites().singleOrNull()
        ?: throw PatchException("Keep recent stickers: ${recentStickersWrites().size} list writes in the add, expected 1")

/**
 * The store's reset: the other `()V` of the add's class that writes the same holder. The app
 * calls it the first time the store is used in each process and on every sign in, sign out and
 * switch, which is what leaves Recently used empty after a restart.
 */
internal fun <M : Method> recentStickersReset(methods: Iterable<M>, add: Method, holder: String): M {
    val resets = methods.filter {
        it.name != add.name && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            it.recentStickersWrites().any { write -> write.holder == holder }
    }
    return resets.singleOrNull()
        ?: throw PatchException("Keep recent stickers: ${resets.size} resets beside ${add.definingClass}->${add.name}, expected 1")
}

private fun MutableMethod.addRecentStickersHook(write: RecentStickersWrite, register: Int, hook: String) {
    if (implementation?.instructions?.toList()?.getOrNull(write.returnIndex)?.opcode != Opcode.RETURN_VOID) {
        throw PatchException("Keep recent stickers: $name has no return after its list write")
    }
    addInstructionsAtControlFlowLabel(
        write.returnIndex,
        "invoke-static/range { v$register .. v$register }, $STICKER_RECENTS->$hook(Ljava/lang/Object;)V",
    )
}

/** Hands each new list to StickerRecents.save as a sticker goes on it. */
internal fun MutableMethod.saveRecentStickers(write: RecentStickersWrite) = addRecentStickersHook(write, write.list, "save")

/** Hands the emptied flow to StickerRecents.restore, which puts this account's list back. */
internal fun MutableMethod.restoreRecentStickers(write: RecentStickersWrite) = addRecentStickersHook(write, write.flow, "restore")

/**
 * The basic panel's Saved page building its rows: the collector that makes the Saved heading and a
 * cell per saved sticker, then hands the list to the page (AgS193S0200000_27_I1.emit$0 on TikTok
 * 47.5.3).
 */
internal object RecentStickerRowsFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Ljava/lang/Object;", "L"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) && method.recentStickerRows() != null },
)

/**
 * Where the Saved page hands its rows on: the page's one `(List)V` call, with the page and the
 * list in [page] and [rows], and the heading and sticker cell classes the rows are built from.
 */
internal data class RecentStickerRows(
    val callIndex: Int,
    val page: Int,
    val rows: Int,
    val pageType: String,
    val title: String,
    val item: String,
)

internal fun Method.recentStickerRows(): RecentStickerRows? {
    val instructions = implementation?.instructions?.toList() ?: return null
    if (instructions.none { it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == FAVOURITE_STICKER_PAGE }) {
        return null
    }
    fun builds(parameter: String) = instructions.filter { it.opcode == Opcode.INVOKE_DIRECT }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter { it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == listOf(parameter) }
        .map { it.definingClass }
        .distinct()
    val item = builds(RECENT_STICKER_ITEM).singleOrNull() ?: return null
    val title = builds("Ljava/lang/String;").singleOrNull() ?: return null
    val (index, call) = instructions.withIndex().filter { (_, instruction) ->
        val reference = instruction.getReference<MethodReference>()
        instruction.opcode == Opcode.INVOKE_VIRTUAL && reference?.returnType == "V" &&
            reference.parameterTypes.map { type -> type.toString() } == listOf("Ljava/util/List;")
    }.singleOrNull() ?: return null
    val registers = call as FiveRegisterInstruction
    if (registers.registerCount != 2) return null
    val pageType = call.getReference<MethodReference>()?.definingClass ?: return null
    return RecentStickerRows(index, registers.registerC, registers.registerD, pageType, title, item)
}

/** How a bridge reaches Recently used from the page: its store getter, the store's flow, the flow's value. */
internal data class RecentStickerStore(
    val getter: String,
    val getterInvoke: String,
    val recentFlow: String,
    val storeInvoke: String,
    val value: String,
    val flowInvoke: String,
)

private fun ClassDef.recentRowInvoke() = if (AccessFlags.INTERFACE.isSet(accessFlags)) "invoke-interface" else "invoke-virtual"

/**
 * The page's one public getter whose type declares getRecentStickersFlow() (the IM sticker
 * store), so the row reads the same list a sent sticker goes on.
 */
internal fun recentStickerStore(page: ClassDef, classDef: (String) -> ClassDef?): RecentStickerStore {
    fun ClassDef.recentFlow() = methods.singleOrNull { it.name == "getRecentStickersFlow" && it.parameterTypes.isEmpty() }
    val getters = page.methods.filter {
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.isEmpty() && classDef(it.returnType)?.recentFlow() != null
    }
    val getter = getters.singleOrNull()
        ?: throw PatchException("Keep recent stickers: ${getters.size} sticker store getters on ${page.type}, expected 1")
    val store = classDef(getter.returnType)!!
    val recentFlow = store.recentFlow()!!
    val flow = classDef(recentFlow.returnType)
        ?: throw PatchException("Keep recent stickers: no class ${recentFlow.returnType} for the recent stickers flow")
    return RecentStickerStore(
        "${page.type}->${getter.name}()${getter.returnType}",
        page.recentRowInvoke(),
        "${store.type}->getRecentStickersFlow()${recentFlow.returnType}",
        store.recentRowInvoke(),
        "${flow.type}->getValue()Ljava/lang/Object;",
        flow.recentRowInvoke(),
    )
}

private fun recentRowBridge(name: String, parameter: String, accessFlags: Int, smali: String) =
    MutableMethod(
        ImmutableMethod(
            STICKER_RECENTS, name, listOf(ImmutableMethodParameter(parameter, null, null)), "Ljava/lang/Object;", accessFlags,
            null, null, ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, smali) }

/** The bodies StickerRecents' row stubs get, by name, each building what the Saved page builds itself. */
internal fun recentRowBridges(
    rows: RecentStickerRows,
    store: RecentStickerStore,
    flagsFor: (String) -> Int = { AccessFlags.STATIC.value },
): List<MutableMethod> = listOf(
    recentRowBridge(
        "recentStickers", "Ljava/lang/Object;", flagsFor("recentStickers"),
        """
            check-cast p0, ${rows.pageType}
            ${store.getterInvoke} { p0 }, ${store.getter}
            move-result-object v0
            ${store.storeInvoke} { v0 }, ${store.recentFlow}
            move-result-object v0
            ${store.flowInvoke} { v0 }, ${store.value}
            move-result-object v0
            return-object v0
        """,
    ),
    recentRowBridge(
        "recentTitle", "Ljava/lang/String;", flagsFor("recentTitle"),
        """
            new-instance v0, ${rows.title}
            invoke-direct { v0, p0 }, ${rows.title}-><init>(Ljava/lang/String;)V
            return-object v0
        """,
    ),
    recentRowBridge(
        "recentItem", "Ljava/lang/Object;", flagsFor("recentItem"),
        """
            check-cast p0, $RECENT_STICKER_ITEM
            new-instance v0, ${rows.item}
            invoke-direct { v0, p0 }, ${rows.item}-><init>($RECENT_STICKER_ITEM)V
            return-object v0
        """,
    ),
)

/** Puts the row bridges in place of StickerRecents' stubs, each keeping its stub's flags as R8 left them. */
internal fun MutableClass.swapRecentRowStubs(rows: RecentStickerRows, store: RecentStickerStore) {
    val stubs = recentRowBridges(rows, store).associate { shape ->
        val stub = methods.singleOrNull {
            it.name == shape.name && it.returnType == shape.returnType &&
                it.parameterTypes.map { type -> type.toString() } == shape.parameterTypes.map { type -> type.toString() }
        } ?: throw PatchException("Keep recent stickers: StickerRecents has no ${shape.name} stub")
        if (!AccessFlags.STATIC.isSet(stub.accessFlags)) {
            throw PatchException("Keep recent stickers: the ${shape.name} stub isn't static")
        }
        shape.name to stub
    }
    for (bridge in recentRowBridges(rows, store) { name -> stubs.getValue(name).accessFlags }) {
        methods.remove(stubs.getValue(bridge.name))
        methods.add(bridge)
    }
}

/** Hands the page and its rows to StickerRecents.addRow right before the page takes them. */
internal fun MutableMethod.addRecentStickerRow(rows: RecentStickerRows) {
    if (rows.page > 15 || rows.rows > 15) {
        throw PatchException("Keep recent stickers: the rows call uses v${rows.page} and v${rows.rows}, past v15")
    }
    addInstructionsAtControlFlowLabel(
        rows.callIndex,
        "invoke-static { v${rows.page}, v${rows.rows} }, $STICKER_RECENTS->addRow(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
}

/** The bridges call the heading and cell constructors from the extension's package, so both must be public. */
private fun requirePublicRowClass(classDef: ClassDef?, type: String, parameter: String) {
    val init = classDef?.methods?.singleOrNull { it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf(parameter) }
    if (classDef == null || init == null || !AccessFlags.PUBLIC.isSet(classDef.accessFlags) || !AccessFlags.PUBLIC.isSet(init.accessFlags)) {
        throw PatchException("Keep recent stickers: $type or its <init>($parameter) isn't public")
    }
}

@Suppress("unused")
val keepRecentStickersPatch = bytecodePatch(
    name = "Keep recent stickers",
    description = "Adds Recently used above Saved in the comment sticker panel, with the last stickers " +
        "you sent, and keeps it when you close and reopen the app. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Comments")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val add = RecentStickerAddFingerprint.method
        val addWrite = add.recentStickersAddWrite()
        val reset = recentStickersReset(RecentStickerAddFingerprint.classDef.methods, add, addWrite.holder)
        val resetWrite = reset.recentStickersWrites().singleOrNull { it.holder == addWrite.holder }
            ?: throw PatchException("Keep recent stickers: the reset writes its list more than once")

        val rowsMethod = RecentStickerRowsFingerprint.method
        val rows = rowsMethod.recentStickerRows()
            ?: throw PatchException("Keep recent stickers: the Saved page's rows changed shape")
        val page = classDefByOrNull(rows.pageType)
            ?: throw PatchException("Keep recent stickers: no class ${rows.pageType} for the Saved page")
        val store = recentStickerStore(page) { classDefByOrNull(it) }
        requirePublicRowClass(classDefByOrNull(rows.title), rows.title, "Ljava/lang/String;")
        requirePublicRowClass(classDefByOrNull(rows.item), rows.item, RECENT_STICKER_ITEM)

        add.saveRecentStickers(addWrite)
        reset.restoreRecentStickers(resetWrite)
        mutableClassDefBy(STICKER_RECENTS).swapRecentRowStubs(rows, store)
        rowsMethod.addRecentStickerRow(rows)
        registerForBuildDetails("enableStickerRecents")
    }
}
