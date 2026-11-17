/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val NOTIFICATION_FRAGMENT_V2 = "Lcom/ss/android/ugc/aweme/notification/v2/NotificationFragmentV2;"
private const val SPLIT_EXTENSION = "Lapp/morphe/extension/tiktok/inbox/ActivitySplit;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val FRAGMENT_MANAGER = "Landroidx/fragment/app/FragmentManager;"
private const val FRAME_LAYOUT = "Landroid/widget/FrameLayout;"
private const val VIEW_PAGER2 = "Landroidx/viewpager2/widget/ViewPager2;"

/** The bridge methods this patch adds to NotificationFragmentV2 for the extension to call. */
internal const val SPLIT_FRAGMENTS = "hushSplitFragments"
internal const val SPLIT_ADD = "hushSplitAdd"
internal const val SPLIT_REMOVE = "hushSplitRemove"

/** The Activity tab's fragment builds its view; the pager of type lists already exists then. */
internal object ActivityPageViewCreatedFingerprint : Fingerprint(
    definingClass = NOTIFICATION_FRAGMENT_V2,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * TikTok's PowerViewPager (a renamed FrameLayout wrapping a ViewPager2 in a field kept as
 * `viewPager2`): the dropdown turns a pick into setCurrentItem(index, smooth).
 */
internal object TypeListPagerSelectFingerprint : Fingerprint(
    name = "setCurrentItem",
    returnType = "V",
    parameters = listOf("I", "Z"),
    custom = { _, classDef -> classDef.isTypeListPager() },
)

/** androidx FragmentTransaction.replace(int, Fragment, String), the one that refuses a zero id. */
internal object FragmentTransactionReplaceFingerprint : Fingerprint(
    parameters = listOf("I", FRAGMENT, "Ljava/lang/String;"),
    strings = listOf("Must use non-zero containerViewId"),
)

/** androidx BackStackRecord.remove(Fragment), by the message it throws for a foreign fragment. */
internal object BackStackRecordRemoveFingerprint : Fingerprint(
    parameters = listOf(FRAGMENT),
    strings = listOf("Cannot remove Fragment attached to a different FragmentManager. Fragment "),
)

/**
 * TikTok's emoji catalog publish(categories, emojis), by the message it logs. A comment row bound
 * before it runs keeps emoji codes like `[Tears of joy]` as text, and TikTok never redraws it. The
 * split's lists bind right as the page opens, so after a cold start they often come first.
 */
internal object EmojiCatalogPublishFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "Ljava/util/List;"),
    strings = listOf("publish: catalog usable again, lifting V0 downgrade"),
)

internal fun ClassDef.isTypeListPager() = superclass == FRAME_LAYOUT &&
    fields.any { it.name == "viewPager2" && it.type == VIEW_PAGER2 }

@Suppress("unused")
val splitActivityListPatch = bytecodePatch(
    name = "Split activity list",
    description = "Shows the Inbox's Activity page as two lists, your newest comments on top and " +
        "likes below, each scrolling on its own, with a bar between them that swaps them or " +
        "hides the lower one. " +
        "Starts off. Turn it on in Hushfeed settings > Inbox.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val bridge = FragmentBridge.resolve()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSplitActivityList()V",
        )
        val page = mutableClassDefBy(NOTIFICATION_FRAGMENT_V2)
        check(page.methods.none { it.name in setOf(SPLIT_FRAGMENTS, SPLIT_ADD, SPLIT_REMOVE) }) {
            "Split activity list: the bridge already exists"
        }
        page.methods.addAll(bridge.methods())

        ActivityPageViewCreatedFingerprint.method.callBeforeEachReturn(
            "invoke-static/range { p0 .. p1 }, $SPLIT_EXTENSION->onPageViewCreated(Ljava/lang/Object;Landroid/view/View;)V",
        )
        TypeListPagerSelectFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p1 }, $SPLIT_EXTENSION->onPagerSelect(Ljava/lang/Object;I)V",
        )
        // Only redraws emoji; on a build where the publish moved the split still works without it.
        val declaredBuild = packageMetadata.versionName in declaredVersions()
        val publish = if (declaredBuild) EmojiCatalogPublishFingerprint.method else EmojiCatalogPublishFingerprint.methodOrNull
        publish?.callBeforeEachReturn("invoke-static {}, $SPLIT_EXTENSION->onEmojiCatalogReady()V")
    }
}

/** Calls [call] at every return-void, keeping each return's incoming branch labels on the call. */
internal fun MutableMethod.callBeforeEachReturn(call: String) {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    check(returns.isNotEmpty()) { "Split activity list: $name has no return" }
    returns.asReversed().forEach { index ->
        val original = implementation!!.instructions[index]
        replaceInstruction(index, call)
        implementation!!.addInstruction(index + 1, original)
    }
}

/**
 * androidx's fragment classes are renamed in TikTok, apart from Fragment itself. The five calls the
 * split needs are found by what androidx's own bytecode does, then wrapped in static methods with
 * fixed names on NotificationFragmentV2, so the extension never depends on a renamed member.
 */
