package com.bytedance.android.live.base.model.user;

/** The LIVE user model's fields a name alert reads, by their real names and types. */
public class User {
    public long id;
    public String idStr;
    public String nickName;
    public String displayId;

    public User(long id, String nickName) {
        this.id = id;
        this.idStr = String.valueOf(id);
        this.nickName = nickName;
    }
}
