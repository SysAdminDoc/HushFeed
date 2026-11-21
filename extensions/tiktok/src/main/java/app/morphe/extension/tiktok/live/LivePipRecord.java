/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The Record button in TikTok's own picture-in-picture window for a LIVE (#413). TikTok builds
 * that window's buttons itself (one, play and pause) and the hook adds this one to its list just
 * before handing it over. Starting, stopping and saving change the button, so the window then
 * gets TikTok's buttons back with the new one. Only stream recordings show it, since a screen
 * recording would film the home screen behind the window.
 *
 * <p>With {@code Keep watching in a small window} on, the window also gets previous and next
 * arrows that move to the LIVE above or below, the way a swipe does in the room (#413). They sit
 * either side of TikTok's own button, or of Record when that shows, since a window holds three.
 * A LIVE opened on its own, outside TikTok's pager of rooms, gets no arrows. Main thread only.
 */
@SuppressWarnings("unused")
public final class LivePipRecord {
    private LivePipRecord() {
    }

    static final String ACTION = "app.morphe.extension.tiktok.LIVE_PIP_RECORD";
    static final String ACTION_PREVIOUS = "app.morphe.extension.tiktok.LIVE_PIP_PREVIOUS";
    static final String ACTION_NEXT = "app.morphe.extension.tiktok.LIVE_PIP_NEXT";
    /**
     * The window's buttons are broadcasts inside TikTok, which any app could send. Only the
     * buttons' PendingIntents carry this, so only a button starts a recording or changes the room.
     */
    static final String EXTRA_TOKEN = "app.morphe.extension.tiktok.LIVE_PIP_RECORD_TOKEN";
    private static final String TOKEN = UUID.randomUUID().toString();

    static final int HIDDEN = 0;
    static final int RECORD = 1;
    static final int STOP = 2;
    static final int SAVING = 3;

    /** Phones allow three buttons; this is the count when the activity can't say. */
    private static final int ROOM = 3;
    private static final int ICON_PX = 72;
    /** How long a room change takes before the arrows are checked again. */
    private static final long SETTLED_MS = 700;

    /** No arrows: a LIVE outside a pager, the small window switch off, or no room for them. */
    static final Rooms NO_ARROWS = new Rooms(false, false, false);

    /** Whether the window shows arrows, and which way there's a LIVE to go to. */
    static final class Rooms {
        final boolean arrows;
        final boolean back;
        final boolean forward;

        Rooms(boolean arrows, boolean back, boolean forward) {
            this.arrows = arrows;
            this.back = back;
            this.forward = forward;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Rooms)) return false;
            Rooms that = (Rooms) other;
            return arrows == that.arrows && back == that.back && forward == that.forward;
        }

        @Override public int hashCode() {
            return (arrows ? 4 : 0) | (back ? 2 : 0) | (forward ? 1 : 0);
        }
    }

    /** Whether a view class is TikTok's pager of LIVE rooms, by the methods only it carries. */
    private static final Map<Class<?>, Boolean> PAGER_CLASSES = new WeakHashMap<>();

    /** TikTok's own buttons, as it last set them, and the activity it set them on. */
    private static List<Object> theirs = Collections.emptyList();
    private static WeakReference<Activity> theirsOn = new WeakReference<>(null);
    private static int shown = HIDDEN;
    private static Rooms shownRooms = NO_ARROWS;
    private static BroadcastReceiver receiver;

    /** Called with TikTok's buttons just before it sets them on the window. */
    public static void addTo(List<Object> actions) {
        try {
            if (actions == null || Build.VERSION.SDK_INT < 26) return;
            Activity activity = LiveRecordChip.hostActivity();
            theirs = new ArrayList<>(actions);
            theirsOn = new WeakReference<>(activity);
            shown = HIDDEN;
            shownRooms = NO_ARROWS;
            int state = LiveRecordChip.pipButtonState();
            Rooms rooms = rooms(activity);
            Context context = activity != null ? activity : Utils.getContext();
            if (context == null) return;
            int room = room(activity);
            if (!rooms.arrows && !fits(state, actions.size(), room)) return;
            List<RemoteAction> mine = buttons(context, theirs, state, room, rooms);
            listen(context);
            actions.clear();
            actions.addAll(mine);
            shown = recordShown(theirs, state, room, rooms) ? state : HIDDEN;
            shownRooms = rooms;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not add Record to the LIVE window", failure);
        }
    }

    /**
     * Gives the window the button for the recording's state now, with TikTok's own ones. Only
     * the buttons are sent, so the window keeps its shape and everything else TikTok set.
     */
    static void update(Activity activity) {
        if (Build.VERSION.SDK_INT < 26 || activity == null || activity != theirsOn.get()) return;
        try {
            int state = LiveRecordChip.pipButtonState();
            Rooms rooms = rooms(activity);
            if ((state == shown && rooms.equals(shownRooms)) || activity.isFinishing() || activity.isDestroyed()) return;
            int room = room(activity);
            List<RemoteAction> actions = buttons(activity, theirs, state, room, rooms);
            boolean added = recordShown(theirs, state, room, rooms);
            if (added || rooms.arrows) listen(activity);
            activity.setPictureInPictureParams(new PictureInPictureParams.Builder().setActions(actions).build());
            shown = added ? state : HIDDEN;
            shownRooms = rooms;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not update Record in the LIVE window", failure);
        }
    }

    /** TikTok's buttons, then the one for {@code state} when it has one and there's room. */
    @RequiresApi(26)
    static List<RemoteAction> buttons(Context context, List<?> theirs, int state, int room) {
        return buttons(context, theirs, state, room, NO_ARROWS);
    }

    /**
     * The window's buttons. Without arrows, TikTok's buttons and then the one for {@code state}
     * when there's room. With them, previous and next either side of one button in the middle:
     * the one for {@code state} when it has one, else TikTok's first. In a window with room for two,
     * that middle button and next, since moving on is what the arrows are for. An arrow with no
     * LIVE its way shows dimmed, so the buttons don't shift between rooms.
     */
    @RequiresApi(26)
    static List<RemoteAction> buttons(Context context, List<?> theirs, int state, int room, Rooms rooms) {
        List<RemoteAction> own = new ArrayList<>();
        for (Object action : theirs) {
            if (action instanceof RemoteAction) own.add((RemoteAction) action);
        }
        if (!rooms.arrows || room < 2) {
            if (fits(state, own.size(), room)) own.add(button(context, state));
            return own;
        }
        RemoteAction middle = state != HIDDEN ? button(context, state) : own.isEmpty() ? null : own.get(0);
        List<RemoteAction> actions = new ArrayList<>(3);
        if (room >= 3) actions.add(arrow(context, ACTION_PREVIOUS, rooms.back));
        if (middle != null) actions.add(middle);
        actions.add(arrow(context, ACTION_NEXT, rooms.forward));
        return actions;
    }

    /** Whether {@link #buttons} puts the Record, Stop or saving button in. */
    static boolean recordShown(List<?> theirs, int state, int room, Rooms rooms) {
        if (state == HIDDEN) return false;
        if (rooms.arrows && room >= 2) return true;
        int taken = 0;
        for (Object action : theirs) {
            if (action instanceof RemoteAction) taken++;
        }
        return fits(state, taken, room);
    }

    /** Previous or next LIVE, dimmed when there's none that way. */
    @RequiresApi(26)
    static RemoteAction arrow(Context context, String name, boolean enabled) {
        boolean next = ACTION_NEXT.equals(name);
        Intent intent = new Intent(name).setPackage(context.getPackageName()).putExtra(EXTRA_TOKEN, TOKEN);
        PendingIntent pending = PendingIntent.getBroadcast(context, next ? 2 : 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String label = next ? L10n.t("Next LIVE") : L10n.t("Previous LIVE");
        RemoteAction action = new RemoteAction(Icon.createWithResource("android",
                next ? android.R.drawable.ic_media_next : android.R.drawable.ic_media_previous), label, label, pending);
        action.setEnabled(enabled);
        return action;
    }

    /** Whether arrows go in this activity's window, and which way they can go. */
    static Rooms rooms(Activity activity) {
        if (activity == null || !SettingsStatus.pictureInPictureEnabled || !Settings.PICTURE_IN_PICTURE.get()) {
            return NO_ARROWS;
        }
        View pager = pagerIn(activity);
        if (pager == null) return NO_ARROWS;
        int current = currentItem(pager);
        int count = pageCount(pager);
        if (current < 0 || count <= 1) return NO_ARROWS;
        return new Rooms(true, current > 0, current < count - 1);
    }

    /** TikTok's pager of LIVE rooms in the activity, or null when the LIVE isn't in one. */
    static View pagerIn(Activity activity) {
        try {
            return pagerIn(activity.getWindow().getDecorView());
        } catch (RuntimeException noWindow) {
            return null;
        }
    }

    private static View pagerIn(View view) {
        if (view == null) return null;
        if (isPager(view.getClass())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = pagerIn(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * TikTok renames the LIVE pager's class (47.9.3's is a bare ViewGroup), but it keeps the pager
     * methods and two of its own, which no other pager in the app has.
     */
    static boolean isPager(Class<?> type) {
        Boolean known = PAGER_CLASSES.get(type);
        if (known != null) return known;
        boolean pager;
        try {
            type.getMethod("setIsCanChangePage", boolean.class);
            type.getMethod("setCurrentItemWithDefaultVelocity", int.class);
            type.getMethod("setCurrentItem", int.class);
            pager = type.getMethod("getCurrentItem").getReturnType() == int.class;
        } catch (NoSuchMethodException | RuntimeException notIt) {
            pager = false;
        }
        PAGER_CLASSES.put(type, pager);
        return pager;
    }

    private static int currentItem(View pager) {
        try {
            Object item = pager.getClass().getMethod("getCurrentItem").invoke(pager);
            return item instanceof Integer ? (Integer) item : -1;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return -1;
        }
    }

    private static int pageCount(View pager) {
        try {
            Object adapter = pager.getClass().getMethod("getAdapter").invoke(pager);
            if (adapter == null) return 0;
            Method count = adapter.getClass().getMethod("getCount");
            count.setAccessible(true);
            Object pages = count.invoke(adapter);
            return pages instanceof Integer ? (Integer) pages : 0;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return 0;
        }
    }

    /** An arrow was pressed: the room above ({@code -1}) or below ({@code 1}), as a swipe would. */
    static void onMove(int direction) {
        Activity activity = theirsOn.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View pager = pagerIn(activity);
        if (pager == null) {
            Logger.printDebug(() -> "LIVE window arrow: no pager of rooms");
            return;
        }
        int from = currentItem(pager);
        int count = pageCount(pager);
        int target = from + direction;
        Logger.printDebug(() -> "LIVE window arrow: " + direction + " from room " + from + " of " + count
                + ", pager " + pager.getWidth() + "x" + pager.getHeight() + " shown " + pager.isShown());
        if (target < 0 || target >= count) return;
        try {
            pager.getClass().getMethod("setCurrentItem", int.class).invoke(pager, target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Could not move the LIVE window to another room", failure);
            return;
        }
        Logger.printDebug(() -> "LIVE window arrow: asked for room " + target + ", pager now on " + currentItem(pager));
        // The new room may set TikTok's buttons again, and either way the arrows follow it.
        Utils.runOnMainThreadDelayed(() -> update(activity), SETTLED_MS);
    }

    static boolean fits(int state, int taken, int room) {
        return state != HIDDEN && taken < room;
    }

    /** The activity went away, and with it the window TikTok set its buttons on. */
    static void forget(Activity activity) {
        if (activity != theirsOn.get()) return;
        theirs = Collections.emptyList();
        theirsOn = new WeakReference<>(null);
        shown = HIDDEN;
        shownRooms = NO_ARROWS;
    }

    @RequiresApi(26)
    private static int room(Activity activity) {
        if (activity == null) return ROOM;
        try {
            return activity.getMaxNumPictureInPictureActions();
        } catch (RuntimeException unknown) {
            return ROOM;
        }
    }

    /** The button for {@code state}: Record, Stop, or a dimmed one while the last one saves. */
    @RequiresApi(26)
    static RemoteAction button(Context context, int state) {
        Intent intent = new Intent(ACTION).setPackage(context.getPackageName()).putExtra(EXTRA_TOKEN, TOKEN);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title;
        String description;
        if (state == STOP) {
            title = L10n.t("Stop");
            description = L10n.t("Stop recording this LIVE");
        } else if (state == SAVING) {
            title = L10n.t("Saving the recording");
            description = title;
        } else {
            title = L10n.t("Record");
            description = L10n.t("Record this LIVE from now");
        }
        RemoteAction action = new RemoteAction(icon(state), title, description, pending);
        action.setEnabled(state != SAVING);
        return action;
    }

    /** A dot to record and a square to stop, drawn white like the window's other buttons. */
    private static Icon icon(int state) {
        Bitmap bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        float middle = ICON_PX / 2f;
        if (state == STOP) {
            float half = ICON_PX * 0.23f;
            canvas.drawRoundRect(new RectF(middle - half, middle - half, middle + half, middle + half),
                    ICON_PX * 0.04f, ICON_PX * 0.04f, paint);
        } else {
            canvas.drawCircle(middle, middle, ICON_PX * 0.3f, paint);
        }
        return Icon.createWithBitmap(bitmap);
    }

    static boolean fromTheButton(Intent intent) {
        try {
            if (intent == null) return false;
            String action = intent.getAction();
            return (ACTION.equals(action) || ACTION_PREVIOUS.equals(action) || ACTION_NEXT.equals(action))
                    && TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN));
        } catch (RuntimeException unreadable) {
            // Another app's extras can name a class this process can't unparcel.
            return false;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void listen(Context context) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context received, Intent intent) {
                if (!fromTheButton(intent)) return;
                String action = intent.getAction();
                if (ACTION_PREVIOUS.equals(action)) Utils.runOnMainThread(() -> onMove(-1));
                else if (ACTION_NEXT.equals(action)) Utils.runOnMainThread(() -> onMove(1));
                else Utils.runOnMainThread(LiveRecordChip::onPipButton);
            }
        };
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        IntentFilter filter = new IntentFilter(ACTION);
        filter.addAction(ACTION_PREVIOUS);
        filter.addAction(ACTION_NEXT);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }
}
