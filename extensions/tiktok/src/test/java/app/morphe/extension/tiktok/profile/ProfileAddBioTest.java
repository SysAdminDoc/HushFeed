package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Hide Add bio (#466): off by default, hides the shown prompt only with its switch, honours Pause. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileAddBioTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.HIDE_ADD_BIO.resetToDefault();
    }

    @Test public void thePromptStaysUntilTheSwitchIsOn() {
        assertFalse(Settings.HIDE_ADD_BIO.defaultValue);
        assertEquals("hide_add_bio", Settings.HIDE_ADD_BIO.key);
        View prompt = new View(RuntimeEnvironment.getApplication());
        ProfileAddBio.shown(prompt);
        assertEquals(View.VISIBLE, prompt.getVisibility());
    }

    @Test public void withTheSwitchOnTheShownPromptGoesStraightBack() {
        Settings.HIDE_ADD_BIO.save(true);
        View prompt = new View(RuntimeEnvironment.getApplication());
        ProfileAddBio.shown(prompt);
        assertEquals(View.GONE, prompt.getVisibility());
        ProfileAddBio.shown(null);
        String hidden = String.join(" ", HookStatus.report());
        assertTrue(hidden, hidden.contains("profile add bio: 1 found"));
        // Switched off, the bind reports the prompt left, a second line of its own.
        Settings.HIDE_ADD_BIO.save(false);
        ProfileAddBio.shown(new View(RuntimeEnvironment.getApplication()));
        String both = String.join(" ", HookStatus.report());
        assertTrue(both, both.contains("profile add bio: 2 found"));
    }

    @Test public void pausedThePromptIsTikToks() {
        Settings.HIDE_ADD_BIO.save(true);
        PausedProcess.set(true);
        View prompt = new View(RuntimeEnvironment.getApplication());
        ProfileAddBio.shown(prompt);
        assertEquals(View.VISIBLE, prompt.getVisibility());
    }
}
