package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Rational;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.time.Duration;
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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PictureInPictureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> owner;
    private View decor;
    private FrameLayout cell;
    private TextureView video;
    private TextView caption;
    private TextView tabs;
    private View alreadyGone;
    private TextureView thumbnail;

    @Before public void setUp() {
        SettingsStatus.pictureInPictureEnabled = true;
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = owner.get();
        FrameLayout content = new FrameLayout(activity);
        cell = new FrameLayout(activity);
        video = new TextureView(activity);
        caption = new TextView(activity);
        cell.addView(video, new FrameLayout.LayoutParams(400, 700));
        cell.addView(caption, new FrameLayout.LayoutParams(400, 100));
        content.addView(cell, new FrameLayout.LayoutParams(400, 700));
        tabs = new TextView(activity);
        content.addView(tabs, new FrameLayout.LayoutParams(400, 100));
        alreadyGone = new View(activity);
        alreadyGone.setVisibility(View.GONE);
        content.addView(alreadyGone, new FrameLayout.LayoutParams(400, 100));
        // A small surface, like a LIVE preview's or an ad's, that isn't the video on screen.
        thumbnail = new TextureView(activity);
        content.addView(thumbnail, new FrameLayout.LayoutParams(60, 60));
        activity.setContentView(content);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 800);
    }

    @After public void tearDown() {
        PictureInPicture.bringBack();
        setPaused(false);
        SettingsStatus.pictureInPictureEnabled = false;
        Settings.PICTURE_IN_PICTURE.save(Settings.PICTURE_IN_PICTURE.defaultValue);
        owner.pause().stop().destroy();
    }

    @Test public void onlyTheWindowsOwnButtonCanPauseOrPlay() {
        Activity activity = owner.get();
        Intent sent = sentBy(PictureInPicture.actions(activity, true, false, false, false, 1).get(0));
        assertTrue(PictureInPicture.fromTheButton(sent));
        for (RemoteAction arrow : PictureInPicture.actions(activity, true, true, true, true, 3)) {
            assertTrue("every button carries the token", PictureInPicture.fromTheButton(sentBy(arrow)));
        }

        // Another app sending the action, before Android 13 let a receiver refuse it.
        Intent bare = new Intent(PictureInPicture.ACTION_TOGGLE)
                .setPackage(activity.getPackageName());
        assertFalse(PictureInPicture.fromTheButton(bare));
        assertFalse(PictureInPicture.fromTheButton(
                new Intent(bare).putExtra(PictureInPicture.EXTRA_TOKEN, "guess")));
        assertFalse(PictureInPicture.fromTheButton(null));
    }

    /** #413: TikTok's LIVE window switch goes on with this one, and back off only if Hushfeed turned it on. */
    @Test public void tiktoksLiveWindowSwitchFollowsThisOne() {
        SharedPreferences state = Utils.getContext()
                .getSharedPreferences(PictureInPicture.STATE, Context.MODE_PRIVATE);
        state.edit().clear().commit();

        Settings.PICTURE_IN_PICTURE.save(false);
        assertNull("never turned on here, so TikTok's own choice stands", PictureInPicture.liveWindowSwitch());

        Settings.PICTURE_IN_PICTURE.save(true);
        assertEquals(Boolean.TRUE, PictureInPicture.liveWindowSwitch());
        assertTrue(state.getBoolean(PictureInPicture.LIVE_WINDOW_OURS, false));

        Settings.PICTURE_IN_PICTURE.save(false);
        assertEquals(Boolean.FALSE, PictureInPicture.liveWindowSwitch());
        assertFalse(state.getBoolean(PictureInPicture.LIVE_WINDOW_OURS, true));
        assertNull("turned back off once, then left to TikTok", PictureInPicture.liveWindowSwitch());

        // Left out of the patch: the room page never asks, and the answer leaves TikTok's switch alone.
        SettingsStatus.pictureInPictureEnabled = false;
        Settings.PICTURE_IN_PICTURE.save(true);
        assertNull(PictureInPicture.liveWindowSwitch());
    }

    /** #413: with this switch on, TikTok's LIVE window stops refusing screens it files as tablets. */
    @Test public void theLiveWindowOpensOnScreensTikTokCallsLarge() {
        Settings.PICTURE_IN_PICTURE.save(false);
        assertEquals("off, TikTok's answer stands", 1, PictureInPicture.liveWindowLargeScreen(1));
        assertEquals(0, PictureInPicture.liveWindowLargeScreen(0));

        Settings.PICTURE_IN_PICTURE.save(true);
        assertEquals(0, PictureInPicture.liveWindowLargeScreen(1));
        assertEquals(0, PictureInPicture.liveWindowLargeScreen(0));

        SettingsStatus.pictureInPictureEnabled = false;
        assertEquals("left out of the patch, TikTok's answer stands", 1, PictureInPicture.liveWindowLargeScreen(1));
    }

    /**
     * #413: in the window the feed's activity is paused, and TikTok's page select starts the next
     * video only for a resumed one, so the window's arrows answered "not paused" to it.
     */
    @Test public void thePageSelectPlaysOnOnlyWhileTheWindowIsUp() {
        assertTrue(PictureInPicture.pageSelectPaused(true, false));
        assertFalse(PictureInPicture.pageSelectPaused(true, true));
        assertFalse(PictureInPicture.pageSelectPaused(false, false));
        assertFalse(PictureInPicture.pageSelectPaused(false, true));

        Activity activity = owner.get();
        assertEquals("no window, TikTok's answer stands", 1, PictureInPicture.pageSelectPaused(1));
        assertEquals(0, PictureInPicture.pageSelectPaused(0));
        try {
            ReflectionHelpers.setStaticField(PictureInPicture.class, "opened", new WeakReference<>(activity));
            assertEquals("opened, but not in the window yet", 1, PictureInPicture.pageSelectPaused(1));
            activity.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            assertEquals(0, PictureInPicture.pageSelectPaused(1));
            assertEquals(0, PictureInPicture.pageSelectPaused(0));
        } finally {
            ReflectionHelpers.setStaticField(PictureInPicture.class, "opened", new WeakReference<Activity>(null));
        }
        assertEquals(1, PictureInPicture.pageSelectPaused(1));
    }

    /**
     * #413: the step also skips the start for a feed panel that says it's off screen, which it says
     * as soon as the activity pauses, so the window counts it as on screen.
     */
    @Test public void thePageSelectSeesTheFeedOnlyWhileTheWindowIsUp() {
        assertTrue(PictureInPicture.pageSelectVisible(true, false));
        assertTrue(PictureInPicture.pageSelectVisible(true, true));
        assertFalse(PictureInPicture.pageSelectVisible(false, false));
        assertTrue(PictureInPicture.pageSelectVisible(false, true));

        Activity activity = owner.get();
        assertEquals("no window, TikTok's answer stands", 0, PictureInPicture.pageSelectVisible(0));
        assertEquals(1, PictureInPicture.pageSelectVisible(1));
        try {
            ReflectionHelpers.setStaticField(PictureInPicture.class, "opened", new WeakReference<>(activity));
            assertEquals("opened, but not in the window yet", 0, PictureInPicture.pageSelectVisible(0));
            activity.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            assertEquals(1, PictureInPicture.pageSelectVisible(0));
            assertEquals(1, PictureInPicture.pageSelectVisible(1));
        } finally {
            ReflectionHelpers.setStaticField(PictureInPicture.class, "opened", new WeakReference<Activity>(null));
        }
        assertEquals(0, PictureInPicture.pageSelectVisible(0));
    }

    /** #413: previous, play or pause, then next, when the video is in a pager and the window has room. */
    @Test public void theArrowsSitEitherSideOfPlayAndPause() {
        Activity activity = owner.get();
        List<RemoteAction> buttons = PictureInPicture.actions(activity, true, true, false, true, 3);
        assertEquals(3, buttons.size());
        assertEquals(PictureInPicture.ACTION_PREVIOUS, sentBy(buttons.get(0)).getAction());
        assertEquals(PictureInPicture.ACTION_TOGGLE, sentBy(buttons.get(1)).getAction());
        assertEquals(PictureInPicture.ACTION_NEXT, sentBy(buttons.get(2)).getAction());
        assertEquals("Previous video", buttons.get(0).getTitle().toString());
        assertEquals("Pause", buttons.get(1).getTitle().toString());
        assertEquals("Next video", buttons.get(2).getTitle().toString());
        assertFalse("nothing before the first video, so the arrow is dimmed", buttons.get(0).isEnabled());
        assertTrue(buttons.get(1).isEnabled());
        assertTrue(buttons.get(2).isEnabled());
        assertEquals("Play", PictureInPicture.actions(activity, false, true, true, true, 3)
                .get(1).getTitle().toString());

        // Each button needs its own PendingIntent, or Android hands every one the last intent.
        assertNotSame(buttons.get(0).getActionIntent(), buttons.get(2).getActionIntent());
    }

    @Test public void withoutAPagerOnlyPlayAndPauseShow() {
        Activity activity = owner.get();
        List<RemoteAction> alone = PictureInPicture.actions(activity, true, false, true, true, 3);
        assertEquals(1, alone.size());
        assertEquals(PictureInPicture.ACTION_TOGGLE, sentBy(alone.get(0)).getAction());
        List<RemoteAction> one = PictureInPicture.actions(activity, true, true, true, true, 1);
        assertEquals(1, one.size());
        assertEquals(PictureInPicture.ACTION_TOGGLE, sentBy(one.get(0)).getAction());
    }

    /**
     * #413: a tall video's window opens about 108dp wide, and Android cut the next arrow off the
     * end of three. A window that holds two shows play or pause and next, and three come back
     * once the window is wide enough.
     */
    @Test public void aNarrowWindowKeepsPlayAndNext() {
        Activity activity = owner.get();
        List<RemoteAction> narrow = PictureInPicture.actions(activity, true, true, true, false, 2);
        assertEquals(2, narrow.size());
        assertEquals(PictureInPicture.ACTION_TOGGLE, sentBy(narrow.get(0)).getAction());
        assertEquals(PictureInPicture.ACTION_NEXT, sentBy(narrow.get(1)).getAction());
        assertFalse("nothing after the last video, so next is dimmed", narrow.get(1).isEnabled());

        assertEquals("the emulator's 110dp window", 2, PictureInPicture.room(3, 110f));
        assertEquals(2, PictureInPicture.room(3, 137f));
        assertEquals("a wide video's 197dp window", 3, PictureInPicture.room(3, 197f));
        assertEquals("a window not up yet", 3, PictureInPicture.room(3, 0f));
        assertEquals("never more than Android allows", 1, PictureInPicture.room(1, 110f));
    }

    @Test public void theVideosPagerIsItsNearestVerticalPager() {
        Activity activity = owner.get();
        VerticalViewPager outer = new VerticalViewPager(activity);
        VerticalViewPager inner = new VerticalViewPager(activity);
        FrameLayout pagerCell = new FrameLayout(activity);
        TextureView paged = new TextureView(activity);
        outer.addView(inner);
        inner.addView(pagerCell);
        pagerCell.addView(paged);
        assertSame(inner, PictureInPicture.pagerOf(paged));
        assertNull("the feed setup above has no pager", PictureInPicture.pagerOf(video));
        assertNull(PictureInPicture.pagerOf(null));
    }

    @Test public void anArrowMovesOnlyAnIdlePagerThatHasAVideoThatWay() {
        VerticalViewPager pager = new VerticalViewPager(owner.get());
        assertTrue(PictureInPicture.canMove(pager, 1));
        assertFalse("the first video has nothing before it", PictureInPicture.canMove(pager, -1));
        pager.hasPrevious = true;
        assertTrue(PictureInPicture.canMove(pager, -1));
        pager.hasNext = false;
        assertFalse(PictureInPicture.canMove(pager, 1));
        pager.hasNext = true;
        pager.scrollState = 2;
        assertFalse("mid-slide, a second move would skip a video", PictureInPicture.canMove(pager, 1));
        assertFalse(PictureInPicture.canMove(null, 1));
    }

    /**
     * #413: after Next the feed's player moved to the new video, but play and pause still named
     * the one it left, so the window kept a still frame with no sound. And a LIVE preview never
     * plays in this window, so the arrow steps over it to the next video.
     */
    @Test public void nextPlaysTheVideoThePlayerMovedToAndStepsOverALive() throws Exception {
        Activity activity = owner.get();
        FrameLayout content = new FrameLayout(activity);
        VerticalViewPager pager = new VerticalViewPager(activity);
        FrameLayout pagerCell = new FrameLayout(activity);
        pagerCell.addView(new TextureView(activity), new FrameLayout.LayoutParams(400, 700));
        pager.addView(pagerCell, new FrameLayout.LayoutParams(400, 700));
        content.addView(pager, new FrameLayout.LayoutParams(400, 700));
        activity.setContentView(content);
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 800);

        Player player = new Player(pager, new Clip("a", 0), new Clip("live", 7), new Clip("c", 0));
        pager.current = 0;
        Class<?> controls = Class.forName(SessionPlaybackHold.class.getName() + "$NativeControls");
        Object fake = Proxy.newProxyInstance(controls.getClassLoader(), new Class<?>[] {controls},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "currentAweme": return ((Player) args[0]).clips[pager.current];
                        case "resume": ((Manager) args[0]).resumes++; ((Manager) args[0]).playing = true; return null;
                        case "pause": ((Manager) args[0]).playing = false; return null;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return "fake controls";
                    }
                });
        try {
            ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "nativeForTests", fake);
            SessionPlaybackHold.onPlayerProgress(player, "a");
            ReflectionHelpers.setStaticField(PictureInPicture.class, "opened", new WeakReference<>(activity));
            activity.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            // TikTok doesn't start the next video in the window by itself.
            player.manager.playing = false;

            PictureInPicture.onMove(1);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));

            assertEquals("stepped over the LIVE", 2, pager.current);
            assertEquals(2, pager.advances);
            assertEquals("c", SessionPlaybackHold.currentAwemeId());
            assertEquals("the new video was started once", 1, player.manager.resumes);
        } finally {
            ReflectionHelpers.setStaticField(PictureInPicture.class, "opened", new WeakReference<Activity>(null));
            ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
            ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "nativeForTests", null);
        }
    }

    /** A feed post; a LIVE preview carries a room id. */
    public static final class Clip extends Aweme {
        private final String aid;
        private final long liveId;

        Clip(String aid, long liveId) {
            this.aid = aid;
            this.liveId = liveId;
        }

        @Override public String getAid() { return aid; }
        @Override public long getLiveId() { return liveId; }
        @Override public boolean isLiveReplay() { return false; }
        @Override public String getLiveType() { return null; }
    }

    public static final class Manager {
        boolean playing = true;
        int resumes;

        public boolean isPlaying() { return playing; }
        public boolean isPaused() { return !playing; }
    }

    /** TikTok's one feed player, whose video is whichever cell the pager is on. */
    public static final class Player {
        final VerticalViewPager pager;
        final Clip[] clips;
        final Manager manager = new Manager();

        Player(VerticalViewPager pager, Clip... clips) {
            this.pager = pager;
            this.clips = clips;
            pager.hasNext = true;
        }

        public Manager getPlayerManager() { return manager; }
    }

    @Test public void offByDefault() {
        assertFalse(Settings.PICTURE_IN_PICTURE.defaultValue);
        assertFalse(PictureInPicture.enabled());
    }

    @Test public void onWhenTheSwitchIs() {
        Settings.PICTURE_IN_PICTURE.save(true);
        assertTrue(PictureInPicture.enabled());
    }

    @Test public void pausedLeavesTikTokAsBefore() {
        Settings.PICTURE_IN_PICTURE.save(true);
        setPaused(true);
        assertFalse(PictureInPicture.enabled());
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.PICTURE_IN_PICTURE.save(true);
        SettingsStatus.pictureInPictureEnabled = false;
        assertFalse(PictureInPicture.enabled());
    }

    /** The video's own shape, held inside what Android takes for a picture-in-picture window. */
    @Test public void theWindowTakesTheVideosShapeWithinAndroidsLimits() {
        assertEquals(new Rational(9, 16), PictureInPicture.shape(1080, 1920));
        assertEquals(new Rational(16, 9), PictureInPicture.shape(1920, 1080));
        assertEquals(new Rational(1, 1), PictureInPicture.shape(720, 720));
        assertSame(PictureInPicture.NARROWEST, PictureInPicture.shape(100, 1000));
        assertSame(PictureInPicture.WIDEST, PictureInPicture.shape(3000, 100));
        assertNull(PictureInPicture.shape(0, 1920));
        assertNull(PictureInPicture.shape(1080, -1));
    }

    @Test public void theVideoIsTheLargestSurfaceShowing() {
        assertSame(video, PictureInPicture.videoView(decor));
        video.setVisibility(View.INVISIBLE);
        assertSame("a hidden surface isn't the video", thumbnail, PictureInPicture.videoView(decor));
        thumbnail.setVisibility(View.GONE);
        assertNull(PictureInPicture.videoView(decor));
    }

    @Test public void theWindowShowsOnlyTheVideoAndEverythingComesBack() {
        PictureInPicture.setAside(decor);
        assertEquals(View.VISIBLE, video.getVisibility());
        assertEquals(View.VISIBLE, cell.getVisibility());
        assertEquals(View.INVISIBLE, caption.getVisibility());
        assertEquals(View.INVISIBLE, tabs.getVisibility());
        assertEquals(View.INVISIBLE, thumbnail.getVisibility());
        assertEquals("a view TikTok had hidden stays as it was", View.GONE, alreadyGone.getVisibility());

        PictureInPicture.bringBack();
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
        assertEquals(View.VISIBLE, thumbnail.getVisibility());
        assertEquals(View.GONE, alreadyGone.getVisibility());
    }

    /** What TikTok changed while the window was up is TikTok's: only views still set aside come back. */
    @Test public void bringingBackLeavesTikToksOwnChangesAlone() {
        PictureInPicture.setAside(decor);
        caption.setVisibility(View.GONE);
        PictureInPicture.bringBack();
        assertEquals(View.GONE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
    }

    /**
     * TikTok shows the cell's controls again after the window opens (S22, 47.1.4, 2026-10-08).
     * The next frame sets them aside again, and once the window closes frames leave them alone.
     */
    @Test public void whatTikTokShowsAgainInTheWindowIsSetAsideOnTheNextFrame() {
        PictureInPicture.keepAsideWhileUp(decor);
        assertEquals(View.INVISIBLE, caption.getVisibility());
        caption.setVisibility(View.VISIBLE);
        alreadyGone.setVisibility(View.VISIBLE);
        decor.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("shown again, set aside again", View.INVISIBLE, caption.getVisibility());
        assertEquals("one TikTok shows later too", View.INVISIBLE, alreadyGone.getVisibility());
        assertEquals(View.VISIBLE, video.getVisibility());

        PictureInPicture.bringBack();
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, alreadyGone.getVisibility());
        tabs.setVisibility(View.VISIBLE);
        decor.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("the window closed, so frames leave TikTok's views alone",
                View.VISIBLE, caption.getVisibility());
    }

    @Test public void nothingIsSetAsideWithoutAVideo() {
        video.setVisibility(View.GONE);
        thumbnail.setVisibility(View.GONE);
        PictureInPicture.setAside(decor);
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
    }

    /** A plain activity isn't a feed window, so leaving it opens nothing, switch on or off. */
    @Test public void leavingAnotherScreenOpensNothing() {
        Settings.PICTURE_IN_PICTURE.save(true);
        PictureInPicture.onUserLeaveHint(owner.get());
        assertFalse(owner.get().isInPictureInPictureMode());
        assertEquals(View.VISIBLE, tabs.getVisibility());
    }

    /** A window the extension didn't open, TikTok's LIVE one for instance, is left to TikTok. */
    @Test public void aWindowItDidntOpenIsLeftAlone() {
        Settings.PICTURE_IN_PICTURE.save(true);
        PictureInPicture.onModeChanged(owner.get(), true);
        assertEquals(View.VISIBLE, caption.getVisibility());
        assertEquals(View.VISIBLE, tabs.getVisibility());
        PictureInPicture.onModeChanged(null, true);
    }

    private static Intent sentBy(RemoteAction button) {
        return Shadows.shadowOf(button.getActionIntent()).getSavedIntent();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}
