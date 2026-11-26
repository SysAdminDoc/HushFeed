/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * The room ID and your own connection in a LIVE room (#487): a small line under Hushfeed's other
 * LIVE buttons, refreshed once a second. The connection numbers are the ones TikTok's player
 * keeps for its own LIVE debug panel, read the way that panel reads them: the pull stream
 * service's current player hands over its stats as JSON. Only what describes your side is shown,
 * the round trip to the stream's server, how far behind live the picture is and how fast it
 * downloads. The same JSON also carries the host's phone model and system; none of that is read.
 * A long press copies the room ID.
 *
 * <p>The same line can say how long the LIVE has been running (#494), from the room's own start
 * time, the one TikTok's LIVE code counts a room's duration from. Each switch shows its own part,
 * and the line shows while either has something to say.
 *
 * <p>Any of the room ID, ping, latency and download speed can be left off (#534), and a tap
 * shrinks the line to a small dot over the video, kept from room to room, until a tap on the dot
 * brings it back.
 */
@SuppressWarnings("unused")
public final class LiveRoomInfo {
    private LiveRoomInfo() {
    }

    /** TikTok's own keys in the player's stats, colon included. */
    static final String PING = "rtt:";
    static final String LATENCY = "delay:";
    static final String DOWNLOAD_SPEED = "download_Speed:";

    private static final long TICK_MS = 1000;
    /** Below Ghost mode's eye, which sits at 244dp and is 48dp tall. */
    static final int TOP_DP = 300;
    private static final int SIDE_DP = 12;
    private static final int GAP_DP = 8;
    private static final int TEXT_SP = 11;
    /** The shrunk line: a round dot with an i on it, as wide as it is tall. */
    static final String DOT = "i";
    private static final int DOT_DP = 28;
    private static final int DOT_TEXT_SP = 14;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // Everything below is touched on the main thread only.
    private static final Map<Object, Boolean> PAGES = new WeakHashMap<>();
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static WeakReference<TextView> labelReference = new WeakReference<>(null);
    private static WeakReference<Application> followed = new WeakReference<>(null);
    /** The room the line on screen shows, which a long press copies. */
    private static String shownRoom;
    /** Whether the line on screen is drawn as the dot, or null before it's drawn either way. */
    private static Boolean drawnCollapsed;
    private static boolean ticking;
    private static boolean statsFailed;
    /** The last room whose player had none of the line's numbers, noted in the log once. */
    private static String roomWithoutNumbers;

    static boolean enabled() {
        return SettingsStatus.liveControlsEnabled && !Setting.isPaused()
                && (Settings.LIVE_ROOM_INFO.get() || Settings.LIVE_RUNNING_TIME.get());
    }

    /**
     * Below this a start time is in seconds, as the room keeps it; at or above, it's already in
     * milliseconds. A second count only reaches it in the year 5138.
     */
    private static final long MILLIS_FROM = 100_000_000_000L;

    /** Called as a LIVE room's page begins to resume, with that page. */
    public static void noteRoomFragment(Object fragment) {
        try {
            if (fragment == null) return;
            Utils.runOnMainThread(() -> onRoomResumed(fragment));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not note the LIVE room page for the room line", failure);
        }
    }

    private static void onRoomResumed(Object fragment) {
        try {
            Object activityObject = Reflect.invoke(fragment, "getActivity");
            if (!(activityObject instanceof Activity)) return;
            Activity activity = (Activity) activityObject;
            PAGES.put(fragment, Boolean.TRUE);
            host = new WeakReference<>(activity);
            registerCallbacks(activity);
            refresh(activity);
            scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set up the LIVE room line", failure);
        }
    }

    private static void registerCallbacks(Activity activity) {
        Application application = activity.getApplication();
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity paused) {
                if (paused == host.get()) removeLabel();
            }

            @Override public void onActivityResumed(Activity resumed) {
                if (resumed == host.get()) {
                    refresh(resumed);
                    scheduleTick();
                }
            }

            @Override public void onActivityDestroyed(Activity destroyed) {
                if (destroyed == host.get()) {
                    removeLabel();
                    host = new WeakReference<>(null);
                }
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) { }
        });
    }

    private static void scheduleTick() {
        if (ticking) return;
        ticking = true;
        HANDLER.postDelayed(LiveRoomInfo::tick, TICK_MS);
    }

    private static void tick() {
        ticking = false;
        try {
            Activity activity = host.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                removeLabel();
                return;
            }
            refresh(activity);
            scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not refresh the LIVE room line", failure);
        }
    }

    /** Shows, hides or rewrites the line for the room open in {@code activity}. */
    static void refresh(Activity activity) {
        if (activity == null) return;
        boolean wanted = enabled() && !activity.isFinishing() && !activity.isDestroyed()
                && !FeedVisibility.isFeedWindow(activity) && !inPictureInPicture(activity);
        Object roomObject = wanted ? LiveRecordChip.roomOf(pageInFront(activity)) : null;
        String room = LiveRecordChip.roomKey(roomObject);
        boolean showRoom = room != null && Settings.LIVE_ROOM_INFO.get();
        String running = room != null && Settings.LIVE_RUNNING_TIME.get()
                ? runningLine(roomObject, System.currentTimeMillis()) : null;
        JSONObject stats = showRoom ? stats() : null;
        String text = showRoom ? text(room, stats) : "";
        if (showRoom && !room.equals(roomWithoutNumbers) && connection(stats).isEmpty()) {
            // With the room ID hidden this leaves no line at all (#534), so say why once a room.
            roomWithoutNumbers = room;
            Logger.printDebug(() -> "LIVE room line: no connection numbers for room " + room
                    + (stats == null ? ", the player gave no stats" : ", its stats hold " + stats.names()));
        }
        if (running != null) text = text.isEmpty() ? running : text + "\n" + running;
        if (text.isEmpty()) {
            removeLabel();
            return;
        }
        TextView label = labelReference.get();
        if (label == null || label.getContext() != activity || label.getParent() == null) {
            removeLabel();
            label = showLabel(activity);
            if (label == null) return;
        }
        boolean collapsed = Settings.LIVE_ROOM_INFO_COLLAPSED.get();
        if (!Boolean.valueOf(collapsed).equals(drawnCollapsed)) shape(label, collapsed);
        String shown = collapsed ? DOT : text;
        if (!shown.contentEquals(label.getText())) label.setText(shown);
        CharSequence description = collapsed ? L10n.t(activity, "Show the room line") : text;
        if (!description.equals(label.getContentDescription())) label.setContentDescription(description);
        // Only the room ID is copied, so with it off or left off the line a long press does nothing.
        shownRoom = showRoom && !Settings.HIDE_LIVE_ROOM_ID.get() ? room : null;
        // Clear screen hides the room's controls, and a screen recording hides Hushfeed's own
        // buttons so they aren't filmed (#265); the line goes with them.
        boolean hidden = LiveRecordChip.clearScreenOn() || LiveRecordChip.filmingScreen();
        label.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        if (label.getParent() instanceof ViewGroup) LiveRecordChip.restack((ViewGroup) label.getParent());
    }

    private static boolean inPictureInPicture(Activity activity) {
        return Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode();
    }

    private static TextView showLabel(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return null;
        TextView label = new TextView(activity);
        label.setTextColor(SettingsUi.OVERLAY_TEXT);
        label.setMaxLines(3);
        label.setFocusable(true);
        SettingsUi.markAsButton(label);
        label.setOnClickListener(LiveRoomInfo::toggleCollapsed);
        // Taken even with no room ID to copy, so letting go after a hold isn't a tap too.
        label.setOnLongClickListener(view -> {
            copyRoom(view.getContext(), shownRoom);
            return true;
        });
        LiveRecordChip.markStacked(label, LiveRecordChip.STACK_ROOM_INFO,
                SettingsUi.dp(activity, TOP_DP), SettingsUi.dp(activity, GAP_DP));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = SettingsUi.dp(activity, TOP_DP);
        params.rightMargin = SettingsUi.dp(activity, SIDE_DP);
        content.addView(label, params);
        labelReference = new WeakReference<>(label);
        return label;
    }

    /** Draws the label as the full line, or as the dot it shrinks to. */
    private static void shape(TextView label, boolean collapsed) {
        Context context = label.getContext();
        drawnCollapsed = collapsed;
        if (collapsed) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, DOT_TEXT_SP);
            label.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, 0, 0, 0);
            label.setMinWidth(SettingsUi.dp(context, DOT_DP));
            label.setMinHeight(SettingsUi.dp(context, DOT_DP));
            label.setBackground(SettingsUi.overlayControl(context, SettingsUi.RADIUS_ROUND_ENDS));
            return;
        }
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        label.setTypeface(Typeface.DEFAULT);
        label.setGravity(Gravity.END);
        int padding = SettingsUi.dp(context, 6);
        label.setPadding(padding + padding / 2, padding / 2, padding + padding / 2, padding / 2);
        label.setMinWidth(0);
        label.setMinHeight(0);
        label.setBackground(SettingsUi.overlayControl(context, SettingsUi.RADIUS_OVERLAY));
    }

    /** A tap on the line shrinks it to the dot, and a tap on the dot brings the line back (#534). */
    private static void toggleCollapsed(View label) {
        Settings.LIVE_ROOM_INFO_COLLAPSED.save(!Settings.LIVE_ROOM_INFO_COLLAPSED.get());
        if (label.getContext() instanceof Activity) refresh((Activity) label.getContext());
    }

    private static void removeLabel() {
        View label = labelReference.get();
        labelReference = new WeakReference<>(null);
        shownRoom = null;
        drawnCollapsed = null;
        if (label != null && label.getParent() instanceof ViewGroup) {
            ((ViewGroup) label.getParent()).removeView(label);
        }
    }

    static void copyRoom(Context context, String room) {
        if (room == null || context == null) return;
        try {
            Utils.setClipboard(context, L10n.t(context, "LIVE room ID"), room);
            Utils.showToastShort(L10n.t("Room ID copied"));
        } catch (RuntimeException unavailable) {
            Logger.printException(() -> "Could not copy the LIVE room ID", unavailable);
        }
    }

    /**
     * The line's text: the room ID, then whichever of your connection's numbers the player has,
     * each left out when it's picked to be hidden (#534).
     */
    static String text(String room, JSONObject stats) {
        String connection = connection(stats);
        String first = Settings.HIDE_LIVE_ROOM_ID.get() ? "" : L10n.f("Room ID %1$s", room);
        return first.isEmpty() || connection.isEmpty() ? first + connection : first + "\n" + connection;
    }

    /** Ping, latency and download speed, each only when the player has a number for it and it isn't hidden. */
    static String connection(JSONObject stats) {
        if (stats == null) return "";
        List<String> parts = new ArrayList<>(3);
        long ping = stats.optLong(PING, -1);
        if (ping > 0 && !Settings.HIDE_LIVE_PING.get()) {
            parts.add(L10n.f("Ping %1$s ms", NumberFormat.getIntegerInstance().format(ping)));
        }
        long latency = stats.optLong(LATENCY, -1);
        if (latency > 0 && !Settings.HIDE_LIVE_LATENCY.get()) {
            parts.add(L10n.f("Latency %1$s s", tenths(latency / 1000.0)));
        }
        long speed = stats.optLong(DOWNLOAD_SPEED, -1);
        if (speed > 0 && !Settings.HIDE_LIVE_DOWNLOAD_SPEED.get()) {
            parts.add(L10n.f("%1$s Mb/s", tenths(speed / 1000.0)));
        }
        StringBuilder line = new StringBuilder();
        for (String part : parts) {
            if (line.length() > 0) line.append(" · ");
            line.append(part);
        }
        return line.toString();
    }

    /** "LIVE for 1:02:03", or null while the room has no start time yet. Real-named fields on 47.9.3. */
    static String runningLine(Object room, long nowMs) {
        long started = startedAtMs(room);
        if (started <= 0) return null;
        return L10n.f("LIVE for %1$s", runningTime(nowMs - started));
    }

    /**
     * When the room started, in milliseconds, or 0 when it doesn't say. TikTok's own duration
     * counts from the room's create time, in seconds; its start time is the fallback. A room
     * from the LIVE feed has neither until TikTok's enter answer fills the page's room in.
     */
    static long startedAtMs(Object room) {
        if (room == null) return 0;
        long started = seconds(Reflect.readField(room, "createTime"));
        if (started <= 0) started = seconds(Reflect.readField(room, "startTime"));
        if (started <= 0) return 0;
        return started >= MILLIS_FROM ? started : started * 1000;
    }

    private static long seconds(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /** "12:05" under an hour, "1:02:03" from one, hours counting on past a day. */
    static String runningTime(long elapsedMs) {
        long total = Math.max(0, elapsedMs) / 1000;
        long hours = total / 3600;
        long minutes = total / 60 % 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static String tenths(double value) {
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(value);
    }

    /** The player's stats, or null when there's no player or the patch couldn't reach it. */
    static JSONObject stats() {
        try {
            Object stats = nativeStats();
            return stats instanceof JSONObject ? (JSONObject) stats : null;
        } catch (Throwable failure) {
            if (!statsFailed) {
                statsFailed = true;
                Logger.printException(() -> "Could not read the LIVE player's stats", failure);
            }
            return null;
        }
    }

    // The patch rewrites this to ask TikTok's pull stream service for its current player's stats,
    // the call TikTok's own LIVE debug panel makes. Unpatched, there are none.
    static Object nativeStats() {
        return null;
    }

    /** The page with the most of itself on screen, or the only page known, or null. */
    private static Object pageInFront(Activity activity) {
        Object best = null;
        Object only = null;
        int pages = 0;
        int bestHeight = 0;
        for (Object fragment : new ArrayList<>(PAGES.keySet())) {
            if (fragment == null || Reflect.invoke(fragment, "getActivity") != activity) continue;
            pages++;
            only = fragment;
            int shown = LiveRecordChip.heightOnScreen(fragment);
            if (shown > bestHeight) {
                best = fragment;
                bestHeight = shown;
            }
        }
        if (best == null && pages == 1 && !Boolean.FALSE.equals(Reflect.invoke(only, "isResumed"))) best = only;
        return best;
    }

    static void resetForTests() {
        HANDLER.removeCallbacksAndMessages(null);
        ticking = false;
        statsFailed = false;
        roomWithoutNumbers = null;
        PAGES.clear();
        removeLabel();
        host = new WeakReference<>(null);
    }
}
