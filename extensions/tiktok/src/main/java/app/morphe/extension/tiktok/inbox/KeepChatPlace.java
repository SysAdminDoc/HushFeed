/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keep your place in chats (#503): a chat you scrolled up in stays where you were.
 *
 * <p>Every change to a chat's message list goes through one consumer in TikTok's message list
 * ability. It hands the event behind the change to a decider, which answers whether the list
 * should move to the newest message once the change is drawn. A yes queues TikTok's
 * {@code scrollToPositionWithOffset(0, 0)} for the next list commit. Two of those yeses move a
 * reader who never asked to go anywhere:
 *
 * <ul>
 *   <li>Coming back from a video opened in the chat. The chat page resumes, TikTok queries its
 *       messages again, and the decider answers yes to the query.</li>
 *   <li>Sending a reply to an older message from up in the chat. The decider answers yes to every
 *       new message you send, so the list jumps to your reply at the bottom and away from the
 *       message you were answering.</li>
 * </ul>
 *
 * <p>The patch hands each yes to {@link #scrollToNewest} with the list's layout manager and the
 * event. While the switch is on and the newest message is off screen, the list stays where it
 * is, except for the two moves a person asks for: a plain message they send, and a page TikTok
 * loads because they tapped the down arrow (TikTok only moves to an incoming page for that tap
 * when the list isn't already at the bottom). Everything else is TikTok's: every no, every yes
 * while the newest message is on screen, and every answer while the switch is off, while
 * Hushfeed is paused or when the event or the list can't be read.
 */
@SuppressWarnings("unused")
public final class KeepChatPlace {
    private static final String HOOK_FAMILY = "keep your place in chats";

    /** TikTok's name for the event a message you send starts. */
    static final String SEND_EVENT = "OnSendMessageEvent";
    /** TikTok's name for the event a page of messages from the server starts. */
    static final String GET_EVENT = "OnGetMessageEvent";

    private static final Object NONE = new Object();
    /** Per send event class, the field that holds the message being sent, or {@link #NONE}. */
    private static final Map<Class<?>, Object> SENT_MESSAGE = new ConcurrentHashMap<>();

    private KeepChatPlace() {
    }

    /**
     * Whether the chat moves to its newest message once this change is drawn. {@code tiktok} is
     * the decider's answer (non-zero for yes), {@code layoutManager} the chat list's layout
     * manager and {@code event} the change's event.
     */
    public static boolean scrollToNewest(int tiktok, @Nullable Object layoutManager, @Nullable Object event) {
        if (tiktok == 0) return false;
        try {
            if (!keepPlace()) return true;
            int firstVisible = firstVisible(layoutManager);
            if (firstVisible <= 0) {
                Logger.printDebug(() -> "[KeepChatPlace] " + eventName(event) + " at the newest message, moved");
                return true;
            }
            String name = eventName(event);
            if (name == null) return true;
            boolean reply = SEND_EVENT.equals(name) && isReply(event);
            boolean scroll = scrolls(firstVisible, name, reply);
            if (!scroll) HookStatus.bound(HOOK_FAMILY, "kept the place on " + name);
            Logger.printDebug(() -> "[KeepChatPlace] " + name + (reply ? " (reply)" : "") + " first visible "
                    + firstVisible + (scroll ? ", moved to newest" : ", kept the place"));
            return scroll;
        } catch (Exception ex) {
            HookStatus.threw(HOOK_FAMILY, "scrollToNewest", ex);
            Logger.printDebug(() -> "[KeepChatPlace] left TikTok's scroll alone", ex);
            return true;
        }
    }

    /**
     * The decision once TikTok has said yes and the switch is on. {@code firstVisible} is the
     * list's first visible position, 0 for the newest message since the chat lays out from the
     * bottom, and -1 when the list can't say.
     */
    static boolean scrolls(int firstVisible, String event, boolean reply) {
        if (firstVisible <= 0) return true;
        if (GET_EVENT.equals(event)) return true;
        if (SEND_EVENT.equals(event)) return !reply;
        return false;
    }

    private static boolean keepPlace() {
        // The list can change while TikTok starts, before Settings can load without throwing.
        return Utils.getContext() != null && Settings.KEEP_CHAT_PLACE.get();
    }

    @Nullable
    private static String eventName(@Nullable Object event) {
        Object name = Reflect.required(event, "getName", HOOK_FAMILY);
        return name instanceof String ? (String) name : null;
    }

    private static int firstVisible(@Nullable Object layoutManager) {
        Object position = Reflect.required(layoutManager, "findFirstVisibleItemPosition", HOOK_FAMILY);
        return position instanceof Integer ? (Integer) position : -1;
    }

    /** Whether the message a send event carries quotes another one, the way a reply does. */
    static boolean isReply(@Nullable Object event) {
        if (event == null) return false;
        Field field = sentMessage(event.getClass());
        if (field == null) return false;
        Object message;
        try {
            message = field.get(event);
        } catch (IllegalAccessException ex) {
            return false;
        }
        return Reflect.required(message, "getReferenceInfo", HOOK_FAMILY) != null;
    }

    /** The event's field whose type carries {@code getReferenceInfo()}, TikTok's IM message. */
    @Nullable
    private static Field sentMessage(Class<?> type) {
        Object cached = SENT_MESSAGE.get(type);
        if (cached == null) {
            cached = NONE;
            for (Class<?> current = type; current != null && current != Object.class && cached == NONE;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    Method quote = Reflect.method(field.getType(), "getReferenceInfo");
                    if (quote == null) continue;
                    try {
                        field.setAccessible(true);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    cached = field;
                    break;
                }
            }
            if (cached == NONE) HookStatus.missingMember(HOOK_FAMILY, "field", type.getName(), "message");
            SENT_MESSAGE.put(type, cached);
        }
        return cached == NONE ? null : (Field) cached;
    }
}
