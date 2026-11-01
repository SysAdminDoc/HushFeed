"""Embed the newest curated release summaries in the Android extension.

Translated sections live in extensions/tiktok/src/main/l10n/notes/<table>.txt, one file per
settings table, with en.txt holding the English summaries. A release in en.txt that doesn't
line up with the CHANGELOG embeds the CHANGELOG's own bullets, with a warning. A translation
goes in only when every table has that release (#91), so What's new never shows one language
a release in its own words and the next language the English.

Only the newest releases that fit BUDGET are embedded, counting each release's English and all
of its translations. Every release's notes and nine tables went in once, and the class reached
798 KB, about 14% of the bundle's extension. What's new links the full CHANGELOG for anything
older. The "**TikTok:** " label every summary opens with stays out too, since the dialog drops
it: 0.80.0 alone carried it 1,070 times and pushed the class past 150 KB.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "CHANGELOG.md"
OUTPUT = ROOT / "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/ReleaseNotesData.java"
NOTES = ROOT / "extensions/tiktok/src/main/l10n/notes"
LANGUAGES = ("az", "de", "es", "in", "it", "pt-rBR", "ru", "tr", "zh-Hans")
START = (0, 60, 0)
# Class-file bytes of notes text across TEXT and every table, which keeps the compiled
# ReleaseNotesData under 150 KB. The newest release goes in even when it alone is larger.
BUDGET = 140_000
HEADING = re.compile(r"^## (\d+)\.(\d+)\.(\d+) \(", re.MULTILINE)
LABEL = re.compile(r"^\* \*\*TikTok:\*\* ", re.MULTILINE)


def published(text: str) -> str:
    text = text.replace("\r\n", "\n")
    matches = list(HEADING.finditer(text))
    if not matches:
        raise ValueError("CHANGELOG has no published release headings")
    sections = []
    for index, match in enumerate(matches):
        if tuple(map(int, match.groups())) < START:
            break
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        sections.append(text[match.start():end].strip())
    if not sections:
        raise ValueError("CHANGELOG has no release at or above 0.60.0")
    return "\n\n".join(sections) + "\n"


def releases(text: str) -> dict:
    """Each "## X.Y.Z (" section of the text, keyed by its version."""
    text = text.replace("\r\n", "\n")
    matches = list(HEADING.finditer(text))
    found = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        found[tuple(map(int, match.groups()))] = text[match.start():end].strip()
    return found


def bullets(section: str) -> int:
    return sum(1 for line in section.split("\n") if line.startswith("* "))


def name(version: tuple) -> str:
    return ".".join(map(str, version))


def table(path: Path) -> dict:
    return releases(path.read_text(encoding="utf-8-sig"))


def summaries(english: str) -> str:
    """The English summary of each embedded release, falling back to the CHANGELOG's bullets."""
    written = table(NOTES / "en.txt")
    sections = []
    for version, source in releases(english).items():
        summary = written.get(version)
        if summary is None or bullets(summary) != bullets(source):
            print(f"warning: en.txt {name(version)} doesn't line up with the CHANGELOG, embedding the CHANGELOG's bullets")
            lines = source.split("\n")
            summary = lines[0] + "\n\n" + "\n".join(line for line in lines[1:] if line.startswith("* "))
        sections.append(summary)
    return "\n\n".join(sections) + "\n"


def translations(english: str) -> dict:
    """The translated notes by table, holding only the releases every table has."""
    shipped = releases(english)
    found = {language: table(NOTES / f"{language}.txt") for language in LANGUAGES
             if (NOTES / f"{language}.txt").is_file()}
    partial = {version for sections in found.values() for version in sections
               if version in shipped and any(version not in found.get(language, {}) for language in LANGUAGES)}
    for version in sorted(partial, reverse=True):
        print(f"warning: {name(version)} isn't in every table, so What's new shows it in English")
    kept = {}
    for language, sections in found.items():
        versions = sorted((v for v in sections if v in shipped and v not in partial), reverse=True)
        if versions:
            kept[language] = "\n\n".join(sections[v] for v in versions) + "\n"
    return kept


