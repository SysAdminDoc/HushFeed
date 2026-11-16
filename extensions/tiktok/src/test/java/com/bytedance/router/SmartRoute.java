package com.bytedance.router;

/** A route the test router built; opening it records its address. */
public final class SmartRoute {
    private final String url;

    SmartRoute(String url) {
        this.url = url;
    }

    public void open() {
        SmartRouter.OPENED.add(url);
    }
}
