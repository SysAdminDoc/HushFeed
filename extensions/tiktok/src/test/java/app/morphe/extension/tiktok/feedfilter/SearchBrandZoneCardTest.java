package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #484: searching "wildwood" opened with a brand's block, an "Ad" banner, a row of its videos and
 * its official account with Follow. On the S22 (47.9.3) it's a native search card of its own,
 * drawn by the brand zone protocol, and the result list Remove feed ads filters held five plain
 * videos. Its view holder is handed over like the AI answer's, and with Remove feed ads on the
 * row goes to no height. Hide Tako AI has nothing to do with it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SearchBrandZoneCardTest {
    private static final String BRAND_ZONE =
            "com.ss.android.ugc.aweme.search.pages.result.topsearch.commerce.searchbrand.SearchAdBrandZoneProtocol";
    private static final String BRAND_ZONE_HYBRID =
            "com.ss.android.ugc.aweme.search.pages.result.topsearch.commerce.hybrid.SearchAdBrandZoneHybridProtocol";
    private static final String VIDEO_CARD = "com.ss.android.ugc.aweme.search.pages.result.video.SearchVideoCardProtocol";

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        FeedFilterCounters.clear();
        Settings.HIDE_TAKO_AI.save(false);
        Settings.REMOVE_ADS.save(true);
    }

    @After public void tearDown() {
        Settings.HIDE_TAKO_AI.resetToDefault();
        Settings.REMOVE_ADS.resetToDefault();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private static View row(int height) {
        View row = new FrameLayout(RuntimeEnvironment.getApplication());
        row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return row;
    }

    @Test public void theBrandBlockGoesWithRemoveFeedAds() {
        View brand = row(1900);
        TakoAiFilter.hideSearchAnswerCard(brand, BRAND_ZONE);
        assertEquals("the brand block was left on the search page", View.GONE, brand.getVisibility());
        assertEquals(0, brand.getLayoutParams().height);

        View hybrid = row(1900);
        TakoAiFilter.hideSearchAnswerCard(hybrid, BRAND_ZONE_HYBRID);
        assertEquals(View.GONE, hybrid.getVisibility());

        View video = row(300);
        TakoAiFilter.hideSearchAnswerCard(video, VIDEO_CARD);
        assertEquals("a video card's row was hidden", View.VISIBLE, video.getVisibility());
        assertEquals(300, video.getLayoutParams().height);

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("search brand zone: 1 found"));
        String counters = String.join("\n", FeedFilterCounters.report());
        assertTrue(counters, counters.contains("SearchBrandZone"));
    }

    @Test public void theBrandBlockStaysWithRemoveFeedAdsOff() {
        Settings.REMOVE_ADS.save(false);
        Settings.HIDE_TAKO_AI.save(true);
        View brand = row(1900);
        TakoAiFilter.hideSearchAnswerCard(brand, BRAND_ZONE);
        assertEquals(View.VISIBLE, brand.getVisibility());
        assertEquals(1900, brand.getLayoutParams().height);
    }
}
