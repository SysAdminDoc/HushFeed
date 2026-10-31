/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import java.lang.reflect.Field;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Stops TikTok telling people you have read their messages.
 *
 * <p>When a chat is opened, TikTok's IM SDK marks it read on the phone, which clears your unread
 * badge, and then sends a "mark conversation read" request. The server passes that on to the
 * other person's app, which is what shows them "Seen". With the switch on, the patch asks this
 * class before a request is sent, and a request that marks a chat as read is answered as if it
 * had gone out. The phone has already updated itself, so nothing looks different to you.
 *
 * <p>Anything unexpected, such as a request body shaped differently from the one this was written
 * for, is sent as TikTok always did. Missing a receipt to hide is better than a message that
 * never goes out.
 */
public final class HideReadReceipts {
    private static volatile Class<?> knownType;
    private static volatile Field markRead;
    private static volatile Field batchMarkRead;

    private HideReadReceipts() {}

    /**
     * True when this request should not be sent because it only tells someone you read their messages.
     *
     * @param requestBody the request about to be sent, whatever it is
     */
    public static boolean shouldSkip(Object requestBody) {
        try {
            if (requestBody == null || !Settings.HIDE_READ_RECEIPTS.get()) return false;
            Class<?> type = requestBody.getClass();
            if (type != knownType) {
                markRead = type.getField("mark_conversation_read_body");
                batchMarkRead = type.getField("batch_mark_read_body");
                knownType = type;
            }
            return markRead.get(requestBody) != null || batchMarkRead.get(requestBody) != null;
        } catch (Throwable error) {
            return false;
        }
    }
}
