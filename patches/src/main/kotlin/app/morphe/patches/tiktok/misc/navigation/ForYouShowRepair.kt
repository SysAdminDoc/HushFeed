/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.interaction.cleardisplay.FeedPagerPageSelectedFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val REPAIR = "Lapp/morphe/extension/tiktok/navigation/ForYouShowRepair;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val STRING = "Ljava/lang/String;"
private const val LISTENERS = "Ljava/util/concurrent/CopyOnWriteArrayList;"
private const val VIEW = "Landroid/view/View;"

/** The page's Hox tag, which For You's own show looks its container up by. */
private const val FOR_YOU_TAG = "For You"

/**
 * What the For You show repair reads and calls (#322, #326). Every member is read off For You's
 * own `onNodeShow` and Hox's switch, so a rename on a new build moves with it.
 *
 * - [fragmentBase] with [activityOf]: the fragment class For You's show reads its activity
 *   through, which also has getView, isResumed and isHidden.
 * - [hoxOf] and [containerTagOf]: the activity's Hox and the tag of a page's container, the key
 *   For You's show reads its Bundle by.
 * - [panel] and [hintOwner]: For You's list panel and the class whose user-visible hint the show
 *   turns on.
 * - [nodeShow]: For You's show itself.
 * - [nodeOf], [container], [current], [tag] and [shown]: a page's node, its container (Home),
 *   the container's current child, a node's tag and the container's shown flag, as Hox's switch
 *   reads them.
 * - [dispatch] with [visibleListeners] and [switchListeners]: the container's own switch, which
 *   hides the current child, makes the named one current and shows it.
 */
internal class ForYouShowCalls(
    val fragmentBase: String,
    val activityOf: MethodReference,
    val hoxOf: MethodReference,
    val containerTagOf: MethodReference,
    val panel: FieldReference,
    val hintOwner: String,
    val nodeShow: MethodReference,
    val nodeOf: MethodReference,
    val container: FieldReference,
    val current: FieldReference,
    val tag: MethodReference,
    val shown: FieldReference,
    val dispatch: MethodReference,
    val visibleListeners: FieldReference,
    val switchListeners: FieldReference,
)

private fun showMissing(what: String): Nothing =
    throw PatchException("For You show repair: $what, so a For You page TikTok never showed stays frozen (#322).")

private fun MethodReference.takes(vararg types: String) = parameterTypes.map(CharSequence::toString) == types.toList()

private fun List<Instruction>.call(opcode: Opcode, from: Int = 0, test: (MethodReference) -> Boolean): Int =
    (from until size).firstOrNull { this[it].opcode == opcode && this[it].getReference<MethodReference>()?.let(test) == true } ?: -1

private fun List<Instruction>.field(opcode: Opcode, from: Int = 0, test: (FieldReference) -> Boolean): Int =
    (from until size).firstOrNull { this[it].opcode == opcode && this[it].getReference<FieldReference>()?.let(test) == true } ?: -1

private fun Instruction.method() = getReference<MethodReference>()!!
private fun Instruction.fieldRef() = getReference<FieldReference>()!!

private fun ClassDef.code(reference: MethodReference): List<Instruction> =
    methods.firstOrNull { it.name == reference.name && it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString) }
        ?.implementation?.instructions?.toList() ?: showMissing("${reference.definingClass} has no ${reference.name} with code")

/** The method [name] taking [parameters] that [type] or one of its superclasses declares. */
private fun declaredMethod(classBy: (String) -> ClassDef?, type: String, name: String, parameters: List<String>): Method? {
    var at: String? = type
    while (at != null) {
        val classDef = classBy(at) ?: return null
        classDef.methods.firstOrNull { it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters }?.let { return it }
        at = classDef.superclass
    }
    return null
}

private fun extends(classBy: (String) -> ClassDef?, type: String, ancestor: String): Boolean {
    var at: String? = type
    while (at != null) {
        if (at == ancestor) return true
        at = classBy(at)?.superclass
    }
    return false
}

/** The register [code] last loads before [at] into [register], when that load is a string. */
private fun List<Instruction>.stringIn(register: Int, at: Int): String? =
    (at - 1 downTo 0).firstOrNull { this[it].opcode.setsRegister() && (this[it] as? OneRegisterInstruction)?.registerA == register }
        ?.let { this[it].getReference<StringReference>()?.string }

