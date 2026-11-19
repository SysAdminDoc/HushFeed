package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Hide Add bio (#466): off by default, hides the shown prompt only with its switch, keeps it
 * hidden when TikTok's profile framework shows it again, honours Pause.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileAddBioTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FrameLayout header;

    @Before public void setUp() {
        Settings.HIDE_ADD_BIO.resetToDefault();
        HookStatus.clear();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        header = new FrameLayout(activity);
        activity.setContentView(header);
    }

    @After public void restore() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.HIDE_ADD_BIO.resetToDefault();
    }

    private View prompt() {
        View prompt = new View(header.getContext());
        header.addView(prompt);
        return prompt;
    }

    private void layOut() {
        header.requestLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void thePromptStaysUntilTheSwitchIsOn() {
        assertFalse(Settings.HIDE_ADD_BIO.defaultValue);
        assertEquals("hide_add_bio", Settings.HIDE_ADD_BIO.key);
        View prompt = prompt();
        ProfileAddBio.shown(prompt);
        layOut();
        assertEquals(View.VISIBLE, prompt.getVisibility());
    }

    @Test public void withTheSwitchOnTheShownPromptGoesStraightBack() {
        Settings.HIDE_ADD_BIO.save(true);
        View prompt = prompt();
        ProfileAddBio.shown(prompt);
        assertEquals(View.GONE, prompt.getVisibility());
        ProfileAddBio.shown(null);
        String hidden = String.join(" ", HookStatus.report());
        assertTrue(hidden, hidden.contains("profile add bio: 1 found"));
        // Switched off, the bind reports the prompt left, a second line of its own.
        Settings.HIDE_ADD_BIO.save(false);
        ProfileAddBio.shown(prompt());
        String both = String.join(" ", HookStatus.report());
        assertTrue(both, both.contains("profile add bio: 2 found"));
    }

    /** Clearing the bio in Edit profile and coming back: the framework shows the view again after the bind. */
    @Test public void aPromptTikTokShowsAgainIsGoneByTheNextLayout() {
        Settings.HIDE_ADD_BIO.save(true);
        View prompt = prompt();
        ProfileAddBio.shown(prompt);
        prompt.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, prompt.getVisibility());
        // A second bind of the same view doesn't stack another keeper, and still ends gone.
        prompt.setVisibility(View.VISIBLE);
        ProfileAddBio.shown(prompt);
        prompt.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, prompt.getVisibility());
    }

    @Test public void turnedOnLaterTheNextLayoutHidesIt() {
        View prompt = prompt();
        ProfileAddBio.shown(prompt);
        layOut();
        assertEquals(View.VISIBLE, prompt.getVisibility());
        Settings.HIDE_ADD_BIO.save(true);
        layOut();
        assertEquals(View.GONE, prompt.getVisibility());
    }

    @Test public void turnedOffTikTokCanShowItAgain() {
        Settings.HIDE_ADD_BIO.save(true);
        View prompt = prompt();
        ProfileAddBio.shown(prompt);
        Settings.HIDE_ADD_BIO.save(false);
        prompt.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.VISIBLE, prompt.getVisibility());
    }

    @Test public void pausedThePromptIsTikToks() {
        Settings.HIDE_ADD_BIO.save(true);
        PausedProcess.set(true);
        View prompt = prompt();
        ProfileAddBio.shown(prompt);
        prompt.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.VISIBLE, prompt.getVisibility());
    }
}
