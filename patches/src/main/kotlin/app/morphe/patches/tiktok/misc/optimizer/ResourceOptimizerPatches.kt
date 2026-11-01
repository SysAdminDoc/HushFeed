/*
 * Adapted from kveld9/kveld-morphe-patches at
 * fcb1768620b8f98a6dd31e801074589ce9a63356 (GPL-3.0).
 * https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildChoice
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildChoicePatch
import app.morphe.patches.tiktok.misc.diagnostics.buildDetailsPatch
import java.util.Locale

@Suppress("unused")
val p2pRelayBlockerPatch = rawResourcePatch(
    name = "Block P2P video relay",
    description = "Removes the files TikTok uses to pass videos on to other viewers through " +
        "your phone's internet connection. The app gets about 3.5 MB smaller. It's built in while " +
        "patching, so only patching again without it brings them back.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.P2P))

    execute {
        val nativeFiles = listOf(
            "lib/arm64-v8a/libavmdlp2pv2.so",
            "lib/arm64-v8a/libp2plivevdp.so",
            "lib/armeabi-v7a/libavmdlp2pv2.so",
            "lib/armeabi-v7a/libp2plivevdp.so",
        )
        val result = stripVerifiedResources(
            get("."),
            "P2P Relay Blocker",
            emptyList(),
            nativeFiles,
            p2pRelayProfiles,
            resolveStandaloneFile = { path -> get(path) },
            versionName = packageMetadata.versionName,
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.P2P, result)
        result.report("P2P Relay Blocker")
    }
}

@Suppress("unused")
val coreAssetDebloatPatch = rawResourcePatch(
    name = "Remove content credential and card scanner assets",
    description = "Removes TikTok's payment card scanner and content credential files, saving " +
        "about 11 MB of storage. Anything in TikTok that needs them may stop working. Only " +
        "patching again without it brings them back.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    // The guard keeps the content credential service from loading the emptied C2PA library
    // when a comment reply opens the photo picker (#211).
    dependsOn(buildChoicePatch(BuildChoice.CORE), c2paInitGuardPatch)

    execute {
        // Only libraries nothing else in the APK links against. An emptied .so that another
        // library names in its DT_NEEDED list fails that library's dlopen ("file offset >=
        // file size: 0 >= 0"), and TikTok's Librarian turns that into an uncaught error.
        // libbytemonitor is needed by libbytebench, which the whole video editor stack loads,
        // so emptying it killed the app the moment the Create tab opened (S22, 2026-09-17);
        // libprofiler is needed by the crash handler's npth_ref_monitor and reschecker. Both stay.
        // So do Pitaya (its feature dex, its libraries and the Python runtime it loads) and the
        // live cast feature dex: every lib/*/libdex_*.so is injected at start-up, and an empty one
        // left TikTok's own strings in English on a Spanish phone (#125).
        val nativeFiles = listOf(
            "lib/arm64-v8a/libtt_c2pa_sdk.so",
            "lib/arm64-v8a/libtt_c2pa_sdk_d.so",
            "lib/armeabi-v7a/libtt_c2pa_sdk.so",
            "lib/armeabi-v7a/libtt_c2pa_sdk_d.so",
            "lib/arm64-v8a/libartlog_monitor.so",
            "lib/armeabi-v7a/libartlog_monitor.so",
        )
        decode("assets/microblink")
        val result = stripVerifiedResources(
            get("."),
            "Core Asset De-bloat",
            listOf("assets/microblink"),
            nativeFiles,
            coreAssetProfiles,
            resolveStandaloneFile = { path -> get(path) },
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.CORE, result)
        result.report("Core Asset De-bloat")
    }
}