/** Reads [ForYouShowCalls] off For You's own show and Hox's switch, with [classBy] to look classes up. */
internal fun forYouShowCalls(classBy: (String) -> ClassDef?): ForYouShowCalls {
    val fragment = classBy(FOR_YOU_FRAGMENT) ?: showMissing("there is no $FOR_YOU_FRAGMENT")
    val show = fragment.methods.firstOrNull { it.name == "onNodeShow" && it.parameterTypes.map(CharSequence::toString) == listOf(BUNDLE) && it.returnType == "V" }
        ?: showMissing("For You no longer has its own onNodeShow(Bundle)")
    val steps = show.implementation?.instructions?.toList() ?: showMissing("For You's show has no code")

    val activityAt = steps.call(Opcode.INVOKE_VIRTUAL) { it.name == "getActivity" && it.takes() && it.returnType.startsWith("L") }
    if (activityAt < 0) missing("For You's show no longer reads its activity")
    val activityOf = steps[activityAt].method()
    val hoxAt = steps.call(Opcode.INVOKE_STATIC, activityAt) { it.definingClass == HOX && it.takes(activityOf.returnType) && it.returnType == HOX }
    if (hoxAt < 0) missing("For You's show no longer asks for the activity's Hox")
    val tagAt = steps.call(Opcode.INVOKE_VIRTUAL, hoxAt) { it.definingClass == HOX && it.takes(STRING) && it.returnType == STRING }
    if (tagAt < 0) missing("For You's show no longer asks Hox for its container's tag")
    if (steps.stringIn((steps[tagAt] as FiveRegisterInstruction).registerD, tagAt) != FOR_YOU_TAG) {
        missing("For You's show no longer asks for the container of \"$FOR_YOU_TAG\"")
    }
    val keyAt = tagAt + 1
    if (steps.getOrNull(keyAt)?.opcode != Opcode.MOVE_RESULT_OBJECT) missing("For You's show no longer keeps its container's tag")
    val key = (steps[keyAt] as OneRegisterInstruction).registerA
    val readsKey = steps.call(Opcode.INVOKE_VIRTUAL, keyAt) { it.name == "getBoolean" && it.takes(STRING) && it.returnType == "Z" }
    if (readsKey < 0 || (steps[readsKey] as FiveRegisterInstruction).registerD != key) {
        missing("For You's show no longer reads its Bundle by its container's tag")
    }

    // The hint: setUserVisibleHint on For You's own panel field, read just before the call.
    val hintAt = (1 until steps.size).firstOrNull { at ->
        val call = steps[at].takeIf { it.opcode == Opcode.INVOKE_VIRTUAL }?.getReference<MethodReference>()
        val read = steps[at - 1].takeIf { it.opcode == Opcode.IGET_OBJECT }?.getReference<FieldReference>()
        call != null && read != null && call.name == "setUserVisibleHint" && call.takes("Z") && call.returnType == "V" &&
            read.definingClass == FOR_YOU_FRAGMENT &&
            (steps[at] as FiveRegisterInstruction).registerC == (steps[at - 1] as OneRegisterInstruction).registerA
    } ?: missing("For You's show no longer turns its list panel's hint on")
    val panel = steps[hintAt - 1].fieldRef()
    val hintOwner = steps[hintAt].method().definingClass
    if (!extends(classBy, panel.type, hintOwner)) missing("For You's panel is no longer a kind of $hintOwner")
    declaredMethod(classBy, hintOwner, "getUserVisibleHint", emptyList())?.takeIf { it.returnType == "Z" }
        ?: missing("$hintOwner no longer reads its hint back")

    val hox = classBy(HOX) ?: missing("there is no $HOX")
    val switchMethod = hox.methods.singleOrNull {
        it.parameterTypes.map(CharSequence::toString) == listOf(BUNDLE, STRING, "Z") && it.returnType == "V"
    } ?: missing("Hox no longer has one switch by tag")
    val switch = switchMethod.implementation?.instructions?.toList() ?: missing("Hox's switch has no code")
    val nodeAt = switch.call(Opcode.INVOKE_VIRTUAL) {
        it.definingClass == HOX && it.takes(STRING) && it.returnType.startsWith("L") && it.returnType != STRING && it.returnType != HOX
    }
    if (nodeAt < 0) missing("Hox's switch no longer looks a node up by tag")
    val nodeOf = switch[nodeAt].method()
    val node = nodeOf.returnType
    val containerAt = switch.field(Opcode.IGET_OBJECT, nodeAt) { it.definingClass == node && it.type.startsWith("L") && it.type != node }
    if (containerAt < 0) missing("Hox's switch no longer reads a node's container")
    val container = switch[containerAt].fieldRef()
    val group = container.type
    val groupClass = classBy(group) ?: missing("there is no $group")
    if (groupClass.superclass != node) missing("$group is no longer a kind of $node")
    val currentAt = switch.field(Opcode.IGET_OBJECT) { it.definingClass == group && it.type == node }
    if (currentAt < 0) missing("Hox's switch no longer reads a container's current child")
    val current = switch[currentAt].fieldRef()
    val tagCallAt = switch.call(Opcode.INVOKE_VIRTUAL) { it.definingClass == node && it.takes() && it.returnType == STRING }
    if (tagCallAt < 0) missing("Hox's switch no longer reads a node's tag")
    val dispatchAt = switch.call(Opcode.INVOKE_VIRTUAL) {
        it.definingClass == group && it.takes(STRING, BUNDLE, LISTENERS, LISTENERS) && it.returnType == "V"
    }
    if (dispatchAt < 0) missing("Hox's switch no longer calls a container's own switch")
    val dispatch = switch[dispatchAt] as FiveRegisterInstruction
    fun listenersInto(register: Int): FieldReference = (dispatchAt - 1 downTo 0).firstOrNull { at ->
        switch[at].opcode.setsRegister() && (switch[at] as? OneRegisterInstruction)?.registerA == register
    }?.takeIf { switch[it].opcode == Opcode.IGET_OBJECT }?.let { switch[it].fieldRef() }
        ?.takeIf { it.definingClass == HOX && it.type == LISTENERS }
        ?: missing("Hox's switch no longer hands its own listener lists to the container's switch")
    val visibleListeners = listenersInto(dispatch.registerF)
    val switchListeners = listenersInto(dispatch.registerG)

    // The container's switch hides the current child, stores the new one, then shows it. The
    // show and the hide both write the container's shown flag.
    val dispatchCode = groupClass.code(dispatch.method())
    val storeAt = dispatchCode.field(Opcode.IPUT_OBJECT) { it.definingClass == group && it.name == current.name }
    if (storeAt < 0) missing("the container's switch no longer stores its current child")
    fun visibility(at: Int): FieldReference {
        if (at < 0) missing("the container's switch no longer hides and shows around the store")
        val code = groupClass.code(dispatchCode[at].method())
        val flagAt = code.field(Opcode.IPUT_BOOLEAN) { it.definingClass == group }
        if (flagAt < 0) missing("the container's show or hide no longer sets its shown flag")
        return code[flagAt].fieldRef()
    }
    fun isOwnBundleCall(reference: MethodReference) = reference.definingClass == group && reference.takes(BUNDLE) && reference.returnType == "V"
    val hideAt = (storeAt - 1 downTo 0).firstOrNull {
        dispatchCode[it].opcode == Opcode.INVOKE_VIRTUAL && isOwnBundleCall(dispatchCode[it].method())
    } ?: -1
    val showAt = dispatchCode.call(Opcode.INVOKE_VIRTUAL, storeAt) { isOwnBundleCall(it) }
    val shown = visibility(showAt)
    if (visibility(hideAt) != shown) missing("the container's show and hide no longer set the same flag")

    val calls = ForYouShowCalls(
        fragmentBase = activityOf.definingClass,
        activityOf = activityOf,
        hoxOf = steps[hoxAt].method(),
        containerTagOf = steps[tagAt].method(),
        panel = panel,
        hintOwner = hintOwner,
        nodeShow = show,
        nodeOf = nodeOf,
        container = container,
        current = current,
        tag = switch[tagCallAt].method(),
        shown = shown,
        dispatch = dispatch.method(),
        visibleListeners = visibleListeners,
        switchListeners = switchListeners,
    )
    calls.requirePublic(classBy)
    return calls
}

