"""Release text for Hushfeed: the CHANGELOG cut, GitHub notes, the guide and the index.

  py -3.13 tools/release_text.py cut --version 0.70.0 [--date 2026-10-09]
  py -3.13 tools/release_text.py github-notes --version 0.70.0 --intro FILE --checked FILE --out FILE
  py -3.13 tools/release_text.py guide [--date 2026-10-09]
  py -3.13 tools/release_text.py index --version 0.70.0 --created 2026-10-09T17:03:42
        --readme-summary FILE --bundle-summary FILE --device-line TEXT

scripts/release/release.ps1 runs them in order; they can also run alone. Text that doesn't line up
gets fixed where it can be and a warning where it can't, since wording never holds a release back.
The indexed bundle description ends with the cut release's ### groups so Morphe Manager can show the
current release's full change list.

Between releases the README's install steps and the bug form name the TikTok build the published
release patches, and a "- Next release:" line under Supported target names the one main declares.
index moves both onto that build and drops the line.
"""

import argparse
import datetime
import json
import re
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "CHANGELOG.md"
NOTES = ROOT / "extensions/tiktok/src/main/l10n/notes"
LANGUAGES = ("az", "de", "es", "in", "it", "pt-rBR", "ru", "tr", "zh-Hans")
SCOPE = "* **TikTok:** "
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
GUIDE = "concepts/patch-development.md"
# The README's Supported target lines, and the line naming the build the next release patches.
SUPPORTED_VERSION = re.compile(
    r"^- Version: \[(?P<version>\d+\.\d+\.\d+)\]\((?P<url>[^)\s]+)\), on APKMirror since (?P<date>\d{4}-\d{2}-\d{2})\.$",
    re.MULTILINE)
SUPPORTED_BUILD = re.compile(r"^- Build: version code .*$", re.MULTILINE)
SUPPORTED_SHA = re.compile(r"^(- SHA-256 of the APK every patch was verified against: )`[0-9a-f]{64}` \(\d+\.\d+\.\d+\)$",
                           re.MULTILINE)
NEXT_RELEASE = re.compile(
    r"^- Next release: TikTok (?P<version>\d+\.\d+\.\d+), \[on APKMirror\]\((?P<url>[^)\s]+)\) since "
    r"(?P<date>\d{4}-\d{2}-\d{2}) \((?P<build>version code [^)]+)\), SHA-256 `(?P<sha>[0-9a-f]{64})`\..*\n",
    re.MULTILINE)
# The README lines that tell someone which TikTok build to patch: the badge, the Important box,
# install step 1 and the two paragraphs on fetching the APK.
INSTALL_CLAIMS = ('<img alt="TikTok ', "> Hushfeed targets the global TikTok package", "1. Get the TikTok ",
                  "Google Play only ever serves", "APKMirror also offers some TikTok releases")


def fail(message: str) -> NoReturn:
    raise SystemExit(f"[release] {message}")


def warn(message: str) -> None:
    print(f"[release] warning: {message}")


def undash(text: str) -> str:
    return re.sub(r"\s*[—–]\s*", ", ", text)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def section(text: str, heading: str) -> str | None:
    """The body under a `## <heading>` line up to the next `## ` line, or None."""
    if heading == "Unreleased":
        pattern = r"^## Unreleased(?:\n|$)"
    else:
        version = heading.removesuffix(" (")
        pattern = (rf"^## {re.escape(version)} \([^\n)]+\)(?:\n|$)" if VERSION.fullmatch(version)
                   else rf"^## {re.escape(heading)}(?:\n|$)")
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        return None
    rest = text[match.end():]
    following = re.search(r"^## ", rest, re.MULTILINE)
    return rest[: following.start()] if following else rest


def bullets(body: str) -> list[str]:
    return [line for line in body.split("\n") if line.startswith("* ")]


