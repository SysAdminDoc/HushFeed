package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.android.livesdk.profile.ui.LiveProfileSheet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

/** The LIVE profile card's colors and hidden rows (#487). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveProfileCardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int YELLOW = 0xFFFFD60A;
    private static final int CHIP = 0xFFFE2C55;
    private static final int CHIP_TEXT = 0xFF161823;
    private static final int TIKTOK_TEXT = 0xFF161823;

    /** TikTok's card module enum keeps its constant names; these are the ones it has. */
    enum Module { HEADER, LIVE_EVENT, LIVE_PRODUCTS, ECOM, SHOP_EXPERIENCE_SCORE, GROUP_CHAT,
        BULLETIN_BOARD, GIFT_GALLERY, FEST_HONORS, FANS_CLUB }

    /** The card's sheet as TikTok extends it. */
    public static final class Sheet extends LiveProfileSheet {
    }

    private boolean liveControls;
    private Context context;

    /** A laid-out card: a full-width white surface under a layer of the same white, a narrow white box, a chip. */
    private static final class Card {
        final FrameLayout root;
        final LinearLayout surface;
        final TextView name;
        final FrameLayout layer;
        final TextView onLayer;
        final FrameLayout box;
        final TextView inBox;
        final TextView chip;

        Card(Context context) {
            root = new FrameLayout(context);
            surface = new LinearLayout(context);
            surface.setOrientation(LinearLayout.VERTICAL);
            surface.setBackground(new ColorDrawable(WHITE));
            name = text(context);
            layer = new FrameLayout(context);
            layer.setBackground(new ColorDrawable(WHITE));
            onLayer = text(context);
            layer.addView(onLayer);
            box = new FrameLayout(context);
            box.setBackground(new ColorDrawable(WHITE));
            inBox = text(context);
            box.addView(inBox);
            chip = text(context);
            chip.setTextColor(CHIP_TEXT);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(CHIP);
            chip.setBackground(shape);
            surface.addView(name, new LinearLayout.LayoutParams(-1, 40));
            surface.addView(layer, new LinearLayout.LayoutParams(-1, 40));
            surface.addView(box, new LinearLayout.LayoutParams(200, 40));
            surface.addView(chip, new LinearLayout.LayoutParams(100, 40));
            root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            layout();
        }

        void layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 400, 800);
        }

        private static TextView text(Context context) {
            TextView view = new TextView(context);
            view.setText("text");
            view.setTextColor(TIKTOK_TEXT);
            return view;
        }
    }

    @Before public void setUp() {
        liveControls = SettingsStatus.liveControlsEnabled;
        SettingsStatus.liveControlsEnabled = true;
        context = RuntimeEnvironment.getApplication();
        LiveProfileCard.resetForTests();
    }

    @After public void tearDown() {
        LiveProfileCard.resetForTests();
        Settings.LIVE_CARD_BACKGROUND.resetToDefault();
        Settings.LIVE_CARD_TEXT.resetToDefault();
        Settings.DARK_LIVE_PANELS.resetToDefault();
        for (String row : LiveProfileCard.ROWS) LiveProfileCard.rowSetting(row).resetToDefault();
        SettingsStatus.liveControlsEnabled = liveControls;
    }

    private static int fill(View view) {
        return ((ColorDrawable) view.getBackground()).getColor();
    }

    @Test public void everyOfferedRowHasItsOwnSwitchThatStartsOff() {
        Set<BooleanSetting> seen = new HashSet<>();
        for (String row : LiveProfileCard.ROWS) {
            BooleanSetting setting = LiveProfileCard.rowSetting(row);
            assertNotNull(row, setting);
            assertFalse(row + " starts off", setting.defaultValue);
            assertTrue(row + " has its own switch", seen.add(setting));
        }
        assertNull(LiveProfileCard.rowSetting("HEADER"));
        assertNull(LiveProfileCard.rowSetting(null));
    }

    @Test public void aHiddenRowIsMatchedByTikToksNameForItsModule() {
        for (Module module : Module.values()) assertFalse(module.name(), LiveProfileCard.hidden(module));
        Settings.HIDE_LIVE_CARD_GROUP_CHAT.save(true);
        Settings.HIDE_LIVE_CARD_SHOP_RATING.save(true);
        assertTrue(LiveProfileCard.hidden(Module.GROUP_CHAT));
        assertTrue(LiveProfileCard.hidden(Module.SHOP_EXPERIENCE_SCORE));
        assertFalse("the shop has its own switch", LiveProfileCard.hidden(Module.ECOM));
        assertFalse("no switch takes off the header", LiveProfileCard.hidden(Module.HEADER));
        assertFalse("a module no switch offers", LiveProfileCard.hidden(Module.FANS_CLUB));
        assertFalse("only the enum constant counts, not its name", LiveProfileCard.hidden("GROUP_CHAT"));
        assertFalse(LiveProfileCard.hidden(null));
    }

    @Test public void keepPassesTikToksAnswerOnWhenItCantTellTheModule() {
        Settings.HIDE_LIVE_CARD_GROUP_CHAT.save(true);
        Object cell = new Object();
        assertFalse("a cell that said no stays off", LiveProfileCard.keep(cell, 0));
        assertTrue("unpatched, no module comes back, so the cell shows", LiveProfileCard.keep(cell, 1));
        assertTrue(LiveProfileCard.keep(null, 1));
        SettingsStatus.liveControlsEnabled = false;
        assertTrue("LIVE controls off", LiveProfileCard.keep(cell, 1));
        assertFalse(LiveProfileCard.keep(cell, 0));
    }

    @Test public void thePickedColorsAndAutomaticText() {
        assertEquals(0, LiveProfileCard.background());
        assertEquals(0, LiveProfileCard.text());
        assertFalse(LiveProfileCard.colorsPicked());
        Settings.LIVE_CARD_BACKGROUND.save("dark_gray");
        assertEquals(0xFF262626, LiveProfileCard.background());
        assertTrue(LiveProfileCard.colorsPicked());
        Settings.LIVE_CARD_BACKGROUND.save("off");
        Settings.LIVE_CARD_TEXT.save("yellow");
        assertEquals(YELLOW, LiveProfileCard.text());
        assertTrue("a text color alone counts", LiveProfileCard.colorsPicked());
        Settings.LIVE_CARD_TEXT.save("something else");
        assertEquals("an unknown choice is TikTok's", 0, LiveProfileCard.text());
        Settings.LIVE_CARD_BACKGROUND.save("black");
        SettingsStatus.liveControlsEnabled = false;
        assertFalse("LIVE controls off", LiveProfileCard.colorsPicked());

        assertEquals(WHITE, LiveProfileCard.contrast(BLACK));
        assertEquals(WHITE, LiveProfileCard.contrast(0xFF262626));
        assertEquals(BLACK, LiveProfileCard.contrast(WHITE));
        assertEquals(BLACK, LiveProfileCard.contrast(0xFFE6E6E6));
    }

    @Test public void theSurfaceAndItsLayersTakeTheBackgroundAndTheirTextStandsOut() {
        Card card = new Card(context);
        Settings.LIVE_CARD_BACKGROUND.save("black");
        LiveProfileCard.paint(card.root);
        assertEquals(BLACK, fill(card.surface));
        assertEquals("a full-width layer of the same fill", BLACK, fill(card.layer));
        assertEquals("Automatic picks white on black", WHITE, card.name.getCurrentTextColor());
        assertEquals(WHITE, card.onLayer.getCurrentTextColor());
        assertEquals("a narrower box is a fill of its own", WHITE, fill(card.box));
        assertEquals(TIKTOK_TEXT, card.inBox.getCurrentTextColor());
        assertEquals("a chip keeps its fill", CHIP, ((GradientDrawable) card.chip.getBackground()).getColor().getDefaultColor());
        assertEquals("and its text", CHIP_TEXT, card.chip.getCurrentTextColor());

        // A later layout, a cell bound as the card's data came in, gets the colors too.
        Settings.LIVE_CARD_TEXT.save("yellow");
        TextView late = new TextView(context);
        late.setTextColor(TIKTOK_TEXT);
        card.layer.addView(late);
        card.layout();
        LiveProfileCard.paint(card.root);
        assertEquals(BLACK, fill(card.surface));
        assertEquals(BLACK, fill(card.layer));
        assertEquals(YELLOW, card.name.getCurrentTextColor());
        assertEquals(YELLOW, late.getCurrentTextColor());
        assertEquals(TIKTOK_TEXT, card.inBox.getCurrentTextColor());
        assertEquals(CHIP_TEXT, card.chip.getCurrentTextColor());
    }

    @Test public void aTextColorAloneKeepsTikToksBackground() {
        Card card = new Card(context);
        Settings.LIVE_CARD_TEXT.save("yellow");
        LiveProfileCard.paint(card.root);
        assertEquals(WHITE, fill(card.surface));
        assertEquals(WHITE, fill(card.layer));
        assertEquals(YELLOW, card.name.getCurrentTextColor());
        assertEquals(YELLOW, card.onLayer.getCurrentTextColor());
        assertEquals(TIKTOK_TEXT, card.inBox.getCurrentTextColor());
    }

    @Test public void nothingPickedLeavesTheCardAsTikTokDrewIt() {
        Card card = new Card(context);
        LiveProfileCard.paint(card.root);
        assertEquals(WHITE, fill(card.surface));
        assertEquals(TIKTOK_TEXT, card.name.getCurrentTextColor());
        Settings.LIVE_CARD_BACKGROUND.save("black");
        SettingsStatus.liveControlsEnabled = false;
        LiveProfileCard.paint(card.root);
        assertEquals("LIVE controls off", WHITE, fill(card.surface));
        assertEquals(TIKTOK_TEXT, card.name.getCurrentTextColor());
    }

    @Test public void theCardIsColoredInPlaceOfDarkLivePanels() {
        Card card = new Card(context);
        Sheet sheet = new Sheet();
        sheet.view = card.root;
        LayoutInflater tiktok = LayoutInflater.from(context);
        assertFalse("nothing picked", LiveProfileCard.colorCard(sheet));
        assertEquals(WHITE, fill(card.surface));

        Settings.LIVE_CARD_BACKGROUND.save("dark_gray");
        assertFalse("another panel", LiveProfileCard.colorCard(new Object()));
        assertFalse(LiveProfileCard.colorCard(null));
        assertSame(tiktok, LivePanelTheme.darkLivePanel(sheet, tiktok));
        assertEquals("colored as it asks for its inflater", 0xFF262626, fill(card.surface));
        assertEquals(WHITE, card.name.getCurrentTextColor());

        TextView late = new TextView(context);
        late.setTextColor(TIKTOK_TEXT);
        card.surface.addView(late);
        card.layout();
        card.root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("each layout gets the colors again", WHITE, late.getCurrentTextColor());
    }

    @Test public void theCardsDarkFixesStepAsideWhileAColorIsPicked() {
        Configuration night = new Configuration(context.getResources().getConfiguration());
        night.uiMode = Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL;
        Context dark = new ContextThemeWrapper(context.createConfigurationContext(night), android.R.style.Theme_Black);
        Settings.DARK_LIVE_PANELS.save(true);
        assertTrue(LivePanelTheme.cardPartsDark(dark));
        Settings.LIVE_CARD_TEXT.save("white");
        assertFalse(LivePanelTheme.cardPartsDark(dark));
    }
}
