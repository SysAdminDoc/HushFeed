/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.util.Rational;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.wellbeing.FeedLock;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A video playing in the feed, or in a video opened from a profile, search or a sound, keeps going
 * in a small window when the reader leaves TikTok. TikTok has picture-in-picture for LIVE only. Its
 * feed activity already declares support for it (for LIVE), and the patch declares it for the
 * detail pager too, which takes size changes itself like the feed does.
 *
 * <p>The patch hands over two moments: leaving (onUserLeaveHint, in the base activity both windows
 * share) and the window opening or closing (ComponentActivity's onPictureInPictureModeChanged).
 * Leaving opens the window only while the player reports the video on screen playing, so a paused
 * video, a profile page or the inbox leave TikTok as they always did, and TikTok's own LIVE window
 * is left to TikTok. In the window, everything but the video is set aside and comes back as it was
 * when the window closes or grows back into TikTok. TikTok may stop its player as the activity
 * pauses, so the video is started again once the window is up, unless the reader paused it there.
 * Play and pause go through TikTok's own player controls, the session hold's bridge. The arrows
 * beside them (#413) move TikTok's pager to the video before or after, as a swipe would. Closing
 * the window stops the video, unless Keep playing in the background is on.
 *
 * <p>Read when the reader leaves, so the switch needs no restart. Paused, it answers off.
 */
public final class PictureInPicture {
    static final String ACTION_TOGGLE = "app.morphe.extension.tiktok.PICTURE_IN_PICTURE_TOGGLE";
    /** The window's arrows (#413): the video before or after this one in the same pager. */
    static final String ACTION_PREVIOUS = "app.morphe.extension.tiktok.PICTURE_IN_PICTURE_PREVIOUS";
    static final String ACTION_NEXT = "app.morphe.extension.tiktok.PICTURE_IN_PICTURE_NEXT";

    /** TikTok's own vertical pager, the one the feed and a video's own page swipe through. */
    static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";

    /** Android shows three buttons in a phone's window; with room for fewer, only play and pause. */
    static final int ROOM_FOR_ARROWS = 3;

    /** How often a move's slide is checked for having settled, and how many times at most. */
    private static final long SETTLE_CHECK_MS = 300;
    private static final int SETTLE_CHECKS = 8;

    /**
     * Carried by the window's own button. Before Android 13 a receiver registered at run time
     * can't be kept from other apps, so any of them could send the action and pause or play
     * the video. They can't read this, since only the button's PendingIntent holds it.
     */
    static final String EXTRA_TOKEN = "app.morphe.extension.tiktok.PICTURE_IN_PICTURE_TOKEN";
    private static final String TOKEN = java.util.UUID.randomUUID().toString();

    /** Android's limits on a window's shape: no narrower than 1:2.39 and no wider than 2.39:1. */
    static final Rational NARROWEST = new Rational(100, 239);
    static final Rational WIDEST = new Rational(239, 100);

    /** A second leave hint for the same activity this soon, from a second hooked declaration, is the same leave. */
    private static final long REPEAT_MS = 1000;

    /** When TikTok's own pause has landed after the window opens, and a second look in case it was late. */
    private static final long[] RESUME_CHECKS_MS = {600, 1500};

    /** How long after the window closes TikTok has to be back in front, or the reader closed the window. */
    private static final long CLOSED_CHECK_MS = 500;

    // Main thread only, like every entry point here.
    private static WeakReference<Activity> opened = new WeakReference<>(null);
    private static long openedAt;
    private static boolean readerPaused;
    private static final List<SetAside> setAside = new ArrayList<>();
    private static WeakReference<View> asideVideo = new WeakReference<>(null);
    private static WeakReference<View> asideRoot = new WeakReference<>(null);
    private static WeakReference<View> keptOn = new WeakReference<>(null);
    private static BroadcastReceiver toggle;

    private PictureInPicture() {
    }

    private static final class SetAside {
        final WeakReference<View> view;
        final int visibility;

        SetAside(View view) {
            this.view = new WeakReference<>(view);
            this.visibility = view.getVisibility();
        }
    }

    static boolean enabled() {
        return SettingsStatus.pictureInPictureEnabled && Settings.PICTURE_IN_PICTURE.get();
    }

    /** Called first in the feed windows' shared onUserLeaveHint. */
    public static void onUserLeaveHint(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            open(activity);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't open", failure);
        }
    }

    @RequiresApi(26)
    private static void open(Activity activity) {
        if (activity == null || !enabled() || !FeedVisibility.isFeedWindow(activity)) return;
        if (activity.isInPictureInPictureMode()) return;
        long now = SystemClock.uptimeMillis();
        if (opened.get() == activity && now - openedAt < REPEAT_MS) return;
        if (!Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying())) return;
        View video = videoView(activity.getWindow().getDecorView());
        if (video == null) return;
        readerPaused = false;
        if (activity.enterPictureInPictureMode(params(activity, video, true))) {
            opened = new WeakReference<>(activity);
            openedAt = now;
        }
    }

    /** Called first in ComponentActivity's onPictureInPictureModeChanged(boolean, Configuration). */
    public static void onModeChanged(Activity activity, boolean inWindow) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            follow(activity, inWindow);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't follow the window", failure);
        }
    }

    @RequiresApi(26)
    private static void follow(Activity activity, boolean inWindow) {
        // Only the window this class opened: TikTok's own LIVE window is TikTok's.
        if (activity == null || opened.get() != activity) return;
        if (inWindow) {
            keepAsideWhileUp(activity.getWindow().getDecorView());
            listen(activity);
            WeakReference<Activity> owner = new WeakReference<>(activity);
            for (long delay : RESUME_CHECKS_MS) {
                Utils.runOnMainThreadDelayed(() -> keepPlaying(owner.get()), delay);
            }
        } else {
            bringBack();
            stopListening(activity);
            opened.clear();
            WeakReference<Activity> owner = new WeakReference<>(activity);
            Utils.runOnMainThreadDelayed(() -> stopIfClosed(owner.get()), CLOSED_CHECK_MS);
        }
    }

    /**
     * Closing the window, rather than growing it back into TikTok, stops the video the way leaving
     * TikTok does, since the window started it again after TikTok's own pause. Keep playing in the
     * background asks for the opposite, so with it on the video plays on.
     */
    private static void stopIfClosed(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.hasWindowFocus()) return;
        if (SettingsStatus.backgroundPlayEnabled && Settings.BACKGROUND_PLAY.get()) return;
        if (Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying())) {
            SessionPlaybackHold.setCurrentPlaying(false);
        }
    }

    /** Starts the video again when TikTok stopped it as the window opened, unless the reader paused it. */
    @RequiresApi(26)
    private static void keepPlaying(Activity activity) {
        if (activity == null || opened.get() != activity || readerPaused) return;
        if (!activity.isInPictureInPictureMode()) return;
        if (Boolean.FALSE.equals(SessionPlaybackHold.currentPlaying())) {
            SessionPlaybackHold.setCurrentPlaying(true);
        }
    }

    /** The window's play or pause button. */
    static void onToggle() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            flip();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't update its button", failure);
        }
    }

    @RequiresApi(26)
    private static void flip() {
        Activity activity = opened.get();
        if (activity == null) return;
        boolean play = !Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying());
        if (!SessionPlaybackHold.setCurrentPlaying(play)) return;
        readerPaused = !play;
        activity.setPictureInPictureParams(params(activity, videoView(activity.getWindow().getDecorView()), play));
    }

    /** The window's previous or next arrow: -1 or 1. */
    static void onMove(int direction) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            move(direction);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't move to another video", failure);
        }
    }

    /**
     * Moves TikTok's pager one video up or down, the way a swipe would, so TikTok picks and plays
     * the next video itself. What was set aside comes back first, since the video slides in from a
     * cell beside this one, and once the pager has settled the new video is kept alone again.
     */
    @RequiresApi(26)
    private static void move(int direction) {
        Activity activity = opened.get();
        if (activity == null || !activity.isInPictureInPictureMode()) return;
        View pager = pagerOf(videoView(activity.getWindow().getDecorView()));
        if (!canMove(pager, direction)) return;
        Object position = Reflect.invoke(pager, "getCurrentItem");
        if (!(position instanceof Integer) || (Integer) position < 0) return;
        bringBack();
        try {
            // The native pager clamps to its adapter and runs its own selection callbacks.
            pager.getClass().getMethod("setCurrentItem", int.class).invoke(pager, (Integer) position + direction);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't move the pager", failure);
            keepAsideWhileUp(activity.getWindow().getDecorView());
            return;
        }
        readerPaused = false;
        settle(new WeakReference<>(activity), SETTLE_CHECKS);
    }

    @RequiresApi(26)
    private static void settle(WeakReference<Activity> owner, int checksLeft) {
        Utils.runOnMainThreadDelayed(() -> {
            Activity activity = owner.get();
            if (activity == null || opened.get() != activity || !activity.isInPictureInPictureMode()) return;
            View decor = activity.getWindow().getDecorView();
            View pager = pagerOf(videoView(decor));
            if (pager != null && !idle(pager) && checksLeft > 1) {
                settle(owner, checksLeft - 1);
                return;
            }
            keepAsideWhileUp(decor);
            activity.setPictureInPictureParams(params(activity, videoView(decor), true));
            for (long delay : RESUME_CHECKS_MS) {
                Utils.runOnMainThreadDelayed(() -> keepPlaying(owner.get()), delay);
            }
        }, SETTLE_CHECK_MS);
    }

    /** The pager the video sits in: its nearest parent that is TikTok's vertical pager, or null. */
    static View pagerOf(View video) {
        ViewParent parent = video == null ? null : video.getParent();
        while (parent instanceof View) {
            for (Class<?> type = parent.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                if (PAGER.equals(type.getName())) return (View) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * Whether an arrow can move the pager now: it has a video that way and isn't mid-slide, and
     * no hold is keeping the reader on this video (the session budget's lock, or a link opened alone).
     */
    static boolean canMove(View pager, int direction) {
        if (pager == null || !idle(pager) || !pager.canScrollVertically(direction)) return false;
        return !SessionBudget.isLocked() && !FeedLock.linkVideoAlone();
    }

    private static boolean idle(View pager) {
        return Integer.valueOf(0).equals(Reflect.invoke(pager, "getScrollState"));
    }

    /**
     * The video on screen: the largest TextureView or SurfaceView showing, which is what TikTok's
     * player draws into. Neighbouring cells sit off screen and show nothing.
     */
    public static View videoView(View root) {
        List<View> shown = videoViews(root);
        return shown.isEmpty() ? null : shown.get(0);
    }

    /**
     * Every TextureView and SurfaceView showing, the largest first, and in tree order where two are
     * the same size. The largest isn't always the one with a frame to read, so the Long press frame
     * save reads down this list.
     */
    public static List<View> videoViews(View root) {
        List<View> found = new ArrayList<>();
        collect(root, found);
        List<View> shown = new ArrayList<>();
        List<Long> areas = new ArrayList<>();
        Rect bounds = new Rect();
        for (View view : found) {
            if (!view.isShown() || !view.getGlobalVisibleRect(bounds)) continue;
            long area = (long) bounds.width() * bounds.height();
            if (area <= 0) continue;
            int at = 0;
            while (at < areas.size() && areas.get(at) >= area) at++;
            shown.add(at, view);
            areas.add(at, area);
        }
        return shown;
    }

    private static void collect(View view, List<View> found) {
        if (view == null) return;
        if (view instanceof TextureView || view instanceof SurfaceView) found.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found);
        }
    }

    /**
     * Sets aside every view showing beside the video and its parents, up to the window: the
     * captions, the right column, the tabs and the neighbouring cells. Each keeps what it was, and
     * {@link #bringBack} restores only those TikTok left alone meanwhile.
     */
    static void setAside(View root) {
        View video = videoView(root);
        if (video == null) return;
        asideVideo = new WeakReference<>(video);
        asideRoot = new WeakReference<>(root);
        hideBeside(video, root);
    }

    /**
     * Keeps the video alone until {@link #bringBack}. TikTok shows the cell's caption, right
     * column and search bar again after the window opens, so on 47.1.4 they sat in the small
     * window over the video (S22, 2026-10-08). Every frame sets aside again whatever TikTok has
     * shown beside the video since. The frame still draws: a skipped one could hold a
     * TextureView's video still if TikTok shows a view on every layout.
     */
    static void keepAsideWhileUp(View root) {
        stopKeeping();
        setAside(root);
        if (asideVideo.get() == null) return;
        root.getViewTreeObserver().addOnPreDrawListener(KEEP_ASIDE);
        keptOn = new WeakReference<>(root);
    }

    private static final ViewTreeObserver.OnPreDrawListener KEEP_ASIDE = () -> {
        View video = asideVideo.get();
        View root = asideRoot.get();
        if (video != null && root != null && video.isAttachedToWindow()) hideBeside(video, root);
        return true;
    };

    private static void hideBeside(View video, View root) {
        View child = video;
        ViewParent parent = child.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View sibling = group.getChildAt(i);
                if (sibling == child || sibling.getVisibility() != View.VISIBLE) continue;
                if (!isSetAside(sibling)) setAside.add(new SetAside(sibling));
                sibling.setVisibility(View.INVISIBLE);
            }
            if (group == root) break;
            child = group;
            parent = group.getParent();
        }
    }

    private static boolean isSetAside(View view) {
        for (SetAside entry : setAside) {
            if (entry.view.get() == view) return true;
        }
        return false;
    }

    private static void stopKeeping() {
        View root = keptOn.get();
        if (root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnPreDrawListener(KEEP_ASIDE);
        }
        keptOn = new WeakReference<>(null);
    }

    static void bringBack() {
        stopKeeping();
        asideVideo = new WeakReference<>(null);
        asideRoot = new WeakReference<>(null);
        for (SetAside entry : setAside) {
            View view = entry.view.get();
            if (view != null && view.getVisibility() == View.INVISIBLE) view.setVisibility(entry.visibility);
        }
        setAside.clear();
    }

    /** The window's shape: the video's own, held to what Android takes, or null when unknown. */
    static Rational shape(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        Rational shape = new Rational(width, height);
        if (shape.compareTo(NARROWEST) < 0) return NARROWEST;
        if (shape.compareTo(WIDEST) > 0) return WIDEST;
        return shape;
    }

    /** The post's own video size, read off the player's current post, else the view the video draws into. */
    private static Rational videoShape(View video) {
        Object post = SessionPlaybackHold.currentPlayingAweme();
        Object model = post == null ? null : Reflect.invoke(post, "getVideo");
        Object width = model == null ? null : Reflect.invoke(model, "getWidth");
        Object height = model == null ? null : Reflect.invoke(model, "getHeight");
        if (width instanceof Integer && height instanceof Integer) {
            Rational shape = shape((Integer) width, (Integer) height);
            if (shape != null) return shape;
        }
        return video == null ? null : shape(video.getWidth(), video.getHeight());
    }

    @RequiresApi(26)
    private static PictureInPictureParams params(Activity activity, View video, boolean playing) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        Rational shape = videoShape(video);
        if (shape != null) builder.setAspectRatio(shape);
        View pager = pagerOf(video);
        builder.setActions(actions(activity, playing, pager != null,
                canMove(pager, -1), canMove(pager, 1), room(activity)));
        return builder.build();
    }

    /** How many buttons Android shows in the window; one when it won't say. */
    @RequiresApi(26)
    private static int room(Activity activity) {
        try {
            return activity.getMaxNumPictureInPictureActions();
        } catch (RuntimeException unknown) {
            return 1;
        }
    }

    /**
     * The window's buttons: play or pause, between the previous and next arrows when the video
     * sits in a pager and the window has room for three. An arrow with no video its way, or held
     * by a lock, shows dimmed and does nothing, so the buttons don't shift between videos.
     */
    @RequiresApi(26)
    static List<RemoteAction> actions(Context context, boolean playing, boolean arrows,
                                      boolean back, boolean forward, int room) {
        String label = playing ? L10n.t("Pause") : L10n.t("Play");
        RemoteAction toggle = action(context, ACTION_TOGGLE, 0,
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, label);
        if (!arrows || room < ROOM_FOR_ARROWS) return Collections.singletonList(toggle);
        RemoteAction previous = action(context, ACTION_PREVIOUS, 1,
                android.R.drawable.ic_media_previous, L10n.t("Previous video"));
        previous.setEnabled(back);
        RemoteAction next = action(context, ACTION_NEXT, 2,
                android.R.drawable.ic_media_next, L10n.t("Next video"));
        next.setEnabled(forward);
        List<RemoteAction> all = new ArrayList<>(ROOM_FOR_ARROWS);
        all.add(previous);
        all.add(toggle);
        all.add(next);
        return all;
    }

    @RequiresApi(26)
    private static RemoteAction action(Context context, String name, int code, int icon, String label) {
        Intent intent = new Intent(name).setPackage(context.getPackageName()).putExtra(EXTRA_TOKEN, TOKEN);
        PendingIntent pending = PendingIntent.getBroadcast(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource("android", icon), label, label, pending);
    }

    private static void listen(Activity activity) {
        if (toggle != null) return;
        toggle = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!fromTheButton(intent)) return;
                String action = intent.getAction();
                if (ACTION_PREVIOUS.equals(action)) onMove(-1);
                else if (ACTION_NEXT.equals(action)) onMove(1);
                else onToggle();
            }
        };
        Context app = activity.getApplicationContext();
        IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
        filter.addAction(ACTION_PREVIOUS);
        filter.addAction(ACTION_NEXT);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(toggle, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(toggle, filter);
        }
    }

    static boolean fromTheButton(Intent intent) {
        try {
            return intent != null && TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN));
        } catch (RuntimeException unreadable) {
            // Another app's extras can name a class this process can't unparcel.
            return false;
        }
    }

    private static void stopListening(Activity activity) {
        if (toggle == null) return;
        try {
            activity.getApplicationContext().unregisterReceiver(toggle);
        } catch (IllegalArgumentException alreadyGone) {
            // Nothing left to unregister.
        }
        toggle = null;
    }
}