def grouped_changelog(body: str) -> str:
    """The ### groups Manager shows. Loose bullets go under Changes, a bullet with no scope gets
    TikTok's and a line under a bullet joins it."""
    groups: dict[str, list[str]] = {}
    heading = "Changes"
    for line in body.split("\n"):
        if line.startswith("### "):
            heading = line[4:].strip() or "Changes"
        elif line.startswith("* "):
            groups.setdefault(heading, []).append(line if re.match(r"\* \*\*[^*]+:\*\* ", line) else SCOPE + line[2:])
        elif line.strip() and groups.get(heading):
            groups[heading][-1] += " " + line.strip()
    return "\n\n".join(f"### {name}\n\n" + "\n".join(items) for name, items in groups.items())


def cut(args) -> None:
    if not VERSION.match(args.version):
        fail(f"{args.version} isn't X.Y.Z")
    date = args.date or datetime.datetime.now().astimezone().date().isoformat()
    text = read(CHANGELOG)
    if re.search(rf"^## {re.escape(args.version)} ", text, re.MULTILINE):
        fail(f"CHANGELOG already has {args.version}")
    body = section(text, "Unreleased")
    if body is None:
        fail("CHANGELOG has no ## Unreleased")
    newest = re.search(r"^## (\d+\.\d+\.\d+) \(", text, re.MULTILINE)
    if newest and tuple(map(int, args.version.split("."))) <= tuple(map(int, newest.group(1).split("."))):
        fail(f"{args.version} must be newer than {newest.group(1)}")
    grouped = grouped_changelog(body)
    items = bullets(grouped)
    if not items:
        fail("Unreleased is empty")
    heading = f"## {args.version} ({date})"
    new = "## Unreleased\n\n" + heading + "\n\n" + grouped + "\n\n"
    start = text.index("## Unreleased\n")
    end = start + len("## Unreleased\n") + len(body)
    write(CHANGELOG, text[:start] + new + text[end:].lstrip("\n"))
    # What's new reads en.txt, one summary per bullet. A draft that doesn't line up gives way to
    # the CHANGELOG's own words.
    english = NOTES / "en.txt"
    table = read(english)
    drafted = section(table, "Unreleased")
    if drafted is None or len(bullets(drafted)) != len(items):
        warn("en.txt's Unreleased doesn't match the CHANGELOG, so it takes the CHANGELOG's bullets")
        if drafted is not None:
            table = table.replace("## Unreleased\n" + drafted, "", 1)
        table = "## Unreleased\n\n" + "\n".join(items) + "\n\n" + table.lstrip("\n")
    write(english, table.replace("## Unreleased\n", heading + "\n", 1))
    translated = 0
    for lang in LANGUAGES:
        path = NOTES / f"{lang}.txt"
        if path.exists() and (section(read(path), "Unreleased") or "").strip():
            write(path, read(path).replace("## Unreleased\n", heading + "\n", 1))
            translated += 1
    print(f"[release] {len(items)} bullets under {heading}; What's new drafted in {translated} of {len(LANGUAGES)} tables")


MANAGER = re.compile(r"Use Morphe Manager (\d+\.\d+\.\d+) or newer")


def manager_floor() -> str:
    match = MANAGER.search(read(ROOT / "README.md"))
    if not match:
        warn("README doesn't say which Morphe Manager to use, so the text says 2.3.0")
        return "2.3.0"
    return match.group(1)


# GitHub notes name the CHANGELOG's ### groups the way the other Hush releases do.
NOTE_GROUPS = {"Features": "New in this release", "Fixes": "Fixes", "Changes": "Other changes",
               "Build": "Behind the scenes"}


