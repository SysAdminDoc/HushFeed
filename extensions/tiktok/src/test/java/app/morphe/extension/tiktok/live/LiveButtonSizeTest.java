package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SettingsRegistryRule;
import app.morphe.extension.tiktok.blockauthor.LiveRoomBlock;
import app.morphe.extension.tiktok.ghostmode.LiveUnseenButton;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #359: Size of the LIVE buttons draws Record, Auto tap, Block and the eye smaller, so they stop
 * covering the room's own buttons on a small screen. Normal is the 48 dp they always were.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveButtonSizeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    private static StringSetting size() throws Exception {
        return (StringSetting) Settings.class.getField("LIVE_BUTTON_SIZE").get(null);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = LiveRecordChip.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static int buttonSize() throws Exception {
        return (Integer) call("buttonSizeDp", new Class<?>[0]);
    }

    private static int scaled(int dp) throws Exception {
        return (Integer) call("scaledDp", new Class<?>[]{int.class}, dp);
    }

    @After public void reset() throws Exception {
        size().resetToDefault();
    }

    @Test public void normalIsTheSizeTheButtonsAlwaysHad() throws Exception {
        assertEquals("48", size().defaultValue);
        assertEquals(48, buttonSize());
        assertEquals(48, scaled(48));
        assertEquals(14, scaled(14));
    }

    @Test public void smallAndSmallestShrinkEverythingInProportion() throws Exception {
        size().save("40");
        assertEquals(40, buttonSize());
        assertEquals(40, scaled(48));
        size().save("32");
        assertEquals(32, buttonSize());
        assertEquals(32, scaled(48));
        assertEquals(9, scaled(14));
        assertEquals(16, scaled(24));
        assertEquals(11f, (Float) call("chipTextSp", new Class<?>[0]), 0.001f);
    }

    @Test public void aSizeTheListDoesNotOfferIsNormal() throws Exception {
        for (String odd : new String[]{"tiny", "", "20", "31", "49", "56", "400"}) {
            size().save(odd);
            assertEquals("\"" + odd + "\" should read as Normal", 48, buttonSize());
        }
        size().save(" 40 ");
        assertEquals(40, buttonSize());
    }

    /** The Record and Auto tap chips: their height and the rule that redraws one after a change. */
    @Test public void theChipsTakeThePickedSizeAndRedrawWhenItChanges() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        float density = activity.getResources().getDisplayMetrics().density;
        size().save("32");
        TextView chip = new TextView(activity);
        call("styleChip", new Class<?>[]{TextView.class}, chip);
        assertEquals(Math.round(32 * density), chip.getMinHeight());
        assertEquals(Math.round(32 * density), chip.getMinWidth());
        assertEquals(Math.round(9 * density), chip.getPaddingLeft());
        assertFalse((Boolean) call("drawnAtOtherSize", new Class<?>[]{TextView.class}, chip));

        size().save("48");
        assertTrue("a chip drawn at 32 dp is redrawn once Normal is picked",
                (Boolean) call("drawnAtOtherSize", new Class<?>[]{TextView.class}, chip));
    }

    /** Block and the eye are squares the picked size on a side. */
    @Test public void blockAndTheEyeTakeThePickedSize() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        size().save("32");
        for (Class<?> owner : new Class<?>[]{LiveRoomBlock.class, LiveUnseenButton.class}) {
            Method show = owner.getDeclaredMethod("showButton", Activity.class);
            show.setAccessible(true);
            View button = (View) show.invoke(null, activity);
            assertEquals(owner.getSimpleName(), Math.round(32 * density), button.getLayoutParams().width);
            assertEquals(owner.getSimpleName(), Math.round(32 * density), button.getLayoutParams().height);
            ((android.view.ViewGroup) button.getParent()).removeView(button);
        }
    }
}
