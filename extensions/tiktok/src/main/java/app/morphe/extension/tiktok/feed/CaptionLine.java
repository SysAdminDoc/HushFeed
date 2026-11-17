/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feed;

import android.content.Context;
import android.text.TextUtils;
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
 * <p>The caption is {@code desc}, in TikTok's generated caption layout (47.8.3's
 * common_feed_layout_video_desc): a vertical LinearLayout holding the caption block, a
 * LinearLayout subclass of TikTok's own with {@code desc} inside it. The line goes in the
 * nearest plain vertical LinearLayout above the caption, right after the child that holds it,
 * so TikTok's caption block, which opens and closes on "more", is left as it was.
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
     * (or none) and the row's post time, and says whether it shows. With no caption to put it
     * under, or nothing to say, no line shows and the row keeps its post time. A post with no
     * caption still has the caption's views, with TikTok hiding the block that holds them, and a
     * line in there would carry the date off screen.
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
                if (isColumn(parent)) {
                    host = (LinearLayout) parent;
                    break;
                }
                if (parent == root) break;
                holder = (View) parent;
            }
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
        style(line, postTime != null ? postTime : caption instanceof TextView ? (TextView) caption : null);
        if (!text.contentEquals(line.getText())) {
            line.setText(text);
        }
        if (line.getVisibility() != View.VISIBLE) {
            line.setVisibility(View.VISIBLE);
        }
        hideOthers(line);
        return true;
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

    /** How many groups up from the name the post time is looked for. */
    private static final int ROW_DEPTH = 3;

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

    /** A plain vertical LinearLayout, not one of TikTok's own subclasses. */
    private static boolean isColumn(ViewParent parent) {
        return parent.getClass() == LinearLayout.class
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2,
                host.getResources().getDisplayMetrics()));
        host.addView(line, at + 1, params);
        LINES.add(line);
        return line;
    }

    /** Dressed like the post time it carries, so it reads as TikTok's own small print. */
    private static void style(Line line, TextView source) {
        if (source == null) return;
        if (line.getTextSize() != source.getTextSize()) {
            line.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        }
        if (line.getCurrentTextColor() != source.getCurrentTextColor()) {
            line.setTextColor(source.getCurrentTextColor());
        }
        if (line.getTypeface() != source.getTypeface()) {
            line.setTypeface(source.getTypeface());
        }
        if (line.getShadowRadius() != source.getShadowRadius() || line.getShadowDx() != source.getShadowDx()
                || line.getShadowDy() != source.getShadowDy() || line.getShadowColor() != source.getShadowColor()) {
            line.setShadowLayer(source.getShadowRadius(), source.getShadowDx(), source.getShadowDy(),
                    source.getShadowColor());
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
