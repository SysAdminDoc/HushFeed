/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Keeps a message in the chat when the other person unsends it, marks it as unsent, and removes
 * the kept ones when asked.
 *
 * <p>TikTok's recall updater is handed the id of the message, the uid of whoever recalled it and
 * the message body. When that is somebody else and the switch is on, the patch has the updater
 * return as if it had applied the recall, so the message is left as it was, and this class
 * writes down what the updater was handed. A recall by the signed-in account is applied as
 * usual, so unsending your own message still works.
 *
 * <p>When the recall carries no id, or nobody is signed in, there is no telling whose recall it
 * is, so it is applied. Keeping a message you meant to unsend would be worse than missing one.
 *
 * <p>Clearing hands each written-down recall to the updater again, with the guard stood down, so
 * TikTok does what it would have done at the time: the message becomes the usual notice that it
 * was unsent.
 */
public final class KeepUnsent {
    /** Where a test or the settings page hears how a clear went. */
    public interface Report {
        void done(String message);
    }

    private static final String MESSAGE_BODY_TYPE = "com.bytedance.im.core.proto.MessageBody";

    /** True on the thread that is replaying a recall, so the guard lets it through. */
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<>();

    /** The newest recall updater TikTok made. It is a small object, so holding it until the app closes is fine. */
    private static volatile Object updater;
    /** The IM SDK's core object, which the updater works through. TikTok makes it as it starts. */
    private static volatile Object im;
    private static volatile Method getMsgId;

    private KeepUnsent() {}

    /** Called when TikTok makes its recall updater, so a later clear has one to hand recalls to. */
    public static void updaterCreated(Object created) {
        if (created != null) updater = created;
    }

    /** Called when TikTok makes its IM SDK core object, so a clear can make a recall updater after a restart. */
    public static void imCreated(Object created) {
        if (created != null) im = created;
    }

    /**
     * True when this recall should be skipped, leaving the message in the chat.
     *
     * @param recallUid the uid of the account that recalled the message, or null or empty when
     *                  the recall doesn't say
     */
    public static boolean shouldKeep(Object recallUpdater, String serverId, String recallUid, Object body, int type) {
        try {
            return decide(recallUpdater, serverId, recallUid, body, type);
        } catch (Throwable error) {
            // Anything unexpected leaves TikTok to apply the recall as it always did.
            return false;
        }
    }

    private static boolean decide(Object recallUpdater, String serverId, String recallUid, Object body, int type) {
        if (Boolean.TRUE.equals(REPLAYING.get())) return false;
        boolean on = Settings.KEEP_UNSENT_MESSAGES.get();
        String self = SignedInUser.id();
        if (!on) return false;
        if (serverId == null || serverId.isEmpty() || recallUid == null || recallUid.isEmpty()) return false;
        if (self == null || self.equals(recallUid)) return false;
        updaterCreated(recallUpdater);
        try {
            KeptUnsentStore.add(new KeptUnsentStore.Entry(serverId, recallUid, type, encode(body), System.currentTimeMillis(), describe(recallUpdater)));
        } catch (Throwable error) {
            // Not writing it down only costs the mark and the clear. The message is still kept.
        }
        return true;
    }

    /**
     * The message's content, with "unsent" after the text when it is one that was kept.
     *
     * <p>Called from the message's own content getter, which the chat draws from, so it has to be
     * quick for every message that was not kept.
     */
    public static String markContent(Object message, String content) {
        if (content == null || !Settings.MARK_KEPT_UNSENT_MESSAGES.get()) return content;
        Set<Long> kept = KeptUnsentStore.ids();
        if (kept.isEmpty()) return content;
        try {
            Method method = getMsgId;
            if (method == null || method.getDeclaringClass() != message.getClass()) {
                method = message.getClass().getMethod("getMsgId");
                getMsgId = method;
            }
            if (!kept.contains(((Number) method.invoke(message)).longValue())) return content;
            JSONObject json = new JSONObject(content);
            // Only text has a text to add to. A video or a sticker is left alone.
            if (!json.has("text")) return content;
            String text = json.optString("text", "");
            String tag = "  " + L10n.t("(unsent)");
            if (text.endsWith(tag)) return content;
            return json.put("text", text + tag).toString();
        } catch (Throwable ignored) {
            return content;
        }
    }

    /** The line under the switch: how many messages are being kept. */
    public static String statusLine() {
        return String.format(L10n.t("Kept so far: %1$d"), KeptUnsentStore.count());
    }

    /** True when a clear has something to remove. */
    public static boolean hasKept() {
        return KeptUnsentStore.count() > 0;
    }

