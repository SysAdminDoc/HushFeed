package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorService;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.commentv2.commentlist.viewmodel.CommentDeleteVM;
import com.ss.android.ugc.aweme.profile.model.User;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** The like count and thumbs down a comment row can lose, and what a thumbs down block takes out (#314, #317). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentRowControlsTest {
    private Activity activity;
    private FrameLayout row;
    private View count;
    private View thumbsDown;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        // Looked up by name against TikTok's own resources, which aren't here.
        Object cache = ReflectionHelpers.getStaticField(CommentTools.class, "RESOURCE_IDS");
        Map<String, Integer> ids = ReflectionHelpers.getField(cache, "ids");
        ids.put(activity.getPackageName() + ":tv_like_count", 0x7f000103);
        ids.put(activity.getPackageName() + ":47.6.3:k1e", 0x7f000101);
        ids.put(activity.getPackageName() + ":47.6.3:mn5", 0x7f000102);
        ReflectionHelpers.<Map<View, ?>>getStaticField(CommentTools.class, "CELL_COMMENTS").clear();
        ReflectionHelpers.<Set<String>>getStaticField(CommentTools.class, "IN_FLIGHT").clear();
        ReflectionHelpers.<Set<String>>getStaticField(CommentTools.class, "BLOCKED_UIDS").clear();
        CommentDeleteVM.POSTED.clear();

        row = new FrameLayout(activity);
        count = new View(activity);
        count.setId(0x7f000103);
        thumbsDown = new View(activity);
        thumbsDown.setId(0x7f000101);
        ImageView icon = new ImageView(activity);
        icon.setId(0x7f000102);
        row.addView(count);
        row.addView(thumbsDown);
        row.addView(icon);
        activity.setContentView(row);
    }

    @After public void restoreSwitches() {
        Settings.HIDE_COMMENT_LIKE_COUNT.resetToDefault();
        Settings.HIDE_COMMENT_DISLIKE.resetToDefault();
        Settings.BLOCK_FROM_COMMENT.resetToDefault();
        CommentDeleteVM.POSTED.clear();
    }

    private void bind() {
        CommentTools.registerCommentCell(row, new Manager());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void bothStayWithTheSwitchesOff() {
        bind();
        assertEquals(View.VISIBLE, count.getVisibility());
        assertEquals(View.VISIBLE, thumbsDown.getVisibility());
    }

    @Test public void eachSwitchTakesOnlyItsOwnControl() {
        Settings.HIDE_COMMENT_LIKE_COUNT.save(true);
        bind();
        assertEquals(View.GONE, count.getVisibility());
        assertEquals(View.VISIBLE, thumbsDown.getVisibility());

        count.setVisibility(View.VISIBLE);
        Settings.HIDE_COMMENT_LIKE_COUNT.save(false);
        Settings.HIDE_COMMENT_DISLIKE.save(true);
        bind();
        assertEquals(View.VISIBLE, count.getVisibility());
        assertEquals(View.GONE, thumbsDown.getVisibility());
    }

    /** TikTok writes the count during the bind, so a row TikTok showed again goes on the next one. */
    @Test public void aControlTikTokShowsAgainGoesOnTheNextBind() {
        Settings.HIDE_COMMENT_LIKE_COUNT.save(true);
        bind();
        count.setVisibility(View.VISIBLE);
        bind();
        assertEquals(View.GONE, count.getVisibility());
    }

    /** The block switch's button is that thumbs down, so hiding it would take the block away. */
    @Test public void blockFromCommentKeepsTheThumbsDown() {
        Settings.BLOCK_FROM_COMMENT.save(true);
        Settings.HIDE_COMMENT_DISLIKE.save(true);
        bind();
        assertEquals(View.VISIBLE, thumbsDown.getVisibility());
    }

    @Test public void aConfirmedBlockTakesTheCommentersCommentsOutOfTheList() throws Exception {
        Settings.BLOCK_FROM_COMMENT.save(true);
        Manager manager = new Manager();
        CommentTools.registerCommentCell(row, manager);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        var serviceType = BlockAuthorService.class;
        Object previousService = ReflectionHelpers.getStaticField(serviceType, "cachedService");
        Object previousMethod = ReflectionHelpers.getStaticField(serviceType, "cachedBlockMethod");
        ReflectionHelpers.setStaticField(serviceType, "cachedService", new BlockService());
        ReflectionHelpers.setStaticField(serviceType, "cachedBlockMethod",
                BlockService.class.getMethod("block", String.class, String.class, int.class, int.class));
        try {
            assertTrue(thumbsDown.performClick());
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(List.of(manager.comment.getUser()), CommentDeleteVM.POSTED);
        } finally {
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ReflectionHelpers.setStaticField(serviceType, "cachedService", previousService);
            ReflectionHelpers.setStaticField(serviceType, "cachedBlockMethod", previousMethod);
        }
    }

    /** A cell state holder carrying a comment, which is what findComment reaches for. */
    public static final class Manager {
        public final Comment comment = new Comment();
    }

    @SuppressWarnings("unused")
    public static final class Comment {
        private final User user = new User();

        Comment() {
            user.uid = "200";
            user.uniqueId = "commenter200";
        }

        public User getUser() { return user; }
        public String getCid() { return "c1"; }
    }

    public static final class BlockService {
        public Call block(String uid, String secUid, int type, int source) {
            return new Call();
        }
    }

    public static final class Call {
        public Response execute() { return new Response(); }
    }

    public static final class Response {
        public Body body() { return new Body(); }
    }

    public static final class Body {
        public final int statusCode = 0;
    }
}
