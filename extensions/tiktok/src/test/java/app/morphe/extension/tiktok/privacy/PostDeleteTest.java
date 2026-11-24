/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.download.GridPicker;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * Deleting your own posts picked on a grid (#487): Delete shows on the picking bar only while
 * every picked post is yours, then a countdown with Stop and no dialog, then each post goes with
 * the request a post's own Delete sends (answered as a future, as TikTok's is) and leaves the
 * grid, then the count it reached.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class PostDeleteTest {
    private static final String ME = "7001";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private ShadowLooper looper;
    private FakeTikTok.Account account;
    private final List<Long> pauses = Collections.synchronizedList(new ArrayList<>());
    private IntConsumer duringPause = pause -> { };

    /** A grid post with its author, the way TikTok's model hands both over. */
    public static final class Post extends Aweme {
        final String aid;
        public final Author author;

        Post(String aid, String uid) {
            this.aid = aid;
            this.author = new Author(uid);
        }

        @Override public String getAid() { return aid; }
        public Author getAuthor() { return author; }
    }

    public static final class Author {
        public final String uid;

        Author(String uid) {
            this.uid = uid;
        }
    }

    /** A grid list as GridPicker finds it: a RecyclerView whose adapter hands its posts out. */
    public static final class RecyclerView extends FrameLayout {
        final Adapter adapter = new Adapter();

        RecyclerView(android.content.Context context) {
            super(context);
        }

        public Adapter getAdapter() {
            return adapter;
        }
    }

    public static final class Adapter {
        final List<Object> mmItems = new ArrayList<>();
        int refreshes;

        public List<Object> getData() {
            return mmItems;
        }

        public void notifyDataSetChanged() {
            refreshes++;
        }
    }

    private RecyclerView grid;
    private final List<FrameLayout> cells = new ArrayList<>();

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        looper = Shadows.shadowOf(Looper.getMainLooper());
        SignedInUser.idForTests = ME;
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.PICK_GRID_VIDEOS.save(true);
        Settings.DELETE_PICKED_POSTS.save(true);
        ReflectionHelpers.callStaticMethod(GridPicker.class, "resetForTests");
        account = new FakeTikTok.Account().withPosts(3);
        PostDelete.servicesForTests = account;
        PostDelete.pacerForTests = new CleanupRunner.Pacer() {
            @Override public void pause(long ms) {
                pauses.add(ms);
                duringPause.accept(pauses.size());
            }

            @Override public void wake() {
            }
        };
        PostDelete.executorForTests = Runnable::run;
        ShadowToast.reset();
        grid = new RecyclerView(activity);
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    }

    @After public void tearDown() {
        PostDelete.resetForTests();
        ReflectionHelpers.callStaticMethod(GridPicker.class, "resetForTests");
        Settings.DELETE_PICKED_POSTS.save(false);
        Settings.PICK_GRID_VIDEOS.save(false);
        SettingsStatus.advancedDownloadsEnabled = false;
        SignedInUser.idForTests = null;
        Utils.setActivity(null);
        controller.pause().stop().destroy();
    }

    /** Lays the posts out as the grid's cells, bound the way the cell holder binds them. */
    private void show(Post... posts) {
        for (Post post : posts) {
            grid.adapter.mmItems.add(post);
            FrameLayout cell = new FrameLayout(activity);
            cell.addView(new View(activity), new FrameLayout.LayoutParams(-1, -1));
            grid.addView(cell, new FrameLayout.LayoutParams(300, 400));
            cells.add(cell);
            GridPicker.bind(cell, post);
        }
    }

    private void pick(int index) {
        View circle = cells.get(index).findViewWithTag("hushfeed_grid_pick");
        assertNotNull("the cell has no circle", circle);
        circle.performClick();
    }

    private TextView barButton(String tag, String text) {
        View bar = activity.findViewById(android.R.id.content).findViewWithTag(tag);
        if (!(bar instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) bar;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof TextView && text.contentEquals(((TextView) child).getText())) return (TextView) child;
        }
        return null;
    }

    /** The picking bar's Delete, a trash icon that says Delete to a screen reader. */
    private TextView delete() {
        View bar = activity.findViewById(android.R.id.content).findViewWithTag("hushfeed_grid_pick_bar");
        if (!(bar instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) bar;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof TextView && "Delete".contentEquals(child.getContentDescription())) return (TextView) child;
        }
        return null;
    }

    private String deleteBarText() {
        View bar = PostDelete.barForTests(activity);
        if (!(bar instanceof ViewGroup)) return null;
        return ((TextView) ((ViewGroup) bar).getChildAt(0)).getText().toString();
    }

    private List<String> gridIds() {
        List<String> ids = new ArrayList<>();
        for (Object item : grid.adapter.mmItems) ids.add(((Post) item).aid);
        return ids;
    }

    private void countDown() {
        looper.idleFor(Duration.ofSeconds(PostDelete.COUNTDOWN_SECONDS));
        looper.idle();
    }

    @Test public void theSwitchStartsOffUnderThePicker() {
        assertFalse(Settings.DELETE_PICKED_POSTS.defaultValue);
    }

    @Test public void deleteShowsOnlyWhileEveryPickedPostIsYours() {
        show(new Post("p0", ME), new Post("p1", ME), new Post("x", "8002"));

        pick(0);
        assertEquals(View.VISIBLE, delete().getVisibility());
        assertEquals("an icon, so the count keeps its room", "", delete().getText().toString());
        assertNotNull(delete().getCompoundDrawablesRelative()[0]);
        pick(2);
        assertEquals("someone else's post is picked", View.GONE, delete().getVisibility());
        pick(2);
        assertEquals(View.VISIBLE, delete().getVisibility());

        Settings.DELETE_PICKED_POSTS.save(false);
        pick(1);
        assertEquals("the switch is off", View.GONE, delete().getVisibility());
        Settings.DELETE_PICKED_POSTS.save(true);
        SignedInUser.idForTests = null;
        pick(1);
        assertEquals("nobody is signed in", View.GONE, delete().getVisibility());
    }

    @Test public void deleteCountsDownThenDeletesEachPostAndTakesItOffTheGrid() {
        show(new Post("p0", ME), new Post("p1", ME), new Post("p2", ME));
        pick(0);
        pick(1);

        delete().performClick();
        assertNull("the picks are given back at once", activity.findViewById(android.R.id.content)
                .findViewWithTag("hushfeed_grid_pick_bar"));
        assertEquals("Posts to delete: 2. Starting in 5", deleteBarText());
        assertNotNull(barButton(PostDelete.BAR_TAG, "Stop"));
        assertTrue(PostDelete.running());
        looper.idleFor(Duration.ofSeconds(1));
        assertEquals("Posts to delete: 2. Starting in 4", deleteBarText());
        assertEquals("nothing goes out during the countdown", List.of(), account.requests);

        countDown();
        assertEquals("each goes with the request a post's own Delete sends, in the order picked",
                List.of("deleteItem {aweme_id=p0}", "deleteItem {aweme_id=p1}"), account.requests);
        assertEquals(List.of("p2"), account.posts);
        assertEquals(List.of("p2"), gridIds());
        assertEquals(2, grid.adapter.refreshes);
        assertEquals("a pause before each delete and before the picks are read again", 3, pauses.size());
        assertNull("the bar goes when it's done", PostDelete.barForTests(activity));
        assertFalse(PostDelete.running());
        assertEquals("Done. 2 removed.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void stopDuringTheCountdownDeletesNothing() {
        show(new Post("p0", ME), new Post("p1", ME));
        pick(0);
        delete().performClick();
        looper.idleFor(Duration.ofSeconds(2));

        barButton(PostDelete.BAR_TAG, "Stop").performClick();
        assertNull(PostDelete.barForTests(activity));
        assertEquals("Cancelled", ShadowToast.getTextOfLatestToast());
        looper.idleFor(Duration.ofSeconds(10));
        assertEquals(List.of(), account.requests);
        assertEquals(List.of("p0", "p1"), gridIds());
        assertFalse(PostDelete.running());
    }

    @Test public void aCountdownWhoseBarLeftTheScreenDeletesNothing() {
        show(new Post("p0", ME));
        pick(0);
        delete().performClick();
        View bar = PostDelete.barForTests(activity);
        ((ViewGroup) bar.getParent()).removeView(bar);

        countDown();
        assertEquals(List.of(), account.requests);
        assertFalse(PostDelete.running());
        assertNull("it ends without a word", ShadowToast.getTextOfLatestToast());
    }

    @Test public void stopMidRunKeepsTheRestAndSaysHowMany() {
        show(new Post("p0", ME), new Post("p1", ME), new Post("p2", ME));
        pick(0);
        pick(1);
        pick(2);
        delete().performClick();
        duringPause = pause -> {
            if (pause == 2) barButton(PostDelete.BAR_TAG, "Stop").performClick();
        };

        countDown();
        assertEquals(List.of("deleteItem {aweme_id=p0}"), account.requests);
        assertEquals(List.of("p1", "p2"), gridIds());
        assertEquals("Stopped. 1 removed.", ShadowToast.getTextOfLatestToast());
        assertFalse(PostDelete.running());
    }

    @Test public void aRefusalEndsTheRunWithTheCountSoFar() {
        account.refuse = "p1";
        show(new Post("p0", ME), new Post("p1", ME), new Post("p2", ME));
        pick(0);
        pick(1);
        pick(2);
        delete().performClick();

        countDown();
        assertEquals(List.of("deleteItem {aweme_id=p0}", "deleteItem {aweme_id=p1}"), account.requests);
        assertEquals("only what TikTok deleted leaves the grid", List.of("p1", "p2"), gridIds());
        assertEquals("TikTok refused. 1 removed.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aFailedFutureReadsAsTikTokSayingNo() {
        account.postFailure = new ServerRefusal(8);
        show(new Post("p0", ME));
        pick(0);
        delete().performClick();

        countDown();
        assertEquals(List.of("p0"), gridIds());
        assertEquals("TikTok refused. 0 removed.", ShadowToast.getTextOfLatestToast());
    }

    /** TikTok's server error, which carries the error code its answer had. */
    public static final class ServerRefusal extends Exception {
        private final int code;

        ServerRefusal(int code) {
            this.code = code;
        }

        public int getErrorCode() {
            return code;
        }
    }

    @Test public void onlyOneRemovalRunsAtATime() {
        show(new Post("p0", ME), new Post("p1", ME));
        pick(0);
        delete().performClick();
        pick(1);
        delete().performClick();

        assertEquals("A removal is already running", ShadowToast.getTextOfLatestToast());
        countDown();
        assertEquals(List.of("deleteItem {aweme_id=p0}"), account.requests);
    }
}
