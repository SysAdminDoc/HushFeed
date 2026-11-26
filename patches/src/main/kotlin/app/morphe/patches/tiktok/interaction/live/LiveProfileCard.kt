/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val LIVE_PROFILE_CARD_EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveProfileCard;"
internal const val COMPONENT_LAYOUT_CONFIG = "Lwebcast/api/room/ComponentLayoutConfig;"
private const val ENUM = "Ljava/lang/Enum;"
private const val VIEW = "Landroid/view/View;"

/**
 * The card modules Hide rows on LIVE profile cards offers (#487), by TikTok's names for them:
 * constants of the card's module enum (X.10Zf on 48.0.2), whose names TikTok keeps since its
 * containers log them ("is_" + name + "_show"). Each one has to be a constant of that enum on a
 * declared build, or the patch fails and says which.
 */
internal val LIVE_CARD_ROWS = listOf(
    "LIVE_EVENT", "LIVE_PRODUCTS", "ECOM", "SHOP_EXPERIENCE_SCORE",
    "GROUP_CHAT", "BULLETIN_BOARD", "GIFT_GALLERY", "FEST_HONORS",
)

/**
 * A container of the LIVE profile card (X.1A3S and its three subclasses X.1A3P, X.1A3Q and X.1A3R
 * on 47.9.3): its bind takes the user, the cells' data by cell and the server's layout config,
 * asks each cell to bind, and lays out only the cells that answer yes.
 */
internal fun Method.isCardContainerBind() = returnType == "Z" && AccessFlags.STATIC.isSet(accessFlags).not() &&
    parameterTypes.map { it.toString() } == listOf(LIVE_USER, "Ljava/util/Map;", COMPONENT_LAYOUT_CONFIG)

/** One cell bind in a container: the `move-result` of its answer, the cell's register and the answer's. */
internal data class CardCellBind(val moveResult: Int, val cell: Int, val result: Int, val bind: MethodReference)

/** Every cell bind in [method]: a virtual `(User, Object)Z` call whose answer moves straight into a register. */
internal fun cardCellBinds(method: Method): List<CardCellBind> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        val call = code[at]
        if (call.opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val target = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (target.returnType != "Z" ||
            target.parameterTypes.map { it.toString() } != listOf(LIVE_USER, "Ljava/lang/Object;")
        ) return@mapNotNull null
        val move = code.getOrNull(at + 1)
        if (move?.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        CardCellBind(at + 1, (call as FiveRegisterInstruction).registerC, (move as OneRegisterInstruction).registerA, target)
    }
}

/**
 * What the extension reads off a card cell: the cells' base class, its method answering the
 * cell's module and that module enum's type, and its field holding the cell's own view.
 */
internal data class LiveCardRoute(val cellBase: String, val component: String, val componentType: String, val view: String)

/**
 * The route from the card's containers: the one class every cell bind is called on, its single
 * no-argument method answering an enum, that enum holding every [LIVE_CARD_ROWS] name, and the
 * base's single View field. A missing step fails the patch and names it.
 */
internal fun liveCardRoute(containers: List<Method>, classOf: (String) -> ClassDef?): LiveCardRoute {
    if (containers.isEmpty()) throw PatchException("LIVE controls: the profile card's containers weren't found.")
    val binds = containers.flatMap { cardCellBinds(it) }
    val bases = binds.map { it.bind.definingClass }.toSet()
    if (binds.isEmpty() || bases.size != 1) {
        throw PatchException("LIVE controls: the profile card's cell bind wasn't found: $bases")
    }
    val base = classOf(bases.single()) ?: throw PatchException("LIVE controls: the profile card's cell class wasn't found.")
    val components = base.methods.filter {
        it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags) && classOf(it.returnType)?.superclass == ENUM
    }
    val component = components.singleOrNull()
        ?: throw PatchException("LIVE controls: the profile card cells' module wasn't found.")
    val modules = classOf(component.returnType)!!
    val names = modules.methods.filter { it.name == "<clinit>" }.flatMap { method ->
        method.implementation?.instructions?.toList()?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        } ?: emptyList()
    }.toSet()
    val missing = LIVE_CARD_ROWS.filter { it !in names }
    if (missing.isNotEmpty()) {
        throw PatchException("LIVE controls: the profile card has no ${missing.joinToString()} module.")
    }
    val view = base.fields.singleOrNull { it.type == VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: throw PatchException("LIVE controls: the profile card cells' view wasn't found.")
    return LiveCardRoute(
        base.type,
        "${base.type}->${component.name}()${component.returnType}",
        component.returnType,
        "${base.type}->${view.name}:$VIEW",
    )
}

