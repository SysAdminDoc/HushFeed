/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Audio only in a LIVE room's Video quality list (#265). TikTok sends LIVEs down to 360p, but a
 * room's stream data also carries an "ao" entry, its sound on its own, which TikTok's player
 * switches to when TikTok goes to the background in some setups. The picker never lists it, and
 * the player only takes it while the server turns on EnableOnlyAudioInAoRes. With the switch on,
 * both pickers (a room menu's Video quality, and the older one Share > Video quality opens on some
 * accounts) get an Audio only row in rooms whose stream has "ao", and the player's two
 * reads of that server flag answer on, so picking the row switches the room to its sound. TikTok's
 * own switch adds only_audio=1 to the room's address, so the picture stops downloading.
 */
@SuppressWarnings("unused")
public final class LiveAudioOnly {
    /** TikTok's quality key for a room's sound on its own. */
    static final String KEY = "ao";
    static final String QUALITY_CLASS = "com.bytedance.android.livesdkapi.depend.model.live.LiveCoreSDKData$Quality";

    /** The room the older picker's row builder loaded last, for {@link #pickerRows}. */
    private static volatile WeakReference<Object> pickerRoom = new WeakReference<>(null);
    /** The room Audio only was picked in, while it's still the pick, so its row shows as the current one. */
    private static volatile WeakReference<Object> audioOnlyRoom = new WeakReference<>(null);
    /** What {@link #pickerMark} answers before the patch fills it in, for the tests. */
    static volatile String unpatchedPickerMark;

    private LiveAudioOnly() {
    }

    /** True while the switch is on. Paused, or without the LIVE controls patch, it answers off. */
    public static boolean enabled() {
        return SettingsStatus.liveControlsEnabled && Settings.LIVE_AUDIO_ONLY.get();
    }

    /**
     * Called as the newer quality picker reads the room's quality list, with the room and that
     * list. While the switch is on and the room's stream has "ao", answers a copy of the list with
     * an Audio only row added at the end. The room's own list is never changed, since the player
     * and TikTok's logging read it too. Otherwise, and when anything about the room can't be read,
     * answers the list as it was.
     */
    public static LinkedList<Object> qualities(Object room, LinkedList<Object> list) {
        if (list == null || !enabled()) return list;
        // A pick right after this belongs to this room, as one in the older picker does (#265).
        pickerRoom = new WeakReference<>(room);
        try {
            if (offers(list)) return list;
            if (!LiveControls.streamHas(LiveRoomStream.streamData(room), KEY)) {
                Logger.printDebug(() -> "LIVE audio only: this room's stream has no sound on its own");
                return list;
            }
            Object row = row(list);
            if (row == null) return list;
            LinkedList<Object> copy = new LinkedList<>(list);
            copy.add(row);
            return copy;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not add Audio only to the LIVE quality list", failure);
            return list;
        }
    }

    /**
     * Called next, in the picker branch that keeps only the qualities the player lists, with the
     * list {@link #qualities} answered and the player's keys. When the list carries the Audio only
     * row and the player's keys don't, answers a copy of the keys with "ao" added, so the row
     * survives that check. Otherwise answers the keys as they were, null included.
     */
    public static List<Object> playerKeys(LinkedList<Object> list, List<Object> keys) {
        if (keys == null || list == null || !enabled()) return keys;
        try {
            if (keys.contains(KEY) || !offers(list)) return keys;
            List<Object> copy = new ArrayList<>(keys);
            copy.add(KEY);
            return copy;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE player's qualities", failure);
            return keys;
        }
    }

    /**
     * Called with each read of the player's EnableOnlyAudioInAoRes flag, a server setting that
     * decides whether a switch to "ao" goes through. Answers on while the switch is on, else the
     * server's value. Only a switch to "ao" reads it.
     */
    public static int switchAllowed(int server) {
        return enabled() ? 1 : server;
    }

    /** Whether the list already has a row keyed "ao". */
    static boolean offers(List<Object> list) {
        for (Object quality : list) {
            if (KEY.equals(Reflect.readField(quality, "sdkKey"))) return true;
        }
        return false;
    }

    /**
     * A new quality row keyed "ao", named Audio only, of the class the list's rows already are,
     * with a level under all of theirs. Null when that class can't be made or written.
     */
    static Object row(List<Object> list) throws Exception {
        Class<?> type = list.isEmpty() ? Class.forName(QUALITY_CLASS) : list.get(0).getClass();
        int lowest = Integer.MAX_VALUE;
        for (Object quality : list) {
            Object level = Reflect.readField(quality, "level");
            if (level instanceof Integer) lowest = Math.min(lowest, (Integer) level);
        }
        return quality(type, lowest == Integer.MAX_VALUE ? 0 : Math.max(0, lowest - 1));
    }

    /** A new quality row of {@code type} keyed "ao", named Audio only, at {@code level}. Null when it can't be written. */
    private static Object quality(Class<?> type, int levelValue) throws Exception {
        Field key = instanceField(type, "sdkKey");
        Field name = instanceField(type, "name");
        Field level = instanceField(type, "level");
        if (key == null || name == null) {
            Logger.printInfo(() -> "LIVE audio only: the quality row has no sdkKey or name on this TikTok version");
            return null;
        }
        Constructor<?> make = type.getDeclaredConstructor();
        make.setAccessible(true);
        Object row = make.newInstance();
        key.set(row, KEY);
        name.set(row, L10n.t("Audio only"));
        if (level != null && level.getType() == int.class) level.setInt(row, levelValue);
        return row;
    }

