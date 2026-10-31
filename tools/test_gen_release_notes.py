"""Compile generated notes and read the exact payload back through the JVM."""

import contextlib
import importlib.util
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_notes", ROOT / "tools/gen-release-notes.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)
# py -I leaves the script's own folder off the path.
sys.path.insert(0, str(ROOT / "tools"))
import release_text  # pyright: ignore[reportMissingImports]


class ReleaseNotesGeneratorTest(unittest.TestCase):
    def setUp(self):
        saved = generator.CHANGELOG, generator.OUTPUT, generator.NOTES
        self.addCleanup(lambda: self.restore(saved))

    @staticmethod
    def restore(saved):
        generator.CHANGELOG, generator.OUTPUT, generator.NOTES = saved

    def test_large_payloads_compile_and_round_trip_without_losing_history(self):
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
        self.assertIsNotNone(java, "a JDK is required")
        self.assertIsNotNone(javac, "a JDK is required")
        payloads = {
            "one long ASCII line": "a" * 70_000,
            "supplementary characters": "🚀" * 12_000,
            "NUL uses two modified UTF-8 bytes": "a\0" * 25_000,
            "quotes backslashes and control characters": '\"\\\t\b\f\x01\x7f' * 10_000,
        }
        for name, payload in payloads.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                changelog = "## Unreleased\nDraft stays out.\n\n## 0.67.0 (date)\n* **TikTok:** " + payload
                changelog += "\n\n## 0.60.0 (date)\n* **TikTok:** Oldest kept.\n\n## 0.59.0 (date)\nToo old.\n"
                source = root / "ReleaseNotesData.java"
                changelog_path = root / "CHANGELOG.md"
                changelog_path.write_text(changelog, encoding="utf-8")
                generator.CHANGELOG = changelog_path
                generator.OUTPUT = source
                generator.NOTES = root / "notes"
                generator.NOTES.mkdir()
                (generator.NOTES / "en.txt").write_text(generator.published(changelog), encoding="utf-8", newline="\n")
                with contextlib.redirect_stdout(io.StringIO()):
                    generator.main()
                capture = root / "CaptureNotes.java"
                capture.write_text(
                    'import java.nio.file.*; import java.nio.charset.StandardCharsets;\n'
                    'public class CaptureNotes { public static void main(String[] args) throws Exception {\n'
                    'String text = (String) Class.forName("app.morphe.extension.tiktok.settings.preference.ReleaseNotesData")'
                    '.getField("TEXT").get(null);\n'
                    'Files.write(Path.of(args[0]), text.getBytes(StandardCharsets.UTF_8)); } }\n',
                    encoding="utf-8",
                )
                compiled = subprocess.run(
                    [javac, "-encoding", "UTF-8", "-d", folder, str(source), str(capture)],
                    capture_output=True, text=True, encoding="utf-8", check=False,
                )
                self.assertEqual(0, compiled.returncode, compiled.stderr[-2000:])
                restored = root / "restored.txt"
                result = subprocess.run(
                    [java, "-cp", folder, "CaptureNotes", str(restored)],
                    capture_output=True, text=True, encoding="utf-8", check=False,
                )
                self.assertEqual(0, result.returncode, result.stderr[-2000:])
                self.assertEqual(generator.bare(generator.published(changelog)).encode("utf-8"),
                                 restored.read_bytes())
                self.assertNotIn(b"**TikTok:**", restored.read_bytes())


class RecentReleasesTest(unittest.TestCase):
    """Only the newest releases that fit BUDGET are embedded, their translations counted."""
    ENGLISH = "\n\n".join(f"## 0.{minor}.0 (date)\n\n* **TikTok:** " + "x" * 100 for minor in (70, 69, 68)) + "\n"

    def setUp(self):
        saved = generator.BUDGET
        self.addCleanup(lambda: setattr(generator, "BUDGET", saved))
        self.section = generator.size(generator.releases(self.ENGLISH)[(0, 70, 0)])
        generator.BUDGET = 2 * self.section + 10

    def test_the_newest_releases_that_fit_are_kept_in_order(self):
        english, translated = generator.recent(self.ENGLISH, {})
        self.assertEqual(self.ENGLISH.split("\n\n## 0.68.0")[0] + "\n", english)
        self.assertEqual({}, translated)

    def test_translations_count_against_the_budget_and_follow_the_cut(self):
        german = ("## 0.70.0 (date)\n\n* **TikTok:** " + "ü" * 60 + "\n\n"
                  "## 0.69.0 (date)\n\n* **TikTok:** Alt.\n")
        english, translated = generator.recent(self.ENGLISH, {"de": german})
        self.assertEqual([(0, 70, 0)], list(generator.releases(english)))
        self.assertEqual({"de": "## 0.70.0 (date)\n\n* **TikTok:** " + "ü" * 60 + "\n"}, translated)

    def test_the_newest_release_goes_in_even_over_the_budget(self):
        generator.BUDGET = 10
        english, translated = generator.recent(self.ENGLISH, {"de": "## 0.69.0 (date)\n\n* **TikTok:** Alt.\n"})
        self.assertEqual([(0, 70, 0)], list(generator.releases(english)))
        self.assertEqual({}, translated)

    def test_sizes_are_class_file_bytes(self):
        self.assertEqual(1 + 2 + 2 + 3 + 6, generator.size("a\0ü€🚀"))


