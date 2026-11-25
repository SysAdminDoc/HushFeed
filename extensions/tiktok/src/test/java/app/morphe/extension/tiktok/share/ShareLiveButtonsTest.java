/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Record and Auto tap as rows of a LIVE's menu (#359). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ShareLiveButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for BaseSharePackage, whose itemType is a public field TikTok never renames. */
    public static final class Package {
        public final String itemType;
        Package(String itemType) { this.itemType = itemType; }
    }

    public static final class Action {
        private final String originalKey;
        Action(String key) { originalKey = key; }
        public String key() {
            String owned = OwnedShareActions.key(this);
            return owned == null ? originalKey : owned;
        }
    }

    private Activity activity;
    private int constructed;
    private int recordTaps;
    private int autoTaps;
    private int unseenTaps;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        SettingsStatus.liveControlsEnabled = true;
        Settings.RECORD_LIVE.save(true);
        Settings.LIVE_AUTO_TAP.save(true);
        Settings.LIVE_BUTTONS_IN_MENU.save(true);
        ShareLiveButtons.nativeForTests = from -> {
            assertSame(activity, from);
            constructed++;
            return new Action("report");
        };
        ShareLiveButtons.recordTapForTests = () -> recordTaps++;
        ShareLiveButtons.autoTapForTests = () -> autoTaps++;
        ShareLiveButtons.unseenTapForTests = () -> unseenTaps++;
    }

    @After public void tearDown() {
        ShareLiveButtons.nativeForTests = null;
        ShareLiveButtons.recordTapForTests = null;
        ShareLiveButtons.autoTapForTests = null;
        ShareLiveButtons.unseenTapForTests = null;
        ((List<?>) ReflectionHelpers.getStaticField(ShareLiveButtons.class, "BUILT")).clear();
        LiveRecordChip.holdForTests(null, false);
        Settings.RECORD_LIVE.resetToDefault();
        Settings.LIVE_AUTO_TAP.resetToDefault();
        Settings.LIVE_BUTTONS_IN_MENU.resetToDefault();
        SettingsStatus.liveControlsEnabled = false;
        PausedProcess.set(false);
        activity.finish();
    }

    private static List<Object> row() {
        return new ArrayList<>(Arrays.asList(new Action("report"), new Action("save")));
    }

    private static String key(Object action) {
        return ((Action) action).key();
    }

    @Test public void bothButtonsJoinALiveMenuOnceAfterTikToksOwnRows() {
        Package live = new Package("live");
        List<Object> nativeRow = row();
        List<?> finished = ShareLiveButtons.actions(live, nativeRow);
        assertEquals(2, nativeRow.size());
        assertEquals(4, finished.size());
        assertEquals("report", key(finished.get(0)));
        assertEquals(ShareLiveButtons.RECORD_KEY, key(finished.get(2)));
        assertEquals(ShareLiveButtons.AUTO_TAP_KEY, key(finished.get(3)));
        assertEquals("Record", OwnedShareActions.label(finished.get(2)));
        assertEquals("Auto tap", OwnedShareActions.label(finished.get(3)));
        assertTrue(OwnedShareActions.owns(finished.get(3)));
        assertEquals("TikTok frames the row like its other ones", "report",
                OwnedShareActions.framedKey(finished.get(2), ShareLiveButtons.RECORD_KEY));

        List<?> again = ShareLiveButtons.actions(live, new ArrayList<>(finished));
        assertEquals(4, again.size());
        assertSame(finished.get(2), again.get(2));
        assertSame(finished.get(3), again.get(3));
        assertEquals(2, constructed);
    }

    @Test public void theMenuStaysAsItIsUntilTheSwitchIsOn() {
        assertFalse(Settings.LIVE_BUTTONS_IN_MENU.defaultValue);
        Settings.LIVE_BUTTONS_IN_MENU.save(false);
        List<Object> nativeRow = row();
        assertSame(nativeRow, ShareLiveButtons.actions(new Package("live"), nativeRow));
        assertEquals(0, constructed);
    }

    @Test public void eachRowNeedsItsOwnButtonSwitchedOn() {
        Settings.RECORD_LIVE.save(false);
        List<?> onlyTap = ShareLiveButtons.actions(new Package("live"), row());
        assertEquals(3, onlyTap.size());
        assertEquals(ShareLiveButtons.AUTO_TAP_KEY, key(onlyTap.get(2)));

        Settings.RECORD_LIVE.save(true);
        Settings.LIVE_AUTO_TAP.save(false);
        List<?> onlyRecord = ShareLiveButtons.actions(new Package("live"), row());
        assertEquals(3, onlyRecord.size());
        assertEquals(ShareLiveButtons.RECORD_KEY, key(onlyRecord.get(2)));
    }

    @Test public void staysOutOfVideoAndProfileMenusAndWhilePaused() {
        for (String type : new String[] {"aweme", "user"}) {
            List<Object> nativeRow = row();
            assertSame(nativeRow, ShareLiveButtons.actions(new Package(type), nativeRow));
        }
        PausedProcess.set(true);
        List<Object> nativeRow = row();
        assertSame(nativeRow, ShareLiveButtons.actions(new Package("live"), nativeRow));
        assertEquals(0, constructed);
    }

    @Test public void aTapReachesTheRightButtonOnlyFromTheMenuItWasBuiltFor() {
        Package live = new Package("live");
        List<?> finished = ShareLiveButtons.actions(live, row());
        assertTrue(OwnedShareActions.click(finished.get(2), activity, live));
        assertEquals(1, recordTaps);
        assertEquals(0, autoTaps);
        assertTrue(OwnedShareActions.click(finished.get(3), activity, live));
        assertEquals(1, autoTaps);

        assertTrue("an owned row is consumed on another menu too",
                OwnedShareActions.click(finished.get(2), activity, new Package("live")));
        assertEquals(1, recordTaps);
        assertFalse(OwnedShareActions.click(new Action("report"), activity, live));
    }

    @Test public void turningTheSwitchOffTakesTheRowsOutAndDeadensThem() {
        Package live = new Package("live");
        List<?> finished = ShareLiveButtons.actions(live, row());
        Object record = finished.get(2);
        Settings.LIVE_BUTTONS_IN_MENU.save(false);
        assertTrue("a row still on screen does nothing", OwnedShareActions.click(record, activity, live));
        assertEquals(0, recordTaps);

        List<?> after = ShareLiveButtons.actions(live, new ArrayList<>(finished));
        assertEquals(2, after.size());
        assertEquals("report", key(after.get(0)));

        Settings.LIVE_BUTTONS_IN_MENU.save(true);
        assertTrue(OwnedShareActions.click(record, activity, live));
        assertEquals("a retired row stays retired", 0, recordTaps);
    }

    @Test public void aLongPressMenuRowCopiedFromAButtonShowsItsOwnNameAndGlyph() {
        List<?> finished = ShareLiveButtons.actions(new Package("live"), row());
        Object recordRow = new Object();
        Object tapRow = new Object();
        OwnedShareActions.menuRow(finished.get(2), recordRow);
        OwnedShareActions.menuRow(finished.get(3), tapRow);

        ColorDrawable flag = new ColorDrawable(Color.RED);
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(flag);
        TextView text = new TextView(activity);
        text.setText("Report");
        text.setTextColor(Color.WHITE);
        OwnedShareActions.bindMenuRow(recordRow, icon, text);
        assertEquals("Record", text.getText().toString());
        assertTrue(icon.getDrawable() instanceof LiveButtonGlyphDrawable);

        TextView tapText = new TextView(activity);
        OwnedShareActions.bindMenuRow(tapRow, new ImageView(activity), tapText);
        assertEquals("Auto tap", tapText.getText().toString());
    }

    @Test public void tikToksOwnMenuRowsKeepWhatTikTokDrew() {
        List<?> finished = ShareLiveButtons.actions(new Package("live"), row());
        Object reportRow = new Object();
        OwnedShareActions.menuRow(finished.get(0), reportRow);
        OwnedShareActions.menuRow(finished.get(2), null);
        for (Object model : new Object[] {reportRow, new Object(), null}) {
            ColorDrawable flag = new ColorDrawable(Color.RED);
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(flag);
            TextView text = new TextView(activity);
            text.setText("Report");
            OwnedShareActions.bindMenuRow(model, icon, text);
            assertEquals("Report", text.getText().toString());
            assertSame(flag, icon.getDrawable());
        }
    }

    /** D#359: with Watch LIVEs unseen on, the eye is a row too, and a tap does what the eye does. */
    @Test public void theEyeJoinsALiveMenuWhileWatchLivesUnseenIsOn() {
        boolean installed = SettingsStatus.ghostModeEnabled;
        SettingsStatus.ghostModeEnabled = true;
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_LIVE_VIEWS.save(true);
        try {
            Package live = new Package("live");
            List<?> finished = ShareLiveButtons.actions(live, row());
            assertEquals(5, finished.size());
            assertEquals(ShareLiveButtons.RECORD_KEY, key(finished.get(2)));
            assertEquals(ShareLiveButtons.AUTO_TAP_KEY, key(finished.get(3)));
            assertEquals(ShareLiveButtons.UNSEEN_KEY, key(finished.get(4)));
            assertEquals("Let the host see you", OwnedShareActions.label(finished.get(4)));
            assertTrue(OwnedShareActions.click(finished.get(4), activity, live));
            assertEquals(1, unseenTaps);
            assertEquals(0, recordTaps);

            Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
            List<?> without = ShareLiveButtons.actions(live, new ArrayList<>(finished));
            assertEquals(4, without.size());
            assertTrue("the old eye row is dead", OwnedShareActions.click(finished.get(4), activity, live));
            assertEquals(1, unseenTaps);
        } finally {
            SettingsStatus.ghostModeEnabled = installed;
            Settings.GHOST_MODE.resetToDefault();
            Settings.GHOST_HIDE_LIVE_VIEWS.resetToDefault();
        }
    }

    @Test public void theRecordRowSaysASaveIsRunning() {
        List<?> finished = ShareLiveButtons.actions(new Package("live"), row());
        LiveRecordChip.holdForTests(null, true);
        assertEquals("Saving the recording", OwnedShareActions.label(finished.get(2)));
    }
}
