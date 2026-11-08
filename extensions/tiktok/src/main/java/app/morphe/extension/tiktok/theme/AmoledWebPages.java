/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import android.webkit.WebView;

import org.json.JSONObject;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.privacy.BrowserPrivacyGuard;

/**
 * AMOLED dark theme on TikTok's web pages: Activity center, Watch history, Comment history and
 * the others SparkActivity shows (#263).
 *
 * <p>Those pages are TikTok web pages in a WebView, so the resource side never reaches them. They
 * fill with TikTok's web design tokens, and in dark mode every one is black already except the
 * gray tiers the native app maps to its own black: Activity center's card is
 * {@code --ui-page-grouped-2}. The patch calls {@link #onPage} from every TikTok WebViewClient's
 * page-finished and page-visible callbacks, and on TikTok's own pages this puts one style
 * element in the document that sets those tokens to the AMOLED color, only under the page's dark
 * scheme. A page in light mode keeps its white, and pages anywhere else are left alone.
 */
public final class AmoledWebPages {
    private static final String HOOK_FAMILY = "amoled web pages";
    static final String STYLE_ID = "hushfeed-amoled";

    /**
     * The web tokens behind the dark tiers the resource side paints: UIPageGrouped2 (Activity
     * center's card, a settings card natively), UISheetFlat1 and UISheetGrouped1 (sheets) and
     * BGPrimary. Each page bundle names them one of two ways, so both are set. UISheetGrouped2,
     * the cards on a sheet, keeps its gray like it does natively.
     */
    static final String[] TOKENS = {
            "--UIPageGrouped2", "--ui-page-grouped-2",
            "--UISheetFlat1", "--ui-sheet-flat-1",
            "--UISheetGrouped1", "--ui-sheet-grouped-1",
            "--BGPrimary", "--bg-primary",
    };

    private AmoledWebPages() {}

    /** Called at the start of TikTok's page-finished and page-visible callbacks. Never throws. */
    public static void onPage(WebView webView, String url) {
        onPage(webView, url, color());
    }

    static void onPage(WebView webView, String url, int argb) {
        try {
            if (webView == null || argb == 0 || !BrowserPrivacyGuard.isTrustedAppPage(url)) return;
            // Off in Hushfeed settings, the pages keep TikTok's grays like the app (#372).
            if (!AmoledTheme.on()) return;
            HookStatus.bound(HOOK_FAMILY, cssColor(argb));
            // TikTok hands one page event down a chain of its clients, so a page can hear this a
            // few times. The script keeps one style element, so a repeat only rewrites it.
            webView.evaluateJavascript(script(argb), null);
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "onPage", failure);
        }
    }

    /** The AMOLED color, filled in by the patch. Zero leaves the pages as TikTok draws them. */
    private static int color() {
        return 0;
    }

    static String cssColor(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /**
     * The tokens set to the color under TikTok's dark scheme: the page's own data attribute when
     * it sets one, and the system's dark mode when it follows that and has picked no light one.
     */
    static String styleSheet(int argb) {
        StringBuilder body = new StringBuilder();
        String color = cssColor(argb);
        for (String token : TOKENS) {
            if (body.length() > 0) body.append(';');
            body.append(token).append(':').append(color).append(" !important");
        }
        return "[data-tux-color-scheme=\"dark\"],[data-theme=\"dark\"]{" + body + "}"
                + "@media (prefers-color-scheme:dark){:root:not([data-tux-color-scheme=\"light\"])"
                + ":not([data-theme=\"light\"]){" + body + "}}";
    }

    /** Puts the style sheet in the page's one AMOLED style element, adding it the first time. */
    static String script(int argb) {
        return "(function(){var d=document,s=d.getElementById('" + STYLE_ID + "');"
                + "if(!s){s=d.createElement('style');s.id='" + STYLE_ID + "';"
                + "(d.head||d.documentElement).appendChild(s);}"
                + "s.textContent=" + JSONObject.quote(styleSheet(argb)) + ";})();";
    }
}
