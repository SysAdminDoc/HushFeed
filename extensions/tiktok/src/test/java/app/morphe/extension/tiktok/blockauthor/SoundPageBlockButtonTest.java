package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.music.model.Music;
import java.lang.reflect.Method;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
// The S22's 1080 pixel width, so Save's row fits the screen the way it does on a phone.
@Config(sdk = 28, qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SoundPageBlockButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A sound page laid out like TikTok's: the title block, Save in a horizontal scroller, the highlight row. */
    public static class SoundPage extends Activity {
        LinearLayout column;
        HorizontalScrollView scroll;
        Button save;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText("Epic Battle");
            header.addView(title);
            TextView author = new TextView(this);
            author.setText("SILVANA INSPIRA");
            header.addView(author);
            column.addView(header);

            scroll = new HorizontalScrollView(this);
            LinearLayout strip = new LinearLayout(this);
            strip.setPadding(45, 23, 45, 23);
            save = new Button(this);
            save.setText("Save");
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xFF333333);
            pill.setCornerRadius(62);
            save.setBackground(pill);
            strip.addView(save, new LinearLayout.LayoutParams(990, 124));
            scroll.addView(strip);
            column.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(new View(this), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 200));
            setContentView(column);
        }
    }

    /** TikTok's page keeps the Music it was handed in a field of TikTok's own Music type. */
    public static class HandedSoundPage extends SoundPage {
        @SuppressWarnings("unused")
        private Music handed = new Music(null, "Fancy", "TWICE");
    }

    private boolean soundPageBlockWas;
    private boolean feedFilterWas;
    private ActivityController<? extends SoundPage> controller;

    @Before public void setup() {
        soundPageBlockWas = SettingsStatus.soundPageBlockEnabled;
        feedFilterWas = SettingsStatus.feedFilterEnabled;
        SettingsStatus.soundPageBlockEnabled = true;
        SettingsStatus.feedFilterEnabled = true;
        Settings.BLOCKED_SOUND_IDS.save("");
        Settings.BLOCKED_SOUND_NAMES.save("");
        Settings.SOUND_PAGE_BLOCK_BUTTON.save(true);
    }

    @After public void cleanup() throws Exception {
        Method dismiss = BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        if (controller != null) controller.pause().stop().destroy();
        SettingsStatus.soundPageBlockEnabled = soundPageBlockWas;
        SettingsStatus.feedFilterEnabled = feedFilterWas;
    }

    private <T extends SoundPage> T open(Class<T> type, String id) {
        Intent intent = new Intent();
        if (id != null) intent.putExtra("id", id);
        ActivityController<T> opened = Robolectric.buildActivity(type, intent).setup().visible();
        controller = opened;
        idle();
        SoundPageBlockButton.onResume(opened.get());
        idle();
        return opened.get();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    private static View row(SoundPage page) {
        int under = page.column.indexOfChild(page.scroll) + 1;
        View view = page.column.getChildAt(under);
        return view != null && SoundPageBlockButton.TAG.equals(view.getTag()) ? view : null;
    }

    private static String label(View row) {
        return ((TextView) ((ViewGroup) row).getChildAt(0)).getText().toString();
    }

    private static List<String> texts(View view, List<String> into) {
        if (view instanceof TextView) into.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                texts(((ViewGroup) view).getChildAt(i), into);
            }
        }
        return into;
    }

    @Test public void blockSoundSitsUnderSaveAndBlocksThePagesSoundById() {
        SoundPage page = open(SoundPage.class, "7123456789");
        View row = row(page);
        assertNotNull("Block sound goes right under Save's scroller", row);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
        assertEquals("as tall as Save", page.save.getHeight(), params.height);
        assertEquals("lined up with Save on the left", 45, params.leftMargin);
        assertEquals("and on the right", 45, params.rightMargin);
        assertEquals("with Save's gap before the next row", 23, params.bottomMargin);
        assertEquals("Block sound", label(row));

        row.performClick();
        idle();
        assertTrue(Settings.BLOCKED_SOUND_IDS.get(), Settings.BLOCKED_SOUND_IDS.get().contains("7123456789"));
        assertEquals("", Settings.BLOCKED_SOUND_NAMES.get());
        assertEquals("Unblock sound", label(row));
        ViewGroup root = page.findViewById(android.R.id.content);
        assertTrue(texts(root, new ArrayList<>()).toString(),
                texts(root, new ArrayList<>()).stream().anyMatch(text ->
                        text.startsWith("Skipping videos with") && text.contains("Epic Battle")));

        row.performClick();
        idle();
        assertFalse(Settings.BLOCKED_SOUND_IDS.get().contains("7123456789"));
        assertEquals("Block sound", label(row));
    }

    @Test public void aSoundAlreadyBlockedOpensAsUnblock() {
        Settings.BLOCKED_SOUND_IDS.save("111,7123456789");
        SoundPage page = open(SoundPage.class, "7123456789");
        View row = row(page);
        assertNotNull(row);
        assertEquals("Unblock sound", label(row));
        row.performClick();
        idle();
        assertEquals("111", Settings.BLOCKED_SOUND_IDS.get());
    }

    @Test public void aSoundWithNoIdIsBlockedByTheNameTheMusicCarries() {
        HandedSoundPage page = open(HandedSoundPage.class, null);
        View row = row(page);
        assertNotNull("the handed Music names the sound", row);
        row.performClick();
        idle();
        assertEquals("", Settings.BLOCKED_SOUND_IDS.get());
        assertEquals("Fancy", Settings.BLOCKED_SOUND_NAMES.get());
    }

    @Test public void aPageWithNoSoundGetsNoButton() {
        SoundPage page = open(SoundPage.class, null);
        assertNull(row(page));
    }

    @Test public void theSwitchAndFeedFilterBothGateIt() {
        Settings.SOUND_PAGE_BLOCK_BUTTON.save(false);
        SoundPage page = open(SoundPage.class, "7123456789");
        assertNull("off means no button", row(page));

        Settings.SOUND_PAGE_BLOCK_BUTTON.save(true);
        SoundPageBlockButton.onResume(page);
        idle();
        assertNotNull("on again, back on the next resume", row(page));

        Settings.SOUND_PAGE_BLOCK_BUTTON.save(false);
        SoundPageBlockButton.onResume(page);
        idle();
        assertNull("switched off while the page was away, gone when it comes back", row(page));

        Settings.SOUND_PAGE_BLOCK_BUTTON.save(true);
        SettingsStatus.feedFilterEnabled = false;
        SoundPageBlockButton.onResume(page);
        idle();
        assertNull("nothing skips a blocked sound without Feed filter", row(page));
    }

    @Test public void aHeaderTikTokRebuildsGetsTheButtonBack() {
        SoundPage page = open(SoundPage.class, "7123456789");
        View row = row(page);
        assertNotNull(row);
        page.column.removeView(row);
        page.column.requestLayout();
        idle();
        assertNotNull("the layout listener puts it back", row(page));
        assertEquals("and only once", 4, page.column.getChildCount());
    }
}
