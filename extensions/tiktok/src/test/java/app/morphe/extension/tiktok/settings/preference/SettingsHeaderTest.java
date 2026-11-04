package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.BuildDetailsTest;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The main page opens on Hushfeed's icon, its name and the version line, centred, with nothing on
 * it to tap. It's there on the main page only, and a search keeps it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsHeaderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private ActivityController<Page> owner;

    public static class Page extends Activity {
        @Override public void onCreate(Bundle state) {
            boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
            Utils.setIsDarkModeEnabled(dark);
            super.onCreate(state);
        }
    }

    @Before public void installControls() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void restore() throws Exception {
        if (owner != null) owner.close();
        for (var entry : statuses.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
        Utils.setIsDarkModeEnabled(true);
    }

    private TikTokPreferenceFragment openHome() {
        owner = Robolectric.buildActivity(Page.class).setup().visible();
        Activity activity = owner.get();
        Utils.setContext(activity);
        TikTokPreferenceFragment home = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit();
        activity.getFragmentManager().executePendingTransactions();
        idle();
        layout(home);
        return home;
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static void layout(TikTokPreferenceFragment page) {
        View root = page.getView();
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 640);
        idle();
    }

    private static ListView list(TikTokPreferenceFragment page) {
        return page.getView().findViewById(android.R.id.list);
    }

    private static int position(ListView list, String key) {
        for (int position = 0; position < list.getAdapter().getCount(); position++) {
            Object item = list.getAdapter().getItem(position);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) return position;
        }
        return -1;
    }

    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    @Test public void theHeaderLeadsTheMainPageAboveSearchAndTakesNoTap() {
        TikTokPreferenceFragment home = openHome();
        ListView list = list(home);
        Preference header = home.findPreference(SettingsHeader.KEY);
        assertTrue("the main page has no header", header instanceof SettingsHeader);
        assertEquals("the header isn't the first row", 0, position(list, SettingsHeader.KEY));
        int search = position(list, "action_search_settings");
        assertTrue("search isn't right under the header", search > 0);
        for (int position = 1; position < search; position++) {
            assertFalse("a tappable row sits between the header and search", list.getAdapter().isEnabled(position));
        }
        assertFalse(header.isSelectable());
        assertFalse(header.isPersistent());
        assertFalse(list.getAdapter().isEnabled(0));
        View column = (View) list.findViewWithTag(SettingsHeader.ICON).getParent();
        assertFalse(column.isClickable());
        assertFalse(column.isLongClickable());
        assertFalse(column.isFocusable());
    }

    @Test public void darkHeaderMatchesTheSpec() { assertHeaderMatchesTheSpec(ScreenColors.DARK); }

    @Test @Config(qualifiers = "w360dp-h640dp-notnight-mdpi")
    public void lightHeaderMatchesTheSpec() { assertHeaderMatchesTheSpec(ScreenColors.LIGHT); }

    private void assertHeaderMatchesTheSpec(ScreenColors colors) {
        TikTokPreferenceFragment home = openHome();
        assertSame(colors, ScreenColors.current());
        ListView list = list(home);
        ImageView icon = list.findViewWithTag(SettingsHeader.ICON);
        assertNotNull("the header isn't on screen at the top", icon);
        LinearLayout column = (LinearLayout) icon.getParent();
        assertEquals(LinearLayout.VERTICAL, column.getOrientation());
        assertEquals(Gravity.CENTER_HORIZONTAL, column.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
        assertEquals(dp(column, 24), column.getPaddingLeft());
        assertEquals(dp(column, 28), column.getPaddingTop());
        assertEquals(dp(column, 24), column.getPaddingRight());
        assertEquals(dp(column, 20), column.getPaddingBottom());
        assertEquals(3, column.getChildCount());

        assertSame(icon, column.getChildAt(0));
        assertEquals(dp(icon, 64), icon.getWidth());
        assertEquals(dp(icon, 64), icon.getHeight());
        assertEquals(ImageView.ScaleType.FIT_CENTER, icon.getScaleType());
        assertEquals("the icon is decoration", View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertNotNull(icon.getDrawable());
        int iconMiddle = icon.getLeft() + icon.getWidth() / 2;
        assertEquals("the icon isn't centred", column.getWidth() / 2, iconMiddle, 1);

        TextView name = (TextView) column.getChildAt(1);
        assertSame(name, list.findViewWithTag(SettingsHeader.NAME));
        assertEquals("Hushfeed", name.getText().toString());
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32,
                name.getResources().getDisplayMetrics()), name.getTextSize(), 0.01f);
        assertEquals(Typeface.create("sans-serif-medium", Typeface.NORMAL), name.getTypeface());
        assertEquals(Gravity.CENTER, name.getGravity());
        assertTrue("the name isn't a heading", name.isAccessibilityHeading());
        assertTrue("the name doesn't wear the gradient", ShimmerTitle.wears(name));
        assertEquals("the gradient lost its full strength", Color.WHITE, name.getCurrentTextColor());
        assertLettersReadOn(name, colors == ScreenColors.DARK);
        assertEquals(dp(name, 12), ((LinearLayout.LayoutParams) name.getLayoutParams()).topMargin);
        assertEquals(dp(name, 12), name.getTop() - icon.getBottom());
        assertEquals("the name isn't centred", column.getWidth() / 2, name.getLeft() + name.getWidth() / 2, 1);
        assertNotNull(name.getLayout());
        assertEquals("the name was cut", 1, name.getLineCount());

        TextView version = (TextView) column.getChildAt(2);
        assertSame(version, list.findViewWithTag(SettingsHeader.VERSION));
        assertEquals(dp(version, 4), ((LinearLayout.LayoutParams) version.getLayoutParams()).topMargin);
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                version.getResources().getDisplayMetrics()), version.getTextSize(), 0.01f);
        assertEquals(colors.summary, version.getCurrentTextColor());
        assertEquals(Gravity.CENTER, version.getGravity());
        // A test build has no bundle version, so there's nothing to name and no gap is left for it.
        assertEquals("", Utils.getPatchesReleaseVersion());
        assertEquals(View.GONE, version.getVisibility());
        assertEquals(name.getBottom() + dp(column, 20), column.getHeight());
    }

    /**
     * The name, drawn between sweeps, in the gradient its ground calls for: light letters on the
     * black page, dark ones on the light page.
     */
    private static void assertLettersReadOn(TextView name, boolean dark) {
        ShimmerTitle.frameFor(name, ShimmerTitle.DRIFT_MS);
        Bitmap drawn = Bitmap.createBitmap(name.getWidth(), name.getHeight(), Bitmap.Config.ARGB_8888);
        name.draw(new Canvas(drawn));
        int letters = 0;
        for (int x = 0; x < drawn.getWidth(); x++) {
            for (int y = 0; y < drawn.getHeight(); y++) {
                int pixel = drawn.getPixel(x, y);
                if (Color.alpha(pixel) < 250) continue;
                letters++;
                float luminance = Color.luminance(pixel);
                assertTrue("a letter in the other theme's gradient: " + Integer.toHexString(pixel),
                        dark ? luminance > 0.25f : luminance < 0.2f);
            }
        }
        assertTrue("the name drew no letters", letters > 0);
    }

    @Test public void theVersionLineNamesTheBundleAndTikTokEachKeptWhole() {
        TikTokPreferenceFragment home = openHome();
        Activity activity = home.getActivity();
        CharSequence line = SettingsHeader.versionLine(activity, "0.80.0", "47.6.3");
        assertEquals("Version " + L10n.isolate("0.80.0") + " for TikTok " + L10n.isolate("47.6.3"), line.toString());
        assertEquals(0x2068, L10n.isolate("0.80.0").charAt(0));
        assertEquals(0x2069, L10n.isolate("0.80.0").charAt(L10n.isolate("0.80.0").length() - 1));
        for (String unreadable : new String[]{null, "", "Unknown"}) {
            assertEquals("Version " + L10n.isolate("0.80.0"),
                    SettingsHeader.versionLine(activity, "0.80.0", unreadable).toString());
        }
        assertEquals("", SettingsHeader.versionLine(activity, "", "47.6.3").toString());
        assertEquals("", SettingsHeader.versionLine(activity, null, "47.6.3").toString());
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h640dp-night-mdpi")
    public void theVersionLineIsTranslated() {
        TikTokPreferenceFragment home = openHome();
        Activity activity = home.getActivity();
        String line = SettingsHeader.versionLine(activity, "0.80.0", "47.6.3").toString();
        assertNotEquals("Version " + L10n.isolate("0.80.0") + " for TikTok " + L10n.isolate("47.6.3"), line);
        assertTrue(line, line.contains(L10n.isolate("0.80.0")));
        assertTrue(line, line.contains(L10n.isolate("47.6.3")));
        String alone = SettingsHeader.versionLine(activity, "0.80.0", "Unknown").toString();
        assertTrue(alone, alone.contains(L10n.isolate("0.80.0")));
        assertFalse(alone, alone.contains("TikTok"));
    }

    @Test public void theLauncherIconShowsOnlyWhenTheBuildGaveTikTokHushfeedsIcon() throws Exception {
        TikTokPreferenceFragment home = openHome();
        Activity activity = home.getActivity();
        ScreenColors colors = ScreenColors.current();
        Bitmap mark = ((BitmapDrawable) SettingsIcons.icon(activity, SettingsIcons.PATCHED, 0)).getBitmap();
        String record = BuildDetailsTest.metadata();
        String listed = "resource_patches=changeAppName,customLauncherIcon";

        Drawable launcher = SettingsHeader.icon(activity, colors, record);
        assertNotNull(launcher);
        assertFalse("the patched mark stood in for the launcher icon",
                launcher instanceof BitmapDrawable && ((BitmapDrawable) launcher).getBitmap() == mark);

        for (String without : new String[]{record.replace(listed, "resource_patches=none"),
                record.replace(listed, "resource_patches=changeAppName"), record.replace(listed + "\n", ""), ""}) {
            Drawable drawn = SettingsHeader.icon(activity, colors, without);
            assertTrue("a build without the icon patch showed an icon it may not carry",
                    drawn instanceof BitmapDrawable && ((BitmapDrawable) drawn).getBitmap() == mark);
            assertEquals("the mark isn't in the heading colour", colors.heading | 0xFF000000, inkOf(drawn));
        }
    }

    @Test public void theHeaderStaysWhileSearching() throws Exception {
        TikTokPreferenceFragment home = openHome();
        Preference search = home.findPreference("action_search_settings");
        assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
        idle();
        Field input = TikTokPreferenceFragment.class.getDeclaredField("searchInput");
        input.setAccessible(true);
        ((SettingsSearchInputPreference) input.get(home)).setQuery("speed");
        var update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(home, "speed");
        idle();
        layout(home);
        ListView list = list(home);
        assertTrue(home.findPreference(SettingsHeader.KEY) instanceof SettingsHeader);
        assertEquals(0, position(list, SettingsHeader.KEY));
        assertTrue(position(list, "action_search_settings") > 0);
        assertTrue(home.closeInlineSearch());
        idle();
        assertEquals(0, position(list(home), SettingsHeader.KEY));
    }

    /** The colour of the drawable's most opaque pixel, drawn at 64 dp in an enabled view. */
    private static int inkOf(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        drawable.setState(new int[]{android.R.attr.state_enabled});
        drawable.setBounds(0, 0, 64, 64);
        drawable.draw(new Canvas(bitmap));
        int best = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) > Color.alpha(best)) best = pixel;
            }
        }
        bitmap.recycle();
        assertEquals("nothing was drawn", 255, Color.alpha(best));
        return best;
    }
}
