/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
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
     * The floating pill's proportions, HushGram's glass tab bar's: 5% of the bar's width clear on
     * each side, at least 16dp, and never wider than 420dp on a wide screen.
     */
    static final float PILL_SIDE_SHARE = 0.05f;
    static final int PILL_MIN_SIDE_DP = 16, PILL_MAX_WIDTH_DP = 420;
    /** The clear space above and under the pill, in dp: a hairline, so the pill is as tall as the bar. */
    static final int PILL_GAP_DP = 1;
    /** How much slimmer a compact pill is on each side, and the shortest one is drawn, in dp. */
    static final int COMPACT_INSET_DP = 4, MIN_PILL_DP = 40;
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
     * The pill inside a bar with {@code bar} bounds, put in {@code out}: in from the sides by
     * {@link #sidePx}, a hairline in from the top and the bottom, and for a {@code compact} pill
     * {@link #COMPACT_INSET_DP} more, less if that would leave it shorter than {@link #MIN_PILL_DP}.
     */
    static void pillBounds(Rect bar, float density, boolean compact, Rect out) {
        int side = sidePx(bar.width(), density);
        int gap = Math.round(PILL_GAP_DP * density);
        int inset = 0;
        if (compact) {
            int room = (bar.height() - 2 * gap - Math.round(MIN_PILL_DP * density)) / 2;
            inset = Math.max(0, Math.min(Math.round(COMPACT_INSET_DP * density), room));
        }
        out.set(bar.left + side, bar.top + gap + inset, bar.right - side, bar.bottom - gap - inset);
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
     * <p>With Hide the bottom tab names the name under each icon is gone, but TikTok keeps the
     * icon where it sat above the name, so the icons rode high in the pill with the empty room
     * under them. Each tab then also moves up or down so its icon sits in the pill's middle.
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
            offset(tab, Float.isNaN(icon) ? 0f : Math.round(pill.exactCenterY() - (tab.getTop() + icon)),
                    LIFTED, false);
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
     * isn't text, so a count badge or a name never counts. NaN when it has none.
     */
    static float iconMiddle(View tab) {
        float[] best = {0f, Float.NaN};
        largestPicture(tab, 0f, best, 0);
        return best[1];
    }

    private static void largestPicture(View view, float top, float[] best, int depth) {
        if (view.getVisibility() != View.VISIBLE || depth > 8) return;
        if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                largestPicture(child, top + child.getTop() + child.getTranslationY(), best, depth + 1);
            }
            return;
        }
        if (view instanceof android.widget.TextView) return;
        float area = (float) view.getWidth() * view.getHeight();
        if (area > best[0]) {
            best[0] = area;
            best[1] = top + view.getHeight() / 2f;
        }
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
        private final ViewTreeObserver.OnPreDrawListener frame = this::beforeFrame;
        private ViewTreeObserver watched;
        /** The bar this listens on, kept after the bar drops this as its background. */
        private WeakReference<ViewGroup> watchedBar = new WeakReference<>(null);

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
                watch(bar);
                // Also here, so the first frame already has them in place.
                placeTabs(bar, pillRect, density);
            }
            drawBehindPill(canvas);
            super.draw(canvas);
        }

        /** What goes under the pill: nothing for the plain one. */
        void drawBehindPill(Canvas canvas) {
        }

        /**
         * Before each frame: spreads the tabs over the pill, and lets go of the bar once it has
         * another background, giving the tabs back their places unless that is another pill.
         */
        boolean beforeFrame() {
            ViewGroup bar = watchedBar.get();
            if (bar == null || bar.getBackground() != this) {
                if (bar != null && !(bar.getBackground() instanceof FloatingBackground)) putTabsBack(bar);
                unwatch();
                return true;
            }
            placeTabs(bar, pillRect, density);
            return true;
        }

        ViewGroup bar() {
            Callback callback = getCallback();
            return callback instanceof ViewGroup ? (ViewGroup) callback : null;
        }

        private void watch(ViewGroup bar) {
            ViewTreeObserver observer = bar.getViewTreeObserver();
            if (observer == watched || !observer.isAlive()) return;
            unwatch();
            // Attaching the bar merges its detached observer's listeners into the window's, so
            // the listener may already be there: never hold it twice.
            observer.removeOnPreDrawListener(frame);
            observer.addOnPreDrawListener(frame);
            watched = observer;
            watchedBar = new WeakReference<>(bar);
        }

        private void unwatch() {
            if (watched != null && watched.isAlive()) watched.removeOnPreDrawListener(frame);
            watched = null;
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
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int alpha = 255;

        GlassPill(float density) {
            this.density = density;
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
                    new int[]{GLASS_RIM_TOP, GLASS_RIM_SIDE, GLASS_RIM_BOTTOM}, new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            box.set(bounds);
            float radius = cornerPx(Settings.GLASS_CORNERS.get(), false, box.height(), density);
            // Glass opacity scales the tint and, up to its full strength, the frost; a shader's
            // colors are scaled by its paint's alpha, the tint's own alpha is scaled here.
            float scale = opacityScale(Settings.GLASS_OPACITY.get());
            tint.setColor(withAlpha(GLASS_TINT, Color.alpha(GLASS_TINT) * alpha / 255f * scale));
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
    /** How far the lens stays in from the pill's top, bottom and ends, and how far past the tab's slot it stands, in dp. */
    static final int LENS_INSET_DP = 4, LENS_GROW_DP = 4;
    /** How much larger the bubble is midway through a slide and under a finger. */
    static final float BUBBLE_SWELL = 0.08f;
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
     * Where the lens's left edge goes under a finger sliding across the tabs: {@code width} wide,
     * centered on {@code finger}, kept between {@code min} and {@code max}.
     */
    static float followLeft(float finger, float width, float min, float max) {
        if (max - min <= width) return min;
        return Math.max(min, Math.min(max - width, finger - width / 2f));
    }

    /**
     * Glass lens (#231): the glass pill with a raised bubble of glass behind the tab you're on,
     * sliding to the next tab you pick, drawn the way HushGram's liquid glass tab bar draws it: a
     * lighter fill, a sheen and a rim brightest along its top, a little swell while it moves, and
     * with Blur the video behind the bar, the blurred video under it magnified ({@link GlassLens}).
     * TikTok marks the current tab with View.isSelected() and nothing else (S22, 47.4.3: no
     * indicator view, no alpha), so the lens follows the bar's first selected child. A bar with
     * no selected tab shows the glass pill alone. The lens is part of the bar's background, so it
     * sits under the tabs and never takes a tap. A selection change redraws no background by
     * itself, so the pre-draw listener asks for one when the selected tab moved.
     * Picking a tab can also hand the bar a new lens (the overlay pass swaps the background when
     * the bar's look changes), and a new lens has no slide of its own to continue, so each bar
     * remembers where its last lens was drawn and a new one slides on from there when that was
     * moments ago (S22, 2026-10-21: the lens jumped between tabs with animations on). With Slide
     * across the tabs the lens follows a finger ({@link GlassTabSlide}), and with Glass haptics
     * it ticks each time its middle passes a tab ({@link GlassBarHaptics}).
     */
    static final class GlassLensBackground extends GlassBackground {
        /** Where each bar's lens was last drawn, kept only as long as the bar is. */
        private static final Map<View, LensSpot> LAST_SPOT = new WeakHashMap<>();
        private final Paint lensFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint magnifiedTint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbleSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbleRim = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        /** Where a finger sliding across the tabs holds the lens's middle, NaN without one. */
        float dragX = Float.NaN;
        /** The tab the lens's middle was over at the last draw, null before the first. */
        private View tickedTab;

        GlassLensBackground(float density) {
            this(density, false);
        }

        GlassLensBackground(float density, boolean blurVideo) {
            super(density, blurVideo);
            bubbleSheen.setShader(new LinearGradient(0, 0, 0, 1, LENS_SHEEN, LENS_SHEEN & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            bubbleRim.setStyle(Paint.Style.STROKE);
            bubbleRim.setStrokeWidth(Math.max(1f, density * 1.25f));
            bubbleRim.setShader(new LinearGradient(0, 0, 0, 1, LENS_RIM_TOP, LENS_RIM_BOTTOM,
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            ViewGroup bar = bar();
            if (bar == null) return;
            keepSlide(bar);
            Rect pill = pillRect;
            if (pill.isEmpty() || !selectedSpan(bar, span)
                    || !lensSpan(span, pill, shownTabs(bar).size(), density, target)) {
                drawnLeft = drawnRight = Float.NaN;
                return;
            }
            float left = target.left, right = target.right;
            float inset = LENS_INSET_DP * density;
            long now = SystemClock.uptimeMillis();
            holdOverStall(now);
            if (!Float.isNaN(dragX)) {
                // Under a finger: on it, swollen, and no slide of its own.
                float width = right - left;
                left = followLeft(dragX, width, pill.left + inset, pill.right - inset);
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
            shown.set(lerp(fromLeft, toLeft, done), target.top, lerp(fromRight, toRight, done), target.bottom);
            LensSpot spot = LAST_SPOT.get(bar);
            if (spot == null) LAST_SPOT.put(bar, spot = new LensSpot());
            written = spot;
            spot.left = shown.left;
            spot.right = shown.right;
            spot.at = now;
            tick(bar, shown.centerX());
            paintBubble(canvas, bar, pill);
            if (done < 1f || bubbleNow != 1f) invalidateSelf();
        }

        /**
         * The bubble at {@link #shown}, swollen by {@link #bubbleNow}: the magnified video where
         * the pill blurs it, the fill, then the sheen and the rim, all at the pill's alpha.
         */
        private void paintBubble(Canvas canvas, ViewGroup bar, Rect pill) {
            int alpha = getDrawable().getAlpha();
            float corner = cornerPx(Settings.GLASS_CORNERS.get(), true, shown.height(), density);
            capsule.set(shown);
            if (bubbleNow != 1f) {
                float swell = (bubbleNow - 1f) / 2f;
                capsule.inset(-capsule.width() * swell, -capsule.height() * swell);
                corner *= bubbleNow;
            }
            RenderNode backdrop = blur == null ? null : blur.drawnBackdrop();
            // Only at full strength: a faded bar would show the magnified video solid.
            if (backdrop != null && alpha == 255 && GlassLens.supported()) {
                if (magnifier == null) magnifier = new GlassLens();
                if (magnifier.paint(canvas, backdrop, pill.left, pill.top, bar.getWidth(), bar.getHeight(),
                        capsule, corner)) {
                    // The pill's tint goes back over it so the bubble reads as part of the pill.
                    magnifiedTint.setColor(withAlpha(GLASS_TINT,
                            Color.alpha(GLASS_TINT) * opacityScale(Settings.GLASS_OPACITY.get())));
                    canvas.drawRoundRect(capsule, corner, corner, magnifiedTint);
                }
            }
            lensFill.setColor(withAlpha(LENS_FILL, Color.alpha(LENS_FILL) * alpha / 255f));
            canvas.drawRoundRect(capsule, corner, corner, lensFill);
            shade.setScale(1f, Math.max(1f, capsule.height()));
            shade.postTranslate(0f, capsule.top);
            bubbleSheen.getShader().setLocalMatrix(shade);
            bubbleRim.getShader().setLocalMatrix(shade);
            bubbleSheen.setAlpha(alpha);
            bubbleRim.setAlpha(alpha);
            canvas.drawRoundRect(capsule, corner, corner, bubbleSheen);
            float half = bubbleRim.getStrokeWidth() / 2f;
            capsule.inset(half, half);
            canvas.drawRoundRect(capsule, Math.max(0f, corner - half), Math.max(0f, corner - half), bubbleRim);
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
        private void keepSlide(ViewGroup bar) {
            if (!Settings.GLASS_TAB_SLIDE.get()) return;
            Activity activity = activityOf(bar.getContext());
            Window window = activity == null ? null : activity.getWindow();
            if (window == null) return;
            GlassTabSlide installed = GlassTabSlide.installedIn(window);
            if (installed != null && installed.bar() == bar) return;
            GlassTabSlide.install(window, new GlassTabSlide(bar, new LensHost(bar),
                    () -> Settings.GLASS_TAB_SLIDE.get() && bar.getBackground() instanceof GlassLensBackground));
        }

        /** Where the last draw put the lens; empty when that draw found no tab to put it behind. */
        RectF lensBounds() {
            return Float.isNaN(drawnLeft) ? new RectF() : new RectF(shown);
        }

        /** True when the selected tab isn't the one the last draw put the lens behind. */
        boolean selectionMoved(ViewGroup bar) {
            if (!selectedSpan(bar, span)) return !Float.isNaN(drawnLeft);
            return span.left != drawnLeft || span.right != drawnRight;
        }

        @Override
        boolean beforeFrame() {
            // A still screen draws nothing, so a lens still showing is as current as this frame,
            // whatever its last draw's time. The lens that replaces it (the bar has already
            // dropped this one as its callback by then) or this lens after a frame with no tab
            // slides on from it (S22, 2026-10-23: a lens jumped after a second on one tab).
            if (written != null && !Float.isNaN(drawnLeft)) written.at = SystemClock.uptimeMillis();
            super.beforeFrame();
            ViewGroup bar = bar();
            if (bar == null || bar.getBackground() != this) {
                // Off the bar, so off the screen. A listener the window kept anyway (unwatch can't
                // reach one merged in from a detached observer) then leaves the memory alone.
                drawnLeft = drawnRight = Float.NaN;
                return true;
            }
            if (selectionMoved(bar)) invalidateSelf();
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

    /** What Slide across the tabs moves: whichever lens the bar wears now, since a new look hands it a new one. */
    private static final class LensHost implements GlassTabSlide.Host {
        private final ViewGroup bar;

        LensHost(ViewGroup bar) {
            this.bar = bar;
        }

        @Override
        public boolean onPill(float x, float y) {
            Drawable background = bar.getBackground();
            return background instanceof GlassLensBackground
                    && ((GlassLensBackground) background).pillRect.contains(Math.round(x), Math.round(y));
        }

        @Override
        public void follow(float x) {
            Drawable background = bar.getBackground();
            if (!(background instanceof GlassLensBackground)) return;
            ((GlassLensBackground) background).dragX = x;
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

    /** What a bar's background is: TikTok's own, or one of the four this class draws. */
    private static final int THEIRS = 0, CLEAR = 1, FLOATING = 2, GLASS = 3, GLASS_LENS = 4;

    private static int kind(Drawable background) {
        if (background instanceof GlassLensBackground) return GLASS_LENS;
        if (background instanceof GlassBackground) return GLASS;
        if (background instanceof FloatingBackground) return FLOATING;
        if (background instanceof ClearBackground) return CLEAR;
        return THEIRS;
    }

    /** True for a glass background that blurs the video behind it. */
    private static boolean blurs(Drawable background) {
        return background instanceof GlassBackground && ((GlassBackground) background).blur != null;
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

    /** The same, with {@code lens} adding the glass lens that follows the selected tab (#231). */
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
        if (clear) {
            if (current == null) return;
            int wanted = !floating ? CLEAR : !glass ? FLOATING : lens ? GLASS_LENS : GLASS;
            if (kind == wanted && (wanted < GLASS || blurs(current) == blur)) return;
            if (kind == THEIRS) BACKGROUNDS.put(bar, current);
            float density = bar.getResources().getDisplayMetrics().density;
            bar.setBackground(wanted == GLASS_LENS ? new GlassLensBackground(density, blur)
                    : wanted == GLASS ? new GlassBackground(density, blur)
                    : wanted == FLOATING ? new FloatingBackground(density)
                    : new ClearBackground());
            if (wanted == CLEAR && bar instanceof ViewGroup) putTabsBack((ViewGroup) bar);
            return;
        }
        if (!BACKGROUNDS.containsKey(bar)) return;
        Drawable original = BACKGROUNDS.remove(bar);
        if (kind != THEIRS) bar.setBackground(original);
        if (bar instanceof ViewGroup) putTabsBack((ViewGroup) bar);
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
