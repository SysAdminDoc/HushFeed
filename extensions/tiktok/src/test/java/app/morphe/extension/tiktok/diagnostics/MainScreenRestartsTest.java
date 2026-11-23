package app.morphe.extension.tiktok.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #419: the feed reset with no crash, and the report only showed a second main screen in the
 * same run. Each start after the first now says what became of the screen before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class MainScreenRestartsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for TikTok's MainActivity. */
    public static final class Main extends Activity {
    }

    /** Another TikTok screen, such as the one a profile's video opens in. */
    public static final class Detail extends Activity {
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        MainScreenRestarts.resetForTests();
        LogBufferManager.clearLogBuffer();
    }

    @After public void tearDown() {
        MainScreenRestarts.resetForTests();
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    /** A main screen built the way the patched onCreate sees it: the hook runs before the host's. */
    private static ActivityController<Main> main(Intent intent) {
        ActivityController<Main> controller = Robolectric.buildActivity(Main.class, intent);
        MainScreenRestarts.onMainActivityCreate(controller.get());
        return controller.setup();
    }

    @Test public void theFirstScreenAndOtherScreensSayNothing() {
        main(new Intent(Intent.ACTION_MAIN));
        Robolectric.buildActivity(Detail.class).setup();
        assertFalse(LogBufferManager.buildExportText().contains("started over"));
    }

    @Test public void aScreenFinishedUnderAVideoIsNamedWhenTheNextOneStarts() {
        ActivityController<Main> first = main(new Intent(Intent.ACTION_MAIN));
        ActivityController<Detail> video = Robolectric.buildActivity(Detail.class).setup();
        first.get().finish();
        first.pause().stop().destroy();
        video.pause().stop().destroy();
        main(new Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("TikTok's main screen started over, 2nd this run: the one before was finished "));
        assertTrue(report, report.contains(" s earlier, starting fresh. Intent android.intent.action.MAIN, flags 0x10000000"));
    }

    @Test public void aSecondScreenOverTheFirstSaysTheFirstIsStillOpen() {
        main(new Intent(Intent.ACTION_MAIN));
        main(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.tiktok.com/@someone/video/1?x=y")));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("2nd this run: the one before is still open under it, starting fresh. "
                + "Intent android.intent.action.VIEW, flags 0x0, a https link"));
        assertFalse("the link's path stays out of the report", report.contains("someone"));
    }

    /**
     * The reporter's phone destroyed the main screen under a profile video, ten times in eight
     * minutes, with the app still running. Developer options' Don't keep activities does exactly
     * that, so the line says when it's on.
     */
    @Test public void aScreenDestroyedWithDontKeepActivitiesOnSaysSo() {
        android.provider.Settings.Global.putInt(RuntimeEnvironment.getApplication().getContentResolver(),
                android.provider.Settings.Global.ALWAYS_FINISH_ACTIVITIES, 1);
        try {
            ActivityController<Main> first = main(new Intent(Intent.ACTION_MAIN));
            ActivityController<Detail> video = Robolectric.buildActivity(Detail.class).setup();
            first.pause().stop().destroy();
            video.pause().stop().destroy();
            main(new Intent(Intent.ACTION_MAIN));

            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("2nd this run: the one before was destroyed by the system "
                    + "(Don't keep activities is on in Developer options) "));
        } finally {
            android.provider.Settings.Global.putInt(RuntimeEnvironment.getApplication().getContentResolver(),
                    android.provider.Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0);
        }
    }

    @Test public void aScreenTheSystemTookDownForAConfigurationChangeSaysWhich() {
        String line = MainScreenRestarts.describe(3, MainScreenRestarts.CONFIGURATION, true,
                ActivityInfo.CONFIG_UI_MODE | ActivityInfo.CONFIG_SCREEN_SIZE, 1500, "android.intent.action.MAIN, flags 0x0");
        assertEquals("TikTok's main screen started over, 3rd this run: the one before was torn down for a "
                + "configuration change 1.5 s earlier, changed screen size, dark mode or UI mode, restoring its "
                + "saved state. Intent android.intent.action.MAIN, flags 0x0", line);
        assertTrue(MainScreenRestarts.describe(11, MainScreenRestarts.DESTROYED, true, 0, -1, "none")
                .startsWith("TikTok's main screen started over, 11th this run: the one before was destroyed by the "
                        + "system, restoring"));
    }

    /** Without Don't keep activities, the line gives the heap, and says when it's past Android's 3/4. */
    @Test public void aScreenDestroyedWithAFullHeapSaysHowFull() {
        long mb = 1024 * 1024;
        assertEquals("Java heap 400 of 512 MB, past the three quarters where Android closes background screens",
                MainScreenRestarts.destroyedWhy(false, 400 * mb, 512 * mb));
        assertEquals("Java heap 100 of 512 MB", MainScreenRestarts.destroyedWhy(false, 100 * mb, 512 * mb));
        assertEquals("Don't keep activities is on in Developer options",
                MainScreenRestarts.destroyedWhy(true, 400 * mb, 512 * mb));
        assertEquals(null, MainScreenRestarts.destroyedWhy(false, 1, 0));
        assertTrue(MainScreenRestarts.describe(4, MainScreenRestarts.DESTROYED, true, 0, 1300, "none",
                        MainScreenRestarts.destroyedWhy(false, 400 * mb, 512 * mb))
                .contains("the one before was destroyed by the system (Java heap 400 of 512 MB, past the three "
                        + "quarters where Android closes background screens) 1.3 s earlier, restoring"));
        assertTrue("a finished screen gets no reason", MainScreenRestarts.describe(4, MainScreenRestarts.FINISHED,
                false, 0, 1300, "none", "Java heap 1 of 2 MB").contains("was finished 1.3 s earlier"));
    }

    @Test public void everyConfigurationPartHasAName() {
        assertEquals(List.of("language", "font size"),
                MainScreenRestarts.configurationNames(ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_FONT_SCALE));
        assertEquals(List.of("display size"), MainScreenRestarts.configurationNames(ActivityInfo.CONFIG_DENSITY));
        assertEquals(List.of(), MainScreenRestarts.configurationNames(0));
    }
}
