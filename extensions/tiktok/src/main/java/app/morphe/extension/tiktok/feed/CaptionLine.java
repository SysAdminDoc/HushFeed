/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feed;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.interaction.GestureActions;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Show the country and date under the caption (#410): the country a video was posted from and
 * its post time come off the creator's row and go on a line of their own right under the
 * caption, "United States · 2d ago".
 *
 * <p>The caption is {@code desc}, in TikTok's generated caption layout (47.9.3's
 * common_feed_layout_video_desc), which sits in the cell's info column under the creator's row.
 * The line goes in that column, the nearest vertical LinearLayout above the caption that also
 * holds the creator's name, right after the child that holds the caption. Nothing inside the
 * caption's own frame is touched: opening or closing the caption on "more" sets that frame to a
 * fixed height TikTok works out from the caption's text alone, so a line in there was squeezed
 * thin, or squeezed the caption, whichever gave way (#457).
 *
 * <p>The date is the row's own post time text, so it reads the way the row would have, Always
 * show publish date's exact time and Mark saved videos' check included. The row's post time is
 * emptied while the line carries it and gets its text back when the line goes. Its visibility is
 * never touched, so a post time that isn't showing is always one TikTok hid, and a rebind shows
 * up as text in it again.
 */
public final class CaptionLine {
    static final String CAPTION_ID = "desc";

    private static int captionViewId;

    /** Marks a line as one of these, wherever TikTok's tree keeps it. */
    static final class Line extends TextView {
        Line(Context context) {
            super(context);
        }
    }

    /** Every line put in, so the ones left in other cells can be hidden and all of them removed. */
    private static final Set<Line> LINES = Collections.newSetFromMap(new WeakHashMap<>());

    /** Post times emptied here, with the text each had then, so only those get it back. */
    private static final Map<TextView, CharSequence> HIDDEN = new WeakHashMap<>();

    private CaptionLine() {
    }

    /** Whether the line is asked for in this build. */
    static boolean wanted() {
        return SettingsStatus.authorRegionEnabled && Settings.COUNTRY_DATE_UNDER_CAPTION.get();
    }

    /** Lets a test drive the caption's id the activity's resources would otherwise supply. */
    static void setCaptionId(int id) {
        captionViewId = id;
    }

    /**
     * Writes the line under the caption of the cell {@code name} sits in, with {@code country}
     * (or none) and the row's post time, and says whether it shows. A post with no caption still
     * has the caption's views, with TikTok hiding the block that holds them, and a line in there
     * would never show, so it goes right under the creator's row instead (#453). With nowhere to
     * put it, or nothing to say, no line shows and the row keeps its post time.
     */
    static boolean show(TextView name, View root, String country) {
        if (name == null) {
            hideOthers(null);
            return false;
        }
        TextView postTime = postTimeBeside(name);
        View caption = caption(name, root);
        LinearLayout host = null;
        View holder = caption;
        if (caption != null && shownUpTo(caption, root)) {
            for (ViewParent parent = caption.getParent(); parent instanceof View; parent = parent.getParent()) {
                if (isColumn(parent) && holds((View) parent, name)) {
                    host = (LinearLayout) parent;
                    break;
                }
                if (parent == root) break;
                holder = (View) parent;
            }
        }
        if (host == null) {
            holder = rowOf(name, postTime, root);
            host = holder == null ? null : (LinearLayout) holder.getParent();
        }
        String date = dateOf(postTime);
        String text = join(country, date);
        if (host == null || text == null) {
            putBack(postTime);
            hideOthers(null);
            return false;
        }

        if (date != null && postTime.getText().length() > 0) {
            HIDDEN.put(postTime, postTime.getText());
            postTime.setText("");
        }
        Line line = lineAfter(host, holder);
        keepGapOf(line, holder);
        style(line, postTime != null ? postTime : caption instanceof TextView ? (TextView) caption : null, name);
        if (!text.contentEquals(line.getText())) {
            line.setText(text);
        }
        if (line.getVisibility() != View.VISIBLE) {
            line.setVisibility(View.VISIBLE);
        }
        hideOthers(line);
        return true;
    }

    /**
     * The creator's whole row, post time included, as the child of the vertical LinearLayout
     * above it: the same info column a line under the caption goes in.
     */
    private static View rowOf(TextView name, TextView postTime, View root) {
        if (postTime == null) return null;
        View cell = GestureActions.cellOf(name);
        View child = name;
        for (ViewParent parent = name.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof LinearLayout
                    && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL
                    && holds(child, postTime)) {
                return child;
            }
            if (parent == root || parent == cell) break;
            child = (View) parent;
        }
        return null;
    }

    private static boolean holds(View group, View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent == group) return true;
        }
        return false;
    }

    /** Whether {@code view} and everything above it up to {@code root} is set to show. */
    private static boolean shownUpTo(View view, View root) {
        for (View at = view; at != null; ) {
            if (at.getVisibility() != View.VISIBLE) return false;
            if (at == root) return true;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    /** Takes every line out and gives the rows their post times back. */
    static void clear() {
        for (Line line : new ArrayList<>(LINES)) {
            ViewParent parent = line.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(line);
            }
        }
        LINES.clear();
        for (TextView postTime : new ArrayList<>(HIDDEN.keySet())) {
            putBack(postTime);
        }
        HIDDEN.clear();
    }

    /**
     * The country for the line, by its name in the phone's language, after its flag with Show
     * the country as a flag on. A code with no name stays as it is.
     */
    static String countryLabel(String code) {
        if (code == null) {
            return null;
        }
        String name = code.length() == 2 ? new Locale("", code).getDisplayCountry() : null;
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(code)) {
            name = code;
        }
        String flag = AuthorRegion.shown(code);
        return flag == null || flag.equals(code) ? name : flag + " " + name;
    }

    /**
     * The row's post time without its leading dot: what it says now, or what it said before it
     * was emptied here. A post time TikTok hid itself has no date to move.
     */
    static String dateOf(TextView postTime) {
        if (postTime == null || postTime.getVisibility() != View.VISIBLE) {
            return null;
        }
        CharSequence text = postTime.getText();
        if (text == null || text.length() == 0) {
            text = HIDDEN.get(postTime);
        }
        if (text == null) {
            return null;
        }
        String now = text.toString();
        int start = 0;
        while (start < now.length() && (Character.isWhitespace(now.charAt(start))
                || Character.isSpaceChar(now.charAt(start)) || now.charAt(start) == (char) 0x00B7
                || now.charAt(start) == (char) 0x2022)) {
            start++;
        }
        String date = now.substring(start).trim();
        return date.isEmpty() ? null : date;
    }

    private static String join(String country, String date) {
        if (country == null) return date;
        return date == null ? country : country + AuthorRegion.SEPARATOR + date;
    }

    /**
     * The post time in the name's row, which is how AuthorRegion told the row apart. A photo
     * post's row keeps the name in a group of its own with the Photo label, so the post time is
     * the first one found going up from the name, never past the name's cell.
     */
    private static TextView postTimeBeside(TextView name) {
        int id = AuthorRegion.postTimeId();
        View cell = GestureActions.cellOf(name);
        ViewParent parent = name.getParent();
        for (int depth = 0; depth < ROW_DEPTH && parent instanceof ViewGroup; depth++) {
            View postTime = ((ViewGroup) parent).findViewById(id);
            if (postTime instanceof TextView) return (TextView) postTime;
            if (parent == cell) break;
            parent = parent.getParent();
        }
        return null;
    }

    /** How many groups up from the name the post time is looked for: three on a video, four on a photo post. */
    private static final int ROW_DEPTH = 4;

    /**
     * The caption in the name's cell. A video in a detail page with no pager around it has its
     * one caption under the window's content.
     */
    private static View caption(TextView name, View root) {
        if (captionViewId == 0) {
            Context context = name.getContext();
            captionViewId = context.getResources().getIdentifier(CAPTION_ID, "id", context.getPackageName());
            if (captionViewId == 0) return null;
        }
        View cell = GestureActions.cellOf(name);
        View scope = cell != name ? cell : root;
        return scope == null ? null : scope.findViewById(captionViewId);
    }

    /** A vertical LinearLayout; 47.9.3's info column is one of TikTok's own subclasses. */
    private static boolean isColumn(ViewParent parent) {
        return parent instanceof LinearLayout
                && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL;
    }

    /** The line right after {@code holder} in {@code host}, put there if it isn't already. */
    private static Line lineAfter(LinearLayout host, View holder) {
        int at = host.indexOfChild(holder);
        View next = host.getChildAt(at + 1);
        if (next instanceof Line) {
            return (Line) next;
        }
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (child instanceof Line) {
                host.removeView(child);
                LINES.remove(child);
                at = host.indexOfChild(holder);
                break;
            }
        }
        Line line = new Line(host.getContext());
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        host.addView(line, at + 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LINES.add(line);
        return line;
    }

    /**
     * Puts the line 2dp under {@code holder} and gives it the gap TikTok leaves under the holder.
     * On a video with no search bar under the caption, that bottom margin is all that keeps the
     * caption off the progress bar, and a line put in after it sat on the bar (#498). The holder's
     * own margin is left alone and cancelled by the line's top one, so taking the line out leaves
     * TikTok's layout as it was. Only a change sets the params, since every layout pass runs this.
     */
    private static void keepGapOf(Line line, View holder) {
        ViewGroup.LayoutParams held = holder.getLayoutParams();
        int gap = held instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) held).bottomMargin : 0;
        int top = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2,
                line.getResources().getDisplayMetrics())) - gap;
        ViewGroup.LayoutParams params = line.getLayoutParams();
        if (!(params instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams own = (LinearLayout.LayoutParams) params;
        if (own.topMargin != top || own.bottomMargin != gap) {
            own.topMargin = top;
            own.bottomMargin = gap;
            line.setLayoutParams(own);
        }
    }

    /** The shadow the line's own shadow switch draws: black at 70%, a little below the letters. */
    static final int SHADOW_COLOR = 0xB3000000;
    static final float SHADOW_RADIUS_DP = 3f;
    static final float SHADOW_DY_DP = 1f;

    /**
     * Sized like the post time it carries, so it reads as TikTok's own small print, and white
     * like the name above it rather than the post time's grey (#498). Its own size, opacity and
     * shadow settings win over both (#500): a long exact date at the name's size crowds the
     * caption, and a faint one disappears on a bright video.
     */
    private static void style(Line line, TextView source, TextView colorOf) {
        int opacity = Math.max(10, Math.min(100, Settings.COUNTRY_DATE_OPACITY.get()));
        int color = faded(colorOf.getCurrentTextColor(), opacity);
        if (line.getCurrentTextColor() != color) {
            line.setTextColor(color);
        }
        DisplayMetrics metrics = line.getResources().getDisplayMetrics();
        int points = Settings.COUNTRY_DATE_TEXT_SIZE.get();
        if (points > 0) {
            int size = FeedTextSize.clampSize(points);
            if (line.getTextSize() != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)) {
                line.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            }
        } else if (source != null && line.getTextSize() != source.getTextSize()) {
            line.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        }
        if (source != null && line.getTypeface() != source.getTypeface()) {
            line.setTypeface(source.getTypeface());
        }
        if (Settings.COUNTRY_DATE_SHADOW.get()) {
            shadow(line, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SHADOW_RADIUS_DP, metrics), 0f,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SHADOW_DY_DP, metrics),
                    faded(SHADOW_COLOR, opacity));
        } else if (source != null) {
            shadow(line, source.getShadowRadius(), source.getShadowDx(), source.getShadowDy(),
                    faded(source.getShadowColor(), opacity));
        }
    }

    /**
     * The color with its alpha taken down to the line's opacity. The shadow needs it as much as
     * the letters: Android fades a shadow with its text only when the shadow's color is opaque,
     * and both shadows here are see-through, so a faint line kept a full dark smudge (#519).
     */
    static int faded(int color, int opacity) {
        return (Math.round(Color.alpha(color) * opacity / 100f) << 24) | (color & 0x00FFFFFF);
    }

    private static void shadow(TextView line, float radius, float dx, float dy, int color) {
        if (line.getShadowRadius() != radius || line.getShadowDx() != dx
                || line.getShadowDy() != dy || line.getShadowColor() != color) {
            line.setShadowLayer(radius, dx, dy, color);
        }
    }

    /** A pager keeps the cells beside the video attached, and their lines can be out of date. */
    private static void hideOthers(Line keep) {
        for (Line line : new ArrayList<>(LINES)) {
            if (line != keep && line.getVisibility() != View.GONE) {
                line.setVisibility(View.GONE);
            }
        }
    }

    /** Gives a post time emptied here its text back, unless TikTok has written it since. */
    private static void putBack(TextView postTime) {
        if (postTime == null) return;
        CharSequence hidden = HIDDEN.remove(postTime);
        if (hidden != null && postTime.getText().length() == 0) {
            postTime.setText(hidden);
        }
    }
}