internal class FragmentBridge(
    private val transaction: String,
    private val beginTransaction: MethodReference,
    private val getFragments: MethodReference,
    private val add: MethodReference,
    private val remove: MethodReference,
    private val commitNowAllowingStateLoss: MethodReference,
) {
    companion object {
        fun resolve(): FragmentBridge = resolve(
            FragmentTransactionReplaceFingerprint.method, BackStackRecordRemoveFingerprint.method,
        ) { type -> classDefBy(type) }

        /** The whole lookup from the two anchor methods; fixture tests run this same code. */
        internal fun resolve(replace: Method, record: Method, classOf: (String) -> ClassDef): FragmentBridge {
            val transaction = replace.definingClass
            val transactionClass = classOf(transaction)
            val addCandidates = transactionClass.methods.filter {
                it.parameterTypes.map(CharSequence::toString) == listOf("I", FRAGMENT, "Ljava/lang/String;") &&
                    it.name != replace.name && !AccessFlags.STATIC.isSet(it.accessFlags)
            }
            check(addCandidates.size == 1) { "Split activity list: FragmentTransaction.add changed (${addCandidates.size})" }

            val recordClass = classOf(record.definingClass)
            check(recordClass.superclass == transaction) { "Split activity list: BackStackRecord moved" }
            val commits = recordClass.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "V" && it.commitsAllowingStateLoss() }
            check(commits.size == 1) { "Split activity list: commitNowAllowingStateLoss changed (${commits.size})" }

            val manager = classOf(FRAGMENT_MANAGER)
            val begins = manager.methods.filter { it.parameterTypes.isEmpty() && it.returnType == transaction && !AccessFlags.STATIC.isSet(it.accessFlags) }
            check(begins.size == 1) { "Split activity list: beginTransaction changed (${begins.size})" }
            val lists = manager.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" && !AccessFlags.STATIC.isSet(it.accessFlags) }
            check(lists.size == 1) { "Split activity list: getFragments changed (${lists.size})" }

            return FragmentBridge(
                transaction,
                begins.single().asReference(),
                lists.single().asReference(),
                addCandidates.single().asReference(),
                ref(transaction, record.name, listOf(FRAGMENT), transaction),
                ref(transaction, commits.single().name, emptyList(), "V"),
            )
        }

        /** commitNowAllowingStateLoss hands `true` to FragmentManager; commitNow hands `false`. */
        private fun Method.commitsAllowingStateLoss(): Boolean {
            val code = implementation?.instructions?.toList() ?: return false
            return code.withIndex().any { (i, instruction) ->
                val target = instruction.getReference<MethodReference>()
                instruction.opcode == Opcode.INVOKE_VIRTUAL && target?.definingClass == FRAGMENT_MANAGER &&
                    target.parameterTypes.lastOrNull()?.toString() == "Z" &&
                    (code.getOrNull(i - 1) as? WideLiteralInstruction)?.wideLiteral == 1L
            }
        }

        private fun Method.asReference(): MethodReference = ref(definingClass, name, parameterTypes.map(CharSequence::toString), returnType)

        private fun ref(owner: String, name: String, parameters: List<String>, returns: String): MethodReference =
            com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference(owner, name, parameters, returns)
    }

    /** The resolved androidx calls, as smali references; read by the fixture test. */
    internal fun calls(): List<String> =
        listOf(beginTransaction, getFragments, add, remove, commitNowAllowingStateLoss).map { it.call() }

    private fun MethodReference.call(): String =
        "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    fun methods(): List<MutableMethod> = listOf(
        bridge(SPLIT_FRAGMENTS, listOf(FRAGMENT), "Ljava/util/List;", 2, """
            invoke-virtual { p0 }, $FRAGMENT->getChildFragmentManager()$FRAGMENT_MANAGER
            move-result-object v0
            invoke-virtual { v0 }, ${getFragments.call()}
            move-result-object v0
            return-object v0
        """),
        bridge(SPLIT_ADD, listOf(FRAGMENT, "I", FRAGMENT, "Ljava/lang/String;"), "V", 5, """
            invoke-virtual { p0 }, $FRAGMENT->getChildFragmentManager()$FRAGMENT_MANAGER
            move-result-object v0
            invoke-virtual { v0 }, ${beginTransaction.call()}
            move-result-object v0
            invoke-virtual { v0, p1, p2, p3 }, ${add.call()}
            invoke-virtual { v0 }, ${commitNowAllowingStateLoss.call()}
            return-void
        """),
        bridge(SPLIT_REMOVE, listOf(FRAGMENT, FRAGMENT), "V", 3, """
            invoke-virtual { p0 }, $FRAGMENT->getChildFragmentManager()$FRAGMENT_MANAGER
            move-result-object v0
            invoke-virtual { v0 }, ${beginTransaction.call()}
            move-result-object v0
            invoke-virtual { v0, p1 }, ${remove.call()}
            invoke-virtual { v0 }, ${commitNowAllowingStateLoss.call()}
            return-void
        """),
    )

    private fun bridge(name: String, parameters: List<String>, returns: String, registers: Int, smali: String): MutableMethod =
        MutableMethod(ImmutableMethod(
            NOTIFICATION_FRAGMENT_V2, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).also { it.addInstructions(0, smali.trimIndent()) }
}
