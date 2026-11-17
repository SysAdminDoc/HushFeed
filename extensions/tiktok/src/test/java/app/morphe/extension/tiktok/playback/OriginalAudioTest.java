package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
public class OriginalAudioTest {
    /** Stands in for a post's TtsInfos list; the hook only passes it on or drops it. */
    private final List<Object> dubs = new ArrayList<>();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.originalAudioEnabled = true;
        dubs.add(new Object());
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.originalAudioEnabled = false;
        Settings.ORIGINAL_AUDIO.save(Settings.ORIGINAL_AUDIO.defaultValue);
    }

    @Test public void offByDefaultTikTokKeepsItsDubs() {
        assertFalse(Settings.ORIGINAL_AUDIO.defaultValue);
        assertFalse(OriginalAudio.on());
        assertSame(dubs, OriginalAudio.dubTracks(dubs));
    }

    /** On, a dubbed post reads like one TikTok never dubbed: no track list at all. */
    @Test public void onAPostHasNoDubTracks() {
        Settings.ORIGINAL_AUDIO.save(true);
        assertTrue(OriginalAudio.on());
        assertNull(OriginalAudio.dubTracks(dubs));
        assertNull(OriginalAudio.dubTracks(null));
    }

    @Test public void pausedTikTokKeepsItsDubs() {
        Settings.ORIGINAL_AUDIO.save(true);
        setPaused(true);
        assertSame(dubs, OriginalAudio.dubTracks(dubs));
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.ORIGINAL_AUDIO.save(true);
        SettingsStatus.originalAudioEnabled = false;
        assertSame(dubs, OriginalAudio.dubTracks(dubs));
    }

    /** Read as each video is prepared, so turning it off brings the dub back on the next one. */
    @Test public void turningItOffTakesEffectAtTheNextVideo() {
        Settings.ORIGINAL_AUDIO.save(true);
        assertNull(OriginalAudio.dubTracks(dubs));
        Settings.ORIGINAL_AUDIO.save(false);
        assertSame(dubs, OriginalAudio.dubTracks(dubs));
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}
