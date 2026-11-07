package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment;
import com.bytedance.android.live.design.resource.theme.LiveThemeAwareFragment;
import com.bytedance.android.live.design.view.sheet.LiveSheetFragment;
import com.bytedance.android.live.rank.impl.list.RankListDialog;
import com.bytedance.android.livesdk.fansclub.FansClubPanel;
import com.bytedance.android.livesdk.profile.ProfilePanel;

import androidx.cardview.widget.CardView;
import com.bytedance.android.live.rank.impl.list.view.PopularityRankingEducationDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * #159: a LIVE panel's views inflate through its own (dark) LIVE overlay, applied with force,
 * only while Dark LIVE panels is on and its context is in dark mode, and only for themed dialogs
 * and sheet pages. The daily ranking, its guide and every fragment under them inflate through the
 * light overlay TikTok's own answer gives the ranking, also with force, the ranking's plain list
 * fragments included, and over a room the switch darkened that overlay goes over the room's theme
 * as it was before the dark app theme, which the ranking's tab strip reads its colors through
 * too, and the rows LIVE's list binders make for a list there come from the list's own light
 * context, as does the info icon a list's bottom row tints. Everything else gets TikTok's
 * inflater back untouched.
 * Under the same conditions a LIVE room takes TikTok's dark app theme with force over its own light
 * one, so TikTok's generic sheets over a room open dark too, and the profile card's bio view takes
 * the link color, since its link is drawn in the view's own text color, and the card's labels
 * take their chip color as it shows over the light card their text and icon were picked for. The
 * card's event box, a white shape under text in the card theme's colors, takes a see-through light
 * fill once its text came out light, on its own copy of the shape.
 */
@RunWith(RobolectricTestRunner.class)
public class LivePanelThemeTest {
    private static final int OVERLAY = android.R.style.Theme_Light;

    /** A room's own fragment: it has an overlay too, but it isn't a panel. */
    public static final class RoomFragment {
        public int getThemeOverlay(Context context) {
            return OVERLAY;
        }
    }

    /** A dialog that extends the themed base, as LiveShareDialog does through LiveDialogFragment. */
    public static final class ShareDialog extends LiveThemeAwareDialogFragment {
    }