private fun missing(what: String): Nothing = showMissing(what)

/**
 * The repair runs in the extension, outside every class it reaches into, so each class and
 * member has to be public. A private one would only fail once the app runs.
 */
private fun ForYouShowCalls.requirePublic(classBy: (String) -> ClassDef?) {
    fun isPublic(flags: Int) = flags and AccessFlags.PUBLIC.value != 0
    val classes = listOf(fragmentBase, FOR_YOU_FRAGMENT, activityOf.returnType, HOX, hintOwner, container.definingClass, container.type)
    classes.forEach { type ->
        if (classBy(type)?.accessFlags?.let(::isPublic) != true) missing("$type is not a public class")
    }
    if (!extends(classBy, FOR_YOU_FRAGMENT, fragmentBase)) missing("$FOR_YOU_FRAGMENT is no longer a kind of $fragmentBase")
    val methods = listOf(activityOf, hoxOf, containerTagOf, nodeShow, nodeOf, tag, dispatch).map {
        Triple(it.definingClass, it.name, it.parameterTypes.map(CharSequence::toString))
    } + listOf(
        Triple(fragmentBase, "getView", emptyList()),
        Triple(fragmentBase, "isResumed", emptyList()),
        Triple(fragmentBase, "isHidden", emptyList()),
        Triple(hintOwner, "getUserVisibleHint", emptyList()),
    )
    methods.forEach { (owner, name, parameters) ->
        val method = declaredMethod(classBy, owner, name, parameters)
        if (method == null || !isPublic(method.accessFlags)) missing("$owner->$name is not a public method")
    }
    listOf(panel, container, current, shown, visibleListeners, switchListeners).forEach { reference ->
        val field = classBy(reference.definingClass)?.fields?.firstOrNull { it.name == reference.name && it.type == reference.type }
        if (field == null || !isPublic(field.accessFlags)) missing("${reference.definingClass}->${reference.name} is not a public field")
    }
}