@Suppress("unused")
val languagePackPurgerPatch = rawResourcePatch(
    name = "Remove unused language packs",
    description = "Removes the app languages you don't list in this patch's options, saving " +
        "up to about 26 MB of storage. English is always kept. With no list it keeps them all. " +
        "Only patching again brings removed ones back.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.LANGUAGES))
    val targetLocales by stringOption(
        key = "locales",
        title = "Languages to keep",
        description = "The languages to keep, as short codes separated by commas, like en, es, " +
            "pt, fr or de. Type all to keep every language. English is always kept.",
        // Keep native translations unless the user chooses which languages to remove (#67).
        default = "all",
        required = false,
    )

    execute {
        decode("assets/strings#lang_*")
        val result = stripVerifiedLanguagePacks(get("."), targetLocales, languageInventories)
        if (result.retainedLocales == null) {
            // Build details keep "unverified": nothing here was checked against a reviewed APK.
            println("[Language Pack Purger] Kept every language pack. This APK's set isn't a reviewed one, so none could be removed.")
        } else {
            BuildDetails.languages(get(BUILD_DETAILS_ASSET), result)
            if (result.missingLocales.isNotEmpty()) {
                println(
                    "[Language Pack Purger] This TikTok has no pack for " +
                        "${result.missingLocales.sorted().joinToString()}, so there was nothing to keep for it.",
                )
            }
            if (result.files == 0) {
                println("[Language Pack Purger] Kept every reviewed language pack.")
            } else {
                result.report("Language Pack Purger")
            }
        }
    }
}

@Suppress("unused")
val studioCreationDebloatPatch = rawResourcePatch(
    name = "Remove creation tools",
    description = "Removes TikTok's camera, editing and effects files, saving about 38 MB of " +
        "storage. The catch: the Create tab and every recording, editing and effects tool stop " +
        "working, and opening one, like effect search, can close TikTok. Leave it out if you " +
        "make videos.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.CREATION))

    execute {
        if (!stripsCreationTools { get(it).exists() }) {
            BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.CREATION, StripSummary(0, 0L, alreadyStripped = false))
            println(
                "[Studio & Creation De-bloat] This build carries none of the editor and effect libraries, so the " +
                    "face model stays too. Emptied alone, it only stops face effects and Beauty from finding a face.",
            )
            return@execute
        }
        decode("assets/model/ttfacemodel")
        val result = stripVerifiedResources(
            get("."),
            "Studio & Creation De-bloat",
            listOf("assets/model/ttfacemodel"),
            studioNativeFiles,
            studioAssetProfiles,
            resolveStandaloneFile = { path -> get(path) },
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.CREATION, result)
        result.report("Studio & Creation De-bloat")
    }
}

@Suppress("unused")
val liveStreamSuiteOptimizerPatch = rawResourcePatch(
    name = "Remove LIVE extras",
    description = "Removes the files for LIVE co-hosting, joining a LIVE as a guest, matches, " +
        "games and gift effects, so the app gets about 3 MB smaller. The catch: in LIVEs, battle " +
        "scores and guest names can go missing, and joining someone's LIVE by voice or video, " +
        "co-hosting, games or animated gifts may stop working.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.LIVE), liveGiftEffectOptimizerPatch)

    execute {
        val nativeFiles = listOf(
            "lib/arm64-v8a/liblink_mic_sdk.so",
            "lib/armeabi-v7a/liblink_mic_sdk.so",
        )
        decode(
            "assets/native_runtime_server/game",
            "assets/offline/tiktok_live_tt_live_lynx_match_component_container",
        )
        val result = stripVerifiedResources(
            get("."),
            "LIVE Stream Suite Optimizer",
            listOf(
                "assets/native_runtime_server/game",
                "assets/offline/tiktok_live_tt_live_lynx_match_component_container",
            ),
            nativeFiles,
            liveAssetProfiles,
            resolveStandaloneFile = { path -> get(path) },
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.LIVE, result)
        result.report("LIVE Stream Suite Optimizer")
    }
}

@Suppress("unused")
val voiceToolsDebloatPatch = rawResourcePatch(
    name = "Remove voice tools",
    description = "Removes TikTok's speech recognition files, saving about 16 MB of storage. The " +
        "catch: voice search, naming a song by listening, talking to TikTok's AI assistant and " +
        "TikTok's music check on videos you post all stop working. Only patching again without it " +
        "brings them back.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    // The guard keeps TikTok's speech engine from loading the emptied libraries the first time
    // anything asks for it (#213).
    dependsOn(buildDetailsPatch, speechEngineGuardPatch)

    execute {
        // The four libraries need only each other and libaudio_fingerprint_sdk, which stays, and
        // nothing else in the APK names them in its DT_NEEDED list.
        val nativeFiles = listOf(
            "lib/arm64-v8a/libspeechengine.so",
            "lib/arm64-v8a/libspeechepg.so",
            "lib/arm64-v8a/libspeechsdk.so",
            "lib/arm64-v8a/libspeechspg.so",
            "lib/armeabi-v7a/libspeechengine.so",
            "lib/armeabi-v7a/libspeechepg.so",
            "lib/armeabi-v7a/libspeechsdk.so",
            "lib/armeabi-v7a/libspeechspg.so",
        )
        val result = stripVerifiedResources(
            get("."),
            "Voice Tools De-bloat",
            emptyList(),
            nativeFiles,
            voiceToolsProfiles,
            resolveStandaloneFile = { path -> get(path) },
        )
        result.report("Voice Tools De-bloat")
        BuildDetails.applied(get(BUILD_DETAILS_ASSET), "voiceToolsDebloat")
    }
}

