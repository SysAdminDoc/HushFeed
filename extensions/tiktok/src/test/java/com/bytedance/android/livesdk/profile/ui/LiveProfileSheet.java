package com.bytedance.android.livesdk.profile.ui;

import android.view.View;

/** Test stand-in for LIVE's profile card sheet; only its name, getDialog and getView are read. */
public class LiveProfileSheet {
    public View view;

    public Object getDialog() {
        return null;
    }

    public View getView() {
        return view;
    }
}
