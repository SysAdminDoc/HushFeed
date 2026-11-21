/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SEARCH_MIX_FEED_BASE = "Lcom/ss/android/ugc/aweme/search/arch/v2/protocol/card/model/SearchMixFeedBase;"
private const val SEARCH_PRECISE_AD = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/precisead/core/model/SearchPreciseAd;"
private const val TOP_RESULTS_FRAGMENT = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/core/ui/jedi/SearchJediMixFeedFragment;"
private const val SEARCH_AD_SERVICE = "Lcom/bytedance/ies/ugc/aweme/commercialize/search/service/ISearchAdMainService;"

/**
 * A precise ad in search (#484) carries its advert videos in the card's `preciseAd.awemeCards` and
 * none of its own, and the extension's search ad test reads both names. Held to every declared
 * build, with the two facts that make the test needed: TikTok's own answer (`isAdOrContainAd`, the
 * card's ad id set) never looks at a precise ad, and the Top results' ad pass hands the whole
 * `awemeCards` list to its advert list.
 */
class SearchPreciseAdFieldsTest {
    @Test
    fun `a precise ad's videos sit where the search ad test reads them`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byType = classes(apk)

            val card = byType.getValue(SEARCH_MIX_FEED_DESCRIPTOR)
            assertEquals("$version: the card's precise ad", SEARCH_PRECISE_AD, card.fields.single { it.name == "preciseAd" }.type)
            val precise = byType.getValue(SEARCH_PRECISE_AD)
            assertEquals("$version: a precise ad's videos", "Ljava/util/List;", precise.fields.single { it.name == "awemeCards" }.type)

            val answer = byType.getValue(SEARCH_MIX_FEED_BASE).methods.single { it.name == "isAdOrContainAd" }
            assertTrue(
                "$version: isAdOrContainAd is no longer the card's ad id set",
                answer.code().any { it.getReference<MethodReference>()?.name == "getAdCidList" },
            )
            val ids = card.methods.single { it.name == "getAdCidList" }
            assertTrue(
                "$version: the card's ad id set reads the precise ad now",
                ids.code().none { it.getReference<FieldReference>()?.name == "preciseAd" || it.getReference<MethodReference>()?.name == "getPreciseAd" },
            )

            val adPasses = byType.getValue(TOP_RESULTS_FRAGMENT).methods.filter { method ->
                val code = method.code()
                code.any { it.getReference<TypeReference>()?.type == SEARCH_AD_SERVICE } &&
                    code.withIndex().any { (index, instruction) ->
                        instruction.getReference<FieldReference>()?.let { it.definingClass == SEARCH_PRECISE_AD && it.name == "awemeCards" } == true &&
                            code.drop(index + 1).take(3).any { next ->
                                next.opcode == Opcode.INVOKE_VIRTUAL && next.getReference<MethodReference>()?.name == "addAll"
                            }
                    }
            }
            assertEquals("$version: the Top results' ad pass that takes every precise ad video", 1, adPasses.size)
        }
    }

    private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()

    private fun classes(apk: File): Map<String, ClassDef> {
        val byType = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
        }
        return byType
    }
}
