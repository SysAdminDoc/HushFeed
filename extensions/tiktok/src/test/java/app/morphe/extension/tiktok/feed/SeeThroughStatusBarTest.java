package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * See-through status bar (#145): on the main feed the spacer TikTok keeps above each video for
 * the status bar goes and the bar's color is cleared, so the video reaches the top edge under the
 * clock. Off and Pause give both back exactly, Hide the status bar wins, and a video opened from
 * search or a profile goes up the same way (#407).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeeThroughStatusBarTest {
    private static final int CELL_ID = 0x7f0a0211;
    private static final int SPACER_ID = 0x7f0a0d31;
    private static final int BAND_ID = 0x7f0a0d32;
    private static final int PLAYER_ID = 0x7f0a5f5e;
    private static final int COVER_ID = 0x7f0a506c;
    private static final int OWN_BAR_COLOR = 0xFF121212;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.7.3:dvu", SPACER_ID);
        VideoOverlayHider.resolveForTests("47.7.3:wo4", BAND_ID);
        VideoOverlayHider.resolveForTests("player_view", PLAYER_ID);
        VideoOverlayHider.resolveForTests("cover", COVER_ID);
    }

    @After
    public void tearDown() {
        Settings.SEE_THROUGH_STATUS_BAR.resetToDefault();
        Settings.STATUS_BAR_SHADE.resetToDefault();
        Settings.SEE_THROUGH_BOTTOM_BAR.resetToDefault();
        SeeThroughBottomBar.reset();
        Settings.HIDE_STATUS_BAR.resetToDefault();
        Settings.FIT_VIDEO_TO_SCREEN.resetToDefault();
        SettingsStatus.videoFitEnabled = false;
        PausedProcess.set(false);
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.7.3:dvu", 0);
        VideoOverlayHider.resolveForTests("47.7.3:wo4", 0);
        VideoOverlayHider.resolveForTests("player_view", 0);
        VideoOverlayHider.resolveForTests("cover", 0);
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    private static void layOut(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    /** TikTok's player sizes the surface to the picture and centers it, the S22's numbers by default. */
    private static void showVideo(FrameLayout player, View surface, int width, int height) {
        surface.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        layOut(player, 1080, 2181);
    }

    /** A feed cell as 47.1.4 lays it out on a tall screen: the bar's spacer, then the video. */
    private static View feedCell(Activity activity, int spacerVisibility) {
        FrameLayout root = new FrameLayout(activity);
        FrameLayout cell = new FrameLayout(activity);
        cell.setId(CELL_ID);
        LinearLayout area = new LinearLayout(activity);
        area.setOrientation(LinearLayout.VERTICAL);
        View spacer = new View(activity);
        spacer.setId(SPACER_ID);
        spacer.setVisibility(spacerVisibility);
        area.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 75));
        area.addView(new View(activity), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        cell.addView(area);
        root.addView(cell);
        activity.setContentView(root);
        return spacer;
    }

    @Test
    public void theVideoGoesUpUnderTheBarAndOffGivesEverythingBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            int flags = activity.getWindow().getAttributes().flags;
            int cutout = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
            int systemUi = activity.getWindow().getDecorView().getSystemUiVisibility();

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", View.INVISIBLE, spacer.getVisibility());
            assertEquals(OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the strip above the video stayed", View.GONE, spacer.getVisibility());
            assertEquals("the bar wasn't made see through", Color.TRANSPARENT, activity.getWindow().getStatusBarColor());
            assertEquals("the window's flags changed", flags, activity.getWindow().getAttributes().flags);
            assertEquals(cutout, activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            assertEquals("the bar was hidden", systemUi, activity.getWindow().getDecorView().getSystemUiVisibility());

            // Every layout runs a pass; another changes nothing.
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());

            // TikTok showing the spacer or coloring the bar again on a page change.
            spacer.setVisibility(View.VISIBLE);
            activity.getWindow().setStatusBarColor(Color.BLACK);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't give the spacer back", View.VISIBLE, spacer.getVisibility());
            assertEquals("off didn't give TikTok's latest color back", Color.BLACK, activity.getWindow().getStatusBarColor());
            assertFalse(SeeThroughStatusBar.isCleared(activity));
            assertEquals(flags, activity.getWindow().getAttributes().flags);
            assertEquals(systemUi, activity.getWindow().getDecorView().getSystemUiVisibility());
        }
    }

    /**
     * The S22 kept a black strip under the clock with the switch on: the spacer was gone and the
     * video was up there, but TikTok's own band, as tall as the bar, lies over the top of each
     * cell. It goes with the spacer, invisible so it keeps its place, and comes back with it.
     */
    @Test
    public void theBlackBandOverTheTopOfTheCellGoesWithTheSpacer() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            View band = new View(activity);
            band.setId(BAND_ID);
            band.setBackgroundColor(Color.BLACK);
            cell.addView(band, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 75));

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", View.VISIBLE, band.getVisibility());

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals("the band still covered the top of the video", View.INVISIBLE, band.getVisibility());

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the band away", View.VISIBLE, band.getVisibility());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, band.getVisibility());

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't give the band back", View.VISIBLE, band.getVisibility());

            // Hide the status bar takes the band too: there's no bar left for it to sit under.
            Settings.HIDE_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, band.getVisibility());
        }
    }

    /**
     * With the spacer and the band gone the S22 still showed black under the clock: TikTok sizes
     * a 9:16 video's surface to the picture (1107 by 1968) and centers it in a 2181 px player. The
     * surface grows from its center until it covers the player. A landscape video and a squarer
     * portrait one keep their bands, and off, Pause and a zoom TikTok set itself are respected.
     */
    @Test
    public void aPortraitVideoGrowsToFillThePlayerUnderTheClock() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            showVideo(player, surface, 1107, 1968);
            assertEquals("not TikTok's centered surface", 106, surface.getTop());

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", 1f, surface.getScaleY(), 0f);

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            float scale = surface.getScaleY();
            assertEquals(scale, surface.getScaleX(), 0f);
            assertTrue("the picture still stopped short of the top", 1968 * scale >= 2181);
            assertTrue("grown past what the player needs", 1968 * scale <= 2181 + 3);

            // Each pass measures again, so the next video in the same cell gets its own answer.
            showVideo(player, surface, 1080, 608);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a landscape video was cropped", 1f, surface.getScaleY(), 0f);
            showVideo(player, surface, 1080, 1440);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a 3:4 video lost a third of its width", 1f, surface.getScaleY(), 0f);
            showVideo(player, surface, 1107, 1968);
            VideoOverlayHider.applyTo(activity);
            assertEquals(scale, surface.getScaleY(), 0f);

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the picture grown", 1f, surface.getScaleY(), 0f);
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(scale, surface.getScaleY(), 0f);

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the picture back", 1f, surface.getScaleX(), 0f);
            assertEquals(1f, surface.getScaleY(), 0f);
            assertFalse(SeeThroughStatusBar.anyFilled());

            surface.setScaleX(1.5f);
            surface.setScaleY(1.5f);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("TikTok's own zoom was overwritten", 1.5f, surface.getScaleY(), 0f);
        }
    }

    /**
     * #390: with the bars see through, the picture shrank to TikTok's size for four frames just
     * after a swipe, then grew again. TikTok writes 1 back on the scale as a video starts, and a
     * scale is no layout, so the fill waited for the next layout pass, which took the 1 for a zoom
     * of TikTok's own and let the picture go until the pass after. The picture and its cover are
     * grown again before the frame is drawn, and a zoom TikTok sets itself still stays.
     */
    @Test
    public void aScaleTikTokPutsBackIsGrownAgainBeforeTheFrameIsDrawn() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            View cover = new View(activity);
            cover.setId(COVER_ID);
            cell.addView(cover, new FrameLayout.LayoutParams(1080, 2181));
            showVideo(player, surface, 1107, 1968);
            layOut(cover, 1080, 2181);

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            float scale = surface.getScaleY();
            assertTrue("the picture didn't fill", scale > 1f);
            assertEquals(scale, cover.getScaleY(), 0f);

            // TikTok's reset with no layout after it: the next frame is drawn straight away.
            surface.setScaleX(1f);
            surface.setScaleY(1f);
            cover.setScaleX(1f);
            cover.setScaleY(1f);
            surface.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("the picture was drawn at TikTok's size", scale, surface.getScaleY(), 0f);
            assertEquals(scale, surface.getScaleX(), 0f);
            assertEquals("the cover was drawn at TikTok's size", scale, cover.getScaleY(), 0f);

            // A layout pass right after the reset grows it again too, rather than letting it go.
            surface.setScaleX(1f);
            surface.setScaleY(1f);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the pass let the picture go", scale, surface.getScaleY(), 0f);
            assertEquals(scale, cover.getScaleY(), 0f);

            surface.setScaleX(1.5f);
            surface.setScaleY(1.5f);
            surface.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("TikTok's own zoom was overwritten before the frame", 1.5f, surface.getScaleY(), 0f);
            VideoOverlayHider.applyTo(activity);
            assertEquals("TikTok's own zoom was overwritten", 1.5f, surface.getScaleY(), 0f);
        }
    }

    /**
     * #390 on the S22: as a video starts TikTok lays the player out 138 px short for one pass and
     * back to full height with no pass after it, so the picture kept the scale worked out for the
     * short player and was drawn 6% small for two to four frames, then grew. The pre-draw pass
     * fills it again for the size the player has when the frame is drawn.
     */
    @Test
    public void aPlayerResizedWithNoLayoutPassIsFilledForItsNewSizeBeforeTheFrame() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            View cover = new View(activity);
            cover.setId(COVER_ID);
            cell.addView(cover, new FrameLayout.LayoutParams(1080, 2181));
            surface.setLayoutParams(new FrameLayout.LayoutParams(1107, 1968, Gravity.CENTER));
            layOut(player, 1080, 2043);
            layOut(cover, 1080, 2181);

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the short player wasn't filled", (2043 + 2f) / 1968, surface.getScaleY(), 1e-5f);

            // Back to full height, and the next frame is drawn with no layout pass first.
            layOut(player, 1080, 2181);
            surface.getViewTreeObserver().dispatchOnPreDraw();
            float full = (2181 + 2f) / 1968;
            assertEquals("the picture was drawn grown for the short player", full, surface.getScaleY(), 1e-5f);
            assertEquals(full, surface.getScaleX(), 1e-5f);
            assertEquals("the cover was drawn grown for the short player", full, cover.getScaleY(), 1e-5f);

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            surface.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("the frame pass grew a picture the switch put back", 1f, surface.getScaleY(), 0f);
            assertEquals(1f, cover.getScaleY(), 0f);
        }
    }

    /**
     * #218: with only See-through bottom bar on, the strip under the video goes and the player
     * reaches the bottom edge, but TikTok centers the 9:16 picture in it, so the bottom of the
     * floating bar sat over black. The bottom bar asks for the same fill; off puts it back.
     */
    @Test
    public void aPortraitVideoReachesTheBottomWithOnlyTheBottomBarSeeThrough() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            showVideo(player, surface, 1107, 1968);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            float scale = surface.getScaleY();
            assertTrue("the picture still stopped short of the bottom", 1968 * scale >= 2181);
            assertTrue("grown past what the player needs", 1968 * scale <= 2181 + 3);
            assertFalse("the status bar was made see through", SeeThroughStatusBar.isCleared(activity));

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the picture back", 1f, surface.getScaleY(), 0f);
            assertFalse(SeeThroughStatusBar.anyFilled());
        }
    }

    /**
     * 47.6.3 on the S22: the surface is 1080 by 1920, centered in the 2199 px player by its layout
     * (139 above, 140 below), and TikTok lifts it 70.5 px with its translation, half the band of
     * the 2061 px player it had before the bar's room was freed. Grown from its center the picture
     * ended at 2129 of 2199 and the tabs sat on black. It grows from the point that covers both
     * edges, with the same scale, and off gives the center back.
     */
    @Test
    public void aSurfaceTikTokLiftedCoversThePlayerToTheBottom() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface, new FrameLayout.LayoutParams(1080, 1920, Gravity.CENTER));
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2199));
            layOut(player, 1080, 2199);
            surface.setTranslationY(-70.5f);
            assertEquals("not the S22's centered surface", 139, surface.getTop());

            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            float scale = surface.getScaleY();
            float top = surface.getTop() + surface.getTranslationY() + surface.getPivotY() * (1f - scale);
            float bottom = top + 1920 * scale;
            assertTrue("the picture stopped short of the top: " + top, top <= 0f);
            assertTrue("the picture stopped short of the bottom: " + bottom, bottom >= 2199f);
            assertTrue("grown past what the player needs: " + scale, 1920 * scale <= 2199 + 3);
            assertEquals("the sides grow from the middle", 540f, surface.getPivotX(), 0f);
            assertEquals("TikTok's lift was changed", -70.5f, surface.getTranslationY(), 0f);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the picture back", 1f, surface.getScaleY(), 0f);
            // A reset pivot follows the view's center again; Robolectric's RenderNode reads 0 for
            // it rather than working the center out, so this asks whether one is still set.
            assertFalse("off kept the moved pivot", surface.isPivotSet());
            assertFalse(SeeThroughStatusBar.anyFilled());
        }
    }

    /**
     * The cover still beside that lifted surface lands on the grown picture. The still sits in the
     * middle of its cell-sized cover and the picture grows from {@code fillPivot}, so a cover grown
     * from its center left the still 70 px off the picture when TikTok lifted the cover with the
     * surface. A cover TikTok left in place keeps its center, and so does one beside a centered surface.
     */
    @Test
    public void theCoverStillLandsOnALiftedPicture() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface, new FrameLayout.LayoutParams(1080, 1920, Gravity.CENTER));
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2199));
            FrameLayout wrapper = new FrameLayout(activity);
            View cover = new View(activity);
            cover.setId(COVER_ID);
            wrapper.addView(cover, new FrameLayout.LayoutParams(1080, 2199));
            cell.addView(wrapper, new FrameLayout.LayoutParams(1080, 2199));
            layOut(player, 1080, 2199);
            layOut(wrapper, 1080, 2199);
            surface.setTranslationY(-70.5f);
            cover.setTranslationY(-70.5f);
            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);

            VideoOverlayHider.applyTo(activity);
            assertTheStillIsOnThePicture(surface, cover, cell);

            // Lifted one view up: TikTok moving the cover's wrapper rather than the cover itself.
            cover.setTranslationY(0f);
            wrapper.setTranslationY(-70.5f);
            VideoOverlayHider.applyTo(activity);
            assertTheStillIsOnThePicture(surface, cover, cell);

            wrapper.setTranslationY(0f);
            VideoOverlayHider.applyTo(activity);
            assertTheStillIsOnThePicture(surface, cover, cell);
            assertFalse("a cover in place left its center", cover.isPivotSet());

            surface.setTranslationY(0f);
            VideoOverlayHider.applyTo(activity);
            assertTheStillIsOnThePicture(surface, cover, cell);
            assertFalse("the cover by a centered picture left its center", cover.isPivotSet());

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the cover back", 1f, cover.getScaleY(), 0f);
            assertFalse(SeeThroughStatusBar.anyFilled());
        }
    }

    /** The still, as tall as the picture and fitted in the middle of its cover, is drawn where the grown picture is in {@code space}. */
    private static void assertTheStillIsOnThePicture(View picture, View cover, View space) {
        float scale = picture.getScaleY();
        assertTrue("the picture didn't fill", scale > 1f);
        assertEquals("the cover kept TikTok's size", scale, cover.getScaleY(), 0f);
        float top = above(picture, space) + pivotY(picture) * (1f - scale);
        float pivot = pivotY(cover);
        float inside = (cover.getHeight() - picture.getHeight()) / 2f;
        float still = above(cover, space) + pivot + scale * (inside - pivot);
        // Same height, same scale: tops that meet put the bottoms together too.
        assertEquals("the still is off the picture", top, still, 2f);
    }

    /** How far down {@code space} the unscaled top of {@code view} sits, its own place and every view's between. */
    private static float above(View view, View space) {
        float top = 0f;
        for (View at = view; at != space; at = (View) at.getParent()) {
            top += at.getTop() + at.getTranslationY();
        }
        return top;
    }

    /** Robolectric reads 0 for a pivot nothing set, where Android follows the view's center. */
    private static float pivotY(View view) {
        return view.isPivotSet() ? view.getPivotY() : view.getHeight() / 2f;
    }

    /**
     * #164: only the first video after a cold start filled. That one plays in the surface TikTok
     * creates at launch inside the player. Later cells can get TikTok's SurfaceControl player,
     * whose view is the player itself: TikTok sizes the player to the picture and places it with
     * its translation, and nothing inside it is a surface. The player grows in its space then, and
     * goes back with the switch.
     */
    @Test
    public void aLaterVideoInTikToksSurfaceControlPlayerFillsToo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            // The first video: TikTok's launch surface inside the player.
            FrameLayout firstCell = new FrameLayout(activity);
            firstCell.setId(CELL_ID);
            FrameLayout firstPlayer = new FrameLayout(activity);
            firstPlayer.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            firstPlayer.addView(surface);
            firstCell.addView(firstPlayer, new FrameLayout.LayoutParams(1080, 2181));
            root.addView(firstCell);
            // The next one: the player is the picture, 1107 by 1968, moved down by TikTok.
            FrameLayout nextCell = new FrameLayout(activity);
            nextCell.setId(CELL_ID);
            FrameLayout space = new FrameLayout(activity);
            FrameLayout nextPlayer = new FrameLayout(activity);
            nextPlayer.setId(PLAYER_ID);
            nextPlayer.addView(new View(activity), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            space.addView(nextPlayer, new FrameLayout.LayoutParams(1107, 1968));
            nextCell.addView(space, new FrameLayout.LayoutParams(1080, 2181));
            root.addView(nextCell);
            activity.setContentView(root);
            showVideo(firstPlayer, surface, 1107, 1968);
            layOut(space, 1080, 2181);
            nextPlayer.setTranslationX(-13f);
            nextPlayer.setTranslationY(106f);

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue("the first video stopped short of the top", 1968 * surface.getScaleY() >= 2181);
            float scale = nextPlayer.getScaleY();
            assertEquals(scale, nextPlayer.getScaleX(), 0f);
            float center = 106f + 1968 / 2f;
            assertTrue("the next video stopped short of the top: " + scale, center - 1968 * scale / 2f <= 0f);
            assertTrue("the next video stopped short of the bottom: " + scale, center + 1968 * scale / 2f >= 2181f);
            assertTrue("grown past what the space needs: " + scale, scale <= SeeThroughStatusBar.MAX_FILL_SCALE);

            // A wide picture in that player keeps its bands.
            nextPlayer.setLayoutParams(new FrameLayout.LayoutParams(1080, 608));
            layOut(space, 1080, 2181);
            nextPlayer.setTranslationY(786f);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a landscape video was cropped", 1f, nextPlayer.getScaleY(), 0f);

            nextPlayer.setLayoutParams(new FrameLayout.LayoutParams(1107, 1968));
            layOut(space, 1080, 2181);
            nextPlayer.setTranslationY(106f);
            VideoOverlayHider.applyTo(activity);
            assertEquals(scale, nextPlayer.getScaleY(), 0f);

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the player back", 1f, nextPlayer.getScaleX(), 0f);
            assertEquals(1f, nextPlayer.getScaleY(), 0f);
            assertEquals(1f, surface.getScaleY(), 0f);
            assertFalse(SeeThroughStatusBar.anyFilled());
        }
    }

    /**
     * #232: TikTok flashes the video's cover still between videos, drawn by a view beside the
     * player (one wrapper down in the feed cell, a plain sibling in the standalone cell), and the
     * still was never grown, so every swipe showed the bands for a frame. The cover takes the
     * picture's scale in both shapes, a scale TikTok wrote on it is left alone, and off puts it back.
     */
    @Test
    public void theCoverStillGrowsWithThePicture() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            // The feed cell: the player and the cover's wrapper side by side in the visible area.
            FrameLayout feedCell = new FrameLayout(activity);
            feedCell.setId(CELL_ID);
            FrameLayout visibleArea = new FrameLayout(activity);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            visibleArea.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            FrameLayout wrapper = new FrameLayout(activity);
            View cover = new View(activity);
            cover.setId(COVER_ID);
            cover.setVisibility(View.GONE);
            wrapper.addView(cover, new FrameLayout.LayoutParams(1080, 2181));
            visibleArea.addView(wrapper, new FrameLayout.LayoutParams(1080, 2181));
            feedCell.addView(visibleArea, new FrameLayout.LayoutParams(1080, 2181));
            root.addView(feedCell);
            // A SurfaceControl cell: the player is the picture, and the cover its plain sibling.
            FrameLayout nextCell = new FrameLayout(activity);
            nextCell.setId(CELL_ID);
            FrameLayout space = new FrameLayout(activity);
            FrameLayout nextPlayer = new FrameLayout(activity);
            nextPlayer.setId(PLAYER_ID);
            nextPlayer.addView(new View(activity), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            space.addView(nextPlayer, new FrameLayout.LayoutParams(1107, 1968));
            View nextCover = new View(activity);
            nextCover.setId(COVER_ID);
            space.addView(nextCover, new FrameLayout.LayoutParams(1080, 2181));
            nextCell.addView(space, new FrameLayout.LayoutParams(1080, 2181));
            root.addView(nextCell);
            activity.setContentView(root);
            showVideo(player, surface, 1107, 1968);
            layOut(space, 1080, 2181);
            nextPlayer.setTranslationY(106f);

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            float scale = surface.getScaleY();
            assertTrue("the picture didn't fill", scale > 1f);
            assertEquals("the feed cell's cover kept TikTok's size", scale, cover.getScaleY(), 0f);
            assertEquals(scale, cover.getScaleX(), 0f);
            float nextScale = nextPlayer.getScaleY();
            assertTrue("the next picture didn't fill", nextScale > 1f);
            assertEquals("the sibling cover kept TikTok's size", nextScale, nextCover.getScaleY(), 0f);

            // A scale TikTok wrote on the cover stays, and comes back under this one's care only
            // once TikTok puts it back.
            nextCover.setScaleX(1.5f);
            nextCover.setScaleY(1.5f);
            VideoOverlayHider.applyTo(activity);
            assertEquals("TikTok's own scale on the cover was overwritten", 1.5f, nextCover.getScaleY(), 0f);
            nextCover.setScaleX(1f);
            nextCover.setScaleY(1f);
            VideoOverlayHider.applyTo(activity);
            assertEquals(nextScale, nextCover.getScaleY(), 0f);

            // TikTok places that player for the space it had before the spacer went, so it can sit
            // low, and the still beside it lands where the grown player does.
            nextPlayer.setTranslationY(120f);
            VideoOverlayHider.applyTo(activity);
            assertTheStillIsOnThePicture(nextPlayer, nextCover, space);

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't put the feed cell's cover back", 1f, cover.getScaleY(), 0f);
            assertEquals("off didn't put the sibling cover back", 1f, nextCover.getScaleY(), 0f);
            assertEquals(1f, surface.getScaleY(), 0f);
            assertEquals(1f, nextPlayer.getScaleY(), 0f);
            assertFalse(SeeThroughStatusBar.anyFilled());
        }
    }

    /**
     * Fit the video to the screen shrinks a picture until all of it shows, and growing it back to
     * cover the player would undo that, so Fit wins while both are on.
     */
    @Test
    public void fitTheVideoToTheScreenWinsOverTheFill() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            FrameLayout player = new FrameLayout(activity);
            player.setId(PLAYER_ID);
            SurfaceView surface = new SurfaceView(activity);
            player.addView(surface);
            cell.addView(player, new FrameLayout.LayoutParams(1080, 2181));
            // What Fit leaves a 9:16 video at in this player: the whole width, 1920 tall.
            showVideo(player, surface, 1080, 1920);
            SettingsStatus.videoFitEnabled = true;
            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);

            VideoOverlayHider.applyTo(activity);
            assertEquals("the fitted video was grown back over its sides", 1f, surface.getScaleY(), 0f);

            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue("with Fit off the video didn't fill", 1920 * surface.getScaleY() >= 2181);

            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Fit coming back on didn't put the video back", 1f, surface.getScaleY(), 0f);
        }
    }

    @Test
    public void pauseShowsTheFeedAsTikTokDrawsIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertTrue(SeeThroughStatusBar.isCleared(activity));

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the strip away", View.INVISIBLE, spacer.getVisibility());
            assertEquals("Pause kept the bar clear", OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());

            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());
        }
    }

    @Test
    public void hidingTheStatusBarWinsAndLeavesTheColorAlone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.VISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            Settings.HIDE_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the hidden bar's spacer stayed", View.GONE, spacer.getVisibility());
            assertEquals("the hidden bar's color was changed", OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());
            assertFalse(SeeThroughStatusBar.isCleared(activity));
            Settings.HIDE_STATUS_BAR.save(false);
            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, spacer.getVisibility());
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT, attributes.layoutInDisplayCutoutMode);
        }
    }

    /**
     * A video opened from search, a profile or the Liked and Favorites grids keeps the same spacer
     * and band in its cell as the feed (Login2, 2026-11-13: dvu and wo4 at 0 to 136 in every detail
     * cell), so it goes up under the clock too, and off gives it all back (#407).
     */
    @Test
    public void aVideoOpenedFromSearchOrAProfileGoesUpUnderTheBarToo() {
        try (var controller = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            View band = new View(activity);
            band.setId(BAND_ID);
            band.setBackgroundColor(Color.BLACK);
            cell.addView(band, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 75));
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("an opened video kept its strip", View.GONE, spacer.getVisibility());
            assertEquals("the band still covered the opened video's top", View.INVISIBLE, band.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, spacer.getVisibility());
            assertEquals(View.VISIBLE, band.getVisibility());
            assertEquals(OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());
        }
    }

    /**
     * Shade behind the status bar (#407): a fade over the top of the window, twice the bar's height
     * and as wide as the window, dark at the top and clear at the bottom, while the bar is see
     * through. It needs See-through status bar, goes with Pause and with its own switch, and the
     * window keeps one fade however many passes run.
     */
    @Test
    public void theShadeLiesBehindTheClockOnlyWhileTheBarIsSeeThrough() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            feedCell(activity, View.INVISIBLE);
            View decor = activity.getWindow().getDecorView();
            layOut(decor, 1080, 2340);
            Settings.STATUS_BAR_SHADE.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the shade showed without See-through status bar", null, SeeThroughStatusBar.shadeOf(activity));

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            android.graphics.drawable.Drawable shade = SeeThroughStatusBar.shadeOf(activity);
            assertTrue("no shade behind the clock", shade instanceof android.graphics.drawable.GradientDrawable);
            assertTrue("the shade isn't in the window's overlay", shade.getCallback() != null);
            int bar = SeeThroughStatusBar.statusBarHeight(decor);
            assertTrue(bar > 0);
            assertEquals(new android.graphics.Rect(0, 0, 1080, 2 * bar), shade.getBounds());
            int[] colors = ((android.graphics.drawable.GradientDrawable) shade).getColors();
            assertEquals("the top isn't dark", 0x80, Color.alpha(colors[0]));
            assertEquals("the bottom isn't clear", 0, Color.alpha(colors[colors.length - 1]));

            VideoOverlayHider.applyTo(activity);
            assertTrue("another pass made a second shade", shade == SeeThroughStatusBar.shadeOf(activity));

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the shade", null, SeeThroughStatusBar.shadeOf(activity));
            assertEquals("the shade stayed drawn after Pause", null, shade.getCallback());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(SeeThroughStatusBar.shadeOf(activity) != null);

            Settings.STATUS_BAR_SHADE.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the shade's switch didn't take it off", null, SeeThroughStatusBar.shadeOf(activity));
        } finally {
            Settings.STATUS_BAR_SHADE.resetToDefault();
        }
    }
}
