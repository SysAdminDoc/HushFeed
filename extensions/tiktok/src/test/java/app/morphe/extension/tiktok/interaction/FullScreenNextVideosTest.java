/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.feed.model.Aweme;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

/** Issue #286: full screen had nothing to swipe to, since the viewer got the one video alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FullScreenNextVideosTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The real adapter's class isn't public, and its getCount and getItem keep their names. */
    private static final class Adapter {
        private final List<Aweme> videos;

        Adapter(Aweme... videos) {
            this.videos = Arrays.asList(videos);
        }

        public int getCount() {
            return videos.size();
        }

        public Aweme getItem(int position) {
            return videos.get(position);
        }
    }

    private static Aweme video(String aid) {
        return new Aweme() {
            @Override public String getAid() {
                return aid;
            }
        };
    }

    @After public void cleanup() {
        Settings.FULL_SCREEN_NEXT_VIDEOS.resetToDefault();
        Utils.setActivity(null);
    }

    @Test
    public void theViewerGetsOnlyTheVideosAfterTheOneOnScreen() throws Exception {
        Aweme first = video("1");
        Aweme onScreen = video("2");
        Aweme next = video("3");
        Aweme last = video("4");
        List<Object> list = new ArrayList<>(Arrays.asList(onScreen));

        assertTrue(FullScreenNextVideos.addVideosAfter(new Adapter(first, onScreen, next, last), onScreen, list, it -> true));

        assertEquals(Arrays.asList(onScreen, next, last), list);
    }

    @Test
    public void whatTheViewerCantPlayStaysOut() throws Exception {
        Aweme onScreen = video("1");
        Aweme ad = video("2");
        Aweme next = video("3");
        List<Object> list = new ArrayList<>(Arrays.asList(onScreen));

        assertTrue(FullScreenNextVideos.addVideosAfter(new Adapter(onScreen, ad, next), onScreen, list, it -> it != ad));

        assertEquals(Arrays.asList(onScreen, next), list);
    }

    @Test
    public void aCopyOfTheVideoWithTheSameIdCounts() throws Exception {
        Aweme onScreen = video("1");
        Aweme next = video("2");
        List<Object> list = new ArrayList<>(Arrays.asList(onScreen));

        assertTrue(FullScreenNextVideos.addVideosAfter(new Adapter(video("1"), next), onScreen, list, it -> true));

        assertEquals(Arrays.asList(onScreen, next), list);
    }

    @Test
    public void aPagerThatDoesntHoldTheVideoIsLeftAlone() throws Exception {
        Aweme onScreen = video("1");
        List<Object> list = new ArrayList<>(Arrays.asList(onScreen));

        assertFalse(FullScreenNextVideos.addVideosAfter(new Adapter(video("2"), video("3")), onScreen, list, it -> true));
        assertFalse(FullScreenNextVideos.addVideosAfter(new Object(), onScreen, list, it -> true));

        assertEquals(Arrays.asList(onScreen), list);
    }

    @Test
    public void aLongFeedStopsAtTheCap() throws Exception {
        Aweme[] videos = new Aweme[300];
        for (int i = 0; i < videos.length; i++) videos[i] = video(String.valueOf(i));
        List<Object> list = new ArrayList<>(Arrays.asList(videos[0]));

        assertTrue(FullScreenNextVideos.addVideosAfter(new Adapter(videos), videos[0], list, it -> true));

        assertEquals(200, list.size());
    }

    @Test
    public void withTheSwitchOffTheListStaysAsTheButtonLeftIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setActivity(activity);
        VerticalViewPager pager = new VerticalViewPager(activity);
        Aweme onScreen = video("1");
        pager.adapter = new Adapter(onScreen, video("2"));
        activity.setContentView(pager);
        View root = pager.getRootView();
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
        List<Object> list = new ArrayList<>(Arrays.asList(onScreen));

        Settings.FULL_SCREEN_NEXT_VIDEOS.save(false);
        FullScreenNextVideos.fill(onScreen, list);

        assertEquals(Arrays.asList(onScreen), list);
    }

    /** An unpatched build answers no, so the stub alone changes nothing. */
    @Test
    public void theStubAddsNothing() {
        assertFalse(FullScreenNextVideos.playsFullScreen(video("1")));
    }
}
