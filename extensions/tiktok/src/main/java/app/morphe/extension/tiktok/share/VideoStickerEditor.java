/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;

import app.morphe.extension.shared.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens TikTok's own animated sticker editor for a video (#214), the trim, text and save flow behind
 * TikTok's Create sticker, which TikTok only offers on some videos. The editor lives in the app's
 * external ability service: {@code ServiceManager.get().getService(IExternalService.class)
 * .abilityService().video2StickerService().video2StickerEdit(activity, aweme, FEED, callback, null,
 * null)}, the same call TikTok's share sheet makes once the video passes its own check.
 *
 * <p>TikTok reports {@code onFailed} only when the editor can't open (its sticker module or video
 * engine isn't ready, or the video wouldn't download). Leaving the editor reports nothing, so a
 * failure is always a reason to fall back.
 *
 * <p>That call only saves a sticker when the video's creator allows stickers: for any other video
 * the editor opens, uploads, and then quietly refuses the save. {@link #openFile} is the route for
 * those, TikTok's own sticker-from-a-saved-video flow, fed a downloaded copy.
 */
final class VideoStickerEditor {
    static final String SERVICE_MANAGER = "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    static final String EXTERNAL_SERVICE = "com.ss.android.ugc.aweme.services.IExternalService";

    private VideoStickerEditor() {}

    /** Where TikTok's classes are found by name, so a test can hand in stand-ins. */
    interface Classes {
        Class<?> named(String name) throws ClassNotFoundException;
    }

    static volatile Classes classes = Class::forName;

    /**
     * Hands the video to TikTok's editor. False means it never got there and the caller makes the
     * cover sticker now. After a true, {@code fallback} still runs once if TikTok reports the editor
     * couldn't open.
     */
    static boolean open(Activity activity, Object aweme, Runnable fallback) {
        AtomicBoolean settled = new AtomicBoolean();
        try {
            Service service = service();
            if (service == null) return false;
            Object editor = service.editor;
            Method edit = editMethod(service.type);
            if (edit == null) return false;
            Class<?>[] parameters = edit.getParameterTypes();
            Object entry = feed(parameters[2]);
            if (entry == null) return false;
            Class<?> callbackType = parameters[3];
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onFailed":
                                if (settled.compareAndSet(false, true)) {
                                    Logger.printInfo(() -> "TikTok's sticker editor didn't open: "
                                            + (args != null && args.length > 2 ? args[1] + " (" + args[2] + ")" : ""));
                                    fallback.run();
                                }
                                return null;
                            case "equals":
                                return args != null && args.length == 1 && proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "Hushfeed video sticker";
                            default:
                                return null;
                        }
                    });
            edit.invoke(editor, activity, aweme, entry, callback, null, null);
            return true;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not open TikTok's sticker editor", error);
            // A failure TikTok already reported has made the cover sticker; otherwise the caller does.
            return !settled.compareAndSet(false, true);
        }
    }

    /**
     * Whether TikTok's editor can take a video file, the way its Favorites page makes a sticker from
     * a saved video. Asked before a download so a phone without the editor makes the cover sticker
     * straight away.
     */
    static boolean takesFiles() {
        try {
            Service service = service();
            return service != null && localMethod(service.type) != null;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not find TikTok's sticker editor", error);
            return false;
        }
    }

    /**
     * Hands a video file to TikTok's editor with no source video attached, so a video whose creator
     * turned stickers off still becomes one (#214). TikTok returns without a word when it can't read
     * the file, so true only means the call reached it.
     */
    static boolean openFile(Activity activity, String path) {
        try {
            Service service = service();
            if (service == null) return false;
            Method open = localMethod(service.type);
            if (open == null) return false;
            open.invoke(service.editor, activity, path, 0L, "favourite_tab");
            return true;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not hand the video to TikTok's sticker editor", error);
            return false;
        }
    }

    private static final class Service {
        final Object editor;
        final Class<?> type;

        Service(Object editor, Class<?> type) {
            this.editor = editor;
            this.type = type;
        }
    }

    /** TikTok's sticker service and the interface its methods are read from, or null when it's missing. */
    private static Service service() throws ReflectiveOperationException {
        Class<?> managerType = classes.named(SERVICE_MANAGER);
        Class<?> externalType = classes.named(EXTERNAL_SERVICE);
        Object manager = managerType.getMethod("get").invoke(null);
        Object external = managerType.getMethod("getService", Class.class).invoke(manager, externalType);
        if (external == null) return null;
        Method abilityGetter = externalType.getMethod("abilityService");
        Object ability = abilityGetter.invoke(external);
        if (ability == null) return null;
        Method editorGetter = abilityGetter.getReturnType().getMethod("video2StickerService");
        Object editor = editorGetter.invoke(ability);
        return editor == null ? null : new Service(editor, editorGetter.getReturnType());
    }

    private static Method localMethod(Class<?> editorType) {
        try {
            return editorType.getMethod("openVideoTrimForSticker", Activity.class, String.class, long.class, String.class);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Method editMethod(Class<?> editorType) {
        for (Method method : editorType.getMethods()) {
            if (method.getName().equals("video2StickerEdit") && method.getParameterTypes().length == 6
                    && Activity.class.isAssignableFrom(method.getParameterTypes()[0])
                    && method.getParameterTypes()[2].isEnum() && method.getParameterTypes()[3].isInterface()) {
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object feed(Class<?> entryType) {
        for (Object constant : entryType.getEnumConstants()) {
            if ("FEED".equals(((Enum) constant).name())) return constant;
        }
        return null;
    }
}
