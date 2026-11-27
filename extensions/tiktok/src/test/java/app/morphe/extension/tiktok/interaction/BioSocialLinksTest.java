package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/** Links to other apps in bios: another app's username becomes a pill that opens its public page (#549). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BioSocialLinksTest {
    private ActivityController<Activity> owner;
    private Activity activity;
    private boolean previousStatus;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousStatus = SettingsStatus.copyIdsEnabled;
        SettingsStatus.copyIdsEnabled = true;
        Settings.COPY_IDS.save(true);
        Settings.BIO_MENTION_LINKS.save(true);
        Settings.BIO_SOCIAL_LINKS.save(true);
        owner = Robolectric.buildActivity(Activity.class).setup();
        activity = owner.get();
        Utils.setActivity(activity);
    }

    @After public void tearDown() {
        SettingsStatus.copyIdsEnabled = previousStatus;
        Settings.COPY_IDS.resetToDefault();
        Settings.BIO_MENTION_LINKS.resetToDefault();
        Settings.BIO_SOCIAL_LINKS.resetToDefault();
        Utils.setActivity(null);
        if (owner != null) owner.close();
    }

    @Test public void eachAppsUsernameOpensItsPublicPageOutsideTikTok() {
        String[][] bios = {
                {"IG: nasa", "nasa", "https://www.instagram.com/nasa"},
                {"insta:@nasa_hq", "nasa_hq", "https://www.instagram.com/nasa_hq"},
                {"snap - nasa.space", "nasa.space", "https://www.snapchat.com/add/nasa.space"},
                {"Snapchat @nasa", "nasa", "https://www.snapchat.com/add/nasa"},
                {"yt @NASA", "NASA", "https://www.youtube.com/@NASA"},
                {"X: @nasa", "nasa", "https://x.com/nasa"},
                {"twitter: nasa", "nasa", "https://x.com/nasa"},
                {"twitch: nasa_live", "nasa_live", "https://www.twitch.tv/nasa_live"},
                {"threads @nasa", "nasa", "https://www.threads.com/@nasa"},
                {"fb | nasa.gov", "nasa.gov", "https://www.facebook.com/nasa.gov"},
                {"Backup on IG: nasa. DM for collabs", "nasa", "https://www.instagram.com/nasa"},
        };
        for (String[] bio : bios) {
            TextView view = bio(bio[0]);
            assertEquals(bio[0], 1, pills(view));
            tapAt(view, bio[0].indexOf(bio[1]) + 1);
            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(bio[0], started);
            assertEquals(bio[0], Intent.ACTION_VIEW, started.getAction());
            assertEquals(bio[0], bio[2], started.getDataString());
            assertNull(bio[0] + ": handed to Android, not kept inside TikTok", started.getPackage());
            assertTrue(bio[0], started.hasCategory(Intent.CATEGORY_BROWSABLE));
        }
    }

    @Test public void aPillIsDrawnInItsAppsColor() {
        assertEquals(0xFFE1306C, pillColor("IG: nasa", "nasa"));
        assertEquals(0xFFFFFC00, pillColor("snap: nasa", "nasa"));
        assertEquals(0xFFFF0000, pillColor("yt: nasa", "nasa"));
        assertEquals(0xFF9146FF, pillColor("twitch: nasa_live", "nasa_live"));
        assertEquals(0xFF1877F2, pillColor("fb: nasa.gov", "nasa.gov"));
    }

    @Test public void aNameTheAppWouldntAllowOrNoAppNamedStaysText() {
        for (String text : new String[]{
                "twitch: abc", "X: way_too_long_for_x", "snap: 1abc", "my ig is nasa", "tw: self harm",
                "max: nasa", "ig: josé", "Discord: nasa", "fb: nasa"}) {
            TextView view = bio(text);
            assertEquals(text, 0, pills(view));
            tapAt(view, text.length() - 2);
            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNull(text, started);
        }
    }

    @Test public void tikTokMentionsBesideAPillStillOpenInTikTok() {
        String text = "IG: @nasa, tiktok backup @esa_web";
        TextView view = bio(text);
        assertEquals(1, pills(view));
        tapAt(view, text.indexOf("esa_web") + 1);
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("https://www.tiktok.com/@esa_web", started.getDataString());
        assertEquals(activity.getPackageName(), started.getPackage());
        tapAt(view, text.indexOf("nasa") + 1);
        assertEquals("https://www.instagram.com/nasa", Shadows.shadowOf(activity).getNextStartedActivity().getDataString());
    }

    @Test public void pillsWorkWithTapAtNamesOff() {
        Settings.BIO_MENTION_LINKS.save(false);
        TextView view = bio("sc: nasa");
        assertEquals(1, pills(view));
        tapAt(view, 5);
        assertEquals("https://www.snapchat.com/add/nasa", Shadows.shadowOf(activity).getNextStartedActivity().getDataString());
    }

    @Test public void thePillComesBackWhenTikTokSetsTheTextAgain() {
        TextView view = bio("IG: nasa");
        view.setText("Translated: IG: nasa_two");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        layout(view);
        assertEquals(1, pills(view));
        tapAt(view, view.getText().toString().indexOf("nasa_two") + 2);
        assertEquals("https://www.instagram.com/nasa_two", Shadows.shadowOf(activity).getNextStartedActivity().getDataString());
    }

    @Test public void offLeavesOtherAppsNamesAsText() {
        Settings.BIO_SOCIAL_LINKS.save(false);
        TextView view = bio("IG: @nasa");
        assertEquals(0, pills(view));
        tapAt(view, 6);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void withNoBrowserATapSaysSoInsteadOfClosingTikTok() {
        Context noBrowser = new ContextWrapper(activity) {
            @Override public void startActivity(Intent intent) {
                throw new ActivityNotFoundException("no browser");
            }
        };
        TextView view = bio(noBrowser, "IG: nasa");
        tapAt(view, 5);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("No app on this phone opens web links", ShadowToast.getTextOfLatestToast());
        assertFalse(activity.isFinishing());
    }

    private TextView bio(CharSequence text) {
        return bio(activity, text);
    }

    private TextView bio(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 48);
        view.setText(text);
        FrameLayout root = new FrameLayout(activity);
        root.addView(view, new FrameLayout.LayoutParams(1000, FrameLayout.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
        CopyIds.onBio(view, text.toString());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        layout(view);
        return view;
    }

    private static void layout(TextView view) {
        View root = (View) view.getParent();
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, 1000, root.getMeasuredHeight());
    }

    private static int pills(TextView view) {
        CharSequence text = view.getText();
        if (!(text instanceof Spanned)) return 0;
        return ((Spanned) text).getSpans(0, text.length(), ReplacementSpan.class).length;
    }

    /** The color just inside the left end of the pill over [name] in a bio showing [text]. */
    private int pillColor(String text, String name) {
        TextView view = bio(text);
        assertEquals(text, 1, pills(view));
        Layout layout = view.getLayout();
        int start = text.indexOf(name);
        int line = layout.getLineForOffset(start);
        int x = (int) (layout.getPrimaryHorizontal(start) + view.getTotalPaddingLeft()) + 4;
        int y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 + view.getTotalPaddingTop();
        Bitmap drawn = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(drawn));
        return drawn.getPixel(x, y);
    }

    private static void tapAt(TextView view, int offset) {
        Layout layout = view.getLayout();
        int line = layout.getLineForOffset(offset);
        float x = (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2
                + view.getTotalPaddingLeft();
        float y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + view.getTotalPaddingTop();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        view.dispatchTouchEvent(down);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(up);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        down.recycle();
        up.recycle();
    }
}