def github_notes(args) -> None:
    """The release body: summary, every bullet under its group, the changelog link, update steps
    and what was checked."""
    body = section(read(CHANGELOG), args.version + " (")
    if body is None:
        fail(f"CHANGELOG has no published {args.version}")
    parts = [read(Path(args.intro)).strip()]
    count = 0
    for block in grouped_changelog(body).split("\n\n### "):
        name, _, lines = block.removeprefix("### ").partition("\n\n")
        items = ["- " + item[len(SCOPE):] if item.startswith(SCOPE) else "- " + item[2:] for item in bullets(lines)]
        parts.append(NOTE_GROUPS.get(name, name) + "\n\n" + "\n".join(items))
        count += len(items)
    _, targets = catalog()
    parts.append("The [changelog](https://github.com/SysAdminDoc/hushfeed/blob/main/CHANGELOG.md) has every earlier "
                 "release too.")
    parts.append("How to update\n\n"
                 f"1. Update Morphe Manager to {manager_floor()} or newer, then refresh the Hushfeed source.\n"
                 f"2. Patch TikTok {spoken(targets)} from APKMirror. Keep the manager's existing signing key so your "
                 "login and settings carry over.")
    parts.append("The `.mpp` download is a patch bundle, not a TikTok APK. "
                 "[Installation instructions](https://github.com/SysAdminDoc/hushfeed#install).")
    parts.append("What was checked\n\n" + read(Path(args.checked)).strip())
    notes = undash("\n\n".join(parts) + "\n")
    write(Path(args.out), notes)
    print(f"[release] notes for {args.version}: {count} CHANGELOG bullets, {len(notes)} characters")


def catalog_patches() -> list:
    data = json.loads(read(ROOT / "patches-list.json"))
    return data["patches"] if isinstance(data, dict) else data


def catalog() -> tuple[int, list[str]]:
    patches = catalog_patches()
    versions = set()
    for patch in patches:
        for app in patch.get("compatibility") or []:
            versions.update(t["version"] for t in app.get("targets") or []
                            if t.get("version") and not t.get("experimental"))
    return len(patches), sorted(versions, key=lambda v: tuple(map(int, v.split("."))))


def spoken(items: list[str]) -> str:
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


SMALL = ("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")


def counted(n: int) -> str:
    """A count as the prose around it writes one: words up to ten, digits above."""
    return SMALL[n] if 0 <= n < len(SMALL) else str(n)


def swap(edits: dict, path: Path, pattern: str, replacement: str) -> None:
    """One line rewritten into `edits` (path to new text)."""
    text = edits.get(path)
    new, count = re.subn(pattern, replacement, read(path) if text is None else text, count=1, flags=re.MULTILINE)
    if count != 1:
        warn(f"{path.name}: nothing matched {pattern[:60]!r}, so that line stays as it was")
    edits[path] = new


def source_version() -> str:
    match = re.search(r"^version = (\d+\.\d+\.\d+)$", read(ROOT / "gradle.properties"), re.MULTILINE)
    if not match:
        fail("gradle.properties has no version = X.Y.Z line")
    return match.group(1)


def guide(args) -> None:
    """The developer guide's Source snapshot line and category table, from gradle.properties
    and patches-list.json."""
    version = source_version()
    count, _ = catalog()
    categories: dict[str, int] = {}
    for patch in catalog_patches():
        name = str(patch.get("category") or "")
        categories[name] = categories.get(name, 0) + 1
    rows = "".join(f"| {name} | {categories[name]} |\n" for name in sorted(categories))
    date = args.date or datetime.datetime.now().astimezone().date().isoformat()
    path = ROOT / GUIDE
    edits = {}
    swap(edits, path, r"^Source snapshot: version \d+\.\d+\.\d+, \d+ patch entries\.",
         f"Source snapshot: version {version}, {count} patch entries.")
    swap(edits, path, r"last synced with the source on \d{4}-\d{2}-\d{2}", f"last synced with the source on {date}")
    swap(edits, path, r"catalog's \d+ patches are grouped as follows:\n\n(\| Manager category[^\n]*\n[^\n]*\n)(?:\|[^\n]*\n)+",
         f"catalog's {count} patches are grouped as follows:\n\n\\g<1>{rows}")
    write(path, edits[path])
    print(f"[release] {GUIDE} reads source {version} with {count} patch entries")


