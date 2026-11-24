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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
 * <p>While hidden, the room still shows its chat, host bar and close button (#513). Once its page
 * kept the room open, the page's own startInteraction builds them ({@link #showRoom}), and the
 * room's messages come over TikTok's LIVE preview channel, the one the feed's LIVE previews read
 * without entering a room ({@link #messageMode}, {@link #reuseFor}). The room's own channel never
 * enters a hidden room ({@link #imEnter}), and what a viewer sends from it (likes, comments,
 * gifts, shares, votes, the room channel's fetch) fails on the phone in the same hook as the
 * enter. The eye in the room ({@code LiveUnseenButton}) lets the room you're in see you: the room
 * page enters again through TikTok's own enter, and the room's later enters and requests go out
 * as usual for the rest of that visit. Leaving the room, or tapping the eye again, hides you from
 * it again (#491).
 *
 * <p>The hooks fail open. With a switch off, Hushfeed paused, the settings not loaded yet, or
 * the request unreadable, the request goes out as it always does, and a room whose page can't
 * start shows just the video and the eye.
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
    /** The names TikTok's message channel kinds keep: a room's own channel and the LIVE preview's. */
    static final String CHANNEL_ROOM = "LIVE_ROOM";
    static final String CHANNEL_PREVIEW = "PREVIEW";
    /** The hidden room's chat read over the preview channel, by its name in the diagnostics. */
    static final String CHAT_PREVIEW = "live chat over the preview channel";
    /** The room channel's websocket enter held for a hidden room, by its name in the diagnostics. */
    static final String IM_ENTER_HELD = "live chat enter held";
    /** The bridge into the room page's startInteraction, by its name in the diagnostics. */
    static final String ROOM_SHOWN = "live room shown";
    /**
     * What a room sends that shows its host you're there, by path: the room channel's own fetch
     * (the preview channel's isn't one), a like, a comment, the quick comment bar's report, an
     * emote, a share, a gift, a poll vote and a room event report.
     */
    static final Set<String> ROOM_REQUESTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/webcast/im/fetch/", "/webcast/room/like/", "/webcast/room/chat/",
            "/webcast/room/chat/event/", "/webcast/room/emote_chat/", "/webcast/room/share/",
            "/webcast/gift/send/", "/webcast/room/poll/vote/", "/webcast/room/event_report/")));

    /**
     * Rooms the eye was tapped in, whose enters go out: until the eye is tapped again, or until
     * the room is left after one of its enters went out (#491).
     */
    private static final Set<String> REVEALED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Revealed rooms an enter went out for since the eye, so leaving one ends its visit. */
    private static final Set<String> ENTERED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** The latest enter held for each room, guarded by itself. */
    private static final Map<String, HeldEnter> HELD = new LinkedHashMap<>();
    /** Hidden rooms whose page started its chat and host bar, until the eye or a leave. */
    private static final Set<String> SHOWN =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** When an enter was last held, and when one was last held without a readable room. */
    private static volatile long heldAt = NEVER;
    private static volatile long unknownHeldAt = NEVER;
    private static volatile boolean loggedHold;
    private static volatile boolean loggedKept;
    private static volatile boolean loggedRequest;
    private static volatile boolean loggedChat;
    private static volatile boolean loggedShown;

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

    /** The room page starting its chat and host bar; the patch's bridge, swapped out in tests. */
    interface Interaction {
        boolean start(Object page, Object room);
    }

    static volatile Interaction interaction = LiveUnseen::startInteraction;

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
     * chain. Throws for a LIVE room enter while it's held, and for what a hidden room sends that
     * shows its host you're there (#513), which TikTok handles as a connection that failed, and
     * returns for everything else.
     */
    public static void holdEnter(Object interceptor, Object chain) throws IOException {
        if (!enabled()) return;
        String room;
        boolean roomRequest;
        try {
            Object request = Reflect.requiredField(interceptor, "mOriginalRequest", HOOK_FAMILY);
            Object path = Reflect.required(request, "getPath", HOOK_FAMILY);
            String text = path instanceof String ? (String) path : null;
            roomRequest = isRoomRequest(text);
            if (!roomRequest && !isEnter(text)) return;
            room = FollowDiagnostics.liveRoomIdOf(request);
            if (roomRequest && !holdsRoomRequest(room)) return;
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, GhostMode.LIVE_ENTER, unreadable);
            return;
        }
        if (roomRequest) {
            // Failed, never kept: TikTok's own retry or the viewer's next tap sends it again.
            if (!loggedRequest) {
                loggedRequest = true;
                Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                        () -> "Ghost mode held back a request from a LIVE watched unseen");
            }
            throw new IOException(HELD_MESSAGE);
        }
        try {
            boolean revealed = room != null && REVEALED.contains(room);
            if (!GhostMode.holdLiveEnter(revealed)) {
                if (revealed) ENTERED.add(room);
                return;
            }
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
     * reason is a failed enter that Ghost mode held for this room, and the page then starts the
     * room's chat and host bar once its leave is over ({@link #showRoom}); any other leave goes
     * ahead and ends the visit ({@link #endVisit}).
     */
    public static boolean keepRoom(Object page, Object reason) {
        boolean stays = stays(page, reason);
        if (stays) Utils.runOnMainThread(() -> showRoom(page));
        else if (reason instanceof Enum) endVisit(page);
        return stays;
    }

    /**
     * A hidden room kept open by its page: the page's own startInteraction builds the chat, the
     * host bar and the close button, through the bridge the patch fills (#513). The room's
     * messages come over TikTok's preview channel ({@link #messageMode}). A room revealed in the
     * meantime is left to its own enter, and a page that can't start keeps the room as it was.
     */
    static void showRoom(Object page) {
        String room = null;
        try {
            Object shown = LiveRecordChip.roomOf(page);
            String key = LiveRecordChip.roomKey(shown);
            if (shown == null || !hiding(key)) return;
            // Counted from before the start, since the room's widgets send as they're built.
            room = key;
            SHOWN.add(room);
            if (!interaction.start(page, shown)) {
                SHOWN.remove(room);
                return;
            }
        } catch (Throwable failure) {
            if (room != null) SHOWN.remove(room);
            HookStatus.threw(HOOK_FAMILY, ROOM_SHOWN, failure);
            return;
        }
        HookStatus.bound(HOOK_FAMILY, ROOM_SHOWN);
        if (!loggedShown) {
            loggedShown = true;
            Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                    () -> "Ghost mode showed the chat of a LIVE watched unseen");
        }
    }

    /**
     * Filled in by the patch with the room page's own start: a page still PREPARING, where a held
     * enter leaves it, moves on to PREPARED, as a successful enter moves it, and its
     * startInteraction builds the chat, host bar and close button. Returns true once it ran, false
     * for a page in any other state. As written here it does nothing.
     */
    static boolean startInteraction(Object page, Object room) {
        return false;
    }

    /**
     * Called by TikTok's LIVE message channel builder with the room id, whether you host the room
     * (non-zero) and the kind of channel asked for. A hidden room's own channel comes back as the
     * LIVE preview kind, which reads the room's chat without entering it, as the feed's LIVE
     * previews do. Anything else, or a kind with no preview, comes back as asked.
     */
    public static Object messageMode(long room, int host, Object mode) {
        try {
            if (host != 0 || room <= 0 || !(mode instanceof Enum)) return mode;
            Enum<?> kind = (Enum<?>) mode;
            if (!CHANNEL_ROOM.equals(kind.name()) || !hiding(String.valueOf(room))) return mode;
            Object preview = previewOf(kind);
            if (preview == null) return mode;
            HookStatus.bound(HOOK_FAMILY, CHAT_PREVIEW);
            if (!loggedChat) {
                loggedChat = true;
                Logger.diagnosticInfo(DiagnosticCategory.OTHER, "GhostMode",
                        () -> "Ghost mode reads a LIVE watched unseen over the preview channel");
            }
            return preview;
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, CHAT_PREVIEW, unreadable);
            return mode;
        }
    }

    /** The PREVIEW constant of a channel kind's enum, or null when it has none. */
    private static Object previewOf(Enum<?> kind) {
        for (Object constant : kind.getDeclaringClass().getEnumConstants()) {
            if (CHANNEL_PREVIEW.equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }

    /**
     * Called by the LIVE room page's start with the channel TikTok kept from the last room and
     * the page. A hidden room gets null, so it makes its own channel ({@link #messageMode}), and
     * the last room's channel isn't moved into it. Anything unreadable keeps the channel; the
     * enter frame and request holds still stand in front of it.
     */
    public static Object reuseFor(Object channel, Object page) {
        if (channel == null) return null;
        try {
            if (!hiding(LiveRecordChip.roomKey(LiveRecordChip.roomOf(page)))) return channel;
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, CHAT_PREVIEW, unreadable);
            return channel;
        }
        HookStatus.bound(HOOK_FAMILY, CHAT_PREVIEW);
        return null;
    }

    /**
     * Called by the room channel's websocket enter with its ImEnterRoom frame and the frame's
     * bytes, right before it sends them. Returns null, which TikTok takes as nothing to send, for
     * a hidden room. A frame with no readable room is held while no room is revealed.
     */
    public static byte[] imEnter(Object frame, byte[] bytes) {
        if (bytes == null || !enabled()) return bytes;
        try {
            Object id = Reflect.readField(frame, "roomId");
            boolean hold = id instanceof Number
                    ? !revealed(String.valueOf(((Number) id).longValue()))
                    : REVEALED.isEmpty();
            if (!hold) return bytes;
            HookStatus.bound(HOOK_FAMILY, IM_ENTER_HELD);
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, IM_ENTER_HELD, unreadable);
        }
        return null;
    }

    private static boolean stays(Object page, Object reason) {
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
     * The room on {@code page} left, for any reason but an enter Ghost mode held. When the eye was
     * tapped there and its enter went out, the visit is over and the room is hidden from again
     * the next time it opens (#491). A room the eye was tapped in that hasn't been entered yet
     * keeps its reveal for that next time. An unreadable page changes nothing.
     */
    static void endVisit(Object page) {
        String room;
        try {
            room = LiveRecordChip.roomKey(LiveRecordChip.roomOf(page));
        } catch (Throwable unreadable) {
            return;
        }
        if (room == null) return;
        SHOWN.remove(room);
        if (ENTERED.remove(room)) REVEALED.remove(room);
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

    /** One of {@link #ROOM_REQUESTS}, by the request's whole path, with or without its last slash. */
    static boolean isRoomRequest(String path) {
        if (path == null) return false;
        String normalized = path.toLowerCase(Locale.ROOT);
        return ROOM_REQUESTS.contains(normalized.endsWith("/") ? normalized : normalized + "/");
    }

    /**
     * Whether a room request for {@code room} is held: the room is hidden. One with no readable
     * room is held while a hidden room shows its chat, since it may well come from that room.
     */
    static boolean holdsRoomRequest(String room) {
        return room != null ? !revealed(room) : !SHOWN.isEmpty();
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
        SHOWN.remove(room);
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
                ENTERED.add(room);
                done.accept(Reveal.SENT);
                return;
            }
            boolean queued = Utils.runOnBackgroundThread(() -> {
                Reveal outcome = send(held);
                if (outcome == Reveal.SENT) ENTERED.add(room);
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

    /** The interceptor's own send, by its name, or null. */
    static Method twin(Class<?> type) {
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
        if (room == null) return;
        REVEALED.remove(room);
        ENTERED.remove(room);
    }

    static int heldForTests() {
        synchronized (HELD) {
            return HELD.size();
        }
    }

    static void resetForTests() {
        reentry = LiveUnseen::enterAgain;
        interaction = LiveUnseen::startInteraction;
        REVEALED.clear();
        ENTERED.clear();
        SHOWN.clear();
        synchronized (HELD) {
            HELD.clear();
        }
        heldAt = NEVER;
        unknownHeldAt = NEVER;
        loggedHold = false;
        loggedKept = false;
        loggedRequest = false;
        loggedChat = false;
        loggedShown = false;
    }
}
