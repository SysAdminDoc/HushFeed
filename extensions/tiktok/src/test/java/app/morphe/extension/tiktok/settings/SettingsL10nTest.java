package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedNavigationPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SharePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.Rule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The settings screens in the phone's language. The English text in the code is the key,
 * the generated {@link L10nTranslations} carries each language, and this walks every screen
 * to make sure nothing is left in English once German is selected.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsL10nTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {}

    /** The tables the generator wrote, which are what the app carries. */
    private static final Map<String, String> GERMAN = L10nTranslations.of("de");
    private static final Map<String, String> INDONESIAN = L10nTranslations.of("in");

    private static final String[] QUIET_INDEX_COPY = {
            "Make it yours.", "Your feed. Your rules.",
            "Your feed", "Conversations", "Saving and sharing", "Privacy and app", "Hushfeed",
            "About", "Your settings", "About Hushfeed", "Version, changes and licenses",
            "Budgets and reminders",
            "Active", "Paused", "Restart pending", "Hushfeed is active", "Hushfeed is paused",
            "Pause Hushfeed's switches after TikTok restarts. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay.",
            "Hushfeed's switches are paused. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay."
    };

    @Before
    public void clearStatusBeforeMountingSettings() throws Exception {
        setEveryStatus(false);
    }

    @After
    public void resetStatus() throws Exception {
        setEveryStatus(false);
    }

    /**
     * The reflection above only reaches preference titles and summaries. A toast is a string
     * literal at a call site, so nothing checked those until this: the runtime feedback was
     * English on every phone while the settings around it were translated.
     *
     * <p>The scan is over the whole file rather than line by line, and it looks at every
     * literal anywhere inside the call's brackets. A first attempt matched only a quote
     * straight after the opening bracket, and it went green while eleven toasts were still
     * English, because {@code showToastShort(ok ? "a" : "b")} and a call wrapped across two
     * lines both slipped past it.
     *
     * <p>The Feature Gate Lab is included now that its runtime states and failures are localized.
     * The shared extension module is not walked: it is TikTok-independent code, and this table
     * is TikTok's.
     */
    @Test public void theGeneratedTableIsTheOneInTheTables() throws Exception {
        // Every other check here reads L10nTranslations, which is generated. A value edited in a
        // table without rerunning scripts/gen-l10n.py was invisible to all of them, and so was a
        // generated file edited by hand, which its own header forbids.
        //
        // German is a tab table and Indonesian is the comma form Weblate hosts, so both readers
        // are exercised on every run rather than one of them being a claim in the README.
        for (String language : languages()) {
            java.util.Map<String, String> generated = L10nTranslations.of(language);
            java.util.Map<String, String> table = readTable(language);

            java.util.List<String> problems = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, String> row : table.entrySet()) {
                String was = generated.get(row.getKey());
                if (was == null) {
                    problems.add(language + " has a row the generated table does not: " + row.getKey());
                } else if (!was.equals(row.getValue())) {
                    problems.add(language + " differs for " + row.getKey()
                            + ": tsv has " + row.getValue() + ", generated has " + was);
                }
            }
            for (String key : generated.keySet()) {
                if (!table.containsKey(key)) {
                    problems.add(language + " generated a row the table does not have: " + key);
                }
            }
            assertEquals("the generated translations are not the ones in the " + language
                    + " table, so run scripts/gen-l10n.py: " + problems, 0, problems.size());
            assertFalse("the " + language + " table is empty", table.isEmpty());
        }
    }

    @Test public void theTableComparisonCanActuallyFail() throws Exception {
        // The comparison above only means something if a changed value is visible to it.
        java.util.Map<String, String> table = readTable("de");
        String key = table.keySet().iterator().next();
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>(table);
        changed.put(key, changed.get(key) + " x");
        assertNotEquals("a changed value read the same", table.get(key), changed.get(key));
        assertFalse("the tables compare equal after a change", table.equals(changed));
    }

    @Test public void everyQuietIndexLabelAndPauseBoundaryHasATranslation() throws Exception {
        for (String language : languages()) {
            Map<String, String> source = readTable(language);
            Map<String, String> generated = L10nTranslations.of(language);
            for (String key : QUIET_INDEX_COPY) {
                assertTrue(language + " has no source translation for " + key, source.containsKey(key));
                assertFalse(language + " has a blank translation for " + key, source.get(key).trim().isEmpty());
                assertEquals(language + " did not generate " + key, source.get(key), generated.get(key));
            }
            for (String retired : new String[]{
                    "From the next start TikTok runs as if it weren't patched, so you can tell whether a problem comes from Hushfeed. Your settings stay as they are.",
                    "TikTok runs as if it weren't patched. Your settings stay as they are."}) {
                assertFalse(language + " still carries the claim that Pause restores the original app",
                        source.containsKey(retired));
                assertFalse(language + " still bundles the claim that Pause restores the original app",
                        generated.containsKey(retired));
            }
        }
    }

    /**
     * One language table, in whichever form it is kept.
     *
     * <p>Tab separated, one key then the translation with # for a comment, or the comma form
     * Weblate hosts: a source,target header and one row per entry.
     */
    private static java.util.Map<String, String> readTable(String language) throws Exception {
        java.io.File file = tableFile(language, ".tsv");
        if (file == null) file = tableFile(language, ".csv");
        assertNotNull("no .tsv or .csv table for " + language, file);
        String body = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        // A spreadsheet round trip leaves a byte order mark, which the generator now skips.
        if (body.startsWith("\ufeff")) body = body.substring(1);
        return file.getName().endsWith(".csv")
                ? readCsvRows(language, body) : readTsvRows(language, body);
    }

    private static java.io.File l10nDirectory() {
        java.io.File directory = new java.io.File("src/main/l10n");
        if (!directory.isDirectory()) directory = new java.io.File("extensions/tiktok/src/main/l10n");
        assertTrue("could not find the l10n tables", directory.isDirectory());
        return directory;
    }

    /** Every language with a table, in either form. Named so a new one is covered on sight. */
    private static java.util.List<String> languages() {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String name : java.util.Objects.requireNonNull(l10nDirectory().list())) {
            if (!name.endsWith(".tsv") && !name.endsWith(".csv")) continue;
            // Lower-cased, because that is what the generator does. A pt-rBR table, which is the
            // spelling L10n builds for a regional tag, otherwise asks for a language the
            // generated class has never heard of and gets null back.
            String language = name.substring(0, name.length() - 4)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!ENGLISH_BASE.equals(language)) found.add(language);
        }
        java.util.Collections.sort(found);
        assertTrue("no language tables at all", found.size() >= 2);
        return found;
    }

    private static java.io.File tableFile(String language, String extension) {
        java.io.File file = new java.io.File(l10nDirectory(), language + extension);
        return file.isFile() ? file : null;
    }

    /** The keys are English text, so the base is a list of source strings, not a translation. */
    private static final String ENGLISH_BASE = "en";

    /** A newline is the two characters \n in a table, the same as the generator reads it. */
    private static String unescapeTableNewlines(String text) {
        return text.replace("\\n", "\n");
    }

    private static java.util.Map<String, String> readTsvRows(String language, String body) {
        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        for (String line : body.split("\\n")) {
            String text = line.replace("\r", "");
            if (text.isEmpty() || text.startsWith("#")) continue;
            int tab = text.indexOf('\t');
            assertTrue("a row with no tab in " + language + ".tsv: " + text, tab > 0);
            rows.put(unescapeTableNewlines(text.substring(0, tab)),
                    unescapeTableNewlines(text.substring(tab + 1)));
        }
        return rows;
    }

    /** Enough of RFC 4180 for these tables: quoted fields, doubled quotes inside them. */
    private static java.util.Map<String, String> readCsvRows(String language, String body) {
        java.util.List<java.util.List<String>> records = new java.util.ArrayList<>();
        java.util.List<String> record = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean atFieldStart = true;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (index + 1 < body.length() && body.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' && atFieldStart) {
                quoted = true;
                atFieldStart = false;
            } else if (character == ',') {
                record.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
            } else if (character == '\n' || character == '\r') {
                // A bare CR ends a record, the same as Python's csv. CRLF is one terminator,
                // so the LF after a CR is taken with it rather than ending a second, empty one.
                if (character == '\r' && index + 1 < body.length()
                        && body.charAt(index + 1) == '\n') {
                    index++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new java.util.ArrayList<>();
                atFieldStart = true;
            } else {
                field.append(character);
                atFieldStart = false;
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }

        assertFalse("the " + language + " table has no header", records.isEmpty());
        java.util.List<String> header = records.get(0);
        assertTrue("the " + language + " header has to start source,target, not " + header,
                header.size() >= 2
                        && "source".equalsIgnoreCase(header.get(0).trim())
                        && "target".equalsIgnoreCase(header.get(1).trim()));

        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        for (int index = 1; index < records.size(); index++) {
            java.util.List<String> line = records.get(index);
            if (line.isEmpty() || line.get(0).isEmpty() || line.get(0).startsWith("#")) continue;
            assertTrue("a row with no target column in " + language + ".csv: " + line,
                    line.size() >= 2);
            rows.put(unescapeTableNewlines(line.get(0)), unescapeTableNewlines(line.get(1)));
        }
        return rows;
    }

    @Test public void theCsvReaderEndsARowWhereThePythonOneDoes() {
        // No shipped table carries a bare CR, so nothing here would have caught the reader
        // swallowing it and running two rows together. These are the shapes Python's csv module
        // produces, checked against it.
        java.util.Map<String, String> unixEndings =
                readCsvRows("probe", "source,target\na,1\nb,2\n");
        assertEquals(2, unixEndings.size());
        assertEquals("1", unixEndings.get("a"));

        java.util.Map<String, String> windowsEndings =
                readCsvRows("probe", "source,target\r\na,1\r\nb,2\r\n");
        assertEquals("a CRLF was read as two row endings", 2, windowsEndings.size());
        assertEquals("1", windowsEndings.get("a"));

        java.util.Map<String, String> classicMacEndings =
                readCsvRows("probe", "source,target\ra,1\rb,2\r");
        assertEquals("a bare CR did not end a row", 2, classicMacEndings.size());
        assertEquals("1", classicMacEndings.get("a"));

        java.util.Map<String, String> quoted =
                readCsvRows("probe", "source,target\n\"a,b\",\"says \"\"hi\"\"\"\n");
        assertEquals(1, quoted.size());
        assertEquals("says \"hi\"", quoted.get("a,b"));

        java.util.Map<String, String> looseQuote = readCsvRows("probe", "source,target\na,b\"c\n");
        assertEquals("a quote inside an unquoted field opened one", "b\"c",
                looseQuote.get("a"));
    }

    @Test public void theEnglishBaseIsExactlyTheStringsTheTablesCarry() throws Exception {
        // Weblate translates from a monolingual base rather than from a language table. It is
        // generated, so it goes stale the same way L10nTranslations does if nobody reruns the
        // script, and nothing else here would notice. Both directions matter: a string added to
        // a table has to reach it, and a string deleted from every table has to leave it, or
        // translators keep being asked for text nothing shows any more.
        java.util.Map<String, String> base = readTable(ENGLISH_BASE);
        java.util.Set<String> carried = new java.util.TreeSet<>();
        for (String language : languages()) {
            for (String key : readTable(language).keySet()) {
                // A |category row is a translation of a key the base lists, not a source string.
                if (pluralBase(key).equals(key)) carried.add(key);
            }
        }

        java.util.Set<String> missing = new java.util.TreeSet<>(carried);
        missing.removeAll(base.keySet());
        assertEquals(ENGLISH_BASE + ".csv is missing source strings, so run scripts/gen-l10n.py: "
                + missing, 0, missing.size());

        java.util.Set<String> extra = new java.util.TreeSet<>(base.keySet());
        extra.removeAll(carried);
        assertEquals(ENGLISH_BASE + ".csv still lists strings no table carries, so run "
                + "scripts/gen-l10n.py: " + extra, 0, extra.size());

        for (java.util.Map.Entry<String, String> row : base.entrySet()) {
            assertEquals("the base translates a string instead of repeating it",
                    row.getKey(), row.getValue());
        }
    }

    /**
     * The Lab's own words stay in the Lab's technical block. A gate is the thing, an override
     * is what the reader sets, a rule is a saved override. "Flag" and "getter" reached the
     * settings home row, two warnings and the override note before this held them out.
     */
    @Test public void labVocabularyStaysOutOfTheSourceStrings() throws Exception {
        java.util.regex.Pattern jargon = java.util.regex.Pattern.compile(
                "(?i)\\bgetters?\\b|\\bgate flags?\\b|\\bflags\\b|\\boverride boundary\\b"
                        + "|\\bconfiguration object\\b|\\btype-checked\\b");
        List<String> offenders = new ArrayList<>();
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (jargon.matcher(key).find()) offenders.add(key);
        }
        assertEquals("Lab vocabulary in a translated string:\n" + String.join("\n", offenders),
                0, offenders.size());
    }

    /**
     * A limit the reader is told about is formatted in from the constant that enforces it, so
     * no key carries the number itself. The example list on the speed row is the one place
     * numbers are words rather than a limit.
     */
    @Test public void noKeyCarriesALimitThatLivesInACodeConstant() throws Exception {
        java.util.regex.Pattern limit = java.util.regex.Pattern.compile(
                "\\bfour seconds\\b|\\b4,?096\\b|\\b10,?000\\b|\\b256 KB\\b|\\b64 KB\\b|\\b1-1000\\b"
                        + "|\\b12 to 48\\b|\\b0\\.5 to 3\\b|\\b8 (?:comma-separated )?speeds\\b");
        List<String> offenders = new ArrayList<>();
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (limit.matcher(key).find()) offenders.add(key);
        }
        assertEquals("keys that type a limit the code holds in a constant:\n"
                + String.join("\n", offenders), 0, offenders.size());
    }

    /**
     * A failure says what happened and what to do next, as two sentences with their stops, and
     * says "couldn't" the way the rest of the screens do. Twenty keys opened "Could not", four
     * of them with no stop at all, and eight stopped at the failure with nothing to do about it.
     */
    @Test public void everyFailureMessageSaysWhatHappenedAndWhatToDoNext() throws Exception {
        // A heading over an error row: its next step is the summary under it, not a sentence of
        // its own, so it is the one failure allowed to be a single line.
        Set<String> headings = new LinkedHashSet<>(List.of("Settings couldn't open"));
        java.util.regex.Pattern failure = java.util.regex.Pattern.compile("(?i)\\bcould ?n['’]?o?t\\b");
        java.util.regex.Pattern uncontracted = java.util.regex.Pattern.compile("(?i)\\bcould not\\b");
        java.util.regex.Pattern boundary = java.util.regex.Pattern.compile("[.!?][\"”']?\\s+[A-Za-z0-9\"“%]");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (!failure.matcher(key).find() || headings.contains(key)) continue;
            checked++;
            if (uncontracted.matcher(key).find()) offenders.add("says could not: " + key);
            if (!key.endsWith(".")) offenders.add("no full stop: " + key);
            if (!boundary.matcher(key).find()) offenders.add("no next step or outcome: " + key);
        }
        assertTrue("the scan found too few failure messages to mean anything: " + checked, checked > 20);
        assertEquals("failure messages that stop short:\n" + String.join("\n", offenders),
                0, offenders.size());
    }

    @Test public void everyTranslationKeepsTheShapeOfItsKey() {
        // Defects the key-set checks cannot see. A placeholder that changed, was dropped or was
        // invented; a sentence that lost or gained its terminator; a quote pair that does not
        // close. The first pass of this check only looked at numbered placeholders in the key,
        // so a bare "%s" and an invented placeholder both walked past it.
        // Every table, not the two that happened to exist when this was written: Spanish and
        // Brazilian Portuguese were added later and went unchecked, and five Spanish rows ended
        // in a full stop their key does not have.
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            for (java.util.Map.Entry<String, String> row
                    : L10nTranslations.of(language).entrySet()) {
                // A |category row is one language's extra plural form of the other form, so
                // it is held to that form's shape.
                String key = pluralBase(row.getKey());
                String value = row.getValue();

                // Both directions and both spellings, so a dropped, changed or added
                // placeholder is all the same finding. When every placeholder is numbered,
                // a translation may use them in any order: the set must match, not the order.
                java.util.List<String> wanted = placeholders(key);
                java.util.List<String> given = placeholders(value);
                boolean allNumbered = !wanted.isEmpty()
                        && wanted.stream().allMatch(p -> p.contains("$"));
                boolean mismatch = allNumbered
                        ? !sorted(wanted).equals(sorted(given))
                        : !wanted.equals(given);
                if (mismatch) {
                    problems.add(language + " placeholders " + wanted + " became " + given
                            + " in: " + key);
                }

                // A full stop the key does not have is usually a sentence the translator ended
                // differently. Sometimes it is the abbreviation the language requires: Spanish
                // and Portuguese write maximum as "máx.", and the period belongs to the word.
                // Reading it as a terminator and taking it off produced "mín./máx", one
                // abbreviation with its period and the identical one beside it without.
                boolean abbreviated = terminator(key) == ' ' && terminator(value) == '.'
                        && endsInAnAbbreviation(value);
                if (terminator(key) != terminator(value) && !abbreviated) {
                    problems.add(language + " ends the sentence with '" + terminator(value)
                            + "' where the key ends with '" + terminator(key) + "' in: " + key);
                }

                // German opens low and closes high. Everything else here uses the English pair.
                char open = "de".equals(language) ? '\u201e' : '\u201c';
                char close = "de".equals(language) ? '\u201c' : '\u201d';
                long opened = value.chars().filter(c -> c == open).count();
                long closed = value.chars().filter(c -> c == close).count();
                if (opened != closed) {
                    problems.add(language + " opened " + opened + " quotes and closed " + closed
                            + " in: " + key);
                }
            }
        }
        assertEquals("translations that do not keep the shape of their key: " + problems,
                0, problems.size());
    }

    @Test public void theTranslationShapeCheckCanActuallyFail() {
        // A scan with nothing to find is a scan that proves nothing. These are the four shapes
        // the check exists for, put in front of it on purpose.
        assertNotEquals("a dropped placeholder", placeholders("across %1$d surfaces"),
                placeholders("auf %d Oberflachen"));
        assertEquals("reordered numbered placeholders are valid",
                sorted(placeholders("%1$s and %2$s")),
                sorted(placeholders("%2$s and %1$s")));
        assertEquals("String.format fills numbered positions regardless of order",
                "B then A",
                String.format("%2$s then %1$s", "A", "B"));
        assertNotEquals("a dropped numbered placeholder",
                sorted(placeholders("%1$s and %2$s")),
                sorted(placeholders("%1$s only")));
        assertNotEquals("an invented placeholder", placeholders("no placeholder here"),
                placeholders("keiner %1$s hier"));
        assertNotEquals("a dropped full stop", terminator("Hide the caption."),
                terminator("Beschreibung ausblenden"));
        assertNotEquals("a changed terminator", terminator("Really?"), terminator("Wirklich."));
        // And the one full stop that is not a terminator, against the sentence it must not
        // excuse. The relaxation reads the length of the last word, so a sentence that happens
        // to end in a short word is the case worth pinning.
        assertTrue("an abbreviation was read as a sentence ending",
                endsInAnAbbreviation("Comentarios mín./máx."));
        assertFalse("a real sentence ending was excused as an abbreviation",
                endsInAnAbbreviation("No está en la caché actual."));
        assertFalse("a value with no full stop was called an abbreviation",
                endsInAnAbbreviation("Komentar min/maks"));
    }

    /** Every placeholder in order, numbered or bare, so a change of either kind shows up. */
    private static java.util.List<String> placeholders(String text) {
        java.util.List<String> found = new java.util.ArrayList<>();
        var match = java.util.regex.Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]").matcher(text);
        while (match.find()) found.add(match.group());
        return found;
    }

    private static java.util.List<String> sorted(java.util.List<String> list) {
        java.util.List<String> copy = new java.util.ArrayList<>(list);
        java.util.Collections.sort(copy);
        return copy;
    }

    /**
     * Whether a trailing full stop belongs to an abbreviation rather than to a sentence.
     *
     * <p>Read from the length of the last word: "máx." is three letters and an abbreviation,
     * "the current cache." is not. Four letters is the ceiling, which covers every shortened
     * form in these tables and leaves the shortest real sentence ending alone.
     */
    private static boolean endsInAnAbbreviation(String text) {
        String trimmed = text.trim();
        if (!trimmed.endsWith(".")) return false;
        int at = trimmed.length() - 1;
        int letters = 0;
        while (at > 0 && Character.isLetter(trimmed.charAt(at - 1))) {
            at--;
            letters++;
        }
        return letters > 0 && letters <= 4;
    }

    /** The character a string ends a sentence with, or a space when it ends with none. */
    private static char terminator(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return ' ';
        char last = trimmed.charAt(trimmed.length() - 1);
        return ".!?:\u2026".indexOf(last) >= 0 ? last : ' ';
    }

    @Test public void everyRuntimeToastGoesThroughTheTable() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree from " + new java.io.File(".").getAbsolutePath(),
                root.isDirectory());

        java.util.List<String> offenders = new java.util.ArrayList<>();
        java.nio.file.Path base = root.toPath();
        int scanned = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(base)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                scanned++;
                for (String offence : rawToastsIn(text)) {
                    offenders.add(base.relativize(file) + ":" + offence);
                }
            }
        }

        assertTrue("the scan found no files to read", scanned > 20);
        assertTrue("this text reaches the reader, so it belongs in the translation table. "
                        + "Wrap it in L10n.t, or L10n.f when it carries a value:\n"
                        + String.join("\n", offenders),
                offenders.isEmpty());
    }

    /**
     * A toast of one sentence takes no full stop; a toast of several ends every sentence with
     * one. "Diagnostic data put back." next to "Seen video history put back" was the same pair
     * of toasts punctuated two ways, and nothing held them to one rule.
     */
    @Test public void everyToastFollowsThePunctuationRule() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue(root.isDirectory());
        java.util.List<String> offenders = new java.util.ArrayList<>();
        int toasts = 0;
        java.nio.file.Path base = root.toPath();
        Map<java.nio.file.Path, String> sources = new LinkedHashMap<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(base)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                if (file.getFileName().toString().startsWith("L10nTranslations")) continue;
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                sources.put(file, text);
                java.util.List<String> messages = new java.util.ArrayList<>(toastLiteralsIn(text));
                messages.addAll(toastVariableLiteralsIn(text));
                for (String message : messages) {
                    toasts++;
                    String fault = punctuationFault(message);
                    if (fault != null) offenders.add(base.relativize(file) + ": " + fault + "  \"" + message + "\"");
                }
            }
        }
        // A message handed over in a variable, through a helper or as a Lab refusal reaches the
        // same toast, and the wrap check already follows all three.
        java.util.Set<String> passedOn = new java.util.TreeSet<>(toastHelperLiteralsIn(sources));
        passedOn.addAll(refusalLiteralsIn(sources));
        for (String message : passedOn) {
            toasts++;
            String fault = punctuationFault(message);
            if (fault != null) offenders.add("(passed on): " + fault + "  \"" + message + "\"");
        }
        assertTrue("the scan found too few toasts to mean anything: " + toasts, toasts > 40);
        assertTrue("one sentence takes no full stop, several take one each:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    /** The scan sees every shape a toast call takes, and the rule can fail. */
    @Test public void theToastPunctuationRuleCanActuallyFail() {
        String source = "class T {\n"
                + "  void a() { Utils.showToastShort(L10n.t(getContext(), \"Field edits were not saved.\")); }\n"
                + "  void b() { postToast(refused ? L10n.t(context, \"Refused\")\n"
                + "      : L10n.t(Utils.getContext(), \"That file is too large.\")); }\n"
                + "  void c() { Utils.showToastLong(L10n.f(\"Saved %1$s\", name)); }\n"
                + "  // Utils.showToastShort(L10n.t(context, \"Inside a comment.\"));\n"
                + "}\n";
        assertEquals(java.util.Arrays.asList("Field edits were not saved.", "Refused",
                "That file is too large.", "Saved %1$s"), toastLiteralsIn(source));

        assertNotNull(punctuationFault("Diagnostic data put back."));
        assertNotNull(punctuationFault("Done!"));
        assertNotNull(punctuationFault("Copied 3 lines. 12 were skipped"));
        assertNotNull(punctuationFault("Saved. nothing else changed"));
        assertNull(punctuationFault("Seen videos put back"));
        assertNull(punctuationFault("Couldn't undo the clear. Try again."));
        assertNull(punctuationFault("Enter a whole number, or one like 20K, 1.5M or 2B"));
        assertNull(punctuationFault("Size limit reached. Recording saved to %1$s"));
        assertNotNull(punctuationFault("Size limit reached. Recording saved to %1$s folder"));
        assertNotNull(punctuationFault("Saved to %1$s."));
    }

    /**
     * A text toast shows at most two lines on Android 12 and later and cuts the rest off with no
     * sign. A 384 dp phone fits about 34 characters a line and word wrap breaks before that, so
     * every toast, in English and every table, has to fit two lines of 28. The toasts that were
     * already longer when this check came in are listed in toast-wrap-baseline.txt, and that
     * list only gets shorter: a new toast has to fit, and one that fits now comes off the list.
     */
    @Test public void everyToastFitsTwoLines() throws Exception {
        java.util.Set<String> messages = new java.util.TreeSet<>();
        Map<java.nio.file.Path, String> sources = toastScanSources();
        for (String source : sources.values()) {
            messages.addAll(toastLiteralsIn(source, false));
            messages.addAll(toastVariableLiteralsIn(source));
        }
        messages.addAll(toastHelperLiteralsIn(sources));
        messages.addAll(refusalLiteralsIn(sources));
        assertTrue("the scan found too few toasts to mean anything: " + messages.size(),
                messages.size() > 40);

        java.util.Set<String> over = new java.util.TreeSet<>();
        for (String message : messages) {
            if (toastLines(message) > 2) over.add(ENGLISH_BASE + "\t" + message);
        }
        for (String language : L10nTranslations.LANGUAGES) {
            java.util.Map<String, String> table = readTable(language);
            for (String message : messages) {
                String shown = table.get(message);
                if (shown != null && toastLines(shown) > 2) over.add(language + "\t" + message);
            }
        }
        java.util.Set<String> known = toastWrapBaseline();
        java.util.Set<String> added = new java.util.TreeSet<>(over);
        added.removeAll(known);
        java.util.Set<String> fixed = new java.util.TreeSet<>(known);
        fixed.removeAll(over);
        assertTrue("these toasts wrap past two lines of 28 characters, and a phone cuts off the "
                + "rest. Shorten them, or show one toast per line:\n" + baselineLines(added),
                added.isEmpty());
        assertTrue("these toasts fit two lines now, so take them off toast-wrap-baseline.txt:\n"
                + baselineLines(fixed), fixed.isEmpty());
    }

    /** The wrap count breaks where a toast does, and a long toast fails it. */
    @Test public void theToastWrapCountCanActuallyFail() {
        assertEquals(1, toastLines("Left TikTok after no touch"));
        assertEquals(2, toastLines("Couldn't undo the clear. Try again."));
        assertEquals(3, toastLines("A field value couldn't be read. Check the values and try again."));
        assertEquals("a value counts as five characters",
                3, toastLines("%1$s is imported. Turn on Use an imported font to see it."));
        assertEquals("a written line break starts a line", 3, toastLines("One\nTwo\nThree"));
        // A Chinese character is about two Latin ones wide, and a line can break between any two.
        assertEquals(1, toastLines("因无触摸已离开 TikTok"));
        assertEquals(2, toastLines("零将其关闭。屏幕在这么多分钟内没有被触摸时"));
        assertEquals(3, toastLines("零将其关闭。屏幕在这么多分钟内没有被触摸时，视频会停止，TikTok"));
    }

    /** The extension's Java a toast scan reads, without the generated translation tables. */
    private static Map<java.nio.file.Path, String> toastScanSources() throws java.io.IOException {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue(root.isDirectory());
        Map<java.nio.file.Path, String> sources = new LinkedHashMap<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root.toPath())) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                if (file.getFileName().toString().startsWith("L10nTranslations")) continue;
                sources.put(file, new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    /**
     * A toast of {@code error.getMessage()} shows whatever sentence the exception was built
     * with, and the wrap check can only measure the extension's own refusals, whose
     * constructions it reads. The download path picker toasted IllegalArgumentException's
     * message, and the allowed-folder list in it ran past two lines with nothing to catch it.
     */
    @Test public void everyToastedExceptionMessageIsARefusalTheScanReads() throws Exception {
        ToastSources sources = new ToastSources(toastScanSources());
        List<MessageToast> toasts = messageToastsIn(sources);
        List<String> unmeasured = new ArrayList<>();
        for (MessageToast toast : toasts) {
            String why = toast.unmeasured(sources);
            if (why != null) unmeasured.add(why);
        }
        refusalLiteralsIn(sources.texts, unmeasured);
        assertTrue("the scan found too few message toasts to mean anything: " + toasts.size(),
                toasts.size() >= 2);
        assertTrue("these toasts show an exception's message the wrap check can't read. Throw one "
                + "of the extension's own refusals, or show the reason somewhere it can wrap:\n"
                + String.join("\n", unmeasured), unmeasured.isEmpty());
    }

    /** A message toast is traced to the refusals its catch names, and one it can't trace fails. */
    @Test public void theMessageToastScanCanActuallyFail() {
        String sentence = "Destinations must start with Download, Movies, Pictures, DCIM or Documents";
        Map<java.nio.file.Path, String> texts = new LinkedHashMap<>();
        texts.put(java.nio.file.Paths.get("Picker.java"), "class Picker {\n"
                + "  void pick(String path) {\n"
                + "    try { check(path); }\n"
                + "    catch (IllegalArgumentException ex) { Utils.showToastLong(ex.getMessage()); }\n"
                + "  }\n"
                + "}\n");
        texts.put(java.nio.file.Paths.get("Lab.java"), "class Lab {\n"
                + "  void change() {\n"
                + "    String notice;\n"
                + "    try { notice = run(); }\n"
                + "    catch (Exception error) {\n"
                + "      boolean refused = error instanceof Refused;\n"
                + "      notice = refused ? error.getMessage() : L10n.t(\"Try again.\");\n"
                + "    }\n"
                + "    Utils.showToastLong(notice);\n"
                + "  }\n"
                + "  String run() throws Refused { throw new Refused(L10n.t(\"" + sentence + "\")); }\n"
                + "  static final class Refused extends Exception { Refused(String why) { super(why); } }\n"
                + "}\n");
        ToastSources sources = new ToastSources(texts);
        List<MessageToast> toasts = messageToastsIn(sources);
        assertEquals(2, toasts.size());
        List<String> unmeasured = new ArrayList<>();
        for (MessageToast toast : toasts) {
            String why = toast.unmeasured(sources);
            if (why != null) unmeasured.add(why);
        }
        assertEquals(unmeasured.toString(), 1, unmeasured.size());
        assertTrue(unmeasured.get(0), unmeasured.get(0).startsWith("Picker:4 "));
        assertTrue(refusalLiteralsIn(texts).contains(sentence));
        assertTrue(toastLines(sentence) > 2);
    }

    /**
     * A message read through a cause, a cast or a field chain names no exception a catch could
     * say, and one kept in a field, or in a local joined to other text, is still found and traced.
     * An instanceof guarding some other variable says nothing about the caught exception. A
     * refusal built from another exception's message, or handed a value the scan can't read,
     * fails where it's built. Each of these passed as measured before.
     */
    @Test public void theMessageToastScanFailsOnEveryShapeItCantTrace() {
        Map<java.nio.file.Path, String> texts = new LinkedHashMap<>();
        texts.put(java.nio.file.Paths.get("Shapes.java"), "class Shapes {\n"
                + "  String last;\n"
                + "  void cause() {\n"
                + "    try { run(); }\n"
                + "    catch (Refusals.Refused error) { Utils.showToastLong(error.getCause().getMessage()); }\n"
                + "  }\n"
                + "  void cast(Exception error) {\n"
                + "    Utils.showToastLong(((Refusals.Refused) error).getMessage());\n"
                + "  }\n"
                + "  void field() {\n"
                + "    Utils.showToastLong(this.holder.failure.getMessage());\n"
                + "  }\n"
                + "  void remember() {\n"
                + "    try { run(); }\n"
                + "    catch (IOException error) { last = error.getMessage(); }\n"
                + "  }\n"
                + "  void tell() { Utils.showToastLong(L10n.t(\"Failed\") + \" \" + last); }\n"
                + "  void local() {\n"
                + "    try { run(); }\n"
                + "    catch (IllegalStateException error) {\n"
                + "      String why = error.getMessage();\n"
                + "      Utils.showToastLong(L10n.t(\"Stopped\") + \": \" + why);\n"
                + "    }\n"
                + "  }\n"
                + "  void placed() {\n"
                + "    try { new Refusals().check(); }\n"
                + "    catch (Refusals.Refused | Refusals.Wrapped error) { Utils.showToastLong(error.getMessage()); }\n"
                + "  }\n"
                + "  void guarded(Object holder) {\n"
                + "    try { run(); }\n"
                + "    catch (IOException error) { if (holder instanceof Refusals.Refused) Utils.showToastLong(error.getMessage()); }\n"
                + "  }\n"
                + "}\n");
        texts.put(java.nio.file.Paths.get("Refusals.java"), "class Refusals {\n"
                + "  String reason;\n"
                + "  void check() throws Exception {\n"
                + "    if (bad) throw new Refused(L10n.t(\"Not allowed\"));\n"
                + "    if (late) throw new Refused(reason);\n"
                + "    try { read(); } catch (IOException cause) { throw new Wrapped(cause); }\n"
                + "  }\n"
                + "  static final class Refused extends Exception { Refused(String why) { super(why); } }\n"
                + "  static final class Wrapped extends Exception {\n"
                + "    Wrapped(Throwable cause) { super(cause.getMessage(), cause); }\n"
                + "  }\n"
                + "}\n");
        ToastSources sources = new ToastSources(texts);
        List<String> unmeasured = new ArrayList<>();
        for (MessageToast toast : messageToastsIn(sources)) {
            String why = toast.unmeasured(sources);
            if (why != null) unmeasured.add(why);
        }
        List<String> literals = refusalLiteralsIn(texts, unmeasured);
        List<String> places = new ArrayList<>();
        for (String why : unmeasured) places.add(why.substring(0, why.indexOf(' ')));
        // The cause, the cast and the field chain; the field, the joined local and the catch whose
        // instanceof tests another variable, none of whose catches name a class here; then Refused
        // handed a field, and Wrapped built from a cause's message.
        assertEquals(String.join("\n", unmeasured), java.util.Arrays.asList("Shapes:5", "Shapes:8",
                "Shapes:11", "Shapes:15", "Shapes:21", "Shapes:31", "Refusals:5", "Refusals:10"), places);
        assertTrue(literals.toString(), literals.contains("Not allowed"));
    }

    /**
     * A message carried into a call's brackets, as {@code L10n.f("Failed: %1$s", why)} and
     * {@code String.valueOf(why)} carry it, kept in another class's field, appended to a
     * StringBuilder, or shown as the exception itself, is found. An instanceof narrows a read only
     * when it guards it: one before it in the catch, or on the other arm of a ?:, doesn't. A
     * refusal joined to a value the scan can't read, or built in a file that imports or qualifies
     * it, fails where it's built. Each of these passed as measured before; a read a guard does
     * narrow, a boolean holding the guard, a ?: whose condition only picks a sentence, and the
     * reads in a ?:'s condition that checks the message before showing it, as
     * FeatureGateDetailFragment's runDetailChange does, pass.
     */
    @Test public void theMessageToastScanFollowsMessagesThroughBracketsBuildersAndOtherClasses() {
        Map<java.nio.file.Path, String> texts = new LinkedHashMap<>();
        texts.put(java.nio.file.Paths.get("More.java"), "class More {\n"
                + "  void bracket() {\n"
                + "    try { run(); }\n"
                + "    catch (IOException error) {\n"
                + "      String why = error.getMessage();\n"
                + "      Utils.showToastLong(L10n.f(\"Failed: %1$s\", why));\n"
                + "    }\n"
                + "  }\n"
                + "  void valueOf() {\n"
                + "    try { run(); }\n"
                + "    catch (IllegalStateException error) {\n"
                + "      String why = error.getMessage();\n"
                + "      Utils.showToastLong(String.valueOf(why));\n"
                + "    }\n"
                + "  }\n"
                + "  void stored() { Utils.showToastLong(L10n.t(\"Last error\") + \": \" + Store.lastError); }\n"
                + "  void remember() {\n"
                + "    try { run(); }\n"
                + "    catch (SecurityException error) { Store.lastError = error.getMessage(); }\n"
                + "  }\n"
                + "  void itself() {\n"
                + "    try { run(); }\n"
                + "    catch (Refusals.Refused error) { Utils.showToastLong(L10n.f(\"Refused: %1$s\", error)); }\n"
                + "  }\n"
                + "  void joined() {\n"
                + "    try { run(); }\n"
                + "    catch (Refusals.Refused error) { Utils.showToastLong(L10n.t(\"Refused\") + \" \" + error); }\n"
                + "  }\n"
                + "  void built() {\n"
                + "    try { run(); }\n"
                + "    catch (IOException error) {\n"
                + "      StringBuilder text = new StringBuilder(L10n.t(\"Failed\"));\n"
                + "      text.append(\": \").append(error.getMessage());\n"
                + "      Utils.showToastLong(text.toString());\n"
                + "    }\n"
                + "  }\n"
                + "  void unguarded() {\n"
                + "    try { run(); }\n"
                + "    catch (Exception error) {\n"
                + "      if (error instanceof Refusals.Refused) log(error);\n"
                + "      Utils.showToastLong(error.getMessage());\n"
                + "    }\n"
                + "  }\n"
                + "  void falseArm() {\n"
                + "    try { run(); }\n"
                + "    catch (Exception error) {\n"
                + "      Utils.showToastLong(error instanceof Refusals.Refused ? L10n.t(\"Refused\") : error.getMessage());\n"
                + "    }\n"
                + "  }\n"
                + "  void measuredLocal() {\n"
                + "    try { run(); }\n"
                + "    catch (Refusals.Refused error) {\n"
                + "      String why = error.getMessage();\n"
                + "      Utils.showToastLong(L10n.f(\"Refused: %1$s\", why));\n"
                + "    }\n"
                + "  }\n"
                + "  void guarded() {\n"
                + "    try { run(); }\n"
                + "    catch (Exception error) {\n"
                + "      if (error instanceof Refusals.Refused) {\n"
                + "        Utils.showToastLong(error.getMessage());\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  void either() {\n"
                + "    try { run(); }\n"
                + "    catch (Exception error) {\n"
                + "      boolean refused = error instanceof Refusals.Refused || error instanceof Refusals.Late;\n"
                + "      Utils.showToastLong(refused ? error.getMessage() : L10n.t(\"Try again\"));\n"
                + "    }\n"
                + "  }\n"
                + "  void checked() {\n"
                + "    try { run(); }\n"
                + "    catch (Exception error) {\n"
                + "      boolean refused = error instanceof Refusals.Refused;\n"
                + "      String failure = refused && error.getMessage() != null\n"
                + "          && !error.getMessage().trim().isEmpty()\n"
                + "          ? error.getMessage() : L10n.t(\"Failed\");\n"
                + "      Utils.showToastLong(failure);\n"
                + "    }\n"
                + "  }\n"
                + "}\n");
        texts.put(java.nio.file.Paths.get("Store.java"), "class Store {\n"
                + "  static String lastError;\n"
                + "  void save() {\n"
                + "    try { write(); }\n"
                + "    catch (IOException error) { lastError = error.getMessage(); }\n"
                + "  }\n"
                + "}\n");
        texts.put(java.nio.file.Paths.get("Refusals.java"), "class Refusals {\n"
                + "  void check(String path, boolean ok, int count) throws Exception {\n"
                + "    if (a) throw new Refused(L10n.t(\"Odd\") + \": \" + path);\n"
                + "    if (b) throw new Refused(ok ? L10n.t(\"Yes\") : L10n.t(\"No\"));\n"
                + "    if (c) throw new Refused(L10n.f(\"At most %1$s\", count));\n"
                + "    if (d) throw new Late(L10n.t(\"Too late\"));\n"
                + "    try { read(); } catch (IOException cause) { String why = cause.getMessage(); throw new Late(why); }\n"
                + "  }\n"
                + "  static final class Refused extends Exception { Refused(String why) { super(why); } }\n"
                + "  static final class Late extends Exception { Late(String why) { super(why); } }\n"
                + "}\n");
        texts.put(java.nio.file.Paths.get("Imported.java"), "import app.Refusals.Refused;\n"
                + "class Imported {\n"
                + "  void fail(String reason) throws Exception { throw new Refused(reason); }\n"
                + "}\n");
        texts.put(java.nio.file.Paths.get("Qualified.java"), "class Qualified {\n"
                + "  void fail() throws Exception { throw new Refusals.Refused(L10n.t(\"From elsewhere\")); }\n"
                + "}\n");
        ToastSources sources = new ToastSources(texts);
        List<String> unmeasured = new ArrayList<>();
        for (MessageToast toast : messageToastsIn(sources)) {
            String why = toast.unmeasured(sources);
            if (why != null) unmeasured.add(why);
        }
        List<String> literals = refusalLiteralsIn(texts, unmeasured);
        List<String> places = new ArrayList<>();
        for (String why : unmeasured) places.add(why.substring(0, why.indexOf(' ')));
        java.util.Collections.sort(places);
        // The local in L10n.f's and valueOf's brackets, Store.lastError's two catches, the
        // exception shown whole twice, the append, the instanceof before the read and on the other
        // arm; then Refused joined to a parameter, Late built from a cause's message, and Refused
        // built in a file that imports it from something it can't read.
        List<String> expected = new ArrayList<>(java.util.Arrays.asList("More:5", "More:12", "Store:5",
                "More:19", "More:23", "More:27", "More:33", "More:41", "More:47", "Refusals:3", "Refusals:7",
                "Imported:3"));
        java.util.Collections.sort(expected);
        assertEquals(String.join("\n", unmeasured), expected, places);
        for (String sentence : new String[]{"Yes", "No", "At most %1$s", "Too late", "From elsewhere"}) {
            assertTrue(sentence + " in " + literals, literals.contains(sentence));
        }
    }

    /** A message handed to a toast through a variable is measured too, only from its own method. */
    @Test public void theToastScanFollowsAMessageVariable() {
        String source = "class T {\n"
                + "  void a() {\n"
                + "    String message = L10n.t(\"Stopped waiting for Wi-Fi.\");\n"
                + "    Utils.showToastLong(message);\n"
                + "  }\n"
                + "  void b(String message) throws IOException { Utils.showToastShort(message); }\n"
                + "  void c() {\n"
                + "    String text = ok ? L10n.t(\"Saved\") : L10n.f(\"Failed: %1$s\", why);\n"
                + "    if (late) { text = L10n.t(\"Too late\"); }\n"
                + "    // String text = L10n.t(\"In a comment\");\n"
                + "    toastInstead(text);\n"
                + "  }\n"
                + "  void d() {\n"
                + "    String note;\n"
                + "    if (ok) note = L10n.t(\"First\"); else note = L10n.t(\"Second\");\n"
                + "    Utils.showToastLong(note);\n"
                + "  }\n"
                + "  void e() // a note before the brace\n"
                + "  {\n"
                + "    Utils.showToastShort(message);\n"
                + "  }\n"
                + "  void f() {\n"
                + "    final String later = L10n.t(\"Captured\");\n"
                + "    handler.post(new Runnable() {\n"
                + "      @Override public void run() { Utils.showToastShort(later); }\n"
                + "    });\n"
                + "  }\n"
                + "  static {\n"
                + "    Utils.showToastShort(message);\n"
                + "  }\n"
                + "  String note;\n"
                + "  void g() {\n"
                + "    post(new Runnable() { public void run() { String note = L10n.t(\"Sibling's own\"); log(note); } });\n"
                + "    post(new Runnable() { public void run() { Utils.showToastShort(note); } });\n"
                + "  }\n"
                + "  void h() {\n"
                + "    String later = L10n.t(\"Hidden by a parameter\");\n"
                + "    listen(new Callback() { public void onResult(String later) { Utils.showToastShort(later); } });\n"
                + "  }\n"
                + "  void i() {\n"
                + "    String text = L10n.t(\"Kept\");\n"
                + "    post(new Runnable() { public void run() { String text = L10n.t(\"Shadowed\"); text = L10n.t(\"Still shadowed\"); } });\n"
                + "    Utils.showToastShort(text);\n"
                + "  }\n"
                + "}\n";
        assertEquals(java.util.Arrays.asList("Stopped waiting for Wi-Fi.", "Saved", "Failed: %1$s",
                "Too late", "First", "Second", "Captured", "Kept"), toastVariableLiteralsIn(source));
    }

    /**
     * A method a toast is handed to, as the Lab's runLabChange is, shows what its calls build in
     * their brackets or hand it in a local, the retry line in its own body, and the sentence of
     * each refusal it names, in the file that names it. A local filled in a switch and translated
     * where it's shown is measured too. A toast kept in a variable names no method, and a label or
     * a same-named refusal of another class is left alone.
     */
    @Test public void theToastScanFollowsTheLabsNoticesAndTheJournal() {
        Map<java.nio.file.Path, String> sources = new LinkedHashMap<>();
        sources.put(java.nio.file.Paths.get("Lab.java"), "class Lab {\n"
                + "  void force() {\n"
                + "    runLabChange(() -> {\n"
                + "      if (none) throw new Refused(L10n.t(context, \"Nothing to force.\"));\n"
                + "      return L10n.t(context, \"Forced 3 gates of 5. The rest don't take a true or false value.\");\n"
                + "    });\n"
                + "  }\n"
                + "  void undo() { runLabChange(Undo::undo, L10n.t(context, \"Put back\")); }\n"
                + "  void imported() {\n"
                + "    String message = L10n.f(context, \"Imported %1$d\", count);\n"
                + "    runLabChange(() -> Undo.importRules(review), message);\n"
                + "  }\n"
                + "  void check() { if (bad) throw new Refused(L10n.t(context, \"Not an export\")); }\n"
                + "  void label() { title.setText(L10n.t(context, \"Not a toast\")); }\n"
                + "  private boolean runLabChange(Change change) { return runLabChange(change, Utils::showToastLong); }\n"
                + "  private boolean runLabChange(Change change, Presenter presenter) {\n"
                + "    Presenter tell = presenter;\n"
                + "    try { result = change.run(); } catch (Exception error) {\n"
                + "      result = error instanceof Refused || error instanceof Store.LimitRefused\n"
                + "          ? error.getMessage() : L10n.t(context, \"Couldn't change it\");\n"
                + "      tell = Utils::showToastLong;\n"
                + "    }\n"
                + "    return true;\n"
                + "  }\n"
                + "  private static final class Refused extends RuntimeException { Refused(String s) { super(s); } }\n"
                + "}\n");
        sources.put(java.nio.file.Paths.get("Store.java"), "class Store {\n"
                + "  void add() { if (full) throw new LimitRefused(limitMessage(9)); }\n"
                + "  static String limitMessage(int count) { return L10n.f(\"At most %1$s rules\", count); }\n"
                + "  static final class LimitRefused extends IOException { LimitRefused(String s) { super(s); } }\n"
                + "}\n");
        sources.put(java.nio.file.Paths.get("Accounts.java"), "class Accounts {\n"
                + "  void read() { throw new Refused(L10n.t(context, \"Another class's refusal\")); }\n"
                + "  static final class Refused extends RuntimeException { Refused(String s) { super(s); } }\n"
                + "}\n");
        String lab = sources.get(java.nio.file.Paths.get("Lab.java"));
        List<String> called = toastLiteralsIn(lab, false);
        assertEquals(java.util.Arrays.asList("Nothing to force.",
                "Forced 3 gates of 5. The rest don't take a true or false value.", "Put back"), called);
        assertEquals("a long Lab notice counts as more than two lines",
                3, toastLines(called.get(1)));
        assertEquals(java.util.Collections.singletonList("Imported %1$d"), toastVariableLiteralsIn(lab));
        assertEquals(java.util.Arrays.asList("Couldn't change it", "Nothing to force.", "Not an export",
                "At most %1$s rules"), refusalLiteralsIn(sources));
        assertTrue("an assigned toast names no method",
                toastPresentersIn(lab, classify(lab)).equals(java.util.Collections.singleton("runLabChange")));

        String journal = "class Journal {\n"
                + "  static void notice(Context context) {\n"
                + "    String message;\n"
                + "    switch (recovery) {\n"
                + "      case DONE: message = \"Recovered a change. \"\n"
                + "          + \"Restart TikTok to apply.\"; break;\n"
                + "      case BAD: message = ok ? \"Set aside\" : \"Couldn't read it\"; break;\n"
                + "      case OTHER: message = L10n.t(context, \"Already translated\"); break;\n"
                + "      default: return;\n"
                + "    }\n"
                + "    Utils.showToastLong(L10n.t(context, message));\n"
                + "    Utils.showToastShort(L10n.t(context, title));\n"
                + "  }\n"
                + "}\n";
        assertEquals(java.util.Arrays.asList("Recovered a change. Restart TikTok to apply.", "Set aside",
                "Couldn't read it", "Already translated"), toastLiteralsIn(journal, false));
    }

    /**
     * A message a helper method returns, or one handed to a method that toasts it, is measured
     * too: through the helper's own returns, a local it returns and the helpers it calls, and
     * through wrappers two deep, a helper's answer handed to one included. A helper's answer shown on a label, logged, or filling part of
     * another message isn't a toast, a call on a value can't be placed, and a banner's stand-in
     * toast is left out as the banners are.
     */
    @Test public void theToastScanFollowsAHelpersAnswer() {
        Map<java.nio.file.Path, String> sources = new LinkedHashMap<>();
        sources.put(java.nio.file.Paths.get("Messages.java"), "class Messages {\n"
                + "  static String busy(String key) {\n"
                + "    if (key == null) return L10n.t(\"Nothing to save\");\n"
                + "    return key.isEmpty() ? L10n.f(\"Busy with %1$s\", key) : later();\n"
                + "  }\n"
                + "  private static String later() {\n"
                + "    String text = L10n.t(\"Try again later\");\n"
                + "    return text;\n"
                + "  }\n"
                + "  static String waiting() { return L10n.t(\"Waiting for Wi-Fi\"); }\n"
                + "  static String offline() { return L10n.t(\"No connection\"); }\n"
                + "  static String loop(int n) { return n > 0 ? loop(n - 1) : L10n.t(\"Bottom\"); }\n"
                + "  static String quiet() {\n"
                + "    post(new Callable<String>() { public String call() { return L10n.t(\"Inner answer\"); } });\n"
                + "    return L10n.t(\"Outer answer\");\n"
                + "  }\n"
                + "}\n");
        sources.put(java.nio.file.Paths.get("Screen.java"), "class Screen {\n"
                + "  void a() { Utils.showToastLong(Messages.busy(key)); }\n"
                + "  void b() {\n"
                + "    String own = Messages.waiting();\n"
                + "    Utils.showToastShort(own);\n"
                + "  }\n"
                + "  void c() { Utils.showToastShort(failure()); }\n"
                + "  private String failure() {\n"
                + "    String why = L10n.t(\"Couldn't save\");\n"
                + "    return why;\n"
                + "  }\n"
                + "  void d() {\n"
                + "    label.setText(shownElsewhere());\n"
                + "    log(L10n.t(\"Logged only\"));\n"
                + "    Utils.showToastShort(L10n.f(\"Saved %1$s\", partOf()));\n"
                + "  }\n"
                + "  private String shownElsewhere() { return L10n.t(\"On a label\"); }\n"
                + "  private String partOf() { return L10n.t(\"A part\"); }\n"
                + "  void e() {\n"
                + "    Utils.showToastShort(Messages.loop(3));\n"
                + "    Utils.showToastShort(Messages.quiet());\n"
                + "  }\n"
                + "  void f() {\n"
                + "    notice(L10n.t(\"Through a wrapper\"));\n"
                + "    Notices.tell(L10n.t(\"Two wrappers deep\"));\n"
                + "    notice(Messages.offline());\n"
                + "  }\n"
                + "  private static void notice(String message) { Utils.showToastShort(message); }\n"
                + "  void g() { value.notice(L10n.t(\"On some value\")); }\n"
                + "  void h() { SettingsActionBanner.showNotice(context, L10n.t(\"A banner\")); }\n"
                + "}\n");
        sources.put(java.nio.file.Paths.get("Notices.java"), "class Notices {\n"
                + "  static void tell(String text) { relay(text, 1); }\n"
                + "  private static void relay(String message, int times) {\n"
                + "    Toast.makeText(context, message, times).show();\n"
                + "  }\n"
                + "}\n");
        sources.put(java.nio.file.Paths.get("SettingsActionBanner.java"), "class SettingsActionBanner {\n"
                + "  static void showNotice(Context context, String message) { show(context, message); }\n"
                + "  private static void show(Context context, String message) {\n"
                + "    if (root == null) Utils.showToastShort(message);\n"
                + "  }\n"
                + "}\n");
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("Nothing to save",
                        "Busy with %1$s", "Try again later", "Waiting for Wi-Fi", "Couldn't save",
                        "Saved %1$s", "Bottom", "Outer answer", "Through a wrapper", "Two wrappers deep",
                        "No connection")),
                new java.util.TreeSet<>(toastHelperLiteralsIn(sources)));
    }

    /** Columns a toast line holds before word wrap moves on. A wide character takes two. */
    private static final int TOAST_COLUMNS = 28;

    /** The lines {@code message} takes in a toast, with each value shown as five characters. */
    static int toastLines(String message) {
        String text = message.replaceAll("%\\d+\\$[sd]", "00000");
        int lines = 0;
        for (String paragraph : text.split("\n", -1)) {
            // Each piece is a word, or one wide character, which a line can end on either side
            // of: {columns, 1 when a space comes before it}.
            java.util.List<int[]> pieces = new java.util.ArrayList<>();
            int word = 0;
            boolean space = false;
            for (int index = 0; index < paragraph.length(); index++) {
                char character = paragraph.charAt(index);
                if (Character.isWhitespace(character)) {
                    if (word > 0) pieces.add(new int[]{word, space ? 1 : 0});
                    word = 0;
                    space = true;
                } else if (isWide(character)) {
                    if (word > 0) {
                        pieces.add(new int[]{word, space ? 1 : 0});
                        word = 0;
                        space = false;
                    }
                    pieces.add(new int[]{2, space ? 1 : 0});
                    space = false;
                } else {
                    word++;
                }
            }
            if (word > 0) pieces.add(new int[]{word, space ? 1 : 0});
            lines++;
            int column = 0;
            for (int[] piece : pieces) {
                int width = piece[0] + (column > 0 ? piece[1] : 0);
                if (column > 0 && column + width > TOAST_COLUMNS) {
                    lines++;
                    column = 0;
                    width = piece[0];
                }
                column += width;
                while (column > TOAST_COLUMNS) {
                    lines++;
                    column -= TOAST_COLUMNS;
                }
            }
        }
        return lines;
    }

    private static boolean isWide(char character) {
        Character.UnicodeScript script = Character.UnicodeScript.of(character);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                || (character >= 0x3000 && character <= 0x303F)
                || (character >= 0xFF00 && character <= 0xFFEF);
    }

    /** Language, a tab, then the English message, with a line break written as the two characters \n. */
    private static java.util.Set<String> toastWrapBaseline() throws Exception {
        java.io.File file = new java.io.File("src/test/resources/toast-wrap-baseline.txt");
        if (!file.isFile()) file = new java.io.File(
                "extensions/tiktok/src/test/resources/toast-wrap-baseline.txt");
        assertTrue("could not find toast-wrap-baseline.txt", file.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> known = new java.util.TreeSet<>();
        for (String line : body.split("\n")) {
            String text = line.replace("\r", "");
            if (text.isEmpty() || text.startsWith("#")) continue;
            known.add(text.replace("\\n", "\n"));
        }
        return known;
    }

    private static String baselineLines(java.util.Set<String> entries) {
        StringBuilder out = new StringBuilder();
        for (String entry : entries) out.append(entry.replace("\n", "\\n")).append('\n');
        return out.toString();
    }

    /**
     * Every literal handed to L10n.t or L10n.f directly inside a toast call.
     *
     * <p>The context argument may be a name, a call such as {@code getContext()} or a
     * qualified call such as {@code Utils.getContext()}: the first version of this scan took
     * a bare name only, and three Lab toasts written with a call walked past it carrying full
     * stops. Every literal in the call is taken, not the first, because a toast that chooses
     * between two messages with a ternary has two.
     */
    private static java.util.List<String> toastLiteralsIn(String text) {
        return toastLiteralsIn(text, true);
    }

    /** {@code banners} false leaves out the settings and undo banners, which aren't toasts. */
    private static java.util.List<String> toastLiteralsIn(String text, boolean banners) {
        byte[] kind = classify(text);
        java.util.List<String> found = new java.util.ArrayList<>();
        // The settings banners (showNotice, showUndo) and the feed's undo banner are toasts in
        // every way that matters to a reader, so they answer to the same rule.
        java.util.regex.Matcher call = java.util.regex.Pattern
                .compile("(?:\\b\\w*[Tt]oast\\w*|Toast\\s*\\.\\s*makeText"
                        + (banners ? "|\\bshowNotice|\\bshowUndo(?:Banner)?" : "")
                        + presenterCalls(text, kind) + ")\\s*\\(")
                .matcher(text);
        while (call.find()) {
            int open = call.end() - 1;
            if (kind[call.start()] != CODE) continue;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            // Every L10n call inside the toast's brackets, and every literal inside each of
            // those calls: t and f carry one, quantity carries the one and the other form.
            found.addAll(l10nLiteralsBetween(text, kind, open, close));
        }
        return found;
    }

    /**
     * Every literal handed to an L10n call that sits wholly between {@code start} and {@code end}.
     * An L10n.t handed a local String instead of a literal takes the literals that local is set
     * to, as in {@code String message; switch (...) { case A: message = "..."; } L10n.t(context,
     * message)}: the settings journal's four recovery toasts are written that way.
     */
    private static java.util.List<String> l10nLiteralsBetween(String text, byte[] kind, int start, int end) {
        java.util.List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher lookup = java.util.regex.Pattern
                .compile("L10n\\s*\\.\\s*(t|f|quantity)\\s*\\(").matcher(text);
        lookup.region(start, end);
        while (lookup.find()) {
            int lookupOpen = lookup.end() - 1;
            int lookupClose = closingBracket(text, kind, lookupOpen);
            if (lookupClose < 0 || lookupClose > end) continue;
            // arguments, not literalsIn: a message written across lines with a plus is one
            // message, and its halves would each fail the rule on their own.
            List<String> literals = arguments(text, kind, lookupOpen, lookupClose);
            if (literals.isEmpty() && lookup.group(1).equals("t")) {
                List<int[]> parts = argumentsOf(text, kind, lookupOpen, lookupClose);
                int[] key = parts.get(parts.size() - 1);
                String name = text.substring(key[0], key[1]).trim();
                if (name.matches("[a-z]\\w*")) {
                    for (int[] assignment : assignmentsReaching(text, kind, name, lookup.start())) {
                        // A value already translated is that call's literal, not a key.
                        boolean translated = java.util.regex.Pattern.compile("L10n\\s*\\.").matcher(text)
                                .region(assignment[0], assignment[1]).find();
                        literals.addAll(translated
                                ? l10nLiteralsBetween(text, kind, assignment[0], assignment[1])
                                : arguments(text, kind, assignment[0], assignment[1]));
                    }
                }
            }
            found.addAll(literals);
        }
        return found;
    }

    /**
     * The methods a file hands a toast to as the way to tell the reader, as in
     * {@code runLabChange(change, Utils::showToastLong)}, written as alternatives for a toast
     * call's pattern. Whatever such a call is given or builds inside its brackets ends up in that
     * toast: the Lab's change notices, each a lambda's answer, a refusal it throws or a message
     * handed in, all reached the reader that way unmeasured. A toast assigned to a variable,
     * {@code tell = Utils::showToastLong}, isn't handed to anything. Kept to the file, since
     * runLabChange is private.
     */
    private static String presenterCalls(String text, byte[] kind) {
        StringBuilder calls = new StringBuilder();
        for (String name : toastPresentersIn(text, kind)) calls.append("|\\b").append(name);
        return calls.toString();
    }

    private static Set<String> toastPresentersIn(String text, byte[] kind) {
        Set<String> names = new LinkedHashSet<>();
        java.util.regex.Matcher reference = java.util.regex.Pattern
                .compile("::\\s*\\w*[Tt]oast\\w*").matcher(text);
        while (reference.find()) {
            if (kind[reference.start()] != CODE) continue;
            // Back over whole brackets to the one the reference sits in. An = or ; first means
            // it is assigned or stands alone, and a brace or square bracket that it is no
            // call's argument.
            int depth = 0;
            for (int at = reference.start() - 1; at >= 0; at--) {
                if (kind[at] != CODE) continue;
                char c = text.charAt(at);
                if (c == ')' || c == ']' || c == '}') {
                    depth++;
                } else if (depth > 0 && (c == '(' || c == '[' || c == '{')) {
                    depth--;
                } else if (c == '(') {
                    int nameEnd = at;
                    while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
                    int nameStart = nameEnd;
                    while (nameStart > 0 && Character.isJavaIdentifierPart(text.charAt(nameStart - 1))) nameStart--;
                    String name = text.substring(nameStart, nameEnd);
                    if (name.matches("[a-z]\\w*") && !NOT_A_HELPER.contains(name)) names.add(name);
                    break;
                } else if (c == '[' || c == '{' || c == ';' || c == '=') {
                    break;
                }
            }
        }
        return names;
    }

    /**
     * What a toast presenter shows that no call of it holds. runLabChange answers a failure with
     * {@code error instanceof Refused ? error.getMessage() : L10n.t(...)}, so the retry line in
     * its own body is a toast, and so is the sentence of every refusal it names: each
     * {@code new Refused(...)} in its own file, and each {@code new RuleLimitRefused(...)} in
     * FeatureGateLabStore, which the instanceof names. A class of the same name elsewhere is
     * another class. Every other toast of an exception's message adds the refusals its catch
     * names, placed the same way (messageToastsIn).
     */
    private static java.util.List<String> refusalLiteralsIn(Map<java.nio.file.Path, String> texts) {
        return refusalLiteralsIn(texts, new ArrayList<>());
    }

    /** As above, adding to {@code unmeasured} each refusal construction whose sentence can't be found. */
    private static java.util.List<String> refusalLiteralsIn(Map<java.nio.file.Path, String> texts,
                                                            java.util.List<String> unmeasured) {
        ToastSources sources = new ToastSources(texts);
        java.util.List<String> found = new java.util.ArrayList<>();
        Map<String, Set<java.nio.file.Path>> refusals = new LinkedHashMap<>();
        java.util.regex.Pattern instance = java.util.regex.Pattern
                .compile("\\binstanceof\\s+((?:\\w+\\s*\\.\\s*)*)(\\w+)");
        java.util.regex.Pattern told = java.util.regex.Pattern.compile("\\.\\s*getMessage\\s*\\(");
        for (java.nio.file.Path path : texts.keySet()) {
            String text = texts.get(path);
            byte[] kind = sources.kinds.get(path);
            for (String presenter : toastPresentersIn(text, kind)) {
                for (int[] body : sources.bodiesOf(path, presenter)) {
                    java.util.regex.Matcher message = told.matcher(text).region(body[0], body[1]);
                    boolean tells = false;
                    while (!tells && message.find()) tells = kind[message.start()] == CODE;
                    if (!tells) continue;
                    found.addAll(l10nLiteralsBetween(text, kind, body[0], body[1]));
                    java.util.regex.Matcher type = instance.matcher(text).region(body[0], body[1]);
                    while (type.find()) {
                        if (kind[type.start()] != CODE) continue;
                        placeRefusal(sources, path, type.group(1), type.group(2), refusals);
                    }
                }
            }
        }
        for (MessageToast toast : messageToastsIn(sources)) {
            for (String[] type : toast.types) placeRefusal(sources, toast.path, type[0], type[1], refusals);
        }
        found.addAll(constructionLiterals(sources, refusals, unmeasured));
        return found;
    }

    /**
     * The files a refusal type, written at {@code path} as {@code qualifier} then {@code name}, is
     * built in: the named owner's, or with no owner its own file and any file of its name.
     */
    private static void placeRefusal(ToastSources sources, java.nio.file.Path path, String qualifier,
                                     String name, Map<String, Set<java.nio.file.Path>> refusals) {
        String[] parts = qualifier.replaceAll("[\\s.]+$", "").split("\\s*\\.\\s*");
        String owner = parts[parts.length - 1];
        Set<java.nio.file.Path> where = refusals.computeIfAbsent(name, key -> new LinkedHashSet<>());
        if (owner.isEmpty()) {
            where.add(path);
            where.addAll(sources.classes.getOrDefault(name, new ArrayList<>()));
        } else {
            where.addAll(sources.classes.getOrDefault(owner, new ArrayList<>()));
        }
    }

    /**
     * Every L10n literal a refusal can carry: the arguments of each {@code new Type(...)} in the
     * files it is placed in and the files builtElsewhere finds, and of each {@code super(...)} in
     * its own class, where a refusal that builds its own sentence holds it.
     *
     * <p>Adds to {@code unmeasured} each construction whose sentence that can't find: one that
     * reads another exception's message, as {@code super(cause.getMessage())} does, a
     * {@code super(...)} that neither builds a sentence nor hands on a parameter, a
     * {@code new Type(...)} with no sentence in it, as {@code new Refused(field)}, of a refusal
     * whose {@code super(...)} hands one on, and one that joins its sentence to something
     * untracedValue can't follow. Each of those passed as measured with nothing, or only part of
     * its sentence, in it.
     */
    private static java.util.List<String> constructionLiterals(ToastSources sources,
                                                               Map<String, Set<java.nio.file.Path>> refusals,
                                                               java.util.List<String> unmeasured) {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (Map.Entry<String, Set<java.nio.file.Path>> refusal : refusals.entrySet()) {
            String name = refusal.getKey();
            java.util.regex.Pattern made = java.util.regex.Pattern
                    .compile("\\bnew\\s+(?:\\w+\\s*\\.\\s*)*" + name + "\\s*\\(");
            java.util.regex.Pattern declared = java.util.regex.Pattern
                    .compile("\\bclass\\s+" + name + "\\b[^{;]*\\{");
            List<Construction> constructions = new ArrayList<>();
            Set<java.nio.file.Path> scanned = new LinkedHashSet<>(refusal.getValue());
            scanned.addAll(builtElsewhere(sources, name, refusal.getValue()));
            for (java.nio.file.Path path : scanned) {
                String text = sources.texts.get(path);
                byte[] kind = sources.kinds.get(path);
                List<int[]> calls = new ArrayList<>();
                java.util.regex.Matcher construction = made.matcher(text);
                while (construction.find()) {
                    if (kind[construction.start()] == CODE) calls.add(new int[]{construction.end() - 1, 0});
                }
                java.util.regex.Matcher type = declared.matcher(text);
                while (type.find()) {
                    if (kind[type.start()] != CODE) continue;
                    int end = closingBrace(text, kind, type.end() - 1);
                    if (end < 0) continue;
                    java.util.regex.Matcher handed = SUPER_CALL.matcher(text).region(type.end(), end);
                    while (handed.find()) {
                        if (kind[handed.start()] == CODE) calls.add(new int[]{handed.end() - 1, 1});
                    }
                }
                for (int[] call : calls) {
                    int close = closingBracket(text, kind, call[0]);
                    if (close < 0) continue;
                    List<String> literals = sources.shown(path, call[0] + 1, close, new java.util.HashSet<>());
                    found.addAll(literals);
                    constructions.add(new Construction(path, call[0], close, call[1] == 1, literals));
                }
            }
            // Whether the refusal's sentence is handed in decides what each new Type(...) owes.
            boolean handsOn = false;
            for (Construction construction : constructions) {
                if (construction.handed && construction.literals.isEmpty()
                        && !readsMessage(sources, construction) && construction.handsOnParameter(sources)) {
                    handsOn = true;
                }
            }
            for (Construction construction : constructions) {
                String place = placeOf(construction.path, sources.texts.get(construction.path), construction.open);
                if (readsMessage(sources, construction)) {
                    unmeasured.add(place + " builds " + name + " from another exception's message, so what it"
                            + " says can't be found");
                } else if (!construction.literals.isEmpty()) {
                    String untraced = untracedValue(sources, construction);
                    if (untraced != null) {
                        unmeasured.add(place + " builds " + name + " from " + untraced + ", which the scan"
                                + " can't read, so part of its sentence can't be measured");
                    }
                } else if (construction.handed) {
                    if (!construction.empty(sources) && !construction.handsOnParameter(sources)) {
                        unmeasured.add(place + " builds " + name + "'s message from something the scan can't read");
                    }
                } else if (handsOn) {
                    unmeasured.add(place + " builds " + name + " from something the scan can't read, so its"
                            + " sentence can't be measured");
                }
            }
        }
        return found;
    }

    /** A {@code new Type(...)} or a {@code super(...)} of a refusal, as its brackets, and the literals it carries. */
    private static final class Construction {
        final java.nio.file.Path path;
        final int open;
        final int close;
        final boolean handed;
        final List<String> literals;

        Construction(java.nio.file.Path path, int open, int close, boolean handed, List<String> literals) {
            this.path = path;
            this.open = open;
            this.close = close;
            this.handed = handed;
            this.literals = literals;
        }

        /** The message argument, the first, as written. */
        String message(ToastSources sources) {
            String text = sources.texts.get(path);
            int[] first = argumentsOf(text, sources.kinds.get(path), open, close).get(0);
            return text.substring(first[0], first[1]).trim();
        }

        /** Whether it's handed nothing, which leaves the message null rather than a sentence. */
        boolean empty(ToastSources sources) {
            return message(sources).isEmpty();
        }

        /** Whether a {@code super(...)} hands on a parameter of its constructor as the message. */
        boolean handsOnParameter(ToastSources sources) {
            String text = sources.texts.get(path);
            byte[] kind = sources.kinds.get(path);
            String message = message(sources);
            return message.matches("[a-z]\\w*")
                    && takesParameter(text, kind, methodBodyStart(text, kind, open, false), message);
        }
    }

    /**
     * Whether a construction's brackets read an exception's message, as written or carried in
     * through a local, a field or a method's answer (ToastSources.readsCarried).
     */
    private static boolean readsMessage(ToastSources sources, Construction construction) {
        byte[] kind = sources.kinds.get(construction.path);
        java.util.regex.Matcher read = MESSAGE_READ.matcher(sources.texts.get(construction.path))
                .region(construction.open, construction.close);
        while (read.find()) {
            if (kind[read.start()] == CODE) return true;
        }
        Map<java.nio.file.Path, java.util.TreeMap<Integer, String>> carried = new LinkedHashMap<>();
        sources.readsCarried(construction.path, construction.open + 1, construction.close,
                new java.util.HashSet<>(), carried, false);
        return !carried.isEmpty();
    }

    /**
     * The first part of a construction's message, its first argument, that shown can't follow
     * outside its L10n calls: a name no assignment the scan reads sets, a constant, a field of
     * something else, or a call that lands in no method here. Null when there's none. A name or
     * call that only picks between sentences, as {@code ok} does in
     * {@code ok ? L10n.t(...) : L10n.t(...)}, adds no text. {@code new Refused(L10n.t("Bad") + ": " + path)}
     * passed as measured on its one literal before this.
     */
    private static String untracedValue(ToastSources sources, Construction construction) {
        java.nio.file.Path path = construction.path;
        String text = sources.texts.get(path);
        byte[] kind = sources.kinds.get(path);
        byte[] code = sources.outside.get(path);
        int[] first = argumentsOf(text, kind, construction.open, construction.close).get(0);
        // Each open bracket, and whether it's a call's or an index's rather than a grouping's or a cast's.
        java.util.ArrayDeque<Boolean> brackets = new java.util.ArrayDeque<>();
        int calls = 0;
        int at = first[0];
        while (at < first[1]) {
            char c = text.charAt(at);
            if (code[at] != CODE) {
                at++;
            } else if (c == '(' || c == '[' || c == '{') {
                boolean call = c != '(' || callBracket(text, first[0], at);
                brackets.push(call);
                if (call) calls++;
                at++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (!brackets.isEmpty() && brackets.pop()) calls--;
                at++;
            } else if (!Character.isJavaIdentifierStart(c)
                    || at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))) {
                at++;
            } else {
                int end = at;
                while (end < first[1] && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                String name = text.substring(at, end);
                int next = end;
                while (next < first[1] && Character.isWhitespace(text.charAt(next))) next++;
                boolean called = next < first[1] && text.charAt(next) == '(';
                boolean constant = name.matches("[A-Z][A-Z0-9_]+") && !(next < first[1] && text.charAt(next) == '.');
                if (calls == 0 && (name.matches("[a-z]\\w*") && !NOT_VALUES.contains(name) || constant)
                        && !picks(text, code, end, first[1])) {
                    String receiver = sources.receiver(path, at);
                    if (called) {
                        boolean lands = false;
                        for (java.nio.file.Path target : sources.targets(path, at)) {
                            if (!sources.bodiesOf(target, name).isEmpty()) lands = true;
                        }
                        if (!lands) return name + "()";
                    } else if (constant || receiver != null && !receiver.equals("this")) {
                        return receiver == null || receiver.isEmpty() ? name : receiver + "." + name;
                    } else {
                        boolean ofThis = receiver != null;
                        List<int[]> set = ofThis ? new ArrayList<>() : assignmentsReaching(text, kind, name, at);
                        if (set.isEmpty()) set = fieldAssignments(text, kind, name, at, ofThis);
                        if (set.isEmpty()) return name;
                    }
                }
                at = end;
            }
        }
        return null;
    }

    /**
     * Whether what ends at {@code end} sits in a ?:'s condition, which picks a sentence rather
     * than adding to one: a ? comes before anything that would end the condition.
     */
    private static boolean picks(String text, byte[] code, int end, int to) {
        int depth = 0;
        for (int at = end; at < to; at++) {
            if (code[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth <= 0) {
                if (c == '?') return true;
                if (c == ':' || c == ',' || c == ';' || c == '+') return false;
            }
        }
        return false;
    }

    /**
     * The files besides {@code placed} that build the refusal {@code name}: each that writes
     * {@code new Owner.Name(...)}, or imports Owner.Name or Owner.* and so can write
     * {@code new Name(...)}, where Owner is a placed file declaring it. A refusal in a file of its
     * own name can be built anywhere as {@code new Name(...)}. Those constructions went unread
     * before this, so their sentences were never measured.
     */
    private static Set<java.nio.file.Path> builtElsewhere(ToastSources sources, String name,
                                                          Set<java.nio.file.Path> placed) {
        Set<java.nio.file.Path> found = new LinkedHashSet<>();
        java.util.regex.Pattern declared = java.util.regex.Pattern.compile("\\bclass\\s+" + name + "\\b");
        for (java.nio.file.Path file : placed) {
            String text = sources.texts.get(file);
            byte[] kind = sources.kinds.get(file);
            boolean declares = false;
            java.util.regex.Matcher type = declared.matcher(text);
            while (!declares && type.find()) declares = kind[type.start()] == CODE;
            if (!declares) continue;
            String owner = ToastSources.className(file);
            java.util.regex.Pattern builds = owner.equals(name)
                    ? java.util.regex.Pattern.compile("\\bnew\\s+(?:\\w+\\s*\\.\\s*)*" + name + "\\s*\\(")
                    : java.util.regex.Pattern.compile("\\bnew\\s+(?:\\w+\\s*\\.\\s*)*" + owner + "\\s*\\.\\s*"
                            + name + "\\s*\\(|\\bimport\\s+[\\w.]*\\b" + owner + "\\s*\\.\\s*(?:" + name
                            + "|\\*)\\s*;");
            for (java.nio.file.Path other : sources.texts.keySet()) {
                if (placed.contains(other)) continue;
                byte[] otherKind = sources.kinds.get(other);
                java.util.regex.Matcher built = builds.matcher(sources.texts.get(other));
                while (built.find()) {
                    if (otherKind[built.start()] == CODE) {
                        found.add(other);
                        break;
                    }
                }
            }
        }
        return found;
    }

    /** Where {@code at} is in {@code path}, as its class and line. */
    private static String placeOf(java.nio.file.Path path, String text, int at) {
        int line = 1;
        for (int index = 0; index < at; index++) if (text.charAt(index) == '\n') line++;
        return ToastSources.className(path) + ":" + line;
    }

    /** A constructor handing its superclass what it was built with. */
    private static final java.util.regex.Pattern SUPER_CALL = java.util.regex.Pattern.compile("\\bsuper\\s*\\(");

    /**
     * A read of an exception's message. ToastSources.receiver says what it reads: one name, as in
     * {@code error.getMessage()}, or none, as in {@code error.getCause().getMessage()}, a cast or
     * {@code this.failure.getMessage()}, whose exception no catch can name.
     */
    private static final java.util.regex.Pattern MESSAGE_READ = java.util.regex.Pattern
            .compile("\\bget(?:Localized)?Message\\s*\\(");

    /**
     * A toast that shows an exception's message: where it reads it, the name it reads, and the
     * types the catch around the read says the exception is, each as its written qualifier
     * ("" or "FeatureGateLabStore.") and its name.
     */
    private static final class MessageToast {
        final java.nio.file.Path path;
        final int at;
        final String exception;
        final List<String[]> types;

        MessageToast(java.nio.file.Path path, int at, String exception, List<String[]> types) {
            this.path = path;
            this.at = at;
            this.exception = exception;
            this.types = types;
        }

        String where(ToastSources sources) {
            return placeOf(path, sources.texts.get(path), at);
        }

        /** Why the wrap scan can't measure what this toast shows, or null when it can. */
        String unmeasured(ToastSources sources) {
            if (exception.isEmpty()) {
                return where(sources) + " shows an exception's message read through a call, a cast or a"
                        + " field, or the exception itself, so the scan can't say what sentence it holds";
            }
            if (types.isEmpty()) {
                return where(sources) + " shows " + exception + ".getMessage(), and no catch around it"
                        + " says what " + exception + " is";
            }
            for (String[] type : types) {
                if (!declares(sources, path, type[0], type[1])) {
                    return where(sources) + " shows the message of " + type[0].replaceAll("\\s", "")
                            + type[1] + ", which isn't a class here, so what it says can't be found";
                }
            }
            return null;
        }
    }

    /**
     * Every toast that shows an exception's message: a read in what a toast or a wrapper is
     * handed, followed through locals and method answers as toastHelperLiteralsIn follows them
     * and into every call's brackets (ToastSources.readsCarried), a caught exception it shows
     * whole, and a read in a toast presenter's body, whose answer it can be. A read guarded by an
     * instanceof of its exception, as runLabChange's is, is taken to show the message of what the
     * guard tests; otherwise of what the catch catches.
     */
    private static List<MessageToast> messageToastsIn(ToastSources sources) {
        toastHelperLiteralsIn(sources);
        for (java.nio.file.Path path : sources.texts.keySet()) {
            for (String presenter : toastPresentersIn(sources.texts.get(path), sources.kinds.get(path))) {
                for (int[] body : sources.bodiesOf(path, presenter)) sources.noteMessageReads(path, body[0], body[1]);
            }
        }
        List<MessageToast> toasts = new ArrayList<>();
        for (Map.Entry<java.nio.file.Path, java.util.TreeMap<Integer, String>> reads : sources.messageReads.entrySet()) {
            String text = sources.texts.get(reads.getKey());
            byte[] kind = sources.kinds.get(reads.getKey());
            for (Map.Entry<Integer, String> read : reads.getValue().entrySet()) {
                toasts.add(new MessageToast(reads.getKey(), read.getKey(), read.getValue(),
                        caughtTypes(text, kind, read.getValue(), read.getKey())));
            }
        }
        return toasts;
    }

    /**
     * What the catch around a read of {@code name}'s message at {@code at} says the exception
     * is: the types a guard holding the read narrows it to (narrowedAt), or else each type it
     * catches. Empty when {@code name} is no catch's exception there, or is no name at all. An
     * instanceof elsewhere in the catch says nothing about this read: an
     * {@code if (error instanceof Refused) log(error);} before it, or the other arm of a ?:.
     */
    private static List<String[]> caughtTypes(String text, byte[] kind, String name, int at) {
        List<String[]> types = new ArrayList<>();
        if (name.isEmpty()) return types;
        int[] around = catchAround(text, kind, name, at);
        if (around == null) return types;
        String named = narrowedAt(text, kind, name, around[0], at);
        if (named == null) named = text.substring(around[2], around[3]);
        for (String type : named.split("\\|")) {
            java.util.regex.Matcher parts = java.util.regex.Pattern
                    .compile("((?:\\w+\\s*\\.\\s*)*)(\\w+)").matcher(type.trim());
            if (parts.matches()) types.add(new String[]{parts.group(1), parts.group(2)});
        }
        return types;
    }

    /**
     * The catch whose exception is {@code name} and whose block is still open at {@code at}: its
     * brace, its closing brace, and where the types it catches are written. Null when there's none.
     */
    private static int[] catchAround(String text, byte[] kind, String name, int at) {
        java.util.regex.Matcher caught = java.util.regex.Pattern
                .compile("\\bcatch\\s*\\(\\s*(?:final\\s+)?([\\w.|\\s]+?)\\s+"
                        + java.util.regex.Pattern.quote(name) + "\\s*\\)\\s*\\{")
                .matcher(text);
        caught.region(methodBodyStart(text, kind, at), at);
        int[] around = null;
        // The last catch still open at the read is the one around it.
        while (caught.find()) {
            if (kind[caught.start()] != CODE) continue;
            int end = closingBrace(text, kind, caught.end() - 1);
            if (end > at) around = new int[]{caught.end() - 1, end, caught.start(1), caught.end(1)};
        }
        return around;
    }

    /**
     * The types, joined by |, that a guard holding the read at {@code at} narrows {@code name}
     * to, or null when none does. A guard is the then-part of an if or the true arm of a ?:,
     * opened after the catch's brace at {@code open}; the one closest to the read is tried first.
     */
    private static String narrowedAt(String text, byte[] kind, String name, int open, int at) {
        java.util.TreeMap<Integer, int[]> guards = new java.util.TreeMap<>(java.util.Collections.reverseOrder());
        java.util.regex.Matcher branch = java.util.regex.Pattern.compile("\\bif\\s*\\(").matcher(text);
        branch.region(open, at);
        while (branch.find()) {
            if (kind[branch.start()] != CODE) continue;
            int condition = branch.end() - 1;
            int close = closingBracket(text, kind, condition);
            if (close < 0 || close >= at) continue;
            int then = close + 1;
            while (then < at && (kind[then] != CODE || Character.isWhitespace(text.charAt(then)))) then++;
            int end = text.charAt(then) == '{' ? closingBrace(text, kind, then) : statementEnd(text, kind, then);
            if (end >= at) guards.put(branch.start(), new int[]{condition + 1, close});
        }
        for (int mark = open + 1; mark < at; mark++) {
            if (kind[mark] != CODE || text.charAt(mark) != '?' || !trueArmHolds(text, kind, mark, at)) continue;
            guards.put(mark, new int[]{conditionStart(text, kind, mark), mark});
        }
        for (int[] condition : guards.values()) {
            List<String> types = narrowing(codeBefore(text, kind, condition[1], condition[1] - condition[0]),
                    name, text, kind, open, at, true);
            if (types != null) return String.join("|", types);
        }
        return null;
    }

    /** Whether the ? at {@code mark} is a conditional's and {@code at} sits in its true arm. */
    private static boolean trueArmHolds(String text, byte[] kind, int mark, int at) {
        int before = mark - 1;
        while (before >= 0 && (kind[before] != CODE || Character.isWhitespace(text.charAt(before)))) before--;
        // A wildcard type argument, <?> or Map<K, ?>, is no conditional.
        if (before < 0 || text.charAt(before) == '<' || text.charAt(before) == ',') return false;
        int depth = 0;
        for (int i = mark + 1; i < at; i++) {
            if (kind[i] != CODE) continue;
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth < 0) return false;
            } else if (depth == 0 && (c == ':' || c == ';' || c == ',')) {
                return false;
            }
        }
        return true;
    }

    /** Where the condition of the ? at {@code mark} starts: after the bracket, operator or return before it. */
    private static int conditionStart(String text, byte[] kind, int mark) {
        int depth = 0;
        for (int i = mark - 1; i >= 0; i--) {
            if (kind[i] != CODE) continue;
            char c = text.charAt(i);
            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth-- == 0) return i + 1;
            } else if (depth == 0) {
                if (c == ',' || c == ';' || c == '?' || c == ':') return i + 1;
                boolean assigns = c == '=' && i > 0 && text.charAt(i + 1) != '='
                        && "=!<>".indexOf(text.charAt(i - 1)) < 0;
                boolean arrow = c == '>' && i > 0 && text.charAt(i - 1) == '-';
                boolean returns = c == 'n' && i >= 5 && text.startsWith("return", i - 5)
                        && (i == 5 || !Character.isJavaIdentifierPart(text.charAt(i - 6)))
                        && !Character.isJavaIdentifierPart(text.charAt(i + 1));
                if (assigns || arrow || returns) return i + 1;
            }
        }
        return 0;
    }

    /**
     * The types a condition narrows {@code name} to, or null: {@code name instanceof T}, a || whose
     * every side narrows it, or a && with one side that does. With {@code resolve}, a bare boolean
     * local stands for its initializer, when it's set between the catch's brace at {@code open}
     * and the read at {@code at}, as {@code boolean refused = error instanceof Refused || ...} is.
     */
    private static List<String> narrowing(String condition, String name, String text, byte[] kind,
                                          int open, int at, boolean resolve) {
        String code = ungrouped(condition);
        List<String> either = topLevel(code, "||");
        if (either.size() > 1) {
            List<String> all = new ArrayList<>();
            for (String side : either) {
                List<String> types = narrowing(side, name, text, kind, open, at, resolve);
                if (types == null) return null;
                all.addAll(types);
            }
            return all;
        }
        List<String> both = topLevel(code, "&&");
        if (both.size() > 1) {
            for (String side : both) {
                List<String> types = narrowing(side, name, text, kind, open, at, resolve);
                if (types != null) return types;
            }
            return null;
        }
        java.util.regex.Matcher tested = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)
                + "\\s+instanceof\\s+((?:\\w+\\s*\\.\\s*)*\\w+)(?:\\s+\\w+)?").matcher(code);
        if (tested.matches()) return new ArrayList<>(java.util.Collections.singletonList(tested.group(1)));
        if (!resolve || !code.matches("[a-z]\\w*")) return null;
        java.util.regex.Matcher local = java.util.regex.Pattern.compile("\\bboolean\\s+" + code + "\\s*=(?!=)")
                .matcher(text);
        local.region(open, at);
        int[] set = null;
        while (local.find()) {
            if (kind[local.start()] != CODE) continue;
            int end = statementEnd(text, kind, local.end());
            if (end > 0) set = new int[]{local.end(), end};
        }
        return set == null ? null
                : narrowing(codeBefore(text, kind, set[1], set[1] - set[0]), name, text, kind, open, at, false);
    }

    /** {@code code} trimmed, without the brackets around all of it. */
    private static String ungrouped(String code) {
        String part = code.trim();
        while (part.startsWith("(") && part.endsWith(")")) {
            int depth = 0;
            int close = -1;
            for (int i = 0; i < part.length() && close < 0; i++) {
                char c = part.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) close = i;
            }
            if (close != part.length() - 1) break;
            part = part.substring(1, close).trim();
        }
        return part;
    }

    /** {@code code} split at each {@code operator} outside any bracket. */
    private static List<String> topLevel(String code, String operator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && code.startsWith(operator, i)) {
                parts.add(code.substring(start, i));
                start = i + operator.length();
                i = start - 1;
            }
        }
        parts.add(code.substring(start));
        return parts;
    }

    /** Whether a type written at {@code path} as {@code qualifier} then {@code name} is declared in the sources. */
    private static boolean declares(ToastSources sources, java.nio.file.Path path, String qualifier, String name) {
        Map<String, Set<java.nio.file.Path>> placed = new LinkedHashMap<>();
        placeRefusal(sources, path, qualifier, name, placed);
        java.util.regex.Pattern declared = java.util.regex.Pattern.compile("\\bclass\\s+" + name + "\\b");
        for (java.nio.file.Path file : placed.get(name)) {
            String text = sources.texts.get(file);
            byte[] kind = sources.kinds.get(file);
            java.util.regex.Matcher type = declared.matcher(text);
            while (type.find()) {
                if (kind[type.start()] == CODE) return true;
            }
        }
        return false;
    }

    /**
     * Every L10n literal that reaches a toast through a local variable, as in
     * {@code String message = L10n.t(...); Utils.showToastLong(message);}. A variable declared in
     * the toast's own method, with a value or without one, is followed through each assignment
     * between its declaration and the call. A toast in an anonymous class's method follows the
     * enclosing method's variables too, as a lambda's does, unless a parameter of its own method
     * hides them. A declaration in a block that closed before the call, such as a sibling
     * anonymous class's method, is another variable and is left out. A message a helper method
     * builds, or one handed in as a parameter, is toastHelperLiteralsIn's to find.
     */
    private static java.util.List<String> toastVariableLiteralsIn(String text) {
        byte[] kind = classify(text);
        java.util.List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher call = java.util.regex.Pattern
                .compile(TOAST_CALL.replace(")\\s*\\(", presenterCalls(text, kind) + ")\\s*\\(")).matcher(text);
        while (call.find()) {
            int open = call.end() - 1;
            if (kind[call.start()] != CODE) continue;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            for (String argument : text.substring(open + 1, close).split(",")) {
                String name = argument.trim();
                if (!name.matches("[a-z]\\w*")) continue;
                for (int[] assignment : assignmentsReaching(text, kind, name, call.start())) {
                    found.addAll(l10nLiteralsBetween(text, kind, assignment[0], assignment[1]));
                }
            }
        }
        return found;
    }

    /** A toast call: a method with toast in its name, or Toast.makeText. */
    private static final String TOAST_CALL = "(?:\\b\\w*[Tt]oast\\w*|Toast\\s*\\.\\s*makeText)\\s*\\(";

    /**
     * Where each assignment to the local String {@code name} that the read at {@code use} can
     * see runs, from just after its = to its semicolon, by the rules toastVariableLiteralsIn
     * gives. Empty when {@code name} is no such variable, a parameter for one.
     */
    private static java.util.List<int[]> assignmentsReaching(String text, byte[] kind, String name, int use) {
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        int body = methodBodyStart(text, kind, use);
        int own = methodBodyStart(text, kind, use, false);
        java.util.regex.Matcher declared = java.util.regex.Pattern
                .compile("\\b(?:String|CharSequence)\\s+" + name + "\\s*(?:=(?!=)|;)").matcher(text);
        declared.region(body, use);
        // The last declaration whose block is still open at the read is the one it reads. Any
        // other one after it sits in a block that closed first, a sibling anonymous class's
        // method say, and holds a variable of its own.
        int from = -1;
        java.util.List<Integer> shadows = new java.util.ArrayList<>();
        while (declared.find()) {
            if (kind[declared.start()] != CODE) continue;
            if (inScope(text, kind, declared.start(), use)) {
                from = declared.start();
                shadows.clear();
            } else if (from >= 0) {
                shadows.add(declared.start());
            }
        }
        if (from < 0) return spans;
        // A parameter of the reading method hides a variable of the method around it.
        if (from < own && takesParameter(text, kind, own, name)) return spans;
        java.util.regex.Matcher assigned = java.util.regex.Pattern
                .compile("\\b" + name + "\\s*\\+?=(?!=)").matcher(text);
        assigned.region(from, use);
        while (assigned.find()) {
            if (kind[assigned.start()] != CODE) continue;
            if (shadowed(text, kind, shadows, assigned.start())) continue;
            int end = statementEnd(text, kind, assigned.end());
            if (end > 0) spans.add(new int[]{assigned.end(), end});
        }
        return spans;
    }

    /**
     * Where each value the String field {@code name} can hold is set, when the read at {@code use}
     * is of that field: its initializer and each assignment to it in its file that no local or
     * parameter of that name hides. Empty when the file declares no such field. {@code qualified}
     * says the read wrote {@code this.}, which only a field can be.
     */
    private static java.util.List<int[]> fieldAssignments(String text, byte[] kind, String name, int use,
                                                          boolean qualified) {
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        if (!qualified && hidesField(text, kind, name, use)) return spans;
        String quoted = java.util.regex.Pattern.quote(name);
        java.util.regex.Matcher field = java.util.regex.Pattern
                .compile("\\b(?:String|CharSequence)\\s+" + quoted + "\\s*(?:=(?!=)|;)").matcher(text);
        boolean declared = false;
        while (!declared && field.find()) {
            declared = kind[field.start()] == CODE && methodBodyStart(text, kind, field.start()) == 0;
        }
        if (!declared) return spans;
        java.util.regex.Matcher assigned = java.util.regex.Pattern
                .compile("(?<![\\w$])" + quoted + "\\s*\\+?=(?!=)").matcher(text);
        while (assigned.find()) {
            if (kind[assigned.start()] != CODE) continue;
            int dot = assigned.start() - 1;
            while (dot >= 0 && Character.isWhitespace(text.charAt(dot))) dot--;
            boolean ours = dot >= 0 && text.charAt(dot) == '.'
                    ? thisBefore(text, dot)
                    : !hidesField(text, kind, name, assigned.start());
            if (!ours) continue;
            int end = statementEnd(text, kind, assigned.end());
            if (end > 0) spans.add(new int[]{assigned.end(), end});
        }
        return spans;
    }

    /** Whether {@code name} at {@code at} is a parameter or a local of the method around it rather than a field. */
    private static boolean hidesField(String text, byte[] kind, String name, int at) {
        int body = methodBodyStart(text, kind, at);
        if (body == 0) return false;
        if (takesParameter(text, kind, body, name)
                || takesParameter(text, kind, methodBodyStart(text, kind, at, false), name)) {
            return true;
        }
        // A type and the name, as a declaration, a catch, a for-each or a lambda's typed parameter
        // writes them. A statement can't otherwise put a word before a name, bar these keywords.
        java.util.regex.Matcher local = java.util.regex.Pattern.compile(
                "(?<![\\w$.])(?!(?:return|throw|else|case|new|yield|assert|do)\\b)[A-Za-z_$][\\w$.]*"
                        + "(?:\\s*<[^;(){}]*>)?(?:\\s*\\[\\s*\\])*\\s+" + java.util.regex.Pattern.quote(name)
                        + "(?=\\s*(?:=(?!=)|;|,|:|\\)))").matcher(text);
        local.region(body, Math.min(text.length(), at + name.length())).useTransparentBounds(true);
        while (local.find()) {
            if (kind[local.start()] == CODE && inScope(text, kind, local.start(), at)) return true;
        }
        return false;
    }

    /** Whether the dot at {@code dot} follows {@code this}. */
    private static boolean thisBefore(String text, int dot) {
        int end = dot;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        int begin = end;
        while (begin > 0 && Character.isJavaIdentifierPart(text.charAt(begin - 1))) begin--;
        return text.substring(begin, end).equals("this") && (begin == 0 || text.charAt(begin - 1) != '.');
    }

    /** Whether the bracket at {@code open} is a call's: a name, or a type argument's close, just before it. */
    private static boolean callBracket(String text, int from, int open) {
        int before = open - 1;
        while (before >= from && Character.isWhitespace(text.charAt(before))) before--;
        if (before < from) return false;
        char c = text.charAt(before);
        return Character.isJavaIdentifierPart(c) || c == '>' && (before == 0 || text.charAt(before - 1) != '-');
    }

    /** Words an expression holds that name no value. */
    private static final Set<String> NOT_VALUES = new LinkedHashSet<>(java.util.Arrays.asList(
            "this", "super", "null", "true", "false", "new", "instanceof"));

    /**
     * Methods whose toast stands in for a banner that couldn't be drawn. The banners aren't held
     * to two lines, so a message that reaches a toast only through one of these isn't either.
     */
    private static final Set<String> BANNER_FALLBACKS = new LinkedHashSet<>(java.util.Arrays.asList(
            "BlockAuthorOverlay#toastInstead", "SettingsActionBanner#show", "SettingsActionBanner#addAction"));

    /** A method being called or declared: its name and the bracket after it. */
    private static final java.util.regex.Pattern CALLED = java.util.regex.Pattern.compile("\\b([a-z]\\w*)\\s*\\(");

    /**
     * Every L10n literal that reaches a toast through another method: one whose answer the toast
     * shows, as in {@code Utils.showToastLong(MediaJobScheduler.busyMessage(key))} or a local set
     * from one, and one that hands a String it was given on to a toast, as in
     * {@code SaveNotice.saved(L10n.t(...), saved)}. TikTok's 23 toasts written as a call and the
     * wrappers SaveNotice, notice and acknowledge all walked past the scan before this.
     *
     * <p>A call is followed when it names its class or none, so it lands in that class's file or
     * its own; a call on a value can't be placed and is left out. A method's answer is read from
     * each return in its own body, through a local it returns and every method it calls in turn.
     * A call inside another call's brackets, an L10n call's included, builds part of a message
     * that the %1$s it fills is already counted for, so it is left out too. Each read of an
     * exception's message it can carry, from anywhere, is noted for messageToastsIn.
     */
    private static java.util.List<String> toastHelperLiteralsIn(Map<java.nio.file.Path, String> texts) {
        return toastHelperLiteralsIn(new ToastSources(texts));
    }

    private static java.util.List<String> toastHelperLiteralsIn(ToastSources sources) {
        Set<String> wrappers = toastWrappers(sources);
        java.util.List<String> found = new java.util.ArrayList<>();
        for (java.nio.file.Path path : sources.texts.keySet()) {
            String text = sources.texts.get(path);
            byte[] kind = sources.kinds.get(path);
            java.util.regex.Matcher call = CALLED.matcher(text);
            while (call.find()) {
                if (kind[call.start()] != CODE) continue;
                int open = call.end() - 1;
                int close = closingBracket(text, kind, open);
                if (close < 0 || openingBraceAfter(text, kind, close) >= 0) continue;
                List<int[]> arguments = argumentsOf(text, kind, open, close);
                for (int index : sources.shownArguments(path, call.start(), call.group(1),
                        arguments.size(), wrappers)) {
                    found.addAll(sources.shown(path, arguments.get(index)[0], arguments.get(index)[1],
                            new java.util.HashSet<>()));
                    sources.noteReadsCarried(path, arguments.get(index)[0], arguments.get(index)[1],
                            new java.util.HashSet<>());
                }
            }
        }
        return found;
    }

    /**
     * Every method that hands a String parameter to a toast, as Class#method#position. Found the
     * way helpersThatPutTextOnTheScreen finds view helpers: a method whose parameter reaches a
     * toast, or reaches a method already found, is one, and that repeats until a pass finds
     * nothing new. Keyed by class rather than by name alone, since show and saved are common.
     */
    private static Set<String> toastWrappers(ToastSources sources) {
        Map<java.nio.file.Path, List<Method>> methods = new LinkedHashMap<>();
        for (java.nio.file.Path path : sources.texts.keySet()) {
            methods.put(path, methodsIn(sources.texts.get(path), sources.kinds.get(path)));
        }
        Set<String> wrappers = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (java.nio.file.Path path : sources.texts.keySet()) {
                String text = sources.texts.get(path);
                byte[] kind = sources.kinds.get(path);
                byte[] outside = sources.outside.get(path);
                String owner = ToastSources.className(path);
                for (Method method : methods.get(path)) {
                    if (BANNER_FALLBACKS.contains(owner + "#" + method.name)) continue;
                    java.util.regex.Matcher call = CALLED.matcher(text);
                    call.region(method.bodyStart, method.bodyEnd);
                    while (call.find()) {
                        if (kind[call.start()] != CODE) continue;
                        int open = call.end() - 1;
                        int close = closingBracket(text, kind, open);
                        if (close < 0) continue;
                        List<int[]> arguments = argumentsOf(text, kind, open, close);
                        for (int index : sources.shownArguments(path, call.start(), call.group(1),
                                arguments.size(), wrappers)) {
                            for (int position : identifiersIn(text, outside,
                                    arguments.get(index)[0], arguments.get(index)[1])) {
                                int declared = method.parameterAt(text, position);
                                if (declared >= 0 && wrappers.add(owner + "#" + method.name + "#" + declared)) {
                                    grew = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return wrappers;
    }

    /** The sources a toast scan reads, each classified once, and the files of each class name. */
    private static final class ToastSources {
        final Map<java.nio.file.Path, String> texts;
        final Map<java.nio.file.Path, byte[]> kinds = new LinkedHashMap<>();
        final Map<java.nio.file.Path, byte[]> outside = new LinkedHashMap<>();
        final Map<String, List<java.nio.file.Path>> classes = new LinkedHashMap<>();
        /** Each read of an exception's message that shown walked past, by place, with the name it reads. */
        final Map<java.nio.file.Path, java.util.TreeMap<Integer, String>> messageReads = new LinkedHashMap<>();

        ToastSources(Map<java.nio.file.Path, String> texts) {
            this.texts = texts;
            for (Map.Entry<java.nio.file.Path, String> source : texts.entrySet()) {
                byte[] kind = classify(source.getValue());
                kinds.put(source.getKey(), kind);
                outside.put(source.getKey(), withoutL10nCalls(source.getValue(), kind));
                classes.computeIfAbsent(className(source.getKey()), name -> new ArrayList<>())
                        .add(source.getKey());
            }
        }

        static String className(java.nio.file.Path path) {
            String file = path.getFileName().toString();
            return file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
        }

        /** The word before a call's dot: null when it has no dot, "" when no single word is there. */
        String receiver(java.nio.file.Path path, int start) {
            String text = texts.get(path);
            int dot = start - 1;
            while (dot >= 0 && Character.isWhitespace(text.charAt(dot))) dot--;
            if (dot < 0 || text.charAt(dot) != '.') return null;
            int end = dot;
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
            int begin = end;
            while (begin > 0 && Character.isJavaIdentifierPart(text.charAt(begin - 1))) begin--;
            int before = begin - 1;
            while (before >= 0 && Character.isWhitespace(text.charAt(before))) before--;
            return before >= 0 && text.charAt(before) == '.' ? "" : text.substring(begin, end);
        }

        /** The files a call at {@code start} can land in: its own, the named class's, or none. */
        List<java.nio.file.Path> targets(java.nio.file.Path path, int start) {
            String receiver = receiver(path, start);
            if (receiver == null || receiver.equals("this")) return java.util.Collections.singletonList(path);
            if (receiver.isEmpty() || !Character.isUpperCase(receiver.charAt(0))) {
                return java.util.Collections.emptyList();
            }
            List<java.nio.file.Path> named = classes.get(receiver);
            return named == null ? java.util.Collections.<java.nio.file.Path>emptyList() : named;
        }

        /** Which arguments of a call a toast shows: all of a toast's, the wrapped ones of a wrapper's. */
        List<Integer> shownArguments(java.nio.file.Path path, int start, String name, int count,
                                     Set<String> wrappers) {
            List<java.nio.file.Path> targets = targets(path, start);
            List<Integer> shown = new ArrayList<>();
            for (java.nio.file.Path target : targets) {
                if (BANNER_FALLBACKS.contains(className(target) + "#" + name)) return shown;
            }
            boolean toast = name.matches("\\w*[Tt]oast\\w*")
                    || name.equals("makeText") && "Toast".equals(receiver(path, start));
            for (int index = 0; index < count; index++) {
                if (toast) {
                    shown.add(index);
                    continue;
                }
                for (java.nio.file.Path target : targets) {
                    if (wrappers.contains(className(target) + "#" + name + "#" + index)) {
                        shown.add(index);
                        break;
                    }
                }
            }
            return shown;
        }

        /**
         * Every L10n literal in an expression a toast shows, and in what it reads: each local or
         * String field it names outside any call's brackets, and the answer of each method it
         * calls outside any brackets.
         */
        List<String> shown(java.nio.file.Path path, int from, int to, Set<String> seen) {
            noteMessageReads(path, from, to);
            String text = texts.get(path);
            byte[] kind = kinds.get(path);
            byte[] code = outside.get(path);
            List<String> found = new ArrayList<>(l10nLiteralsBetween(text, kind, from, to));
            for (int[] value : valuesRead(path, from, to)) {
                if (seen.add(path + "@" + value[0])) found.addAll(shown(path, value[0], value[1], seen));
            }
            java.util.regex.Matcher call = CALLED.matcher(text);
            int at = from;
            while (at < to && call.find(at) && call.start() < to) {
                at = call.end();
                if (code[call.start()] != CODE) continue;
                int close = closingBracket(text, kind, call.end() - 1);
                if (close < 0 || close >= to) continue;
                at = close + 1;
                for (java.nio.file.Path target : targets(path, call.start())) {
                    for (int[] body : bodiesOf(target, call.group(1))) {
                        if (!seen.add(target + "#" + body[0])) continue;
                        for (int[] answer : returnsIn(target, body)) {
                            found.addAll(shown(target, answer[0], answer[1], seen));
                        }
                    }
                }
            }
            return found;
        }

        /**
         * Where each value an expression reads outside any call's brackets is set: a local String
         * through the assignments that reach it, or else a String field of its file through all of
         * its own. A message kept in a local and joined to other text, as in
         * {@code L10n.t(...) + ": " + why}, walked past the scan when only an expression of one
         * name was followed. A name in a call's brackets builds part of what that call answers,
         * and the call is what's followed.
         */
        List<int[]> valuesRead(java.nio.file.Path path, int from, int to) {
            String text = texts.get(path);
            byte[] kind = kinds.get(path);
            byte[] code = outside.get(path);
            List<int[]> values = new ArrayList<>();
            // Each open bracket, and whether it's a call's or an index's rather than a grouping's or a cast's.
            java.util.ArrayDeque<Boolean> brackets = new java.util.ArrayDeque<>();
            int calls = 0;
            int at = from;
            while (at < to) {
                char c = text.charAt(at);
                if (code[at] != CODE) {
                    at++;
                } else if (c == '(' || c == '[' || c == '{') {
                    boolean call = c != '(' || callBracket(text, from, at);
                    brackets.push(call);
                    if (call) calls++;
                    at++;
                } else if (c == ')' || c == ']' || c == '}') {
                    if (!brackets.isEmpty() && brackets.pop()) calls--;
                    at++;
                } else if (!Character.isJavaIdentifierStart(c)
                        || at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))) {
                    at++;
                } else {
                    int end = at;
                    while (end < to && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                    String name = text.substring(at, end);
                    int next = end;
                    while (next < to && Character.isWhitespace(text.charAt(next))) next++;
                    int dot = at - 1;
                    while (dot >= from && Character.isWhitespace(text.charAt(dot))) dot--;
                    boolean member = dot >= from && text.charAt(dot) == '.';
                    boolean ofThis = member && thisBefore(text, dot);
                    if (calls == 0 && (!member || ofThis) && !(next < to && text.charAt(next) == '(')
                            && name.matches("[a-z]\\w*") && !NOT_VALUES.contains(name)) {
                        List<int[]> set = ofThis ? new ArrayList<>() : assignmentsReaching(text, kind, name, at);
                        values.addAll(set.isEmpty() ? fieldAssignments(text, kind, name, at, ofThis) : set);
                    }
                    at = end;
                }
            }
            return values;
        }

        /**
         * Records each read of an exception's message between {@code from} and {@code to}, with
         * the name it reads, or "" when it reads one through a call, a cast or a chain of fields.
         * A read in a ?:'s condition, as {@code error.getMessage() != null ? ... : ...} has, only
         * picks what's shown, so it's left out.
         */
        void noteMessageReads(java.nio.file.Path path, int from, int to) {
            noteMessageReads(path, from, to, messageReads);
        }

        void noteMessageReads(java.nio.file.Path path, int from, int to,
                              Map<java.nio.file.Path, java.util.TreeMap<Integer, String>> into) {
            String text = texts.get(path);
            byte[] kind = kinds.get(path);
            java.util.regex.Matcher read = MESSAGE_READ.matcher(text).region(from, to);
            while (read.find()) {
                if (kind[read.start()] != CODE) continue;
                String name = receiver(path, read.start());
                // A getMessage() with nothing before it is an exception reading its own.
                if (name == null) continue;
                int close = closingBracket(text, kind, read.end() - 1);
                if (close >= 0 && picks(text, kind, close + 1, text.length())) continue;
                into.computeIfAbsent(path, key -> new java.util.TreeMap<>()).put(read.start(), name);
            }
        }

        /** readsCarried into messageReads, a caught exception shown whole counted too. */
        void noteReadsCarried(java.nio.file.Path path, int from, int to, Set<String> seen) {
            readsCarried(path, from, to, seen, messageReads, true);
        }

        /**
         * Records into {@code into} each read of an exception's message an expression can carry
         * from anywhere in it, a call's brackets included, where shown leaves the text to the %1$s
         * those brackets fill: through each local or String field it names, a {@code Type.field}
         * set in any file, a local StringBuilder and what's appended to it, and the answer of each
         * method it calls but L10n's. With {@code itself}, a caught exception it shows whole, as
         * {@code String.valueOf(error)} or {@code "..." + error} do, is recorded as a read through
         * no name, since its text starts with its class's. {@code L10n.f("Failed: %1$s", why)},
         * {@code String.valueOf(why)} and {@code Store.lastError} each carried a message past the
         * scan before this.
         */
        void readsCarried(java.nio.file.Path path, int from, int to, Set<String> seen,
                          Map<java.nio.file.Path, java.util.TreeMap<Integer, String>> into, boolean itself) {
            String text = texts.get(path);
            byte[] kind = kinds.get(path);
            noteMessageReads(path, from, to, into);
            int at = from;
            while (at < to) {
                char c = text.charAt(at);
                if (kind[at] != CODE || !Character.isJavaIdentifierStart(c)
                        || at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))) {
                    at++;
                    continue;
                }
                int end = at;
                while (end < to && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                String name = text.substring(at, end);
                int next = end;
                while (next < to && Character.isWhitespace(text.charAt(next))) next++;
                if (name.matches("[a-z]\\w*") && !NOT_VALUES.contains(name)) {
                    String receiver = receiver(path, at);
                    if (next < to && text.charAt(next) == '(') {
                        if (!"L10n".equals(receiver)) {
                            for (java.nio.file.Path target : targets(path, at)) {
                                for (int[] body : bodiesOf(target, name)) {
                                    if (!seen.add("call " + target + "#" + body[0])) continue;
                                    for (int[] answer : returnsIn(target, body)) {
                                        readsCarried(target, answer[0], answer[1], seen, into, itself);
                                    }
                                }
                            }
                        }
                    } else {
                        for (Map.Entry<java.nio.file.Path, List<int[]>> set
                                : valueSpans(path, at, name, receiver).entrySet()) {
                            for (int[] span : set.getValue()) {
                                if (!seen.add("value " + set.getKey() + "@" + span[0])) continue;
                                readsCarried(set.getKey(), span[0], span[1], seen, into, itself);
                            }
                        }
                        if (itself && receiver == null && showsWhole(text, kind, end)
                                && catchAround(text, kind, name, at) != null) {
                            into.computeIfAbsent(path, key -> new java.util.TreeMap<>()).put(at, "");
                        }
                    }
                }
                at = end;
            }
        }

        /**
         * Where a value named at {@code at} is set, by file: a local, or a String field of its own
         * file (through {@code this.} too), or a local StringBuilder's start and appends; or for a
         * {@code Type.field}, its assignments in Type's file and in any file that writes it so.
         */
        Map<java.nio.file.Path, List<int[]>> valueSpans(java.nio.file.Path path, int at, String name,
                                                        String receiver) {
            String text = texts.get(path);
            byte[] kind = kinds.get(path);
            Map<java.nio.file.Path, List<int[]>> spans = new LinkedHashMap<>();
            if (receiver == null || receiver.equals("this")) {
                boolean ofThis = receiver != null;
                List<int[]> set = ofThis ? new ArrayList<>() : assignmentsReaching(text, kind, name, at);
                if (set.isEmpty()) set = fieldAssignments(text, kind, name, at, ofThis);
                if (set.isEmpty() && !ofThis) set = builderSpans(text, kind, name, at);
                spans.put(path, set);
            } else if (!receiver.isEmpty() && Character.isUpperCase(receiver.charAt(0))) {
                for (java.nio.file.Path file : classes.getOrDefault(receiver, new ArrayList<>())) {
                    spans.computeIfAbsent(file, key -> new ArrayList<>())
                            .addAll(fieldAssignments(texts.get(file), kinds.get(file), name, 0, true));
                }
                java.util.regex.Pattern written = java.util.regex.Pattern.compile("(?<![\\w$.])"
                        + java.util.regex.Pattern.quote(receiver) + "\\s*\\.\\s*"
                        + java.util.regex.Pattern.quote(name) + "\\s*\\+?=(?!=)");
                for (java.nio.file.Path file : texts.keySet()) {
                    String other = texts.get(file);
                    byte[] otherKind = kinds.get(file);
                    java.util.regex.Matcher assigned = written.matcher(other);
                    while (assigned.find()) {
                        if (otherKind[assigned.start()] != CODE) continue;
                        int end = statementEnd(other, otherKind, assigned.end());
                        if (end > 0) {
                            spans.computeIfAbsent(file, key -> new ArrayList<>()).add(new int[]{assigned.end(), end});
                        }
                    }
                }
            }
            return spans;
        }

        /** The body of each method called {@code name} that {@code path} declares, as its two braces. */
        List<int[]> bodiesOf(java.nio.file.Path path, String name) {
            String text = texts.get(path);
            byte[] kind = kinds.get(path);
            List<int[]> bodies = new ArrayList<>();
            java.util.regex.Matcher match = java.util.regex.Pattern
                    .compile("\\b" + java.util.regex.Pattern.quote(name) + "\\s*\\(").matcher(text);
            while (match.find()) {
                if (kind[match.start()] != CODE || receiver(path, match.start()) != null) continue;
                int close = closingBracket(text, kind, match.end() - 1);
                if (close < 0) continue;
                int brace = openingBraceAfter(text, kind, close);
                if (brace < 0) continue;
                int end = closingBrace(text, kind, brace);
                if (end > 0) bodies.add(new int[]{brace, end});
            }
            return bodies;
        }

        /** What each return in a method body hands back, leaving out an anonymous class's own methods. */
        List<int[]> returnsIn(java.nio.file.Path path, int[] body) {
            String text = texts.get(path);
            byte[] kind = kinds.get(path);
            List<int[]> answers = new ArrayList<>();
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\breturn\\b").matcher(text);
            match.region(body[0], body[1]);
            while (match.find()) {
                if (kind[match.start()] != CODE) continue;
                if (methodBodyStart(text, kind, match.start(), false) != body[0]) continue;
                int end = statementEnd(text, kind, match.end());
                if (end > match.end()) answers.add(new int[]{match.end(), end});
            }
            return answers;
        }
    }

    /** A method or constructor head just before its body's brace; if, for, catch and the rest aren't. */
    private static final java.util.regex.Pattern METHOD_HEAD = java.util.regex.Pattern.compile(
            "\\b(?!(?:if|for|while|switch|catch|synchronized|try)\\b)\\w+\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)"
                    + "\\s*(?:throws\\s+[\\w.,\\s]+)?$");

    /** An anonymous class's head, {@code new Runnable()}, which METHOD_HEAD would take for a method. */
    private static final java.util.regex.Pattern ANONYMOUS_HEAD = java.util.regex.Pattern.compile(
            "\\bnew\\s+[\\w.]+\\s*(?:<[^{};]*>)?\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)\\s*$");

    /** A static initializer's head, which holds statements as a method does. */
    private static final java.util.regex.Pattern STATIC_HEAD = java.util.regex.Pattern.compile("\\bstatic\\s*$");

    /**
     * Where the body of the method holding {@code at} opens, or 0 when no method head is found.
     * A method of an anonymous class counts as part of the method around that class, whose
     * variables it can read.
     */
    private static int methodBodyStart(String text, byte[] kind, int at) {
        return methodBodyStart(text, kind, at, true);
    }

    /** {@code throughAnonymous} false stops at the innermost method, an anonymous class's own. */
    private static int methodBodyStart(String text, byte[] kind, int at, boolean throughAnonymous) {
        int inner = 0;
        int method = 0;
        boolean inMethod = false;
        for (int i = at - 1; i >= 0; i--) {
            if (kind[i] != CODE) continue;
            char c = text.charAt(i);
            if (c == '}') {
                inner++;
            } else if (c == '{') {
                if (inner > 0) {
                    inner--;
                    continue;
                }
                String head = codeBefore(text, kind, i, 600);
                boolean anonymous = ANONYMOUS_HEAD.matcher(head).find();
                if (inMethod) {
                    // The block around a method is its class: an anonymous one sends the walk on
                    // to the method around it, a named one ends it.
                    if (!anonymous) return method;
                    inMethod = false;
                } else if (!anonymous && (METHOD_HEAD.matcher(head).find() || STATIC_HEAD.matcher(head).find())) {
                    method = i;
                    if (!throughAnonymous) return method;
                    inMethod = true;
                }
            }
        }
        return method;
    }

    /** Whether the block holding {@code declaration} is still open at {@code use}. */
    private static boolean inScope(String text, byte[] kind, int declaration, int use) {
        int depth = 0;
        for (int i = declaration; i < use; i++) {
            if (kind[i] != CODE) continue;
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth < 0) return false;
        }
        return true;
    }

    /** Whether an assignment at {@code at} is to one of {@code shadows}, a variable of its own block. */
    private static boolean shadowed(String text, byte[] kind, java.util.List<Integer> shadows, int at) {
        for (int shadow : shadows) {
            if (shadow <= at && inScope(text, kind, shadow, at)) return true;
        }
        return false;
    }

    /** Whether the method whose body opens at {@code body} takes a parameter called {@code name}. */
    private static boolean takesParameter(String text, byte[] kind, int body, String name) {
        String head = codeBefore(text, kind, body, 600);
        java.util.regex.Matcher method = METHOD_HEAD.matcher(head);
        if (!method.find()) return false;
        String parameters = head.substring(head.indexOf('(', method.start()), method.end());
        return java.util.regex.Pattern.compile("[\\w>\\]]\\s+" + name + "\\s*[,)]").matcher(parameters).find();
    }

    /** The {@code length} characters before {@code end}, with comments and literals blanked out. */
    private static String codeBefore(String text, byte[] kind, int end, int length) {
        int start = Math.max(0, end - length);
        StringBuilder code = new StringBuilder(end - start);
        for (int i = start; i < end; i++) code.append(kind[i] == CODE ? text.charAt(i) : ' ');
        return code.toString();
    }

    /** The semicolon ending the statement that runs on from {@code from}, or -1. */
    private static int statementEnd(String text, byte[] kind, int from) {
        int depth = 0;
        for (int at = from; at < text.length(); at++) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == ';' && depth == 0) return at;
            if (depth < 0) return -1;
        }
        return -1;
    }

    /**
     * Whether a value whose name ends at {@code end} is used whole: anything but a member read
     * (toString aside), an instanceof or a comparison, past brackets that only group or cast it.
     * A call's bracket closing after it hands it on whole.
     */
    private static boolean showsWhole(String text, byte[] kind, int end) {
        int after = end;
        while (true) {
            while (after < text.length() && (kind[after] != CODE || Character.isWhitespace(text.charAt(after)))) {
                after++;
            }
            if (after >= text.length() || text.charAt(after) != ')') break;
            int open = openingBracket(text, kind, after);
            if (open >= 0 && callBracket(text, 0, open)) return true;
            after++;
        }
        String rest = text.substring(after, Math.min(text.length(), after + 40));
        if (rest.startsWith(".")) return rest.matches("(?s)\\.\\s*toString\\s*\\(.*");
        return !rest.matches("(?s)(?:instanceof\\b|[=!]=).*");
    }

    /** The bracket that the one at {@code close} closes, or -1. */
    private static int openingBracket(String text, byte[] kind, int close) {
        int depth = 0;
        for (int at = close; at >= 0; at--) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == ')' || c == ']' || c == '}') depth++;
            else if ((c == '(' || c == '[' || c == '{') && --depth == 0) return at;
        }
        return -1;
    }

    /**
     * Where a local StringBuilder or StringBuffer {@code name}, read at {@code use}, is built: its
     * initializer and each append or insert to it between there and the read, chained ones too.
     */
    private static java.util.List<int[]> builderSpans(String text, byte[] kind, String name, int use) {
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        String quoted = java.util.regex.Pattern.quote(name);
        java.util.regex.Matcher declared = java.util.regex.Pattern
                .compile("\\b(?:StringBuilder|StringBuffer)\\s+" + quoted + "\\s*=(?!=)").matcher(text);
        declared.region(methodBodyStart(text, kind, use), use);
        int from = -1;
        while (declared.find()) {
            if (kind[declared.start()] == CODE && inScope(text, kind, declared.start(), use)) from = declared.end();
        }
        if (from < 0) return spans;
        int end = statementEnd(text, kind, from);
        if (end > 0) spans.add(new int[]{from, end});
        java.util.regex.Pattern added = java.util.regex.Pattern.compile("\\s*\\.\\s*(?:append|insert)\\s*\\(");
        java.util.regex.Matcher built = java.util.regex.Pattern
                .compile("(?<![\\w$.])" + quoted + "(?=\\s*\\.\\s*(?:append|insert)\\s*\\()").matcher(text);
        built.region(from, use);
        while (built.find()) {
            if (kind[built.start()] != CODE) continue;
            int after = built.end();
            java.util.regex.Matcher call = added.matcher(text);
            // Each append of a chain, as in text.append(a).append(b), adds to the same builder.
            while (call.region(after, use).lookingAt()) {
                int close = closingBracket(text, kind, call.end() - 1);
                if (close < 0) break;
                spans.add(new int[]{call.end(), close});
                after = close + 1;
            }
        }
        return spans;
    }

    /** Null when the message follows the rule, otherwise what it gets wrong. */
    static String punctuationFault(String message) {
        String trimmed = message.trim();
        if (trimmed.isEmpty()) return null;
        // A sentence boundary is a terminator followed by a space and the start of the next
        // sentence, whatever it starts with: a capital, a quote, a placeholder, a number or a
        // word left in lower case by mistake.
        boolean several = trimmed.matches("(?s).*[.!?][\"”']?\\s+[A-Za-z0-9\"“%].*");
        boolean ends = trimmed.matches("(?s).*[.!?][\"”']?$");
        if (!several && ends) return "one sentence ends with a stop";
        // A message that ends on a placeholder ends on a path or a name, and a stop after it
        // reads as part of the path, so "Saved. Recording saved to %1$s" keeps its last stop off.
        if (several && !ends && !trimmed.matches("(?s).*%\\d+\\$[sd]$")) {
            return "several sentences and the last has no stop";
        }
        return null;
    }

    /**
     * Anything that puts words on the screen. Any method with "toast" in its name,
     * however it is spelled, plus the platform call those wrap and the undo banner,
     * which is a toast in every way that matters to a reader.
     *
     * <p>A content description counts too. Nothing shows it on screen, but it is the only
     * words a reader using TalkBack gets for a button drawn as a glyph, and three of the
     * player's overlay buttons reached that reader in English on a German phone.
     *
     * <p>Named this widely on purpose. The first version knew two method names, and a
     * file with a private toast helper of its own walked past it carrying five English
     * messages.
     */
    private static final String SHOWS_TEXT = "(?:\\b\\w*[Tt]oast\\w*|Toast\\s*\\.\\s*makeText|showUndoBanner"
            + "|showActionBanner|showNoticeBanner"
            + "|setContentDescription|setStateDescription|SettingsUi\\s*\\.\\s*text)\\s*\\(";

    /** Marks every character of a source file as code, inside a literal, or inside a comment. */
    private static final byte CODE = 0, LITERAL = 1, COMMENT = 2;

    private static byte[] classify(String text) {
        byte[] kind = new byte[text.length()];
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '/') {
                while (at < text.length() && text.charAt(at) != '\n') kind[at++] = COMMENT;
            } else if (c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '*') {
                int close = text.indexOf("*/", at + 2);
                int stop = close < 0 ? text.length() : close + 2;
                while (at < stop) kind[at++] = COMMENT;
            } else if (c == '"' || c == '\'') {
                kind[at++] = LITERAL;
                while (at < text.length()) {
                    char inside = text.charAt(at);
                    kind[at++] = LITERAL;
                    if (inside == '\\') { if (at < text.length()) kind[at++] = LITERAL; continue; }
                    if (inside == c) break;
                }
            } else {
                kind[at++] = CODE;
            }
        }
        return kind;
    }

    /**
     * Every string shown by a toast in this file that does not go through the table, as
     * "line  text". A literal counts as translated when the brackets it sits directly inside
     * belong to {@code L10n.t} or {@code L10n.f}; anything else, including a ternary and a
     * concatenation, is the reader seeing English.
     */
    private static java.util.List<String> rawToastsIn(String text) {
        byte[] kind = classify(text);
        java.util.List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher call = java.util.regex.Pattern
                .compile(SHOWS_TEXT).matcher(text);
        while (call.find()) {
            int open = call.end() - 1;
            if (kind[call.start()] != CODE) continue;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            for (int at = open + 1; at < close; at++) {
                if (kind[at] != LITERAL || text.charAt(at) != '"') continue;
                int literalEnd = at + 1;
                while (literalEnd < close && kind[literalEnd] == LITERAL) literalEnd++;
                // An empty literal says nothing, so there is nothing to translate. Eight of
                // these are the getTitle() == null ? "" : ... idiom on the hand built dialogs.
                boolean empty = literalEnd == at + 2;
                if (!empty && !throughTheTable(text, kind, at)) {
                    found.add(lineOf(text, at) + "  "
                            + text.substring(at, Math.min(literalEnd, at + 70)));
                }
                at = literalEnd;
            }
        }
        return found;
    }

    private static int closingBracket(String text, byte[] kind, int open) {
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            if (kind[at] != CODE) continue;
            if (text.charAt(at) == '(') depth++;
            else if (text.charAt(at) == ')' && --depth == 0) return at;
        }
        return -1;
    }

    /** The call whose brackets this literal sits directly inside, if it is one of L10n's. */
    private static boolean throughTheTable(String text, byte[] kind, int literalAt) {
        int depth = 0;
        int owner = -1;
        for (int at = literalAt - 1; at >= 0; at--) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) { owner = at; break; }
                depth--;
            }
        }
        if (owner < 0) return false;
        int end = owner;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.') start--;
            else break;
        }
        String name = text.substring(start, end);
        for (String lookup : new String[]{"L10n.t", "L10n.f", "L10n.quantity"}) {
            if (name.equals(lookup) || name.endsWith("." + lookup)) return true;
        }
        return false;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int at = 0; at < index; at++) if (text.charAt(at) == '\n') line++;
        return line;
    }

    @Test
    public void everyLanguageTheBundleCarriesHasATable() {
        assertNotNull("German", L10nTranslations.of("de"));
        for (String language : L10nTranslations.LANGUAGES) {
            assertNotNull(language, L10nTranslations.of(language));
        }
        // A language nobody translated into falls back rather than failing.
        assertNull(L10nTranslations.of("xx"));
    }

    @Test
    public void aCountrySpecificLocaleFallsBackToItsLanguage() {
        // The tables are named by language; Austrian German gets the German table.
        assertEquals("Beschreibung ausblenden",
                L10n.t(contextFor("de", "AT"), "Hide the caption"));
        assertEquals("Beschreibung ausblenden",
                L10n.t(contextFor("de", ""), "Hide the caption"));
        // Indonesian arrives as either ISO code depending on the runtime, and both find it.
        assertEquals("Sembunyikan keterangan", L10n.t(contextFor("in", "ID"), "Hide the caption"));
        assertEquals("Sembunyikan keterangan", L10n.t(contextFor("id", "ID"), "Hide the caption"));
        // A language with no table keeps the English.
        assertEquals("Hide the caption", L10n.t(contextFor("fr", "FR"), "Hide the caption"));
    }

    @Test
    public void simplifiedChineseUsesItsScriptTableAndTraditionalChineseKeepsEnglish() {
        Context simplified = contextFor(Locale.forLanguageTag("zh-Hans-CN"));
        assertEquals(List.of("zh-hans-rcn", "zh-hans"), L10n.tags(simplified));
        assertEquals("隐藏文案", L10n.t(simplified, "Hide the caption"));
        assertEquals("信息流过滤器", L10n.t(simplified, "Feed filter"));
        assertEquals("信息流界面", L10n.t(simplified, "Feed screen"));
        assertEquals("信息流标签页", L10n.t(simplified, "Feed tabs"));
        assertEquals("你的信息流", L10n.t(simplified, "Your feed"));
        assertEquals("zh-hans", L10n.tableTag(simplified));
        assertEquals("zh-Hans", L10n.shownLocale(simplified).toLanguageTag());

        Context mainland = contextFor(Locale.forLanguageTag("zh-CN"));
        assertEquals(List.of("zh-hans-rcn", "zh-hans"), L10n.tags(mainland));
        assertEquals("隐藏文案", L10n.t(mainland, "Hide the caption"));

        Context traditional = contextFor(Locale.forLanguageTag("zh-Hant-TW"));
        assertEquals(List.of("zh-hant-rtw", "zh-hant"), L10n.tags(traditional));
        assertEquals("Hide the caption", L10n.t(traditional, "Hide the caption"));
        assertNull(L10n.tableTag(traditional));

        Context taiwan = contextFor(Locale.forLanguageTag("zh-TW"));
        assertEquals(List.of("zh-hant-rtw", "zh-hant"), L10n.tags(taiwan));
        assertEquals("Hide the caption", L10n.t(taiwan, "Hide the caption"));
    }

    @Test
    public void asecondLanguageAnswersWhenTheFirstHasNoTable() {
        // A phone set to French and then German shows German, because Android resolves a
        // string against the whole language list rather than only the first entry.
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocales(new LocaleList(new Locale("fr", "FR"), new Locale("de", "DE")));
        Context context = RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
        assertEquals(List.of("fr-rfr", "fr", "de-rde", "de"), L10n.tags(context));
        assertEquals("Beschreibung ausblenden", L10n.t(context, "Hide the caption"));
        // The order is the phone's: a language with a table earlier in the list wins.
        configuration.setLocales(new LocaleList(new Locale("in", "ID"), new Locale("de", "DE")));
        assertEquals("Sembunyikan keterangan", L10n.t(
                RuntimeEnvironment.getApplication().createConfigurationContext(configuration),
                "Hide the caption"));
    }

    @Test
    public void englishAheadOfAnotherLanguageKeepsTheSettingsEnglish() {
        // English is the text itself. A phone set to English and then German runs TikTok in
        // English, and the settings used to skip past it to the German table.
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocales(new LocaleList(new Locale("en", "US"), new Locale("de", "DE")));
        assertEquals("Hide the caption", L10n.t(
                RuntimeEnvironment.getApplication().createConfigurationContext(configuration),
                "Hide the caption"));
        // A language with no table ahead of English falls through to English and stops there.
        configuration.setLocales(new LocaleList(new Locale("fr", "FR"), new Locale("en", "GB"),
                new Locale("de", "DE")));
        assertEquals("Hide the caption", L10n.t(
                RuntimeEnvironment.getApplication().createConfigurationContext(configuration),
                "Hide the caption"));
    }

    /** A context whose resources report one locale, which is what the lookup reads. */
    private static Context contextFor(String language, String country) {
        return contextFor(new Locale(language, country));
    }

    private static Context contextFor(Locale locale) {
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocale(locale);
        return RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
    }

    @Test
    public void englishStaysEnglishAndUnknownTextPassesThrough() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        assertEquals("Hide the caption", L10n.t("Hide the caption"));
        assertEquals("Not a settings string", L10n.t("Not a settings string"));
        assertEquals("Current: 3 videos", L10n.f("Current: %1$s", L10n.quantity(Utils.getContext(), 3, "%1$s video", "%1$s videos", "3")));
    }

    @Test
    public void theMusicLineSummaryDescribesTheLabelInEveryLocale() throws Exception {
        String summary = "Hide the track name beside the caption.";
        String oldSummary = "Hide the spinning music cover and the track name beside the caption.";
        SettingsStatus.videoOverlaysEnabled = true;
        List<String> locales = new ArrayList<>(languages());
        locales.add(ENGLISH_BASE);
        for (String language : locales) {
            Map<String, String> source = readTable(language);
            String expected = source.get(summary);
            assertNotNull(language + " is missing the label summary", expected);
            assertFalse(language + " still promises to hide the disc", source.containsKey(oldSummary));
            if (!language.equals(ENGLISH_BASE)) {
                assertEquals(language, expected, L10nTranslations.of(language).get(summary));
                assertNotEquals(language + " fell back to English", summary, expected);
            }
            String[] tag = language.split("-r", 2);
            Context context = contextFor(tag[0], tag.length == 1 ? "" : tag[1].toUpperCase(Locale.ROOT));
            try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
                PreferenceScreen screen = controller.get().getPreferenceManager().createPreferenceScreen(context);
                new InterfacePreferenceCategory(context, screen);
                Preference music = screen.findPreference(Settings.HIDE_FEED_MUSIC.key);
                assertNotNull(language + " has no music-line row", music);
                assertEquals(language, expected, music.getSummary().toString());
            }
        }
    }

    @Test
    public void nativeDescriptionAndAuthorSizingHaveIndependentRowsInEveryLocale() throws Exception {
        String[] titles = {"Description text size", "Author text size"};
        String[] summaries = {
                "Use 0 for TikTok's size, or %1$d to %2$d. Sizes the description below the author's name and keeps Android's font scaling.",
                "Use 0 for TikTok's size, or %1$d to %2$d. Sizes the author's name and the post date next to it, and keeps Android's font scaling."};
        String[] keys = {Settings.FEED_DESCRIPTION_TEXT_SIZE.key, Settings.FEED_AUTHOR_TEXT_SIZE.key};
        SettingsStatus.feedTextSizeEnabled = true;
        List<String> locales = new ArrayList<>(languages());
        locales.add(ENGLISH_BASE);
        for (String language : locales) {
            Map<String, String> source = readTable(language);
            for (String key : new String[]{titles[0], titles[1], summaries[0], summaries[1]}) {
                assertNotNull(language + " is missing " + key, source.get(key));
                if (!language.equals(ENGLISH_BASE)) {
                    assertEquals(language, source.get(key), L10nTranslations.of(language).get(key));
                    assertNotEquals(language + " fell back to English", key, source.get(key));
                }
            }
            String[] tag = language.split("-r", 2);
            Context context = contextFor(tag[0], tag.length == 1 ? "" : tag[1].toUpperCase(Locale.ROOT));
            try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
                PreferenceScreen screen = controller.get().getPreferenceManager().createPreferenceScreen(context);
                new InterfacePreferenceCategory(context, screen);
                for (int i = 0; i < keys.length; i++) {
                    Preference row = screen.findPreference(keys[i]);
                    assertNotNull(language + " has no " + titles[i], row);
                    assertEquals(language, L10n.t(context, titles[i]), row.getTitle().toString());
                    assertTrue(language, row.getSummary().toString().startsWith(L10n.f(context, summaries[i],
                            app.morphe.extension.tiktok.feed.FeedTextSize.MIN_TEXT_SIZE,
                            app.morphe.extension.tiktok.feed.FeedTextSize.MAX_TEXT_SIZE)));
                }
                assertNull("Native sizing must not require or enable spoken subtitles",
                        screen.findPreference(Settings.CAPTION_TEXT_SIZE.key));
            }
        }
    }

    @Test
    @Config(sdk = 28, qualifiers = "de")
    public void germanShowsOnARealPreferenceUnderTheGermanLocale() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            TogglePreference toggle = new TogglePreference(activity, "Hide the caption",
                    "Hide the description under the creator's name on the feed.", Settings.HIDE_FEED_CAPTION);
            assertEquals("Beschreibung ausblenden", toggle.getTitle().toString());
            assertEquals("Die Beschreibung unter dem Namen des Creators im Feed ausblenden.",
                    toggle.getSummary().toString());
            assertEquals("Aktuell: 3 Videos", L10n.f(activity, "Current: %1$s", L10n.quantity(activity, 3, "%1$s video", "%1$s videos", "3")));
        }
    }

    @Test
    @Config(sdk = 28, qualifiers = "in-rID")
    public void indonesianShowsOnARealPreferenceUnderTheIndonesianLocale() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            TogglePreference toggle = new TogglePreference(activity, "Hide the caption",
                    "Hide the description under the creator's name on the feed.", Settings.HIDE_FEED_CAPTION);
            assertEquals("Sembunyikan keterangan", toggle.getTitle().toString());
            assertEquals("Sembunyikan deskripsi di bawah nama kreator pada feed.",
                    toggle.getSummary().toString());
            assertEquals("Saat ini: 3 video",
                    L10n.f(activity, "Current: %1$s", L10n.quantity(activity, 3, "%1$s video", "%1$s videos", "3")));
        }
    }

    @Test
    public void everyTableIsKeyedByTheEnglishTextItself() {
        assertEquals("Beschreibung ausblenden", GERMAN.get("Hide the caption"));
        assertEquals("Sembunyikan keterangan", INDONESIAN.get("Hide the caption"));
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            assertFalse(language, table.isEmpty());
            // Nothing is translated to nothing: an empty value would blank a label.
            for (Map.Entry<String, String> entry : table.entrySet()) {
                assertFalse("empty key in " + language, entry.getKey().isEmpty());
                assertFalse("empty translation of: " + entry.getKey(), entry.getValue().isEmpty());
            }
        }
    }

    @Test
    public void everyTableCoversTheSameEnglish() {
        // A language that is missing entries the others have would show a half English screen.
        // The extra plural rows a language may carry are its own and are left out of the
        // comparison, but each has to belong to a key the language also carries plainly.
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            Set<String> plain = new LinkedHashSet<>();
            for (String key : table.keySet()) {
                String base = pluralBase(key);
                if (base.equals(key)) {
                    plain.add(key);
                } else {
                    assertTrue(language + " carries " + key + " without its other form " + base,
                            table.containsKey(base));
                }
            }
            assertEquals("keys of " + language, sourceKeys(GERMAN), plain);
        }
    }

    /**
     * A language whose plural rule splits everyday counts more finely than English's two forms
     * carries a row for each extra form, for every count the code words. Russian says
     * 1 результат, 2 результата, 5 результатов and 21 результат: a count with no few row read
     * "2 результатов", and with no one row past 1, "21 результатов".
     */
    @Test
    public void everyCountCarriesTheFormsItsLanguageNeeds() throws Exception {
        Set<String> others = quantityOtherForms();
        assertTrue("the scan found too few counts to mean anything: " + others.size(), others.size() > 20);
        List<String> missing = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            android.icu.text.PluralRules rules = android.icu.text.PluralRules.forLocale(
                    L10n.localeOf(language));
            Set<String> needed = new LinkedHashSet<>();
            for (long count = 0; count <= 200; count++) {
                String category = rules.select(count);
                // English's forms cover exactly 1 and everything in "other". A one category
                // that also takes 0 reads fine in the other form; one that takes 21 doesn't.
                if (!category.equals("other") && !(category.equals("one") && count <= 1)) {
                    needed.add(category);
                }
            }
            for (String other : others) {
                for (String category : needed) {
                    if (!table.containsKey(other + "|" + category)) {
                        missing.add(language + ": " + other + "|" + category);
                    }
                }
            }
        }
        assertEquals("count forms missing from the tsv files:\n" + String.join("\n", missing),
                0, missing.size());
    }

    /**
     * The other form of every count the code words: the fourth argument of each L10n.quantity
     * call, and the plural unit of each number row, which NumberInputPreference hands to
     * L10n.quantity itself ("%1$s s" under Seek by needs its few row as much as a sentence
     * does). Read by position, so a one form that isn't a literal can't shift another argument
     * into its place. An other form that isn't written out where the count is worded fails
     * rather than going unchecked; NumberInputPreference's own call is the one exception, and
     * its units are read from the rows that pass them. A unit given once, or with the same one
     * and other form, is a symbol (s, dp, %) that reads the same for every count.
     */
    private static Set<String> quantityOtherForms() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree", root.isDirectory());
        java.util.regex.Pattern call = java.util.regex.Pattern.compile("L10n\\s*\\.\\s*quantity\\s*\\(");
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "new\\s+(?:[\\w.]+\\.)?NumberInputPreference\\s*\\(");
        // A subclass's super(...) and NumberInputPreference's own this(...) pass units too.
        java.util.regex.Pattern chained = java.util.regex.Pattern.compile("\\b(?:super|this)\\s*\\(");
        Set<String> others = new LinkedHashSet<>();
        List<String> unread = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(root.toPath())) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                byte[] kind = classify(text);
                String name = file.getFileName().toString();
                boolean numberInput = name.equals("NumberInputPreference.java");
                java.util.regex.Matcher match = call.matcher(text);
                while (match.find()) {
                    if (kind[match.start()] != CODE) continue;
                    List<int[]> arguments = topLevelArguments(text, kind, match.end() - 1);
                    String one = arguments.size() > 3 ? literalArgument(text, kind, arguments.get(2)) : null;
                    String other = arguments.size() > 3 ? literalArgument(text, kind, arguments.get(3)) : null;
                    if (other != null) {
                        if (!other.isEmpty() && !other.equals(one)) others.add(other);
                    } else if (!numberInput) {
                        unread.add(name + ": " + firstLine(text, match.start()));
                    }
                }
                List<java.util.regex.Matcher> rows = new ArrayList<>();
                rows.add(row.matcher(text));
                if (numberInput || text.contains("extends NumberInputPreference")) rows.add(chained.matcher(text));
                for (java.util.regex.Matcher rowMatch : rows) {
                    while (rowMatch.find()) {
                        if (kind[rowMatch.start()] != CODE) continue;
                        List<int[]> arguments = topLevelArguments(text, kind, rowMatch.end() - 1);
                        // (context, title, summary, setting, unit) or (..., setting, one, other).
                        if (arguments.size() != 5 && arguments.size() != 6) continue;
                        String one = literalArgument(text, kind, arguments.get(4));
                        String other = literalArgument(text, kind, arguments.get(arguments.size() - 1));
                        if (other != null) {
                            if (!other.isEmpty() && arguments.size() == 6 && !other.equals(one)) others.add(other);
                        } else if (!numberInput) {
                            unread.add(name + ": " + firstLine(text, rowMatch.start()));
                        }
                    }
                }
            }
        }
        assertEquals("counts worded from something other than a literal, which this can't check:\n"
                + String.join("\n", unread), 0, unread.size());
        return others;
    }

    /** The spans of a call's own arguments, split at its own commas; open is its bracket. */
    private static List<int[]> topLevelArguments(String text, byte[] kind, int open) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int at = open; at < text.length(); at++) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth == 0) {
                    if (!text.substring(start, at).trim().isEmpty()) spans.add(new int[]{start, at});
                    return spans;
                }
            } else if (c == ',' && depth == 1) {
                spans.add(new int[]{start, at});
                start = at + 1;
            }
        }
        return spans;
    }

    /** An argument made of string literals and pluses alone, as the one string it is; else null. */
    private static String literalArgument(String text, byte[] kind, int[] span) {
        StringBuilder value = new StringBuilder();
        boolean any = false;
        for (int at = span[0]; at < span[1]; at++) {
            char c = text.charAt(at);
            if (kind[at] == COMMENT) continue;
            if (kind[at] == CODE) {
                if (c == '+' || Character.isWhitespace(c)) continue;
                return null;
            }
            if (c != '"') return null;
            int end = at + 1;
            while (end < span[1] && kind[end] == LITERAL) end++;
            int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
            value.append(unescape(text.substring(at + 1, contentEnd)));
            any = true;
            at = end - 1;
        }
        return any ? value.toString() : null;
    }

    private static String firstLine(String text, int from) {
        int end = text.indexOf('\n', from);
        return text.substring(from, end < 0 ? text.length() : end).trim();
    }

    /** The positional scan reads an other form wherever it sits, and refuses one it can't read. */
    @Test public void theCountScanReadsTheOtherFormByPosition() {
        String source = "L10n.quantity(context, n, flag ? \"a\" : \"b\", \"%1$d c\" + \"s\", n, x);";
        byte[] kind = classify(source);
        List<int[]> arguments = topLevelArguments(source, kind, source.indexOf('('));
        assertEquals(6, arguments.size());
        assertEquals("%1$d cs", literalArgument(source, kind, arguments.get(3)));
        assertNull(literalArgument(source, kind, arguments.get(2)));
        assertNull(literalArgument(source, kind, arguments.get(1)));
        String nested = "new NumberInputPreference(context, L10n.f(context, \"t %1$d\", 1, 2), \"s\", s, \"%1$s day\", \"%1$s days\")";
        kind = classify(nested);
        arguments = topLevelArguments(nested, kind, nested.indexOf('('));
        assertEquals(6, arguments.size());
        assertEquals("%1$s days", literalArgument(nested, kind, arguments.get(5)));
    }

    /**
     * A key is a whole phrase, never a lone unit or conjunction. "days", "dp", "or" and "view
     * per like" were keys assembled into sentences in code, which fixed the word order and the
     * agreement to English; each is now a phrase with its number or its list in it.
     */
    @Test public void noKeyIsALoneUnitOrConjunction() throws Exception {
        // Words that stand alone on purpose: a state a screen reader speaks for a row.
        Set<String> standalone = new LinkedHashSet<>(List.of("collapsed", "expanded"));
        java.util.regex.Pattern lone = java.util.regex.Pattern.compile(
                "^(?:[a-z]+|[a-z]+ per [a-z ]+)$");
        List<String> offenders = new ArrayList<>();
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (lone.matcher(key).matches() && !standalone.contains(key)) offenders.add(key);
        }
        assertEquals("keys that are a lone unit or conjunction:\n" + String.join("\n", offenders),
                0, offenders.size());
    }

    /** The gates read a {@code |category} row as its other form, and nothing else as one. */
    @Test public void aPluralVariantRowBelongsToItsOtherForm() {
        assertEquals("%1$d results", pluralBase("%1$d results|few"));
        assertEquals("%1$d results", pluralBase("%1$d results|many"));
        assertEquals("Filter: %1$s", pluralBase("Filter: %1$s"));
        // Only a CLDR category counts; a bar inside ordinary text is text.
        assertEquals("a|b", pluralBase("a|b"));
        assertEquals("Something|other", pluralBase("Something|other"));
    }

    /** The other form a {@code |category} row belongs to, or the key itself. */
    static String pluralBase(String key) {
        java.util.regex.Matcher variant = java.util.regex.Pattern
                .compile("^(.+)\\|(?:zero|one|two|few|many)$").matcher(key);
        return variant.matches() ? variant.group(1) : key;
    }

    /** A table's keys without the {@code |category} rows: the strings the code says. */
    private static Set<String> sourceKeys(Map<String, String> table) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : table.keySet()) {
            if (pluralBase(key).equals(key)) keys.add(key);
        }
        return keys;
    }

    @Test
    public void everySettingsStringHasATranslationEntry() throws Exception {
        Set<String> english = new LinkedHashSet<>(sourceKeys(GERMAN));
        Set<String> shown = collectEverything();
        List<String> missing = new ArrayList<>();

        // A restart-gated row's summary is its own sentence plus the shared restart sentence,
        // looked up separately and joined afterwards. Both halves still have to be entries, so
        // the tail is taken off here and checked on its own rather than exempted.
        assertTrue("the restart sentence itself is not in the table",
                english.contains(TogglePreference.RESTART_SENTENCE));

        List<java.util.regex.Pattern> templates = templates(english);
        for (String text : shown) {
            // Whole first. Plenty of summaries were written with the restart sentence in them
            // and are one key including it, so splitting before looking would break those.
            if (english.contains(text)) continue;

            String body = text;
            // Both joins. A summary that is a sentence takes the note after a space; one that
            // is a state line takes it on the line below. Stripping only the first left every
            // tab row exempted as composed by the newline the note itself had just added.
            if (body.endsWith(" " + TogglePreference.RESTART_SENTENCE)
                    || body.endsWith("\n" + TogglePreference.RESTART_SENTENCE)) {
                body = body.substring(0,
                        body.length() - TogglePreference.RESTART_SENTENCE.length() - 1);
            }
            // Text spanning lines is assembled from parts that are entries of their own.
            // Exempting anything merely carrying a digit or a slash let 63 of 619 strings
            // through, including every message with a value in it. Text a placeholder key
            // produces (a limit formatted in from a constant) is accepted only when one of
            // those keys matches it whole, not because it carries a number.
            boolean composed = body.contains("\n") || body.matches("(?s).*%\\d\\$.*");
            if (!composed && !isValueRatherThanProse(body) && !english.contains(body)
                    && !producedByAKey(body, templates)) {
                missing.add(body);
            }
        }
        assertEquals("settings text without a translation entry: " + missing, 0, missing.size());
    }

    /** The keys with placeholders, as patterns for the text they can produce. */
    private static List<java.util.regex.Pattern> templates(Set<String> keys) {
        List<java.util.regex.Pattern> found = new ArrayList<>();
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("%\\d\\$([sd])");
        for (String key : keys) {
            java.util.regex.Matcher match = placeholder.matcher(key);
            if (!match.find()) continue;
            // A key that is nearly all placeholder would accept nearly anything, which would
            // make this check say nothing.
            if (key.replaceAll("%\\d\\$[sd]", "").trim().length() < 8) continue;
            StringBuilder regex = new StringBuilder("(?s)");
            int last = 0;
            do {
                regex.append(java.util.regex.Pattern.quote(key.substring(last, match.start())));
                // A number the way NumberFormat writes it, or any text at all for %s.
                regex.append("d".equals(match.group(1)) ? "-?[\\d,.]+" : ".+?");
                last = match.end();
            } while (match.find());
            regex.append(java.util.regex.Pattern.quote(key.substring(last)));
            found.add(java.util.regex.Pattern.compile(regex.toString()));
        }
        return found;
    }

    private static boolean producedByAKey(String text, List<java.util.regex.Pattern> templates) {
        for (java.util.regex.Pattern template : templates) {
            if (template.matcher(text).matches()) return true;
        }
        return false;
    }

    /**
     * A chosen value shown back to the reader rather than wording of ours. A resolution and a
     * speed are written the same way in every language, and the download folder is a real path
     * on the device. An entry mapping each of these to itself would only pad the table.
     *
     * <p>The SIM preset summary is the exception that is a real gap. It is built at runtime as
     * "country, carrier (mccmnc)", and the country reads differently in German and Indonesian.
     * Translating the preset list is its own task and is on the roadmap; matching it on the
     * carrier code it ends with keeps this check honest about what it lets past rather than
     * widening the rule until it disappears.
     */
    private static boolean isValueRatherThanProse(String text) {
        return text.matches("\\d+p")
                || text.matches("\\d+(\\.\\d+)?x")
                || text.matches("[A-Za-z]+(/[A-Za-z0-9_-]+)+")
                || text.matches(".*\\(\\d{5,6}\\)");
    }

    @Test
    public void theTableCarriesNothingTheScreensNoLongerSay() throws Exception {
        // A wording that drifted leaves its old entry behind, translated and unreachable, and
        // the next reader of the table cannot tell it from one that is still in use.
        Set<String> shown = new LinkedHashSet<>(collectEverything());
        shown.addAll(runtimeStringsInSource());

        Set<String> orphaned = new LinkedHashSet<>();
        for (String language : L10nTranslations.LANGUAGES) {
            for (String english : L10nTranslations.of(language).keySet()) {
                // A |category row lives or dies with the other form it belongs to.
                if (!shown.contains(pluralBase(english))) {
                    orphaned.add(english);
                }
            }
        }

        assertEquals("translation entries nothing shows any more, so remove them from the tsv "
                + "files and rerun scripts/gen-l10n.py: " + orphaned, 0, orphaned.size());
    }

    @Test
    public void everyStringHandedToL10nHasAnEntry() throws Exception {
        // Wrapping a string in L10n.t is what the toast and content-description check looks for,
        // and on its own it proves only the shape of the call. Without this, a new overlay label
        // wrapped correctly but never added to the tsv files passes every check and still
        // reaches the reader in English.
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree", root.isDirectory());

        java.util.regex.Pattern call = java.util.regex.Pattern.compile("L10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(");
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(root.toPath())) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                byte[] kind = classify(text);
                java.util.regex.Matcher match = call.matcher(text);
                while (match.find()) {
                    if (kind[match.start()] != CODE) continue;
                    int close = closingBracket(text, kind, match.end() - 1);
                    if (close < 0) continue;
                    List<String> parts = literalsIn(text, kind, match.end(), close);
                    if (parts.isEmpty()) continue;
                    found++;

                    // One message split across lines is a single key; a choice between two
                    // messages is two keys. Both are written as several literals in one call,
                    // so either reading is accepted and only a part in neither is reported.
                    if (GERMAN.containsKey(String.join("", parts))) continue;
                    for (String part : parts) {
                        if (!GERMAN.containsKey(part)) {
                            missing.add(file.getFileName() + ": " + part);
                        }
                    }
                }
            }
        }

        assertTrue("the scan found no L10n calls to read", found > 50);
        assertEquals("strings handed to L10n with no entry in the tsv files: " + missing,
                0, missing.size());
    }

    @Test
    public void everyProfileCountLabelHasAnEntryInEveryTable() {
        // The count editor hands L10n each Metric's title, a field rather than a literal, so the
        // scan above can't read it. "Likes" showed in English beside a translated "Follower" and
        // "Seguiti" on an Italian phone that way.
        List<String> missing = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            for (app.morphe.extension.tiktok.profile.LocalProfileCounts.Metric metric
                    : app.morphe.extension.tiktok.profile.LocalProfileCounts.Metric.values()) {
                if (!L10nTranslations.of(language).containsKey(metric.title)) {
                    missing.add(language + ": " + metric.title);
                }
            }
        }
        assertEquals("profile count labels with no entry in the tsv files: " + missing, 0, missing.size());
    }

    /**
     * Every word a settings row is built with is a key, read from the source rather than from
     * the screen.
     *
     * <p>The rendered check two tests up cannot see these. A row's summary is built at runtime
     * from its own wording plus a range line and a current-value line, and the joined text is
     * exempted there as composed, because the lines below the first are numbers formatted from
     * keys of their own. So the wording at the top of six Playback rows, and the unit words
     * beside their numbers, had no entry in any table and reached German, Spanish, Indonesian
     * and Brazilian phones in English under a translated title, with both gates passing.
     *
     * <p>Reading the constructor call instead sidesteps the formatting entirely: the literal
     * handed to the row is the key, before anything is joined to it.
     */
    @Test
    public void everyWordASettingsRowIsBuiltWithHasAnEntry() throws Exception {
        java.io.File categories = new java.io.File(
                "src/main/java/app/morphe/extension/tiktok/settings/preference/categories");
        if (!categories.isDirectory()) categories = new java.io.File("extensions/tiktok/src/main/java"
                + "/app/morphe/extension/tiktok/settings/preference/categories");
        assertTrue("could not find the settings categories", categories.isDirectory());

        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "new\\s+(?:[\\w.]+\\.)?(?:TogglePreference|NumberInputPreference"
                        + "|ClockHourPreference|InputTextPreference|RangeValuePreference)\\s*\\(");
        List<String> missing = new ArrayList<>();
        int rows = 0;
        java.io.File[] sources = categories.listFiles((dir, name) -> name.endsWith(".java"));
        assertTrue("no category sources to read", sources != null && sources.length > 5);
        for (java.io.File file : sources) {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            byte[] kind = classify(text);
            java.util.regex.Matcher match = row.matcher(text);
            while (match.find()) {
                if (kind[match.start()] != CODE) continue;
                int close = closingBracket(text, kind, match.end() - 1);
                if (close < 0) continue;
                rows++;
                for (String argument : arguments(text, kind, match.end() - 1, close)) {
                    if (argument.isEmpty() || GERMAN.containsKey(argument)) continue;
                    missing.add(file.getName() + ": " + argument);
                }
            }
        }

        assertTrue("the scan found too few settings rows to mean anything: " + rows, rows > 100);
        assertEquals("settings rows built from text with no entry in the tsv files, so it reaches "
                + "a translated phone in English: " + missing, 0, missing.size());
    }

    /**
     * A summary that sends the reader to another switch names that switch, in every language.
     *
     * <p>Three of the four translations of the store-region row told the reader to turn on
     * switches that are on no screen: "SIM-Details überschreiben" where the switch says
     * "SIM-Angaben überschreiben", "Ganti detail SIM" where it says "Timpa detail SIM". Each
     * table is right about its own titles and wrong about the titles quoted inside a summary,
     * which no per-string check can see, because both halves are perfectly good translations.
     */
    @Test public void aSummaryThatNamesAnotherRowNamesItByTheTitleThatRowCarries() throws Exception {
        List<String[]> rows = settingsRowArguments();
        Set<String> titles = new LinkedHashSet<>();
        for (String[] row : rows) {
            // Long enough that finding it inside a sentence means it was quoted on purpose.
            if (row[0] != null && row[0].length() >= 12) titles.add(row[0]);
        }
        assertTrue("the scan found too few row titles to mean anything: " + titles.size(),
                titles.size() > 40);

        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            for (String[] row : rows) {
                String summary = row[1];
                if (summary == null) continue;
                String translatedSummary = table.get(summary);
                if (translatedSummary == null) continue;
                for (String title : titles) {
                    if (title.equals(row[0]) || !summary.contains(title)) continue;
                    // "Hide the status bar" inside a quoted "Hide the status bar in LIVE rooms"
                    // names the longer row, not its own.
                    if (onlyInsideLongerTitle(summary, title, titles)) continue;
                    String translatedTitle = table.get(title);
                    if (translatedTitle == null) continue;
                    checked++;
                    if (!translatedSummary.contains(translatedTitle)) {
                        wrong.add(language + ": a summary sends the reader to \"" + translatedTitle
                                + "\" but says \"" + translatedSummary + "\"");
                    }
                }
            }
        }

        assertTrue("no summary quotes another row's title, so this checked nothing", checked > 0);
        assertEquals("summaries that name a switch by words no switch carries:\n"
                + String.join("\n", wrong), 0, wrong.size());
    }

    /** Whether every place {@code title} shows up in {@code summary} is part of a longer quoted title. */
    private static boolean onlyInsideLongerTitle(String summary, String title, Set<String> titles) {
        for (int at = summary.indexOf(title); at >= 0; at = summary.indexOf(title, at + 1)) {
            boolean covered = false;
            for (String longer : titles) {
                if (longer.length() <= title.length() || !longer.contains(title)) continue;
                for (int start = summary.indexOf(longer); start >= 0; start = summary.indexOf(longer, start + 1)) {
                    if (start <= at && at + title.length() <= start + longer.length()) {
                        covered = true;
                        break;
                    }
                }
                if (covered) break;
            }
            if (!covered) return false;
        }
        return true;
    }

    /**
     * One verb per outcome on the settings rows. Hide is a video or a control kept out of
     * view, remove is data taken out (ads, tracking parameters, the watermark), skip is the
     * feed moving past a video (a blocked sound, the budget). "Skipped", "drop" and "take
     * away" were used for the first of those in seven rows beside 137 that said hide.
     */
    @Test public void rowSummariesSayHideForWhatIsKeptOutOfView() throws Exception {
        List<String[]> rows = settingsRowArguments();
        List<String> offenders = new ArrayList<>();
        for (String[] row : rows) {
            for (String text : row) {
                if (text != null && STRAY_HIDE_VERB.matcher(text).find()) offenders.add(text);
            }
        }
        assertTrue("the scan found too few settings rows to mean anything: " + rows.size(),
                rows.size() > 100);
        assertEquals("rows that say drop, take away or skip where the rest say hide:\n"
                + String.join("\n", offenders), 0, offenders.size());
    }

    /**
     * The verbs the rows used for hiding before they said hide. "Skip" on its own stays: the
     * sound button skips the video playing and the warnings row skips a dialog, which is what
     * skip means. It is a video kept out of the feed that must not be "skipped".
     */
    private static final java.util.regex.Pattern STRAY_HIDE_VERB = java.util.regex.Pattern.compile(
            "\\b(?:[Dd]rop(?:s|ped|ping)?|[Tt]ake[sn]? away|skipped"
                    + "|[Ss]kip (?:feed |recorded )?(?:videos|LIVE|posts))\\b");

    /** The verb rule catches the seven rows it was written for and lets the two real skips by. */
    @Test public void theHideVerbRuleCanActuallyFail() {
        for (String old : new String[]{
                "These accounts are always skipped.",
                "Matching captions are skipped.",
                "Drop the notification saying somebody new followed you before it reaches the drawer.",
                "Take away the streak button in a chat and the reminder message that goes with it.",
                "Skip feed videos with place badges, even when they aren't paid ads.",
                "Skip recorded LIVE broadcasts in the feed.",
                "Skip videos marked as using promotional music.",
                "Skip videos that use a sound blocked with the player's sound button, or named below.",
                "Keep a local record of what you have watched and drop those videos from later feed pages."}) {
            assertTrue(old, STRAY_HIDE_VERB.matcher(old).find());
        }
        for (String kept : new String[]{
                "Block this sound",
                "Skip content warnings",
                "Add a separate button that skips videos using the current sound.",
                "Hide recorded LIVE broadcasts from the feed."}) {
            assertFalse(kept, STRAY_HIDE_VERB.matcher(kept).find());
        }
    }

    /** Every settings row the category sources build, as {@code [title, summary]}. */
    private static List<String[]> settingsRowArguments() throws Exception {
        java.io.File categories = new java.io.File(
                "src/main/java/app/morphe/extension/tiktok/settings/preference/categories");
        if (!categories.isDirectory()) categories = new java.io.File("extensions/tiktok/src/main/java"
                + "/app/morphe/extension/tiktok/settings/preference/categories");
        assertTrue("could not find the settings categories", categories.isDirectory());

        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "new\\s+(?:[\\w.]+\\.)?(?:TogglePreference|NumberInputPreference"
                        + "|ClockHourPreference|InputTextPreference|RangeValuePreference)\\s*\\(");
        List<String[]> found = new ArrayList<>();
        java.io.File[] sources = categories.listFiles((dir, name) -> name.endsWith(".java"));
        assertTrue("no category sources to read", sources != null && sources.length > 5);
        for (java.io.File file : sources) {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            byte[] kind = classify(text);
            java.util.regex.Matcher match = row.matcher(text);
            while (match.find()) {
                if (kind[match.start()] != CODE) continue;
                int close = closingBracket(text, kind, match.end() - 1);
                if (close < 0) continue;
                List<String> arguments = arguments(text, kind, match.end() - 1, close);
                found.add(new String[]{
                        arguments.size() > 0 ? arguments.get(0) : null,
                        arguments.size() > 1 ? arguments.get(1) : null});
            }
        }
        return found;
    }

    /**
     * One entry per argument of a call. Literals with nothing but a plus and whitespace between
     * them are one string in the source and one key in the table; a new argument starts a new
     * one. {@link #literalsIn} flattens both into a list, which cannot tell a summary written
     * across five lines from five separate words.
     */
    private static List<String> arguments(String text, byte[] kind, int from, int to) {
        List<String> found = new ArrayList<>();
        int previousEnd = -1;
        for (int at = from; at < to; at++) {
            if (kind[at] != LITERAL || text.charAt(at) != '"') continue;
            int end = at + 1;
            while (end < to && kind[end] == LITERAL) end++;
            int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
            String piece = unescape(text.substring(at + 1, contentEnd));

            String between = previousEnd < 0 ? null : text.substring(previousEnd, at);
            if (between != null && between.indexOf('+') >= 0
                    && between.replace("+", "").trim().isEmpty()) {
                found.set(found.size() - 1, found.get(found.size() - 1) + piece);
            } else {
                found.add(piece);
            }
            previousEnd = end;
            at = end - 1;
        }
        return found;
    }

    /**
     * The string literals inside one call, in order. A message too long for one line is written
     * as several literals with a plus between them and is one key; a choice between two messages
     * is written the same way and is two keys. The caller decides which reading fits.
     */
    private static List<String> literalsIn(String text, byte[] kind, int from, int to) {
        List<String> parts = new ArrayList<>();
        for (int at = from; at < to; at++) {
            if (kind[at] != LITERAL || text.charAt(at) != '"') continue;
            int end = at + 1;
            while (end < to && kind[end] == LITERAL) end++;
            // The run covers both quotes, so the closing one is not part of the text.
            int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
            // A literal being compared against is a value, not words on a screen:
            // "OBJECT".equals(entry.type) sits in an argument that does show text. Only the
            // comparisons, named one by one. Excluding every literal with a dot after it also
            // excused setText("A whole sentence".trim()), and a builder chain wrapped onto the
            // next line with it.
            if (!COMPARED.matcher(text).region(end, text.length()).lookingAt()) {
                parts.add(unescape(text.substring(at + 1, contentEnd)));
            }
            at = end - 1;
        }
        return parts;
    }

    /** What a literal can be asked, directly after its closing quote, without being words. */
    private static final java.util.regex.Pattern COMPARED = java.util.regex.Pattern.compile(
            "\\s*\\.\\s*(equals|equalsIgnoreCase|contentEquals|compareTo|compareToIgnoreCase)"
                    + "\\s*\\(");

    private static String unescape(String literal) {
        StringBuilder plain = new StringBuilder();
        for (int at = 0; at < literal.length(); at++) {
            char c = literal.charAt(at);
            if (c != '\\' || at + 1 >= literal.length()) {
                plain.append(c);
                continue;
            }
            char next = literal.charAt(++at);
            switch (next) {
                case 'n': plain.append('\n'); break;
                case 't': plain.append('\t'); break;
                default: plain.append(next); break;
            }
        }
        return plain.toString();
    }

    /**
     * Every string the extension's own source says, from both trees that reach the table. A
     * message written as several literals with a plus between them counts as its parts and as
     * the whole, the way the compiler joins it.
     *
     * <p>L10nTranslations*.java is skipped on purpose. It is generated from the tsv files and
     * repeats every key back as a literal, so including it left the orphan check unable to fail:
     * a key was "shown" because the generator had written it out again.
     */
    /**
     * Text a hand-built dialog puts on a view, which the checks above cannot see.
     *
     * <p>They reflect preference titles and summaries and they scan toasts and content
     * descriptions. A dialog built by hand calls setText, setHint or setTitle instead, and a
     * string handed to one of those went out in English however many tables it was in: two of
     * these already had German rows and simply bypassed L10n.
     *
     * <p>The rule is about the call rather than the table. Prose reaching one of these methods
     * has to arrive through L10n, so a later table that loses the row fails the check above
     * rather than shipping quietly.
     */
    @Test
    public void noDialogTextIsHandedStraightToAViewInEnglish() throws Exception {
        Map<java.nio.file.Path, String> sources = new LinkedHashMap<>();
        for (java.nio.file.Path file : tikTokSources()) {
            sources.put(file, new String(java.nio.file.Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        List<String> unwrapped = unwrappedProseIn(sources);
        assertTrue("no call to any of these was found, so this proves nothing", callsChecked > 20);
        assertEquals("text handed straight to a view in English, wrap it in L10n.t: "
                + unwrapped, 0, unwrapped.size());
    }

    /** How many showing calls the last scan looked inside. A scan of nothing proves nothing. */
    private static int callsChecked;

    /**
     * Every prose literal that reaches a view in these sources without going through L10n.
     *
     * <p>Separate from the test so a synthetic source can be put in front of the same code.
     * A scan whose only input is a tree that already passes says nothing about what it would
     * catch, and four shapes walked past the first version of it.
     */
    private static List<String> unwrappedProseIn(Map<java.nio.file.Path, String> sources) {
        Set<String> helpers = helpersThatPutTextOnTheScreen(sources);
        java.util.regex.Pattern call = java.util.regex.Pattern.compile(
                VIEW_TEXT_CALLS + helperAlternation(namesIn(helpers)));
        List<String> unwrapped = new ArrayList<>();
        callsChecked = 0;
        for (Map.Entry<java.nio.file.Path, String> source : sources.entrySet()) {
            java.nio.file.Path file = source.getKey();
            String text = source.getValue();
            byte[] kind = classify(text);
            byte[] outside = withoutL10nCalls(text, kind);
            java.util.regex.Matcher match = call.matcher(text);
            while (match.find()) {
                if (kind[match.start()] != CODE) continue;
                int open = match.end() - 1;
                int close = closingBracket(text, kind, open);
                if (close < 0) continue;
                callsChecked++;
                String helper = calledName(text, match.start(), open);
                List<int[]> arguments = argumentsOf(text, kind, open, close);
                for (int index = 0; index < arguments.size(); index++) {
                    // A derived helper shows one of its parameters and passes the rest along.
                    // Reading every literal in its brackets would flag the type name in
                    // addInfo(root, "TikTok's own value", "OBJECT".equals(entry.type) ? ...).
                    if (helper != null && !helpers.contains(helper + "#" + index)) continue;
                    for (String literal : literalsIn(text, outside,
                            arguments.get(index)[0], arguments.get(index)[1])) {
                        if (isProse(literal)) {
                            unwrapped.add(file.getFileName() + ": " + literal);
                        }
                    }
                }
            }
        }
        return unwrapped;
    }

    /**
     * The shapes this check has already been blind to, put in front of it on purpose.
     *
     * <p>Each of these went past it while the real tree was green, so a green run over the
     * real tree is not evidence that the analysis works. The sentence is the same in every
     * case and it must be reported every time.
     */
    @Test
    public void theViewTextCheckCanActuallyFail() {
        String sentence = "A whole sentence of English";
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("straight to setText",
                "class A { void a(android.widget.TextView v) { v.setText(\"" + sentence + "\"); } }");
        shapes.put("through a helper of its own",
                "class A {\n"
                        + "  void a() { show(\"" + sentence + "\"); }\n"
                        + "  void show(String caption) { SettingsUi.text(null, caption, 1, 2, 3); }\n"
                        + "}");
        shapes.put("a method that trims the literal first",
                "class A { void a(android.widget.TextView v) { v.setText(\"" + sentence
                        + "\".trim()); } }");
        shapes.put("a builder chain wrapped onto the next line",
                "class A { void a(android.widget.TextView v) { v.setText(\"" + sentence + "\"\n"
                        + "        .toUpperCase()); } }");
        shapes.put("a helper whose first parameter is a generic type",
                "class A {\n"
                        + "  void a() { counted(null, \"" + sentence + "\"); }\n"
                        + "  void counted(Map<String, Integer> counts, String caption) {\n"
                        + "    SettingsUi.text(null, caption, 1, 2, 3);\n"
                        + "  }\n"
                        + "}");
        shapes.put("a helper whose parameter carries an annotation",
                "class A {\n"
                        + "  void a() { noted(\"" + sentence + "\"); }\n"
                        + "  void noted(@SuppressWarnings(\"x\") String caption) {\n"
                        + "    SettingsUi.text(null, caption, 1, 2, 3);\n"
                        + "  }\n"
                        + "}");

        for (Map.Entry<String, String> shape : shapes.entrySet()) {
            Map<java.nio.file.Path, String> source = new LinkedHashMap<>();
            source.put(java.nio.file.Paths.get("A.java"), shape.getValue());
            List<String> found = unwrappedProseIn(source);
            assertEquals(shape.getKey() + " was not reported",
                    java.util.Collections.singletonList("A.java: " + sentence), found);
        }

        // And the one shape that must stay quiet, because it is a value being compared.
        Map<java.nio.file.Path, String> comparison = new LinkedHashMap<>();
        comparison.put(java.nio.file.Paths.get("B.java"),
                "class B { void a(android.widget.TextView v) {\n"
                        + "  v.setText(\"OBJECT\".equals(type) ? one : two);\n"
                        + "} }");
        assertEquals("a literal being compared against was read as words",
                java.util.Collections.emptyList(), unwrappedProseIn(comparison));
    }

    /**
     * The calls that are a view being handed words, before anything is derived from them.
     *
     * <p>The two named classes are the label builders this bundle draws its own screens with,
     * so a sentence written as {@code FeatureGateLabUi.body(context, "...")} is a sentence on
     * the screen exactly as {@code setText} is.
     */
    private static final String VIEW_TEXT_CALLS =
            "\\.\\s*(setText|setHint|setTitle|setMessage|setContentDescription|setPositiveButton"
                    + "|setNegativeButton|setNeutralButton)\\s*\\(|"
                    + "\\b(FeatureGateLabUi|SettingsUi)\\s*\\.\\s*"
                    + "(text|label|body|header|title|caption)\\s*\\(|"
                    // A menu item is a row a reader reads, and Menu.add takes its words as the
                    // last of four arguments rather than through anything setText-shaped. Six
                    // items in the Feature Gate Lab's overflow were English because of it.
                    + "(?:[Mm]enu\\w*|getMenu\\s*\\(\\s*\\))\\s*\\.\\s*add\\s*\\(";

    /** Never a helper, whatever the block behind the bracket does with a String. */
    private static final Set<String> NOT_A_HELPER = new LinkedHashSet<>(java.util.Arrays.asList(
            "if", "for", "while", "switch", "catch", "synchronized", "t", "f"));

    /**
     * Every method here that hands a String it was given straight to something that shows it,
     * as the method's name, a hash and the position of the parameter that gets shown.
     *
     * <p>The label builders above used to be the whole list, written out by hand, which stops
     * the rule one hop short of wherever the code actually is. FeatureGateLabUi.iconButton
     * hands its description to setContentDescription and FeatureGateDetailFragment.addInfo
     * hands its label to body(), so "Clear search" and twelve rows of the Feature Gate Lab's
     * technical details went out in English with this check reading straight past them. The
     * declarations are walked instead: a method whose String parameter reaches a call already
     * known to show text is one of those calls itself, and that repeats until a pass finds
     * nothing new.
     *
     * <p>Straight is the whole of it. A parameter that arrives at the view through L10n is
     * translated on the way, which is what every preference wrapper here does, so the L10n
     * calls are blanked out before the parameter is looked for. Without that the wrappers
     * themselves count as English and every row of every settings page is a finding.
     */
    private static Set<String> helpersThatPutTextOnTheScreen(
            Map<java.nio.file.Path, String> sources) {
        Map<java.nio.file.Path, byte[]> kinds = new LinkedHashMap<>();
        Map<java.nio.file.Path, byte[]> masked = new LinkedHashMap<>();
        Map<java.nio.file.Path, List<Method>> methods = new LinkedHashMap<>();
        for (Map.Entry<java.nio.file.Path, String> source : sources.entrySet()) {
            byte[] kind = classify(source.getValue());
            kinds.put(source.getKey(), kind);
            masked.put(source.getKey(), withoutL10nCalls(source.getValue(), kind));
            methods.put(source.getKey(), methodsIn(source.getValue(), kind));
        }

        Set<String> helpers = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            java.util.regex.Pattern shows = java.util.regex.Pattern.compile(
                    VIEW_TEXT_CALLS + helperAlternation(namesIn(helpers)));
            for (Map.Entry<java.nio.file.Path, String> source : sources.entrySet()) {
                String text = source.getValue();
                byte[] kind = kinds.get(source.getKey());
                byte[] outside = masked.get(source.getKey());
                for (Method method : methods.get(source.getKey())) {
                    for (int index : shownParametersOf(text, kind, outside, method, shows,
                            helpers)) {
                        if (helpers.add(method.name + "#" + index)) grew = true;
                    }
                }
            }
        }
        return helpers;
    }

    /** The bare method names of a set of name#index entries. */
    private static Set<String> namesIn(Set<String> helpers) {
        Set<String> names = new LinkedHashSet<>();
        for (String helper : helpers) names.add(helper.substring(0, helper.indexOf('#')));
        return names;
    }

    /** The alternation adding the derived helpers to the pattern, empty when there are none. */
    private static String helperAlternation(Set<String> names) {
        StringBuilder pattern = new StringBuilder();
        for (String name : names) {
            pattern.append("|\\b").append(java.util.regex.Pattern.quote(name)).append("\\s*\\(");
        }
        return pattern.toString();
    }

    /** Which of a method's parameters reach a showing call inside its body without going
     * through L10n, by position in the declaration. */
    private static Set<Integer> shownParametersOf(String text, byte[] kind, byte[] outside,
                                                  Method method, java.util.regex.Pattern shows,
                                                  Set<String> helpers) {
        Set<Integer> shown = new LinkedHashSet<>();
        java.util.regex.Matcher match = shows.matcher(text);
        int at = method.bodyStart;
        while (match.find(at) && match.start() < method.bodyEnd) {
            at = match.end();
            if (kind[match.start()] != CODE) continue;
            int open = match.end() - 1;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            String helper = calledName(text, match.start(), open);
            List<int[]> arguments = argumentsOf(text, kind, open, close);
            for (int index = 0; index < arguments.size(); index++) {
                if (helper != null && !helpers.contains(helper + "#" + index)) continue;
                for (int position : identifiersIn(text, outside,
                        arguments.get(index)[0], arguments.get(index)[1])) {
                    int declared = method.parameterAt(text, position);
                    if (declared >= 0) shown.add(declared);
                }
            }
        }
        return shown;
    }

    /** A method declaration: its name, the parameters it takes in order, and its body. */
    private static final class Method {
        final String name;
        final List<String> parameters;
        final Set<String> stringParameters;
        final int bodyStart;
        final int bodyEnd;

        Method(String name, List<String> parameters, Set<String> stringParameters,
               int bodyStart, int bodyEnd) {
            this.name = name;
            this.parameters = parameters;
            this.stringParameters = stringParameters;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }

        /** The position of the String parameter named at this offset, or -1 for anything else. */
        int parameterAt(String text, int position) {
            int end = position;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
            String word = text.substring(position, end);
            return stringParameters.contains(word) ? parameters.indexOf(word) : -1;
        }
    }

    /** The name of the method a matched call belongs to, or null when it is one of the seeds. */
    private static String calledName(String text, int start, int open) {
        String head = text.substring(start, open).trim();
        if (head.startsWith(".") || head.contains(".")) return null;
        return head.matches("\\w+") ? head : null;
    }

    /** Each argument of a call, as the half-open range it occupies. */
    private static List<int[]> argumentsOf(String text, byte[] kind, int open, int close) {
        List<int[]> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int at = open + 1; at < close; at++) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                arguments.add(new int[]{start, at});
                start = at + 1;
            }
        }
        arguments.add(new int[]{start, close});
        return arguments;
    }

    /** Where each bare identifier starts in a range, skipping anything L10n already holds. */
    private static List<Integer> identifiersIn(String text, byte[] outside, int from, int to) {
        List<Integer> starts = new ArrayList<>();
        int at = from;
        while (at < to) {
            if (outside[at] != CODE || !Character.isJavaIdentifierStart(text.charAt(at))) {
                at++;
                continue;
            }
            if (at > 0 && (text.charAt(at - 1) == '.'
                    || Character.isJavaIdentifierPart(text.charAt(at - 1)))) {
                at++;
                continue;
            }
            starts.add(at);
            while (at < to && Character.isJavaIdentifierPart(text.charAt(at))) at++;
        }
        return starts;
    }

    // A name and an opening bracket. Where that bracket ends is counted rather than matched,
    // because a parameter list can hold brackets of its own: an annotation with an argument,
    // @SuppressWarnings("x") String caption, made a pattern of "no bracket inside" walk past
    // the declaration entirely, and a helper it never saw is a helper it never derives.
    // A lambda's parameters carry no type, so a lambda has no String parameter by this reading.
    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile(
            "(\\w+)\\s*\\(");

    /** Every method in a file that takes a String or a CharSequence. */
    private static List<Method> methodsIn(String text, byte[] kind) {
        List<Method> methods = new ArrayList<>();
        java.util.regex.Matcher match = DECLARATION.matcher(text);
        while (match.find()) {
            if (kind[match.start()] != CODE) continue;
            String name = match.group(1);
            if (NOT_A_HELPER.contains(name)) continue;
            int open = match.end() - 1;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            int brace = openingBraceAfter(text, kind, close);
            if (brace < 0) continue;
            List<int[]> parts = parameterPartsOf(text, kind, open, close);
            List<String> parameters = parametersOf(text, parts);
            Set<String> strings = stringParametersOf(text, parts);
            // A generic type carries a comma of its own, so Map<String, Integer> counts read
            // as two parameters and every parameter after it was recorded one place too far
            // to the right. The call side is then checked at the wrong argument.
            if (strings.isEmpty()) continue;
            int end = closingBrace(text, kind, brace);
            if (end < 0) continue;
            methods.add(new Method(name, parameters, strings, brace, end));
        }
        return methods;
    }

    /**
     * The brace that opens a method body, when the only thing between it and the bracket is a
     * throws clause. Anything else, a call or a control statement, has no body here.
     */
    private static int openingBraceAfter(String text, byte[] kind, int close) {
        StringBuilder between = new StringBuilder();
        for (int at = close + 1; at < text.length(); at++) {
            if (kind[at] != CODE) return -1;
            char c = text.charAt(at);
            if (c == '{') {
                return between.toString().trim().matches("(?:throws\\s+[\\w.,\\s]+)?") ? at : -1;
            }
            between.append(c);
            if (between.length() > 200) return -1;
        }
        return -1;
    }

    /**
     * A declaration's parameters, as the ranges they occupy.
     *
     * <p>Like {@link #argumentsOf} but counting angle brackets too, which a declaration has and
     * a call site does not: at a call site a {@code <} is a comparison and counting it would
     * run the depth away.
     */
    private static List<int[]> parameterPartsOf(String text, byte[] kind, int open, int close) {
        List<int[]> parts = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int at = open + 1; at < close; at++) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(new int[]{start, at});
                start = at + 1;
            }
        }
        parts.add(new int[]{start, close});
        return parts;
    }

    /** Every parameter name in a declaration's bracket, in order. */
    private static List<String> parametersOf(String text, List<int[]> parts) {
        List<String> names = new ArrayList<>();
        for (int[] part : parts) {
            String[] words = text.substring(part[0], part[1]).trim().split("\\s+");
            names.add(words.length < 2 ? "" : words[words.length - 1]);
        }
        return names;
    }

    /** The names of the String and CharSequence parameters inside a declaration's bracket. */
    private static Set<String> stringParametersOf(String text, List<int[]> parts) {
        Set<String> names = new LinkedHashSet<>();
        for (int[] part : parts) {
            String[] words = text.substring(part[0], part[1]).trim().split("\\s+");
            if (words.length < 2) continue;
            String type = words[words.length - 2];
            String name = words[words.length - 1];
            if (!name.matches("\\w+")) continue;
            if (type.equals("String") || type.equals("CharSequence")
                    || type.equals("String...") || type.equals("CharSequence...")) {
                names.add(name);
            }
        }
        return names;
    }

    /** The brace closing the one at this index, counting only the braces that are code. */
    private static int closingBrace(String text, byte[] kind, int open) {
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            if (kind[at] != CODE) continue;
            if (text.charAt(at) == '{') depth++;
            else if (text.charAt(at) == '}' && --depth == 0) return at;
        }
        return -1;
    }

    /** Every .java file under the TikTok extension. */
    private static List<java.nio.file.Path> tikTokSources() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree", root.isDirectory());
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(root.toPath())) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * The same classification with every L10n call blanked out.
     *
     * <p>A literal inside L10n.t is the key of a translated string, which is the thing this
     * check wants to see. Only what is left over is text going to a view as it stands.
     */
    private static byte[] withoutL10nCalls(String text, byte[] kind) {
        byte[] outside = kind.clone();
        java.util.regex.Pattern l10n = java.util.regex.Pattern.compile("L10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(");
        java.util.regex.Matcher match = l10n.matcher(text);
        while (match.find()) {
            if (kind[match.start()] != CODE) continue;
            int close = closingBracket(text, kind, match.end() - 1);
            if (close < 0) continue;
            for (int index = match.start(); index <= close && index < outside.length; index++) {
                outside[index] = COMMENT;
            }
        }
        return outside;
    }

    /**
     * Whether a literal is something a reader reads rather than a value.
     *
     * <p>A resource name, a package name, a format fragment and a path are all strings that
     * mean the same in every language. Prose has a space in it, or is a capitalised word long
     * enough not to be an abbreviation.
     */
    private static boolean isProse(String literal) {
        String trimmed = literal.trim();
        if (trimmed.length() < 4) return false;
        if (trimmed.startsWith("%") || trimmed.contains("://")) return false;
        // A path, a package name or a resource id: no spaces and a separator inside it.
        if (!trimmed.contains(" ")
                && (trimmed.contains(".") || trimmed.contains("/") || trimmed.contains("_"))) {
            return false;
        }
        return trimmed.contains(" ") || Character.isUpperCase(trimmed.charAt(0));
    }

    private static Set<String> runtimeStringsInSource() throws Exception {
        Set<String> literals = new LinkedHashSet<>();
        // Gradle runs the tests with the module directory as the working directory, so the paths
        // are tried from the repository root and from inside extensions/tiktok. Skipping a root
        // that resolves from neither would quietly shrink what counts as shown.
        for (String relative : new String[]{
                "extensions/tiktok/src/main/java",
                "extensions/shared/library/src/main/java"}) {
            java.io.File root = new java.io.File(relative);
            if (!root.isDirectory()) root = new java.io.File("../../" + relative);
            if (!root.isDirectory()) root = new java.io.File(relative.replaceFirst("^extensions/tiktok/", ""));
            assertTrue("could not find " + relative + " from "
                    + new java.io.File(".").getAbsolutePath(), root.isDirectory());
            try (java.util.stream.Stream<java.nio.file.Path> files =
                         java.nio.file.Files.walk(root.toPath())) {
                for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                        .collect(java.util.stream.Collectors.toList())) {
                    if (file.getFileName().toString().startsWith("L10nTranslations")) continue;
                    collectStrings(new String(java.nio.file.Files.readAllBytes(file),
                            java.nio.charset.StandardCharsets.UTF_8), literals);
                }
            }
        }
        assertTrue("the scan found no source to read", literals.size() > 100);
        return literals;
    }

    /** Adds each literal, and for a run joined by plus signs the joined message as well. */
    private static void collectStrings(String text, Set<String> into) {
        byte[] kind = classify(text);
        int at = 0;
        while (at < text.length()) {
            if (kind[at] != LITERAL || text.charAt(at) != '"') {
                at++;
                continue;
            }
            List<String> parts = new ArrayList<>();
            while (at < text.length() && kind[at] == LITERAL && text.charAt(at) == '"') {
                int end = at + 1;
                while (end < text.length() && kind[end] == LITERAL) end++;
                int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
                parts.add(unescape(text.substring(at + 1, contentEnd)));
                into.add(parts.get(parts.size() - 1));

                int probe = end;
                while (probe < text.length() && kind[probe] == CODE
                        && Character.isWhitespace(text.charAt(probe))) probe++;
                if (probe < text.length() && kind[probe] == CODE && text.charAt(probe) == '+') {
                    probe++;
                    while (probe < text.length() && kind[probe] == CODE
                            && Character.isWhitespace(text.charAt(probe))) probe++;
                    at = probe;
                    continue;
                }
                at = end;
                break;
            }
            if (parts.size() > 1) into.add(String.join("", parts));
        }
    }

    @Test
    @Config(sdk = 28, qualifiers = "de")
    public void nothingOnTheScreensStaysEnglishUnderTheGermanLocale() throws Exception {
        assertNothingStaysEnglish("de", GERMAN, "Beschreibung ausblenden");
    }

    @Test
    @Config(sdk = 28, qualifiers = "in-rID")
    public void nothingOnTheScreensStaysEnglishUnderTheIndonesianLocale() throws Exception {
        assertNothingStaysEnglish("in-rID", INDONESIAN, "Sembunyikan keterangan");
    }

    private void assertNothingStaysEnglish(String qualifier, Map<String, String> table,
                                           String expected) throws Exception {
        // Words the language spells the same way, like Transparent in German, cannot tell
        // the two apart, so only the entries that actually change are evidence.
        Set<String> english = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : table.entrySet()) {
            if (!entry.getValue().equals(entry.getKey())) {
                english.add(entry.getKey());
            }
        }
        Set<String> shown = collectEverything();
        List<String> untranslated = new ArrayList<>();
        for (String text : shown) {
            if (english.contains(text)) {
                untranslated.add(text);
            }
        }
        assertEquals("still English under " + qualifier + ": " + untranslated, 0, untranslated.size());
        assertTrue(shown.contains(expected));
    }

    private Set<String> collectEverything() throws Exception {
        setEveryStatus(true);
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        Set<String> strings = new LinkedHashSet<>();
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new FeedFilterPreferenceCategory(activity, screen);
            new FeedNavigationPreferenceCategory(activity, screen);
            new InterfacePreferenceCategory(activity, screen);
            new CommentsPreferenceCategory(activity, screen);
            new DownloadsPreferenceCategory(activity, screen);
            new PlaybackPreferenceCategory(activity, screen);
            new ScreenTimePreferenceCategory(activity, screen);
            // "Start today over" says something different once it has been tapped, and that
            // sentence shipped in English on every translated phone because nothing here had
            // ever tapped it. The page is built a second time with the undo armed, and the tap
            // is taken back afterwards: the undo flag is process wide and this method runs once
            // per language, so leaving it armed meant the second language only ever saw the
            // tapped wording and stopped checking the other one.
            app.morphe.extension.tiktok.wellbeing.SessionBudget.clear();
            PreferenceScreen afterStartingOver =
                    activity.getPreferenceManager().createPreferenceScreen(activity);
            new ScreenTimePreferenceCategory(activity, afterStartingOver);
            collect(afterStartingOver, strings);
            app.morphe.extension.tiktok.wellbeing.SessionBudget.undoClear();
            assertFalse("the tap on Start today over was not taken back, so the next language"
                            + " will not see the untapped wording",
                    app.morphe.extension.tiktok.wellbeing.SessionBudget.canUndoClear());
            new InboxPreferenceCategory(activity, screen);
            new SharePreferenceCategory(activity, screen);
            new SimSpoofPreferenceCategory(activity, screen);
            new DebugPreferenceCategory(activity, screen);
            new ExtensionPreferenceCategory(activity, screen);
            // This About action is unconditional and doesn't belong to a patch-gated category.
            screen.addPreference(new app.morphe.extension.tiktok.settings.preference.BuildDetailsPreference(activity));
            collect(screen, strings);
            // Navigation labels are built outside the leaf categories.
            for (String section : new String[]{"Feed filter", "Feed tabs", "Feed screen",
                    "Playback", "Screen time", "Comments", "Downloads", "Share sheet", "Inbox",
                    "Privacy", "Region", "App", "Diagnostics", "Backup and restore", "Settings",
                    "Choose what reaches your feed", "Arrange your feed and bottom tabs",
                    "Captions, gestures and on-screen controls", "Quality, speed and auto-advance",
                    "Daily budgets, reminders and the hold", "Filters, translation and copy options",
                    "Quality, files, subtitles and hand-off", "People, shortcuts and sending controls",
                    "Choose which rows and controls appear", "Tracking, device access and links",
                    "Country and network preferences", "Layout, search, profile and system",
                    "Logging, hook status and reports", "Save, restore, reset and undo your settings"}) {
                strings.add(L10n.t(activity, section));
            }
            for (String text : QUIET_INDEX_COPY) strings.add(L10n.t(activity, text));
            strings.add(L10n.t(activity, "Back up settings"));
            strings.add(L10n.t(activity, "Restore settings"));
            strings.add(L10n.t(activity, "Reset settings"));
            // The wording the backup row actually uses. This list said "Undo last restore" long
            // after the row started saying "or reset" too, which kept a dead tsv row alive.
            strings.add(L10n.t(activity, "Undo last restore or reset"));
            strings.add(L10n.t(activity, "Copy build details"));
            strings.add(L10n.t(activity, "Save build details"));
            strings.add(L10n.t(activity, "Build details copied to the clipboard"));
        }
        return strings;
    }

    private static void collect(Preference preference, Set<String> into) {
        add(preference.getTitle(), into);
        add(preference.getSummary(), into);
        if (preference instanceof ListPreference) {
            ListPreference list = (ListPreference) preference;
            add(list.getDialogTitle(), into);
            if (list.getEntries() != null) {
                for (CharSequence entry : list.getEntries()) {
                    add(entry, into);
                }
            }
        }
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                collect(group.getPreference(i), into);
            }
        }
    }

    private static void add(CharSequence text, Set<String> into) {
        if (text != null && text.length() > 0 && !"%s".contentEquals(text)) {
            into.add(text.toString());
        }
    }

    private static void setEveryStatus(boolean value) throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.setBoolean(null, value);
            }
        }
    }
}
