package com.ss.android.ugc.profile.platform.business.header.business.info.business.relationinfo.data;

import com.ss.android.ugc.profile.platform.base.data.BizBaseData;

/** One profile count's data, with the members 48.0.2 keeps. */
public final class RelationData extends BizBaseData {
    private final Integer followingVisibility;
    private final String number;

    public RelationData(Integer followingVisibility, String number) {
        this.followingVisibility = followingVisibility;
        this.number = number;
    }

    public Integer getFollowingVisibility() { return followingVisibility; }
    public String getNumber() { return number; }
}
