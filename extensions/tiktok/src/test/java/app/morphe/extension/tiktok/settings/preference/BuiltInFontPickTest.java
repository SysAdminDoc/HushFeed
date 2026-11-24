/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.font.BuiltInFonts;
import app.morphe.extension.tiktok.font.ImportedFont;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * Pick a built-in font (#487): the row sits under Import a font file only where the patch put
 * fonts in TikTok's assets, the list names them A to Z without the licence file, each name is
 * drawn in its own font, and picking one makes it the imported font, kept under its name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BuiltInFontPickTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final byte[] TRUETYPE = {0, 1, 0, 0, 0, 12, 0, (byte) 0x80};

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    /** Stand-in assets: the files listed, each opening as a tiny TrueType file. */
    private static final class Folder implements BuiltInFonts.Source {
        final String[] files;
        final Map<String, Typeface> faces = new HashMap<>();
        final List<String> opened = new ArrayList<>();

        Folder(String... files) {
            this.files = files;
        }

        @Override public String[] list(Context context) {
            return files;
        }

        @Override public InputStream open(Context context, String file) throws IOException {
            if (!Arrays.asList(files).contains(file)) throw new IOException("no " + file);
            opened.add(file);
            return new ByteArrayInputStream(TRUETYPE);
        }

        @Override public Typeface face(Context context, String file) {
            Typeface face = Typeface.create(file, Typeface.NORMAL);
            faces.put(file, face);
            return face;
        }
    }

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        HookStatus.clear();
        ImportedFont.remove(context);
    }

    @After public void tearDown() {
        BuiltInFonts.resetForTests();
        Utils.setContext(RuntimeEnvironment.getApplication());
        ImportedFont.remove(RuntimeEnvironment.getApplication());
        Settings.IMPORTED_FONT.resetToDefault();
        SettingsStatus.systemFontEnabled = false;
        HookStatus.clear();
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }

    @Test public void theNamesRunAToZWithoutTheLicences() {
        BuiltInFonts.useForTests(new Folder("VT323.ttf", "LICENSES.txt", "Abril Fatface.ttf", ".ttf",
                "Press Start 2P.ttf", "notes.md"));
        assertEquals(Arrays.asList("Abril Fatface", "Press Start 2P", "VT323"), BuiltInFonts.names(context));
        assertEquals("Press Start 2P.ttf", BuiltInFonts.fileName("Press Start 2P"));
    }

    @Test public void noAssetsMeansNoRow() throws IOException {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.systemFontEnabled = true;

            assertTrue("a test app has no such assets", BuiltInFonts.names(activity).isEmpty());
            assertNull(build(activity).findPreference(ImportedFontPreference.BUILT_IN_KEY));
            BuiltInFonts.useForTests(new Folder("LICENSES.txt"));
            assertNull("the licences alone are no fonts", build(activity).findPreference(ImportedFontPreference.BUILT_IN_KEY));
            BuiltInFonts.useForTests(new BuiltInFonts.Source() {
                @Override public String[] list(Context c) throws IOException {
                    throw new IOException("unreadable");
                }

                @Override public InputStream open(Context c, String file) throws IOException {
                    throw new IOException("unreadable");
                }

                @Override public Typeface face(Context c, String file) {
                    return null;
                }
            });
            assertNull(build(activity).findPreference(ImportedFontPreference.BUILT_IN_KEY));

            BuiltInFonts.useForTests(new Folder("Lobster.ttf", "Bangers.ttf", "LICENSES.txt"));
            PreferenceScreen screen = build(activity);
            Preference row = screen.findPreference(ImportedFontPreference.BUILT_IN_KEY);
            assertNotNull(row);
            assertEquals("Pick a built-in font", String.valueOf(row.getTitle()));
            assertEquals("Pick one of 2 fonts that come with Hushfeed. Each is shown in its own style.",
                    String.valueOf(row.getSummary()));
            int pick = screen.findPreference("action_import_font").getOrder();
            int remove = screen.findPreference("action_remove_imported_font").getOrder();
            assertTrue("between Import a font file and Remove", pick < row.getOrder() && row.getOrder() < remove);
        }
    }

    @Test public void eachNameShowsInItsFontAndAPickBecomesTheImportedFont() throws Exception {
        Folder folder = new Folder("Lobster.ttf", "Press Start 2P.ttf", "Bangers.ttf", "LICENSES.txt");
        BuiltInFonts.useForTests(folder);
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.systemFontEnabled = true;

            ImportedFontPreference.showBuiltInFonts(activity);
            idle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            ListView list = dialog.getListView();
            ListAdapter rows = list.getAdapter();
            assertTrue("the dialog's own row styling", rows instanceof DialogChoices);
            assertEquals(3, rows.getCount());
            List<String> names = new ArrayList<>();
            for (int i = 0; i < rows.getCount(); i++) {
                View row = rows.getView(i, null, list);
                assertTrue(row instanceof TextView);
                TextView text = (TextView) row;
                names.add(String.valueOf(text.getText()));
                assertSame("the row keeps the font's face over the dialog's",
                        folder.faces.get(text.getText() + ".ttf"), text.getTypeface());
            }
            assertEquals(Arrays.asList("Bangers", "Lobster", "Press Start 2P"), names);

            Shadows.shadowOf(dialog).clickOnItem(2);
            idle();
            Utils.awaitBackgroundTasksForTests();
            idle();
            assertFalse(dialog.isShowing());
            assertEquals(List.of("Press Start 2P.ttf"), folder.opened);
            assertEquals("kept under its own name", "Press Start 2P.ttf", ImportedFont.name(context));
            assertFalse("the switch stays as the reader left it", Settings.IMPORTED_FONT.get());

            PreferenceScreen screen = build(activity);
            assertEquals("Imported: Press Start 2P.ttf. Pick another file to replace it.",
                    String.valueOf(screen.findPreference("action_import_font").getSummary()));
            assertTrue("Remove takes a built-in font off too",
                    screen.findPreference("action_remove_imported_font").isEnabled());
        }
    }

    @Test public void aRowWithNoFaceTagKeepsTheDialogsFont() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            TextView plain = new TextView(activity);
            ListAdapter source = new android.widget.BaseAdapter() {
                @Override public int getCount() { return 1; }
                @Override public Object getItem(int position) { return "Plain"; }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                    return plain;
                }
            };
            new DialogChoices(source, ScreenColors.current(), false).getView(0, null, new ListView(activity));
            assertEquals("sans-serif-medium",
                    Shadows.shadowOf(plain.getTypeface()).getFontDescription().getFamilyName());
        }
    }
}