// The camera feature dex stays, like every lib/*/libdex_*.so: TikTok injects them all at
// start-up, and an empty one left its own strings in English on a Spanish phone (#125).
private val studioNativeFiles = listOf(
    "lib/arm64-v8a/libEffectCreatorJni.so",
    "lib/arm64-v8a/libeffect_plugin.so",
    "lib/arm64-v8a/libttvesdk_plugin.so",
    "lib/armeabi-v7a/libEffectCreatorJni.so",
    "lib/armeabi-v7a/libeffect_plugin.so",
    "lib/armeabi-v7a/libttvesdk_plugin.so",
)

/**
 * Whether "Remove creation tools" has anything to take: the face model goes only with the
 * editor and effect libraries. A build without them (APKMirror's split bundle on 47.5.3) gets its
 * camera and effects from libraries TikTok fetches itself, so emptying the model there saved
 * under 1 MB and left face effects and Beauty unable to find a face (#285).
 */
internal fun stripsCreationTools(shipped: (String) -> Boolean) = studioNativeFiles.any(shipped)

private fun StripSummary.report(patchName: String) {
    if (files == 0) {
        println("[$patchName] This build ships none of the reviewed files, so there was nothing to empty.")
    } else if (alreadyStripped) {
        println("[$patchName] The reviewed target set was already empty.")
    } else {
        val mebibytes = String.format(Locale.US, "%.2f", bytes.toDouble() / (1024.0 * 1024.0))
        println("[$patchName] Emptied $files reviewed files ($mebibytes MiB unpacked).")
    }
}

private fun file(path: String, sha256: String) = ResourceFileContract(path, sha256)

private val microblinkFiles = listOf(
    file("assets/microblink/blinkcard/Model_1118d9d674e23996f70c6416b2bf5a6ce6ef24a6ad2c92f0ddd1e198e5f05305.rtttl", "5175cfccf687db041900edf58775cdda68553977e043941166fc63d26d460298"),
    file("assets/microblink/blinkcard/Model_349432d66ef2b216155673b634f7d5c47795bed35719b954f726b5f0856740f3.rtttl", "70c2d6e2db5739948f6b25c691454328e64fee3de381b19d4f984d238f201e2c"),
    file("assets/microblink/blinkcard/Model_3b11c3ffacbbf390b932fb9a7024f1a0016f66281ea8c790f8b5903374ad89c2.rtttl", "baae6d2360a3ce299b13b0750f6f8f21d4eb9961031b1cb6318bb59c29ca2d65"),
    file("assets/microblink/blinkcard/Model_47a2b4fe3503a673b9119db53c50711a9f9ae9b3ada515a534fdbd8013623d43.rtttl", "f088377e3391855d5ada5ff4001c89fdb6acd29a40f25b346bc988c6e84f4f50"),
    file("assets/microblink/blinkcard/Model_830c13896f96c1cb6d5cad725f44e6aae470f8672d640d20b3272ed4bb839699.rtttl", "ee18e0f7a9b964eb2fb4dd08abe6f5017f0ae9486f308c9a27d65e81dcc85aa8"),
    file("assets/microblink/blinkcard/Model_9f6734be0f5c1e4f3c6c621f4a72db8241feaf7c8705dc68a9cc07a7b634ee85.rtttl", "c0d8dca352eef82edbfcb63465f656783e5cdda57b435104c25b1a552ddd6263"),
    file("assets/microblink/blinkcard/Model_b9263312a9b623d1a3b75b643ccdcbc36aae52c278d721443468147c50e44583.rtttl", "8cd96554162a2b0b7d413d95b3d99da6cfd8e84caa499714f034850f788cbcd7"),
    file("assets/microblink/blinkcard/Model_cc1fab8df49d9a21de6c7b76ccf0dac40b17fcfb7073cc520eca073cbf8e33e9.rtttl", "61e33f9748bd27c4e2bb88fc677e05a8517b018fbc3d9899b6e9e852a627dcc0"),
    file("assets/microblink/blinkcard/Model_e946dc0b1d15d7dd6ab8b936593bd77a07a9d8a5fa7a803e531713a6f5d2eab6.rtttl", "5b531081f2d15eda91f1598089281c4a887a4990325eac3f66ffbf23de359e8f"),
    file("assets/microblink/blinkcard/Model_ee471cb6e7b68287281f761c2b05221043b9b059c6cea9132523ab2981f9a7ca.rtttl", "74174713ad1974152b8a5d92405b91bf84ee515a36f5691f7314a6b4720f352c"),
    file("assets/microblink/blinkcard/Model_f132d1bd7614b1274fafb8a41ec6c047b84b2a43654ae2da5ddd78a2765601c6.rtttl", "00caf18a73114c05903ddb448afbd1191ff07825a318664fcc82360ce7e9290d"),
    file("assets/microblink/blinkcard/device_list.json", "7093365bcfc7a97ac57f2aefb23d9be05b0fc707b5aea58305ec76a8abefd670"),
    file("assets/microblink/blinkcard/device_list_mb.json", "07b3a400c1246e09de51fc15c57308d5ff0feeb09ae2bbfa3511fdd4afe06aed"),
)

