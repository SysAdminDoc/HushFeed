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

A release is one command: `scripts/release/release.ps1 -Version <x.y.z> -Summary <file>`. It works in its own worktree and runs these steps in order: cut, test, bundle, apply, walk, publish, index. If a step fails, fix the cause and run it again with `-From <step>`. After the cut, only a fix for a failed test or a broken walk goes into the release.

The steps run what actually proves the bundle: the runtime and patch tests once, lint, the bundle build, every patch applied to each APK the catalog declares, a whole-app walk of each declared app patched from that exact bundle, and the Android runtime's own verifier over the patched TikTok. Nothing goes public until those pass. The release assets go up before `patches-bundle.json` moves, so its `download_url` always points at a file that exists.

The local helpers are `scripts/gen-l10n.py`, `scripts/verify-all-patches.ps1` and `scripts/patch-for-device.ps1`. The last two need a Morphe desktop jar and a fixture APK. Anything more than one of them needs goes in `scripts/common.ps1`, which they all dot-source. The jar runs on JDK 21 or newer, so set `HUSHFEED_JAVA` or `JAVA_HOME` if that isn't the `java` first on your PATH. An explicit `-Java` directory must contain `bin/java.exe` or `bin/java`.

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

Run `scripts/install-hooks.ps1` once per checkout. It installs a pre-push hook that takes a couple of seconds: it refuses a commit naming an AI tool as author or co-author, and an added line carrying a device serial or a home folder path. Set `HUSHFEED_SKIP_PRE_PUSH=1` to push past a false match. Builds and tests run with the work and at release time, never on a push.

## Settings for your machine

Native patch options for runtime defaults come from `patches/settings-defaults-owners.json`.
Assign each configurable Java setting to its owning patch there; internal state and legacy keys
need an explicit exclusion reason. `scripts/gen-patch-defaults.ps1` reads the setting declarations
and emits their types, defaults and numeric ranges into the bundle's settings catalog. After
changing a setting or its ownership, run `pwsh -File scripts/gen-patch-defaults.ps1` and regenerate
`patches-list.json` with the usual Gradle task. `pwsh -File scripts/gen-patch-defaults.ps1 -Check`
verifies the checked-in catalog without changing it. These helpers require PowerShell 7 and no
additional dependencies.

Nothing in the repository points at a folder or a phone on anybody's machine. These variables do
that instead, and none of them has a default:

- `HUSHFEED_FIXTURE_DIR` is the folder that holds the vendor TikTok APKs the fixture tests read.
  They're hundreds of megabytes each, so they aren't in the repository. When it's unset the
  fixture tests skip and say which variable to set. When it names a folder that holds none of
  them they fail, because a skip there would look like a pass. `scripts/patch-for-device.ps1` and
  `scripts/verify-injected-registers.ps1` take their default APK from the same folder.
- `HUSHFEED_DESKTOP_JAR` is the Morphe desktop CLI jar. `HUSHFEED_WORKDIR` or a jar under
  `build/morphe-tools` works too, except in the release command, which reads this variable only.
- `HUSHFEED_BUILD_WRAPPER` names a PowerShell script the release command runs Gradle through,
  called as `<wrapper> -ProjectDir <repository> -Tasks <task>...`. It helps when several builds
  share one machine and need to queue. Unset, it runs `gradlew.bat` itself.
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
