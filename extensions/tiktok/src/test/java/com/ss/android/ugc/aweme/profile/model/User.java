package com.ss.android.ugc.aweme.profile.model;

/** The fields of TikTok's account model the follow status label and account facts read, by their real names and types. */
public class User {
    public String uid;
    public String uniqueId;
    public String nickname;
    public int followStatus;
    public int followerStatus;
    public Long _createTime;
    public long registerTime;
    public String region;
    public String accountRegion;
    public String isoCountryCode;
    public String country;
    public String language;
    public long uniqueIdModifyTime;
    public int nickNameModifyTs;
    public boolean isSecret;
    public boolean hasOpenFavorite;
    public int awemeCount;
    public int favoritingCount;
    public int collectCount;
    public int repostCount;
    public String secUid;
    /** pay_grade in the profile response. */
    public UserHonor userHonor;
    /** A UrlModel in TikTok; here anything with a urlList, which is all the avatar saver reads. */
    public Object avatar300;
    /** The other picture sizes, UrlModels in TikTok, as account facts reads them. */
    public Object avatarThumb;
    public Object avatarMedium;
    public Object avatarLarger;
    /** cover_url: a list of UrlModels. */
    public java.util.List<Object> coverUrls;

    public String getUid() { return uid; }
    public String getUniqueId() { return uniqueId; }
    public String getNickname() { return nickname; }
    public int getFollowStatus() { return followStatus; }
    public int getFollowerStatus() { return followerStatus; }
}
