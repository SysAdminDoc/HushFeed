/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
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
 * A tap copies the room ID.
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

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // Everything below is touched on the main thread only.
    private static final Map<Object, Boolean> PAGES = new WeakHashMap<>();
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static WeakReference<TextView> labelReference = new WeakReference<>(null);
    private static WeakReference<Application> followed = new WeakReference<>(null);
    /** The room the line on screen shows, which a tap copies. */
    private static String shownRoom;
    private static boolean ticking;
    private static boolean statsFailed;

    static boolean enabled() {
        return SettingsStatus.liveControlsEnabled && Settings.LIVE_ROOM_INFO.get() && !Setting.isPaused();
    }

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
        String room = wanted ? roomInFront(activity) : null;
        if (room == null) {
            removeLabel();
            return;
        }
        TextView label = labelReference.get();
        if (label == null || label.getContext() != activity || label.getParent() == null) {
            removeLabel();
            label = showLabel(activity);
            if (label == null) return;
        }
        String text = text(room, stats());
        if (!text.contentEquals(label.getText())) {
            label.setText(text);
            label.setContentDescription(text);
        }
        shownRoom = room;
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
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        label.setGravity(Gravity.END);
        label.setMaxLines(2);
        int padding = SettingsUi.dp(activity, 6);
        label.setPadding(padding + padding / 2, padding / 2, padding + padding / 2, padding / 2);
        label.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY));
        label.setFocusable(true);
        SettingsUi.markAsButton(label);
        label.setOnClickListener(view -> copyRoom(view.getContext(), shownRoom));
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

    private static void removeLabel() {
        View label = labelReference.get();
        labelReference = new WeakReference<>(null);
        shownRoom = null;
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

    /** The line's text: the room ID, then whichever of your connection's numbers the player has. */
    static String text(String room, JSONObject stats) {
        String connection = connection(stats);
        String first = L10n.f("Room ID %1$s", room);
        return connection.isEmpty() ? first : first + "\n" + connection;
    }

    /** Ping, latency and download speed, each only when the player has a number for it. */
    static String connection(JSONObject stats) {
        if (stats == null) return "";
        List<String> parts = new ArrayList<>(3);
        long ping = stats.optLong(PING, -1);
        if (ping > 0) parts.add(L10n.f("Ping %1$s ms", NumberFormat.getIntegerInstance().format(ping)));
        long latency = stats.optLong(LATENCY, -1);
        if (latency > 0) parts.add(L10n.f("Latency %1$s s", tenths(latency / 1000.0)));
        long speed = stats.optLong(DOWNLOAD_SPEED, -1);
        if (speed > 0) parts.add(L10n.f("%1$s Mb/s", tenths(speed / 1000.0)));
        StringBuilder line = new StringBuilder();
        for (String part : parts) {
            if (line.length() > 0) line.append(" · ");
            line.append(part);
        }
        return line.toString();
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

    /** The room of the page with the most of itself on screen, or the only page known. */
    static String roomInFront(Activity activity) {
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
        return best == null ? null : LiveRecordChip.roomKey(LiveRecordChip.roomOf(best));
    }

    static void resetForTests() {
        HANDLER.removeCallbacksAndMessages(null);
        ticking = false;
        statsFailed = false;
        PAGES.clear();
        removeLabel();
        host = new WeakReference<>(null);
    }
}
