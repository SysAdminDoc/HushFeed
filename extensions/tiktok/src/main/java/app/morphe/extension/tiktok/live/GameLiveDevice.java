/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import androidx.annotation.Nullable;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The phone TikTok's LIVE server is told about while a LIVE is set up (issue #459).
 *
 * <p>A game LIVE's quality picker only lists what the server sends back for a game LIVE from
 * /webcast/room/create_info/ (RoomCreateInfo.pushStreamInfoMap), and the server holds some fast
 * phones below 1080p 60fps. That request carries nothing about the phone except TikTok's common
 * parameters, so while the switch is on, the setup requests go out with a Galaxy S25 Ultra as
 * their device_type and device_brand. Every other request keeps the real phone.
 *
 * <p>TTNet's common-parameter handler calls {@link #requestPath} with each request's path before
 * it builds the parameters and {@link #requestParams} once they're built, on the same thread.
 * Off, paused, or a failure in here, and the real phone goes out as TikTok has it.
 */
public final class GameLiveDevice {
    /** What a Galaxy S25 Ultra reports as {@code Build.MODEL} and {@code Build.BRAND}. */
    static final String MODEL = "SM-S938B";
    static final String BRAND = "samsung";
    static final String MODEL_FIELD = "device_type";
    static final String BRAND_FIELD = "device_brand";
    /**
     * The requests that set up a LIVE: what the Go LIVE page loads (create_info, which carries
     * the qualities), the room it creates, and the push settings it reads back.
     */
    static final String[] SETUP_PATHS = {
            "webcast/room/create_info",
            "webcast/room/create",
            "webcast/room/push_stream_info",
    };

    /** Set while this thread builds a setup request's parameters with the switch on. */
    private static final ThreadLocal<Boolean> SETUP = new ThreadLocal<>();

    private GameLiveDevice() {
    }

    /** From the common-parameter handler, with the path of the request it's about to fill. */
    public static void requestPath(@Nullable String path) {
        try {
            if (isSetup(path) && Settings.REPORT_NEWER_PHONE_TO_GAME_LIVE.get()) {
                SETUP.set(Boolean.TRUE);
                return;
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read a LIVE request's path", failure);
        }
        SETUP.remove();
    }

    /**
     * From the handler once the parameters are built. Only fields TikTok put in the map are
     * changed, so one it left out stays out.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void requestParams(@Nullable Map params) {
        if (SETUP.get() == null) return;
        SETUP.remove();
        try {
            if (params == null) return;
            Object model = params.get(MODEL_FIELD);
            if (model instanceof String) params.put(MODEL_FIELD, MODEL);
            if (params.get(BRAND_FIELD) instanceof String) params.put(BRAND_FIELD, BRAND);
            Logger.printDebug(() -> "Game LIVE setup: reporting " + MODEL + " instead of " + model);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set a LIVE setup request's phone", failure);
        }
    }

    /** Whether a request path is one of {@link #SETUP_PATHS}, with or without its slashes. */
    static boolean isSetup(@Nullable String path) {
        if (path == null) return false;
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') start++;
        while (end > start && path.charAt(end - 1) == '/') end--;
        int length = end - start;
        for (String setup : SETUP_PATHS) {
            if (setup.length() == length && path.regionMatches(start, setup, 0, length)) return true;
        }
        return false;
    }
}
