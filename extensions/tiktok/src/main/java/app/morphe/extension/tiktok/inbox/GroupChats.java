/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;

/**
 * The group chats this account has, by the name the Inbox shows, so a streak can message one.
 *
 * <p>A group's chat id can't be built from two uids the way a one to one chat's can, and the
 * database that holds the chats is encrypted with a key kept in the Android keystore, so it
 * can't be read from here. TikTok does hand each chat to {@code Conversation.setCoreInfo} when
 * it loads the chat into memory, and the patch calls {@link #seen} from there. The id and the
 * name of every group that goes by are kept, per account, in a small file of Hushfeed's own.
 * A group that was never loaded since this was installed turns up once the Inbox has been open.
 */
public final class GroupChats {
    public static final String PREFS_NAME = "hushfeed_group_chats";
    private static final char SEPARATOR = '\t';

    /** What was last written for each account and name, so an unchanged group costs no disk write. */
    private static final Map<String, String> WRITTEN = new HashMap<>();
    private static volatile Methods methods;

    private GroupChats() {}

    /** One group chat: its id and its name as TikTok spells it. */
    static final class Chat {
        final String id;
        final String name;

        Chat(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Methods {
        final Class<?> owner;
        final Method isGroup;
        final Method id;
        final Method core;
        Method name;
        Class<?> nameOwner;

        Methods(Class<?> owner) throws NoSuchMethodException {
            this.owner = owner;
            isGroup = open(owner.getMethod("isGroupChat"));
            id = open(owner.getMethod("getConversationId"));
            core = open(owner.getMethod("getCoreInfo"));
        }
    }

    private static Method open(Method method) {
        method.setAccessible(true);
        return method;
    }

    /**
     * The name as compared: trimmed, runs of whitespace as one space, the characters the streak
     * ledger uses for itself taken out, lowercase. Emoji pass through untouched.
     */
    static String normalize(String name) {
        if (name == null) return "";
        String text = name.replace('|', ' ').replace(',', ' ').trim().replaceAll("\\s+", " ");
        return text.toLowerCase(Locale.ROOT);
    }

    /** Called with each conversation as TikTok stores its core info. Never throws. */
    public static void seen(Object conversation) {
        if (conversation == null) return;
        try {
            Context context = Utils.getContext();
            String self = SignedInUser.id();
            if (context == null || self == null) return;
            Methods found = methods;
            if (found == null || found.owner != conversation.getClass()) {
                found = new Methods(conversation.getClass());
                methods = found;
            }
            if (!Boolean.TRUE.equals(found.isGroup.invoke(conversation))) return;
            Object id = found.id.invoke(conversation);
            Object core = found.core.invoke(conversation);
            if (!(id instanceof String) || ((String) id).isEmpty() || core == null) return;
            Method getName = nameMethod(found, core);
            Object name = getName.invoke(core);
            if (!(name instanceof String)) return;
            remember(context, self, (String) id, (String) name);
        } catch (Throwable ignored) {
            // Whatever TikTok handed over, a failed note must never reach the chat code.
        }
    }

    private static Method nameMethod(Methods found, Object core) throws NoSuchMethodException {
        Method getName = found.name;
        if (getName == null || found.nameOwner != core.getClass()) {
            getName = open(core.getClass().getMethod("getName"));
            found.name = getName;
            found.nameOwner = core.getClass();
        }
        return getName;
    }

    static void remember(Context context, String self, String id, String name) {
        String key = normalize(name);
        if (key.isEmpty() || id.isEmpty()) return;
        String entry = self + SEPARATOR + key;
        String value = id + SEPARATOR + name.trim();
        synchronized (WRITTEN) {
            if (value.equals(WRITTEN.get(entry))) return;
            WRITTEN.put(entry, value);
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (value.equals(preferences.getString(entry, null))) return;
        preferences.edit().putString(entry, value).apply();
    }

    /** The group this account has under that name, or null. */
    static Chat find(Context context, String self, String name) {
        String key = normalize(name);
        if (key.isEmpty()) return null;
        String value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(self + SEPARATOR + key, null);
        if (value == null) return null;
        int split = value.indexOf(SEPARATOR);
        if (split <= 0) return null;
        return new Chat(value.substring(0, split), value.substring(split + 1));
    }

    /** So a test starts without what an earlier one saw. */
    static void resetForTests() {
        synchronized (WRITTEN) {
            WRITTEN.clear();
        }
        methods = null;
    }
}
