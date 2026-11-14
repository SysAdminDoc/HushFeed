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

import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The Record button in TikTok's own picture-in-picture window for a LIVE (#413). TikTok builds
 * that window's buttons itself (one, play and pause) and the hook adds this one to its list just
 * before handing it over. Starting, stopping and saving change the button, so the window then
 * gets TikTok's buttons back with the new one. Only stream recordings show it, since a screen
 * recording would film the home screen behind the window. Main thread only.
 */
@SuppressWarnings("unused")
public final class LivePipRecord {
    private LivePipRecord() {
    }

    static final String ACTION = "app.morphe.extension.tiktok.LIVE_PIP_RECORD";
    /**
     * The window's button is a broadcast inside TikTok, which any app could send. Only the
     * button's PendingIntent carries this, so only the button starts or stops a recording.
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

    /** TikTok's own buttons, as it last set them, and the activity it set them on. */
    private static List<Object> theirs = Collections.emptyList();
    private static WeakReference<Activity> theirsOn = new WeakReference<>(null);
    private static int shown = HIDDEN;
    private static BroadcastReceiver receiver;

    /** Called with TikTok's buttons just before it sets them on the window. */
    public static void addTo(List<Object> actions) {
        try {
            if (actions == null || Build.VERSION.SDK_INT < 26) return;
            Activity activity = LiveRecordChip.hostActivity();
            theirs = new ArrayList<>(actions);
            theirsOn = new WeakReference<>(activity);
            shown = HIDDEN;
            int state = LiveRecordChip.pipButtonState();
            Context context = activity != null ? activity : Utils.getContext();
            if (context == null || !fits(state, actions.size(), room(activity))) return;
            listen(context);
            actions.add(button(context, state));
            shown = state;
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
            if (state == shown || activity.isFinishing() || activity.isDestroyed()) return;
            int room = room(activity);
            List<RemoteAction> actions = buttons(activity, theirs, state, room);
            boolean added = actions.size() > buttons(activity, theirs, HIDDEN, room).size();
            if (added) listen(activity);
            activity.setPictureInPictureParams(new PictureInPictureParams.Builder().setActions(actions).build());
            shown = added ? state : HIDDEN;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not update Record in the LIVE window", failure);
        }
    }

    /** TikTok's buttons, then the one for {@code state} when it has one and there's room. */
    @RequiresApi(26)
    static List<RemoteAction> buttons(Context context, List<?> theirs, int state, int room) {
        List<RemoteAction> actions = new ArrayList<>();
        for (Object action : theirs) {
            if (action instanceof RemoteAction) actions.add((RemoteAction) action);
        }
        if (fits(state, actions.size(), room)) actions.add(button(context, state));
        return actions;
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
    }

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
    @RequiresApi(23)
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
            return intent != null && ACTION.equals(intent.getAction())
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
                if (fromTheButton(intent)) Utils.runOnMainThread(LiveRecordChip::onPipButton);
            }
        };
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }
}
