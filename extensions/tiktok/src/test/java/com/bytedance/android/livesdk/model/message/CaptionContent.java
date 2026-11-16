package com.bytedance.android.livesdk.model.message;

/** One language's text of a LIVE caption line, final like TikTok's, by its real field names. */
public final class CaptionContent {
    public String content;
    public String lang;

    public CaptionContent(String content, String lang) {
        this.content = content;
        this.lang = lang;
    }
}
