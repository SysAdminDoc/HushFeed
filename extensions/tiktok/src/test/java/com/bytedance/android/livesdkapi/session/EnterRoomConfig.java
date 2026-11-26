package com.bytedance.android.livesdkapi.session;

import com.bytedance.android.livesdkapi.depend.model.live.Room;

/**
 * Test fixture for TikTok's LIVE enter config, the one a room page is opened with, not part of
 * the extension. TikTok keeps these member names on 48.0.2.
 */
public class EnterRoomConfig {
    public StreamData mStreamData = new StreamData();
    public RoomsData mRoomsData = new RoomsData();

    public static class StreamData {
        public String pullStreamData;
        public long streamRoomId;
    }

    public static class RoomsData {
        public long roomId;
        public Room enterRoomCache;
    }
}
