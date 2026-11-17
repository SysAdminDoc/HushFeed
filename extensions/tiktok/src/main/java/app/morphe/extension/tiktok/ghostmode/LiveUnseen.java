/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.follow.FollowDiagnostics;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watch LIVEs unseen (#420). Opening a LIVE room sends TikTok's room enter request, and that is
 * what puts you on the host's viewer list and the "joined" line in the chat. While Ghost mode and
 * its Watch LIVEs unseen switch are on, the hook in TikTok's network layer turns every room enter
 * down on the phone, as a failed connection, before anything is sent. The stream itself comes
 * from the room TikTok already loaded with the feed.
 *
 * <p>While hidden, the room shows the video and the eye, and nothing a successful enter builds
 * (the chat, the host bar, the close button). The eye in the room ({@code LiveUnseenButton})
 * lets the room you're in see you: the room page enters again through TikTok's own enter, which
 * builds the rest of the room, and the room's later enters go out as usual until TikTok is
 * closed.
 *
 * <p>The hook fails open. With a switch off, Hushfeed paused, the settings not loaded yet, or
 * the request unreadable, the request goes out as it always does.
 */
public final class LiveUnseen {
    static final String HOOK_FAMILY = "ghost mode";
    static final String ENTER_PATH = "/webcast/room/enter/";
    /** TikTok's own call the hook stands in front of, made later when a room is revealed. */
    static final String TWIN =
            "com_bytedance_retrofit2_CallServerInterceptor__executeCall$___twin___";
    /** How many held enters are kept to send later; the oldest goes first. */
    static final int HELD_LIMIT = 8;
    static final String HELD_MESSAGE = "Hushfeed Ghost mode kept this LIVE from seeing you";
    /** The reason TikTok's room page gives itself for leaving when its room enter failed. */
    static final String ENTER_FAILED = "ENTER_FAILED";
    /** The hook in front of the room page's leave, by its name in the diagnostics. */
    static final String ROOM_KEPT = "live room kept";
    /** The bridge into the room page's own enter, by its name in the diagnostics. */
    static final String ROOM_ENTERED_AGAIN = "live room entered again";
    /** How long after an enter held with no readable room a failed enter is still taken as ours. */
    static final long UNKNOWN_ROOM_WINDOW_MS = 15_000;
    private static final long NEVER = -1;

    /** Rooms the eye was tapped in, whose enters go out. Until TikTok is closed. */
    private static final Set<String> REVEALED = ConcurrentHashMap.newKeySet();
    /** The latest enter held for each room, guarded by itself. */
    private static final Map<String, HeldEnter> HELD = new LinkedHashMap<>();
    /** When an enter was last held, and when one was last held without a readable room. */
    private static volatile long heldAt = NEVER;
    private static volatile long unknownHeldAt = NEVER;
    private static volatile boolean loggedHold;
    private static volatile boolean loggedKept;

    /** TikTok's network interceptor and chain for one enter it was about to send. */
    static final class HeldEnter {
        final Object interceptor;
        final Object chain;

        HeldEnter(Object interceptor, Object chain) {
            this.interceptor = interceptor;
            this.chain = chain;
        }
    }

    /** The room page entering a room again; the patch's bridge, swapped out in tests. */
    interface Reentry {
        boolean enter(Object page, long room);
    }

    static volatile Reentry reentry = LiveUnseen::enterAgain;

    /** What happened to a room when the eye was tapped. */
    public enum Reveal {
        /** The room's held enter went out: the host sees you now. */
        SENT,
        /** No enter was held for it: you'll be seen the next time you open it. */
        NEXT_TIME,
        /** Its held enter couldn't be sent: reopening the room sends a new one. */
        FAILED,
    }

    private LiveUnseen() {
    }

    /** Whether the switch is on, read where the hook runs, so flipping it needs no restart. */
    public static boolean enabled() {
        try {
            return Utils.getContext() != null && SettingsStatus.ghostModeEnabled
                    && Settings.GHOST_MODE.get() && Settings.GHOST_HIDE_LIVE_VIEWS.get();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /**
     * Called by TikTok's network interceptor right before it sends a request, with itself and its
     * chain. Throws for a LIVE room enter while it's held, which TikTok handles as a connection
     * that failed, and returns for everything else.
     */
    public static void holdEnter(Object interceptor, Object chain) throws IOException {
        if (!enabled()) return;
        String room;
        try {
            Object request = Reflect.requiredField(interceptor, "mOriginalRequest", HOOK_FAMILY);
            Object path = Reflect.required(request, "getPath", HOOK_FAMILY);
            if (!isEnter(path instanceof String ? (String) path : null)) return;
            room = FollowDiagnostics.liveRoomIdOf(request);
            if (!GhostMode.holdLiveEnter(room != null && REVEALED.contains(room))) return;
            long now = SystemClock.elapsedRealtime();
            heldAt = now;
            if (room != null) keep(room, new HeldEnter(interceptor, chain));
            else unknownHeldAt = now;
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, GhostMode.LIVE_ENTER, unreadable);
            return;
        }
        if (!loggedHold) {
            loggedHold = true;
            Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                    () -> "Ghost mode held back a LIVE room enter");
        }
        throw new IOException(HELD_MESSAGE);
    }

    /**
     * Called by TikTok's LIVE room page at the start of its leave, with itself and the reason.
     * TikTok takes the enter Ghost mode held back as a failed one and leaves the room for the
     * next LIVE with an "Unstable connection" toast. Returns true to stay in the room when the
     * reason is a failed enter that Ghost mode held for this room; any other leave goes ahead.
     */
    public static boolean keepRoom(Object page, Object reason) {
        try {
            if (!(reason instanceof Enum) || !ENTER_FAILED.equals(((Enum<?>) reason).name())) return false;
            if (!enabled()) return false;
            String room = LiveRecordChip.roomKey(LiveRecordChip.roomOf(page));
            if (!heldFor(room, SystemClock.elapsedRealtime())) return false;
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, ROOM_KEPT, unreadable);
            return false;
        }
        HookStatus.bound(HOOK_FAMILY, ROOM_KEPT);
        if (!loggedKept) {
            loggedKept = true;
            Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                    () -> "Ghost mode kept a LIVE open after holding back its room enter");
        }
        return true;
    }

    /**
     * Whether the failed enter of {@code room} at {@code now} was one Ghost mode held: its enter
     * is still held, or a recent one was held without a readable room. A page with no readable
     * room counts any recent hold.
     */
    static boolean heldFor(String room, long now) {
        if (room == null) return recent(heldAt, now);
        synchronized (HELD) {
            if (HELD.containsKey(room)) return true;
        }
        return recent(unknownHeldAt, now);
    }

    private static boolean recent(long at, long now) {
        return at != NEVER && now >= at && now - at <= UNKNOWN_ROOM_WINDOW_MS;
    }

    /** A room enter or its newer and prefetch forms, by the request's path. */
    static boolean isEnter(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).contains(ENTER_PATH);
    }

    private static void keep(String room, HeldEnter held) {
        synchronized (HELD) {
            HELD.remove(room);
            HELD.put(room, held);
            Iterator<String> oldest = HELD.keySet().iterator();
            while (HELD.size() > HELD_LIMIT && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** Whether the host of {@code room} can see you, because the eye was tapped there. */
    public static boolean revealed(String room) {
        return room != null && REVEALED.contains(room);
    }

    /**
     * Whether Ghost mode keeps {@code room}'s enters back right now. Such a room has no stream
     * fields of its own until the eye is tapped, since TikTok fills them in from the enter.
     */
    public static boolean hiding(String room) {
        return room != null && enabled() && !revealed(room);
    }

    /** Gets the outcome of {@link #reveal}. API 24's Consumer is above the payload's floor. */
    public interface RevealDone {
        void accept(Reveal outcome);
    }

    /**
     * The eye tapped in {@code room}, shown by {@code page}: its enters go out from now on. When
     * an enter was held for it, the page enters the room again through TikTok's own enter, so the
     * chat, the host bar and the rest that a successful enter builds come in. When the page can't,
     * the held enter is sent as it was, off the main thread, and the room keeps showing just the
     * video. {@code done} gets the outcome on the main thread.
     */
    public static void reveal(Object page, String room, RevealDone done) {
        if (room == null) {
            Utils.runOnMainThread(() -> done.accept(Reveal.NEXT_TIME));
            return;
        }
        REVEALED.add(room);
        HeldEnter held;
        synchronized (HELD) {
            held = HELD.remove(room);
        }
        if (held == null) {
            Utils.runOnMainThread(() -> done.accept(Reveal.NEXT_TIME));
            return;
        }
        Utils.runOnMainThread(() -> {
            if (enterAgainOnMain(page, room)) {
                done.accept(Reveal.SENT);
                return;
            }
            boolean queued = Utils.runOnBackgroundThread(() -> {
                Reveal outcome = send(held);
                Utils.runOnMainThread(() -> done.accept(outcome));
            });
            if (!queued) done.accept(Reveal.FAILED);
        });
    }

    /** Sends a held enter through TikTok's own call, and lets its answer go. */
    static Reveal send(HeldEnter held) {
        try {
            Method call = twin(held.interceptor.getClass());
            if (call == null) return Reveal.FAILED;
            call.invoke(held.interceptor, held.chain);
            Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                    () -> "Ghost mode sent a held LIVE room enter after the eye was tapped");
            return Reveal.SENT;
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                    () -> "Ghost mode could not send a held LIVE room enter: " + cause.getClass().getSimpleName());
            return Reveal.FAILED;
        }
    }

    private static Method twin(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(TWIN) && method.getParameterTypes().length == 1) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * The room page entering {@code room} again on the main thread, through the bridge the patch
     * fills. Returns false when the page or the room id can't be used or the bridge refused, so
     * the held enter is sent instead.
     */
    static boolean enterAgainOnMain(Object page, String room) {
        if (page == null) return false;
        long id;
        try {
            id = Long.parseLong(room);
        } catch (NumberFormatException notAnId) {
            return false;
        }
        try {
            if (!reentry.enter(page, id)) return false;
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, ROOM_ENTERED_AGAIN, failure);
            return false;
        }
        HookStatus.bound(HOOK_FAMILY, ROOM_ENTERED_AGAIN);
        Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                () -> "Ghost mode entered a LIVE room again after the eye was tapped");
        return true;
    }

    /**
     * Filled in by the patch with the room page's own enter: the pending enter reset, a new enter
     * callback made from the page and the room id and kept where the page keeps it, and the page's
     * send. The callback's success is what builds the room's chat, host bar and close button.
     * Returns true once that enter is on its way. As written here it does nothing.
     */
    static boolean enterAgain(Object page, long room) {
        return false;
    }

    /** The eye tapped again: the room's next enter is held again. The host already saw this one. */
    public static void hideAgain(String room) {
        if (room != null) REVEALED.remove(room);
    }

    static int heldForTests() {
        synchronized (HELD) {
            return HELD.size();
        }
    }

    static void resetForTests() {
        reentry = LiveUnseen::enterAgain;
        REVEALED.clear();
        synchronized (HELD) {
            HELD.clear();
        }
        heldAt = NEVER;
        unknownHeldAt = NEVER;
        loggedHold = false;
        loggedKept = false;
    }
}
