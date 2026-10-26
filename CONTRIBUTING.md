# Contributing

Feature ideas, bug reports, and pull requests are welcome.

For the source map, target boundary, factory install notes, and patch workflow, see the [app and patch development map](concepts/patch-development.md). For current ad routes, tracking scope, signed-in settings, observed onboarding, and patch opportunities, see the [TikTok app audit](concepts/tiktok-app-audit.md).

Not sure it's a bug, or just have a question? Start in [Discussions](https://github.com/SysAdminDoc/hushfeed/discussions). If it turns out to be a bug, it moves to an issue from there.

If you open an issue, include:

- the TikTok package name and version
- the Morphe Manager version and Hushfeed bundle version
- every selected patch, including the settings patch
- the affected surface, such as Feed, Inbox, Comments or Downloads
- the expected and actual behavior
- ordered reproduction steps, including each Hushfeed setting changed before the first failure
- logs or screenshots if the issue is visual or crash related

GitHub doesn't let the person who opened an issue reopen it once a maintainer closes it. So a
closing comment always says how to get it reopened: comment there and we'll reopen it.

Small focused pull requests are easier to test than large mixed changes.

For patch changes, please include what APK version you tested against and what behavior you verified.

Diagnostic tools can copy or save a redacted report. Attach that report instead of raw logcat
when possible, and remove any private messages or account details from screenshots.

Before publishing a release, run `scripts/validate-release-facts.ps1` after the runtime tests,
the patch tests and patch list generation. It checks the generated version, target package,
target version, patch count and both test counts against the README and `patches-bundle.json`.
Run the patch tests with `HUSHFEED_FIXTURE_DIR` set: a skipped fixture test fails the check,
because the description can only quote a run that read the fixtures. The check also holds the
bug report form's placeholders to the published version, the TikTok target and the Manager floor,
so update them in the same commit that moves `patches-bundle.json` to the new release. After uploading the
bundle and `SHA256SUMS.txt`, run it again with `-VerifyPublishedAsset` to check the indexed URL,
the local artifact hash and the hosted checksum entry. The local release helpers are
`scripts/gen-l10n.py`, `scripts/verify-all-patches.ps1`, `scripts/patch-for-device.ps1` and
`scripts/measure-patch-heap.ps1`. The last three need a Morphe desktop jar and a fixture APK.
Anything more than one of them needs goes in `scripts/common.ps1`, which they all dot-source:
the work-directory path guard, the cleanup that refuses to delete outside it, the version read
and the desktop CLI lookup live there rather than in four copies that drift apart. Run
`scripts/test-script-contracts.ps1` after changing a PowerShell helper. The local pre-push hook
runs it automatically for files under `scripts/`. A change to `scripts/ResourceTableCheck.java`
or `scripts/verify-all-patches.ps1` also runs `scripts/test-resource-table-check.ps1`, which
builds small APKs with the SDK's aapt2 and checks that a lost id, file, style item or reference
fails and names the id.
The jar runs on JDK 21 or newer, so set `HUSHFEED_JAVA` or `JAVA_HOME` if that is not the `java`
first on your PATH. An explicit `-Java` directory must contain `bin/java.exe` or `bin/java`.

Commit and push the new source version while `patches-bundle.json` still names the previous
working release. Build from that exact commit, publish its bundle and checksum, then update the
index and repository description in a second commit. The pre-push gate allows the index to lag
only during that first step. During the index push, it checks the artifact timestamp against the
published version tag, not the later index commit. It won't compare a new artifact with an old
release.

The unreleased receipt format qualifies every package/version pair in `patches-list.json`. Run the build stage from PowerShell with explicit inputs when the APKs live in separate folders, for example `& ./scripts/release/release.ps1 -Stage build -Fixture @('C:\fixtures\tiktok.apk', 'C:\fixtures\pinedrama-merged.apk')`. Each source must match that package's manifest, hash and exact patch selection. Native audits must independently match those inputs and the build's options. Historical receipts can't authorize a new publication. The current whole-app walk profile covers global TikTok only, so PineDrama's missing audit and walk still prevent publication.

`pinedrama-47.5.3-capabilities-v1` is a separate read-only prerequisite inspection. Build its probe with `tools/verification-probe/build.ps1 -Profile pinedrama-47.5.3-capabilities-v1` and the target's signing key. `New-PineDramaInspectionPlan` in `tools/verification-probe/probe-log.ps1` requires the exact stock/patched APK, device-build receipt and bundle. It refuses split-required APKs and UI commands. Its result is always incomplete and cannot replace native feature checks or a whole-app walk.

Immutable publications must pass both `gh release verify` and `gh release verify-asset`.
Add `-RequireImmutableRelease` to the published-asset check when preparing that publication.
Prepare a draft with every final asset attached before publishing, since an immutable release
locks the tag and files. Release attestations identify the tag, commit and uploaded bytes.
The existing reproducible-build checks still establish what the source produces.

For a signed publication, keep the private key outside the repository and commit only the
reviewed `cosign.pub`. Attach the Sigstore bundle and the matching `.sig` before publishing,
then update `signature_download_url` to the Sigstore bundle. Use stable Cosign 3.1.3 or newer
and add `-RequireBundleSignature` to the published-asset check. Normal transparency
verification stays enabled. Existing unsigned releases print a skip unless signatures are required.

Run `scripts/test-release-attestation.ps1` and `scripts/test-release-signature.ps1` after
changing these helpers. The pre-push gate selects their focused contracts when their inputs
change. Attestation tests include native command boundaries. Signature policy fixtures check
the version floor, pinned-key arguments and rejection paths. A real production signature still
needs verification against the held signing key and final artifact.

