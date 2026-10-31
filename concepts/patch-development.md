# App and patch development map

This guide maps the TikTok app Hushfeed modifies to the code and release checks that make a patch. Its version and patch counts were last synced with the source on 2026-10-31, and `scripts/validate-release-facts.ps1` fails when they drift. The generated [patch catalog](../patches-list.json) is the current source of truth for patch names and compatibility.

## What Hushfeed ships

Hushfeed is a Morphe patch bundle, not a TikTok APK. Morphe Manager takes a TikTok APK chosen by the user, applies the selected patches, and signs the result with the user's existing key. The patch definitions and the runtime code they inject live in separate Gradle modules.

Source snapshot: version 0.80.0, 144 patch entries. Those come from gradle.properties and patches-list.json.

Published bundle: version 0.79.0, 141 patches. Those come from patches-bundle.json and the release it names.

The source runs ahead of the published bundle until the next release ships. The index moves only after a new bundle is published and verified, which is the release sequence described in CONTRIBUTING.md. Do not hand-edit the index to make the two match.

Keep every Unreleased bullet under a `###` group. The release cutter preserves those groups, and the index stage appends them to the bundle description so Morphe Manager can show the newest release's whole change list. Release fact checks read only the prose above the first group because a change bullet may mention an older build or count. The same gate checks that the appended groups exactly match the release entry in this changelog.

## Target app and version boundary

| Property | Current patch target |
| --- | --- |
| App | Global TikTok |
| Package | com.zhiliaoapp.musically |
| Supported version | 47.5.3 |
| Version code | 2024705030 |
| APK signer SHA-256 | 9041803e91bcb814b4b4399fb5c85a91640b755e5e8ba76813814bf4cf2ab5ba |
| Extension minimum Android API | 23 |

Global TikTok is the main target. PineDrama (com.ss.android.ttmd.video 47.5.3, version code 2024705030, signer SHA-256 e97ca0c8835c5a9e4f7702a0382b103ede9c5d51e5194c8db54f4c8833f54e18) is built from TikTok's 47.5.3 code, and the patches whose anchors it keeps declare it too: 98 of the 144 in the current catalog. It leaves out TikTok's stories, so the parts of a patch that need them check `shipsStories()` and skip on PineDrama. A patch's package and version support says only that it applies. Whether it works signed in on a drama feed is a separate runtime check. TikTok Lite and regional package variants aren't patch targets.

The target identity is centralized in [AppCompatibilities.kt](../patches/src/main/kotlin/app/morphe/patches/shared/compat/AppCompatibilities.kt). Patch definitions normally use that compatibility object instead of copying package names, signer hashes, or version codes into each patch. The README's [Supported target](../README.md#supported-target) section is the user-facing version record, and its install steps keep naming the build the published release patches, since that's what people install. When the target moves, update AppCompatibilities.kt from the reviewed APK and add one line under Supported target in the form ``- Next release: TikTok X.Y.Z, [on APKMirror](link) since YYYY-MM-DD (version code ...), SHA-256 `...`.`` The release's index step moves the install steps onto that build and drops the line.

