/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Method;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fix Spotify login: Spotify's own sign-in page in place of the Spotify app.
 *
 * <p>Add to Spotify signs in through the Spotify app and nothing else. TikTok's copy of the Spotify
 * auth SDK lives in the music feature dex ({@code libdex_df_music_dsp.so}, which the patcher can't
 * reach). It sends {@link #START_AUTH_FLOW} to the Spotify app for result {@link #REQUEST_CODE},
 * and the app checks the caller's signing key against the one Spotify has on file for TikTok's
 * client id. A patched TikTok carries another key, so the app turns it down, and the SDK's only
 * other route is the Play Store page.
 *
 * <p>The hook is TikTok's startActivityForResult wrapper in the main dex, which the hand-off goes
 * through. For that one request it opens Spotify's authorize page with the request's client id,
 * redirect, scopes and state in a WebView, then gives the SDK's LoginActivity what the Spotify app
 * would have: result OK with a {@code REPLY} bundle holding the code, or an {@code ERROR}. The
 * redirect is an https page on www.tiktok.com, which the WebView stops at, and TikTok trades the
 * code on its server as it does after the app. Without the Spotify app installed the SDK never
 * gets this far and opens the Play Store, as it does unpatched.
 */
@SuppressWarnings("unused")
public final class SpotifyWebLogin {
    static final String START_AUTH_FLOW = "com.spotify.sso.action.START_AUTH_FLOW";
    static final int REQUEST_CODE = 1138;
    static final String AUTHORIZE_PAGE = "https://accounts.spotify.com/authorize";
    /** The SDK's resultCode for an error reply, beside Activity's OK (-1) and canceled (0). */
    static final int RESULT_ERROR = -2;

    private SpotifyWebLogin() {
    }

    /**
     * Called first in TikTok's startActivityForResult wrapper. True when this was Spotify's
     * sign-in hand-off and the web page took it, so the wrapper returns without starting it.
     */
    public static boolean openInstead(int requestCode, Activity activity, Intent intent) {
        if (requestCode != REQUEST_CODE || activity == null || intent == null
                || !START_AUTH_FLOW.equals(intent.getAction())) {
            return false;
        }
        try {
            Uri page = authorizePage(intent);
            if (page == null) {
                Logger.printInfo(() -> "Spotify sign-in request not understood, handing it to the app");
                return false;
            }
            show(activity, page, Uri.parse(intent.getStringExtra("REDIRECT_URI")));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Spotify web sign-in failed to open", ex);
            return false;
        }
    }

    /**
     * Spotify's authorize page for the request the SDK built for its app, or null when the
     * request lacks what the page needs or asks for anything but a code.
     */
    static Uri authorizePage(Intent request) {
        String client = request.getStringExtra("CLIENT_ID");
        String redirect = request.getStringExtra("REDIRECT_URI");
        String type = request.getStringExtra("RESPONSE_TYPE");
        if (TextUtils.isEmpty(client) || TextUtils.isEmpty(redirect) || !"code".equals(type)) return null;
        Uri.Builder page = Uri.parse(AUTHORIZE_PAGE).buildUpon()
                .appendQueryParameter("client_id", client)
                .appendQueryParameter("response_type", type)
                .appendQueryParameter("redirect_uri", redirect);
        String[] scopes = request.getStringArrayExtra("SCOPES");
        if (scopes != null && scopes.length > 0) page.appendQueryParameter("scope", TextUtils.join(" ", scopes));
        String state = request.getStringExtra("STATE");
        if (state != null) page.appendQueryParameter("state", state);
        return page.build();
    }

    /** Whether {@code url} is the request's redirect: same scheme, host and path, any query. */
    static boolean isRedirect(Uri url, Uri redirect) {
        return url != null && redirect != null
                && TextUtils.equals(url.getScheme(), redirect.getScheme())
                && url.getHost() != null && url.getHost().equalsIgnoreCase(redirect.getHost())
                && TextUtils.equals(trimSlash(url.getPath()), trimSlash(redirect.getPath()));
    }

    private static String trimSlash(String path) {
        return path != null && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** What the Spotify app would have answered with, given where the page redirected. */
    static Reply replyFor(Uri landed) {
        String code = landed.getQueryParameter("code");
        if (!TextUtils.isEmpty(code)) {
            Bundle reply = new Bundle();
            reply.putString("RESPONSE_TYPE", "code");
            reply.putString("AUTHORIZATION_CODE", code);
            reply.putString("STATE", landed.getQueryParameter("state"));
            return new Reply(Activity.RESULT_OK, new Intent().putExtra("REPLY", reply));
        }
        String error = landed.getQueryParameter("error");
        return new Reply(RESULT_ERROR, new Intent().putExtra("ERROR", TextUtils.isEmpty(error) ? "Unknown error" : error));
    }

    static final class Reply {
        final int resultCode;
        final Intent data;

        Reply(int resultCode, Intent data) {
            this.resultCode = resultCode;
            this.data = data;
        }
    }

    private static void show(Activity activity, Uri page, Uri redirect) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        WebView web = new WebView(activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        boolean[] answered = {false};
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return land(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // A redirect the client wasn't asked about still starts a page here.
                if (url != null && land(Uri.parse(url))) view.stopLoading();
            }

            private boolean land(Uri url) {
                if (!isRedirect(url, redirect)) return false;
                if (!answered[0]) {
                    answered[0] = true;
                    Reply reply = replyFor(url);
                    Logger.printInfo(() -> "Spotify web sign-in answered " + reply.resultCode);
                    dialog.dismiss();
                    Utils.runOnMainThread(() -> deliver(activity, reply));
                }
                return true;
            }
        });
        dialog.setOnDismissListener(closed -> {
            Utils.runOnMainThread(web::destroy);
            if (!answered[0]) {
                answered[0] = true;
                Utils.runOnMainThread(() -> deliver(activity, new Reply(Activity.RESULT_CANCELED, null)));
            }
        });
        dialog.setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        web.loadUrl(page.toString());
    }

    /** Hands {@code reply} to the LoginActivity that asked, the way Android returns a result. */
    static void deliver(Activity activity, Reply reply) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Method onResult = Activity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
            onResult.setAccessible(true);
            onResult.invoke(activity, REQUEST_CODE, reply.resultCode, reply.data);
        } catch (Exception ex) {
            Logger.printException(() -> "Spotify web sign-in couldn't return its answer", ex);
        }
    }
}