// One reviewed path set per strip for the declared build, read off its universal APK and its
// split bundle. The log monitor, camera, relay and link mic libraries are rebuilt from one
// TikTok build to the next, so a strip refuses any other build's files ("no
// reviewed path set matched"), and a build the bundle moves to needs its own sets read off its
// fixtures. The Microblink files and the C2PA debug libraries have been byte-identical on every
// build read so far, and 47.4.3 rebuilt the main C2PA library. 47.5.3 kept 47.4.3's C2PA,
// monitor, editor and speech libraries and rebuilt the link mic library, two LIVE scripts and
// the arm64 and armeabi-v7a P2P video relay. 47.6.3 kept the editor and speech libraries and
// both LIVE scripts and rebuilt the main C2PA, log monitor, link mic and avmdl relay libraries.
private val c2paArm64Files = listOf(
    file("lib/arm64-v8a/libtt_c2pa_sdk.so", "4b4b8e8435f9d1786b5e54c13690ca7b728bd976abd10c3ccf67a257fcd9b763"),
    file("lib/arm64-v8a/libtt_c2pa_sdk_d.so", "a64ce0fb43e7c22e2d95d8bbfcaede522d585c5d51dbae87d24db1359c27a01a"),
)

private val c2paArmeabiFiles = listOf(
    file("lib/armeabi-v7a/libtt_c2pa_sdk.so", "f509e6e46878b09df48d40d1322a1e23bb331b77e36a3fb6a1e596be4a063286"),
    file("lib/armeabi-v7a/libtt_c2pa_sdk_d.so", "a96af4a99de68503234e3ab658d9702ac436ae1e0650dc5551e4e29a89700b0c"),
)

// 47.6.3 (APKMirror's universal APK, read on 2026-11-01). Its split bundle holds the universal
// APK's bytes for every file it carries.
private val monitorFiles = listOf(
    file("lib/arm64-v8a/libartlog_monitor.so", "886bdb40824e3f2712c304a080f1b3e6d23ae0f760f834095004e248070d6b21"),
    file("lib/armeabi-v7a/libartlog_monitor.so", "fa4961c5254836f8fb8e0041f7f795d6d1e6da0dad6113c25b7f07630b717efe"),
)

// APKMirror also sells TikTok as a split bundle (tiktok-47-6-3, "arm64-v8a + armeabi-v7a",
// 120-640dpi, read on 2026-11-01). Morphe Manager and the desktop CLI merge every split in one,
// and every file the merged APK shares with the universal APK is byte-identical. 47.6.3's bundle
// has 33 feature splits where 47.5.3's had 7, and they only come with arm64 libraries. So it has
// the Microblink card scanner, the editor and effect libraries, the P2P relay and the speech epg
// and spg libraries again, on arm64, and lacks their armeabi-v7a copies. Each bundle profile is
// the universal APK's profile less those armeabi-v7a files. It keeps 27 languages.

