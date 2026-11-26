package app.morphe.extension.tiktok.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.TwoStatePreference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;

import java.time.Duration;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #419: Android closed TikTok's main screen at 462 of 512 MB of Java heap, three quarters being
 * the line it closes stopped screens at. A collection past five eighths now hands TikTok the
 * running-low trim first, and the report says what it freed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class HeapReliefTest {
    private static final long MB = 1024L * 1024;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for TikTok's MainActivity and writes down each trim it's handed. */
    public static final class Main extends Activity {
        final List<Integer> trims = new ArrayList<>();

        @Override public void onTrimMemory(int level) {
            super.onTrimMemory(level);
            trims.add(level);
        }
    }

    /** One of TikTok's own trim listeners, such as its Fresco cache's. */
    private static final class Listener implements ComponentCallbacks2 {
        final List<Integer> trims = new ArrayList<>();
        boolean fail;

        @Override public void onTrimMemory(int level) {
            trims.add(level);
            if (fail) throw new IllegalStateException("a listener that throws");
        }

        @Override public void onConfigurationChanged(Configuration configuration) {
        }

        @Override public void onLowMemory() {
        }
    }

    private final Listener listener = new Listener();
    private Main main;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        Settings.FREE_MEMORY_EARLY.resetToDefault();
        HeapRelief.resetForTests();
        LogBufferManager.clearLogBuffer();
        RuntimeEnvironment.getApplication().registerComponentCallbacks(listener);
        ActivityController<Main> controller = Robolectric.buildActivity(Main.class);
        HeapRelief.onMainActivityCreate(controller.get());
        main = controller.setup().get();
    }

    @After public void tearDown() {
        RuntimeEnvironment.getApplication().unregisterComponentCallbacks(listener);
        HeapRelief.resetForTests();
        Settings.FREE_MEMORY_EARLY.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    /** A reading after a collection, then whatever it posted to the main thread. */
    private static void heapAt(long usedMb) {
        HeapRelief.noteHeap(usedMb * MB, 512 * MB, SystemClock.uptimeMillis());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void later(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static String report() {
        return LogBufferManager.buildExportText();
    }

    @Test public void theSwitchIsOnByDefault() {
        assertTrue(Settings.FREE_MEMORY_EARLY.get());
    }

    public static final class Page extends PreferenceActivity {
    }

    @Test public void theSwitchIsOnTheDiagnosticsPage() {
        boolean diagnostics = SettingsStatus.diagnosticsEnabled;
        try (var controller = Robolectric.buildActivity(Page.class).setup()) {
            Utils.setContext(controller.get());
            SettingsStatus.diagnosticsEnabled = true;
            PreferenceScreen screen = controller.get().getPreferenceManager().createPreferenceScreen(controller.get());
            new DebugPreferenceCategory(controller.get(), screen);
            Preference row = screen.findPreference(Settings.FREE_MEMORY_EARLY.key);
            assertNotNull("no Free memory early row on the Diagnostics page", row);
            assertEquals("Free memory early", String.valueOf(row.getTitle()));
            assertTrue("the row shows the switch on", ((TwoStatePreference) row).isChecked());
        } finally {
            SettingsStatus.diagnosticsEnabled = diagnostics;
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
    }

    @Test public void pastFiveEighthsTikTokAndItsScreenGetTheRunningLowTrim() {
        heapAt(320);
        assertEquals(List.of(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW), listener.trims);
        assertEquals(List.of(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW), main.trims);
    }

    @Test public void belowFiveEighthsNothingIsTrimmed() {
        heapAt(110);
        heapAt(319);
        assertEquals(List.of(), listener.trims);
        assertEquals(List.of(), main.trims);
        assertTrue(report(), report().contains(
                "Highest after a collection: 319 of 512 MB (Android closes background screens past 384 MB,"
                        + " Free memory early trims at 320 MB)"));
    }

    @Test public void theReportSaysWhatTheTrimFreed() {
        heapAt(462);
        later(2_000);
        heapAt(250);
        later(4_000);
        heapAt(270);
        String during = report();
        assertTrue(during, during.contains("[" + HeapRelief.TITLE + "]"));
        assertTrue(during, during.contains(
                "Trimmed TikTok's caches 1 time, the last at 462 MB, 250 MB at the lowest after it so far"));
        later(6_000);
        heapAt(280);
        String after = report();
        assertTrue(after, after.contains(
                "Free memory early: trimmed TikTok's caches at 462 of 512 MB Java heap, 250 MB at the lowest"
                        + " in the 10 s after, 212 MB freed (1st this run)"));
        assertTrue(after, after.contains("Trimmed TikTok's caches 1 time, the last at 462 MB, 250 MB at the lowest after it\n"));
        assertTrue(after, after.contains("Freed in all: 212 MB"));
        assertTrue(after, after.contains("Highest after a collection: 462 of 512 MB"));
    }

    @Test public void aNewTrimWaitsAMinuteAndFiveAfterOneThatFreedLittle() {
        heapAt(400);
        later(1_000);
        heapAt(200);
        later(10_000);
        heapAt(390);
        assertEquals("the minute after a trim", 1, main.trims.size());
        later(49_000);
        heapAt(390);
        assertEquals("a minute after the first trim", 2, main.trims.size());

        // This one frees 2 MB, so the next waits five minutes.
        later(1_000);
        heapAt(388);
        later(10_000);
        heapAt(389);
        later(120_000);
        heapAt(395);
        assertEquals("two minutes after a trim that freed little", 2, main.trims.size());
        later(170_000);
        heapAt(395);
        assertEquals("five minutes after it", 3, main.trims.size());
        assertTrue(report(), report().contains("Trimmed TikTok's caches 3 times, the last at 395 MB"));
    }

    @Test public void theSwitchOffTrimsNothing() {
        Settings.FREE_MEMORY_EARLY.save(false);
        heapAt(470);
        assertEquals(List.of(), listener.trims);
        assertEquals(List.of(), main.trims);
        assertTrue(report(), report().contains("Highest after a collection: 470 of 512 MB (Android closes"
                + " background screens past 384 MB, Free memory early off)"));
    }

    @Test public void aListenerThatThrowsStillLeavesTheScreenItsTrim() {
        listener.fail = true;
        heapAt(400);
        assertEquals(List.of(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW), listener.trims);
        assertEquals(List.of(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW), main.trims);
    }

    @Test public void aFinishedScreenIsLeftOut() {
        main.finish();
        heapAt(400);
        assertEquals(List.of(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW), listener.trims);
        assertEquals(List.of(), main.trims);
    }

    @Test public void eachCollectionReadsTheHeapAndLeavesTheNextSentinel() {
        HeapRelief.resetForTests();
        HeapRelief.followGc = true;
        HeapRelief.reading = new HeapRelief.Reading() {
            @Override public long used() {
                return 120 * MB;
            }

            @Override public long max() {
                return 512 * MB;
            }
        };
        HeapRelief.onMainActivityCreate(Robolectric.buildActivity(Main.class).get());
        assertTrue(HeapRelief.watchingForTests());
        Object armed = HeapRelief.sentinelForTests();
        assertNotNull(armed);

        new HeapRelief.GcSentinel().finalize();
        assertNotSame("a new sentinel waits for the next collection", armed, HeapRelief.sentinelForTests());
        assertTrue(report(), report().contains("Highest after a collection: 120 of 512 MB"));
    }
}
