package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import app.morphe.extension.shared.BuildNames;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PineDrama runs Hushfeed's settings too, and every restart row, the saved toast and the Feature
 * Gate Lab's messages told its reader to restart TikTok (#156). The restart phrase names the app
 * that's running; TikTok's servers, TikTok Sans and the other mentions stay TikTok's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PineDramaRestartTextTest {
    private static final String TIKTOK = "com.zhiliaoapp.musically";
    private static final Pattern ENGLISH_RESTART = Pattern.compile("[Rr]estart(?:ing)? TikTok\\b");
    private static final String[] TABLES = {"az", "de", "es", "in", "it", "pt-rbr", "ru", "tr", "zh-hans"};

    @After
    public void tearDown() {
        BuildNames.setRunningBuildForTests(null);
    }

    /**
     * Every restart key in every table: each restart phrase the English has turns into PineDrama,
     * and no other TikTok does. A translator who words a restart line a new way fails here
     * rather than leaving PineDrama's reader with a stray TikTok.
     */
    @Test
    public void everyRestartPhraseNamesPineDramaInEveryLanguage() {
        List<String> keys = new ArrayList<>();
        for (String key : L10nTranslations.of("de").keySet()) {
            if (ENGLISH_RESTART.matcher(key).find()) keys.add(key);
        }
        // A floor so a scan that finds nothing can't pass; the Lab's gate toasts dropped 17 of these.
        assertTrue("restart keys: " + keys.size(), keys.size() >= 40);

        List<String> problems = new ArrayList<>();
        for (String key : keys) {
            int restarts = count(ENGLISH_RESTART.matcher(key));
            check(problems, "en", key, restarts);
            for (String language : TABLES) {
                Map<String, String> table = L10nTranslations.of(language);
                String text = table.get(key);
                if (text != null && !text.isEmpty()) check(problems, language, text, restarts);
            }
        }
        assertEquals(String.join("\n", problems), 0, problems.size());
    }

    private static void check(List<String> problems, String language, String text, int restarts) {
        assertEquals(text, L10n.forApp(text, false));
        String pineDrama = L10n.forApp(text, true);
        int named = occurrences(pineDrama, "PineDrama");
        int left = occurrences(pineDrama, "TikTok");
        if (named != restarts || left != occurrences(text, "TikTok") - restarts) {
            problems.add(language + " (" + named + " of " + restarts + "): " + pineDrama);
        }
    }

    @Test
    public void theRunningAppPicksTheName() {
        Context english = context(Locale.US);
        Context german = context(Locale.GERMANY);
        String saved = "Saved. Restart TikTok to apply this.";
        String font = "Shows TikTok's text in your phone's font instead of TikTok Sans. Icons and gift "
                + "animations keep their own fonts. Restart TikTok to see the change.";

        BuildNames.setRunningAppForTests(L10n.PINEDRAMA, "47.4.3");
        assertEquals("Saved. Restart PineDrama to apply this.", L10n.t(english, saved));
        assertEquals("Restart PineDrama to apply 2 changes", L10n.quantity(english, 2,
                "Restart TikTok to apply this change", "Restart TikTok to apply %1$d changes"));
        assertEquals("Shows TikTok's text in your phone's font instead of TikTok Sans. Icons and gift "
                + "animations keep their own fonts. Restart PineDrama to see the change.", L10n.t(english, font));
        String germanSaved = L10n.t(german, saved);
        assertTrue(germanSaved, germanSaved.contains("PineDrama") && !germanSaved.contains("TikTok"));

        BuildNames.setRunningAppForTests(TIKTOK, "47.5.3");
        assertEquals(saved, L10n.t(english, saved));
        assertEquals("Restart TikTok to apply 2 changes", L10n.quantity(english, 2,
                "Restart TikTok to apply this change", "Restart TikTok to apply %1$d changes"));
        assertEquals(L10nTranslations.of("de").get(saved), L10n.t(german, saved));
    }

    private static Context context(Locale locale) {
        Configuration configuration = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        return RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
    }

    private static int count(Matcher matcher) {
        int found = 0;
        while (matcher.find()) found++;
        return found;
    }

    private static int occurrences(String text, String word) {
        int found = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) found++;
        return found;
    }
}