    /**
     * Removes every kept message, by handing each recall back to TikTok's updater, and reports
     * how it went. The work is done off the main thread, and the report comes back on it.
     */
    public static void clearKept(Report report) {
        List<KeptUnsentStore.Entry> pending = KeptUnsentStore.all();
        if (pending.isEmpty()) {
            report.done(L10n.t("Nothing has been kept"));
            return;
        }
        if (updater == null && im == null) {
            report.done(L10n.t("TikTok isn't ready yet. Open your Inbox, then try again."));
            return;
        }
        Thread worker = new Thread(() -> {
            int removed = 0;
            int failed = 0;
            REPLAYING.set(Boolean.TRUE);
            try {
                for (KeptUnsentStore.Entry entry : pending) {
                    try {
                        Object target = updaterFor(entry);
                        Method apply = target == null ? null : applyMethod(target.getClass());
                        if (apply == null) throw new IllegalStateException("no updater");
                        apply.invoke(target, entry.serverId, entry.recallUid, decode(entry.body), entry.type);
                        KeptUnsentStore.remove(entry.serverId);
                        removed++;
                    } catch (Throwable error) {
                        failed++;
                    }
                }
            } finally {
                REPLAYING.remove();
            }
            final String message = failed == 0
                    ? String.format(L10n.t("Removed: %1$d"), removed)
                    : String.format(L10n.t("Removed: %1$d. The other %2$d are still there. Open your Inbox, then try again."), removed, failed);
            Utils.runOnMainThread(() -> report.done(message));
        }, "hushfeed-clear-unsent");
        worker.start();
    }

    /**
     * What the updater was made from, as JSON: its class and its plain values. The IM core object
     * it also holds is not saved, since it is made again every time TikTok starts.
     */
    private static String describe(Object made) {
        try {
            List<Field> fields = new ArrayList<>();
            for (Field field : made.getClass().getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) fields.add(field);
            }
            // Comparator.comparing is API 24.
            Collections.sort(fields, (a, b) -> a.getName().compareTo(b.getName()));
            JSONArray ints = new JSONArray();
            JSONObject json = new JSONObject().put("class", made.getClass().getName()).put("ints", ints);
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(made);
                if (field.getType() == int.class) ints.put(((Number) value).intValue());
                else if (field.getType() == long.class) json.put("long", ((Number) value).longValue());
                else if (field.getType() == String.class) json.put("string", value == null ? "" : value);
                else if (value instanceof Enum) json.put("enum", ((Enum<?>) value).name());
            }
            return json.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** An updater to hand the entry's recall to: a new one made like the original, else the newest TikTok made. */
    private static Object updaterFor(KeptUnsentStore.Entry entry) {
        Object core = im;
        if (core != null && !entry.updater.isEmpty()) {
            try {
                Object made = make(new JSONObject(entry.updater), core);
                if (made != null) return made;
            } catch (Throwable ignored) {
                // Falls through to the newest updater.
            }
        }
        return updater;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object make(JSONObject json, Object core) throws Exception {
        Class<?> type = Class.forName(json.getString("class"), false, core.getClass().getClassLoader());
        JSONArray ints = json.optJSONArray("ints");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            Object[] arguments = new Object[parameters.length];
            int nextInt = 0;
            boolean usable = true;
            for (int i = 0; i < parameters.length && usable; i++) {
                Class<?> parameter = parameters[i];
                if (parameter == int.class) {
                    arguments[i] = ints == null ? 0 : ints.optInt(nextInt++, 0);
                } else if (parameter == long.class) {
                    arguments[i] = json.optLong("long", 0L);
                } else if (parameter == String.class) {
                    arguments[i] = json.optString("string", "");
                } else if (parameter.isEnum()) {
                    Object[] constants = parameter.getEnumConstants();
                    String name = json.optString("enum", "");
                    arguments[i] = name.isEmpty() ? constants[0] : Enum.valueOf((Class<Enum>) parameter, name);
                } else if (parameter.isInstance(core)) {
                    arguments[i] = core;
                } else {
                    usable = false;
                }
            }
            if (!usable) continue;
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        }
        return null;
    }

    /** The updater's method that applies a recall, found by what it takes, since its name is obfuscated. */
    private static Method applyMethod(Class<?> type) {
        try {
            Class<?> body = Class.forName(MESSAGE_BODY_TYPE, false, type.getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 4 && parameters[0] == String.class && parameters[1] == String.class
                        && parameters[2] == body && parameters[3] == int.class) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (Throwable ignored) {
            // Falls through to no method, which the caller reports.
        }
        return null;
    }

    private static String encode(Object body) {
        if (body == null) return "";
        try {
            Object adapter = body.getClass().getField("ADAPTER").get(null);
            Method encode = adapterMethod(adapter, "encode", Object.class);
            encode.setAccessible(true);
            byte[] bytes = (byte[]) encode.invoke(adapter, body);
            return bytes == null ? "" : Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Object decode(String body) throws Exception {
        if (body == null || body.isEmpty()) return null;
        Class<?> type = Class.forName(MESSAGE_BODY_TYPE, false, im != null ? im.getClass().getClassLoader()
                : updater != null ? updater.getClass().getClassLoader() : KeepUnsent.class.getClassLoader());
        Object adapter = type.getField("ADAPTER").get(null);
        Method decode = adapterMethod(adapter, "decode", byte[].class);
        decode.setAccessible(true);
        return decode.invoke(adapter, (Object) Base64.decode(body, Base64.NO_WRAP));
    }

    private static Method adapterMethod(Object adapter, String name, Class<?> parameter) throws NoSuchMethodException {
        for (Class<?> type = adapter.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals(name) && parameters.length == 1 && parameters[0] == parameter) return method;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
