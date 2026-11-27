/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hushfeed's own notices (#545): a swipe left or right puts one away, Show notices at the top
 * hangs them a gap under the status bar, and Blur behind notices puts the blurred video behind
 * them on Android 12 and up, the plain dark chip everywhere else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NoticesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private GlassVideoBlur.Finder realFinder;

    /** A root whose place on the screen and whose bars the test sets. */
    private static final class Root extends FrameLayout {
        int screenTop;
        WindowInsets insets;

        Root(Activity activity) {
            super(activity);
        }

        @Override public WindowInsets getRootWindowInsets() {
            return insets;
        }

        @Override public void getLocationOnScreen(int[] out) {
            out[0] = 0;
            out[1] = screenTop;
        }
    }

    /** A notice whose window focus the test sets, since Robolectric never gives a window focus. */
    private static final class Focused extends FrameLayout {
        Focused(Activity activity) {
            super(activity);
        }

        @Override public boolean hasWindowFocus() {
            return true;
        }
    }

    /** A video under the whole window that records what it is asked to read. */
    private static final class Video implements GlassVideoBlur.Source {
        int reads;

        @Override public Rect bounds() {
            return new Rect(0, 0, 1080, 2400);
        }

        @Override public int bufferWidth() {
            return 1080;
        }

        @Override public int bufferHeight() {
            return 2400;
        }

        @Override public void read(Rect from, Bitmap into, GlassVideoBlur.Done done) {
            reads++;
            done.done(true);
        }

        @Override public boolean current() {
            return true;
        }
    }

    @Before public void setUp() {
        realFinder = GlassVideoBlur.finder;
    }

    @After public void tearDown() {
        GlassVideoBlur.finder = realFinder;
        Settings.NOTICES_AT_TOP.save(false);
        Settings.NOTICE_BLUR.save(false);
        idle();
        Utils.setActivity(null);
    }

    @Test public void bothSwitchesStartOffAndNeedNoRestart() {
        assertEquals("notices_at_top", Settings.NOTICES_AT_TOP.key);
        assertEquals("notice_blur", Settings.NOTICE_BLUR.key);
        assertFalse(Settings.NOTICES_AT_TOP.defaultValue);
        assertFalse(Settings.NOTICE_BLUR.defaultValue);
        assertFalse("each notice reads it as it shows", Settings.NOTICES_AT_TOP.rebootApp);
        assertFalse("each notice reads it as it shows", Settings.NOTICE_BLUR.rebootApp);
    }

    @Test public void atTheTopANoticeHangsAGapUnderTheStatusBar() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Root root = new Root(activity);
            root.insets = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 63, 0, 48)).build();
            int gap = SettingsUi.dp(activity, 16);

            FrameLayout.LayoutParams bottom = BlockAuthorOverlay.noticeParams(activity, root);
            FrameLayout.LayoutParams banner = BlockAuthorOverlay.bannerParams(activity, root);
            assertEquals("off, a notice goes where the banner always went",
                    banner.gravity, bottom.gravity);
            assertEquals(banner.bottomMargin, bottom.bottomMargin);
            assertEquals(Gravity.BOTTOM, bottom.gravity & Gravity.VERTICAL_GRAVITY_MASK);

            Settings.NOTICES_AT_TOP.save(true);
            FrameLayout.LayoutParams top = BlockAuthorOverlay.noticeParams(activity, root);
            assertEquals(Gravity.TOP | Gravity.CENTER_HORIZONTAL, top.gravity);
            assertEquals("a gap under the status bar", 63 + gap, top.topMargin);
            assertEquals(0, top.bottomMargin);
            assertEquals("the sides keep the banner's gap", gap, top.leftMargin);
            assertEquals(gap, top.rightMargin);

            root.screenTop = 20;
            assertEquals("a root that starts partway under the bar clears the rest of it",
                    43 + gap, BlockAuthorOverlay.noticeParams(activity, root).topMargin);
            root.screenTop = 900;
            assertEquals("a sheet that starts below the bar gets just the gap",
                    gap, BlockAuthorOverlay.noticeParams(activity, root).topMargin);

            assertEquals("the bars a run of picks or deletes shows stay at the bottom",
                    Gravity.BOTTOM, BlockAuthorOverlay.bannerParams(activity, root).gravity
                            & Gravity.VERTICAL_GRAVITY_MASK);
        }
    }

    @Test public void theBannerTakesTheTopWhenTheSwitchIsOn() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setActivity(activity);
            Root sheet = new Root(activity);
            sheet.insets = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 63, 0, 48)).build();
            Settings.NOTICES_AT_TOP.save(true);
            BlockAuthorOverlay.showUndoBanner(sheet, "Blocked someone", () -> { });
            idle();
            View banner = bannerIn(sheet);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) banner.getLayoutParams();
            assertEquals(Gravity.TOP | Gravity.CENTER_HORIZONTAL, params.gravity);
            assertEquals(63 + SettingsUi.dp(activity, 16), params.topMargin);
        }
    }

    @Test public void aSwipeEitherWayPutsTheBannerAwayAndCountsAsRead() {
        for (int direction : new int[]{1, -1}) {
            try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
                Activity activity = controller.get();
                Utils.setActivity(activity);
                ViewGroup root = activity.findViewById(android.R.id.content);
                AtomicInteger read = new AtomicInteger();
                BlockAuthorOverlay.showNoticeBanner(root, "Saved to your gallery", read::incrementAndGet);
                idle();
                View banner = laidOutBannerIn(root);
                swipe(banner, banner.getWidth() / 4f, direction * banner.getWidth() / 2f, 10, 16);
                assertEquals("it follows the finger before it goes", direction > 0,
                        banner.getTranslationX() > 0);
                settleSlide();
                assertNull("the swipe left it on the screen", banner.getParent());
                assertEquals("a swipe is a read", 1, read.get());
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
                assertEquals("and its timer doesn't count it again", 1, read.get());
            }
        }
    }

    @Test public void aShortSlowDragSlidesBackAndAQuickFlickStillGoes() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            AtomicInteger read = new AtomicInteger();
            BlockAuthorOverlay.showNoticeBanner(root, "Saved to your gallery", read::incrementAndGet);
            idle();
            View banner = laidOutBannerIn(root);
            float little = banner.getWidth() / 6f;
            swipe(banner, banner.getWidth() / 4f, little, 10, 50);
            settleSlide();
            assertSame("a short slow drag let it go", root, banner.getParent());
            assertEquals("it slid back", 0f, banner.getTranslationX(), 0.5f);
            assertEquals(1f, banner.getAlpha(), 0.01f);
            assertEquals(0, read.get());

            swipe(banner, banner.getWidth() / 4f, little, 2, 8);
            settleSlide();
            assertNull("a quick flick goes however short", banner.getParent());
            assertEquals(1, read.get());
        }
    }

    @Test public void aDragFromUndoIsTheSwipeAndATapOnItIsStillUndo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            AtomicInteger undone = new AtomicInteger();

            BlockAuthorOverlay.showUndoBanner(root, "Blocked someone", undone::incrementAndGet);
            idle();
            ViewGroup banner = (ViewGroup) laidOutBannerIn(root);
            View undo = banner.getChildAt(banner.getChildCount() - 1);
            float x = undo.getLeft() + undo.getWidth() / 2f;
            float y = undo.getTop() + undo.getHeight() / 2f;
            long down = SystemClock.uptimeMillis();
            send(banner, down, down, MotionEvent.ACTION_DOWN, x, y);
            send(banner, down, down + 40, MotionEvent.ACTION_UP, x, y);
            idle();
            assertEquals("a tap on Undo is Undo", 1, undone.get());
            assertNull(banner.getParent());

            BlockAuthorOverlay.showUndoBanner(root, "Blocked someone", undone::incrementAndGet);
            idle();
            banner = (ViewGroup) laidOutBannerIn(root);
            undo = banner.getChildAt(banner.getChildCount() - 1);
            swipe(banner, undo.getLeft() + undo.getWidth() / 2f, -banner.getWidth() / 2f, 10, 16);
            settleSlide();
            assertNull("a drag that starts on Undo still puts the banner away", banner.getParent());
            assertEquals("and doesn't undo the block", 1, undone.get());
        }
    }

    @Test public void talkBackOffersDismissAndItPutsTheNoticeAway() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            AtomicInteger read = new AtomicInteger();
            BlockAuthorOverlay.showNoticeBanner(root, "Saved to your gallery", read::incrementAndGet);
            idle();
            View banner = bannerIn(root);
            AccessibilityNodeInfo info = banner.createAccessibilityNodeInfo();
            assertTrue(info.isDismissable());
            assertTrue(info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS));
            assertTrue(banner.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null));
            idle();
            assertNull(banner.getParent());
            assertEquals(1, read.get());
        }
    }

    @Test public void withBlurOnTheVideoBehindANoticeIsReadAndLetGoWhenItLeaves() {
        Video video = new Video();
        GlassVideoBlur.finder = bar -> video;
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            Focused notice = new Focused(activity);
            root.addView(notice, new FrameLayout.LayoutParams(600, 120));
            activity.setContentView(root);
            layOut(root, 1080, 2400);

            Settings.NOTICE_BLUR.save(true);
            Notices.dress(notice);
            Drawable background = notice.getBackground();
            assertTrue("the notice isn't on the glass: " + background.getClass().getSimpleName(),
                    background instanceof Notices.Glass);
            Notices.Glass glass = (Notices.Glass) background;
            assertTrue(glass.blur.on());
            glass.setBounds(0, 0, 600, 120);
            glass.draw(new Canvas(Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888)));
            assertTrue("drawing starts the loop", glass.blur.sampling());
            assertTrue(glass.blur.watchingWindow());
            glass.blur.step(1000);
            assertEquals("the strip behind the notice was read", 1, video.reads);
            assertTrue(glass.blur.hasFrame());
            glass.draw(new Canvas(Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888)));
            assertFalse("a software canvas keeps the plain chip", glass.frosted());

            root.removeView(notice);
            assertFalse("a notice gone lets go of the window", glass.blur.watchingWindow());
            assertFalse("and of its picture", glass.blur.hasFrame());
        }
    }

    @Test public void blurOffIsThePlainChip() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View notice = new View(controller.get());
            Notices.dress(notice);
            assertPlainChip(controller.get(), notice);
        }
    }

    @Test @Config(sdk = 28)
    public void belowAndroid12BlurOnIsStillThePlainChip() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Settings.NOTICE_BLUR.save(true);
            View notice = new View(controller.get());
            Notices.dress(notice);
            assertPlainChip(controller.get(), notice);
        }
    }

    private static void assertPlainChip(Activity activity, View notice) {
        Drawable background = notice.getBackground();
        assertTrue("not the overlay family's chip: " + background.getClass().getSimpleName(),
                background instanceof GradientDrawable);
        GradientDrawable chip = (GradientDrawable) background;
        assertNotNull(chip.getColor());
        assertEquals(SettingsUi.OVERLAY_BANNER_SCRIM, chip.getColor().getDefaultColor());
        assertEquals(SettingsUi.dp(activity, SettingsUi.RADIUS_OVERLAY), chip.getCornerRadius(), 0.5f);
    }

    /**
     * Drags across a notice from {@code fromX} by {@code dx} in {@code steps} moves {@code stepMs}
     * apart, then lets go, all at its middle height, in its own coordinates.
     */
    public static void swipe(View notice, float fromX, float dx, int steps, long stepMs) {
        float y = notice.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        send(notice, down, down, MotionEvent.ACTION_DOWN, fromX, y);
        for (int step = 1; step <= steps; step++) {
            send(notice, down, down + step * stepMs, MotionEvent.ACTION_MOVE, fromX + dx * step / steps, y);
        }
        send(notice, down, down + steps * stepMs, MotionEvent.ACTION_UP, fromX + dx, y);
    }

    /** Lets a slide off, or back, run to its end. */
    public static void settleSlide() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    /** Measures and lays out a view at this size, the way its window would. */
    public static void layOut(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void send(View view, long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private static View bannerIn(ViewGroup root) {
        for (int index = root.getChildCount() - 1; index >= 0; index--) {
            if (root.getChildAt(index) instanceof Notices.SwipeRow) return root.getChildAt(index);
        }
        throw new AssertionError("no notice in the root");
    }

    private static View laidOutBannerIn(ViewGroup root) {
        layOut(root, 1080, 2400);
        View banner = bannerIn(root);
        assertTrue("the notice has no width to swipe across", banner.getWidth() > 0);
        return banner;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
