package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.AppIconPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * With diagnostic logging on, the settings page reports a keyed list, switch or text row that has no
 * Setting behind it, since that's usually a typo in the key. The App icon row (Discussion #114) has a
 * key only so the settings search finds it and saves nothing, so it must not be reported.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class KeyedRowWithoutSettingTest {
    private static final String TYPO_KEY = "keyed_row_test_typo_key";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean previousDebug;

    public static final class Page extends TikTokPreferenceFragment {
        @Override protected void initialize() {
            Activity activity = getActivity();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(activity);
            setPreferenceScreen(screen);
            screen.addPreference(new AppIconPreference(activity));

            ListPreference typo = new ListPreference(activity);
            typo.setKey(TYPO_KEY);
            typo.setTitle("A list whose key matches no setting");
            typo.setEntries(new CharSequence[]{"One"});
            typo.setEntryValues(new CharSequence[]{"one"});
            screen.addPreference(typo);
        }
    }

    public static final class Host extends Activity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Before public void logDiagnostics() {
        previousDebug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After public void restoreLogging() {
        BaseSettings.DEBUG.save(previousDebug);
    }

    @Test public void theAppIconRowIsNotReportedButARealTypoStillIs() {
        try (var owner = Robolectric.buildActivity(Host.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new Page())
                    .commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            String report = LogBufferManager.snapshotForCrash(100_000);
            assertTrue(report, report.contains("Preference key has no setting: " + TYPO_KEY));
            assertFalse(report, report.contains("Preference key has no setting: hushfeed_app_icon"));
        }
    }
}
