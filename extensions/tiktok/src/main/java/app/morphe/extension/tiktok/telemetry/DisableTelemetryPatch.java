/*
 * SPDX-FileCopyrightText: 2026 hxreborn
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.extension.tiktok.telemetry;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import com.bytedance.applog.priority.PriorityHttpResponse;

public final class DisableTelemetryPatch {
    private DisableTelemetryPatch() {}

    /** The reply the AppLog priority uploader's callers take as delivered: TikTok's own success data. */
    static final String DELIVERED_PRIORITY_DATA = "{\"message\":\"success\",\"magic_tag\":\"ss_app_log\"}";

    /**
     * What the AppLog priority uploader answers with the switch on, in place of the request: a
     * 200 whose data says success and carries the SDK's magic tag, which is what its callers
     * check before dropping the events they sent, so nothing is kept to send again.
     */
    public static Object deliveredPriorityResponse() {
        return new PriorityHttpResponse(200, "", DELIVERED_PRIORITY_DATA);
    }

    /**
     * What TikTok looks up to learn which DNS resolver the phone uses (#379). Asked with
     * whoami.akamai.net at each lookup. The phone answers localhost itself, so with the switch
     * on no query leaves it, and TikTok just gets a local address back.
     */
    public static String resolverLookupHost(String host) {
        return isTelemetryDisabled() ? "localhost" : host;
    }

    /** Where Orbu's telemetry uploads go with the switch on: the phone itself, which refuses them. */
    static final String LOCAL_BASE_URL = "https://localhost/";

    /**
     * A base URL Orbu builds its requests on (#379). Its log, sampling and challenge uploads all
     * post to proxy.telemetry.us-ashburn-1.oci.oraclecloud.com, and a config can name another
     * region. With the switch on, any host with a telemetry label points at the phone, so the
     * name is never looked up and nothing leaves it. Orbu's configuration host is left alone.
     */
    public static String orbuBaseUrl(String url) {
        if (url == null || !isTelemetryDisabled() || !hasTelemetryLabel(url)) return url;
        return LOCAL_BASE_URL;
    }

    private static boolean hasTelemetryLabel(String url) {
        int scheme = url.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int end = start;
        while (end < url.length() && "/:?#".indexOf(url.charAt(end)) < 0) end++;
        for (String label : url.substring(start, end).split("\\.")) {
            if (label.equalsIgnoreCase("telemetry")) return true;
        }
        return false;
    }

    public static boolean isTelemetryDisabled() {
        // Keep analytics enabled if the context isn't initialized yet
        return Utils.getContext() != null && Settings.DISABLE_ANALYTICS.get();
    }
}