private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun FieldReference.smali() = "$definingClass->$name:$type"

/** One extension stub's body: the registers it needs besides its parameters, and its smali. */
internal class ShowStub(val locals: Int, val smali: String)

/** The body the patch writes into the extension's stub [name]. */
internal fun ForYouShowCalls.stub(name: String): ShowStub = when (name) {
    "readView" -> ShowStub(1, """
        check-cast p0, $fragmentBase
        invoke-virtual { p0 }, $fragmentBase->getView()$VIEW
        move-result-object v0
        return-object v0
    """)
    "readResumed" -> ShowStub(1, """
        check-cast p0, $fragmentBase
        invoke-virtual { p0 }, $fragmentBase->isResumed()Z
        move-result v0
        return v0
    """)
    "readHidden" -> ShowStub(1, """
        check-cast p0, $fragmentBase
        invoke-virtual { p0 }, $fragmentBase->isHidden()Z
        move-result v0
        return v0
    """)
    "readPanel" -> ShowStub(1, """
        check-cast p0, $FOR_YOU_FRAGMENT
        iget-object v0, p0, ${panel.smali()}
        return-object v0
    """)
    "readHint" -> ShowStub(1, """
        check-cast p0, $hintOwner
        invoke-virtual { p0 }, $hintOwner->getUserVisibleHint()Z
        move-result v0
        return v0
    """)
    "readActivity" -> ShowStub(1, """
        check-cast p0, $fragmentBase
        invoke-virtual { p0 }, ${activityOf.smali()}
        move-result-object v0
        return-object v0
    """)
    "readHox" -> ShowStub(1, """
        check-cast p0, ${activityOf.returnType}
        invoke-static { p0 }, ${hoxOf.smali()}
        move-result-object v0
        return-object v0
    """)
    "readContainerTag" -> ShowStub(1, """
        check-cast p0, $HOX
        invoke-virtual { p0, p1 }, ${containerTagOf.smali()}
        move-result-object v0
        return-object v0
    """)
    "readNode" -> ShowStub(1, """
        check-cast p0, $HOX
        invoke-virtual { p0, p1 }, ${nodeOf.smali()}
        move-result-object v0
        return-object v0
    """)
    "readContainer" -> ShowStub(1, """
        check-cast p0, ${container.definingClass}
        iget-object v0, p0, ${container.smali()}
        return-object v0
    """)
    "readCurrentChild" -> ShowStub(1, """
        check-cast p0, ${current.definingClass}
        iget-object v0, p0, ${current.smali()}
        return-object v0
    """)
    "readShown" -> ShowStub(1, """
        check-cast p0, ${shown.definingClass}
        iget-boolean v0, p0, ${shown.smali()}
        return v0
    """)
    "readTag" -> ShowStub(1, """
        check-cast p0, ${tag.definingClass}
        invoke-virtual { p0 }, ${tag.smali()}
        move-result-object v0
        return-object v0
    """)
    "callHomeSwitch" -> ShowStub(2, """
        check-cast p0, $HOX
        check-cast p1, ${dispatch.definingClass}
        iget-object v0, p0, ${visibleListeners.smali()}
        iget-object v1, p0, ${switchListeners.smali()}
        invoke-virtual { p1, p2, p3, v0, v1 }, ${dispatch.smali()}
        return-void
    """)
    "callNodeShow" -> ShowStub(0, """
        check-cast p0, $FOR_YOU_FRAGMENT
        invoke-virtual { p0, p1 }, ${nodeShow.smali()}
        return-void
    """)
    else -> missing("the extension has no stub named $name")
}

