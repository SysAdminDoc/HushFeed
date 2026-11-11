/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Pin more chats (#385): lifts the cap TikTok's app puts on pinned chats in the Inbox.
 *
 * <p>The cap is the server value {@code tt_im_inbox_pin_chat_limit}, 5 when the server sends
 * nothing. Before a pin goes out, the Inbox counts the chats already pinned and refuses the pin
 * with its "pin up to" toast once the count reaches the cap. TikTok's website has no such cap and
 * the app already shows the pins made there, so the check lives only in the app. While the setting
 * is on the cap reads as {@link #LIFTED_LIMIT}, a number far above what anyone pins but small
 * enough that TikTok's own arithmetic on it can't overflow. Every other answer is TikTok's, and so
 * is the answer while the setting is off, while Hushfeed is paused and before the application
 * context exists. TikTok reads the value once per launch, so the setting asks for a restart.
 */
@SuppressWarnings("unused")
public final class PinMoreChats {
    private static final String HOOK_FAMILY = "pin more chats";

    /** The cap while the setting is on. */
    static final int LIFTED_LIMIT = 100;

    private PinMoreChats() {
    }

    /**
     * How many chats may be pinned, from TikTok's {@code limit}. Called once per launch, the first
     * time a chat is pinned.
     */
    public static int pinLimit(int limit) {
        if (limit >= LIFTED_LIMIT) return limit;
        try {
            if (!liftLimit()) return limit;
            HookStatus.bound(HOOK_FAMILY, "lifted the pin limit from " + limit);
            return LIFTED_LIMIT;
        } catch (Exception ex) {
            Logger.printDebug(() -> "[PinMoreChats] left the pin limit at " + limit, ex);
            return limit;
        }
    }

    private static boolean liftLimit() {
        // TikTok can ask while it starts, before Settings can load without throwing.
        return Utils.getContext() != null && Settings.PIN_MORE_CHATS.get();
    }
}