When the TikTok target moves, record the content-marker corpus again on the new build.
`ContentMarkerCorpusTest` replays shapes recorded from real videos through the AI label, paid
partnership, series and playlist filters, because hand-built test objects never carried the
defaults TikTok actually sends. `tools/verification-probe/record-markers.ps1` records them on the
test phone, route by route, and `-Write` turns the recordings into
`extensions/tiktok/src/test/resources/feed-markers/<route>-<version>.json`. The files hold shapes
only: no ids, names or captions.

The view ids the extension looks up by name move too. TikTok hands its short resource names out
again on every build, mostly to other views, so a name that still resolves can be the wrong one.
`patches/src/test/resources/view-id-anchors.txt` lists every lookup with an owner where there is
one: a class of the target, with a real name, whose code loads the id, or, written
`layout:<class>`, loads a layout that sets it. The classes of TikTok's dynamic feature modules
count too. Each module's code ships in the APK as `lib/<abi>/libdex_<module>.so`, which the test
also reads. `RuntimeViewIdAnchorsTest` fails for each owner
that no longer does so on the declared target. Read that group off the
new build's live view tree, then change the code and the table together, because the test also
holds the table to the code. Replace the old name rather than keeping it as a fallback. On the new
build the old name belongs to some other view, and a fallback hides or clicks that view wherever
the new name finds nothing. The test only lets a group look up more than one name when its entry
in `MORE_THAN_ONE_NAME` says why.

Translations live in `extensions/tiktok/src/main/l10n/`, either as a tab separated `<code>.tsv`
or as the `source,target` CSV a Weblate export gives you. `scripts/gen-l10n.py` reads both and
writes `en.csv`, the base a Weblate project translates from. Rerun it after touching a table:
the tests compare the tables against the generated class and fail if the two disagree.
The release check also refuses results older than the sources, so rerun the tests after an edit
rather than reusing the last run's XML.

Run `scripts/install-hooks.ps1` once per checkout. It installs a pre-push hook that runs the
runtime tests when a push changes anything under `extensions/` or `patches/`, the release check
when it changes `README.md`, `gradle.properties`, `patches-list.json` or `patches-bundle.json`,
and the script contract tests when it changes anything under `scripts/`. A push that changes a
patch under `patches/src/main`, or the patcher pin in `gradle/libs.versions.toml`, also builds the
bundle and applies every patch to each TikTok build the catalog declares, all of them at once,
with `scripts/verify-all-patches.ps1` and the desktop CLI. The tests never run the patcher, and a
fingerprint that matched nothing once passed all of them. Nothing builds on
GitHub, so a push is the last place any of them can run. Set `HUSHFEED_SKIP_PRE_PUSH=1` to push
without it. Every check looks at each commit the push carries, with that commit's own copy of
the check. It works in place only when that commit is HEAD and nothing in the working tree differs
from it. Otherwise it uses a clean worktree of the commit in the temp folder, one push at a time,
so work that isn't part of the push can neither fail it nor pass it. An in-place check reads the
tree again when it finishes, even after a build failure, and stops the push if anything changed
meanwhile: a tree that moved during a build invalidates the result whether it passed or failed.
Each commit is checked by its own copy of the check script, so a renamed or removed helper
cannot cause one commit's check to run through another commit's code. Don't commit to the branch
you're pushing until the push ends: git reads the branch again after the hook, so a commit made
meanwhile would go out unchecked, and the hook refuses the push when it sees the branch moved. The one exception
is the push that rewrites `patches-bundle.json`: it's checked against the bundle and test results
this checkout built, so it has to come from a clean checkout of the commit it pushes.

## Settings for your machine

Nothing in the repository points at a folder or a phone on anybody's machine. These variables do
that instead, and none of them has a default:

- `HUSHFEED_FIXTURE_DIR` is the folder that holds the vendor TikTok APKs the fixture tests read.
  They're hundreds of megabytes each, so they aren't in the repository. When it's unset the
  fixture tests skip and say which variable to set. When it names a folder that holds none of
  them they fail, because a skip there would look like a pass. `scripts/patch-for-device.ps1` and
  `scripts/verify-injected-registers.ps1` take their default APK from the same folder. The
  pre-push hook refuses a push that changes a patch unless this folder holds one universal APK of
  every declared build.
- `HUSHFEED_DESKTOP_JAR` is the Morphe desktop CLI jar. `HUSHFEED_WORKDIR` or a jar under
  `build/morphe-tools` works too, except in the pre-push hook, which reads this variable only.
- `HUSHFEED_BUILD_WRAPPER` names a PowerShell script the pre-push hook runs Gradle through,
  called as `<wrapper> -ProjectDir <repository> -Tasks <task>...`. It helps when several builds
  share one machine and need to queue. Unset, the hook runs `gradlew.bat` itself.
- `HUSHFEED_GATE_SERIAL` set to `1` makes the pre-push hook apply the bundle to one fixture at a
  time. Each desktop CLI run can take a quarter of the machine's memory, so on a machine that's
  short of it the runs shouldn't overlap. Unset, they all run at once.
- `HUSHFEED_DEVICE_SERIAL` is the adb serial of the one test phone `scripts/phone.sh` may drive.
  The script refuses every other device, and it won't run at all while this is unset. Keep your
  own phone out of it.

## Source notices

Preserve every existing copyright, license, author-credit, and source-origin
notice when modifying or moving a file. Do not remove or replace a notice unless
the attributed code is removed from the file.

New source written specifically for this project may use:

```text
/*
 * Copyright <year> Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
```

For source derived from another project, retain its existing notices and add a
clear `Forked from:` URL when the origin is not already stated. Do not use a
project-only copyright header in a way that implies exclusive ownership of
upstream code.
