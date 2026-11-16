/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LIVE name alerts (#424). Every message a LIVE room gets from TikTok's server passes through one
 * dispatch in the room's message manager, and the hook there hands each one here. A chat message
 * that mentions you (TikTok's own mention list, or your name typed in it) and a caption line where
 * your name is said out loud both raise an alert: a notification when TikTok's notifications are
 * on, and a banner in the room when they're off.
 *
 * <p>The names are your handle, your TikTok name and the ones you add in settings. A name matches
 * whole words, ignoring case and accents, except in scripts written without spaces, where it
 * matches anywhere in the line.
 */
@SuppressWarnings("unused")
public final class LiveMentions {
    private LiveMentions() {
    }

    static final String FAMILY = "live name alerts";
    static final String CHAT_MESSAGE = "com.bytedance.android.livesdk.model.message.ChatMessage";
    static final String CAPTION_MESSAGE = "com.bytedance.android.livesdk.model.message.CaptionMessage";
    static final String CHANNEL = "hushfeed_live_name_alerts";
    static final int NOTIFICATION_ID = 0x4C4D41;
    /** One alert per room in this long, so a host who keeps saying your name doesn't buzz on. */
    static final long QUIET_MS = 30_000;
    private static final long NAMES_MS = 10_000;
    private static final int SEEN_LIMIT = 256;
    /** Auto names shorter than this (a two-letter TikTok name) would match too much to be useful. */
    static final int SHORTEST_OWN_NAME = 3;
    /** Two characters already make a whole name in Chinese, Japanese and the like (小明). */
    static final int SHORTEST_OWN_UNSPACED_NAME = 2;
    private static final int SNIPPET_LIMIT = 160;

    /** The alerts a test reads instead of a notification or banner; null in the app. */
    static volatile List<Alert> alertsForTests;
    /** Stands in for the clock in tests; zero reads the real one. */
    static volatile long nowForTests;

    private static final Object LOCK = new Object();
    // Guarded by LOCK.
    private static final Map<String, Boolean> SEEN = new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    };
    private static final Map<String, Long> LAST_ALERT = new LinkedHashMap<String, Long>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 32;
        }
    };
    private static List<String> names = Collections.emptyList();
    private static String namesFrom;
    private static long namesAt;
    private static boolean channelMade;

    /** What an alert says: who said it and the line, and where it came from. */
    static final class Alert {
        final String room;
        final String who;
        final String text;
        final boolean spoken;

        Alert(String room, String who, String text, boolean spoken) {
            this.room = room;
            this.who = who;
            this.text = text;
            this.spoken = spoken;
        }

        String line() {
            return who == null || who.isEmpty() ? text : who + ": " + text;
        }
    }

    static boolean enabled() {
        return SettingsStatus.liveControlsEnabled && Settings.LIVE_NAME_ALERTS.get() && !Setting.isPaused();
    }

    /** Called with each message as TikTok's LIVE message manager starts dispatching it. */
    public static void onMessage(Object message) {
        try {
            if (message == null || !enabled()) return;
            String type = message.getClass().getName();
            if (CHAT_MESSAGE.equals(type)) {
                chat(message);
            } else if (CAPTION_MESSAGE.equals(type)) {
                caption(message);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check a LIVE message for your name", failure);
        }
    }

    private static void chat(Object message) {
        String me = SignedInUser.id();
        Object sender = Reflect.requiredField(message, "userInfo", FAMILY);
        if (me != null && me.equals(userId(sender))) return;
        String text = asString(Reflect.requiredField(message, "content", FAMILY));
        boolean mentioned = me != null && (mentions(Reflect.requiredField(message, "mentionUserList", FAMILY), me)
                || me.equals(userId(Reflect.readField(message, "atUser"))));
        if (!mentioned && (text == null || matchingName(text, currentNames()) == null)) return;
        String room = roomOf(message);
        if (!firstSight("chat:" + room + ':' + Reflect.invoke(message, "getMessageId"))) return;
        alert(new Alert(room, userName(sender), text == null ? "" : text, false));
    }

    private static void caption(Object message) {
        Object contents = Reflect.requiredField(message, "content", FAMILY);
        if (!(contents instanceof List)) return;
        StringBuilder joined = new StringBuilder();
        for (Object part : new ArrayList<>((List<?>) contents)) {
            String text = asString(Reflect.readField(part, "content"));
            if (text == null || text.trim().isEmpty()) continue;
            if (joined.length() > 0) joined.append('\n');
            joined.append(text.trim());
        }
        if (joined.length() == 0) return;
        String line = joined.toString();
        if (matchingName(line, currentNames()) == null) return;
        String room = roomOf(message);
        // A caption line arrives in pieces as it's said, all with one sentence id: one alert per line.
        if (!firstSight("caption:" + room + ':' + Reflect.readField(message, "sentenceId"))) return;
        String speaker = asString(Reflect.readField(message, "speakerUserName"));
        int firstLine = line.indexOf('\n');
        alert(new Alert(room, speaker, firstLine < 0 ? line : line.substring(0, firstLine), true));
    }

    /**
     * Called with a line speech recognition heard in {@code room}'s sound, for rooms without
     * captions. Whether it named you; the room's quiet time still decides whether you hear it.
     */
    static boolean heard(String room, String said) {
        if (said == null || room == null || !enabled()) return false;
        String line = said.trim();
        if (line.isEmpty() || matchingName(line, currentNames()) == null) return false;
        alert(new Alert(room, null, line, true));
        return true;
    }

    // ---- Names ----

    /** Your handle, your TikTok name and the names from settings, normalized, refreshed now and then. */
    static List<String> currentNames() {
        String extra = Settings.LIVE_NAME_ALERT_NAMES.get();
        long now = now();
        synchronized (LOCK) {
            if (namesAt != 0 && now - namesAt < NAMES_MS && String.valueOf(extra).equals(namesFrom)) return names;
        }
        List<String> built = namesFrom(SignedInUser.handle(), SignedInUser.nickname(), extra);
        synchronized (LOCK) {
            names = built;
            namesFrom = String.valueOf(extra);
            namesAt = now;
        }
        return built;
    }

    static List<String> namesFrom(String handle, String nickname, String extra) {
        Set<String> found = new LinkedHashSet<>();
        if (extra != null) {
            for (String piece : extra.split("[,،、，]")) {
                String name = normalize(piece);
                if (name.codePointCount(0, name.length()) >= 2) found.add(name);
            }
        }
        for (String own : new String[] {handle, nickname}) {
            String name = normalize(own);
            int shortest = unspaced(name) ? SHORTEST_OWN_UNSPACED_NAME : SHORTEST_OWN_NAME;
            if (name.codePointCount(0, name.length()) >= shortest) found.add(name);
        }
        return new ArrayList<>(found);
    }

    /** The first of {@code names} that {@code text} says, or null. */
    static String matchingName(String text, List<String> names) {
        if (text == null || names.isEmpty()) return null;
        String said = normalize(text);
        if (said.isEmpty()) return null;
        String padded = ' ' + said + ' ';
        for (String name : names) {
            if (name.isEmpty()) continue;
            if (unspaced(name) ? said.contains(name) : padded.contains(' ' + name + ' ')) return name;
        }
        return null;
    }

    /** Lower case, accents off, and every run of anything that isn't a letter or digit as one space. */
    static String normalize(String text) {
        if (text == null) return "";
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean space = false;
        for (int i = 0; i < decomposed.length(); ) {
            int c = decomposed.codePointAt(i);
            i += Character.charCount(c);
            int kind = Character.getType(c);
            if (kind == Character.NON_SPACING_MARK || kind == Character.ENCLOSING_MARK) continue;
            // A spacing mark is part of its letter (the vowel signs of Hindi or Burmese, say).
            if (Character.isLetterOrDigit(c) || kind == Character.COMBINING_SPACING_MARK) {
                if (space && out.length() > 0) out.append(' ');
                space = false;
                out.appendCodePoint(Character.toLowerCase(c));
            } else {
                space = true;
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Whether a name is written in a script without spaces between words, so it matches anywhere. */
    static boolean unspaced(String name) {
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            if (unspacedScript(c)) return true;
            i += Character.charCount(c);
        }
        return false;
    }

    private static boolean unspacedScript(int c) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(c);
        } catch (IllegalArgumentException notACodePoint) {
            return false;
        }
        switch (script) {
            case HAN:
            case HIRAGANA:
            case KATAKANA:
            case THAI:
            case LAO:
            case KHMER:
            case MYANMAR:
                return true;
            default:
                return false;
        }
    }

    // ---- Alerts ----

    private static boolean firstSight(String key) {
        synchronized (LOCK) {
            return SEEN.put(key, Boolean.TRUE) == null;
        }
    }

    /** Whether this room is past its quiet time, which starts again when it says yes. */
    private static boolean roomMayAlert(String room) {
        long now = now();
        synchronized (LOCK) {
            Long last = LAST_ALERT.get(room);
            if (last != null && now - last < QUIET_MS) return false;
            LAST_ALERT.put(room, now);
            return true;
        }
    }

    private static void alert(Alert alert) {
        if (!roomMayAlert(alert.room)) return;
        List<Alert> recorded = alertsForTests;
        if (recorded != null) {
            recorded.add(alert);
            return;
        }
        Context context = Utils.getContext();
        if (context == null) return;
        if (notificationsOn(context)) {
            notify(context, alert);
        } else {
            Utils.runOnMainThread(() -> banner(alert));
        }
    }

    static boolean notificationsOn(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled());
    }

    static void notify(Context context, Alert alert) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            makeChannel(context, manager);
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_VIBRATE);
        }
        String line = snippet(alert.line());
        builder.setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle(L10n.t(context, "Your name came up in a LIVE"))
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(line))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true);
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            builder.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException refused) {
            // Notifications were turned off between the check and the post: the banner says it.
            Utils.runOnMainThread(() -> banner(alert));
        }
    }

    /** Vibrates without a sound, since a LIVE is playing; Android's channel settings can add one. */
    private static void makeChannel(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        synchronized (LOCK) {
            if (channelMade) return;
            channelMade = true;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                L10n.t(context, "LIVE name alerts"), NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    /** The in-room banner, for when TikTok's notifications are off. Nothing shows with TikTok closed. */
    static void banner(Alert alert) {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        String title = L10n.t(activity, "Your name came up in a LIVE");
        BlockAuthorOverlay.showNoticeBanner(root,
                new StringBuilder(title).append('\n').append(snippet(alert.line())).toString());
    }

    static String snippet(String text) {
        String flat = text.replace('\n', ' ').trim();
        if (flat.codePointCount(0, flat.length()) <= SNIPPET_LIMIT) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, SNIPPET_LIMIT)).trim() + "…";
    }

    // ---- TikTok's models ----

    private static boolean mentions(Object list, String me) {
        if (!(list instanceof List)) return false;
        for (Object id : new ArrayList<>((List<?>) list)) {
            if (id != null && me.equals(String.valueOf(id))) return true;
        }
        return false;
    }

    private static String userId(Object user) {
        if (user == null) return null;
        Object id = Reflect.readField(user, "id");
        if (id instanceof Long && (Long) id != 0L) return String.valueOf(id);
        return asString(Reflect.readField(user, "idStr"));
    }

    private static String userName(Object user) {
        if (user == null) return null;
        return Reflect.firstNonBlank(asString(Reflect.readField(user, "nickName")),
                asString(Reflect.readField(user, "displayId")));
    }

    /** The room the message is for, from its common block, or "" when it has none. */
    static String roomOf(Object message) {
        Object common = Reflect.readField(message, "baseMessage");
        Object room = Reflect.readField(common, "roomId");
        return room == null ? "" : String.valueOf(room);
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static long now() {
        long forTests = nowForTests;
        return forTests != 0 ? forTests : SystemClock.elapsedRealtime();
    }

    static void resetForTests() {
        synchronized (LOCK) {
            SEEN.clear();
            LAST_ALERT.clear();
            names = Collections.emptyList();
            namesFrom = null;
            namesAt = 0;
            channelMade = false;
        }
        alertsForTests = null;
        nowForTests = 0;
    }
}
