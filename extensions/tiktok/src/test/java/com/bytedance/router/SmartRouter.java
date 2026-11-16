package com.bytedance.router;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Stands in for TikTok's router: records each route it was asked to open. */
public final class SmartRouter {
    public static final List<String> OPENED = new ArrayList<>();

    private SmartRouter() {
    }

    public static SmartRoute buildRoute(Context context, String url) {
        return new SmartRoute(url);
    }
}
