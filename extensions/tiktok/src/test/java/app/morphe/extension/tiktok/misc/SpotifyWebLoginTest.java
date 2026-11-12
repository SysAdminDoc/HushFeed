package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * Spotify's sign-in hand-off as TikTok's music feature builds it on 47.7.3 (`X/1hYn`): the
 * START_AUTH_FLOW intent with the client id, redirect, response type, scopes and state, sent for
 * result 1138. The web page has to ask Spotify for the same thing, stop at the same redirect, and
 * answer the SDK's LoginActivity in the shape its onActivityResult reads.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SpotifyWebLoginTest {
    private static final String CLIENT = "949ee5eac701483299bde049ac493baf";
    private static final String REDIRECT = "https://www.tiktok.com/third-party-oauth-redirect/music/spotify";

    /** Stands in for the SDK's LoginActivity: keeps every result it's handed. */
    public static class LoginActivity extends Activity {
        int results;
        int requestCode;
        int resultCode;
        Intent data;

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            results++;
            this.requestCode = requestCode;
            this.resultCode = resultCode;
            this.data = data;
        }
    }

    private ActivityController<LoginActivity> controller;
    private LoginActivity login;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        controller = Robolectric.buildActivity(LoginActivity.class).setup();
        login = controller.get();
    }

    @After public void tearDown() {
        Dialog open = ShadowDialog.getLatestDialog();
        if (open != null && open.isShowing()) open.dismiss();
        controller.pause().stop().destroy();
    }

    private static Intent handOff() {
        return new Intent(SpotifyWebLogin.START_AUTH_FLOW)
                .setPackage("com.spotify.music")
                .putExtra("VERSION", 1)
                .putExtra("CLIENT_ID", CLIENT)
                .putExtra("REDIRECT_URI", REDIRECT)
                .putExtra("RESPONSE_TYPE", "code")
                .putExtra("SCOPES", new String[]{"user-library-modify", "playlist-modify-public"})
                .putExtra("STATE", "s1")
                .putExtra("UTM_SOURCE", "spotify-sdk");
    }

    private WebView openPage() {
        assertTrue(SpotifyWebLogin.openInstead(SpotifyWebLogin.REQUEST_CODE, login, handOff()));
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("no sign-in page opened", dialog);
        assertTrue(dialog.isShowing());
        View content = ((ViewGroup) dialog.findViewById(android.R.id.content)).getChildAt(0);
        assertTrue("the page isn't a WebView: " + content, content instanceof WebView);
        return (WebView) content;
    }

    private void land(WebView web, String url) {
        shadowOf(web).getWebViewClient().onPageStarted(web, url, null);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void onlySpotifysHandOffIsTaken() {
        assertFalse(SpotifyWebLogin.openInstead(1137, login, handOff()));
        assertFalse(SpotifyWebLogin.openInstead(SpotifyWebLogin.REQUEST_CODE, login,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.spotify.music"))));
        assertFalse(SpotifyWebLogin.openInstead(SpotifyWebLogin.REQUEST_CODE, login, null));
        assertFalse(SpotifyWebLogin.openInstead(SpotifyWebLogin.REQUEST_CODE, null, handOff()));
        // A request the page can't serve goes to the Spotify app as TikTok sent it.
        assertFalse(SpotifyWebLogin.openInstead(SpotifyWebLogin.REQUEST_CODE, login, handOff().putExtra("RESPONSE_TYPE", "token")));
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test
    public void thePageAsksSpotifyForWhatTheAppWasAskedFor() {
        Uri page = SpotifyWebLogin.authorizePage(handOff());
        assertNotNull(page);
        assertEquals("https://accounts.spotify.com/authorize", page.buildUpon().clearQuery().build().toString());
        assertEquals(CLIENT, page.getQueryParameter("client_id"));
        assertEquals("code", page.getQueryParameter("response_type"));
        assertEquals(REDIRECT, page.getQueryParameter("redirect_uri"));
        assertEquals("user-library-modify playlist-modify-public", page.getQueryParameter("scope"));
        assertEquals("s1", page.getQueryParameter("state"));

        assertNull(SpotifyWebLogin.authorizePage(handOff().putExtra("CLIENT_ID", "")));
        assertNull(SpotifyWebLogin.authorizePage(handOff().putExtra("REDIRECT_URI", (String) null)));
        Uri bare = SpotifyWebLogin.authorizePage(handOff().putExtra("SCOPES", new String[0]).putExtra("STATE", (String) null));
        assertNull(bare.getQueryParameter("scope"));
        assertNull(bare.getQueryParameter("state"));

        assertEquals(page.toString(), shadowOf(openPage()).getLastLoadedUrl());
    }

    @Test
    public void theRedirectIsKnownByWhereItGoesNotItsQuery() {
        Uri redirect = Uri.parse(REDIRECT);
        assertTrue(SpotifyWebLogin.isRedirect(Uri.parse(REDIRECT + "?code=a&state=s1"), redirect));
        assertTrue(SpotifyWebLogin.isRedirect(Uri.parse("https://WWW.TIKTOK.COM/third-party-oauth-redirect/music/spotify/?error=x"), redirect));
        assertFalse(SpotifyWebLogin.isRedirect(Uri.parse("https://accounts.spotify.com/en/login?continue=" + Uri.encode(REDIRECT)), redirect));
        assertFalse(SpotifyWebLogin.isRedirect(Uri.parse("https://www.tiktok.com/third-party-oauth-redirect/music/deezer?code=a"), redirect));
        assertFalse(SpotifyWebLogin.isRedirect(Uri.parse("http://www.tiktok.com/third-party-oauth-redirect/music/spotify?code=a"), redirect));
        assertFalse(SpotifyWebLogin.isRedirect(null, redirect));
    }

    @Test
    public void aCodeGoesBackTheWayTheSpotifyAppSendsIt() {
        WebView web = openPage();
        land(web, "https://accounts.spotify.com/en/login");
        assertEquals("a Spotify page answered the SDK", 0, login.results);

        land(web, REDIRECT + "?code=AQBcode&state=s1");
        assertEquals(1, login.results);
        assertEquals(SpotifyWebLogin.REQUEST_CODE, login.requestCode);
        assertEquals(Activity.RESULT_OK, login.resultCode);
        Bundle reply = login.data.getParcelableExtra("REPLY");
        assertNotNull(reply);
        assertEquals("code", reply.getString("RESPONSE_TYPE"));
        assertEquals("AQBcode", reply.getString("AUTHORIZATION_CODE"));
        assertEquals("s1", reply.getString("STATE"));
        assertFalse(ShadowDialog.getLatestDialog().isShowing());

        // The redirect showing up again, or the page closing after, doesn't answer twice.
        land(web, REDIRECT + "?code=other");
        assertEquals(1, login.results);
    }

    @Test
    public void aRefusalOrAClosedPageStillAnswers() {
        land(openPage(), REDIRECT + "?error=access_denied&state=s1");
        assertEquals(SpotifyWebLogin.RESULT_ERROR, login.resultCode);
        assertEquals("access_denied", login.data.getStringExtra("ERROR"));

        openPage();
        ShadowDialog.getLatestDialog().dismiss();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, login.results);
        assertEquals(Activity.RESULT_CANCELED, login.resultCode);
        assertNull(login.data);
    }

    @Test
    public void nothingGoesToALoginThatAlreadyClosed() {
        WebView web = openPage();
        login.finish();
        land(web, REDIRECT + "?code=late");
        assertEquals(0, login.results);
    }
}
