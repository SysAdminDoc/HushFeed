package com.bytedance.android.livesdk.model.message;

import com.bytedance.android.livesdkapi.message.BaseMessage;
import com.bytedance.android.livesdkapi.message.CommonMessageData;

import java.util.ArrayList;
import java.util.List;

/** A LIVE caption line, final like TikTok's, with the fields a name alert reads by their real names. */
public final class CaptionMessage extends BaseMessage {
    public List<CaptionContent> content = new ArrayList<>();
    public boolean definite;
    public long sentenceId;
    public long speakerUserId;
    public String speakerUserName;

    public CaptionMessage(long room, long sentence, String speaker, String... lines) {
        baseMessage = new CommonMessageData();
        baseMessage.roomId = room;
        baseMessage.messageId = sentence * 10 + lines.length;
        this.sentenceId = sentence;
        this.speakerUserName = speaker;
        for (String line : lines) content.add(new CaptionContent(line, "en"));
    }
}
