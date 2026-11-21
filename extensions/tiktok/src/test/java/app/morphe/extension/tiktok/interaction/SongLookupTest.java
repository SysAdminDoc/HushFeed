package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.SongRecognition;
import app.morphe.extension.tiktok.settings.L10n;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Constructor;

/** What the reader sees once Shazam has answered: the song with Copy, or why there is none. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SongLookupTest {
    @After public void tearDown() {
        SongLookup.resetForTests();
    }

    private static SongRecognition.Result result(SongRecognition.Outcome outcome, String title, String artist)
            throws Exception {
        Constructor<SongRecognition.Result> make = SongRecognition.Result.class.getDeclaredConstructor(
                SongRecognition.Outcome.class, String.class, String.class);
        make.setAccessible(true);
        return make.newInstance(outcome, title, artist);
    }

    @Test public void aMatchShowsTheSongAndCopiesIt() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            SongLookup.answer(activity, result(SongRecognition.Outcome.FOUND, "A Song", "A Band"));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("A Song", Shadows.shadowOf(dialog).getTitle().toString());
            TextView message = dialog.findViewById(android.R.id.message);
            assertEquals("A Band", message.getText().toString());

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(L10n.f("%1$s by %2$s", "A Song", "A Band"),
                    String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            assertEquals(L10n.t("Song copied"), ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void everyOtherEndSaysWhy() throws Exception {
        String[][] cases = {
                {"NOT_FOUND", "Shazam didn't recognize this song"},
                {"NO_SOUND", "This video has no sound to identify"},
                {"QUIET", "This sound is too quiet to identify"},
                {"LIMITED", "Shazam is busy. Try again later."},
                {"FAILED", "The song couldn't be looked up. Try again."},
        };
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            for (String[] testCase : cases) {
                ShadowToast.reset();
                SongLookup.answer(activity, result(SongRecognition.Outcome.valueOf(testCase[0]), null, null));
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertEquals(testCase[0], L10n.t(testCase[1]), ShadowToast.getTextOfLatestToast());
            }
        }
    }

    @Test public void aMatchWithTheScreenGoneIsSaidInstead() throws Exception {
        Utils.setContext(org.robolectric.RuntimeEnvironment.getApplication());
        SongLookup.answer(null, result(SongRecognition.Outcome.FOUND, "A Song", null));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("A Song", ShadowToast.getTextOfLatestToast());
    }
}
