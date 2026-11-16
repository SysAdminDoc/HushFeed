package com.bytedance.android.livesdkapi.message;

/** The base of every LIVE message model, holding its common block under its real name. */
public class BaseMessage {
    public CommonMessageData baseMessage;

    /** What TikTok's obfuscated message base answers, the common block's id. */
    public long getMessageId() {
        return baseMessage == null ? 0 : baseMessage.messageId;
    }
}
