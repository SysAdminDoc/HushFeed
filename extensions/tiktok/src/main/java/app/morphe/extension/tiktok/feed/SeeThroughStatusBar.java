/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The window and video halves of See-through status bar (#145). VideoOverlayHider collapses the
 * spacer TikTok keeps above each feed video for the bar, so the picture reaches the top edge; this
 * clears the bar's own color while the feed or an opened video is in front, so the picture shows
 * through it (opened videos since #407). Android 15 and up draw every app's bar see through already
 * and ignore the color, so there it's only the spacer.
 *
 * <p>Shade behind the status bar (#407) lays a fade in the window's overlay, twice the bar's
 * height, dark at the top and clear at the bottom, so the clock stays readable over a bright video.
 * It's drawn over everything in the window, so it comes off the moment the bar isn't see through.
 *
 * <p>Nothing else about the window changes: no flags, no insets, no cutout mode. The color TikTok
 * last gave the bar is kept, and given back when the feed leaves the front, the switch goes off or
 * Hushfeed is paused, unless TikTok has set another color since.
 *
 * <p>TikTok sizes each video's surface to the picture and centers it in the cell's player, so on
 * a tall screen a 9:16 video still left a black band under the clock with the spacer gone (S22: a
 * 1107 by 1968 surface in a 1080 by 2181 player, 106 px above and below). {@link #fill} grows a
 * portrait surface until it covers the player, trimming a little off each side: from its center,
 * or from the point that covers both edges when TikTok moved the surface ({@link #fillPivot}).
 * See-through bottom bar asks for the same fill on its own, for the band under the picture (#218).
 */
public final class SeeThroughStatusBar {
    /**
     * The most a picture is grown. 1.11 fills the S22's player and trims about 70 px off each side;
     * past 1.2 a picture loses too much of its sides, so it keeps TikTok's bands instead.
     */
    static final float MAX_FILL_SCALE = 1.2f;
    /** Windows whose bar this cleared, with the color TikTok last gave it. */
    private static final Map<Window, Integer> CLEARED = new WeakHashMap<>();
    /** Windows the status bar's fade lies over, with the fade. */
    private static final Map<Window, Drawable> SHADES = new WeakHashMap<>();
    /** The fade from the top edge down: half black, a quarter, then clear. */
    private static final int[] SHADE_COLORS = {0x80000000, 0x40000000, 0x00000000};
    /** Video surfaces and players this grew, with the scale it wrote. */
    private static final Map<View, Float> FILLED = new WeakHashMap<>();
    /** Surfaces whose pivot this moved off their center, so it goes back with the scale. */
    private static final Set<View> PIVOTED = Collections.newSetFromMap(new WeakHashMap<>());
    /** Players the last pass filled, with their cell's cover id, so the pre-draw pass fills them again. */
    private static final Map<View, Integer> PLAYERS = new WeakHashMap<>();
    /** The pre-draw pass's copy of {@link #PLAYERS}, kept so a frame allocates nothing. */
    private static final List<View> REFILLING = new ArrayList<>();
    /** How many scales {@link #grow} has written, so the pre-draw pass can tell whether it changed any. */
    private static int scaleWrites;

    private SeeThroughStatusBar() {
    }

    /** One layout pass: clears the bar while {@code wanted}, and gives its color back otherwise. */
    static void apply(Activity activity, boolean wanted) {
        Window window = activity.getWindow();
        if (window == null) return;
        int color = window.getStatusBarColor();
        if (wanted) {
            if (color == Color.TRANSPARENT) return;
            // TikTok's own color, kept even when it set a new one while the bar was clear.
            CLEARED.put(window, color);
            window.setStatusBarColor(Color.TRANSPARENT);
            return;
        }
        Integer own = CLEARED.remove(window);
        if (own != null && color == Color.TRANSPARENT) window.setStatusBarColor(own);
    }

    /** Whether this has cleared the bar of {@code activity}'s window. */
    static boolean isCleared(Activity activity) {
        Window window = activity.getWindow();
        return window != null && CLEARED.containsKey(window);
    }

    /**
     * One layout pass of Shade behind the status bar (#407): while {@code wanted}, a dark fade lies
     * over the top of the window, half black under the clock and clear at twice the bar's height,
     * so the clock and icons stay readable over a bright video. It sits in the window's overlay,
     * over the picture and TikTok's top row alike, and moves no view. A phone that picks dark or
     * light icons from what's under them reads the fade too. Otherwise it comes off.
     */
    static void shade(Activity activity, boolean wanted) {
        Window window = activity.getWindow();
        if (window == null) return;
        View decor = window.peekDecorView();
        if (decor == null) return;
        Drawable shade = SHADES.get(window);
        if (!wanted) {
            if (shade != null) {
                decor.getOverlay().remove(shade);
                SHADES.remove(window);
            }
            return;
        }
        if (shade == null) {
            shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, SHADE_COLORS);
            SHADES.put(window, shade);
            decor.getOverlay().add(shade);
        }
        int width = decor.getWidth();
        int height = 2 * statusBarHeight(decor);
        Rect bounds = shade.getBounds();
        if (bounds.left != 0 || bounds.top != 0 || bounds.right != width || bounds.bottom != height) {
            shade.setBounds(0, 0, width, height);
        }
    }

    /** The fade over {@code activity}'s window, or null while there's none. */
    static Drawable shadeOf(Activity activity) {
        Window window = activity.getWindow();
        return window == null ? null : SHADES.get(window);
    }

    /**
     * How tall the status bar over {@code decor} is: the window's top inset, or the platform's
     * bar height before the window has any.
     */
    @SuppressWarnings("deprecation")
    static int statusBarHeight(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        int top = insets == null ? 0 : insets.getSystemWindowInsetTop();
        if (top > 0) return top;
        android.content.res.Resources resources = decor.getResources();
        int id = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) {
            int height = resources.getDimensionPixelSize(id);
            if (height > 0) return height;
        }
        return Math.round(24 * resources.getDisplayMetrics().density);
    }

    /**
     * One layout pass over a feed cell's player: while {@code wanted}, grows its video so a portrait
     * picture covers the player top to bottom; otherwise puts back the scale this wrote. Every pass
     * measures again, so a new video in a reused cell gets its own scale. A scale TikTok wrote
     * itself is left alone.
     *
     * <p>Only the first video after a cold start plays in a surface of the player's own (#164).
     * TikTok creates that one at launch, sized to the picture inside {@code player_view}. Every
     * later cell takes its video view from a factory that, depending on TikTok's experiments, hands
     * back a SurfaceView or a TextureView inside the player, or a SurfaceControl player (47.1.4's
     * {@code X.1GNu}) whose view is {@code player_view} itself: the size TikTok works out for the
     * picture goes on the player, and the picture is a layer that follows the player's place, size
     * and scale. With no surface inside it, the player is the picture, and it grows inside the space
     * around it instead. Fit the video to the screen wins: a picture it shrank to show whole isn't
     * grown back.
     *
     * <p>TikTok keeps the video's cover still in a view of its own beside the player ({@code
     * coverId}): a sibling of the player, or a sibling's child, the size of the cell with the still
     * fitted inside. It shows the still for a frame as a video leaves and while the next one loads,
     * and the still was never grown, so with the picture filled the bands came back for that frame
     * on every swipe (#232). The cover takes the picture's scale, and goes back with it. It grows
     * from the point that keeps its still on the grown picture ({@link #coverPivot}).
     */
    static void fill(View player, boolean wanted, int coverId) {
        if (!(player instanceof ViewGroup)) return;
        if (wanted && fitWins()) wanted = false;
        if (wanted) PLAYERS.put(player, coverId);
        else PLAYERS.remove(player);
        ViewGroup group = (ViewGroup) player;
        ViewParent space = player.getParent();
        boolean surfaces = false;
        float scale = 1f;
        float middle = Float.NaN;
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View surface = group.getChildAt(i);
            if (!(surface instanceof SurfaceView) && !(surface instanceof TextureView)) continue;
            surfaces = true;
            float grown = wanted ? fillScale(group.getHeight(), surface) : 1f;
            float pivot = fillPivot(surface, grown);
            scale = grow(surface, grown, pivot);
            middle = middle(surface, space, scale, pivot);
        }
        // A player this grew keeps getting a pass after a surface shows up in it, so it goes back.
        if (!surfaces || FILLED.containsKey(player)) {
            boolean grows = wanted && !surfaces && space instanceof View;
            float own = grow(player, grows ? fillScale(((View) space).getHeight(), player, true) : 1f);
            if (!surfaces) {
                scale = own;
                middle = middle(player, space, own, Float.NaN);
            }
        }
        View cover = cover(space, coverId);
        if (cover != null) grow(cover, scale, coverPivot(cover, space, scale, middle));
    }

    /**
     * Where the cover grows from so the still fitted in its middle lands on the grown picture,
     * whose middle sits at {@code middle} in {@code space}, or NaN for its center. A surface TikTok
     * lifted grows from {@link #fillPivot}, which brings its middle down to the space's, so a cover
     * TikTok left in place keeps its center, and one TikTok lifted with the surface grows from
     * higher up instead of leaving its still 70 px off the picture with the band under it.
     */
    private static float coverPivot(View cover, ViewParent space, float scale, float middle) {
        int height = cover.getHeight();
        if (scale <= 1f || Float.isNaN(middle) || height <= 0) return Float.NaN;
        float half = height / 2f;
        float pivot = (offset(cover, space) + scale * half - middle) / (scale - 1f);
        if (!(pivot >= 0f && pivot <= height) || Math.abs(pivot - half) <= 1f) return Float.NaN;
        return pivot;
    }

    /** Where the middle of {@code picture}, grown by {@code scale} from {@code pivotY} (NaN for its center), sits in {@code space}. */
    private static float middle(View picture, ViewParent space, float scale, float pivotY) {
        float half = picture.getHeight() / 2f;
        float pivot = Float.isNaN(pivotY) || scale == 1f ? half : pivotY;
        return offset(picture, space) + pivot + scale * (half - pivot);
    }

    /** How far down {@code space} the top of {@code view}, somewhere inside it, sits, or NaN when it isn't inside. */
    private static float offset(View view, ViewParent space) {
        float top = 0f;
        for (View at = view; at != space; ) {
            top += at.getTop() + at.getTranslationY();
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return Float.NaN;
            at = (View) parent;
        }
        return top;
    }

    /** The cell's cover still beside {@code player}: a sibling, or a sibling's child, with {@code coverId}. */
    private static View cover(ViewParent space, int coverId) {
        if (coverId == 0 || !(space instanceof ViewGroup)) return null;
        ViewGroup around = (ViewGroup) space;
        for (int i = 0, count = around.getChildCount(); i < count; i++) {
            View beside = around.getChildAt(i);
            if (beside.getId() == coverId) return beside;
            if (!(beside instanceof ViewGroup)) continue;
            ViewGroup wrapper = (ViewGroup) beside;
            for (int j = 0, inner = wrapper.getChildCount(); j < inner; j++) {
                View inside = wrapper.getChildAt(j);
                if (inside.getId() == coverId) return inside;
            }
        }
        return null;
    }

    /** Whether Fit the video to the screen is on, so a picture it shrank stays whole. */
    private static boolean fitWins() {
        return SettingsStatus.videoFitEnabled && Settings.FIT_VIDEO_TO_SCREEN.get();
    }

    /** {@link #grow(View, float, float)} from the view's center. */
    private static float grow(View view, float scale) {
        return grow(view, scale, Float.NaN);
    }

    /**
     * Writes {@code scale} on {@code view} unless TikTok has written a scale of its own there, and
     * answers the scale this now holds on it, 1 when none. It grows from {@code pivotY}, or from
     * the center when that's NaN, and a pivot this wrote goes back with the scale. A 1 written over
     * this one's scale is TikTok putting the view back, not a zoom of its own (#390).
     */
    private static float grow(View view, float scale, float pivotY) {
        Float ours = FILLED.get(view);
        float current = view.getScaleY();
        boolean reset = ours != null && current == 1f && view.getScaleX() == 1f;
        if (!reset && (current != (ours == null ? 1f : ours) || view.getScaleX() != current)) {
            FILLED.remove(view);
            pivot(view, Float.NaN);
            return 1f;
        }
        if (scale != current) {
            view.setScaleX(scale);
            view.setScaleY(scale);
            scaleWrites++;
        }
        pivot(view, scale == 1f ? Float.NaN : pivotY);
        if (scale == 1f) FILLED.remove(view);
        else FILLED.put(view, scale);
        return scale;
    }

    /** Puts {@code view}'s pivot at {@code y}, or gives back the center when this moved it and {@code y} is NaN. */
    private static void pivot(View view, float y) {
        if (Float.isNaN(y)) {
            if (!PIVOTED.remove(view)) return;
            if (Build.VERSION.SDK_INT >= 28) {
                view.resetPivot();
            } else {
                view.setPivotX(view.getWidth() / 2f);
                view.setPivotY(view.getHeight() / 2f);
            }
            return;
        }
        if (view.getPivotY() != y || view.getPivotX() != view.getWidth() / 2f) {
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(y);
        }
        PIVOTED.add(view);
    }

    /**
     * Where {@code picture} grows from so {@code scale} covers the space from edge to edge, or NaN
     * for its center. TikTok can move a surface it centered with its translation: 47.6.3 on the
     * S22 lifts it 70.5 px, half the band it had before the bar's room was freed, so grown from its
     * center the picture stopped 70 px short of the bottom and the tabs sat on black. The pivot
     * that puts its top edge a pixel over the space's top puts the bottom one a pixel under, since
     * the scale is the space's height plus two. A picture partly outside the space keeps its center.
     */
    static float fillPivot(View picture, float scale) {
        if (scale <= 1f) return Float.NaN;
        float above = picture.getTop() + picture.getTranslationY();
        float pivot = (above + 1f) / (scale - 1f);
        int height = picture.getHeight();
        if (pivot < 0f || pivot > height || Math.abs(pivot - height / 2f) <= 1f) return Float.NaN;
        return pivot;
    }

    /** {@link #fillScale(int, View, boolean)} for a surface TikTok centered by its layout. */
    static float fillScale(int playerHeight, View surface) {
        return fillScale(playerHeight, surface, false);
    }

    /**
     * How much {@code picture} grows to cover a space {@code spaceHeight} tall, or 1 to leave it.
     * Only a portrait picture TikTok centered, since it grows from its center; a landscape video
     * keeps its bands, and so does a picture that would lose more than {@link #MAX_FILL_SCALE}
     * allows. Two pixels over, so no line of the band shows at either edge.
     *
     * <p>A player TikTok sized to the picture ({@code moved}) is placed by its translation as well
     * as its layout, and TikTok works its place out for the space it had before the spacer above
     * it went, so it can sit a little low. It grows until the wider band is covered too, within
     * the same limit.
     */
    static float fillScale(int spaceHeight, View picture, boolean moved) {
        int width = picture.getWidth();
        int height = picture.getHeight();
        if (width <= 0 || height <= width || spaceHeight <= height) return 1f;
        float shift = moved ? picture.getTranslationY() : 0f;
        float above = picture.getTop() + shift;
        float below = spaceHeight - picture.getBottom() - shift;
        float scale;
        if (moved) {
            if (above < 0 || below < 0) return 1f;
            scale = (height + 2f * Math.max(above, below) + 2f) / height;
        } else {
            if (Math.abs(above - below) > 2) return 1f;
            scale = (spaceHeight + 2f) / height;
        }
        return scale > MAX_FILL_SCALE ? 1f : scale;
    }

    /** Whether a surface still carries a scale this wrote, so the pass runs to put it back. */
    static boolean anyFilled() {
        return !FILLED.isEmpty();
    }

    /**
     * The pre-draw pass: every player the last pass filled is filled again for the size it has
     * now, before the frame is drawn. As a video starts TikTok writes 1 on the picture's scale,
     * and can lay the player out short for one pass and back to full height with nothing after
     * it, so the picture was drawn at TikTok's size, or grown for the short player and 6% small,
     * for a few frames after a swipe and then grew (#390). Neither reaches the layout pass in
     * time. A scale TikTok set itself is still left alone, the values are compared first so a
     * frame with nothing changed writes nothing, and a pass that turned the fill off takes the
     * player out. Answers how many scales it wrote.
     */
    static int refill() {
        if (PLAYERS.isEmpty()) return 0;
        int before = scaleWrites;
        REFILLING.addAll(PLAYERS.keySet());
        for (int i = 0, count = REFILLING.size(); i < count; i++) {
            View player = REFILLING.get(i);
            Integer coverId = player == null ? null : PLAYERS.get(player);
            if (coverId != null && player.isAttachedToWindow()) fill(player, true, coverId);
        }
        REFILLING.clear();
        return scaleWrites - before;
    }
}