/** [profile] without [absent], every one of which it must list: its build's split bundle. */
private fun bundleOf(profile: ResourceProfile, vararg absent: String): ResourceProfile {
    val paths = profile.files.map { it.path }.toSet()
    val missing = absent.filterNot { it in paths }
    check(missing.isEmpty()) { "${profile.label} does not list $missing, so it cannot be left out of the bundle profile" }
    return ResourceProfile("${profile.label} split bundle", profile.files.filterNot { it.path in absent })
}

private val coreAssets = ResourceProfile(
    "TikTok 47.6.3",
    microblinkFiles + c2paArm64Files + c2paArmeabiFiles + monitorFiles,
)

internal val coreAssetProfiles = listOf(
    coreAssets,
    bundleOf(coreAssets, "lib/armeabi-v7a/libtt_c2pa_sdk_d.so"),
)

private val studioArm64Files = listOf(
    file("assets/model/ttfacemodel/tt_face_v11.1.model", "c4b081d04f6829f7cd5849abca0941891f1a7f2cb250efd7f0e42fece5426610"),
    file("lib/arm64-v8a/libEffectCreatorJni.so", "b7db28436aa1ccd85e3d52d1f916817397be6b35dfff40d1bb69d0d5251a3293"),
    file("lib/arm64-v8a/libeffect_plugin.so", "2990d5bf76d832b4a6c25bbd9540f3191e86aa8342c54a638fad26ada2806be3"),
    file("lib/arm64-v8a/libttvesdk_plugin.so", "0f1cc7737330f8a9b81e03f3ab15eecea197254c16c23b5492c5424aaf51d6c0"),
)

private val studioAssets = ResourceProfile(
    "TikTok 47.6.3",
    studioArm64Files +
        listOf(
            file("lib/armeabi-v7a/libEffectCreatorJni.so", "d3ae58712413c2d1d06dcf508eb7850452f58c93b9befd57867cd029421c9482"),
            file("lib/armeabi-v7a/libeffect_plugin.so", "f89bd50e941392fee2e47d031711fdce3520dae7f3e531da59dd0afbbec9d91c"),
            file("lib/armeabi-v7a/libttvesdk_plugin.so", "42ab2be66f2b02622f52062fc3ff1b4862f44f0098faecfac3996afe3e7cf1f9"),
        ),
)

internal val studioAssetProfiles = listOf(
    studioAssets,
    bundleOf(
        studioAssets,
        "lib/armeabi-v7a/libEffectCreatorJni.so",
        "lib/armeabi-v7a/libeffect_plugin.so",
        "lib/armeabi-v7a/libttvesdk_plugin.so",
    ),
)

internal val liveAssetProfiles = listOf(
    // The universal APK and the split bundle carry these five files byte for byte.
    ResourceProfile(
        "TikTok 47.6.3",
        listOf(
            file("assets/native_runtime_server/game/scripts/ttmg-core.js.zip", "9727cb446d3caa26e853e43efeedba6269ed95936e661d393a55a5a4e28c6331"),
            file("assets/offline/tiktok_live_tt_live_lynx_match_component_container/mainV12/template.js", "e7fcdda86f17ea09135f4b5c09c59cf3483d49b78f76215f4e63b0f35f3941c6"),
            file("assets/offline/tiktok_live_tt_live_lynx_match_component_container/match_invitee_v3/template.js", "8e84ec297249a771c9c9d56438f6c509d2a0f438c759ee65e212ab14ef7cb3eb"),
            file("lib/arm64-v8a/liblink_mic_sdk.so", "32ff13e8868600fc1a49fa2c37d2cb33276e11af0a724398438d006a8a20ef47"),
            file("lib/armeabi-v7a/liblink_mic_sdk.so", "6589dd618fc7dfe7e38dee672f3490fe5d5d0af5d62eb592d8be6b7020bf365d"),
        ),
    ),
)

