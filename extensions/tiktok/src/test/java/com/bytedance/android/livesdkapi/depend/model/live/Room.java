package com.bytedance.android.livesdkapi.depend.model.live;

/** Test fixture for TikTok's LIVE room, the type a room page holds, not part of the extension. */
public class Room {
    public Object owner;
    public String idStr;
    /** Seconds since the epoch, as TikTok's own room keeps them. */
    public long createTime;
    public long startTime;
}
