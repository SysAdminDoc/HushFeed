/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** TikTok Lite's For You response (#48): its own FeedItemList, beside TikTok's feed.model one. */
internal const val LITE_FEED_ITEM_LIST_CLASS = "Lcom/ss/android/ugc/aweme/main/homepage/fragment/data/model/FeedItemList;"

internal object LiteFeedItemListGetItemsFingerprint : Fingerprint(
    definingClass = LITE_FEED_ITEM_LIST_CLASS,
    name = "getItems",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

/** A Lite method that builds a FeedItemList from one response. */
internal class LiteListBuilder(val definingClass: String, val name: String, val parameter: String) {
    fun matches(method: Method) = method.name == name && method.parameterTypes == listOf(parameter) &&
        method.returnType == LITE_FEED_ITEM_LIST_CLASS
}

/**
 * Where Lite 47.8.2 builds a FeedItemList for something the reader opened rather than For You: a
 * profile's posts, and a video opened on its own (two copies of that builder ship). The read
 * filter leaves these lists alone, since hiding a video there takes away the one the reader
 * tapped (#135 on TikTok).
 */
internal val LITE_OPENED_LIST_BUILDERS = listOf(
    LiteListBuilder("LX/EQf;", "L", "Lcom/ss/android/ugc/aweme/profile/aweme/model/AwemeListResponse;"),
    LiteListBuilder("LX/G6M;", "LIIIIZZ", "Lcom/ss/android/ugc/aweme/feed/model/detail/FeedDetail;"),
    LiteListBuilder("LX/IRx;", "LCI", "Lcom/ss/android/ugc/aweme/feed/model/detail/FeedDetail;"),
)

/**
 * The Feed filter in TikTok Lite. Lite has none of the TikTok feed classes the rest of the patch
 * hooks, but its For You items are the same Aweme model, and every reader of the response goes
 * through its FeedItemList's getItems(), so the main feed's filters run there and drop what they
 * would drop on TikTok, ads first. Registers last, so a run that fails partway doesn't list the
 * Feed filter as applied.
 */
internal fun filterLiteFeed() {
    LiteFeedItemListGetItemsFingerprint.method.addInstruction(
        0,
        "invoke-static/range {p0 .. p0}, Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;->" +
            "filterLiteOnRead(Ljava/lang/Object;)V",
    )
    for (builder in LITE_OPENED_LIST_BUILDERS) {
        val method = mutableClassDefBy(builder.definingClass).methods.single { builder.matches(it) }
        method.findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
            val register = method.getInstruction<OneRegisterInstruction>(index).registerA
            method.addInstructionsAtControlFlowLabel(
                index,
                "invoke-static/range {v$register .. v$register}, " +
                    "Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;->markLiteOpenedList(Ljava/lang/Object;)V",
            )
        }
    }
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedFilter()V",
    )
}