/**
 * Brings a For You page TikTok never showed back to life (#322, #326). On some starts Hox, the
 * page tree that tells each page it's shown, keeps another tab as Home's current child or never
 * sends For You its show, so For You's list panel keeps its user-visible hint off and every swipe
 * lands on a video showing only its cover while the first video's sound plays on. Seen on a Z
 * Fold 7, a Pixel 10, a realme and a Galaxy A15, with and without Feed tab navigation.
 *
 * Each For You fragment says when it's built, and each video pager says when it's about to pick
 * a page. The extension decides from there: on For You's own feed, in front, with Home shown and
 * the hint off, it makes Home's own switch to For You and, if the hint is still off, sends the
 * page's show itself. Both hooks are one call with no branch, at the top of the pager's dispatch
 * and in front of the constructor's return (which no try block covers).
 */
internal val forYouShowRepairPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        try {
            val calls = forYouShowCalls { classDefByOrNull(it) }
            val pageSelected = FeedPagerPageSelectedFingerprint.method
            val constructor = mutableClassDefBy(FOR_YOU_FRAGMENT).methods.singleOrNull {
                it.name == "<init>" && it.parameterTypes.isEmpty()
            } ?: missing("For You no longer has its no-argument constructor")
            val build = constructor.implementation ?: missing("For You's constructor has no code")
            if (build.tryBlocks.isNotEmpty()) missing("For You's constructor has try blocks")
            val returns = build.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            if (returns.isEmpty()) missing("For You's constructor has no return-void")

            val extension = mutableClassDefBy(REPAIR)
            fun fillStub(name: String, calls: ForYouShowCalls) {
                val stub = extension.methods.single { it.name == name }
                val body = calls.stub(name)
                val filled = stub.cloneMutable(additionalRegisters = body.locals)
                extension.methods.remove(stub)
                extension.methods.add(filled)
                filled.addInstructions(0, body.smali)
            }
            for (name in listOf(
                "readView", "readResumed", "readHidden", "readPanel", "readHint", "readActivity", "readHox",
                "readContainerTag", "readNode", "readContainer", "readCurrentChild", "readShown", "readTag",
                "callHomeSwitch", "callNodeShow",
            )) fillStub(name, calls)

            returns.reversed().forEach { at ->
                constructor.addInstructionsAtControlFlowLabel(
                    at,
                    "invoke-static/range { p0 .. p0 }, $REPAIR->forYouCreated(Ljava/lang/Object;)V",
                )
            }
            pageSelected.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, $REPAIR->feedPageSelected(Ljava/lang/Object;)V",
            )
        } catch (problem: PatchException) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Settings] Left out the For You show repair on ${packageMetadata.versionName}: ${problem.message}")
        }
    }
}
