package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** #525: Hide LIVE chat keeps the room's chat invisible whatever shows it again, and only then. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveChatTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private View chat;

    @Before public void setUp() {
        SettingsStatus.liveControlsEnabled = true;
        Activity room = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(room);
        chat = new View(room);
        page.addView(chat);
        room.setContentView(page);
    }

    @After public void tearDown() {
        SettingsStatus.liveControlsEnabled = false;
        Settings.HIDE_LIVE_CHAT.resetToDefault();
        PausedProcess.set(false);
    }

    private void nextFrame() {
        chat.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test public void theChatStaysInvisibleWhenTikTokShowsItAgain() {
        Settings.HIDE_LIVE_CHAT.save(true);
        LiveChat.onLoad(chat);
        assertEquals(View.INVISIBLE, chat.getVisibility());
        // The widget's show and clear screen code set the view visible again.
        chat.setVisibility(View.VISIBLE);
        nextFrame();
        assertEquals(View.INVISIBLE, chat.getVisibility());
        // A room loading the same view again watches it once.
        LiveChat.onLoad(chat);
        chat.setVisibility(View.VISIBLE);
        nextFrame();
        assertEquals(View.INVISIBLE, chat.getVisibility());
    }

    @Test public void turningTheSwitchOffShowsTheChatAtTheNextFrame() {
        Settings.HIDE_LIVE_CHAT.save(true);
        LiveChat.onLoad(chat);
        Settings.HIDE_LIVE_CHAT.save(false);
        nextFrame();
        assertEquals(View.VISIBLE, chat.getVisibility());
        nextFrame();
        assertEquals(View.VISIBLE, chat.getVisibility());
    }

    @Test public void offOrPausedTheChatIsLeftAsTikTokHasIt() {
        LiveChat.onLoad(chat);
        assertEquals(View.VISIBLE, chat.getVisibility());
        Settings.HIDE_LIVE_CHAT.save(true);
        PausedProcess.set(true);
        nextFrame();
        assertEquals(View.VISIBLE, chat.getVisibility());
    }

    @Test public void aChatTikTokHidItselfIsNotShownWhenTheSwitchGoesOff() {
        Settings.HIDE_LIVE_CHAT.save(true);
        LiveChat.onLoad(chat);
        chat.setVisibility(View.GONE);
        nextFrame();
        assertEquals(View.GONE, chat.getVisibility());
        Settings.HIDE_LIVE_CHAT.save(false);
        nextFrame();
        assertEquals(View.GONE, chat.getVisibility());
    }

    @Test public void withoutTheLiveControlsPatchNothingIsHidden() {
        SettingsStatus.liveControlsEnabled = false;
        Settings.HIDE_LIVE_CHAT.save(true);
        LiveChat.onLoad(chat);
        assertEquals(View.VISIBLE, chat.getVisibility());
    }

    @Test public void aRoomWithNoChatViewIsLeftAlone() {
        Settings.HIDE_LIVE_CHAT.save(true);
        LiveChat.onLoad(null);
    }
}
