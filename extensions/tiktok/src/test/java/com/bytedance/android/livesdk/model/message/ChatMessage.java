package com.bytedance.android.livesdk.model.message;

import com.bytedance.android.live.base.model.user.User;
import com.bytedance.android.livesdkapi.message.BaseMessage;
import com.bytedance.android.livesdkapi.message.CommonMessageData;

import java.util.ArrayList;
import java.util.List;

/** A LIVE chat line, with the fields a name alert reads by their real names and types. */
public class ChatMessage extends BaseMessage {
    public String content;
    public List<Long> mentionUserList = new ArrayList<>();
    public List<User> mentionUsers = new ArrayList<>();
    public User atUser;
    public User userInfo;

    public ChatMessage(long room, long id, User sender, String content) {
        baseMessage = new CommonMessageData();
        baseMessage.roomId = room;
        baseMessage.messageId = id;
        this.userInfo = sender;
        this.content = content;
    }
}
