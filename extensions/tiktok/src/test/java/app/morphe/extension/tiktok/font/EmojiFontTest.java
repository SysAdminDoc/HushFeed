/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #458: the emoji font goes behind whichever text font is in use, and its file is kept apart from
 * the text font's. Android's fallback builder needs real font files, so these stand in for it and
 * hold what it's given. That the chain draws emoji is checked on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class EmojiFontTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final byte[] TRUETYPE = {0, 1, 0, 0, 0, 12, 0, (byte) 0x80};

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private static final class Call {
        final Typeface text;
        final File importedText;
        final File emoji;

        Call(Typeface text, File importedText, File emoji) {
            this.text = text;
            this.importedText = importedText;
            this.emoji = emoji;
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private final Typeface stacked = Typeface.create("stacked", Typeface.NORMAL);
    private final Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ImportedFont.remove(context);
        ImportedFont.EMOJI.clear(context);
        EmojiFont.resetForTests();
        EmojiFont.stacker = (text, importedText, emoji) -> {
            calls.add(new Call(text, importedText, emoji));
            return stacked;
        };
    }

    @After public void tearDown() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        ImportedFont.remove(context);
        ImportedFont.EMOJI.clear(context);
        EmojiFont.resetForTests();
        Settings.IMPORTED_EMOJI_FONT.resetToDefault();
        Settings.IMPORTED_FONT.resetToDefault();
        Settings.SYSTEM_FONT.resetToDefault();
        Settings.SYSTEM_EMOJI.resetToDefault();
        SettingsStatus.systemFontEnabled = false;
    }

    private void importEmoji() throws IOException {
        ImportedFont.EMOJI.take(context, new ByteArrayInputStream(TRUETYPE), "Emoji.ttf").install();
    }

    @Test public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals("imported_emoji_font", Settings.IMPORTED_EMOJI_FONT.key);
        assertEquals(Boolean.FALSE, Settings.IMPORTED_EMOJI_FONT.defaultValue);
        assertTrue("the engine built TikTok's faces already", Settings.IMPORTED_EMOJI_FONT.rebootApp);
    }

    @Test public void withTheSwitchOffOrNoFileTheFacesStayAsTheyWere() throws IOException {
        importEmoji();
        assertSame("switch off", tiktok, SystemFont.systemize(tiktok));
        assertFalse(SystemEmoji.leaveToDevice());

        ImportedFont.EMOJI.clear(context);
        Settings.IMPORTED_EMOJI_FONT.save(true);
        assertSame("no file", tiktok, SystemFont.systemize(tiktok));
        assertFalse(SystemEmoji.leaveToDevice());
        assertTrue("nothing to build from", calls.isEmpty());
    }

    @Test public void tikTokKeepsItsTextFontWithTheEmojiFontBehindIt() throws IOException {
        importEmoji();
        Settings.IMPORTED_EMOJI_FONT.save(true);

        assertSame(stacked, SystemFont.systemize(tiktok));
        assertSame("the engine asks again for the same face", stacked, SystemFont.systemize(tiktok));
        assertEquals("one chain per face", 1, calls.size());
        assertSame(tiktok, calls.get(0).text);
        assertNull("TikTok's font isn't an imported file", calls.get(0).importedText);
        assertEquals("Emoji.ttf", calls.get(0).emoji.getName());
        assertTrue("EmojiCompat would draw over it", SystemEmoji.leaveToDevice());
    }

    @Test public void aBundleWithoutTikToksFontsStillGetsTheEmojiFont() throws IOException {
        importEmoji();
        Settings.IMPORTED_EMOJI_FONT.save(true);
        SystemFont.requested(700, 0);
        assertSame("the engine answers null on the split bundle (#346)", stacked, SystemFont.systemize(null));
        assertEquals(1, calls.size());
        assertNotNull(calls.get(0).text);
        assertNull(calls.get(0).importedText);
    }

    @Test public void anImportedTextFontGoesFirstFromItsOwnFile() throws IOException {
        importEmoji();
        ImportedFont.receive(context, new ByteArrayInputStream(TRUETYPE), "Inter.ttf").install();
        Settings.IMPORTED_EMOJI_FONT.save(true);
        Settings.IMPORTED_FONT.save(true);

        assertSame(stacked, SystemFont.systemize(tiktok));
        assertEquals("Inter.ttf", calls.get(0).importedText.getName());
        assertEquals("Emoji.ttf", calls.get(0).emoji.getName());
    }

    @Test public void theSystemFontIsStackedLikeTikToks() throws IOException {
        importEmoji();
        Settings.IMPORTED_EMOJI_FONT.save(true);
        Settings.SYSTEM_FONT.save(true);
        assertSame(stacked, SystemFont.systemize(tiktok));
        assertNull(calls.get(0).importedText);
    }

    @Test public void aChainThatCantBeBuiltLeavesTheTextAloneForTheRun() throws IOException {
        importEmoji();
        Settings.IMPORTED_EMOJI_FONT.save(true);
        EmojiFont.stacker = (text, importedText, emoji) -> {
            calls.add(new Call(text, importedText, emoji));
            throw new IOException("not a font after all");
        };
        assertSame(tiktok, SystemFont.systemize(tiktok));
        assertSame(tiktok, SystemFont.systemize(tiktok));
        assertEquals("tried once", 1, calls.size());
        assertFalse("TikTok's own emoji handling comes back", SystemEmoji.leaveToDevice());
        String section = String.join("\n", SystemFont.Report.INSTANCE.lines());
        assertTrue(section, section.contains("Imported emoji font: Emoji.ttf"));
        assertTrue(section, section.contains("couldn't be used"));
    }

    @Test public void theTwoFontsAreKeptApart() throws IOException {
        importEmoji();
        ImportedFont.receive(context, new ByteArrayInputStream(TRUETYPE), "Inter.ttf").install();
        assertEquals("Inter.ttf", ImportedFont.name(context));
        assertEquals("Emoji.ttf", ImportedFont.EMOJI.fileName(context));

        ImportedFont.remove(context);
        assertNull(ImportedFont.name(context));
        assertEquals("removing the text font took the emoji font", "Emoji.ttf", ImportedFont.EMOJI.fileName(context));
    }

    @Test public void anEmojiFontMayBeFarLargerThanATextFont() throws IOException {
        long size = ImportedFont.MAX_BYTES + 1;
        ImportedFont.Incoming incoming = ImportedFont.EMOJI.take(context, zeroesAfterSignature(size), "Big.ttf");
        assertEquals("Big.ttf", incoming.install());
        try {
            ImportedFont.receive(context, zeroesAfterSignature(size), "Big.ttf");
            throw new AssertionError("the text font's limit moved");
        } catch (ImportedFont.Rejected refused) {
            assertEquals(ImportedFont.Reason.TOO_LARGE, refused.reason);
        }
        assertTrue(ImportedFont.EMOJI_MAX_BYTES > ImportedFont.MAX_BYTES);
    }

    private static InputStream zeroesAfterSignature(long size) {
        return new InputStream() {
            private long at;

            @Override public int read() {
                if (at >= size) return -1;
                return at < TRUETYPE.length ? TRUETYPE[(int) at++] & 0xff : zero();
            }

            private int zero() {
                at++;
                return 0;
            }
        };
    }

    @Test public void theRowsComeWithThePatch() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            assertNull(build(activity).findPreference(Settings.IMPORTED_EMOJI_FONT.key));
            SettingsStatus.systemFontEnabled = true;
            PreferenceScreen screen = build(activity);
            assertNotNull(screen.findPreference(Settings.IMPORTED_EMOJI_FONT.key));
            assertNotNull(screen.findPreference("action_import_emoji_font"));
            assertFalse("nothing to remove yet", screen.findPreference("action_remove_imported_emoji_font").isEnabled());
        }
    }

    @Config(sdk = 30)
    @Test public void olderPhonesDontGetASwitchThatCantWork() throws IOException {
        importEmoji();
        Settings.IMPORTED_EMOJI_FONT.save(true);
        assertFalse(EmojiFont.active());
        assertSame(tiktok, SystemFont.systemize(tiktok));
        assertTrue(calls.isEmpty());
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.systemFontEnabled = true;
            PreferenceScreen screen = build(activity);
            assertNotNull("the text font rows stay", screen.findPreference("action_import_font"));
            assertNull(screen.findPreference(Settings.IMPORTED_EMOJI_FONT.key));
            assertNull(screen.findPreference("action_import_emoji_font"));
        }
    }

    private static PreferenceScreen build(TestActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}