/**
 * Hands each cell's answer in [this] container bind to the extension with the cell, right after
 * the answer moves into its register, and puts the extension's answer in its place. Straight-line,
 * and only the answer's register is written.
 */
internal fun MutableMethod.hookCardCellBinds(): Int {
    val binds = cardCellBinds(this)
    binds.asReversed().forEach { bind ->
        if (bind.cell > 15 || bind.result > 15 || bind.cell == bind.result) {
            throw PatchException("LIVE controls: a profile card container's cell bind doesn't fit the hook.")
        }
        addInstructions(
            bind.moveResult + 1,
            """
                invoke-static { v${bind.cell}, v${bind.result} }, $LIVE_PROFILE_CARD_EXTENSION->keep(Ljava/lang/Object;I)Z
                move-result v${bind.result}
            """,
        )
    }
    return binds.size
}

/** Every card container bind in the app. */
internal fun cardContainerBinds(): List<Pair<String, Method>> {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { definition ->
        definition.methods.filter { it.isCardContainerBind() && cardCellBinds(it).isNotEmpty() }
            .forEach { found += definition.type to it }
    }
    return found
}

/** Hooks every card container and writes the two bridges the extension reads a cell through. */
internal fun hookLiveProfileCardRows() {
    val containers = cardContainerBinds()
    val route = liveCardRoute(containers.map { it.second }) { classDefByOrNull(it) }
    containers.forEach { (type, method) ->
        mutableClassDefBy(type).findMutableMethodOf(method).hookCardCellBinds()
    }
    installLiveProfileCardBridges(route)
}

/** Writes LiveProfileCard.componentOf and LiveProfileCard.cellView along [route]. */
internal fun installLiveProfileCardBridges(route: LiveCardRoute) {
    val extension = mutableClassDefBy(LIVE_PROFILE_CARD_EXTENSION)
    fun bridge(name: String) = extension.methods.singleOrNull { it.name == name }
        ?: throw PatchException("LIVE controls: missing profile card bridge $name")
    val componentOf: MutableMethod = bridge("componentOf")
    val cellView: MutableMethod = bridge("cellView")
    listOf(componentOf, cellView).forEach {
        if (it.returnType != "Ljava/lang/Object;" || it.parameterTypes.map { type -> type.toString() } != listOf("Ljava/lang/Object;")) {
            throw PatchException("LIVE controls: the profile card bridge ${it.name} has the wrong shape")
        }
    }
    val component = componentBridge(componentOf, route)
    val view = cellViewBridge(cellView, route)
    extension.methods.remove(componentOf)
    extension.methods.remove(cellView)
    extension.methods.add(component)
    extension.methods.add(view)
}

/** [stub] answering a card cell's module along [route], and null for anything that isn't a cell. */
internal fun componentBridge(stub: MutableMethod, route: LiveCardRoute): MutableMethod =
    stub.cloneMutable(additionalRegisters = 2).apply {
        addInstructions(
            0,
            """
                instance-of v0, p0, ${route.cellBase}
                if-eqz v0, :none
                move-object v1, p0
                check-cast v1, ${route.cellBase}
                invoke-virtual { v1 }, ${route.component}
                move-result-object v0
                return-object v0
                :none
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }

/** [stub] answering a card cell's own view along [route], and null for anything that isn't a cell. */
internal fun cellViewBridge(stub: MutableMethod, route: LiveCardRoute): MutableMethod =
    stub.cloneMutable(additionalRegisters = 2).apply {
        addInstructions(
            0,
            """
                instance-of v0, p0, ${route.cellBase}
                if-eqz v0, :none
                move-object v1, p0
                check-cast v1, ${route.cellBase}
                iget-object v0, v1, ${route.view}
                return-object v0
                :none
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }
