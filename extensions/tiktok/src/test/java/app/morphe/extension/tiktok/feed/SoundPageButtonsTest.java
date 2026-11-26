package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
// The S22's 1080 x 2340 screen, so the bottom buttons sit where they do on a phone.
@Config(sdk = 28, qualifiers = "w360dp-h780dp-xxhdpi")
public class SoundPageButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * A sound page laid out like TikTok's on the S22: Back at the top, Save in the header's row
     * of buttons, the list of videos, and Jam Sound and Use sound floating at the bottom. Jam Sound
     * takes its own tap; Use sound is a label inside a tappable box with its icon.
     */
    public static class SoundPage extends Activity {
        Button back;
        Button save;
        Button inList;
        Button jam;
        View useBox;
        Button use;
        ImageView useIcon;
        Button ours;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout root = new FrameLayout(this);
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            back = new Button(this);
            back.setText("Back");
            column.addView(back, new LinearLayout.LayoutParams(113, 124));

            HorizontalScrollView row = new HorizontalScrollView(this);
            save = new Button(this);
            save.setText("Save");
            row.addView(save, new FrameLayout.LayoutParams(990, 124));
            column.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView list = new ScrollView(this);
            LinearLayout videos = new LinearLayout(this);
            videos.setOrientation(LinearLayout.VERTICAL);
            videos.addView(new View(this), new LinearLayout.LayoutParams(1080, 1150));
            inList = new Button(this);
            inList.setText("Video");
            videos.addView(inList, new LinearLayout.LayoutParams(358, 200));
            list.addView(videos);
            column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            jam = new Button(this);
            jam.setText("Jam Sound");
            FrameLayout.LayoutParams jamAt = new FrameLayout.LayoutParams(518, 174, Gravity.BOTTOM | Gravity.START);
            jamAt.setMargins(28, 0, 0, 45);
            root.addView(jam, jamAt);

            FrameLayout box = new FrameLayout(this);
            box.setClickable(true);
            RelativeLayout inside = new RelativeLayout(this);
            useIcon = new ImageView(this);
            inside.addView(useIcon, new RelativeLayout.LayoutParams(67, 67));
            use = new Button(this);
            use.setText("Use sound");
            use.setClickable(false);
            RelativeLayout.LayoutParams label = new RelativeLayout.LayoutParams(234, 174);
            label.leftMargin = 83;
            inside.addView(use, label);
            box.addView(inside, new FrameLayout.LayoutParams(407, 174, Gravity.CENTER));
            useBox = box;
            FrameLayout.LayoutParams useAt = new FrameLayout.LayoutParams(518, 174, Gravity.BOTTOM | Gravity.END);
            useAt.setMargins(0, 0, 28, 45);
            root.addView(box, useAt);

            // Hushfeed's own Block sound row, were it ever this low, is never one of TikTok's buttons.
            ours = new Button(this);
            ours.setTag("hushfeed_sound_page_block");
            FrameLayout.LayoutParams oursAt = new FrameLayout.LayoutParams(300, 124, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            oursAt.setMargins(0, 0, 0, 300);
            root.addView(ours, oursAt);
            setContentView(root);
        }
    }

    private boolean patchWas;
    private ActivityController<SoundPage> controller;

    @Before public void setup() {
        patchWas = SettingsStatus.hideSoundPageButtonsEnabled;
        SettingsStatus.hideSoundPageButtonsEnabled = true;
        Settings.HIDE_SOUND_PAGE_BUTTONS.save(true);
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        SettingsStatus.hideSoundPageButtonsEnabled = patchWas;
        Settings.HIDE_SOUND_PAGE_BUTTONS.resetToDefault();
    }

    private SoundPage open() {
        controller = Robolectric.buildActivity(SoundPage.class).setup().visible();
        idle();
        SoundPageButtons.onResume(controller.get());
        idle();
        return controller.get();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    @Test public void hidesJamSoundAndUseSoundByTheirOwnBoxes() {
        SoundPage page = open();

        assertEquals("Jam Sound takes its own tap, so it's what goes", View.GONE, page.jam.getVisibility());
        assertEquals("Use sound goes with its tappable box, icon and all", View.GONE, page.useBox.getVisibility());
        assertEquals(View.VISIBLE, page.back.getVisibility());
        assertEquals("Save is in the header's row of buttons", View.VISIBLE, page.save.getVisibility());
        assertEquals("A button in the list of videos stays", View.VISIBLE, page.inList.getVisibility());
        assertEquals("Hushfeed's own row stays", View.VISIBLE, page.ours.getVisibility());
    }

    @Test public void aButtonTikTokShowsAgainIsHiddenAgain() {
        SoundPage page = open();
        page.jam.setVisibility(View.VISIBLE);
        page.useBox.setVisibility(View.VISIBLE);
        idle();

        assertEquals(View.GONE, page.jam.getVisibility());
        assertEquals(View.GONE, page.useBox.getVisibility());
    }

    @Test public void switchingItOffShowsThemAsTheyWere() {
        SoundPage page = open();
        Settings.HIDE_SOUND_PAGE_BUTTONS.save(false);
        SoundPageButtons.onResume(page);
        idle();

        assertEquals(View.VISIBLE, page.jam.getVisibility());
        assertEquals(View.VISIBLE, page.useBox.getVisibility());
        page.jam.setVisibility(View.VISIBLE);
        idle();
        assertEquals("Nothing hides them once it's off", View.VISIBLE, page.jam.getVisibility());
    }

    @Test public void offOrWithoutThePatchNothingIsHidden() {
        Settings.HIDE_SOUND_PAGE_BUTTONS.save(false);
        SoundPage page = open();
        assertEquals(View.VISIBLE, page.jam.getVisibility());
        assertEquals(View.VISIBLE, page.useBox.getVisibility());
        controller.pause().stop().destroy();
        controller = null;

        Settings.HIDE_SOUND_PAGE_BUTTONS.save(true);
        SettingsStatus.hideSoundPageButtonsEnabled = false;
        page = open();
        assertEquals(View.VISIBLE, page.jam.getVisibility());
        assertEquals(View.VISIBLE, page.useBox.getVisibility());
    }
}