private val p2pRelay = ResourceProfile(
    "TikTok 47.6.3",
    listOf(
        file("lib/arm64-v8a/libavmdlp2pv2.so", "3611c75139020550b58b9cbb99eff6dfb72e0d301318fed62d0d01f84a4c9620"),
        file("lib/arm64-v8a/libp2plivevdp.so", "ed35b032fac7c169860d3f37bc3be1933df9bddc8ac6785413f9608945946337"),
        file("lib/armeabi-v7a/libavmdlp2pv2.so", "21473bda7be31d9b4b24e71414af86d741d6f383e2d163fd2e768a339ca5f01b"),
        file("lib/armeabi-v7a/libp2plivevdp.so", "dd8a626f8b0efe36a883096923bed56ecb457e9408b26262c844442901dc1d09"),
    ),
)

internal val p2pRelayProfiles = listOf(
    p2pRelay,
    bundleOf(p2pRelay, "lib/armeabi-v7a/libavmdlp2pv2.so", "lib/armeabi-v7a/libp2plivevdp.so"),
)

private val voiceTools = ResourceProfile(
    "TikTok 47.6.3",
    listOf(
        file("lib/arm64-v8a/libspeechengine.so", "66cdc8ec913a42e2e1c63531daff727a8bb09daf2a26f73dc6c1fd78c4c97a5e"),
        file("lib/arm64-v8a/libspeechepg.so", "47027d80c045d7232e4859ab9416f3684b36eefdb616e725da5c797eb424ff1e"),
        file("lib/arm64-v8a/libspeechsdk.so", "6077aa66c8ae0f05f7dd9cdd231c7b5b37173a8820033aa488483ab74c4a8839"),
        file("lib/arm64-v8a/libspeechspg.so", "f521abde32911960bf935ea0ba97ed477575add1aa0cb3aee4fc7b847b0dd423"),
        file("lib/armeabi-v7a/libspeechengine.so", "99c8941df82c357fd7560aa3ef26013b6186b1d708a56387dd59820d4404cc85"),
        file("lib/armeabi-v7a/libspeechepg.so", "2b753ce19fa28fd0973ecb6751e58c1f7886c51114f8ba35ad14d5c203a96fde"),
        file("lib/armeabi-v7a/libspeechsdk.so", "6bc593dd38bfc985202edefa1b161a071378ca6da754a3058f1e6056b2e2a248"),
        file("lib/armeabi-v7a/libspeechspg.so", "2492ca89d1c73df7ad6da5c37eba62c0b08133f565ec6223273cdc9cceeaf578"),
    ),
)

internal val voiceToolsProfiles = listOf(
    voiceTools,
    bundleOf(
        voiceTools,
        "lib/armeabi-v7a/libspeechepg.so",
        "lib/armeabi-v7a/libspeechspg.so",
    ),
)

internal val languageInventories = listOf(
    // The universal APK: 207 files in 64 directories.
    LanguageInventoryContract(
        directories = setOf(
            "af", "ar", "az", "bg", "bn", "ca", "ceb", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi",
            "fil", "fr", "ga", "gu", "he", "hi", "hr", "hu", "id", "in", "is", "it", "iw", "ja", "jv", "kk",
            "km", "kn", "ko", "lt", "lv", "ml", "mr", "ms", "my", "nb", "nl", "or", "pa", "pl", "pt", "ro",
            "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tr", "uk", "ur", "uz", "vi", "zh", "zu",
        ),
        pathManifestSha256 = "38f412319f00a31a025e03095ea9bf4266b412c2322d2a6b0b4a031a073b0c08",
        contentManifestSha256 = setOf("87f96868f76cc3cf83eb89928971db2e6acfbbe406eb5cca72362761aec36460"),
    ),
    // The split bundle: 27 language splits and 106 files.
    LanguageInventoryContract(
        directories = setOf(
            "ar", "de", "en", "es", "et", "fi", "fr", "he", "hi", "hu", "id", "in", "it", "iw", "ja", "ko",
            "ms", "nl", "pl", "pt", "ru", "sv", "th", "tr", "uk", "vi", "zh",
        ),
        pathManifestSha256 = "1b372a3b46779664920ebc1031ca38eb787dce81da9089a5867723290041dcac",
        contentManifestSha256 = setOf("030331c888bdcb3e4e62bdb24aa10b1f1716d9118502228df510fea2b6447ac3"),
    ),
)