def width(character: str) -> int:
    """A character's bytes in a class file's CONSTANT_Utf8, which counts NUL twice and
    supplementary characters as two surrogates."""
    code = ord(character)
    return 1 if 0 < code < 128 else 2 if code < 2048 else 3 if code <= 65535 else 6


def bare(text: str) -> str:
    """The summaries without the TikTok label ReleaseNotes drops when it shows them."""
    return LABEL.sub("* ", text)


def size(text: str) -> int:
    return sum(width(character) for character in text)


def recent(english: str, translated: dict) -> tuple:
    """English and each table cut to the newest releases whose notes fit BUDGET together."""
    shipped = releases(english)
    tables = {language: releases(notes) for language, notes in translated.items()}
    kept = []
    total = 0
    for version in shipped:
        cost = size(shipped[version]) + sum(size(table[version]) for table in tables.values()
                                            if version in table)
        if kept and total + cost > BUDGET:
            break
        kept.append(version)
        total += cost
    cut = {language: [table[version] for version in kept if version in table]
           for language, table in tables.items()}
    return ("\n\n".join(shipped[version] for version in kept) + "\n",
            {language: "\n\n".join(sections) + "\n" for language, sections in cut.items() if sections})


def builder(notes: str) -> list:
    """The text as StringBuilder appends of at most 30,000 class-file bytes each."""
    parts = []
    start = 0
    used = 0
    for index, character in enumerate(notes):
        if used + width(character) > 30_000:
            parts.append(notes[start:index])
            start = index
            used = 0
        used += width(character)
    parts.append(notes[start:])
    lines = []
    for part in parts:
        escaped = "".join(
            "\\\\" if character == "\\" else '\\"' if character == '"'
            else f"\\{ord(character):03o}" if ord(character) < 32 or ord(character) == 127
            else character for character in part
        )
        lines.append(f'            .append("{escaped}")')
    return lines


def constant(language: str) -> str:
    return "NOTES_" + language.upper().replace("-", "_")


def main() -> None:
    notes = summaries(published(CHANGELOG.read_text(encoding="utf-8")))
    notes, translated = recent(bare(notes), {language: bare(text)
                                             for language, text in translations(notes).items()})
    lines = [
        "/*",
        " * Copyright 2026 Hushfeed contributors",
        " * https://github.com/SysAdminDoc/hushfeed",
        " *",
        " * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).",
        " */",
        "package app.morphe.extension.tiktok.settings.preference;",
        "",
        "/** The newest curated release summaries carried inside the app. Run tools/gen-release-notes.py after release edits. */",
        "public final class ReleaseNotesData {",
        "    private ReleaseNotesData() {}",
        "    public static final String TEXT = new StringBuilder()",
    ]
    lines += builder(notes)
    lines.append("            .toString();")
    lines += [
        "",
        "    /** The translated notes for a settings table tag (lower case, as L10n reads it), or null. */",
        "    public static String translated(String tag) {",
        "        if (tag == null) return null;",
        "        switch (tag) {",
    ]
    for language in sorted(translated):
        # L10nTranslations reads Indonesian's table for both of Java's codes.
        for tag in [language.lower()] + (["id"] if language == "in" else []):
            lines.append(f'            case "{tag}":')
        lines.append(f'                return {constant(language)};')
    lines += ["            default: return null;", "        }", "    }"]
    for language in sorted(translated):
        lines.append("")
        lines.append(f"    private static final String {constant(language)} = new StringBuilder()")
        lines += builder(translated[language])
        lines.append("            .toString();")
    lines.append("}")
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"embedded {bullets(notes)} published summaries from {len(releases(notes))} releases and "
          f"{len(translated)} translations")


if __name__ == "__main__":
    main()
