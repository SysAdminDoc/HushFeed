package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.interaction.AutoAdvance;
import app.morphe.extension.tiktok.interaction.DetailAutoAdvance;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

/** Issue #311: a video opened from a profile moves on at its end, where the switch allows it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DetailAutoAdvanceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<? extends Activity> owner;
    private VerticalViewPager pager;

    @Before public void setup() {
        CurrentVideoAuthor.resetForTests();
        Settings.AUTO_ADVANCE.save(true);
        open(DetailActivity.class, new Intent());
        select("clip");
    }

    private void open(Class<? extends Activity> page, Intent intent) {
        owner = Robolectric.buildActivity(page, intent).setup().visible().windowFocusChanged(true);
        Utils.setContext(owner.get());
        Utils.setActivity(owner.get());
        pager = new VerticalViewPager(owner.get());
        owner.get().setContentView(pager);
        View root = pager.getRootView();
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
    }

    private void select(String clip) {
        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params(clip, "creator"));
        CurrentVideoAuthor.onPlaying(clip);
    }

    private void ended(String clip) {
        DetailAutoAdvance.onPlayCompleted(clip);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void cleanup() {
        PausedProcess.set(false);
        Settings.AUTO_ADVANCE.resetToDefault();
        Settings.AUTO_ADVANCE_LIMIT.resetToDefault();
        Settings.AUTO_ADVANCE_SEARCH.resetToDefault();
        Settings.BACKGROUND_PLAY.resetToDefault();
        CurrentVideoAuthor.resetForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Utils.setActivity(null);
        owner.close();
    }

    @Test public void theEndOfAVideoMovesThePagerOnByOne() {
        ended("clip");
        assertEquals(4, pager.current);
        assertEquals(1, pager.advances);
    }

    @Test public void theSwitchAndPauseLeaveTheVideoLooping() {
        Settings.AUTO_ADVANCE.save(false);
        ended("clip");
        assertEquals(0, pager.advances);
        Settings.AUTO_ADVANCE.save(true);
        PausedProcess.set(true);
        ended("clip");
        assertEquals("Pause Hushfeed still moved the pager", 0, pager.advances);
    }

    @Test public void anEndFromAnotherVideoMovesNothing() {
        ended("preloaded");
        assertEquals(0, pager.advances);
        ended(null);
        assertEquals(0, pager.advances);
    }

    @Test public void theMainFeedKeepsItsOwnComponent() {
        owner.close();
        open(Activity.class, new Intent());
        ended("clip");
        assertEquals(0, pager.advances);
    }

    @Test public void aPagerThatCannotMoveStays() {
        pager.hasNext = false;
        ended("clip");
        assertEquals("the last video moved", 0, pager.advances);
        pager.hasNext = true;
        pager.scrollState = 1;
        ended("clip");
        assertEquals("a swipe in progress moved", 0, pager.advances);
    }

    @Test public void aSheetHoldingFocusStopsIt() {
        owner.windowFocusChanged(false);
        ended("clip");
        assertEquals(0, pager.advances);
    }

    @Test public void theLimitStopsItAndKeepGoingStartsAgain() {
        Settings.AUTO_ADVANCE_LIMIT.save(2);
        ended("clip");
        assertEquals(1, pager.advances);
        select("second");
        ended("second");
        assertEquals("the limit's last video moved on", 1, pager.advances);
        select("third");
        ended("third");
        assertEquals("a stopped session moved on", 1, pager.advances);
    }

    @Test public void searchFollowsItsOwnSwitch() {
        owner.close();
        open(DetailActivity.class, new Intent().putExtra("video_from", "from_search_mix"));
        ended("clip");
        assertEquals("search moved with its switch off", 0, pager.advances);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        ended("clip");
        assertEquals(1, pager.advances);
    }

    @Test public void aProfileIsNotSearch() {
        owner.close();
        open(DetailActivity.class, new Intent().putExtra("video_from", "from_profile_other"));
        ended("clip");
        assertEquals(1, pager.advances);
    }

    /** TikTok's background session is one video: moving the pager under it ended it with no next one. */
    @Test public void awayItStaysPutWithBackgroundPlayOnOrOff() {
        owner.get().getWindow().getDecorView().setVisibility(View.INVISIBLE);
        owner.windowFocusChanged(false);
        ended("clip");
        assertEquals("moved away from the screen with background play off", 0, pager.advances);
        Settings.BACKGROUND_PLAY.save(true);
        ended("clip");
        assertEquals("moved away from the screen with background play on", 0, pager.advances);
    }

    @Test public void aPageWithTikToksOwnComponentIsLeftToIt() {
        Object component = new Object();
        View indicator = new View(owner.get());
        AutoAdvance.onView(component, indicator);
        try {
            ended("clip");
            assertEquals(0, pager.advances);
        } finally {
            AutoAdvance.onDestroy(component);
        }
    }
}
