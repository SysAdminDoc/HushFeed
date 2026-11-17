/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Hushfeed language shows the settings and Hushfeed's messages in a language picked there,
 * whatever TikTok and the phone are set to (#431). The reporter asked for Chinese, which only
 * showed on a phone or TikTok set to Chinese. Match TikTok keeps the old way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@SuppressWarnings("deprecation")
public class HushfeedLanguageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class PageActivity extends Activity {
        int recreated;

        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }

        @Override public void recreate() {
            recreated++;
        }
    }

    private static StringSetting language() throws Exception {
        return (StringSetting) Settings.class.getField("HUSHFEED_LANGUAGE").get(null);
    }

    private static String[] pick(String name) throws Exception {
        return (String[]) L10n.class.getField(name).get(null);
    }

    @After public void reset() throws Exception {
        language().resetToDefault();
    }

    @Test public void matchTikTokIsTheDefaultAndFollowsTikToksLanguage() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("tiktok", language().defaultValue);
        assertEquals("Settings theme", L10n.t(context, "Settings theme"));
        assertNull(L10n.tableTag(context));
    }

    @Test public void chineseShowsOnAnEnglishPhone() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        language().save("zh-hans");
        assertEquals("设置主题", L10n.t(context, "Settings theme"));
        assertEquals("zh-hans", L10n.tableTag(context));

        language().save("tiktok");
        assertEquals("Settings theme", L10n.t(context, "Settings theme"));
    }

    @Test @Config(qualifiers = "de")
    public void englishWinsOverAGermanPhoneAndAnUnknownPickFollowsTikTok() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("Design der Einstellungen", L10n.t(context, "Settings theme"));
        language().save("en");
        assertEquals("Settings theme", L10n.t(context, "Settings theme"));
        assertNull(L10n.tableTag(context));
        language().save("klingon");
        assertEquals("Design der Einstellungen", L10n.t(context, "Settings theme"));
    }

    @Test public void everyTableCanBePickedAndIsListedInItsOwnName() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String[] values = pick("PICK_VALUES");
        String[] labels = pick("PICK_LABELS");
        assertEquals(values.length, labels.length);
        assertEquals("tiktok", values[0]);
        assertEquals("en", values[1]);

        Field tables = Class.forName("app.morphe.extension.tiktok.settings.L10nTranslations")
                .getDeclaredField("LANGUAGES");
        tables.setAccessible(true);
        List<String> offered = new ArrayList<>(Arrays.asList(values).subList(2, values.length));
        assertEquals("a language with a table isn't offered", Arrays.asList((String[]) tables.get(null)), offered);

        for (int index = 2; index < values.length; index++) {
            language().save(values[index]);
            assertEquals(values[index], L10n.tableTag(context));
            // The name stays the same in every language, so it reads the same under its own pick.
            assertEquals(labels[index], L10n.t(context, labels[index]));
        }
        language().save("de");
        assertEquals("Español", L10n.t(context, "Español"));
        assertEquals("Hushfeed-Sprache", L10n.t(context, "Hushfeed language"));
    }

    @Test public void theHomePageOffersTheChoiceAndRebuildsOnAPick() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            PageActivity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            Preference row = home.findPreference("hushfeed_language");
            assertNotNull("the home page has no Hushfeed language row", row);
            ChoicePreference choice = (ChoicePreference) row;
            assertEquals("Hushfeed language", String.valueOf(choice.getTitle()));
            assertArrayEquals(pick("PICK_VALUES"), choice.getEntryValues());
            assertArrayEquals(pick("PICK_LABELS"), choice.getEntries());
            assertNotNull("the row has no icon beside Settings theme's", choice.getIcon());

            assertTrue(choice.getOnPreferenceChangeListener().onPreferenceChange(choice, "zh-hans"));
            assertEquals("zh-hans", language().get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the pick didn't rebuild the page in the new language", 1, activity.recreated);
        }
    }
}
