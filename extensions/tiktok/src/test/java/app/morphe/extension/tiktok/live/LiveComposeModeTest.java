package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertSame;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.bytedance.android.live.liveinteract.multiguestv3.main.userinfo.guest.MultiLiveGuestUserInfoDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.util.Map;

/**
 * #362: TikTok's LIVE Compose host gives every LIVE Compose view the Light palette, black text on
 * a pill at 5% black. The guest panel's "Find an open guest spot" bar is one, a page under the
 * guest panel, whose white sheet goes black with Dark LIVE panels on, so the bar read black on
 * black. The page hands its view over as it makes it, and a view under a darkened panel takes the
 * Dark palette from the host's mode; everything else keeps TikTok's.
 */
@RunWith(RobolectricTestRunner.class)
public class LiveComposeModeTest {
    /** TikTok's Compose palette modes, named as X.0hLJ names them on 48.0.2. */
    enum Mode { Auto, Light, Dark }

    /** The guest spot page: a plain fragment under the guest panel; only getParentFragment is read. */
    public static final class GuestSpotPage {
        public Object parent;

        public Object getParentFragment() {
            return parent;
        }
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
        SettingsStatus.liveControlsEnabled = false;
        Settings.DARK_LIVE_PANELS.save(Settings.DARK_LIVE_PANELS.defaultValue);
        LivePanelTheme.stockSeen = false;
        LivePanelTheme.panelSeen = false;
        ReflectionHelpers.<Map<?, ?>>getStaticField(LivePanelTheme.class, "DARK_PANELS").clear();
        ReflectionHelpers.<Map<?, ?>>getStaticField(LivePanelTheme.class, "DARK_COMPOSE").clear();
    }

    private MultiLiveGuestUserInfoDialog guestPanel() {
        MultiLiveGuestUserInfoDialog guest = new MultiLiveGuestUserInfoDialog();
        guest.overlay = android.R.style.Theme_Light;
        guest.view = new FrameLayout(dark);
        return guest;
    }

    /** The bar's view as the page makes it, handed over, and the mode the host then gives it. */
    private static Object barMode(GuestSpotPage page, Context context) {
        View bar = new FrameLayout(context);
        LivePanelTheme.darkComposePage(page, bar);
        return LivePanelTheme.composeMode(bar, Mode.Light);
    }

    @Test public void theGuestSpotBarUnderADarkenedGuestPanelTakesTheDarkPalette() {
        MultiLiveGuestUserInfoDialog guest = guestPanel();
        GuestSpotPage page = new GuestSpotPage();
        page.parent = guest;
        LayoutInflater tiktok = LayoutInflater.from(dark);

        LivePanelTheme.darkLivePanel(guest, tiktok);
        assertSame("the switch starts off", Mode.Light, barMode(page, dark));

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLivePanel(guest, tiktok);
        assertSame("under the darkened guest panel", Mode.Dark, barMode(page, dark));
        assertSame("in light mode the switch leaves it", Mode.Light, barMode(page, light));
        assertSame("a Compose view no page handed over", Mode.Light,
                LivePanelTheme.composeMode(new FrameLayout(dark), Mode.Light));

        GuestSpotPage undarkened = new GuestSpotPage();
        undarkened.parent = guestPanel();
        assertSame("under a panel that wasn't darkened", Mode.Light, barMode(undarkened, dark));
        assertSame("under no panel", Mode.Light, barMode(new GuestSpotPage(), dark));
    }
}