    /**
     * Called as the older picker's row builder loads the room (Share > Video quality on some
     * accounts), so {@link #pickerRows} knows which room the rows it gets next belong to. Weak, so
     * a closed room isn't kept.
     */
    public static void noteRoom(Object room) {
        pickerRoom = new WeakReference<>(room);
    }

    /**
     * Called with the rows the older picker is about to list, right after TikTok built them for the
     * room {@link #noteRoom} saw. While the switch is on and that room's stream has "ao", answers a
     * copy with an Audio only row at the end. The builder's own answer stays as it was, since TikTok
     * also reads it to find the next lower quality on a weak connection. Otherwise, and when
     * anything can't be read, answers the rows as they were.
     */
    public static List<Object> pickerRows(List<Object> rows) {
        if (rows == null || rows.isEmpty() || !enabled()) return rows;
        try {
            if (rowsOffer(rows)) return rows;
            if (!LiveControls.streamHas(LiveRoomStream.streamData(pickerRoom.get()), KEY)) {
                Logger.printDebug(() -> "LIVE audio only: the older picker's room has no sound on its own");
                return rows;
            }
            Object row = pickerRow(rows.get(0).getClass());
            if (row == null) return rows;
            if (sameRoom(pickerRoom.get(), audioOnlyRoom.get())) mark(row);
            List<Object> copy = new ArrayList<>(rows);
            copy.add(row);
            return copy;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not add Audio only to the older LIVE quality list", failure);
            return rows;
        }
    }

    /**
     * Called with each quality picked in either picker, after TikTok took it. An Audio only pick
     * ties it to the room whose picker opened last, so the older picker's row shows as the current
     * one when it opens again there, and a screen recording there knows the picture is stopped.
     * Any other pick ends that. Another room never matches, since Audio only lasts for the room it
     * was picked in.
     */
    static void notePick(String pick) {
        if (pick == null || pick.isEmpty()) return;
        audioOnlyRoom = new WeakReference<>(KEY.equals(pick) ? pickerRoom.get() : null);
    }

    /**
     * Whether {@code room} is playing Audio only, picked there and not changed since. A screen
     * recording of it would show a still picture.
     */
    public static boolean playing(Object room) {
        return enabled() && sameRoom(room, audioOnlyRoom.get());
    }

    /**
     * Whether two room objects are the same LIVE: the same object, or two copies with the same
     * nonzero id. Null never matches.
     */
    static boolean sameRoom(Object one, Object other) {
        if (one == null || other == null) return false;
        if (one == other) return true;
        Object id = Reflect.readField(one, "id");
        return id instanceof Long && (Long) id != 0L && id.equals(Reflect.readField(other, "id"));
    }

    /**
     * The name of the boolean field TikTok's older picker marks the current quality's row with.
     * The patch answers it from the row builder, where TikTok sets it on a row it made from a key.
     */
    static String pickerMark() {
        return unpatchedPickerMark;
    }

    /** Marks an older picker row as the current quality, when the patch named the field and the row has it. */
    private static void mark(Object row) throws IllegalAccessException {
        String name = pickerMark();
        Field field = name == null ? null : instanceField(row.getClass(), name);
        if (field == null || field.getType() != boolean.class) {
            Logger.printDebug(() -> "LIVE audio only: the older picker's row can't be marked as the current quality");
            return;
        }
        field.setBoolean(row, true);
    }

    /**
     * {@code type}'s field {@code name} when it belongs to each row rather than to the class, so a
     * write lands on the row. Null otherwise: a static field could be final, which Android 17
     * refuses to have written.
     */
    private static Field instanceField(Class<?> type, String name) {
        Field field = Reflect.field(type, name);
        return field == null || Modifier.isStatic(field.getModifiers()) ? null : field;
    }

    /** Whether one of the older picker's rows already carries the key "ao" in a text field. */
    static boolean rowsOffer(List<Object> rows) throws IllegalAccessException {
        for (Object row : rows) {
            if (row == null) continue;
            for (Field field : row.getClass().getDeclaredFields()) {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                if (KEY.equals(field.get(row))) return true;
            }
        }
        return false;
    }

    /**
     * An older picker row of {@code type} for Audio only, made through its constructor that takes a
     * quality row, the way TikTok makes the others. Null when it has none.
     */
    static Object pickerRow(Class<?> type) throws Exception {
        for (Constructor<?> make : type.getDeclaredConstructors()) {
            Class<?>[] parameters = make.getParameterTypes();
            if (parameters.length != 1 || parameters[0].isPrimitive() || parameters[0] == String.class) continue;
            Object quality = quality(parameters[0], 0);
            if (quality == null) continue;
            make.setAccessible(true);
            return make.newInstance(quality);
        }
        Logger.printInfo(() -> "LIVE audio only: the older picker's row has no quality constructor on this TikTok version");
        return null;
    }
}
