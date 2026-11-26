/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The state behind See-through bottom bar (#145). TikTok's bottom tab bar paints its own
 * background, and the room under the picture is kept for it by a spacer in each cell or, on the
 * S22, by a strip the bar's height under the main feed's pager, so the picture stops above the
 * bar. VideoOverlayHider collapses those through its ordinary hide path, lifts each cell's widget
 * layer by what the pager gained, and hands this class the bar: it swaps the bar's background for
 * a clear one and puts the original back, and it judges whether the collapse still pushed the
 * caption or the buttons under the bar.
 */
final class SeeThroughBottomBar {
    /** How far an overlay may reach into the bar, in pixels, before it counts as under it. */
    private static final float SLACK_PX = 2f;

    /** The clear stand-in, told apart from anything TikTok sets by its class. */
    private static final class ClearBackground extends ColorDrawable {
        ClearBackground() {
            super(Color.TRANSPARENT);
        }
    }

    /** The floating pill's fill: dark and see-through, so the video shows behind the tabs. */
    static final int PILL_COLOR = 0x99000000;
    /** The pill's hairline, light enough to find the pill's edge over a dark video. */
    static final int PILL_EDGE_COLOR = 0x40FFFFFF;
    /**
     * The pill where TikTok draws its tabs for a light bar, dark ink as on Home's Community grid
     * (#436): pale, so the dark tabs read, with a dark hairline to find its edge on white.
     */
    static final int PILL_COLOR_ON_LIGHT = 0xD9FFFFFF, PILL_EDGE_ON_LIGHT = 0x1F000000;
    /**
     * The floating pill's proportions, HushGram's glass tab bar's: 5% of the bar's width clear on
     * each side, at least 16dp, and never wider than 420dp on a wide screen.
     */
    static final float PILL_SIDE_SHARE = 0.05f;
    static final int PILL_MIN_SIDE_DP = 16, PILL_MAX_WIDTH_DP = 420;
    /** The clear space under the pill, in dp: a hairline. */
    static final int PILL_GAP_DP = 1;
    /**
     * How far the pill stands above the bar's top, in dp. Within the bar's own 46dp the Home icon's
     * roof and its name touched the lens (S22, 2026-10-27), so the pill reaches over the feed a
     * little and the feed's caption goes up by as much ({@link #riseOver}).
     */
    static final int PILL_RISE_DP = 4;
    /** How much slimmer a compact pill is on each side, and the shortest one is drawn, in dp. */
    static final int COMPACT_INSET_DP = 2, MIN_PILL_DP = 40;
    /** How far the first and last tabs' slots stay in from the pill's ends, in dp. */
    static final int TAB_INNER_DP = 7;

    /** How far the pill keeps from each side of a bar {@code width} pixels wide. */
    static int sidePx(int width, float density) {
        int minimum = Math.round(PILL_MIN_SIDE_DP * density);
        int byShare = Math.round(width * PILL_SIDE_SHARE);
        int byWidth = Math.round((width - PILL_MAX_WIDTH_DP * density) / 2f);
        return Math.max(minimum, Math.max(byShare, byWidth));
    }

    /**
     * The pill over a bar with {@code bar} bounds, put in {@code out}: in from the sides by
     * {@link #sidePx}, {@link #PILL_RISE_DP} above the top, a hairline in from the bottom, and for
     * a {@code compact} pill {@link #COMPACT_INSET_DP} in from both, less if that would leave it
     * shorter than {@link #MIN_PILL_DP}.
     */
    static void pillBounds(Rect bar, float density, boolean compact, Rect out) {
        int side = sidePx(bar.width(), density);
        int gap = Math.round(PILL_GAP_DP * density);
        int rise = Math.round(PILL_RISE_DP * density);
        int inset = 0;
        if (compact) {
            int room = (bar.height() + rise - gap - Math.round(MIN_PILL_DP * density)) / 2;
            inset = Math.max(0, Math.min(Math.round(COMPACT_INSET_DP * density), room));
        }
        out.set(bar.left + side, bar.top - rise + inset, bar.right - side, bar.bottom - gap - inset);
    }

    /** The gap kept between the feed's progress line and the pill's top, in dp. */
    static final int SEEK_BAR_CLEAR_DP = 4;

    /**
     * How far the feed's progress line goes up with the pill {@code rise} px above the bar: past
     * the rise by {@link #SEEK_BAR_CLEAR_DP}, so it doesn't ride on the pill's rim (S22,
     * 2026-10-27). Nothing without a pill.
     */
    static int seekBarLift(int rise, float density) {
        return rise <= 0 ? 0 : rise + Math.round(SEEK_BAR_CLEAR_DP * density);
    }

    /**
     * How far the pill {@code bar}'s background draws reaches above the bar, 0 without a pill.
     * Worked out from the bar's size, so it holds before the pill's first draw.
     */
    static int riseOver(View bar) {
        if (bar == null || !(bar.getBackground() instanceof FloatingBackground) || bar.getHeight() <= 0) return 0;
        Rect pill = new Rect();
        pillBounds(new Rect(0, 0, bar.getWidth(), bar.getHeight()), ((FloatingBackground) bar.getBackground()).density,
                "compact".equals(Settings.GLASS_BAR_HEIGHT.get()), pill);
        return Math.max(0, -pill.top);
    }

    /**
     * The pill stands {@link #PILL_RISE_DP} above the bar, which over the video only covers
     * picture. A page that stops at the bar's top, Home's Community grid or another tab's page,
     * showed the pill's top edge over its last row (#436), so with {@code paint} the strip the pill
     * stands in is painted the color around the bar ({@link #stripColor}) and the pill sits inside
     * the bottom area. Without it the strip is left clear.
     */
    static void paintRiseStrip(View bar, boolean paint) {
        if (bar == null || !(bar.getBackground() instanceof FloatingBackground)) return;
        ((FloatingBackground) bar.getBackground()).setStrip(paint ? stripColor(bar) : 0);
    }

    /**
     * The color around {@code bar} where the page stops at its top: TikTok's own bar color when
     * it's a solid one, otherwise white under dark tabs and black under light ones.
     */
    static int stripColor(View bar) {
        Drawable theirs = BACKGROUNDS.get(bar);
        Integer color = null;
        if (theirs instanceof ColorDrawable) {
            color = ((ColorDrawable) theirs).getColor();
        } else if (theirs instanceof GradientDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.ColorStateList colors = ((GradientDrawable) theirs).getColor();
            if (colors != null) color = colors.getDefaultColor();
        }
        if (color != null && Color.alpha(color) == 0xFF) return color;
        return bar instanceof ViewGroup && tabsOnLight((ViewGroup) bar) ? Color.WHITE : Color.BLACK;
    }

    /**
     * The corner radius Pill corners gives the pill, or the lens with {@code lens}, for a shape
     * {@code height} tall: 12dp and 8dp for "standard", 20dp and 14dp for "rounder", and half the
     * height otherwise, which is never passed.
     */
    static float cornerPx(String corners, boolean lens, float height, float density) {
        float half = Math.max(0f, height / 2f);
        if ("standard".equals(corners)) return Math.min((lens ? 8 : 12) * density, half);
        if ("rounder".equals(corners)) return Math.min((lens ? 14 : 20) * density, half);
        return half;
    }

    /** What Glass opacity multiplies the glass's tint by. */
    static float opacityScale(String opacity) {
        if ("clear".equals(opacity)) return 0.5f;
        if ("light".equals(opacity)) return 0.75f;
        if ("frosted".equals(opacity)) return 1.35f;
        return 1f;
    }

    /** {@code color} with an alpha of {@code alpha}, held between 0 and 255. */
    static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, Math.round(alpha))) << 24);
    }

    /** The middle of the {@code index}th of {@code count} slots spread evenly from {@code from} to {@code to}. */
    static float slotMiddle(float from, float to, int count, int index) {
        return from + (to - from) * (index + 0.5f) / count;
    }

    /** The bar's shown tabs, left to right. */
    static List<View> shownTabs(ViewGroup bar) {
        List<View> tabs = new ArrayList<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            if (tab.getVisibility() == View.VISIBLE && tab.getWidth() > 0) tabs.add(tab);
        }
        Collections.sort(tabs, (one, other) -> Integer.compare(one.getLeft(), other.getLeft()));
        return tabs;
    }

    /** Tabs this class moved to spread them over the pill, with the offset it gave each. */
    private static final Map<View, Float> MOVED = new WeakHashMap<>();

    /**
     * Spreads the bar's shown tabs evenly over {@code pill}, from {@link #TAB_INNER_DP} in from one
     * end to as far in from the other, as HushGram's glass tab bar does. TikTok lays them out over
     * the bar's whole width, which left the outer icons near the pill's ends and the lens off
     * their middles. They move by translation, so nothing is laid out again and each tab's taps
     * move with it. A tab TikTok offset itself keeps its offset.
     *
     * <p>The pill stands above the bar ({@link #PILL_RISE_DP}), so each tab also moves up to sit
     * in the pill's middle. With Hide the bottom tab names the name under each icon is gone, but
     * TikTok keeps the icon where it sat above the name, so the icons rode high in the pill with
     * the empty room under them. Then the icon, not the tab, goes in the pill's middle.
     */
    static void placeTabs(ViewGroup bar, Rect pill, float density) {
        if (pill.isEmpty()) return;
        List<View> tabs = shownTabs(bar);
        float inner = TAB_INNER_DP * density;
        boolean centerIcons = Settings.HIDE_BOTTOM_TAB_LABELS.get();
        for (int k = 0; k < tabs.size(); k++) {
            View tab = tabs.get(k);
            float middle = (tab.getLeft() + tab.getRight()) / 2f;
            offset(tab, Math.round(slotMiddle(pill.left + inner, pill.right - inner, tabs.size(), k) - middle),
                    MOVED, true);
            float icon = centerIcons ? iconMiddle(tab) : Float.NaN;
            if (Float.isNaN(icon)) icon = tab.getHeight() / 2f;
            offset(tab, Math.round(pill.exactCenterY() - (tab.getTop() + icon)), LIFTED, false);
        }
    }

    /** Tabs this class moved up or down to put their icons in the pill's middle, with the offset each got. */
    private static final Map<View, Float> LIFTED = new WeakHashMap<>();

    /**
     * Gives {@code tab} a sideways ({@code across}) or up and down offset of {@code shift},
     * noting it in {@code moved}. An offset of TikTok's own, one {@code moved} doesn't hold, stays.
     */
    private static void offset(View tab, float shift, Map<View, Float> moved, boolean across) {
        Float ours = moved.get(tab);
        float now = across ? tab.getTranslationX() : tab.getTranslationY();
        if (now != 0f && (ours == null || ours != now)) return;
        if (now != shift) {
            if (across) tab.setTranslationX(shift);
            else tab.setTranslationY(shift);
        }
        if (shift == 0f) moved.remove(tab);
        else moved.put(tab, shift);
    }

    /**
     * The middle of {@code tab}'s icon, from the tab's top: the largest shown view in it that
     * isn't text and draws something, so a count badge, a name or an empty container never
     * counts. NaN when it has none.
     *
     * <p>A picture whose view fills most of the tab's height says nothing by its box, so then the
     * middle is where it draws. TikTok's Create tab (48.0.2, 144x98 on a 720px screen) holds two
     * Fresco views as big as the cell, and the plate with the + draws in the cell's upper part,
     * 10px above the view's middle, which left the + high in the pill (#474).
     */
    static float iconMiddle(View tab) {
        List<View> pictures = new ArrayList<>();
        List<PointF> at = new ArrayList<>();
        pictures(tab, 0f, 0f, pictures, at, 0);
        int best = -1;
        float bestArea = 0f;
        for (int i = 0; i < pictures.size(); i++) {
            float area = (float) pictures.get(i).getWidth() * pictures.get(i).getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        if (best < 0) return Float.NaN;
        View icon = pictures.get(best);
        float middle = at.get(best).y + icon.getHeight() / 2f;
        if (icon.getHeight() * 4 < tab.getHeight() * 3) return middle;
        float drawn = drawnMiddle(tab, pictures, at, bestArea);
        return Float.isNaN(drawn) ? middle : drawn;
    }

    /** Every shown view in {@code view} that isn't text and draws something, with where it sits in the tab. */
    private static void pictures(View view, float left, float top, List<View> out, List<PointF> at, int depth) {
        if (view.getVisibility() != View.VISIBLE || depth > 8) return;
        if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                pictures(child, left + child.getLeft() + child.getTranslationX(),
                        top + child.getTop() + child.getTranslationY(), out, at, depth + 1);
            }
            return;
        }
        if (view instanceof android.widget.TextView) return;
        // An empty container with no background draws nothing, so it can't be the icon. TikTok's
        // Create tab (47.9.3 LX/05e7) can carry a centered container of its own, and taking that
        // for the icon puts the tab's middle in the pill's middle, leaving the + high (#474).
        // Android skips drawing such a view the same way: will-not-draw and nothing behind or over it.
        if (view.willNotDraw() && view.getBackground() == null && view.getForeground() == null) return;
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
        out.add(view);
        at.add(new PointF(left, top));
    }

    /** The drawn middle each tab had at its size, and when an empty one may be tried again. */
    private static final Map<View, float[]> DRAWN = new WeakHashMap<>();
    /** How long a tab whose picture hadn't drawn yet (Fresco loads it late) waits for another look. */
    static final long DRAWN_RETRY_MS = 500;
    /** The alpha a pixel needs to count as part of the picture, so a soft shadow's fringe doesn't. */
    static final int DRAWN_ALPHA = 0x40;

    /**
     * The middle of the rows {@code tab}'s big pictures draw on, from the tab's top: every picture
     * at least 90% of {@code area}, since Create's two Fresco views overlap and only one shows its
     * image. NaN when they draw nothing yet or can't be drawn here.
     */
    private static float drawnMiddle(View tab, List<View> pictures, List<PointF> at, float area) {
        int width = tab.getWidth(), height = tab.getHeight();
        if (width <= 0 || height <= 0) return Float.NaN;
        long now = SystemClock.uptimeMillis();
        float[] known = DRAWN.get(tab);
        if (known != null && known[0] == width && known[1] == height
                && (!Float.isNaN(known[2]) || now < known[3])) {
            return known[2];
        }
        float middle = Float.NaN;
        Bitmap pixels = null;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // Through a Picture, so a hardware bitmap (which a software canvas refuses) is read
                // back from the GPU instead of failing the draw.
                Picture picture = new Picture();
                drawPictures(picture.beginRecording(width, height), pictures, at, area);
                picture.endRecording();
                pixels = Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888);
            } else {
                pixels = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                drawPictures(new Canvas(pixels), pictures, at, area);
            }
            middle = opaqueMiddle(pixels);
        } catch (IllegalArgumentException | IllegalStateException failed) {
            // A view that refuses an off-screen canvas keeps its box's middle, as before.
            Logger.printDebug(() -> "Bottom bar icon not measurable: " + failed);
        } finally {
            if (pixels != null) pixels.recycle();
        }
        DRAWN.put(tab, new float[]{width, height, middle, now + DRAWN_RETRY_MS});
        return middle;
    }

    private static void drawPictures(Canvas canvas, List<View> pictures, List<PointF> at, float area) {
        for (int i = 0; i < pictures.size(); i++) {
            View picture = pictures.get(i);
            if ((float) picture.getWidth() * picture.getHeight() < area * 0.9f) continue;
            int save = canvas.save();
            canvas.translate(at.get(i).x, at.get(i).y);
            picture.draw(canvas);
            canvas.restoreToCount(save);
        }
    }

    /** The middle of the rows of {@code pixels} that hold a pixel at least {@link #DRAWN_ALPHA} opaque. NaN when none do. */
    static float opaqueMiddle(Bitmap pixels) {
        int width = pixels.getWidth(), height = pixels.getHeight();
        int[] row = new int[width];
        int first = -1, last = -1;
        for (int y = 0; y < height; y++) {
            pixels.getPixels(row, 0, width, 0, y, width, 1);
            for (int pixel : row) {
                if ((pixel >>> 24) >= DRAWN_ALPHA) {
                    if (first < 0) first = y;
                    last = y;
                    break;
                }
            }
        }
        return first < 0 ? Float.NaN : (first + last + 1) / 2f;
    }

    /** Takes back the offsets {@link #placeTabs} gave the bar's tabs, where nothing changed them since. */
    static void putTabsBack(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            Float ours = MOVED.remove(tab);
            if (ours != null && tab.getTranslationX() == ours) tab.setTranslationX(0f);
            Float lifted = LIFTED.remove(tab);
            if (lifted != null && tab.getTranslationY() == lifted) tab.setTranslationY(0f);
        }
    }

    /** The bar {@code background} is drawn for, or null when it's on no bar. */
    static ViewGroup barOf(Drawable background) {
        Drawable.Callback callback = background.getCallback();
        return callback instanceof ViewGroup ? (ViewGroup) callback : null;
    }

    /**
     * One pre-draw listener on a bar's window, moved along when the bar's window changes.
     * Attaching the bar merges its detached observer's listeners into the window's, so the
     * listener may already be there: it's never held twice.
     */
    private static final class FrameWatch {
        private final ViewTreeObserver.OnPreDrawListener frame;
        private ViewTreeObserver watched;
        /** The bar this listens on, kept after the bar drops the background that set it. */
        private WeakReference<ViewGroup> bar = new WeakReference<>(null);

        FrameWatch(ViewTreeObserver.OnPreDrawListener frame) {
            this.frame = frame;
        }

        ViewGroup bar() {
            return bar.get();
        }

        void on(ViewGroup bar) {
            ViewTreeObserver observer = bar.getViewTreeObserver();
            if (observer == watched || !observer.isAlive()) return;
            off();
            observer.removeOnPreDrawListener(frame);
            observer.addOnPreDrawListener(frame);
            watched = observer;
            this.bar = new WeakReference<>(bar);
        }

        void off() {
            if (watched != null && watched.isAlive()) watched.removeOnPreDrawListener(frame);
            watched = null;
        }
    }

    /**
     * Floating bottom bar (#124): a rounded see-through pill drawn inside the bar, in from its
     * sides by HushGram's proportions and a hairline from its top and bottom, so the tabs float
     * over the video. The bar keeps its place and size; before each frame its tabs are spread
     * over the pill ({@link #placeTabs}), and they go back once the bar has another background.
     */
    static class FloatingBackground extends InsetDrawable {
        final float density;
        /** The pill's bounds in the bar, as last laid out. */
        final Rect pillRect = new Rect();
        /** The Pill height and Pill corners the pill was last laid out with. */
        private String height, corners;
        /** True while the pill is painted for TikTok's light bar ({@link #tabsOnLight}, #436). */
        boolean onLight;
        /**
         * The color the strip the pill stands in above the bar is painted, or 0 to leave it
         * clear over the video ({@link #paintRiseStrip}, #436).
         */
        private int strip;
        private final Paint stripPaint = new Paint();
        private final FrameWatch watch = new FrameWatch(this::beforeFrame);

        FloatingBackground(float density) {
            this(pill(density), density);
        }

        FloatingBackground(Drawable pill, float density) {
            super(pill, 0);
            this.density = density;
        }

        /**
         * No padding: a background's padding becomes the view's, which would squeeze the tabs and
         * outlive the pill when TikTok's background comes back. The pill's place is drawing only.
         */
        @Override
        public boolean getPadding(Rect padding) {
            padding.set(0, 0, 0, 0);
            return false;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            layOut(bounds);
        }

        /** Places the pill in {@code bounds} by the Pill height and Pill corners picked. */
        private void layOut(Rect bounds) {
            height = Settings.GLASS_BAR_HEIGHT.get();
            corners = Settings.GLASS_CORNERS.get();
            pillBounds(bounds, density, "compact".equals(height), pillRect);
            Drawable inner = getDrawable();
            if (inner instanceof GradientDrawable) {
                ((GradientDrawable) inner).setCornerRadius(cornerPx(corners, false, pillRect.height(), density));
            }
            inner.setBounds(pillRect);
        }

        @Override
        public void draw(Canvas canvas) {
            if (!Settings.GLASS_BAR_HEIGHT.get().equals(height) || !Settings.GLASS_CORNERS.get().equals(corners)) {
                layOut(getBounds());
            }
            ViewGroup bar = bar();
            if (bar != null) {
                watch.on(bar);
                // Also here, so the first frame already has them in place.
                placeTabs(bar, pillRect, density);
                followTabs(bar);
            }
            Rect bounds = getBounds();
            if (strip != 0 && pillRect.top < bounds.top) {
                stripPaint.setColor(strip);
                canvas.drawRect(bounds.left, pillRect.top, bounds.right, bounds.top, stripPaint);
            }
            drawBehindPill(canvas);
            super.draw(canvas);
        }

        void setStrip(int color) {
            if (strip == color) return;
            strip = color;
            invalidateSelf();
        }

        int strip() {
            return strip;
        }

        /**
         * Paints the pill light or dark to go with the ink TikTok gave {@code bar}'s tabs (#436),
         * true when that changed.
         */
        private boolean followTabs(ViewGroup bar) {
            boolean light = tabsOnLight(bar);
            if (light == onLight) return false;
            onLight = light;
            Drawable inner = getDrawable();
            if (inner instanceof GlassPill) {
                ((GlassPill) inner).setOnLight(light);
            } else if (inner instanceof GradientDrawable) {
                ((GradientDrawable) inner).setColor(light ? PILL_COLOR_ON_LIGHT : PILL_COLOR);
                ((GradientDrawable) inner).setStroke(Math.max(1, Math.round(density)),
                        light ? PILL_EDGE_ON_LIGHT : PILL_EDGE_COLOR);
            }
            return true;
        }

        /** What goes under the pill: nothing for the plain one. */
        void drawBehindPill(Canvas canvas) {
        }

        /**
         * Before each frame: spreads the tabs over the pill, and lets go of the bar once it has
         * another background, giving the tabs back their places unless that is another pill.
         */
        private boolean beforeFrame() {
            ViewGroup bar = watch.bar();
            if (bar == null || bar.getBackground() != this) {
                if (bar != null && !(bar.getBackground() instanceof FloatingBackground)) putTabsBack(bar);
                watch.off();
                return true;
            }
            placeTabs(bar, pillRect, density);
            // A tab's new ink redraws the tab alone, never the bar's background under it.
            if (followTabs(bar)) invalidateSelf();
            return true;
        }

        ViewGroup bar() {
            return barOf(this);
        }

        private static GradientDrawable pill(float density) {
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setCornerRadius(app.morphe.extension.tiktok.settings.preference.SettingsUi.RADIUS_ROUND_ENDS * density);
            pill.setColor(PILL_COLOR);
            pill.setStroke(Math.max(1, Math.round(density)), PILL_EDGE_COLOR);
            return pill;
        }
    }

    /** The glass pill's dark tint, light enough that the video shows through, dark enough for white icons. */
    static final int GLASS_TINT = 0x4D000000;
    /** The white frost over the tint, brighter at the top. */
    static final int GLASS_FROST_TOP = 0x33FFFFFF, GLASS_FROST_BOTTOM = 0x0DFFFFFF;
    /** The sheen over the upper half, fading out by the middle. */
    static final int GLASS_SHEEN = 0x40FFFFFF;
    /** The rim: bright along the top edge where the light catches it, faint on the sides, a little back at the bottom. */
    static final int GLASS_RIM_TOP = 0x99FFFFFF, GLASS_RIM_SIDE = 0x26FFFFFF, GLASS_RIM_BOTTOM = 0x4DFFFFFF;
    /**
     * The glass over TikTok's light bar (#436): a white tint for its dark tabs, and a dark rim,
     * faint along the top and a little deeper at the bottom, since a white one vanishes on white.
     */
    static final int GLASS_TINT_ON_LIGHT = 0x99FFFFFF;
    static final int GLASS_RIM_TOP_ON_LIGHT = 0x14000000, GLASS_RIM_SIDE_ON_LIGHT = 0x1F000000,
            GLASS_RIM_BOTTOM_ON_LIGHT = 0x33000000;

    /**
     * Liquid glass look (#218): the floating pill drawn as glass, in the same place. A view can
     * only blur what it draws itself, never the video under it, so the glass is drawn rather than
     * blurred: a lighter tint than the plain pill, a frost and a sheen that are brightest at the
     * top, and a rim that catches the light along the top edge.
     *
     * <p>With Blur the video behind the bar (#231) the strip of video behind the pill is read
     * back and drawn blurred under the tint, so the glass blurs the picture instead of only
     * frosting it. Until a strip has been read, or where none can be, it is the plain glass.
     */
    static class GlassBackground extends FloatingBackground {
        /** Null for the plain glass. */
        final GlassVideoBlur blur;

        GlassBackground(float density) {
            this(density, false);
        }

        GlassBackground(float density, boolean blurVideo) {
            super(new GlassPill(density), density);
            blur = blurVideo ? new GlassVideoBlur(density, this) : null;
        }

        @Override
        void drawBehindPill(Canvas canvas) {
            Callback callback = getCallback();
            if (blur != null && callback instanceof View) {
                blur.drawBehind(canvas, (View) callback, pillRect,
                        cornerPx(Settings.GLASS_CORNERS.get(), false, pillRect.height(), density));
            }
        }
    }

    static final class GlassPill extends Drawable {
        private final float density;
        private final float rim;
        /** True for the bar's pill, shaped by Pill corners and tinted by Glass opacity. */
        private final boolean forBar;
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int alpha = 255;
        /** True for the white glass over TikTok's light bar (#436). */
        private boolean onLight;

        GlassPill(float density) {
            this(density, true);
        }

        void setOnLight(boolean light) {
            if (onLight == light) return;
            onLight = light;
            onBoundsChange(getBounds());
            invalidateSelf();
        }

        boolean onLight() {
            return onLight;
        }

        /** One not {@code forBar} has round ends and the standard tint, the Stories pill's glass (#409). */
        GlassPill(float density, boolean forBar) {
            this.density = density;
            this.forBar = forBar;
            rim = Math.max(1f, density);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(rim);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            float top = bounds.top, bottom = bounds.bottom;
            frost.setShader(new LinearGradient(0, top, 0, bottom,
                    GLASS_FROST_TOP, GLASS_FROST_BOTTOM, Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(0, top, 0, (top + bottom) / 2f,
                    GLASS_SHEEN, GLASS_SHEEN & 0x00FFFFFF, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, top, 0, bottom,
                    onLight ? new int[]{GLASS_RIM_TOP_ON_LIGHT, GLASS_RIM_SIDE_ON_LIGHT, GLASS_RIM_BOTTOM_ON_LIGHT}
                            : new int[]{GLASS_RIM_TOP, GLASS_RIM_SIDE, GLASS_RIM_BOTTOM},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            box.set(bounds);
            float radius = forBar ? cornerPx(Settings.GLASS_CORNERS.get(), false, box.height(), density)
                    : box.height() / 2f;
            // Glass opacity scales the tint and, up to its full strength, the frost; a shader's
            // colors are scaled by its paint's alpha, the tint's own alpha is scaled here.
            float scale = forBar ? opacityScale(Settings.GLASS_OPACITY.get()) : 1f;
            int glass = onLight ? GLASS_TINT_ON_LIGHT : GLASS_TINT;
            tint.setColor(withAlpha(glass, Color.alpha(glass) * alpha / 255f * scale));
            frost.setAlpha(Math.round(alpha * Math.min(1f, scale)));
            canvas.drawRoundRect(box, radius, radius, tint);
            canvas.drawRoundRect(box, radius, radius, frost);
            // The sheen sits inside the rim.
            float in = rim * 2f;
            box.inset(in, in);
            canvas.drawRoundRect(box, Math.max(0f, radius - in), Math.max(0f, radius - in), sheen);
            box.set(bounds);
            box.inset(rim / 2f, rim / 2f);
            canvas.drawRoundRect(box, Math.max(0f, radius - rim / 2f), Math.max(0f, radius - rim / 2f), edge);
        }

        @Override
        public void setAlpha(int alpha) {
            if (this.alpha == alpha) return;
            this.alpha = alpha;
            sheen.setAlpha(alpha);
            edge.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            tint.setColorFilter(filter);
            frost.setColorFilter(filter);
            sheen.setColorFilter(filter);
            edge.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The lens's fill, HushGram's highlight on dark glass, so the tab you're on reads as raised glass. */
    static final int LENS_FILL = 0x40FFFFFF;
    /** The bubble's sheen, from its top edge fading down inside it. */
    static final int LENS_SHEEN = 0x40FFFFFF;
    /** The bubble's rim: bright along its top where the light catches it, faint along its bottom. */
    static final int LENS_RIM_TOP = 0xB0FFFFFF, LENS_RIM_BOTTOM = 0x28FFFFFF;
    /**
     * The lens on TikTok's own bar (#483): a thin line and nothing inside, white at 80% on a dark
     * bar and dark on a light one, since the frosted bubble looked like a heavy frame on a flat bar.
     */
    static final int LENS_OUTLINE = 0xCCFFFFFF, LENS_OUTLINE_ON_LIGHT = 0x8C000000;
    static final float LENS_OUTLINE_DP = 1f;
    /** How far the lens stays in from the pill's top, bottom and ends, and how far past the tab's slot it stands, in dp. */
    static final int LENS_INSET_DP = 3, LENS_GROW_DP = 4;
    /** How much larger the bubble is midway through a slide and under a finger. */
    static final float BUBBLE_SWELL = 0.08f;
    /** With Liquid glass when held, how much larger a fully held bubble is (#440). */
    static final float LIQUID_SWELL = 0.3f;
    /** And how much larger the tab right under a fully held bubble is. */
    static final float TAB_SWELL = 0.15f;
    /** How long a held lens takes to swell all the way, and to wobble back to rest once let go. */
    static final long LIQUID_PRESS_MS = 160, LIQUID_RELEASE_MS = 560;
    /** How long the lens takes to slide to a newly picked tab. */
    static final long LENS_SLIDE_MS = 280;
    /** How long a bar remembers where its last lens was, for the lens that replaces it. */
    static final long LENS_MEMORY_MS = 1000;
    /** A gap between two frames of a slide longer than this is the main thread stalling. */
    static final long LENS_STALL_MS = 50;
    /** How far a slide moves on over a stall: one frame at 60 Hz. */
    static final long LENS_FRAME_MS = 16;

    /**
     * Where the lens goes behind a selected tab spanning {@code tab}, put in {@code out}: as wide
     * as the tab's slot on the pill and {@link #LENS_GROW_DP} more each side, on the tab's middle,
     * kept {@link #LENS_INSET_DP} inside the pill. False when there's no room for it.
     */
    static boolean lensSpan(RectF tab, Rect pill, int tabs, float density, RectF out) {
        float inset = LENS_INSET_DP * density;
        float slot = tabs <= 0 ? tab.width() : (pill.width() - 2f * TAB_INNER_DP * density) / tabs;
        float half = Math.min(tab.width(), slot) / 2f + LENS_GROW_DP * density;
        float left = Math.max(pill.left + inset, tab.centerX() - half);
        float right = Math.min(pill.right - inset, tab.centerX() + half);
        if (right - left < inset * 2f) return false;
        out.set(left, pill.top + inset, right, pill.bottom - inset);
        return true;
    }

    /**
     * The bubble's size {@code elapsed} ms into a slide that began at size {@code from}: it swells
     * to {@link #BUBBLE_SWELL} over 1 mid-slide, eases back to 1 as it settles, and carries on from
     * {@code from} when it was already bigger, such as a finger letting go of it.
     */
    static float bubbleScale(long elapsed, float from) {
        if (elapsed >= LENS_SLIDE_MS) return 1f;
        float t = Math.max(0f, elapsed) / (float) LENS_SLIDE_MS;
        float swell = 1f + BUBBLE_SWELL * (float) Math.sin(Math.PI * t);
        float carried = 1f + Math.max(0f, from - 1f) * (1f - t);
        return Math.max(swell, carried);
    }

    /**
     * How pressed a held lens is {@code elapsed} ms after a finger went down on it ({@code held})
     * or let go, starting from {@code from}: 0 at rest, 1 fully held. Going down it eases out to 1
     * over {@link #LIQUID_PRESS_MS}. Let go, it springs back over {@link #LIQUID_RELEASE_MS},
     * past rest once (a fifth of where it started, so the bubble dips below its size) and a
     * little back, and lands on 0, like a drop of liquid settling.
     */
    static float liquidPress(long elapsed, boolean held, float from) {
        if (held) {
            if (elapsed >= LIQUID_PRESS_MS) return 1f;
            float rest = 1f - Math.max(0f, elapsed) / (float) LIQUID_PRESS_MS;
            return from + (1f - from) * (1f - rest * rest);
        }
        if (elapsed >= LIQUID_RELEASE_MS) return 0f;
        double t = Math.max(0f, elapsed) / (double) LIQUID_RELEASE_MS;
        return (float) (from * Math.exp(-4.0 * t) * Math.cos(2.5 * Math.PI * t));
    }

    /**
     * The bubble's size with a held lens's press on top of {@code bubble}, the size its slide
     * gives it: the larger of the two while pressed, and smaller than that while it wobbles back.
     */
    static float heldBubble(float bubble, float press) {
        float liquid = 1f + LIQUID_SWELL * press;
        return press >= 0f ? Math.max(bubble, liquid) : bubble * liquid;
    }

    /**
     * How large a tab whose middle is at {@code middle} is under a lens {@code width} wide on
     * {@code lens}, pressed {@code press}: {@link #TAB_SWELL} larger right under a fully held one,
     * less the further off its middle the tab is, its own size a lens's width away, and a little
     * smaller while the lens wobbles back past rest.
     */
    static float tabGrowth(float middle, float lens, float width, float press) {
        if (width <= 0f || press == 0f) return 1f;
        float near = Math.max(0f, 1f - Math.abs(middle - lens) / width);
        return 1f + TAB_SWELL * press * near;
    }

    /**
     * Where the lens's left edge goes under a finger sliding across the tabs: {@code width} wide,
     * centered on {@code finger}, kept between {@code min} and {@code max}.
     */
    static float followLeft(float finger, float width, float min, float max) {
        if (max - min <= width) return min;
        return Math.max(min, Math.min(max - width, finger - width / 2f));
    }

    /** The lens's fill on a light bar, where the white one wouldn't show. */
    static final int LENS_FILL_ON_LIGHT = 0x1A000000;
    /** The bubble's rim on a light bar: darker along its top, faint along its bottom. */
    static final int LENS_RIM_TOP_ON_LIGHT = 0x40000000, LENS_RIM_BOTTOM_ON_LIGHT = 0x14000000;

    /** A bar background that carries the lens behind the selected tab (#231, #416). */
    interface LensCarrier {
        TabLens lens();

        /** Where the lens stays inside, and where Slide across the tabs starts, in the bar's pixels. */
        Rect lensArea();
    }

    /**
     * Glass lens on the current tab (#231): a raised bubble of glass behind the tab you're on,
     * sliding to the next tab you pick, drawn the way HushGram's liquid glass tab bar draws it: a
     * lighter fill, a sheen and a rim brightest along its top, a little swell while it moves, and
     * with Blur the video behind the bar, the blurred video under it magnified ({@link GlassLens}).
     * Any of the bar's looks carries it (#416): the glass pill, the plain floating pill, the clear
     * bar and TikTok's own bar, each drawing it over its own background ({@link LensCarrier}).
     *
     * <p>TikTok marks the current tab with View.isSelected() and nothing else (S22, 47.4.3: no
     * indicator view, no alpha), so the lens follows the bar's first selected child. A bar with
     * no selected tab shows no lens. The lens is part of the bar's background, so it sits under
     * the tabs and never takes a tap. A selection change redraws no background by itself, so the
     * pre-draw listener asks for one when the selected tab moved. Picking a tab can also hand the
     * bar a new lens (the overlay pass swaps the background when the bar's look changes), and a
     * new lens has no slide of its own to continue, so each bar remembers where its last lens was
     * drawn and a new one slides on from there when that was moments ago (S22, 2026-10-21: the
     * lens jumped between tabs with animations on). With Slide across the tabs the lens follows a
     * finger ({@link GlassTabSlide}), and with Glass haptics it ticks each time its middle passes a
     * tab ({@link GlassBarHaptics}). With Liquid glass when held a finger on the pill brings the
     * lens to it and swells it, the magnifier bends harder under it, the tabs under it grow and on
     * Android 13 and up bend at its rim, and it all wobbles back once let go ({@link #liquidPress}).
     */
    static final class TabLens {
        /** Where each bar's lens was last drawn, kept only as long as the bar is. */
        private static final Map<View, LensSpot> LAST_SPOT = new WeakHashMap<>();
        private final float density;
        /** The background the lens is drawn in. */
        private final Drawable owner;
        /** The glass's blur, null where nothing behind the lens is blurred. */
        private final GlassVideoBlur blur;
        private final FrameWatch watch = new FrameWatch(this::beforeFrame);
        private final Paint lensFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint magnifiedTint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbleSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbleRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbleRimOnLight = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** Whether the lens is a thin outline instead of the glass bubble (#483). */
        private final boolean outline;
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix shade = new Matrix();
        private final RectF target = new RectF();
        private final RectF shown = new RectF();
        private final RectF capsule = new RectF();
        private final RectF span = new RectF();
        private GlassLens magnifier;
        /** The bar's remembered spot this lens last wrote, null before its first lens. */
        private LensSpot written;
        /** The selected tab's span the last draw used, NaN before a draw that found one. */
        private float drawnLeft = Float.NaN, drawnRight = Float.NaN;
        /** Where the slide runs from and to, and when it began. */
        private float fromLeft, fromRight, toLeft, toRight;
        private long slideStart;
        /** When this lens last drew itself, 0 before its first lens. */
        private long lastDraw;
        /** The bubble's size now, and at the start of the slide it is in. */
        private float bubbleNow = 1f, bubbleFrom = 1f;
        /** Whether a finger holds the lens (Liquid glass when held). */
        boolean held;
        /** When {@link #held} last changed, 0 before it ever did, and how pressed the lens was then. */
        private long heldChange;
        private float pressFrom;
        /** How pressed this draw found the lens, and the bubble's size with that press on it. */
        private float pressNow, bubbleShown = 1f;
        /** Where a finger sliding across the tabs holds the lens's middle, NaN without one. */
        float dragX = Float.NaN;
        /** Where the finger holding the lens went down, in the bar's pixels, NaN without one. */
        private float pressX = Float.NaN;
        /** The tabs a held lens has grown, each with the scale it had before. */
        private final Map<View, float[]> grown = new WeakHashMap<>();
        private final RectF lensBox = new RectF();
        private final RectF tabBox = new RectF();
        /** The tab the lens's middle was over at the last draw, null before the first. */
        private View tickedTab;

        TabLens(float density, Drawable owner, GlassVideoBlur blur) {
            this(density, owner, blur, false);
        }

        /** With {@code outline}, the lens is the thin outline TikTok's own bar wears (#483). */
        TabLens(float density, Drawable owner, GlassVideoBlur blur, boolean outline) {
            this.density = density;
            this.owner = owner;
            this.blur = blur;
            this.outline = outline;
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(Math.max(1f, density * LENS_OUTLINE_DP));
            bubbleSheen.setShader(new LinearGradient(0, 0, 0, 1, LENS_SHEEN, LENS_SHEEN & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            for (Paint rim : new Paint[]{bubbleRim, bubbleRimOnLight}) {
                rim.setStyle(Paint.Style.STROKE);
                rim.setStrokeWidth(Math.max(1f, density * 1.25f));
            }
            bubbleRim.setShader(new LinearGradient(0, 0, 0, 1, LENS_RIM_TOP, LENS_RIM_BOTTOM,
                    Shader.TileMode.CLAMP));
            bubbleRimOnLight.setShader(new LinearGradient(0, 0, 0, 1, LENS_RIM_TOP_ON_LIGHT,
                    LENS_RIM_BOTTOM_ON_LIGHT, Shader.TileMode.CLAMP));
        }

        /**
         * Draws the lens behind {@code bar}'s selected tab, kept inside {@code area}, at
         * {@code alpha}, in dark tones over a bar that's {@code onLight}.
         */
        void draw(Canvas canvas, ViewGroup bar, Rect area, int alpha, boolean onLight) {
            watch.on(bar);
            keepSlide(bar);
            if (area.isEmpty() || !selectedSpan(bar, span)
                    || !lensSpan(span, area, shownTabs(bar).size(), density, target)) {
                drawnLeft = drawnRight = Float.NaN;
                restoreTabs();
                return;
            }
            float left = target.left, right = target.right;
            float inset = LENS_INSET_DP * density;
            long now = SystemClock.uptimeMillis();
            holdOverStall(now);
            if (Float.isNaN(dragX) && !Float.isNaN(pressX) && Settings.GLASS_LIQUID_HOLD.get()) {
                // Held (#440): the lens goes to the finger, sliding there as it would to a tab.
                float width = right - left;
                left = followLeft(pressX, width, area.left + inset, area.right - inset);
                right = left + width;
            }
            if (!Float.isNaN(dragX)) {
                // Under a finger: on it, swollen, and no slide of its own.
                float width = right - left;
                left = followLeft(dragX, width, area.left + inset, area.right - inset);
                right = left + width;
                fromLeft = toLeft = left;
                fromRight = toRight = right;
                slideStart = now - LENS_SLIDE_MS;
                bubbleNow = bubbleFrom = 1f + BUBBLE_SWELL;
            } else if (Float.isNaN(drawnLeft)) {
                LensSpot last = LAST_SPOT.get(bar);
                toLeft = left;
                toRight = right;
                bubbleFrom = 1f;
                if (last != null && now - last.at < LENS_MEMORY_MS
                        && (last.left != left || last.right != right)) {
                    // A lens this bar showed moments ago: carry on from where that one was.
                    fromLeft = last.left;
                    fromRight = last.right;
                    slideStart = now;
                } else {
                    // The first tab found, or the first in a while: no slide in from nowhere.
                    fromLeft = left;
                    fromRight = right;
                    slideStart = now - LENS_SLIDE_MS;
                }
            } else if (left != toLeft || right != toRight) {
                // From wherever the lens is now, so a tap mid-slide turns it around smoothly.
                float done = progress(now);
                fromLeft = lerp(fromLeft, toLeft, done);
                fromRight = lerp(fromRight, toRight, done);
                toLeft = left;
                toRight = right;
                slideStart = now;
                bubbleFrom = bubbleNow;
            }
            drawnLeft = span.left;
            drawnRight = span.right;
            float done = progress(now);
            if (Float.isNaN(dragX)) {
                bubbleNow = animatorsEnabled() ? bubbleScale(now - slideStart, bubbleFrom) : 1f;
            }
            pressNow = press(now);
            bubbleShown = heldBubble(bubbleNow, pressNow);
            shown.set(lerp(fromLeft, toLeft, done), target.top, lerp(fromRight, toRight, done), target.bottom);
            LensSpot spot = LAST_SPOT.get(bar);
            if (spot == null) LAST_SPOT.put(bar, spot = new LensSpot());
            written = spot;
            spot.left = shown.left;
            spot.right = shown.right;
            spot.at = now;
            tick(bar, shown.centerX());
            paintBubble(canvas, bar, area, alpha, onLight);
            liquidTabs(bar);
            if (done < 1f || bubbleNow != 1f || pressMoving(now)) owner.invalidateSelf();
        }

        /**
         * Liquid glass when held (#440): the tabs under a held lens grow toward its middle and, on
         * Android 13 and up, bend at its rim the way the video under it does. They follow the
         * press as it wobbles back, then get the scale they had and lose the bend.
         */
        private void liquidTabs(ViewGroup bar) {
            if (pressNow == 0f) {
                restoreTabs();
                return;
            }
            float corner = swollen(lensBox);
            for (View tab : shownTabs(bar)) {
                float[] before = grown.get(tab);
                // The floating pill spreads its tabs by moving them, so the middle counts the move.
                float scale = tabGrowth(tab.getLeft() + tab.getTranslationX() + tab.getWidth() / 2f,
                        lensBox.centerX(), lensBox.width(), pressNow);
                if (before == null) {
                    if (scale == 1f) continue;
                    before = new float[]{tab.getScaleX(), tab.getScaleY()};
                    grown.put(tab, before);
                }
                tab.setScaleX(before[0] * scale);
                tab.setScaleY(before[1] * scale);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && tab.isHardwareAccelerated()) {
                    if (magnifier == null) magnifier = new GlassLens();
                    tab.setRenderEffect(inTab(tab, lensBox, tabBox)
                            ? magnifier.tabBend(tabBox, corner / Math.max(0.01f, tab.getScaleX()), pressNow)
                            : null);
                }
            }
        }

        /** Puts back every tab a held lens grew or bent. */
        private void restoreTabs() {
            if (grown.isEmpty()) return;
            for (Map.Entry<View, float[]> entry : grown.entrySet()) {
                View tab = entry.getKey();
                if (tab == null) continue;
                tab.setScaleX(entry.getValue()[0]);
                tab.setScaleY(entry.getValue()[1]);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) tab.setRenderEffect(null);
            }
            grown.clear();
        }

        /**
         * {@code lens}, in the bar's pixels, put in {@code out} in {@code tab}'s own pixels before
         * its move and scale, the space its bend is drawn in. False when the two don't meet.
         */
        static boolean inTab(View tab, RectF lens, RectF out) {
            float scaleX = Math.max(0.01f, tab.getScaleX()), scaleY = Math.max(0.01f, tab.getScaleY());
            float originX = tab.getLeft() + tab.getTranslationX() + tab.getPivotX();
            float originY = tab.getTop() + tab.getTranslationY() + tab.getPivotY();
            out.set(tab.getPivotX() + (lens.left - originX) / scaleX,
                    tab.getPivotY() + (lens.top - originY) / scaleY,
                    tab.getPivotX() + (lens.right - originX) / scaleX,
                    tab.getPivotY() + (lens.bottom - originY) / scaleY);
            return out.right > 0f && out.left < tab.getWidth() && out.bottom > 0f && out.top < tab.getHeight();
        }

        /** The bubble at {@link #shown} swollen to {@link #bubbleShown}, put in {@code out}; returns its corners. */
        private float swollen(RectF out) {
            float corner = cornerPx(Settings.GLASS_CORNERS.get(), true, shown.height(), density);
            out.set(shown);
            if (bubbleShown != 1f) {
                float swell = (bubbleShown - 1f) / 2f;
                out.inset(-out.width() * swell, -out.height() * swell);
                corner *= bubbleShown;
            }
            return corner;
        }

        /** A finger went down on the lens at {@code x}, in the bar's pixels ({@code now} true), or let go of it. */
        void hold(boolean now, float x) {
            pressX = now ? x : Float.NaN;
            hold(now);
        }

        /** A finger went down on the lens ({@code now} true) or let go of it. */
        void hold(boolean now) {
            if (now == held) return;
            long at = SystemClock.uptimeMillis();
            pressFrom = press(at);
            held = now;
            heldChange = at;
        }

        /** How pressed the lens is at {@code now}: 0 with Liquid glass when held off. */
        float press(long now) {
            if (!Settings.GLASS_LIQUID_HOLD.get() || heldChange == 0) return 0f;
            if (!animatorsEnabled()) return held ? 1f : 0f;
            return liquidPress(now - heldChange, held, pressFrom);
        }

        /** Whether the press is still swelling or wobbling, so the next frame has to draw. */
        private boolean pressMoving(long now) {
            if (!Settings.GLASS_LIQUID_HOLD.get() || heldChange == 0 || !animatorsEnabled()) return false;
            return now - heldChange < (held ? LIQUID_PRESS_MS : LIQUID_RELEASE_MS);
        }

        /**
         * The bubble at {@link #shown}, swollen to {@link #bubbleShown}: the magnified video where
         * the glass blurs it, the fill, then the sheen and the rim, all at {@code alpha}. A held
         * lens bends harder and its tint and fill clear a little, so the bend shows through.
         */
        private void paintBubble(Canvas canvas, ViewGroup bar, Rect area, int alpha, boolean onLight) {
            float corner = swollen(capsule);
            if (outline) {
                // TikTok's own bar is flat, so its lens is a line with nothing inside (#483).
                int color = onLight ? LENS_OUTLINE_ON_LIGHT : LENS_OUTLINE;
                outlinePaint.setColor(withAlpha(color, Color.alpha(color) * alpha / 255f));
                float half = outlinePaint.getStrokeWidth() / 2f;
                capsule.inset(half, half);
                canvas.drawRoundRect(capsule, Math.max(0f, corner - half), Math.max(0f, corner - half), outlinePaint);
                return;
            }
            float clear = 1f - 0.4f * Math.max(0f, pressNow);
            RenderNode backdrop = blur == null ? null : blur.drawnBackdrop();
            // Only at full strength: a faded bar would show the magnified video solid.
            if (backdrop != null && alpha == 255 && GlassLens.supported()) {
                if (magnifier == null) magnifier = new GlassLens();
                if (magnifier.paint(canvas, backdrop, area.left, area.top, bar.getWidth(), bar.getHeight(),
                        capsule, corner, pressNow)) {
                    // The pill's tint goes back over it so the bubble reads as part of the pill.
                    int glass = onLight ? GLASS_TINT_ON_LIGHT : GLASS_TINT;
                    magnifiedTint.setColor(withAlpha(glass,
                            Color.alpha(glass) * opacityScale(Settings.GLASS_OPACITY.get()) * clear));
                    canvas.drawRoundRect(capsule, corner, corner, magnifiedTint);
                }
            }
            int fill = onLight ? LENS_FILL_ON_LIGHT : LENS_FILL;
            lensFill.setColor(withAlpha(fill, Color.alpha(fill) * alpha / 255f * clear));
            canvas.drawRoundRect(capsule, corner, corner, lensFill);
            Paint rim = onLight ? bubbleRimOnLight : bubbleRim;
            shade.setScale(1f, Math.max(1f, capsule.height()));
            shade.postTranslate(0f, capsule.top);
            rim.getShader().setLocalMatrix(shade);
            rim.setAlpha(alpha);
            if (!onLight) {
                // A white sheen on a light bar is nothing but a smudge.
                bubbleSheen.getShader().setLocalMatrix(shade);
                bubbleSheen.setAlpha(alpha);
                canvas.drawRoundRect(capsule, corner, corner, bubbleSheen);
            }
            float half = rim.getStrokeWidth() / 2f;
            capsule.inset(half, half);
            canvas.drawRoundRect(capsule, Math.max(0f, corner - half), Math.max(0f, corner - half), rim);
        }

        /** With Glass haptics, a tick each time the lens's middle passes over a different tab. */
        private void tick(ViewGroup bar, float middle) {
            View over = null;
            float best = Float.MAX_VALUE;
            for (View tab : shownTabs(bar)) {
                float off = Math.abs(middle - ((tab.getLeft() + tab.getRight()) / 2f + tab.getTranslationX()));
                if (off < best) {
                    best = off;
                    over = tab;
                }
            }
            if (tickedTab != null && over != tickedTab && Settings.GLASS_HAPTICS.get()) {
                GlassBarHaptics.tick(bar, Settings.GLASS_HAPTIC_STYLE.get());
            }
            tickedTab = over;
        }

        /**
         * With Slide across the tabs, makes sure the bar's window hands its touches to a slide for
         * this bar. One slide serves every lens the bar wears ({@link LensHost}).
         */
        private static void keepSlide(ViewGroup bar) {
            if (!Settings.GLASS_TAB_SLIDE.get() && !Settings.GLASS_LIQUID_HOLD.get()) return;
            Activity activity = activityOf(bar.getContext());
            Window window = activity == null ? null : activity.getWindow();
            if (window == null) return;
            GlassTabSlide installed = GlassTabSlide.installedIn(window);
            if (installed != null && installed.bar() == bar) return;
            GlassTabSlide.install(window, new GlassTabSlide(bar, new LensHost(bar),
                    () -> Settings.GLASS_TAB_SLIDE.get() && bar.getBackground() instanceof LensCarrier,
                    () -> Settings.GLASS_LIQUID_HOLD.get() && bar.getBackground() instanceof LensCarrier));
        }

        /** Where the last draw put the lens; empty when that draw found no tab to put it behind. */
        RectF bounds() {
            return Float.isNaN(drawnLeft) ? new RectF() : new RectF(shown);
        }

        /** True when the selected tab isn't the one the last draw put the lens behind. */
        boolean selectionMoved(ViewGroup bar) {
            if (!selectedSpan(bar, span)) return !Float.isNaN(drawnLeft);
            return span.left != drawnLeft || span.right != drawnRight;
        }

        private boolean beforeFrame() {
            // A still screen draws nothing, so a lens still showing is as current as this frame,
            // whatever its last draw's time. The lens that replaces it (the bar has already
            // dropped this one's background by then) or this lens after a frame with no tab
            // slides on from it (S22, 2026-10-23: a lens jumped after a second on one tab).
            if (written != null && !Float.isNaN(drawnLeft)) written.at = SystemClock.uptimeMillis();
            ViewGroup bar = watch.bar();
            if (bar == null || bar.getBackground() != owner) {
                // Off the bar, so off the screen. A listener the window kept anyway (off() can't
                // reach one merged in from a detached observer) then leaves the memory alone.
                drawnLeft = drawnRight = Float.NaN;
                restoreTabs();
                watch.off();
                return true;
            }
            // Slide across the tabs turned on while the bar stands still: a plain pill over a
            // playing video never redraws, so the slide is hooked in on the window's next frame
            // instead of waiting for the bar's (#416, the first slide after the switch did nothing).
            keepSlide(bar);
            if (selectionMoved(bar)) owner.invalidateSelf();
            return true;
        }

        /**
         * A slide timed by the clock alone finishes unseen when the main thread stalls, as it does
         * while TikTok builds a tab's page the first time it opens: the frame after the stall
         * showed the lens already behind the new tab (S22, 2026-10-23: about 270 ms with no frame
         * after the first tap from Home onto Inbox, #231). A slide that was running at the last
         * draw carries on a frame past where that draw showed it. A gap past the bar's memory
         * means the bar was out of sight, and the slide is left to finish.
         */
        private void holdOverStall(long now) {
            long gap = now - lastDraw;
            if (lastDraw != 0 && gap > LENS_STALL_MS && gap < LENS_MEMORY_MS
                    && lastDraw - slideStart < LENS_SLIDE_MS) {
                slideStart += gap - LENS_FRAME_MS;
            }
            lastDraw = now;
        }

        private float progress(long now) {
            if (!animatorsEnabled()) return 1f;
            float t = Math.min(1f, Math.max(0f, (now - slideStart) / (float) LENS_SLIDE_MS));
            float rest = 1f - t;
            // Quick off the mark, easing into place.
            return 1f - rest * rest * rest;
        }

        private static float lerp(float from, float to, float done) {
            return from + (to - from) * done;
        }

        /** A lens's last drawn sides and the uptime it was drawn at. */
        private static final class LensSpot {
            float left, right;
            long at;
        }
    }

    /** The glass pill with the lens behind the selected tab (#231). */
    static final class GlassLensBackground extends GlassBackground implements LensCarrier {
        private final TabLens lens;

        GlassLensBackground(float density) {
            this(density, false);
        }

        GlassLensBackground(float density, boolean blurVideo) {
            super(density, blurVideo);
            lens = new TabLens(density, this, blur);
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            ViewGroup bar = bar();
            if (bar != null) lens.draw(canvas, bar, pillRect, getDrawable().getAlpha(), onLight);
        }

        @Override
        public TabLens lens() {
            return lens;
        }

        @Override
        public Rect lensArea() {
            return pillRect;
        }

        RectF lensBounds() {
            return lens.bounds();
        }

        boolean selectionMoved(ViewGroup bar) {
            return lens.selectionMoved(bar);
        }
    }

    /**
     * The plain floating pill with the lens behind the selected tab (#416), drawn as the thin
     * outline (#483): the frosted bubble read as a gray block on the see-through pill.
     */
    static final class FloatingLensBackground extends FloatingBackground implements LensCarrier {
        private final TabLens lens;

        FloatingLensBackground(float density) {
            super(density);
            lens = new TabLens(density, this, null, true);
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            ViewGroup bar = bar();
            if (bar != null) lens.draw(canvas, bar, pillRect, getDrawable().getAlpha(), onLight);
        }

        @Override
        public TabLens lens() {
            return lens;
        }

        @Override
        public Rect lensArea() {
            return pillRect;
        }
    }

    /**
     * Where a lens over the bar's whole width stays: the bar's {@code bounds} less its top and
     * bottom padding, put in {@code out}.
     */
    static Rect tabArea(ViewGroup bar, Rect bounds, Rect out) {
        out.set(bounds.left, bounds.top + bar.getPaddingTop(), bounds.right, bounds.bottom - bar.getPaddingBottom());
        if (out.height() <= 0) out.set(bounds);
        return out;
    }

    /** True for a solid, light {@code color}, where the white lens wouldn't show and a dark one goes instead. */
    static boolean isLight(int color) {
        if (Color.alpha(color) < 0x80) return false;
        return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color) > 0.6f * 255f;
    }

    /**
     * True when TikTok draws {@code bar}'s tabs for a light bar, in dark ink, as over Home's
     * Community grid (#436), so a pill under them goes light. Read from the tabs' shown labels,
     * most of them deciding so a badge's white number can't; with none, from TikTok's own
     * background this class holds.
     */
    static boolean tabsOnLight(ViewGroup bar) {
        int[] inks = new int[2];
        countInks(bar, 0, inks);
        if (inks[0] + inks[1] > 0) return inks[0] > inks[1];
        return isLight(BACKGROUNDS.get(bar));
    }

    /** Counts {@code group}'s shown labels a few levels down: dark ink in [0], the rest in [1]. */
    private static void countInks(ViewGroup group, int depth, int[] inks) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof TextView) {
                if (((TextView) child).length() > 0) inks[isDarkInk(((TextView) child).getCurrentTextColor()) ? 0 : 1]++;
            } else if (child instanceof ViewGroup && depth < 4) {
                countInks((ViewGroup) child, depth + 1, inks);
            }
        }
    }

    /** True for dark ink at any strength: TikTok dims its unpicked tabs' labels, black or white. */
    static boolean isDarkInk(int color) {
        if (Color.alpha(color) == 0) return false;
        return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color) < 0.4f * 255f;
    }

    /** Whether {@code background} is light, as far as its color can be read. */
    static boolean isLight(Drawable background) {
        if (background instanceof ColorDrawable) return isLight(((ColorDrawable) background).getColor());
        if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.ColorStateList colors = ((GradientDrawable) background).getColor();
            return colors != null && isLight(colors.getDefaultColor());
        }
        return false;
    }

    /**
     * A solid color with the lens over it (#416): the clear bar's transparent one, or TikTok's
     * own bar color. A ColorDrawable still, so TikTok setting the bar's color recolors this one
     * as it would its own, and the color it set goes back with TikTok's background.
     */
    static final class ColorLensBackground extends ColorDrawable implements LensCarrier {
        /** True when this stands in for See-through bottom bar's clear background. */
        final boolean clear;
        private final TabLens lens;
        private final Rect area = new Rect();
        /** The color last set, before any alpha, and the alpha the bar's fade gave it. */
        private int base;
        private int fade = 255;

        ColorLensBackground(int color, boolean clear, float density) {
            super(color);
            base = color;
            this.clear = clear;
            // TikTok's own bar gets the outline (#483), the clear bar over the video the bubble.
            lens = new TabLens(density, this, null, !clear);
        }

        @Override
        public void setColor(int color) {
            base = color;
            super.setColor(color);
        }

        /** The color last set, before any alpha. */
        int baseColor() {
            return base;
        }

        @Override
        public void setAlpha(int alpha) {
            fade = alpha;
            super.setAlpha(alpha);
        }

        /** Never transparent: the lens still draws over a clear color. */
        @Override
        public int getOpacity() {
            int opacity = super.getOpacity();
            return opacity == PixelFormat.TRANSPARENT ? PixelFormat.TRANSLUCENT : opacity;
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            ViewGroup bar = barOf(this);
            if (bar != null) lens.draw(canvas, bar, tabArea(bar, getBounds(), area), fade, !clear && isLight(base));
        }

        @Override
        public TabLens lens() {
            return lens;
        }

        @Override
        public Rect lensArea() {
            ViewGroup bar = barOf(this);
            return bar == null ? area : tabArea(bar, getBounds(), area);
        }
    }

    /**
     * TikTok's own bar background, whatever it is, with the lens over it (#416). A wrapper, so
     * its padding, state and look stay TikTok's.
     */
    static final class LensOverBackground extends InsetDrawable implements LensCarrier {
        private final TabLens lens;
        private final Rect area = new Rect();
        private int fade = 255;

        LensOverBackground(Drawable theirs, float density) {
            super(theirs, 0);
            lens = new TabLens(density, this, null, true);
        }

        @Override
        public void setAlpha(int alpha) {
            fade = alpha;
            super.setAlpha(alpha);
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            ViewGroup bar = barOf(this);
            if (bar != null) {
                lens.draw(canvas, bar, tabArea(bar, getBounds(), area), fade, isLight(getDrawable()));
            }
        }

        @Override
        public TabLens lens() {
            return lens;
        }

        @Override
        public Rect lensArea() {
            ViewGroup bar = barOf(this);
            return bar == null ? area : tabArea(bar, getBounds(), area);
        }
    }

    /** What Slide across the tabs moves: whichever lens the bar wears now, since a new look hands it a new one. */
    private static final class LensHost implements GlassTabSlide.Host {
        private final ViewGroup bar;

        LensHost(ViewGroup bar) {
            this.bar = bar;
        }

        @Override
        public boolean onPill(float x, float y) {
            Drawable background = bar.getBackground();
            return background instanceof LensCarrier
                    && ((LensCarrier) background).lensArea().contains(Math.round(x), Math.round(y));
        }

        @Override
        public void follow(float x) {
            Drawable background = bar.getBackground();
            if (!(background instanceof LensCarrier)) return;
            ((LensCarrier) background).lens().dragX = x;
            background.invalidateSelf();
        }

        @Override
        public void hold(boolean held, float x) {
            Drawable background = bar.getBackground();
            if (!(background instanceof LensCarrier)) return;
            ((LensCarrier) background).lens().hold(held, x);
            background.invalidateSelf();
        }
    }

    /** The activity {@code context} belongs to, or null. */
    static Activity activityOf(Context context) {
        for (int depth = 0; context != null && depth < 10; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** False when the phone's animator duration scale is 0, so the lens jumps instead of sliding. */
    static boolean animatorsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
    }

    /** The first shown, selected child's span in the bar's coordinates, or false when there's none. */
    static boolean selectedSpan(ViewGroup bar, RectF out) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE || !tab.isSelected() || tab.getWidth() == 0) continue;
            float left = tab.getLeft() + tab.getTranslationX() - bar.getScrollX();
            out.set(left, tab.getTop(), left + tab.getWidth(), tab.getBottom());
            return true;
        }
        return false;
    }

    /**
     * What a bar's background is: TikTok's own, or one this class draws. The lens kinds carry the
     * lens behind the selected tab (#231, #416), THEIRS_LENS over TikTok's own background.
     */
    private static final int THEIRS = 0, CLEAR = 1, FLOATING = 2, GLASS = 3, GLASS_LENS = 4,
            THEIRS_LENS = 5, CLEAR_LENS = 6, FLOATING_LENS = 7;

    private static int kind(Drawable background) {
        if (background instanceof GlassLensBackground) return GLASS_LENS;
        if (background instanceof GlassBackground) return GLASS;
        if (background instanceof FloatingLensBackground) return FLOATING_LENS;
        if (background instanceof FloatingBackground) return FLOATING;
        if (background instanceof ColorLensBackground) {
            return ((ColorLensBackground) background).clear ? CLEAR_LENS : THEIRS_LENS;
        }
        if (background instanceof LensOverBackground) return THEIRS_LENS;
        if (background instanceof ClearBackground) return CLEAR;
        return THEIRS;
    }

    /** True for a glass background that blurs the video behind it. */
    private static boolean blurs(Drawable background) {
        return background instanceof GlassBackground && ((GlassBackground) background).blur != null;
    }

    /**
     * Hands the color TikTok gave the bar while the lens rode over it back to TikTok's own
     * background, so the bar goes back the color TikTok last asked for.
     */
    private static void carryColor(Drawable current, Drawable original) {
        if (!(current instanceof ColorLensBackground) || ((ColorLensBackground) current).clear) return;
        if (!(original instanceof ColorDrawable)) return;
        int color = ((ColorLensBackground) current).baseColor();
        if (((ColorDrawable) original).getColor() != color) ((ColorDrawable) original).setColor(color);
    }

    /** Bars whose background this class replaced, with the drawable each had. */
    private static final Map<View, Drawable> BACKGROUNDS = new WeakHashMap<>();

    /**
     * Overlays (the caption frame, the buttons' column) measured with the spacer in place: true
     * when one sat clear of the bar then. Only an overlay that was fine before the collapse can
     * be blamed for being under the bar after it.
     */
    private static final Map<View, Boolean> CLEAR_BEFORE = new WeakHashMap<>();

    /** Set once a collapse put an overlay under the bar; the spacer then stays as TikTok has it. */
    private static boolean collapseRefused;

    /** The overlay the refusal blamed and where it sat against the bar, for the debug log. */
    private static String blamed = "";

    private SeeThroughBottomBar() {
    }

    static String blamed() {
        return blamed;
    }

    /** Whether the spacer under the video may be collapsed. */
    static boolean collapseAllowed() {
        return !collapseRefused;
    }

    /** True while a bar's background is held, so the layout pass keeps running to put it back. */
    static boolean holdsBackground() {
        return !BACKGROUNDS.isEmpty();
    }

    /** Forgets the verdict and the measurements once the switch is off. */
    static void reset() {
        collapseRefused = false;
        CLEAR_BEFORE.clear();
    }

    /**
     * Swaps the bar's background for a clear one, or puts back the one this class took. A
     * background TikTok set since is never overwritten on the way back.
     */
    static void setBackgroundClear(View bar, boolean clear) {
        setBackgroundClear(bar, clear, false);
    }

    /**
     * The same, with {@code floating} drawing the floating pill (#124) in place of the clear
     * background. A bar with no background of its own is a layout this wasn't written for, and
     * keeps TikTok's look.
     */
    static void setBackgroundClear(View bar, boolean clear, boolean floating) {
        setBackgroundClear(bar, clear, floating, false);
    }

    /** The same, with {@code glass} drawing the floating pill as glass (#218). */
    static void setBackgroundClear(View bar, boolean clear, boolean floating, boolean glass) {
        setBackgroundClear(bar, clear, floating, glass, false);
    }

    /**
     * The same, with {@code lens} adding the lens that follows the selected tab (#231): on the
     * glass pill, the plain pill or the clear bar, and with {@code clear} false over TikTok's own
     * bar (#416).
     */
    static void setBackgroundClear(View bar, boolean clear, boolean floating, boolean glass, boolean lens) {
        setBackgroundClear(bar, clear, floating, glass, lens, false);
    }

    /** The same, with {@code blur} blurring the video behind the glass where the phone can (#231). */
    static void setBackgroundClear(View bar, boolean clear, boolean floating, boolean glass, boolean lens,
            boolean blur) {
        if (bar == null) return;
        blur &= GlassVideoBlur.supported();
        Drawable current = bar.getBackground();
        int kind = kind(current);
        float density = bar.getResources().getDisplayMetrics().density;
        if (clear) {
            if (current == null) return;
            int wanted = !floating ? (lens ? CLEAR_LENS : CLEAR)
                    : !glass ? (lens ? FLOATING_LENS : FLOATING)
                    : lens ? GLASS_LENS : GLASS;
            letPillRise(bar, floating);
            if (kind == wanted && (!(current instanceof GlassBackground) || blurs(current) == blur)) return;
            if (kind == THEIRS) BACKGROUNDS.put(bar, current);
            else carryColor(current, BACKGROUNDS.get(bar));
            bar.setBackground(wanted == GLASS_LENS ? new GlassLensBackground(density, blur)
                    : wanted == GLASS ? new GlassBackground(density, blur)
                    : wanted == FLOATING_LENS ? new FloatingLensBackground(density)
                    : wanted == FLOATING ? new FloatingBackground(density)
                    : wanted == CLEAR_LENS ? new ColorLensBackground(Color.TRANSPARENT, true, density)
                    : new ClearBackground());
            if (!floating && bar instanceof ViewGroup) putTabsBack((ViewGroup) bar);
            return;
        }
        letPillRise(bar, false);
        if (lens) {
            // TikTok's own bar with the lens over it (#416).
            if (current == null || kind == THEIRS_LENS) return;
            Drawable theirs = kind == THEIRS ? current : BACKGROUNDS.get(bar);
            if (theirs == null) return;
            BACKGROUNDS.put(bar, theirs);
            Drawable over = theirs instanceof ColorDrawable
                    ? new ColorLensBackground(((ColorDrawable) theirs).getColor(), false, density)
                    : new LensOverBackground(theirs, density);
            bar.setBackground(over);
            // Swapping it out cleared the wrapped background's callback, so its redraws reach the bar again.
            if (over instanceof LensOverBackground) theirs.setCallback((LensOverBackground) over);
            if (bar instanceof ViewGroup) putTabsBack((ViewGroup) bar);
            return;
        }
        if (!BACKGROUNDS.containsKey(bar)) return;
        Drawable original = BACKGROUNDS.remove(bar);
        carryColor(current, original);
        if (kind != THEIRS) bar.setBackground(original);
        if (bar instanceof ViewGroup) putTabsBack((ViewGroup) bar);
    }

    /** Bar parents this class stopped clipping, so a pill could stand above its bar. */
    private static final Map<ViewGroup, Boolean> UNCLIPPED = new WeakHashMap<>();

    /**
     * Lets the pill draw above {@code bar} ({@link #PILL_RISE_DP}) by having the bar's parent stop
     * clipping its children, or with {@code rise} false clips them again where this class stopped
     * it. TikTok's bar parent spans the whole feed (S22, 47.5.3), so nothing past it shows.
     */
    static void letPillRise(View bar, boolean rise) {
        if (!(bar.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) bar.getParent();
        if (rise) {
            if (!parent.getClipChildren()) return;
            parent.setClipChildren(false);
            UNCLIPPED.put(parent, true);
        } else if (UNCLIPPED.remove(parent) != null && !parent.getClipChildren()) {
            parent.setClipChildren(true);
        }
    }

    /**
     * Compares the overlays with the bar. With the spacer in place ({@code collapsed} false) it
     * notes which overlays sit clear of the bar; once the spacer is collapsed it reports true, and
     * remembers, when one that was clear has moved under it. Views not laid out, hidden, or
     * entirely above or below the bar (a neighbouring cell) are not judged.
     */
    static boolean judge(View bar, List<View> overlays, boolean collapsed) {
        if (bar == null || !bar.isShown() || bar.getHeight() == 0) return false;
        float barTop = topInWindow(bar);
        for (View overlay : overlays) {
            if (overlay == null || !overlay.isShown() || overlay.getHeight() == 0) continue;
            float top = topInWindow(overlay);
            boolean under = reachesUnder(top, top + overlay.getHeight(), barTop);
            if (!collapsed) {
                CLEAR_BEFORE.put(overlay, !under);
            } else if (under && Boolean.TRUE.equals(CLEAR_BEFORE.get(overlay))) {
                collapseRefused = true;
                blamed = "0x" + Integer.toHexString(overlay.getId()) + " at " + Math.round(top) + "-"
                        + Math.round(top + overlay.getHeight()) + ", bar top " + Math.round(barTop);
                return true;
            }
        }
        return false;
    }

    /** An overlay that starts above the bar's top edge and ends below it. */
    static boolean reachesUnder(float top, float bottom, float barTop) {
        return top < barTop && bottom > barTop + SLACK_PX;
    }

    static float topInWindow(View view) {
        float top = view.getTop() + view.getTranslationY();
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View group = (View) parent;
            top += group.getTop() - group.getScrollY() + group.getTranslationY();
            parent = group.getParent();
        }
        return top;
    }
}