    private Context dark;
    private Context light;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(app);
        SettingsStatus.liveControlsEnabled = true;
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL;
        dark = new ContextThemeWrapper(app.createConfigurationContext(night), android.R.style.Theme_Black);
        Configuration day = new Configuration(night);
        day.uiMode = Configuration.UI_MODE_NIGHT_NO | Configuration.UI_MODE_TYPE_NORMAL;
        light = new ContextThemeWrapper(app.createConfigurationContext(day), android.R.style.Theme_Black);
    }

    @After public void tearDown() {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        Settings.DARK_LIVE_PANELS.save(Settings.DARK_LIVE_PANELS.defaultValue);
        LivePanelTheme.stockSeen = false;
        LivePanelTheme.panelSeen = false;
        ReflectionHelpers.<Map<?, ?>>getStaticField(LivePanelTheme.class, "DARK_PANELS").clear();
        // A Spark page asked with no activity looks for any darkened room, so none carries over.
        ReflectionHelpers.<Map<?, ?>>getStaticField(LivePanelTheme.class, "LIGHT_ROOMS").clear();
    }

    private static int textColor(Context context) {
        TypedValue value = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true));
        return value.resourceId != 0 ? value.resourceId : value.data;
    }

    private static ShareDialog dialog() {
        ShareDialog dialog = new ShareDialog();
        dialog.overlay = OVERLAY;
        return dialog;
    }

    @Test public void aPanelInflatesThroughItsOverlayWithForceInDarkModeWithTheSwitch() {
        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame("the switch starts off", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));

        Settings.DARK_LIVE_PANELS.save(true);
        LayoutInflater wrapped = LivePanelTheme.darkLivePanel(dialog(), tiktok);
        assertNotSame(tiktok, wrapped);
        // The overlay wins over the context's own theme, the way TikTok's light wrap does.
        assertEquals(textColor(new ContextThemeWrapper(dark, OVERLAY)), textColor(wrapped.getContext()));
        assertTrue(textColor(dark) != textColor(wrapped.getContext()));

        LiveSheetFragment sheet = new LiveSheetFragment();
        sheet.overlay = OVERLAY;
        assertNotSame("a sheet page is a panel", tiktok, LivePanelTheme.darkLivePanel(sheet, tiktok));
    }

    @Test public void lightModeRoomFragmentsPauseAndMissingPiecesKeepTikToksInflater() {
        Settings.DARK_LIVE_PANELS.save(true);
        LayoutInflater day = LayoutInflater.from(light);
        assertSame("light mode", day, LivePanelTheme.darkLivePanel(dialog(), day));

        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame("a room's own fragment", tiktok, LivePanelTheme.darkLivePanel(new RoomFragment(), tiktok));
        assertSame("no panel", tiktok, LivePanelTheme.darkLivePanel(null, tiktok));
        assertSame(null, LivePanelTheme.darkLivePanel(dialog(), null));
        ShareDialog none = new ShareDialog();
        assertSame("no overlay", tiktok, LivePanelTheme.darkLivePanel(none, tiktok));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));
    }

    /** A LIVE room with a light theme of its own, the way LivePlayActivity builds one. */
    private static Activity room() {
        Activity room = Robolectric.buildActivity(Activity.class).create().get();
        room.setTheme(android.R.style.Theme_Light);
        return room;
    }

    @Test public void aRoomTakesTikToksDarkAppThemeInDarkModeWithTheSwitch() {
        RuntimeEnvironment.setQualifiers("+night");
        Activity room = room();
        int stock = textColor(room);
        LivePanelTheme.darkLiveRoom(room, android.R.style.Theme_Black);
        assertEquals("the switch starts off", stock, textColor(room));

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLiveRoom(room, android.R.style.Theme_Black);
        // Applied with force, so it wins over the room's own light theme.
        assertEquals(textColor(new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Black)), textColor(room));
        assertTrue(stock != textColor(room));
    }

    @Test public void lightModeAPauseAndNoKnownThemeLeaveTheRoomAlone() {
        Settings.DARK_LIVE_PANELS.save(true);
        RuntimeEnvironment.setQualifiers("+notnight");
        Activity day = room();
        int stock = textColor(day);
        LivePanelTheme.darkLiveRoom(day, android.R.style.Theme_Black);
        assertEquals("light mode", stock, textColor(day));

        RuntimeEnvironment.setQualifiers("+night");
        Activity night = room();
        stock = textColor(night);
        LivePanelTheme.darkLiveRoom(night);
        assertEquals("without the patch the dark app theme is unknown", stock, textColor(night));
        LivePanelTheme.darkLiveRoom(night, 0);
        assertEquals("no theme", stock, textColor(night));
        LivePanelTheme.darkLiveRoom(null);
        LivePanelTheme.darkLiveRoom(null, android.R.style.Theme_Black);

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        LivePanelTheme.darkLiveRoom(night, android.R.style.Theme_Black);
        assertEquals("paused", stock, textColor(night));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        LivePanelTheme.darkLiveRoom(night, android.R.style.Theme_Black);
        assertEquals("without the patch", stock, textColor(night));
    }

    /** A page fragment, the way the daily ranking's RankPageFragment sits in its view pager. */
    public static final class RankPage extends LiveThemeAwareFragment {
    }

    /** A plain androidx fragment under a page, the way the ranking's GiftListFragment holds its rows. */
    public static final class GiftList {
        Object parent;
        int asked;

        public Object getParentFragment() {
            asked++;
            return parent;
        }
    }

    private static final int DARK_OVERLAY = android.R.style.Theme_Black_NoTitleBar;

    /** The room the ranking opens over: in dark mode too, but not the inflater's own context. */
    private Context room;

    private <T extends LiveThemeAwareDialogFragment> T stock(T panel) {
        panel.overlay = DARK_OVERLAY;
        panel.lightOverlay = OVERLAY;
        if (room == null) {
            room = new ContextThemeWrapper(dark, android.R.style.Theme_Black);
        }
        panel.context = room;
        return panel;
    }

    private static RankPage page(Object parent) {
        RankPage page = new RankPage();
        page.parent = parent;
        return page;
    }

    @Test public void theDailyRankingKeepsTikToksLightLiveTheme() {
        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame("the switch starts off", tiktok, LivePanelTheme.darkLivePanel(stock(new RankListDialog()), tiktok));

        Settings.DARK_LIVE_PANELS.save(true);
        int lightText = textColor(new ContextThemeWrapper(dark, OVERLAY));
        RankListDialog daily = stock(new RankListDialog());
        LayoutInflater ranking = LivePanelTheme.darkLivePanel(daily, tiktok);
        // TikTok's own answer picks the light overlay, which wins over the room's dark theme.
        assertNotSame(tiktok, ranking);
        assertEquals(lightText, textColor(ranking.getContext()));
        assertTrue(textColor(dark) != textColor(ranking.getContext()));
        assertSame("asked with the ranking's own context", room, daily.asked);
        assertTrue("the provider hook answers again afterwards", LiveControls.darkLivePanels(dark));
        assertEquals("its first-time guide", lightText,
            textColor(LivePanelTheme.darkLivePanel(stock(new PopularityRankingEducationDialog()), tiktok).getContext()));
        assertEquals("a ranking subclass", lightText,
            textColor(LivePanelTheme.darkLivePanel(stock(new HourlyRanking()), tiktok).getContext()));

        // Its pages take their inflater from the room, so they're found by their parent fragment,
        // and the ranking is what's asked.
        RankListDialog parent = stock(new RankListDialog());
        assertEquals("a ranking page", lightText,
            textColor(LivePanelTheme.darkLivePanel(page(parent), tiktok).getContext()));
        assertSame("the page asks its ranking", room, parent.asked);
        assertSame("a page under an ordinary panel", tiktok, LivePanelTheme.darkLivePanel(page(dialog()), tiktok));
        assertSame("a page on its own", tiktok, LivePanelTheme.darkLivePanel(page(null), tiktok));

        // A dialog opened from a ranking page is an ordinary panel and keeps its own overlay.
        ShareDialog share = dialog();
        share.overlay = DARK_OVERLAY;
        share.parent = page(stock(new RankListDialog()));
        assertEquals("a dialog over the ranking's pages keeps its dark overlay, not the ranking's light one",
            textColor(new ContextThemeWrapper(dark, DARK_OVERLAY)), textColor(LivePanelTheme.darkLivePanel(share, tiktok).getContext()));

        RankListDialog detached = stock(new RankListDialog());
        detached.context = null;
        assertEquals("no context of its own", lightText, textColor(LivePanelTheme.darkLivePanel(detached, tiktok).getContext()));
        assertSame("asks with the inflater's", dark, detached.asked);
        RankListDialog none = new RankListDialog();
        none.context = room;
        assertSame("no overlay", tiktok, LivePanelTheme.darkLivePanel(none, tiktok));
        RankListDialog outside = stock(new RankListDialog());
        outside.lightOverlay = DARK_OVERLAY;
        assertSame("TikTok answering dark itself keeps its inflater", tiktok, LivePanelTheme.darkLivePanel(outside, tiktok));
        LayoutInflater day = LayoutInflater.from(light);
        assertSame("light mode", day, LivePanelTheme.darkLivePanel(stock(new RankListDialog()), day));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", tiktok, LivePanelTheme.darkLivePanel(stock(new RankListDialog()), tiktok));
        assertSame("a page, paused", tiktok, LivePanelTheme.darkLivePanel(page(stock(new RankListDialog())), tiktok));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", tiktok, LivePanelTheme.darkLivePanel(stock(new RankListDialog()), tiktok));
    }

    @Test public void aPlainFragmentUnderTheRankingTakesItsLightTheme() {
        LayoutInflater tiktok = LayoutInflater.from(dark);
        GiftList list = new GiftList();
        list.parent = page(stock(new RankListDialog()));
        assertSame("the switch starts off", tiktok, LivePanelTheme.darkLivePage(list, tiktok));

        Settings.DARK_LIVE_PANELS.save(true);
        // Every fragment in the app passes here, so none looks at its parents before a ranking inflated.
        LivePanelTheme.stockSeen = false;
        assertSame("before any ranking", tiktok, LivePanelTheme.darkLivePage(list, tiktok));
        assertEquals("no walk before any ranking", 0, list.asked);
        assertNotSame(tiktok, LivePanelTheme.darkLivePanel(stock(new RankListDialog()), tiktok));
        assertTrue("a ranking inflated", LivePanelTheme.stockSeen);
        // A plain fragment takes the room's inflater, so it's found by its parents too.
        LayoutInflater rows = LivePanelTheme.darkLivePage(list, tiktok);
        assertEquals(1, list.asked);
        assertNotSame(tiktok, rows);
        assertEquals(textColor(new ContextThemeWrapper(dark, OVERLAY)), textColor(rows.getContext()));
        GiftList direct = new GiftList();
        direct.parent = stock(new RankListDialog());
        assertNotSame("right under the ranking", tiktok, LivePanelTheme.darkLivePage(direct, tiktok));

        // LIVE's themed fragments and dialogs have their own hook, so this one leaves them alone.
        assertSame("a ranking page", tiktok, LivePanelTheme.darkLivePage(page(stock(new RankListDialog())), tiktok));
        assertSame("the ranking", tiktok, LivePanelTheme.darkLivePage(stock(new RankListDialog()), tiktok));
        assertSame("an ordinary panel", tiktok, LivePanelTheme.darkLivePage(dialog(), tiktok));

        GiftList elsewhere = new GiftList();
        assertSame("a fragment under no panel", tiktok, LivePanelTheme.darkLivePage(elsewhere, tiktok));
        elsewhere.parent = dialog();
        assertSame("a fragment under an ordinary panel", tiktok, LivePanelTheme.darkLivePage(elsewhere, tiktok));
        assertSame("a fragment without parents", tiktok, LivePanelTheme.darkLivePage(new RoomFragment(), tiktok));
        assertSame("no fragment", tiktok, LivePanelTheme.darkLivePage(null, tiktok));
        assertSame(null, LivePanelTheme.darkLivePage(list, null));
        LayoutInflater day = LayoutInflater.from(light);
        assertSame("light mode", day, LivePanelTheme.darkLivePage(list, day));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", tiktok, LivePanelTheme.darkLivePage(list, tiktok));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", tiktok, LivePanelTheme.darkLivePage(list, tiktok));
    }

    /** #362: the moderator settings' muted and blocked lists are plain fragments under a dark panel. */
    @Test public void aPlainFragmentUnderADarkenedPanelTakesItsDarkContext() {
        LayoutInflater tiktok = LayoutInflater.from(dark);
        Settings.DARK_LIVE_PANELS.save(true);
        ShareDialog admin = dialog();
        GiftList list = new GiftList();
        list.parent = admin;
        assertSame("before any panel was darkened", tiktok, LivePanelTheme.darkLivePage(list, tiktok));
        assertEquals("no walk before any panel was darkened", 0, list.asked);

        LayoutInflater sheet = LivePanelTheme.darkLivePanel(admin, tiktok);
        assertNotSame(tiktok, sheet);
        assertTrue("a panel was darkened", LivePanelTheme.panelSeen);
        LayoutInflater rows = LivePanelTheme.darkLivePage(list, tiktok);
        assertNotSame(tiktok, rows);
        assertSame("the panel's own dark context", sheet.getContext(), rows.getContext());
        assertEquals(textColor(new ContextThemeWrapper(dark, OVERLAY)), textColor(rows.getContext()));
        GiftList deeper = new GiftList();
        deeper.parent = page(admin);
        assertSame("through a themed page", sheet.getContext(), LivePanelTheme.darkLivePage(deeper, tiktok).getContext());

        GiftList other = new GiftList();
        other.parent = dialog();
        assertSame("a panel that wasn't darkened", tiktok, LivePanelTheme.darkLivePage(other, tiktok));
        RankListDialog ranking = stock(new RankListDialog());
        ranking.parent = admin;
        GiftList under = new GiftList();
        under.parent = ranking;
        assertSame("the walk stops at a stock panel", tiktok, LivePanelTheme.darkLivePage(under, tiktok));
        assertSame("a fragment under no panel", tiktok, LivePanelTheme.darkLivePage(new GiftList(), tiktok));
        assertSame("the panel itself", tiktok, LivePanelTheme.darkLivePage(admin, tiktok));
        assertSame(null, LivePanelTheme.darkLivePage(list, null));
        LayoutInflater day = LayoutInflater.from(light);
        assertSame("light mode", day, LivePanelTheme.darkLivePage(list, day));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", tiktok, LivePanelTheme.darkLivePage(list, tiktok));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", tiktok, LivePanelTheme.darkLivePage(list, tiktok));
    }

    /** #362: the gift guide bubble is white, so over a darkened room it inflates over the room's light theme. */
    @Test public void theGiftGuideBubbleOverADarkenedRoomTakesTheRoomsLightTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        Activity live = room();
        int roomText = textColor(live);
        assertSame("the switch starts off", live, LivePanelTheme.lightRoomView(live));

        Settings.DARK_LIVE_PANELS.save(true);
        assertSame("a room the hook never darkened", live, LivePanelTheme.lightRoomView(live));
        LivePanelTheme.darkLiveRoom(live, android.R.style.Theme_Black);
        assertTrue("the room went dark", roomText != textColor(live));
        Context bubble = LivePanelTheme.lightRoomView(live);
        assertNotSame(live, bubble);
        assertSame("the bubble still sits in the room", live, LivePanelTheme.roomOf(bubble));
        assertEquals("the bubble's text takes the room's light color", roomText, textColor(bubble));
        assertTrue("the room itself stays dark", roomText != textColor(live));
        Context wrapped = new ContextThemeWrapper(live, PARTIAL_OVERLAY);
        assertEquals("found through a wrapped room context", roomText, textColor(LivePanelTheme.lightRoomView(wrapped)));
        Context app = RuntimeEnvironment.getApplication();
        assertSame("no room", app, LivePanelTheme.lightRoomView(app));
        assertSame(null, LivePanelTheme.lightRoomView(null));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", live, LivePanelTheme.lightRoomView(live));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", live, LivePanelTheme.lightRoomView(live));
    }

    /** Sets only the window animations, the way TikTok's light LIVE overlay leaves most of a theme alone. */
    private static final int PARTIAL_OVERLAY = android.R.style.Animation_Dialog;

    private static boolean hasOverlay(Context context) {
        return context.getTheme().resolveAttribute(android.R.attr.windowEnterAnimation, new TypedValue(), true);
    }

    @Test public void theRankingOverADarkenedRoomStartsFromTheRoomsLightTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        Settings.DARK_LIVE_PANELS.save(true);
        Activity live = room();
        int roomText = textColor(live);
        assertTrue("the room has no window animation of its own", !hasOverlay(live));
        LivePanelTheme.darkLiveRoom(live, android.R.style.Theme_Black);
        assertTrue("the room went dark", roomText != textColor(live));
        // The room's setTheme can run again on the same activity; the copy from before the first pass stays.
        LivePanelTheme.darkLiveRoom(live, android.R.style.Theme_Black);

        RankListDialog daily = stock(new RankListDialog());
        daily.lightOverlay = PARTIAL_OVERLAY;
        daily.context = live;
        Context ranking = LivePanelTheme.darkLivePanel(daily, LayoutInflater.from(live)).getContext();
        assertTrue("the light overlay is on", hasOverlay(ranking));
        assertEquals("what the overlay leaves alone keeps the room's light value", roomText, textColor(ranking));
        assertTrue("the room itself stays dark", roomText != textColor(live));
        GiftList list = new GiftList();
        list.parent = page(daily);
        Context rows = LivePanelTheme.darkLivePage(list, LayoutInflater.from(live)).getContext();
        assertTrue(hasOverlay(rows));
        assertEquals("a list under the ranking too", roomText, textColor(rows));
        Context wrapped = LivePanelTheme.darkLivePanel(daily, LayoutInflater.from(new ContextThemeWrapper(live, OVERLAY))).getContext();
        assertEquals("found through a wrapped room context", roomText, textColor(wrapped));

        // An ordinary panel keeps the darkened room under its own overlay.
        ShareDialog share = dialog();
        share.overlay = PARTIAL_OVERLAY;
        assertEquals(textColor(live), textColor(LivePanelTheme.darkLivePanel(share, LayoutInflater.from(live)).getContext()));

        // A room the switch never darkened has nothing kept, so the overlay goes over its own theme.
        Activity other = room();
        assertSame(null, LivePanelTheme.lightRoom(other));
        assertSame(null, LivePanelTheme.lightRoom(RuntimeEnvironment.getApplication()));
        assertSame(null, LivePanelTheme.lightRoom(null));
        daily.context = other;
        Context plain = LivePanelTheme.darkLivePanel(daily, LayoutInflater.from(other)).getContext();
        assertTrue(hasOverlay(plain));
        assertEquals(textColor(other), textColor(plain));
    }

    /** Spark's SchemaBundle, the page schema's parsed query. */
    public static final class SchemaBundle {
        final Map<String, String> queries = new HashMap<>();
        String url;

        public Map<String, String> getAllQueries() {
            return queries;
        }

        public String getUrl() {
            return url;
        }
    }

    /** Spark's SparkContext, one page's schema. */
    public static final class SparkPage {
        public SchemaBundle schemaBundle;
        public String fullUrl;
    }

    /** The audience ranking popup's schema, as the emulator's heap held it (2026-10-23), cut down. */
    private static String rankingUrl(String buyers, String type) {
        return "sslocal://lynxview_popup?room_id=1&url=https%3A%2F%2Fexample%2Faudience_rank.js&width=360"
                + "&can_show_top_buyers_rank=" + buyers + "&entrance_rank_type=" + type + "&show_anchor_header=0";
    }

    private static SparkPage ranking(boolean buyers) {
        SparkPage page = new SparkPage();
        page.schemaBundle = new SchemaBundle();
        page.schemaBundle.queries.put("can_show_top_buyers_rank", buyers ? "1" : "0");
        page.fullUrl = rankingUrl(buyers ? "1" : "0", buyers ? "top_buyers" : "top_viewers");
        return page;
    }

    @Test public void onlyTheShopRankingOverADarkenedRoomKeepsSparksLightTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        Activity live = room();
        SparkPage buyers = ranking(true);
        assertTrue("the switch starts off", !LivePanelTheme.sparkKeepsStock(buyers, live));
        Settings.DARK_LIVE_PANELS.save(true);
        assertTrue("a room the switch never darkened", !LivePanelTheme.sparkKeepsStock(buyers, live));
        LivePanelTheme.darkLiveRoom(live, android.R.style.Theme_Black);

        assertTrue("the shop ranking", LivePanelTheme.sparkKeepsStock(buyers, live));
        assertTrue("found through a popup's own wrapper",
                LivePanelTheme.sparkKeepsStock(buyers, new ContextThemeWrapper(live, OVERLAY)));
        assertTrue("asked with no activity, the darkened room", LivePanelTheme.sparkKeepsStock(buyers, null));
        assertTrue("asked with no activity, the darkened room", LivePanelTheme.sparkKeepsStock(buyers, dark));
        assertTrue("the plain viewer ranking reads dark", !LivePanelTheme.sparkKeepsStock(ranking(false), live));
        assertTrue("another Spark page reads dark", !LivePanelTheme.sparkKeepsStock(new SparkPage(), live));
        assertTrue("no page", !LivePanelTheme.sparkKeepsStock(null, live));
        assertTrue("another room the switch never darkened", !LivePanelTheme.sparkKeepsStock(buyers, room()));

        // The schema's parsed query wins; the page's URLs answer when it has none.
        SparkPage parsed = ranking(false);
        parsed.fullUrl = rankingUrl("1", "top_buyers");
        assertTrue("the parsed query says viewers", !LivePanelTheme.sparkKeepsStock(parsed, live));
        SparkPage url = ranking(true);
        url.schemaBundle.queries.clear();
        assertTrue("the page's full URL", LivePanelTheme.sparkKeepsStock(url, live));
        url.fullUrl = null;
        url.schemaBundle.url = rankingUrl("1", "top_buyers");
        assertTrue("the schema's URL", LivePanelTheme.sparkKeepsStock(url, live));
        url.schemaBundle.url = rankingUrl("10", "top_buyers");
        assertTrue("only the whole value", !LivePanelTheme.sparkKeepsStock(url, live));

        Settings.DARK_LIVE_PANELS.save(false);
        assertTrue("the switch off", !LivePanelTheme.sparkKeepsStock(buyers, live));
        Settings.DARK_LIVE_PANELS.save(true);
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertTrue("paused", !LivePanelTheme.sparkKeepsStock(buyers, live));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertTrue("without the patch", !LivePanelTheme.sparkKeepsStock(buyers, live));
    }

    @Test public void theRankingsTabsReadTheirColorsThroughItsLightTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        Activity live = room();
        int roomText = textColor(live);
        RankListDialog daily = stock(new RankListDialog());
        daily.lightOverlay = PARTIAL_OVERLAY;
        daily.context = live;
        RankPage page = page(daily);

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLiveRoom(live, android.R.style.Theme_Black);
        assertTrue("the room went dark", roomText != textColor(live));
        assertSame("before any ranking inflated", live, LivePanelTheme.stockContext(page, live));
        LivePanelTheme.darkLivePanel(daily, LayoutInflater.from(live));
        Settings.DARK_LIVE_PANELS.save(false);
        assertSame("the switch off", live, LivePanelTheme.stockContext(page, live));
        Settings.DARK_LIVE_PANELS.save(true);
        // The tab strip hands over the page's getContext(), the room; before the page has a view
        // its tabs get the ranking's light overlay over the room's light theme.
        Context tabs = LivePanelTheme.stockContext(page, live);
        assertTrue("the light overlay is on", hasOverlay(tabs));
        assertEquals("the tabs' colors resolve to the room's light values", roomText, textColor(tabs));
        assertTrue("the room itself stays dark", roomText != textColor(live));
        // Once it has one, the context its views inflated with.
        Context inflated = LivePanelTheme.darkLivePanel(page, LayoutInflater.from(live)).getContext();
        page.view = new View(inflated);
        assertSame(inflated, LivePanelTheme.stockContext(page, live));

        // Outside a room TikTok's own answer is already dark: no light overlay to put back, so even
        // a page with a view keeps the context it handed over.
        RankListDialog outside = stock(new RankListDialog());
        outside.lightOverlay = DARK_OVERLAY;
        RankPage outsidePage = page(outside);
        outsidePage.view = new View(new ContextThemeWrapper(live, OVERLAY));
        assertSame("TikTok answering dark itself", live, LivePanelTheme.stockContext(outsidePage, live));

        RankPage elsewhere = page(dialog());
        elsewhere.view = new View(live);
        assertSame("a page under an ordinary panel", live, LivePanelTheme.stockContext(elsewhere, live));
        assertSame("a page on its own", live, LivePanelTheme.stockContext(page(null), live));
        assertSame("no page", live, LivePanelTheme.stockContext(null, live));
        assertSame("no context", null, LivePanelTheme.stockContext(page, null));
        assertSame("light mode", light, LivePanelTheme.stockContext(page, light));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", live, LivePanelTheme.stockContext(page, live));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", live, LivePanelTheme.stockContext(page, live));
    }

    @Test public void aListUnderTheRankingMakesItsRowsThroughItsLightTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        Activity live = room();
        int roomText = textColor(live);
        RankListDialog daily = stock(new RankListDialog());
        daily.lightOverlay = PARTIAL_OVERLAY;
        daily.context = live;
        GiftList list = new GiftList();
        list.parent = page(daily);
        int layout = android.R.layout.simple_list_item_1;

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLiveRoom(live, android.R.style.Theme_Black);
        assertNull("before any ranking inflated", LivePanelTheme.stockRow(new FrameLayout(live), layout, Boolean.FALSE));
        LivePanelTheme.darkLivePanel(daily, LayoutInflater.from(live));
        // The list's own view inflated through its fragment's light inflater, so it carries that context.
        Context rows = LivePanelTheme.darkLivePage(list, LayoutInflater.from(live)).getContext();
        ViewGroup recycler = new FrameLayout(rows);
        View row = LivePanelTheme.stockRow(recycler, layout, Boolean.FALSE);
        assertSame("inflated through the list's own context", rows, row.getContext());
        assertEquals("the row's colors resolve to the room's light values", roomText, textColor(row.getContext()));
        assertTrue("the room itself stays dark", roomText != textColor(live));
        assertNull("left for the binder's holder call to place", row.getParent());
        assertNotNull("a list context a view wrapped in its own theme",
            LivePanelTheme.stockRow(new FrameLayout(new ContextThemeWrapper(rows, OVERLAY)), layout, Boolean.FALSE));
        assertNotNull("no attach flag", LivePanelTheme.stockRow(recycler, layout, null));
        // A room the switch never darkened keeps no light theme, so its ranking takes the overlay alone, still stock.
        Activity plainRoom = room();
        RankListDialog plain = stock(new RankListDialog());
        plain.lightOverlay = PARTIAL_OVERLAY;
        plain.context = plainRoom;
        GiftList plainList = new GiftList();
        plainList.parent = page(plain);
        LivePanelTheme.darkLivePanel(plain, LayoutInflater.from(plainRoom));
        Context plainRows = LivePanelTheme.darkLivePage(plainList, LayoutInflater.from(plainRoom)).getContext();
        assertNotNull("a ranking over a room the switch never darkened",
            LivePanelTheme.stockRow(new FrameLayout(plainRows), layout, Boolean.FALSE));

        // Every other row is TikTok's.
        assertNull("a row TikTok attaches as it inflates", LivePanelTheme.stockRow(recycler, layout, Boolean.TRUE));
        assertNull("a list in the room", LivePanelTheme.stockRow(new FrameLayout(live), layout, Boolean.FALSE));
        ShareDialog share = dialog();
        share.overlay = PARTIAL_OVERLAY;
        Context panel = LivePanelTheme.darkLivePanel(share, LayoutInflater.from(live)).getContext();
        assertNull("a list in an ordinary panel", LivePanelTheme.stockRow(new FrameLayout(panel), layout, Boolean.FALSE));
        assertNull("no list", LivePanelTheme.stockRow(null, layout, Boolean.FALSE));
        assertNull("no layout", LivePanelTheme.stockRow(recycler, 0, Boolean.FALSE));
        assertNull("a layout that doesn't inflate", LivePanelTheme.stockRow(recycler, -1, Boolean.FALSE));
        assertNull("light mode", LivePanelTheme.stockRow(new FrameLayout(new LivePanelTheme.StockContext(light, OVERLAY)), layout, Boolean.FALSE));
        Settings.DARK_LIVE_PANELS.save(false);
        assertNull("the switch off", LivePanelTheme.stockRow(recycler, layout, Boolean.FALSE));
        Settings.DARK_LIVE_PANELS.save(true);

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertNull("paused", LivePanelTheme.stockRow(recycler, layout, Boolean.FALSE));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertNull("without the patch", LivePanelTheme.stockRow(recycler, layout, Boolean.FALSE));
    }

    @Test public void aListsBottomRowTintsItsInfoIconThroughTheRowsLightTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        Activity live = room();
        int roomText = textColor(live);
        RankListDialog daily = stock(new RankListDialog());
        daily.lightOverlay = PARTIAL_OVERLAY;
        daily.context = live;
        GiftList list = new GiftList();
        list.parent = page(daily);

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLiveRoom(live, android.R.style.Theme_Black);
        TextView early = new TextView(new LivePanelTheme.StockContext(live, OVERLAY));
        assertSame("before any ranking inflated", live, LivePanelTheme.stockViewContext(early, live));
        LivePanelTheme.darkLivePanel(daily, LayoutInflater.from(live));
        // The bar's text inflated with the list's own view, through its fragment's light inflater.
        Context bar = LivePanelTheme.darkLivePage(list, LayoutInflater.from(live)).getContext();
        TextView gap = new TextView(bar);
        Context icon = LivePanelTheme.stockViewContext(gap, live);
        assertSame("the row's own context", gap.getContext(), icon);
        assertEquals("the icon's tint resolves to the room's light value", roomText, textColor(icon));
        assertTrue("the room itself stays dark", roomText != textColor(live));
        TextView wrapped = new TextView(new ContextThemeWrapper(bar, OVERLAY));
        assertSame("a row a view wrapped in its own theme", wrapped.getContext(), LivePanelTheme.stockViewContext(wrapped, live));

        // Every other row keeps the context it handed over.
        assertSame("a row in the room", live, LivePanelTheme.stockViewContext(new TextView(live), live));
        ShareDialog share = dialog();
        share.overlay = PARTIAL_OVERLAY;
        Context panel = LivePanelTheme.darkLivePanel(share, LayoutInflater.from(live)).getContext();
        assertSame("a row in an ordinary panel", live, LivePanelTheme.stockViewContext(new TextView(panel), live));
        assertSame("no row", live, LivePanelTheme.stockViewContext(null, live));
        ReflectionHelpers.setStaticField(LivePanelTheme.class, "viewContextFailureLogged", false);
        assertSame("a row text that isn't a view", live, LivePanelTheme.stockViewContext("99+", live));
        assertTrue("turned away, not caught", !(boolean) ReflectionHelpers.getStaticField(LivePanelTheme.class, "viewContextFailureLogged"));
        assertNull("no context", LivePanelTheme.stockViewContext(gap, null));
        assertSame("light mode", light, LivePanelTheme.stockViewContext(new TextView(new LivePanelTheme.StockContext(light, OVERLAY)), light));
        Settings.DARK_LIVE_PANELS.save(false);
        assertSame("the switch off", live, LivePanelTheme.stockViewContext(gap, live));
        Settings.DARK_LIVE_PANELS.save(true);

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", live, LivePanelTheme.stockViewContext(gap, live));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", live, LivePanelTheme.stockViewContext(gap, live));
    }

    @Test public void aBiosViewTakesTheLinkColorInDarkModeWithTheSwitch() {
        int link = 0xe6ffffff;
        TextView bio = new TextView(dark);
        bio.setTextColor(Color.BLACK);
        LivePanelTheme.bioLinkColor(link, bio);
        assertEquals("the switch starts off", Color.BLACK, bio.getCurrentTextColor());

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.bioLinkColor(link, bio);
        assertEquals(link, bio.getCurrentTextColor());

        TextView lightBio = new TextView(light);
        lightBio.setTextColor(Color.BLACK);
        LivePanelTheme.bioLinkColor(link, lightBio);
        assertEquals("light mode", Color.BLACK, lightBio.getCurrentTextColor());
        LivePanelTheme.bioLinkColor(link, "bio");
        LivePanelTheme.bioLinkColor(link, null);

        TextView paused = new TextView(dark);
        paused.setTextColor(Color.BLACK);
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        LivePanelTheme.bioLinkColor(link, paused);
        assertEquals("paused", Color.BLACK, paused.getCurrentTextColor());
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        LivePanelTheme.bioLinkColor(link, paused);
        assertEquals("without the patch", Color.BLACK, paused.getCurrentTextColor());
    }

    /** ProfileCardLabel's shape and simple name: the background the extension colors. */
    static final class ProfileCardLabel {
        private LabelBackground background;

        ProfileCardLabel(LabelBackground background) {
            this.background = background;
        }
    }

    /** Some other model with the same shape, which the extension leaves alone. */
    static final class Sticker {
        private LabelBackground background;

        Sticker(LabelBackground background) {
            this.background = background;
        }
    }

    /** LabelBackground's shape, made by the extension through its no-argument constructor. */
    static final class LabelBackground {
        private String colorCode;
        private Object image;

        private LabelBackground() {
        }

        static LabelBackground of(String colorCode) {
            LabelBackground background = new LabelBackground();
            background.colorCode = colorCode;
            return background;
        }
    }

    /** A label whose background is static, which a write would set for every label at once. */
    static final class SharedLabel {
        static LabelBackground background;
    }

    /** A background whose color is static. */
    static final class SharedColor {
        static String colorCode = "#0A161823";
    }

    static final class SharedColorLabel {
        private final SharedColor background = new SharedColor();
    }

    @Test public void aLabelsStaticFieldsAreLeftAlone() throws Exception {
        LivePanelTheme.lightLabel(new SharedLabel());
        assertNull("a static background was made", SharedLabel.background);
        LivePanelTheme.lightLabel(new SharedColorLabel());
        assertEquals("a static color was written", "#0A161823", SharedColor.colorCode);
    }

    @Test public void aCardsLabelsTakeTheirColorAsItShowsOverTheLightCardInDarkModeWithTheSwitch() {
        View strip = new View(dark);
        ProfileCardLabel faint = new ProfileCardLabel(LabelBackground.of("#0A161823"));
        LivePanelTheme.cardLabels(Arrays.asList(faint), strip);
        assertEquals("the switch starts off", "#0A161823", faint.background.colorCode);

        Settings.DARK_LIVE_PANELS.save(true);
        ProfileCardLabel opaque = new ProfileCardLabel(LabelBackground.of("#2A2A2A"));
        ProfileCardLabel none = new ProfileCardLabel(LabelBackground.of(null));
        ProfileCardLabel empty = new ProfileCardLabel(LabelBackground.of(""));
        ProfileCardLabel unreadable = new ProfileCardLabel(LabelBackground.of("nope"));
        ProfileCardLabel pictured = new ProfileCardLabel(LabelBackground.of("#0A161823"));
        pictured.background.image = new Object();
        ProfileCardLabel bare = new ProfileCardLabel(null);
        Sticker sticker = new Sticker(LabelBackground.of("#0A161823"));
        LivePanelTheme.cardLabels(Arrays.asList(faint, null, opaque, none, empty, unreadable, pictured, bare, sticker), strip);
        assertEquals("over white", "#FFF5F5F6", faint.background.colorCode);
        assertEquals("#FF2A2A2A", opaque.background.colorCode);
        assertEquals("TikTok's default over white", "#FFF1F1F2", none.background.colorCode);
        assertEquals("#FFF1F1F2", empty.background.colorCode);
        assertEquals("#FFF1F1F2", unreadable.background.colorCode);
        assertEquals("an image stays", "#0A161823", pictured.background.colorCode);
        assertEquals("a label without one gets one", "#FFF1F1F2", bare.background.colorCode);
        assertEquals("not a card label", "#0A161823", sticker.background.colorCode);
        LivePanelTheme.cardLabels(Arrays.asList(faint), strip);
        assertEquals("twice is the same", "#FFF5F5F6", faint.background.colorCode);

        ProfileCardLabel lightLabel = new ProfileCardLabel(LabelBackground.of("#0A161823"));
        LivePanelTheme.cardLabels(Arrays.asList(lightLabel), new View(light));
        assertEquals("light mode", "#0A161823", lightLabel.background.colorCode);
        LivePanelTheme.cardLabels(Arrays.asList(lightLabel), "strip");
        LivePanelTheme.cardLabels(Arrays.asList(lightLabel), null);
        LivePanelTheme.cardLabels(lightLabel, strip);
        LivePanelTheme.cardLabels(null, strip);
        LivePanelTheme.cardLabels(Arrays.asList("label", 7), strip);
        assertEquals("#0A161823", lightLabel.background.colorCode);

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        LivePanelTheme.cardLabels(Arrays.asList(lightLabel), strip);
        assertEquals("paused", "#0A161823", lightLabel.background.colorCode);
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        LivePanelTheme.cardLabels(Arrays.asList(lightLabel), strip);
        assertEquals("without the patch", "#0A161823", lightLabel.background.colorCode);
    }

    /** The event module's box: a shape drawn from the given state around a title in the given color. */
    private static ViewGroup eventBox(Context context, int textColor, Drawable.ConstantState shape) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(shape.newDrawable());
        TextView title = new TextView(context);
        title.setTextColor(textColor);
        box.addView(title);
        return box;
    }

    private static int fill(View box) {
        return ((GradientDrawable) box.getBackground()).getColor().getDefaultColor();
    }

    @Test public void aCardsEventBoxTakesALightFillWhenItsTextCameOutLightInDarkModeWithTheSwitch() {
        GradientDrawable white = new GradientDrawable();
        white.setColor(Color.WHITE);
        white.setCornerRadius(16f);
        Drawable.ConstantState shape = white.getConstantState();
        ViewGroup box = eventBox(dark, 0xFFF6F6F6, shape);
        LivePanelTheme.eventBox(box);
        assertEquals("the switch starts off", Color.WHITE, fill(box));

        Settings.DARK_LIVE_PANELS.save(true);
        ViewGroup other = eventBox(dark, Color.BLACK, shape);
        LivePanelTheme.eventBox(box);
        assertEquals(0x29FFFFFF, fill(box));
        assertEquals("the corners stay", 16f, ((GradientDrawable) box.getBackground()).getCornerRadius(), 0f);
        assertEquals("the shared shape stays white", Color.WHITE, fill(other));
        LivePanelTheme.eventBox(box);
        assertEquals("twice is the same", 0x29FFFFFF, fill(box));
        LivePanelTheme.eventBox(other);
        assertEquals("dark text keeps its white", Color.WHITE, fill(other));

        ViewGroup nested = new FrameLayout(dark);
        nested.setBackground(shape.newDrawable());
        nested.addView(eventBox(dark, Color.WHITE, shape));
        LivePanelTheme.eventBox(nested);
        assertEquals("text a level down", 0x29FFFFFF, fill(nested));

        ViewGroup lightBox = eventBox(light, Color.WHITE, shape);
        LivePanelTheme.eventBox(lightBox);
        assertEquals("light mode", Color.WHITE, fill(lightBox));
        ViewGroup plain = new FrameLayout(dark);
        plain.setBackgroundColor(Color.WHITE);
        TextView plainTitle = new TextView(dark);
        plainTitle.setTextColor(Color.WHITE);
        plain.addView(plainTitle);
        LivePanelTheme.eventBox(plain);
        assertEquals("not a shape", Color.WHITE, ((ColorDrawable) plain.getBackground()).getColor());
        LivePanelTheme.eventBox(new TextView(dark));
        LivePanelTheme.eventBox("box");
        LivePanelTheme.eventBox(null);

        ViewGroup paused = eventBox(dark, Color.WHITE, shape);
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        LivePanelTheme.eventBox(paused);
        assertEquals("paused", Color.WHITE, fill(paused));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        LivePanelTheme.eventBox(paused);
        assertEquals("without the patch", Color.WHITE, fill(paused));
    }

    @Test public void aCardsBadgeTileGoesOverADarkCopyOfItsShapeInDarkModeWithTheSwitch() {
        // The low levels' tile on 47.4.3: a pale lavender at about a fifth of full strength.
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(0x30CCD1F0);
        tint.setCornerRadius(16f);
        tint.setStroke(1, Color.RED);
        View tile = new View(dark);
        LivePanelTheme.badgeTile(tile, tint);
        assertSame("the switch starts off", tint, tile.getBackground());

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.badgeTile(tile, tint);
        LayerDrawable layers = (LayerDrawable) tile.getBackground();
        assertEquals(2, layers.getNumberOfLayers());
        assertEquals("the shape's padding counts once", LayerDrawable.PADDING_MODE_STACK, layers.getPaddingMode());
        GradientDrawable base = (GradientDrawable) layers.getDrawable(0);
        assertEquals(LivePanelTheme.DARK_FILL, base.getColor().getDefaultColor());
        assertEquals("the corners stay", 16f, base.getCornerRadius(), 0f);
        assertSame("the server's shape draws over it", tint, layers.getDrawable(1));
        assertEquals("the tile's own shape keeps its fill", 0x30CCD1F0, tint.getColor().getDefaultColor());

        // A higher level's solid pink tile, which over white showed as a light block on the dark card.
        GradientDrawable solid = new GradientDrawable();
        solid.setColor(0xFFFFF1F3);
        solid.setCornerRadius(12f);
        View pinkTile = new View(dark);
        LivePanelTheme.badgeTile(pinkTile, solid);
        GradientDrawable shown = (GradientDrawable) pinkTile.getBackground();
        assertNotSame("a copy, not the server's shape", solid, shown);
        assertEquals("a solid tile takes the dark fill", LivePanelTheme.DARK_FILL, shown.getColor().getDefaultColor());
        assertEquals(12f, shown.getCornerRadius(), 0f);
        assertEquals("the server's shape keeps its fill", 0xFFFFF1F3, solid.getColor().getDefaultColor());
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFFF1F3, 0xFFF1F3FF});
        assertTrue("a light gradient", LivePanelTheme.lightShape(fade));
        assertTrue("a see-through stop", !LivePanelTheme.lightShape(
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFFF1F3, 0x30F1F3FF})));
        assertTrue("no fill at all", !LivePanelTheme.lightShape(new GradientDrawable()));

        View lightTile = new View(light);
        LivePanelTheme.badgeTile(lightTile, tint);
        assertSame("light mode", tint, lightTile.getBackground());
        ColorDrawable plain = new ColorDrawable(Color.RED);
        LivePanelTheme.badgeTile(tile, plain);
        assertSame("not a shape", plain, tile.getBackground());
        LivePanelTheme.badgeTile(tile, null);
        assertNull("no background", tile.getBackground());
        GradientDrawable stateless = new GradientDrawable() {
            @Override public ConstantState getConstantState() {
                return null;
            }
        };
        LivePanelTheme.badgeTile(tile, stateless);
        assertSame("a shape with no state to copy", stateless, tile.getBackground());
        GradientDrawable broken = new GradientDrawable() {
            @Override public ConstantState getConstantState() {
                throw new IllegalStateException("copy");
            }
        };
        LivePanelTheme.badgeTile(tile, broken);
        assertSame("a failed copy still sets the tile's own", broken, tile.getBackground());

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        LivePanelTheme.badgeTile(tile, tint);
        assertSame("paused", tint, tile.getBackground());
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        LivePanelTheme.badgeTile(tile, tint);
        assertSame("without the patch", tint, tile.getBackground());
    }

    @Test public void panelsAreFoundByTikToksClassNames() {
        assertEquals(LiveThemeAwareDialogFragment.class.getName(), LivePanelTheme.THEMED_DIALOG);
        assertEquals(LiveSheetFragment.class.getName(), LivePanelTheme.SHEET);
        assertEquals(LiveThemeAwareFragment.class.getName(), LivePanelTheme.THEMED_FRAGMENT);
        assertEquals(RankListDialog.class.getName(), LivePanelTheme.RANK_LIST);
        assertEquals(PopularityRankingEducationDialog.class.getName(), LivePanelTheme.RANK_GUIDE);
        assertTrue(LivePanelTheme.isPanel(ShareDialog.class));
        assertTrue(!LivePanelTheme.isPanel(RoomFragment.class));
        assertTrue(!LivePanelTheme.isPanel(RankListDialog.class));
        assertTrue(!LivePanelTheme.isPanel(PopularityRankingEducationDialog.class));
        assertTrue("a ranking subclass stays stock too", !LivePanelTheme.isPanel(HourlyRanking.class));
        assertTrue("a sheet page is a panel, not a themed page", LivePanelTheme.isPanel(LiveSheetFragment.class));
        assertTrue("a themed page isn't a panel", !LivePanelTheme.isPanel(RankPage.class));
        RankListDialog ranking = new RankListDialog();
        assertSame(ranking, LivePanelTheme.stockPanel(ranking));
        HourlyRanking hourly = new HourlyRanking();
        assertSame(hourly, LivePanelTheme.stockPanel(hourly));
        assertSame("a page finds its ranking", ranking, LivePanelTheme.stockPanel(page(ranking)));
        assertSame(null, LivePanelTheme.stockPanel(dialog()));
        assertSame(null, LivePanelTheme.stockPanel(new RoomFragment()));
        ShareDialog share = dialog();
        share.parent = ranking;
        assertSame("an ordinary panel over the ranking ends the walk", null, LivePanelTheme.stockPanel(share));
        RankPage loop = page(null);
        loop.parent = loop;
        assertSame("a parent loop ends", null, LivePanelTheme.stockPanel(loop));
    }

    /** A custom drawn tab strip with three paints of its own, the way the fan club's is built. */
    static final class StripView extends View {
        final Paint selected = new Paint();
        final Paint other = new Paint();
        final Paint unselected = new Paint();

        StripView(Context context) {
            super(context);
            selected.setColor(Color.WHITE);
            other.setColor(Color.WHITE);
            unselected.setColor(0x0D000000);
        }
    }

    private Dialog dialogWith(View content) {
        Dialog dialog = new Dialog(dark);
        dialog.setContentView(content);
        // Shown, so the decor is attached and keeps the system UI flags it is given.
        dialog.show();
        return dialog;
    }

    @Test public void aPanelsNavigationBarGoesDarkInDarkModeWithTheSwitch() {
        FansClubPanel panel = new FansClubPanel();
        panel.overlay = OVERLAY;
        panel.dialog = dialogWith(new FrameLayout(dark));
        android.view.WindowInsetsController bars = panel.dialog.getWindow().getInsetsController();
        bars.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);

        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertTrue((bars.getSystemBarsAppearance() & android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0);

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(light));
        assertTrue((bars.getSystemBarsAppearance() & android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0);

        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertEquals(0, bars.getSystemBarsAppearance() & android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    @Test public void theProfileCardsInnerTilesGoDarkButItsLabelsAndSeeThroughTilesStay() {
        FrameLayout root = new FrameLayout(dark);
        View tile = new View(dark);
        tile.setBackground(new ColorDrawable(Color.WHITE));
        View see = new View(dark);
        see.setBackground(new ColorDrawable(0x29FFFFFF));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFFF2F2F2);
        View rounded = new View(dark);
        rounded.setBackground(shape);
        ViewGroup strip = new FrameLayout(dark);
        strip.setBackground(new ColorDrawable(Color.WHITE));
        root.addView(tile);
        root.addView(see);
        root.addView(rounded);
        root.addView(strip);
        ProfilePanel panel = new ProfilePanel();
        panel.overlay = OVERLAY;
        panel.dialog = dialogWith(root);

        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertEquals("the switch starts off", Color.WHITE, ((ColorDrawable) tile.getBackground()).getColor());

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.cardLabels(Arrays.asList(), strip);
        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertEquals(LivePanelTheme.DARK_FILL, ((ColorDrawable) tile.getBackground()).getColor());
        assertEquals(0x29FFFFFF, ((ColorDrawable) see.getBackground()).getColor());
        assertEquals(LivePanelTheme.DARK_FILL, ((GradientDrawable) rounded.getBackground()).getColor().getDefaultColor());
        assertEquals("the label strip stays light", Color.WHITE, ((ColorDrawable) strip.getBackground()).getColor());
        assertTrue("an ordinary panel keeps its fills", !LivePanelTheme.fillPanel(ShareDialog.class));
    }

    @Test public void theFanClubTabStripAndItsCardGoDarkInDarkModeWithTheSwitch() {
        FrameLayout root = new FrameLayout(dark);
        CardView card = new CardView(dark);
        card.setCardBackgroundColor(Color.WHITE);
        StripView strip = new StripView(dark);
        card.addView(strip);
        root.addView(card);
        FansClubPanel panel = new FansClubPanel();
        panel.overlay = OVERLAY;
        panel.dialog = dialogWith(root);

        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertEquals("the switch starts off", Color.WHITE, card.getCardBackgroundColor().getDefaultColor());
        assertEquals(Color.WHITE, strip.selected.getColor());

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(light));
        assertEquals("TikTok Light stays stock", Color.WHITE, strip.selected.getColor());

        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertEquals(LivePanelTheme.DARK_FILL, card.getCardBackgroundColor().getDefaultColor());
        assertEquals(LivePanelTheme.DARK_FILL, strip.selected.getColor());
        assertEquals(LivePanelTheme.DARK_FILL, strip.other.getColor());
        assertEquals(LivePanelTheme.DARK_TAB, strip.unselected.getColor());
    }

    /** A plain LIVE sheet with a window, like the gift sheet: dark through its overlay, no fills of its own. */
    public static final class MenuSheet extends LiveThemeAwareDialogFragment {
        public Dialog dialog;

        public Dialog getDialog() {
            return dialog;
        }
    }

    private static final int LIGHT_BAR = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;

    /** Runs the panel window's before-frame listeners, as the next frame would. */
    private static void nextFrame(Dialog dialog) {
        ReflectionHelpers.callInstanceMethod(dialog.getWindow().getDecorView().getViewTreeObserver(), "dispatchOnPreDraw");
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 600);
    }

    private static TextView text(Context context, int color) {
        TextView text = new TextView(context);
        text.setText("Fan");
        text.setTextColor(color);
        return text;
    }

    @Test public void aSheetsNavigationBarStaysDarkWhenTikTokTurnsItLightAgain() {
        MenuSheet stock = new MenuSheet();
        stock.overlay = OVERLAY;
        stock.dialog = dialogWith(new FrameLayout(dark));
        android.view.WindowInsetsController stockBars = stock.dialog.getWindow().getInsetsController();
        stockBars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        LivePanelTheme.darkLivePanel(stock, LayoutInflater.from(dark));
        nextFrame(stock.dialog);
        assertEquals("the switch starts off", LIGHT_BAR, stockBars.getSystemBarsAppearance() & LIGHT_BAR);

        Settings.DARK_LIVE_PANELS.save(true);
        MenuSheet menu = new MenuSheet();
        menu.overlay = OVERLAY;
        menu.dialog = dialogWith(new FrameLayout(dark));
        android.view.WindowInsetsController bars = menu.dialog.getWindow().getInsetsController();
        LivePanelTheme.darkLivePanel(menu, LayoutInflater.from(dark));
        assertEquals(0, bars.getSystemBarsAppearance() & LIGHT_BAR);

        // TikTok's sheet puts the light bar back as it shows.
        bars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        nextFrame(menu.dialog);
        assertEquals(0, bars.getSystemBarsAppearance() & LIGHT_BAR);
    }

    @Test public void theLongPressMenusPlainDialogGetsADarkNavigationBar() {
        Dialog stock = dialogWith(new FrameLayout(dark));
        android.view.WindowInsetsController stockBars = stock.getWindow().getInsetsController();
        stockBars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        LivePanelTheme.liveMenu(stock);
        assertEquals("the switch starts off", LIGHT_BAR, stockBars.getSystemBarsAppearance() & LIGHT_BAR);

        Settings.DARK_LIVE_PANELS.save(true);
        Dialog lightMenu = new Dialog(light);
        lightMenu.setContentView(new FrameLayout(light));
        lightMenu.show();
        android.view.WindowInsetsController lightBars = lightMenu.getWindow().getInsetsController();
        lightBars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        LivePanelTheme.liveMenu(lightMenu);
        assertEquals("light mode", LIGHT_BAR, lightBars.getSystemBarsAppearance() & LIGHT_BAR);

        Dialog menu = dialogWith(new FrameLayout(dark));
        android.view.WindowInsetsController bars = menu.getWindow().getInsetsController();
        bars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        LivePanelTheme.liveMenu(menu);
        assertEquals(0, bars.getSystemBarsAppearance() & LIGHT_BAR);
        // The menu sets its window up again as it shows.
        bars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        nextFrame(menu);
        assertEquals(0, bars.getSystemBarsAppearance() & LIGHT_BAR);
        LivePanelTheme.liveMenu(null);
    }

    @Test public void theLongPressMenuIsDarkenedOnceItShowsNotBefore() {
        Settings.DARK_LIVE_PANELS.save(true);
        // TikTok hands the menu over before showing it, with no window views built yet.
        Dialog menu = new Dialog(dark);
        LivePanelTheme.liveMenu(menu);
        assertNull("the window's views are left for the menu to build", menu.getWindow().peekDecorView());

        menu.setContentView(new FrameLayout(dark));
        menu.show();
        android.view.WindowInsetsController bars = menu.getWindow().getInsetsController();
        bars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
        assertEquals(0, bars.getSystemBarsAppearance() & LIGHT_BAR);
        bars.setSystemBarsAppearance(LIGHT_BAR, LIGHT_BAR);
        nextFrame(menu);
        assertEquals(0, bars.getSystemBarsAppearance() & LIGHT_BAR);
    }

    @Test public void darkGrayTextOnASwappedFillTurnsLightAndStaysLight() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(dark);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        // The fan club's tabs: a strip that paints them, and the labels in an overlay drawn after it.
        FrameLayout tabs = new FrameLayout(dark);
        FrameLayout holder = new FrameLayout(dark);
        holder.addView(new StripView(dark));
        FrameLayout overlay = new FrameLayout(dark);
        TextView fan = text(dark, Color.BLACK);
        overlay.addView(fan);
        tabs.addView(holder);
        tabs.addView(overlay);
        root.addView(tabs, new android.widget.LinearLayout.LayoutParams(1080, 100));
        TextView below = text(dark, Color.BLACK);
        root.addView(below, new android.widget.LinearLayout.LayoutParams(1080, 100));
        // The profile card's showcase row: a white shape under black and pink text.
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        android.widget.LinearLayout row = new android.widget.LinearLayout(dark);
        row.setBackground(shape);
        TextView title = text(dark, 0x80161823);
        TextView pink = text(dark, 0xFFFE2C55);
        FrameLayout chip = new FrameLayout(dark);
        chip.setBackground(new ColorDrawable(0x29FFFFFF));
        TextView onChip = text(dark, Color.BLACK);
        chip.addView(onChip);
        row.addView(title);
        row.addView(pink);
        row.addView(chip);
        root.addView(row, new android.widget.LinearLayout.LayoutParams(1080, 100));
        TextView pill = text(dark, Color.BLACK);
        pill.setBackground(new ColorDrawable(Color.WHITE));
        root.addView(pill, new android.widget.LinearLayout.LayoutParams(1080, 100));

        Settings.DARK_LIVE_PANELS.save(true);
        FansClubPanel lightPanel = new FansClubPanel();
        lightPanel.overlay = OVERLAY;
        lightPanel.dialog = dialogWith(root);
        layOut(root);
        LivePanelTheme.darkLivePanel(lightPanel, LayoutInflater.from(light));
        assertEquals("TikTok Light stays stock", Color.BLACK, fan.getCurrentTextColor());
        assertEquals(Color.BLACK, pill.getCurrentTextColor());

        LivePanelTheme.darkLivePanel(lightPanel, LayoutInflater.from(dark));
        assertEquals("the label over the dark tab", Color.WHITE, fan.getCurrentTextColor());
        assertEquals("text keeps how see-through it is", 0x80FFFFFF, title.getCurrentTextColor());
        assertEquals("colored text keeps its color", 0xFFFE2C55, pink.getCurrentTextColor());
        assertEquals("text on its own light chip keeps its ink", Color.BLACK, onChip.getCurrentTextColor());
        assertEquals("a white pill's own text", Color.WHITE, pill.getCurrentTextColor());
        assertEquals("text beside the tabs, not over them, keeps its ink", Color.BLACK, below.getCurrentTextColor());

        // TikTok recolors the label as the pick changes.
        fan.setTextColor(0x66000000);
        nextFrame(lightPanel.dialog);
        assertEquals(0x66FFFFFF, fan.getCurrentTextColor());
    }

    @Test public void layeredFillsPicturelessCardsAndBadgeTilesGoDarkAndLightTheirText() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(dark);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        // The fan club's FANS card: an image view with no picture, its background a see-through
        // white layered over a clear shape, and its label in a sibling drawn after it.
        FrameLayout card = new FrameLayout(dark);
        android.widget.ImageView cardBackground = new android.widget.ImageView(dark);
        GradientDrawable veil = new GradientDrawable();
        veil.setColor(0xBFFFFFFF);
        GradientDrawable clear = new GradientDrawable();
        clear.setColor(Color.TRANSPARENT);
        cardBackground.setBackground(new LayerDrawable(new Drawable[]{veil, clear}));
        card.addView(cardBackground, new FrameLayout.LayoutParams(1080, 100));
        TextView fans = text(dark, Color.BLACK);
        card.addView(fans);
        root.addView(card, new android.widget.LinearLayout.LayoutParams(1080, 100));
        // A profile card badge: the server's art in an image view over its tile, its level text after it.
        FrameLayout badge = new FrameLayout(dark);
        android.widget.ImageView tile = new android.widget.ImageView(dark);
        tile.setImageDrawable(new ColorDrawable(Color.RED));
        badge.addView(tile, new FrameLayout.LayoutParams(300, 100));
        TextView level = text(dark, 0x55161823);
        badge.addView(level);
        root.addView(badge, new android.widget.LinearLayout.LayoutParams(1080, 100));
        // A photo on a white ground, which isn't a fill.
        android.widget.ImageView photo = new android.widget.ImageView(dark);
        photo.setImageDrawable(new ColorDrawable(Color.BLUE));
        photo.setBackground(new ColorDrawable(Color.WHITE));
        root.addView(photo, new android.widget.LinearLayout.LayoutParams(1080, 100));

        Settings.DARK_LIVE_PANELS.save(true);
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(0x30CCD1F0);
        LivePanelTheme.badgeTile(tile, tint);
        ProfilePanel panel = new ProfilePanel();
        panel.overlay = OVERLAY;
        panel.dialog = dialogWith(root);
        layOut(root);
        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));

        assertEquals("the card's white layer", LivePanelTheme.DARK_FILL, veil.getColor().getDefaultColor());
        assertEquals("its clear layer stays clear", Color.TRANSPARENT, clear.getColor().getDefaultColor());
        assertEquals("the FANS label", Color.WHITE, fans.getCurrentTextColor());
        assertEquals("the level text over the dark tile", 0x55FFFFFF, level.getCurrentTextColor());
        assertEquals("a photo's ground", Color.WHITE, ((ColorDrawable) photo.getBackground()).getColor());
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aDarkIconOnASwappedFillIsDrawnWhiteButColoredArtAndPhotosStay() {
        FrameLayout root = new FrameLayout(dark);
        android.widget.LinearLayout row = new android.widget.LinearLayout(dark);
        row.setBackground(new ColorDrawable(Color.WHITE));
        android.widget.ImageView chevron = new android.widget.ImageView(dark);
        chevron.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        chevron.setPadding(10, 10, 10, 10);
        chevron.setImageDrawable(new ColorDrawable(0x59000000));
        android.widget.ImageView art = new android.widget.ImageView(dark);
        art.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        art.setPadding(10, 10, 10, 10);
        art.setImageDrawable(new ColorDrawable(0xFFFE2C55));
        android.widget.ImageView photo = new android.widget.ImageView(dark);
        photo.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        photo.setImageDrawable(new ColorDrawable(0xFF202020));
        android.widget.ImageView badge = new android.widget.ImageView(dark);
        badge.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        badge.setPadding(10, 10, 10, 10);
        badge.setBackground(new LayerDrawable(new Drawable[]{new ColorDrawable(Color.WHITE)}));
        badge.setImageDrawable(new ColorDrawable(Color.BLACK));
        for (View icon : new View[]{chevron, art, photo, badge}) {
            row.addView(icon, new android.widget.LinearLayout.LayoutParams(40, 40));
        }
        root.addView(row);
        FansClubPanel panel = new FansClubPanel();
        panel.overlay = OVERLAY;
        panel.dialog = dialogWith(root);
        layOut(root);

        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertNull("the switch starts off", chevron.getColorFilter());

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLivePanel(panel, LayoutInflater.from(dark));
        assertNotNull("the chevron goes white", chevron.getColorFilter());
        assertNull("colored art stays", art.getColorFilter());
        assertNull("a photo stays", photo.getColorFilter());
        assertNull("an icon on its own tile stays", badge.getColorFilter());

        // TikTok swaps the chevron's image for colored art: it shows as drawn.
        chevron.setImageDrawable(new ColorDrawable(0xFFFE2C55));
        nextFrame(panel.dialog);
        assertNull(chevron.getColorFilter());
    }

    @Test public void anIconIsOneDarkGrayInkOnAClearGround() {
        int clear = Color.TRANSPARENT;
        assertTrue(LivePanelTheme.darkMonochrome(new int[]{clear, 0x66000000, 0x66000000, clear}));
        assertTrue("anti-aliased edges are the same ink", LivePanelTheme.darkMonochrome(new int[]{clear, 0x20161823, 0xFF161823}));
        assertTrue("a photo has no clear ground", !LivePanelTheme.darkMonochrome(new int[]{0xFF202020, 0xFF202020}));
        assertTrue("two inks are art", !LivePanelTheme.darkMonochrome(new int[]{clear, 0xFF000000, 0xFF505050}));
        assertTrue("a colored ink is art", !LivePanelTheme.darkMonochrome(new int[]{clear, 0xFF7A1020}));
        assertTrue("light ink is already readable", !LivePanelTheme.darkMonochrome(new int[]{clear, Color.WHITE}));
        assertTrue("nothing drawn", !LivePanelTheme.darkMonochrome(new int[]{clear, clear}));
        assertTrue(LivePanelTheme.darkInk(0x80161823));
        assertTrue("TikTok's pink isn't gray", !LivePanelTheme.darkInk(0xFFFE2C55));
        assertEquals(0x59FFFFFF, LivePanelTheme.lightened(0x59000000));
        assertTrue(!LivePanelTheme.ownBackdrop(null));
        assertTrue(!LivePanelTheme.ownBackdrop(new ColorDrawable(Color.TRANSPARENT)));
        assertTrue(!LivePanelTheme.ownBackdrop(new ColorDrawable(LivePanelTheme.DARK_FILL)));
        assertTrue(LivePanelTheme.ownBackdrop(new ColorDrawable(0x29FFFFFF)));
        assertTrue(LivePanelTheme.ownBackdrop(new LayerDrawable(new Drawable[]{new ColorDrawable(Color.WHITE)})));
        assertTrue("a ripple with only its mask is clear", !LivePanelTheme.ownBackdrop(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.GRAY), null, new ColorDrawable(Color.WHITE))));
    }

    /** A ranking built on the daily one, the way TikTok splits its list dialogs into variants. */
    static class HourlyRanking extends RankListDialog { }
}