class TranslatedNotesGateTest(unittest.TestCase):
    ENGLISH = "## 0.68.0 (date)" + 2 * chr(10) + "* **TikTok:** One." + 2 * chr(10) + "* **TikTok:** Two." + 2 * chr(10) + "## 0.67.0 (date)" + 2 * chr(10) + "* Old." + chr(10)
    SECTION = "## 0.68.0 (date)" + 2 * chr(10) + "* **TikTok:** Eins." + 2 * chr(10) + "* **TikTok:** Zwei." + chr(10)

    def notes(self, files):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        for language, text in files.items():
            (root / f"{language}.txt").write_text(text, encoding="utf-8", newline="\n")
        generator.NOTES = root
        return generator.translations(self.ENGLISH)

    def test_a_release_translated_for_every_table_is_embedded_for_each(self):
        found = self.notes({language: self.SECTION for language in generator.LANGUAGES})
        self.assertEqual(set(generator.LANGUAGES), set(found))
        self.assertTrue(found["de"].startswith("## 0.68.0 (date)"))

    def test_a_release_translated_for_one_table_only_fails(self):
        with self.assertRaisesRegex(ValueError, "but not az, es, in, it, pt-rBR, ru, tr, zh-Hans: "):
            self.notes({"de": self.SECTION})

    def test_both_tools_name_every_settings_table_and_each_has_its_notes(self):
        # A new language adds its table, its notes and a line in each tool's LANGUAGES together.
        l10n = ROOT / "extensions/tiktok/src/main/l10n"
        tables = {path.stem for path in l10n.iterdir() if path.suffix in (".tsv", ".csv")} - {"en"}
        notes = {path.stem for path in (l10n / "notes").glob("*.txt")} - {"en"}
        self.assertEqual(tables, set(generator.LANGUAGES))
        self.assertEqual(notes, set(generator.LANGUAGES))
        self.assertEqual(generator.LANGUAGES, release_text.LANGUAGES)
        self.assertEqual(set(release_text.FOREIGN), set(release_text.LANGUAGES))

    def test_a_translation_missing_a_bullet_fails(self):
        files = {language: self.SECTION for language in generator.LANGUAGES}
        files["ru"] = "## 0.68.0 (date)" + 2 * chr(10) + "* **TikTok:** Один." + chr(10)
        with self.assertRaisesRegex(ValueError, "ru.txt 0.68.0: 1 bullets, expected 2"):
            self.notes(files)

    def test_a_release_the_changelog_never_published_fails(self):
        files = {language: "## 0.69.0 (date)" + 2 * chr(10) + "* Neu." + chr(10) for language in generator.LANGUAGES}
        with self.assertRaisesRegex(ValueError, "hasn't published"):
            self.notes(files)

    def test_notes_for_a_language_without_a_table_fail(self):
        with self.assertRaisesRegex(ValueError, "fr.txt has no settings table"):
            self.notes({"fr": self.SECTION})

    def test_translation_dates_and_issue_order_must_match_the_summaries(self):
        for altered, why in ((self.SECTION.replace("(date)", "(other)"), "heading differs"),
                             (self.SECTION.replace("Eins.", "Eins (#1)."), "issue references")):
            with self.subTest(why=why), self.assertRaisesRegex(ValueError, why):
                self.notes({language: altered for language in generator.LANGUAGES})


class EnglishSummariesGateTest(unittest.TestCase):
    SOURCE = ("## 0.68.0 (date)\n\n* **TikTok:** Long explanation. The actual change is here (#1).\n\n"
              "* **TikTok:** Another explanation (#2, #3).\n\n"
              "## 0.60.0 (old)\n\n* **TikTok:** Oldest.\n")
    SUMMARIES = SOURCE.replace("Long explanation. The actual change is here", "The actual change")

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        saved = generator.NOTES
        self.addCleanup(lambda: setattr(generator, "NOTES", saved))
        generator.NOTES = Path(folder.name)
        self.path = generator.NOTES / "en.txt"

    def check(self, text):
        self.path.write_text(text, encoding="utf-8", newline="\n")
        return generator.summaries(self.SOURCE)

    def test_authored_summaries_keep_all_changes_and_exclude_drafts(self):
        result = self.check("## Unreleased\n\n* **TikTok:** Draft.\n\n" + self.SUMMARIES)
        self.assertEqual(self.SUMMARIES, result)
        self.assertNotIn("Long explanation", result)

    def test_missing_extra_duplicate_or_reordered_releases_fail(self):
        alterations = [self.SUMMARIES.split("## 0.60.0")[0],
                       "## 0.69.0 (date)\n\n* **TikTok:** Extra.\n\n" + self.SUMMARIES,
                       self.SUMMARIES + "\n" + self.SUMMARIES,
                       "## 0.60.0 (old)\n\n* **TikTok:** Oldest.\n\n" + self.SUMMARIES]
        for altered in alterations:
            with self.subTest(text=altered[:40]), self.assertRaises(ValueError):
                self.check(altered)

    def test_missing_bullets_dates_refs_or_continued_lines_fail(self):
        for altered in (self.SUMMARIES.replace("* **TikTok:** Oldest.\n", ""),
                        self.SUMMARIES.replace("(old)", "(wrong)"),
                        self.SUMMARIES.replace("#2, #3", "#3, #2"),
                        self.SUMMARIES.replace("Oldest.", "Oldest.\nContinued.")):
            with self.subTest(text=altered[-80:]), self.assertRaises(ValueError):
                self.check(altered)

    def test_missing_english_file_fails_instead_of_embedding_full_changelog(self):
        with self.assertRaises(FileNotFoundError):
            generator.summaries(self.SOURCE)


if __name__ == "__main__":
    unittest.main()