def release_page(url: str) -> str:
    """The APKMirror page for the whole release an APK link points into, or the link itself."""
    page = re.match(r".*?-release/", url)
    return page.group(0) if page else url


def fold_next_release(text: str, targets: list[str]) -> str:
    """The README with its install steps moved onto the build the release being indexed patches.

    A README with neither a Supported target '- Version:' line nor a '- Next release:' line (the
    test fixtures) comes back as it was, and so does one that already installs the targets and has
    no '- Next release:' line."""
    supported = list(SUPPORTED_VERSION.finditer(text))
    announced = re.findall(r"^- Next release:", text, re.MULTILINE)
    if not supported and not announced and not re.search(r"^- Version: ", text, re.MULTILINE):
        return text
    if len(supported) != 1 or len(re.findall(r"^- Version: ", text, re.MULTILINE)) != 1:
        warn("README.md needs one Supported target line reading '- Version: [X.Y.Z](url), on APKMirror since "
             "YYYY-MM-DD.'")
        return text
    current = supported[0]
    upcoming = list(NEXT_RELEASE.finditer(text))
    if len(upcoming) != len(announced):
        warn("README.md has a '- Next release:' line that doesn't read 'TikTok X.Y.Z, [on APKMirror](url) since "
             "YYYY-MM-DD (version code ...), SHA-256 `...`.'")
        return text
    if not upcoming:
        if [current["version"]] != targets:
            warn(f"README.md installs TikTok {current['version']} and has no '- Next release:' line for TikTok "
                 f"{spoken(targets)}, the build the patches declare")
            return text
        return text
    if len(upcoming) > 1:
        warn("README.md has more than one '- Next release:' line")
        return text
    upcoming = upcoming[0]
    if [upcoming["version"]] != targets:
        warn(f"README.md's '- Next release:' line names TikTok {upcoming['version']}, but the patches declare "
             f"TikTok {spoken(targets)}")
        return text
    if upcoming["version"] == current["version"]:
        warn(f"README.md already installs TikTok {current['version']}, so its '- Next release:' line is left over")
        return text
    folded = text[:upcoming.start()] + text[upcoming.end():]
    folded = SUPPORTED_VERSION.sub(
        lambda _: f"- Version: [{upcoming['version']}]({upcoming['url']}), on APKMirror since {upcoming['date']}.",
        folded, count=1)
    folded, builds = SUPPORTED_BUILD.subn(lambda _: f"- Build: {upcoming['build']}", folded)
    folded, hashes = SUPPORTED_SHA.subn(lambda m: f"{m.group(1)}`{upcoming['sha']}` ({upcoming['version']})", folded)
    if builds != 1 or hashes != 1:
        warn("README.md needs one Supported target '- Build:' line and one '- SHA-256 of the APK' line")
        return text
    old = re.compile(rf"(?<![\d.]){re.escape(current['version'])}(?!\.?\d)")
    # APKMirror writes the version into its links with dashes, so a link the swap above missed shows as that.
    old_link = re.compile(rf"tiktok-{re.escape(current['version'].replace('.', '-'))}(?![\d])")
    # Step 1 also links the release's own page, where APKMirror lists the bundle beside the APK.
    pages = release_page(current["url"]), release_page(upcoming["url"])
    lines, seen = [], set()
    for line in folded.split("\n"):
        claim = next((c for c in INSTALL_CLAIMS
                      if (c in line if c == INSTALL_CLAIMS[0] else line.startswith(c))), None)
        if claim:
            seen.add(claim)
            line = line.replace(current["url"], upcoming["url"]).replace(*pages)
            line = old.sub(lambda _: upcoming["version"], line)
            if old_link.search(line):
                warn(f"README.md links TikTok {current['version']} somewhere other than its Supported target link: "
                     f"{line[:80]}")
                return text
        lines.append(line)
    missing = [c for c in INSTALL_CLAIMS if c not in seen]
    if missing:
        warn(f"README.md has no install line with {missing[0]!r} to move onto TikTok {upcoming['version']}")
        return text
    return "\n".join(lines)


