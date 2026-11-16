package app.morphe.extension.tiktok.speed;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A held video's pull stays with TikTok's lock (#422): once the hold speeds the video up, the feed
 * around it no longer takes the finger's moves as a scroll.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HoldPullGuardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Takes every move, the way the feed's pager takes a pull once it passes its slop. */
    private static final class Feed extends FrameLayout {
        Feed(Activity activity) {
            super(activity);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return event.getActionMasked() == MotionEvent.ACTION_MOVE;
        }
    }

    private final List<Integer> heard = new ArrayList<>();
    private Feed feed;
    private long downTime;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        feed = new Feed(activity);
        View video = new View(activity);
        feed.addView(video, new FrameLayout.LayoutParams(1080, 2000));
        feed.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY));
        feed.layout(0, 0, 1080, 2000);
        // Stands in for TikTok's hold listener with the hook at its top.
        video.setOnTouchListener((view, event) -> {
            HoldPullGuard.onHoldTouch(view, event);
            heard.add(event.getActionMasked());
            return true;
        });
        HoldPullGuard.resetForTests();
        SettingsStatus.holdSpeedSlideEnabled = true;
    }

    @After
    public void tearDown() {
        HoldPullGuard.resetForTests();
        Settings.ENABLE_LONG_PRESS_SPEED_LOCK.resetToDefault();
        Settings.HOLD_SPEED_SLIDE.resetToDefault();
        SettingsStatus.holdSpeedSlideEnabled = false;
    }

    @Test
    public void aPullDownOnAHeldVideoReachesTheLockWithTheLockOn() {
        Settings.ENABLE_LONG_PRESS_SPEED_LOCK.save(true);
        holdThenPull();
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP), heard);
    }

    @Test
    public void theSlideKeepsItsMovesWithTheLockOff() {
        Settings.HOLD_SPEED_SLIDE.save(true);
        holdThenPull();
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP), heard);
    }

    @Test
    public void withBothOffTheFeedStillTakesThePull() {
        holdThenPull();
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), heard);
    }

    @Test
    public void theNextSwipeScrollsTheFeedAgain() {
        Settings.ENABLE_LONG_PRESS_SPEED_LOCK.save(true);
        holdThenPull();
        heard.clear();
        send(MotionEvent.ACTION_DOWN, 120, 900);
        send(MotionEvent.ACTION_MOVE, 120, 700);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), heard);
    }

    private void holdThenPull() {
        send(MotionEvent.ACTION_DOWN, 120, 900);
        // The hold's press runs once the finger has rested long enough.
        HoldPullGuard.onSpeedUp();
        send(MotionEvent.ACTION_MOVE, 120, 1040);
        send(MotionEvent.ACTION_MOVE, 120, 1280);
        if (heard.get(heard.size() - 1) != MotionEvent.ACTION_CANCEL) send(MotionEvent.ACTION_UP, 120, 1280);
    }

    private void send(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
        feed.dispatchTouchEvent(event);
        event.recycle();
    }
}