On 2026-10-09, a clean factory APK was downloaded from [TikTok's official Android download page](https://www.tiktok.com/download). It was TikTok 38.3.3, package com.zhiliaoapp.musically, version code 2023803030, minSdk 21, targetSdk 34, and 429,047,484 bytes. Its SHA-256 was AB8D9394D87CA046BDA8F49031791CFAF75588A0830B5D54766E1BCF8CCDD6BC. apksigner verified its official signer, which matches the certificate recorded for the supported target.

That factory APK is useful for observing TikTok's unpatched onboarding and selected signed-in surfaces. It is not a patch fixture. Its version is far older than the supported build, so bytecode matches, resource IDs, and patch success on it say nothing about the supported target.

## Factory install observation

The factory APK was installed into a separate writable emulator data image. The emulator's original app data was left intact. Launch first showed an Android full-screen notice with a Got it button. After dismissing that system notice, TikTok showed:

- Sign up for TikTok
- Create a profile, follow other accounts, make your own videos, and more.
- Skip
- Use phone or email
- Continue with Facebook
- Continue with Google
- Already have an account? Log in

The initial sign-up screen offered Skip, phone or email, Facebook, Google, and Log in. A later launch led to TikTok's birthday gate, with a Birthday field, month, day, and year picker labels, a disabled Continue button, and a Sign up action. The picker initially showed October 9, 2025. The clean pass ended there. The account holder later completed onboarding and sign-in on the visible emulator, which reached the For You feed and settings. That pass showed one in-feed video ad with an Ad label and Shop now button. It also exposed TikTok's ad, privacy, contact-sync, and content preference controls. No preferences were changed and no account-specific values were recorded. The birthday-gate screenshot and detailed route, tracking, and patch analysis are in [the TikTok app audit](tiktok-app-audit.md). These factory-install UI observations are from 38.3.3, not evidence about the supported 47.1.4 build. The separately labeled physical-device report below covers the later modified 47.1.4 installation.

## Build and injection path

1. Kotlin patch declarations under patches/src/main/kotlin describe compatibility, dependencies, options, and changes to TikTok bytecode or resources.
2. Runtime hooks and settings under extensions are compiled as Morphe extension payloads.
3. The :patches:buildAndroid task packages the patch code and both extension payloads into patches-<version>.mpp.
4. PatchListGenerator loads the built bundle and writes patches-list.json. That JSON records patch descriptions, categories, dependencies, options, package compatibility, signer, and target versions for Manager.
5. BundleVerifier checks the bundle's version, DEX payloads, checksum, target description, and exact match with the generated patch catalog.
6. Fixture tests and the desktop patcher check that the selected patch set still applies to the declared TikTok APK.

The delivered bundle contains classes.dex, extensions/tiktok.mpe, and extensions/shared.mpe. It does not contain TikTok's APK. The app package name and certificate are checked separately from the patch bundle's source version.

## Gradle module map

| Module | Responsibility |
| --- | --- |
| :patches | Kotlin patch recipes, compatibility metadata, patch tests, patch-list generation, and the .mpp bundle |
| :patches:stub | Compile-time declarations for types supplied by the patcher or host app |
| :extensions:tiktok | TikTok-specific Java runtime hooks, settings, localization, and Robolectric tests |
| :extensions:tiktok:stub | Compile-time declarations for TikTok classes referenced by the extension |
| :extensions:shared | Shared runtime extension payload |
| :extensions:shared:library | Shared settings, utility, and diagnostic code used by extensions and tests |

The Morphe Gradle plugin and the default extension namespace are configured in settings.gradle.kts. The current plugin pin is 1.3.4. The version catalog pins patcher 2.0.0 and the README holds the Manager floor at 2.0.0 (morphe-desktop 1.19.0 is the first CLI on it). Keep those pins compatible because Manager refuses a bundle built for a newer patcher. The TikTok extension compiles for Java 11 with API 23 as its floor. The shared library compiles for Java 17, also with API 23 as its floor. Module build files declare which payload name each extension produces. The stub modules let source compile against host types; they are not a substitute for checking the real APK.

## Where to look first

| Work area | Source of truth |
| --- | --- |
| Supported TikTok package, signer, and version codes | patches/src/main/kotlin/app/morphe/patches/shared/compat/AppCompatibilities.kt |
| Patch declarations | patches/src/main/kotlin/app/morphe/patches/tiktok/ |
| Shared patch helpers and failure contracts | patches/src/main/kotlin/app/morphe/util/ and patches/src/main/kotlin/app/morphe/patches/tiktok/shared/ |
| Runtime extension hooks | extensions/tiktok/src/main/java/app/morphe/extension/tiktok/ |
| Shared runtime settings and utilities | extensions/shared/src/main and extensions/shared/library/src/main |
| In-app setting keys, types, defaults, and availability | extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/Settings.java |
| Settings screen entry hook | extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/TikTokActivityHook.java |
| Settings page rows and categories | extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/ |
| Patch tests | patches/src/test/kotlin/ and patches/src/test/resources/ |
| Runtime tests | extensions/tiktok/src/test/java/ |
| Generated patch metadata | patches-list.json |
| Build, fixture, device, and release helpers | scripts/ |

The runtime code is grouped by feature under extensions/tiktok/src/main/java/app/morphe/extension/tiktok/. Current package folders are accountmanagement, blockauthor, captions, capture, cleardisplay, comment, commentsort, diagnostics, download, externalbrowser, favorites, featurecontrols, featuregatelab, feed, feedfilter, foldable, follow, font, ghostmode, inbox, interaction, live, misc, navigation, network, notinterested, offline, playback, popups, privacy, profile, publishdate, repost, search, seekbar, seen, settings, share, speed, spoof, storage, telemetry, theme, translation, upload, and wellbeing. Search the generated patch catalog by display name first, then follow its dependencies into the Kotlin and Java sources.

The current catalog's 144 patches are grouped as follows:

| Manager category | Patches |
| --- | ---: |
| Comments | 17 |
| Downloads | 5 |
| Feed | 20 |
| Inbox | 8 |
| Interaction | 18 |
| Interface | 10 |
| Performance | 11 |
| Playback | 20 |
| Privacy | 16 |
| Search | 4 |
| Settings | 15 |

The [patch catalog](../patches-list.json) has the complete names and plain-English descriptions. Keep the prose in that generated catalog aligned with the README's patch table and the in-app setting title. The build's documentation checks compare those sources.

## How the in-app settings connect

The settings patch is defined in patches/src/main/kotlin/app/morphe/patches/tiktok/misc/settings/SettingsPatch.kt. It hooks TikTok's AdPersonalizationActivity and calls TikTokActivityHook, which builds the extension's TikTokPreferenceFragment inside that activity. Preference category classes build the pages and rows.

The hook checks the launch intent. It accepts the Hushfeed settings extra or the `morphe_settings` action, then returns to TikTok's original path for other launches. An activity dump naming `AdPersonalizationActivity` can therefore describe Hushfeed settings rather than TikTok's ad controls. Record the intent and visible screen together. The physical-device pause check in the [runtime report](runtime-results-2026-10-09.md) used this reused activity with `morphe_settings`.

Settings.java declares runtime setting keys, types, defaults, and availability rules. BaseSettings and Setting in extensions/shared/library provide storage and common behavior. SettingsStatus loads settings and exposes the values that bytecode hooks read. A setting key is persisted on the user's device, so keep existing keys stable across updates. A renamed key needs a migration.

An install-time Morphe patch option is different from an in-app setting. For example, AMOLED dark theme has a color option applied while building the patched APK. Advanced downloads depends on the Settings patch and exposes runtime switches in TikTok's Hushfeed settings. The generated catalog lists those dependencies and patch options.

The detailed in-app settings pages are separate from Morphe Manager's patch categories. Quiet Index groups these pages on the home screen since 0.70.0; their category classes and persisted setting keys remain the same:

| Settings page | Category class |
| --- | --- |
| App | ExtensionPreferenceCategory |
| Comments | CommentsPreferenceCategory |
| Diagnostics | DebugPreferenceCategory |
| Downloads | DownloadsPreferenceCategory |
| Feed filter | FeedFilterPreferenceCategory |
| Feed tabs | FeedNavigationPreferenceCategory |
| Feed screen | InterfacePreferenceCategory |
| Inbox | InboxPreferenceCategory |
| Playback | PlaybackPreferenceCategory |
| Privacy | PrivacyPreferenceCategory |
| Screen time | ScreenTimePreferenceCategory |
| Share sheet | SharePreferenceCategory |
| Region | SimSpoofPreferenceCategory |

Translations live in extensions/tiktok/src/main/l10n/. The generator scripts/gen-l10n.py refreshes the English base table and generated translation class. Run it after editing translation tables. The tests compare the sources and generated output.

## Settings design concepts

The October 12 refresh keeps Quiet Index’s navigation and gives every native page the same rounded panels, compact heading and pink controls. The boards below cover the home page, the five hubs it had then, all fourteen detailed pages, search, Feature Gate Lab and its details, plus the creator editor. They are design targets, not screenshots of the running app. On October 13 the four group hubs came out and their pages moved onto the home page under headings (see [Home page coverage](#home-page-coverage)), so the hub boards now only show the panel style.

| Pages | Design target | Native captures |
| --- | --- | --- |
| Home, Feed & layout, Feed screen, Backup and restore | [Open board](assets/settings-design-2026-10-12/01-home-feed-backup.png) | [Home](../assets/settings/settings.png), [Home, scrolled to the end](../assets/settings/settings-end.png), [Feed screen](../assets/settings/interface.png), [Backup](../assets/settings/backup.png) |
| Comments & inbox, Downloads & sharing, App & advanced, About | [Open board](assets/settings-design-2026-10-12/02-hubs.png) | [About](../assets/settings/hub-about.png). The other three hubs' pages sit under headings on the home page now. |
| Feed filter, Feed tabs, Playback, Screen time | [Open board](assets/settings-design-2026-10-12/03-feed-playback-time.png) | [Feed filter](../assets/settings/feed_filter.png), [Feed tabs](../assets/settings/feed_navigation.png), [Playback](../assets/settings/playback.png), [Screen time](../assets/settings/screen_time.png) |
| Comments, Inbox, Downloads, Share sheet | [Open board](assets/settings-design-2026-10-12/04-conversations-sharing.png) | [Comments](../assets/settings/comments.png), [Inbox](../assets/settings/inbox.png), [Downloads](../assets/settings/downloads.png), [Share sheet](../assets/settings/share.png) |
| Privacy, App, Region, Diagnostics | [Open board](assets/settings-design-2026-10-12/05-privacy-app-region-diagnostics.png) | [Privacy](../assets/settings/privacy.png), [App](../assets/settings/behavior.png), [Region](../assets/settings/region.png), [Diagnostics](../assets/settings/diagnostics.png) |
| Feature Gate Lab, Gate details, Search, Creator editor | [Open board](assets/settings-design-2026-10-12/06-lab-search-editor.png) | [Lab](../assets/settings/lab.png), [Gate details](../assets/settings/gate_details.png), [Search](../assets/settings/search.png), [Creator editor](../assets/settings/creator-list.png) |

The earlier navigation studies remain below as background.

The previous home repeated Feed filter, Privacy and Screen time as shortcuts above their full rows. Quiet Index replaced that stack with a compact status beside the Hushfeed title, search and seven groups. Since October 13 the groups are gone and every page is a row on the home page, because the Hush settings standard allows at most one level below the main screen. Detailed controls remain on their existing pages.

| Concept | Preview | Navigation tradeoff |
| --- | --- | --- |
| Quiet Index | [Open mockup](assets/settings-design-2026-10-09/quiet-index.png) | Selected and shipped in 0.70.0. Seven grouped destinations keep the home compact. Related pages move one level deeper. |
| Three-domain Workspace | [Open mockup](assets/settings-design-2026-10-09/three-domain-workspace.png) | Experience, Privacy and Tools divide the library. Less scrolling, but users need to learn which tab owns a setting. |
| Focused Controls | [Open mockup](assets/settings-design-2026-10-09/focused-controls.png) | Expandable groups expose common controls in place. Faster adjustments, but expansion state and accidental changes need attention. |

### Home page coverage

| Home heading | Rows, each opening one page or doing one thing |
| --- | --- |
| Your feed | Feed filter, Feed tabs, Feed screen, Playback, Screen time |
| Conversations | Comments, Inbox |
| Saving and sharing | Downloads, Share sheet |
| Privacy and app | Privacy, App, Region, Backup and restore, Feature Gate Lab |
| Hushfeed | Status card, Pause Hushfeed, Diagnostics, About Hushfeed, Report a problem, Support Hushfeed |

About Hushfeed holds the build details, the pending What's new, attribution and licenses.

The tabbed concept puts Region beside Privacy. Its Tools tab owns App, maintenance and About. The expandable concept combines Playback with Screen time, puts Region beside Privacy, and keeps maintenance under Tools & backup. Those are proposed groupings, not new patch behavior.

### Native implementation

TikTokPreferenceFragment.createMasterMenu builds the home page. Each heading is a compact SectionHeadingPreference, added only once one of its pages is available from the installed patches, so a small patch selection never shows an empty heading. Every Section gets one compact SettingsMenuPreference row. The rows carry no count of changed settings any more; that badge went with the group pages it was shown on. The Hub enum is down to About Hushfeed, the one page that isn't a section. SettingsHeaderPreference holds the title and compact status, with SettingsSearchEntryPreference beneath it. Every page opens one level from home, and Back returns there. A restart marker written by an older build that names one of the removed groups (`hub:FEED_LAYOUT` and the rest) opens the home page, since forPage no longer knows those names.

Search still indexes individual controls and checklist members, including retained aliases for renamed settings. A result opens the existing page and scrolls to the matching row. A Pause result opens the home page scrolled to the Pause row. This is a navigation change. It leaves persisted setting keys, defaults, dependency explanations, backup and undo behavior in their existing owners.

The home uses a black base, 40 dp pink icon tiles and one grouped list. Settings panels use 18 dp corners, fields use 16 dp corners, and switches have pill tracks. Page titles use 34 sp with the existing large-text and word-fitting rules. At 390 by 844 the home shows search, Quick setups and the first page row without scrolling. Shared overlay geometry stays independent of settings geometry.

The mockups use a 480 by 960 logical frame. The native pages keep a slightly denser rhythm, and the Lab uses a compact toolbar so search and filters leave room for the gate list. Action rows retain their actual behavior: Reset and Undo do not gain navigation chevrons. The creator dialog wraps its content on a device; the offscreen capture uses a full-height host.

The compact status has Active, Paused and Restart pending text states. Tapping it scrolls the home page down to the full status card under the Hushfeed heading, which holds the detailed status and recovery actions, with Pause right below it. The Pause description and paused summary say that Hushfeed's switches pause while saved settings and the changes made when patching, like the app's name or icon, stay. They no longer describe Pause as an unpatched app. Mockup switch positions are examples, not captured device settings. No protection score or battery-saving claim is part of the design.

The mockups establish hierarchy and category coverage. They are not evidence of native layout or device acceptance. Use the implementation's rendered screenshots and recorded checks for font scaling, accessibility, interaction and localization. Rendered checks cover both themes, navigation, translated text, twice the text size and right-to-left layout.

The final signed build was exercised on a Galaxy S22 running Android 16 and TikTok 47.1.4 at 450dpi, in light mode with the default text size. The walkthrough visited all 24 surfaces above, including search and the creator editor. The final home and five hubs were recaptured after correcting icon scaling at phone density. Existing app data and settings were preserved. Dark mode and enlarged text were checked through the rendered suite. TalkBack interaction was not rerun for this refresh.

## Adding or changing a patch

1. Start from the exact patch name in patches-list.json. Read its Kotlin declaration, dependencies, runtime hook, tests, and the matching README entry.
2. Confirm the behavior against the declared APK. Record the package, version, and signer. Do not use the 38.3.3 factory APK as a bytecode or resource reference.
3. Put the patch in the closest existing feature package. Use a narrow fingerprint and validate the exact number or shape of matches. PatchContracts.kt provides helpers that fail with a patch-specific message when a match disappears, becomes ambiguous, or changes shape.
4. Decide whether the control belongs in Manager at patch time or in Hushfeed settings at runtime. For a runtime switch, add its stable key and default in Settings.java, connect it to the category row, and inject a hook that reads the setting. Add translation entries when new setting text is introduced.
5. Add a patch test for the selected bytecode or resource contract. Add a runtime test beside the Java feature when behavior can be exercised without TikTok's private runtime. Keep APK-dependent tests tied to the external fixture directory.
6. Regenerate patches-list.json from the built Morphe bundle. Do not hand-edit generated rows.
7. Run the README's test and bundle sequence. The all-patches verifier invokes the Morphe desktop patcher against each declared fixture. Unit tests alone do not prove that the patcher can apply a patch to TikTok.
8. For UI or device-specific behavior, install a signed build only on a test device where the existing TikTok signing key matches. The device helper's -Replace option removes TikTok and its local data, so it is not a safe shortcut for inspecting a factory install.

## Moving to a newer TikTok target

Treat a target update as a new reverse-engineering pass. TikTok can reuse short resource names for unrelated views, change obfuscated method shapes, alter server-backed defaults, or move a feature into a dynamic module.

1. Verify the exact universal APK and any split bundle. Record the package, version name, version code, signer, and APK hash. Update AppCompatibilities.kt and any patch that reads a version code.
2. Re-record the content-marker corpus from real videos with tools/verification-probe/record-markers.ps1. Refresh ContentMarkerCorpusTest inputs and the Feature Gate Lab catalog with :patches:generateGateCatalog.
3. Re-read the target's view tree and dynamic-module owners. Update patches/src/test/resources/view-id-anchors.txt with the owning class and the matching code. Replace stale resource names instead of retaining fallbacks that can resolve to a different view.
4. Review resource optimizer inventories, language-pack files, and resource IDs against the new APK. Keep the path and digest checks version-specific.
5. Set HUSHFEED_FIXTURE_DIR to the reviewed APKs, run the patch and runtime tests, then apply the complete patch set with scripts/verify-all-patches.ps1 and the Morphe desktop CLI. Tests do not invoke the patcher, so passing tests alone is not target approval.
6. Run the [patch audit](#patch-audit) on a phone with the full catalog applied, fix what fails, and commit the new results file next to the old one. Then update the README target details, changelog, generated catalog, and release facts as one reviewed target move.

## Verification layers

| Check | What it proves |
| --- | --- |
| :extensions:tiktok:test | Runtime Java behavior through Robolectric |
| :extensions:tiktok:lint and :extensions:shared:library:lint | Android API-floor calls and guards |
| :patches:test | Patch logic, contracts, generated documentation, and catalog consistency |
| :patches:generatePatchesList | Catalog generated from the compiled patch bundle |
| :patches:buildAndroid plus :patches:verifyBundle | Bundle payloads, manifest version, checksum, compatibility description, and catalog match |
| scripts/verify-all-patches.ps1 | Each patch applies through the Morphe desktop patcher to every declared APK fixture |
| scripts/validate-release-facts.ps1 | README, catalog, bundle index, fixture results, and release facts agree |
| audit/run-audit.ps1 | Every patch does what its description says on a phone running the full catalog |
| Device acceptance | The injected runtime hook behaves in the real TikTok UI on the supported version |

The APK fixtures are vendor files outside the repository. Set HUSHFEED_FIXTURE_DIR to their folder. Set HUSHFEED_DESKTOP_JAR to the Morphe desktop CLI jar for the apply-all verification. CONTRIBUTING.md documents the other script inputs and the pre-push checks.

For traffic, background work and battery checks, follow the [runtime observation protocol](runtime-observation.md). It separates actual transfers from API attempts, whole-phone charge from per-app estimates, and paused runtime settings from static APK changes. Its source map identifies the controls that can change the workload. The [October 9 measurements](runtime-results-2026-10-09.md) add a physical-device example on TikTok 47.1.4 with Hushfeed 0.68.0 paused, including capture limits that future checks must handle.

## Patch audit

The verifier above proves each patch applies. The audit proves each one still works once TikTok runs. audit/catalog.json has one entry per patch: its settings with their defaults, where it shows, and the checks that prove it. A check is a list of steps the runner carries out in order. Some read the patched APK (an emptied library, a manifest line, a string in the dex). Others drive the phone through the verification probe: flip a Hushfeed setting, open the feed or a search, ask the probe what's on screen and hold the answer to a regex, wait for a log line, or save a screenshot with the exact thing a person should look for. Most checks compare the switch off with the switch on, so a hider that hides nothing fails instead of passing quietly.

Results go to `audit/results/tiktok-<version>.json`, one file per TikTok version, with the APK hash, the Hushfeed commit, the phone's model and Android version (never its serial), and each check's status (pass, fail, review, inconclusive, error or skipped), why it failed and which evidence it left. Inconclusive means a precondition never held, like no video with comments turning up in eight swipes, so it says nothing about the patch. The evidence (screenshots and probe answers) goes to `-OutDir`, a temp folder unless you pass one. The results file names each piece by its file name in that folder and never by a path on your machine, since it gets committed.

To audit a new TikTok version:

1. Patch the full catalog with scripts/patch-for-device.ps1, signed with the key of the TikTok already installed on the test device. For installation, supply `-Serial`, `-LeaseDir` and `-ChatIdentity`. A local build without `-Serial` needs no lease.
2. Build and install the probe with tools/verification-probe/build.ps1, signed with the same key. Its installation and uninstall paths require the same lease arguments.
3. Run `audit/run-audit.ps1 -Apk <patched apk> -BuildReceipt <patched apk>.audit-build.json -Bundle <mpp> -Serial <emulator> -LeaseDir <shared lease folder> -ChatIdentity <task identifier> -OutDir <evidence folder> -Install`. The device builder writes the receipt beside its signed APK. The runner verifies that receipt against the APK, bundle and clean source manifest, then compares the installed base APK hash before starting checks. The Windows device scripts also accept `HUSHFEED_DEVICE_LEASE_DIR` and `HUSHFEED_CHAT_IDENTITY`. They hold an exclusive lease through their device operations and stop if ownership expires or is lost. Existing leases are never reclaimed automatically. `-Only 'Patch name,category:Feed'` reruns a part, and `-NoDevice` runs just the APK checks.
4. Look at every screenshot a review check saved, judged against its review text. Write the verdicts as `{"Patch name/check-id": {"status": "pass", "note": "...", "receiptId": "<check receiptId>"}}` and merge them with `-ApplyVerdicts <file> -Results <results file> -OutDir <evidence folder>`. Only undecided review checks accept `pass` or `fail`. The importer checks the definition, execution receipt and actual evidence hashes before changing any results.
5. Fix each failing patch, add a test for what broke, and rerun that patch with `-Only` until it passes.
6. Diff the new results file against the previous version's. A check that passed before and fails now is the first thing to look at.

Run `audit/run-audit.ps1 -ValidateResults -Results <results file> -OutDir <evidence folder>` to check saved evidence without rewriting it. Add `-Apk <expected apk>` to qualify it against another artifact. Missing, duplicate, removed, unknown-status and changed or unversioned checks are reported by patch/check key. A partial run keeps old records, but each check must match its definition, build receipt, APK, available evidence and executable-input digest. The digest covers the committed runtime, patch resources, build tools and other executable inputs across the project. It changes conservatively when any of those inputs change. Documentation and audit outputs are excluded. Current-checkout validation refuses uncommitted or hidden executable inputs. Explicit historical-commit validation reads that committed tree. Legacy records without an input digest keep their verdicts but need a new bound run before they qualify. Without a verified build receipt, a run is explicitly unbound. Device-build receipts also retain the stock and patched manifest package, version and version code, both APK hashes, exact patch selection/options, bundle hash and patch-report hash. A version-code override may differ from stock. Every ordinary check requires its patch selected; a negative check must declare `patchSelection: absent`. Older device-build receipts remain historical.

PineDrama gets its own audit. `audit/catalog-pinedrama.json` holds an entry for each of its patches, with checks that open PineDrama's own tabs (Home, Discover, Inbox, Profile, no Friends) and use its `snssdk845221` links. The runner picks the catalog, probe build, tabs and results name from the APK's package, so a PineDrama run writes `audit/results/pinedrama-<version>.json`. Build its probe with `tools/verification-probe/build.ps1 -Profile pinedrama-47.5.3-audit-v1`, and check the catalog without a device with `audit/run-audit.ps1 -Validate -CatalogPackage com.ss.android.ttmd.video`. A release needs a clean audit of every declared package: every check passed, none left in review, inconclusive, failed or skipped.

The verification probe sends diagnostic reports in encoded frames with byte counts and a SHA-256 digest. Missing or damaged frames leave the check inconclusive. Rebuild the probe before rerunning these report checks. A missing current video is also inconclusive when the check declares that video as a prerequisite; a probe exception or an observed expectation failure still fails.

Release receipt schema 3 enumerates every declared package/version pair. Its pre-patching input ledger and patch results retain source and output APK manifests/hashes, requested patches/options, applied patch names and the raw CLI report hash. Every exact declared input needs an independently collected native audit bound to that package, input hash, bundle and clean source. Forced probes don't supply missing declared coverage. Older published receipts remain historical and cannot authorize a new publication.

Walk receipt schema 2 retains the verified native build receipt and installed APK identity before and after the walk. Publication compares its stock APK and exact patch/options selection with the release fixture. The global TikTok profile refuses other packages; PineDrama needs separate native audit and whole-app walk profiles.

Unreleased startup checks can read 32 bounded first-frame callback records from `SessionPlaybackHold`. They keep weak controller/model/manager references and `elapsedRealtime` timestamps, with direct source-key validation around the existing native reads. The records stay in memory and produce no logs. Pair them only with the same source's stable keyed prepare timestamp. This measures receipt of the extension callback. Displayed-frame timing requires a separate observation.

The saved 47.1.4 report is historical mixed-build evidence, not one complete current-build pass. It contains 366 of the catalog's 368 checks and five APK digests. `Hide video overlays/opened-video-like-count-clear` and `Translate comments/video-translations-bound` have no saved result. Its original verdicts remain intact; none have been assigned new receipts retroactively.

A patch whose screen moved or whose setting changed needs its catalog entry changed in the same commit as the fix. The runner restores every setting a patch's checks touched and turns Log diagnostics on only for the run. It never taps anything labeled Follow, Message, Send, Post or the like, so checks can't reach other people. Keep new checks inside the same limits: no posting, liking, following, messaging or going LIVE, and no strangers' stories or profiles. Opening a creator's profile with Ghost mode off sends them a profile view, so a check that needs a profile uses your own or @tiktok, and one that could land on a profile if its patch broke (a swipe left) turns Ghost mode on too.

A `tap` step finds its node in a uiautomator dump by `text`, `desc` or `label` (either of the two, for TikTok's Compose pages such as Settings and privacy, which put a row's words in its description and leave its text empty). uiautomator can't dump a screen while a video plays behind it, which includes the comment sheet. Inside the sheet, press through the probe instead: a `findtext` probe step with `"match": "regex"` finds a label by pattern, `"click": "true"` clicks it, `"hold": "900"` holds a finger on it for a long-press menu, and `"sibling": "next"` moves to the view right after the label for an icon with no text (the sort glyph beside "189 comments"). An empty field matches by its hint. The probe drops the invisible direction marks TikTok puts around numbers, so a pattern can start at a digit. Ids are renamed every build, so a route by id is a last resort.

A few habits keep checks honest on any phone. Match text sizes in sp (the probe's `textSizeSp` and `textSp`), never pixels, since pixels follow the screen's density. A settings list only builds the rows on screen, so a `findtext` for a row lower down takes `"retrySwipe": 4`, which scrolls and asks again. A `log` step only sees lines written after the step before it started, so a line logged at startup goes straight after the `restart`. A theme check switches the phone with `{"night": "yes"}` or `"no"`, and the runner puts the phone's own mode back when that patch is done. `{"open": "appinfo"}` shows Android's App info page, the same name and icon the launcher uses, on a page that looks the same on every phone. `{"vibrations": "none"}` or `"some"` reads Android's own record of what it played for TikTok since the check started (or since the last such step), so a haptics check doesn't depend on a log line. An animation that Android plays (a screen sliding in) can't be read from TikTok at all, so `{"record": "start"}` films the steps after it and `{"record": "stop", "transition": "still"}` or `"animated"` counts the frames part way between the first screen and the last (it needs ffmpeg). A test phone often keeps Android's animation scales at 0, where nothing slides and no animator runs, so such a check starts with `{"animations": "on"}`, which sets all three scales to 1 until that patch is done, when the runner puts the phone's own values back. TikTok has its own Light or Dark choice, and while it says Light the phone's night mode changes nothing, so a dark-theme check uses `{"theme": "dark"}` (or `"light"`) followed by a restart; the probe writes TikTok's stored choice and the runner puts the old one back after the patch. When a check needs a certain kind of video (one with 10,000+ likes or comments, one 15 seconds or longer, a creator with a story), let `videoinfo` pick it with `retrySwipe` and `"precondition": true`, so a feed that never serves one reads inconclusive instead of failing. A switch flipped while TikTok is open only reaches what TikTok draws after it. The next video is usually laid out already and a profile header keeps its icons, so read a few swipes later or after a `restart`, and make a switch that only acts as TikTok draws something a restart switch.

The test commands and their required order are maintained in [README.md](../README.md#building-from-source). The release procedure and fixture setup live in [CONTRIBUTING.md](../CONTRIBUTING.md). Update those instructions when the Gradle task graph or release gates change.

## Updating this guide

Refresh the target table and factory-install note when a new TikTok build is inspected. Update the module map when payload boundaries change. Patch counts are a dated snapshot; patches-list.json remains the authoritative list. Keep device observations tied to the exact TikTok version and mark any behavior that was not reached without an account.