def index(args) -> None:
    count, targets = catalog()
    if not targets:
        fail("patches-list.json names no TikTok versions")
    manager = manager_floor()
    readme_summary = read(Path(args.readme_summary)).strip()
    bundle_summary = read(Path(args.bundle_summary)).strip()
    release_body = section(read(CHANGELOG), args.version)
    if release_body is None:
        fail(f"CHANGELOG has no dated {args.version} heading. Run cut first.")
    groups = grouped_changelog(release_body)
    apks = "the supported APK" if len(targets) == 1 else f"all {counted(len(targets))} supported APKs"
    readme_summary = undash(readme_summary)
    description = undash(
        f"Hushfeed v{args.version} has {count} patches for global TikTok {spoken(targets)} (com.zhiliaoapp.musically).\n\n"
        + bundle_summary + "\n\n"
        + f"All {count} patches applied to {apks}."
        + (f" {args.device_line.strip()}" if args.device_line else "") + "\n\n"
        + f"Needs Morphe Manager {manager} or newer. Refresh the Hushfeed source to update. The .mpp is a patch "
        + "bundle, not a TikTok APK. Keep the manager's existing signing key when updating."
        + "\n\n" + groups
    )
    bundle_path = ROOT / "patches-bundle.json"
    bundle = json.loads(read(bundle_path))
    bundle.update({
        "created_at": args.created,
        "description": description,
        "download_url": f"https://github.com/SysAdminDoc/hushfeed/releases/download/v{args.version}/patches-{args.version}.mpp",
        "version": args.version,
    })
    # Manager pins the first key a source signs with and checks every bundle against it.
    public_key = ROOT / "morphe-signing.pub"
    if public_key.is_file():
        bundle["public_key"] = read(public_key).strip()
    readme = ROOT / "README.md"
    edits = {readme: fold_next_release(read(readme), targets)}
    swap(edits, readme, r"^Hushfeed v\d+\.\d+\.\d+ contains \d+ patches for TikTok .*$",
         f"Hushfeed v{args.version} contains {count} patches for TikTok {spoken(targets)}. New in this one: "
         f"{readme_summary} It needs Morphe Manager {manager} or newer.")
    swap(edits, readme, r"^The main branch contains \d+ patches, .*$",
         f"The main branch contains {count} patches, the same set as v{args.version}.")
    swap(edits, readme, r"shows all \d+ Hushfeed patches", f"shows all {count} Hushfeed patches")
    swap(edits, ROOT / ".github/ISSUE_TEMPLATE/bug_report.yml",
         r"placeholder: Version \d+\.\d+\.\d+ for TikTok \d+\.\d+\.\d+(?!\.?\d)",
         f"placeholder: Version {args.version} for TikTok {targets[-1]}")
    swap(edits, ROOT / GUIDE, r"^Published bundle: version \d+\.\d+\.\d+, \d+ patches\.",
         f"Published bundle: version {args.version}, {count} patches.")
    for path, text in edits.items():
        write(path, text)
    write(bundle_path, json.dumps(bundle, indent=2, ensure_ascii=False) + "\n")
    print(f"[release] index, README, bug form and guide point at v{args.version} ({count} patches, TikTok {spoken(targets)})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("cut")
    p.add_argument("--version", required=True)
    p.add_argument("--date")
    p.set_defaults(run=cut)
    p = sub.add_parser("github-notes")
    for name in ("--version", "--intro", "--checked", "--out"):
        p.add_argument(name, required=True)
    p.set_defaults(run=github_notes)
    p = sub.add_parser("guide")
    p.add_argument("--date")
    p.set_defaults(run=guide)
    p = sub.add_parser("index")
    for name in ("--version", "--created", "--readme-summary", "--bundle-summary"):
        p.add_argument(name, required=True)
    p.add_argument("--device-line", default="")
    p.set_defaults(run=index)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
