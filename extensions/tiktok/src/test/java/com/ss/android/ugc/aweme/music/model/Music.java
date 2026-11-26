package com.ss.android.ugc.aweme.music.model;

/** Stands in for TikTok's Music model, read through the same getters SoundIdentity tries. */
public class Music implements java.io.Serializable {
    private final String mid;
    private final String musicName;
    private final String authorName;

    public Music(String mid, String musicName, String authorName) {
        this.mid = mid;
        this.musicName = musicName;
        this.authorName = authorName;
    }

    public String getMid() {
        return mid;
    }

    public String getMusicName() {
        return musicName;
    }

    public String getAuthorName() {
        return authorName;
    }
}
