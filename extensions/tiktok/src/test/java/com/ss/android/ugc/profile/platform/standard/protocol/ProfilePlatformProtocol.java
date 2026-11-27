package com.ss.android.ugc.profile.platform.standard.protocol;

import com.ss.android.ugc.profile.platform.base.data.BizBaseData;

/** The newer profile header's component protocol, which holds its one data object under an R8 name. */
public class ProfilePlatformProtocol {
    private BizBaseData LLJL;

    public void bind(BizBaseData data) { LLJL = data; }
}
