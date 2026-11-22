package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertArrayEquals;
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
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private static final int BIO_GRAY = 0x75000000;
    private static final int LINK = 0xFF2B5DB9;
    private static final int CHIP_PINK = 0xFFFFF1F3;
    private static final int FAINT_BLACK = 0x0D000000;

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

    /**
     * The sheet as 47.9.3 draws it (S22, 2026-11-22): in its own window, a clear rounded shape
     * holds a frame whose first child is the surface, a plain view with TikTok's two-stop
     * gradient, and whose second is the card's own view, so the card sits beside its surface.
     */
    private static final class GradientSheet {
        final FrameLayout window;
        final View touchOutside;
        final View surface;
        final FrameLayout card;
        final TextView name;
        final TextView chip;
        /** The bio: TikTok's text with its 46% black span over it all and a link's color. */
        final TextView bio;
        /** A chip drawn as a fill beside its label, both under one frame (the Community chip). */
        final View chipFill;
        final TextView chipLabel;
        /** A round button that is 5% black over the card, with its icon. */
        final FrameLayout button;
        final ImageView icon;
        /** A faint line with nothing on it. */
        final View divider;

        GradientSheet(Context context, int[] stops) {
            window = new FrameLayout(context);
            touchOutside = new View(context);
            window.addView(touchOutside, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout rounded = new FrameLayout(context);
            GradientDrawable clear = new GradientDrawable();
            clear.setColor(0);
            clear.setCornerRadius(24);
            rounded.setBackground(clear);
            FrameLayout frame = new FrameLayout(context);
            surface = new View(context);
            surface.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, stops));
            card = new FrameLayout(context);
            LinearLayout rows = new LinearLayout(context);
            rows.setOrientation(LinearLayout.VERTICAL);
            name = Card.text(context);
            chip = Card.text(context);
            chip.setTextColor(CHIP_TEXT);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(CHIP);
            chip.setBackground(shape);
            bio = Card.text(context);
            bio.setText(tikTokBio());
            FrameLayout chipFrame = new FrameLayout(context);
            chipFill = new View(context);
            GradientDrawable pink = new GradientDrawable();
            pink.setColor(CHIP_PINK);
            chipFill.setBackground(pink);
            FrameLayout labelFrame = new FrameLayout(context);
            chipLabel = Card.text(context);
            labelFrame.addView(chipLabel);
            chipFrame.addView(chipFill, new FrameLayout.LayoutParams(-1, -1));
            chipFrame.addView(labelFrame, new FrameLayout.LayoutParams(-1, -1));
            button = new FrameLayout(context);
            button.setBackground(new ColorDrawable(FAINT_BLACK));
            icon = new ImageView(context);
            button.addView(icon, new FrameLayout.LayoutParams(20, 20));
            divider = new View(context);
            divider.setBackground(new ColorDrawable(FAINT_BLACK));
            rows.addView(name, new LinearLayout.LayoutParams(-1, 40));
            rows.addView(chip, new LinearLayout.LayoutParams(100, 40));
            rows.addView(bio, new LinearLayout.LayoutParams(-1, 40));
            rows.addView(chipFrame, new LinearLayout.LayoutParams(140, 50));
            rows.addView(button, new LinearLayout.LayoutParams(50, 50));
            rows.addView(divider, new LinearLayout.LayoutParams(300, 2));
            card.addView(rows, new FrameLayout.LayoutParams(-1, -1));
            frame.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            frame.addView(card, new FrameLayout.LayoutParams(-1, -1));
            rounded.addView(frame, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, 400);
            bottom.topMargin = 400;
            window.addView(rounded, bottom);
            window.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            window.layout(0, 0, 400, 800);
        }

        GradientDrawable surfaceFill() {
            return (GradientDrawable) surface.getBackground();
        }

        /** As HeaderCell builds it on 47.9.3, a fresh builder each time the bio view measures. */
        static CharSequence tikTokBio() {
            SpannableStringBuilder text = new SpannableStringBuilder("Watch us on YouTube @hotr");
            text.setSpan(new ForegroundColorSpan(BIO_GRAY), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(LINK), 20, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return text;
        }
    }

    private static Set<Integer> spanColors(TextView view) {
        Spanned text = (Spanned) view.getText();
        Set<Integer> colors = new HashSet<>();
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            colors.add(span.getForegroundColor());
        }
        return colors;
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

    @Test public void aCardBesideTikToksGradientSurfaceTakesTheColors() {
        GradientSheet sheet = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        Sheet panel = new Sheet();
        panel.view = sheet.card;
        Settings.LIVE_CARD_BACKGROUND.save("black");
        Settings.LIVE_CARD_TEXT.save("yellow");
        assertTrue(LiveProfileCard.colorCard(panel));
        // Repainted as a gradient of one color, so a shader TikTok already drew from goes too.
        assertArrayEquals("the surface beside the card", new int[] {BLACK, BLACK}, sheet.surfaceFill().getColors());
        assertEquals(YELLOW, sheet.name.getCurrentTextColor());
        assertEquals("a chip keeps its fill", CHIP, ((GradientDrawable) sheet.chip.getBackground()).getColor().getDefaultColor());
        assertEquals("and its text", CHIP_TEXT, sheet.chip.getCurrentTextColor());
        assertNull("the clear rounded shape stays clear", ((GradientDrawable) ((View) sheet.surface.getParent().getParent())
                .getBackground()).getColors());
        assertNull("nothing outside the sheet", sheet.touchOutside.getBackground());

        // Starting from the sheet's window finds the same surface.
        LiveProfileCard.resetForTests();
        GradientSheet fromWindow = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        LiveProfileCard.paint(fromWindow.window);
        assertArrayEquals(new int[] {BLACK, BLACK}, fromWindow.surfaceFill().getColors());
        assertEquals(YELLOW, fromWindow.name.getCurrentTextColor());
    }

    @Test public void aTextColorAloneOnTheGradientPicksItsOwnContrast() {
        GradientSheet sheet = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        Settings.LIVE_CARD_TEXT.save("yellow");
        LiveProfileCard.paint(sheet.card);
        assertArrayEquals("TikTok's gradient stays", new int[] {0xFFF8F8F8, WHITE}, sheet.surfaceFill().getColors());
        assertEquals(YELLOW, sheet.name.getCurrentTextColor());

        LiveProfileCard.resetForTests();
        GradientSheet dark = new GradientSheet(context, new int[] {0xFF101010, 0xFF1E1E1E});
        Settings.LIVE_CARD_TEXT.save("off");
        Settings.LIVE_CARD_BACKGROUND.save("light_gray");
        LiveProfileCard.paint(dark.card);
        assertArrayEquals(new int[] {0xFFE6E6E6, 0xFFE6E6E6}, dark.surfaceFill().getColors());
        assertEquals("Automatic picks black on light gray", BLACK, dark.name.getCurrentTextColor());
    }

    @Test public void aGradientThatShowsIsNotTheSurface() {
        int[] banner = {0xFFFE2C55, 0xFF25F4EE};
        GradientSheet sheet = new GradientSheet(context, banner);
        Settings.LIVE_CARD_BACKGROUND.save("black");
        LiveProfileCard.paint(sheet.card);
        assertArrayEquals("a two-color banner is a picture of its own", banner, sheet.surfaceFill().getColors());
        assertEquals(TIKTOK_TEXT, sheet.name.getCurrentTextColor());

        assertEquals(Integer.valueOf(0xFFFBFBFB), LiveProfileCard.solidColor(
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFFF8F8F8, WHITE})));
        assertNull(LiveProfileCard.solidColor(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, banner)));
    }

    /** On the S22 the bio went black on black: its gray is a span, which beats the view's color. */
    @Test public void theBiosGraySpanTakesTheTextColorAtItsOwnStrength() {
        GradientSheet sheet = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        Settings.LIVE_CARD_BACKGROUND.save("black");
        LiveProfileCard.paint(sheet.window);
        Set<Integer> expected = new HashSet<>();
        expected.add(0x75FFFFFF);
        expected.add(LINK);
        assertEquals("the gray takes white at 46%, the link keeps its color", expected, spanColors(sheet.bio));

        // The bio view sets TikTok's text again each time it measures. It now comes in as one
        // that changes in place, and the next layout inks it there.
        sheet.bio.setText(GradientSheet.tikTokBio());
        CharSequence again = sheet.bio.getText();
        assertTrue(again instanceof Spannable);
        LiveProfileCard.paint(sheet.window);
        assertSame("inked in place, not set again", again, sheet.bio.getText());
        assertEquals(expected, spanColors(sheet.bio));

        LiveProfileCard.resetForTests();
        GradientSheet yellow = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        Settings.LIVE_CARD_TEXT.save("yellow");
        LiveProfileCard.paint(yellow.window);
        assertTrue(spanColors(yellow.bio).contains(0x75FFD60A));
    }

    /** The Community chip's label sits over its pink fill, the two side by side under one frame. */
    @Test public void aChipsLabelDrawnOverItsFillKeepsItsColor() {
        GradientSheet sheet = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        Settings.LIVE_CARD_BACKGROUND.save("black");
        LiveProfileCard.paint(sheet.window);
        assertEquals(CHIP_PINK, ((GradientDrawable) sheet.chipFill.getBackground()).getColor().getDefaultColor());
        assertEquals(TIKTOK_TEXT, sheet.chipLabel.getCurrentTextColor());
        assertEquals("text straight on the card still changes", WHITE, sheet.name.getCurrentTextColor());
    }

    /** TikTok's round buttons are 5% black, gray on its white card and gone on a black one. */
    @Test public void aFaintRoundButtonKeepsTheGrayItShowedOnTikToksCard() {
        GradientSheet sheet = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        Settings.LIVE_CARD_BACKGROUND.save("black");
        LiveProfileCard.paint(sheet.window);
        assertEquals("5% black over the sheet's #fbfbfb", 0xFFEEEEEE, fill(sheet.button));
        assertEquals("a faint line with nothing on it stays as it was", FAINT_BLACK, fill(sheet.divider));
        LiveProfileCard.paint(sheet.window);
        assertEquals("the next layout leaves it", 0xFFEEEEEE, fill(sheet.button));
        assertEquals(0xFFF2F2F2, LiveProfileCard.over(FAINT_BLACK, WHITE));

        LiveProfileCard.resetForTests();
        GradientSheet textOnly = new GradientSheet(context, new int[] {0xFFF8F8F8, WHITE});
        Settings.LIVE_CARD_BACKGROUND.save("off");
        Settings.LIVE_CARD_TEXT.save("yellow");
        LiveProfileCard.paint(textOnly.window);
        assertEquals("TikTok's card keeps its tint", FAINT_BLACK, fill(textOnly.button));
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
