/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 * Modified for hushfeed (TikTok), 2026.
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.TwoStatePreference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;

import app.morphe.extension.shared.settings.preference.ImmediateAction;

/**
 * The settings screen's colour roles, in TikTok's red and cyan: the black page by default and the
 * light one when TikTok is light. Every role is a token from {@link SettingsUi}, which carries
 * each one's measured contrast beside it.
 */
public final class ScreenColors {
    public final boolean light;
    /** The page, behind the bar and every row. */
    public final @ColorInt int background;
    /** A dialog's surface. */
    public final @ColorInt int dialog;
    /** Row and dialog titles. */
    public final @ColorInt int title;
    /** Row summaries and a dialog's message. */
    public final @ColorInt int summary;
    /**
     * A field's placeholder. Quieter than {@link #summary}, so an example in an empty field
     * doesn't read as a value already saved, and still 4.5:1 on every surface.
     */
    public final @ColorInt int hint;
    /** Section titles, row icons, the bar's back arrow and every focus outline. */
    public final @ColorInt int heading;
    /** A switch that's on, a dialog's main action and a field's cursor and focused outline. */
    public final @ColorInt int accent;
    /**
     * A switch that's off. A field's outline at rest is {@link #outline} instead: this grey
     * against the accent of a focused field is only 1.45:1 on the dark theme.
     */
    public final @ColorInt int switchOff;
    /** Rows and their edge. */
    public final @ColorInt int card;
    public final @ColorInt int outline;
    /** The tile behind an icon. */
    public final @ColorInt int iconSurface;
    /** Text and the thumb on {@link #accent}. */
    public final @ColorInt int onAccent;
    /** Validation feedback. */
    public final @ColorInt int error;

    public static final ScreenColors DARK = new ScreenColors(true);
    public static final ScreenColors LIGHT = new ScreenColors(false);

    /** The colours to paint with now, read on each bind so a theme change shows on the next one. */
    public static ScreenColors current() {
        return SettingsUi.isDarkMode() ? DARK : LIGHT;
    }

    ScreenColors(boolean dark) {
        light = !dark;
        background = SettingsUi.backgroundOn(dark);
        dialog = dark ? SettingsUi.DARK_DIALOG : SettingsUi.LIGHT_DIALOG;
        title = SettingsUi.textPrimaryOn(dark);
        summary = SettingsUi.textSecondaryOn(dark);
        hint = SettingsUi.hintOn(dark);
        heading = SettingsUi.headingOn(dark);
        accent = SettingsUi.accentOn(dark);
        switchOff = SettingsUi.switchOffOn(dark);
        card = dark ? SettingsUi.DARK_CARD : SettingsUi.LIGHT_CARD;
        outline = SettingsUi.borderOn(dark);
        iconSurface = dark ? SettingsUi.DARK_ICON_SURFACE : SettingsUi.LIGHT_ICON_SURFACE;
        onAccent = SettingsUi.onAccentOn(dark);
        error = dark ? SettingsUi.DARK_ERROR : SettingsUi.LIGHT_ERROR;
    }

    /** An unchecked switch's thumb, white on either theme's grey track. */
    @ColorInt int offThumb() {
        return SettingsUi.SWITCH_THUMB;
    }

    /**
     * [color], at Material's 38% on a disabled row. A plain colour replaced the theme's own
     * disabled state, so a row that ignored taps looked the same as one that would take them.
     */
    static ColorStateList dimmedWhenDisabled(@ColorInt int color) {
        int dimmed = (color & 0x00FFFFFF) | 0x61000000;
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{dimmed, color});
    }

    /**
     * [color] for a disabled row's summary, at 75%. The summary is where a greyed-out row says
     * what to turn on first, and at 38% it read at about 2:1 on the card. The title and switch
     * keep the 38% that shows the row is off.
     */
    static ColorStateList readableWhenDisabled(@ColorInt int color) {
        int dimmed = (color & 0x00FFFFFF) | 0xC0000000;
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{dimmed, color});
    }

    /** Native list navigation uses selected and activated as well as focused. Keep each visible. */
    ColorStateList interactionOutline(@ColorInt int normal) {
        return interactionOutline(normal, heading);
    }

    ColorStateList interactionOutline(@ColorInt int normal, @ColorInt int focused) {
        return new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                {android.R.attr.state_hovered}, {android.R.attr.state_selected},
                {android.R.attr.state_activated}, {android.R.attr.state_checked}, {}},
                new int[]{(normal & 0x00FFFFFF) | (Math.round(Color.alpha(normal) * 0.38f) << 24),
                        focused, focused, focused, focused, focused, normal});
    }

    /** A press in [color] at 20%, and none on a disabled row. */
    static ColorStateList interactionRipple(@ColorInt int color) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.TRANSPARENT, (color & 0x00FFFFFF) | 0x33000000});
    }

    /** A picked choice's fill: the accent at 13% inside its corners. */
    ColorStateList choiceFill(@ColorInt int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{(accent & 0x00FFFFFF) | 0x22000000, normal});
    }

    /** A switch's track and thumb while greyed: the colour at half strength, as Material draws it. */
    public static @ColorInt int half(@ColorInt int color) {
        return (color & 0x00FFFFFF) | 0x80000000;
    }

    /**
     * Paints [row] the way the page draws it. An [overview] row sits on the main page, full bleed;
     * any other is part of its section's inset card, which [first] opens and [last] closes.
     */
    @SuppressWarnings("deprecation")
    void paintRow(View row, Preference preference, boolean overview, boolean first, boolean last) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(this.title));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, overview ? 15 : 16);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
            title.setEllipsize(null);
            // Android 9 and older hyphenate by default, and the line balancer split words that
            // fit ("co-mentarios" in a 222 px card at twice the size). Android 10 doesn't.
            title.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            if (title.getParent() instanceof View && title.getParent() != row) {
                View text = (View) title.getParent();
                text.setPadding(0, 0, 0, 0);
                text.setMinimumHeight(0);
            }
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(readableWhenDisabled(this.summary));
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, overview ? 12 : 13);
            summary.setLineSpacing(0, 1);
            summary.setSingleLine(false);
            summary.setMaxLines(Integer.MAX_VALUE);
            summary.setEllipsize(null);
            summary.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            summary.setPadding(0, 0, 0, 0);
        }
        SettingsUi.styleSwitches(row);
        paintChevron(row, preference);
        ImageView icon = row.findViewById(android.R.id.icon);
        if (icon != null) icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icon != null && preference.getIcon() != null) paintIcon(icon, dp(row, 24));
        paintSurface(row, preference, overview, first, last);
    }

    /** A row's icon at [size] in the heading colour, in a 38 dp frame that ends 14 dp before the text. */
    void paintIcon(ImageView icon, int size) {
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        if (params != null && (params.width != size || params.height != size)) {
            params.width = size;
            params.height = size;
            icon.setLayoutParams(params);
        }
        icon.setPadding(0, 0, 0, 0);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageTintList(dimmedWhenDisabled(heading));
        icon.setBackground(null);
        if (!(icon.getParent() instanceof View)) return;
        View frame = (View) icon.getParent();
        frame.setPaddingRelative(0, 0, dp(icon, 14), 0);
        frame.setMinimumWidth(0);
        ViewGroup.LayoutParams room = frame.getLayoutParams();
        if (room == null) return;
        // Android's own row pulls its frame 4 dp toward the edge, off the line the text starts on.
        boolean pulled = room instanceof ViewGroup.MarginLayoutParams
                && ((ViewGroup.MarginLayoutParams) room).getMarginStart() != 0;
        if (room.width != size + dp(icon, 14) || pulled) {
            room.width = size + dp(icon, 14);
            if (pulled) ((ViewGroup.MarginLayoutParams) room).setMarginStart(0);
            frame.setLayoutParams(room);
        }
    }

    /** Keyed tag in the app's id space: what a row's surface was last painted as. */
    private static final int TAG_SURFACE = 0x7f7f4007;

    /**
     * The row's card: full bleed with no corners on the main page, its side and top edges pushed
     * a pixel out of sight so rows meet on one hairline; inset 18 dp in a section, rounded where
     * the card opens and closes, with 4 dp of air outside each end.
     */
    void paintSurface(View row, Preference preference, boolean overview, boolean first, boolean last) {
        boolean selectable = preference.isSelectable();
        // A list recycles its rows on every frame of a scroll. One that comes back with the same
        // shape in the same theme keeps the card it has.
        Integer look = (light ? 1 : 0) | (overview ? 2 : 0) | (first ? 4 : 0) | (last ? 8 : 0)
                | (selectable ? 16 : 0);
        if (!look.equals(row.getTag(TAG_SURFACE)) || row.getBackground() == null) {
            float radius = overview ? 0 : dp(row, SettingsUi.RADIUS_CARD);
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(card);
            surface.setStroke(dp(row, 1), selectable ? interactionOutline(outline) : ColorStateList.valueOf(outline));
            float top = first ? radius : 0;
            float bottom = last ? radius : 0;
            surface.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
            int side = overview ? -dp(row, 1) : dp(row, 18);
            Drawable inset = new InsetDrawable(surface, side,
                    overview ? -dp(row, 1) : first ? dp(row, 4) : -dp(row, 1), side,
                    !overview && last ? dp(row, 4) : 0);
            row.setBackground(selectable ? new RippleDrawable(interactionRipple(heading), inset, null).mutate() : inset);
            row.setTag(TAG_SURFACE, look);
        }
        // After the background: a view takes its background's padding, and an InsetDrawable's is
        // its insets.
        int horizontal = overview ? 20 : 30;
        int vertical = overview ? 6 : 10;
        row.setPaddingRelative(dp(row, horizontal), dp(row, vertical + (!overview && first ? 4 : 0)),
                dp(row, horizontal), dp(row, vertical + (!overview && last ? 4 : 0)));
        row.setMinimumHeight(dp(row, SettingsViewTogglePreference.KEY.equals(preference.getKey()) ? 48
                : overview ? 52 : 64));
    }

    /** A section's heading: small capitals in the heading colour, lined up with the cards under it. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(heading);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setLetterSpacing(0.055f);
            title.setAllCaps(true);
        }
        row.setPaddingRelative(dp(row, 22), dp(row, 20), dp(row, 22), dp(row, 8));
    }

    /** The tag the chevron wears in a row's widget frame. */
    static final String CHEVRON = "hushfeed_chevron";

    /**
     * Whether a tap on [preference] opens something: a page, a picker, a dialog or a link. A
     * switch flips and an action acts, and a chevron on either makes the wrong promise.
     */
    @SuppressWarnings("deprecation")
    static boolean opensSomething(Preference preference) {
        return preference.isSelectable() && !(preference instanceof TwoStatePreference)
                && !(preference instanceof ImmediateAction && ((ImmediateAction) preference).actsOnTap());
    }

    /**
     * The chevron at the row's end, on the rows a tap opens something from: pointing on, or down
     * and up on a section that opens in place. A recycled row loses one it no longer earns.
     */
    void paintChevron(View row, Preference preference) {
        View widget = row.findViewById(android.R.id.widget_frame);
        if (!(widget instanceof ViewGroup)) return;
        ViewGroup frame = (ViewGroup) widget;
        View chevron = frame.findViewWithTag(CHEVRON);
        if (!opensSomething(preference)) {
            if (chevron != null) {
                ((ViewGroup) chevron.getParent()).removeView(chevron);
                // The frame is here for the chevron on these rows, and an empty one still takes
                // width. Preference hides it when the view is built, which reuse skips.
                if (frame.getChildCount() == 0) frame.setVisibility(View.GONE);
            }
            return;
        }
        ImageView image;
        if (chevron instanceof ImageView) {
            image = (ImageView) chevron;
        } else {
            if (chevron != null) ((ViewGroup) chevron.getParent()).removeView(chevron);
            image = new ImageView(row.getContext());
            image.setTag(CHEVRON);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(dp(row, 16), dp(row, 24));
            slot.gravity = Gravity.CENTER_VERTICAL;
            frame.addView(image, slot);
        }
        int direction = 0;
        if (preference instanceof SettingsMenuPreference && ((SettingsMenuPreference) preference).isExpandable()) {
            direction = ((SettingsMenuPreference) preference).isExpanded() ? -1 : 1;
        }
        Drawable drawn = image.getDrawable();
        if (!(drawn instanceof Chevron) || ((Chevron) drawn).color != summary
                || ((Chevron) drawn).direction != direction) {
            image.setImageDrawable(new Chevron(summary, dp(row, 2), direction));
        }
        image.setEnabled(preference.isEnabled());
        frame.setVisibility(View.VISIBLE);
    }

    private static int dp(View view, int value) {
        return SettingsUi.dp(view.getContext(), value);
    }

    /**
     * A chevron drawn in code with a round 2 dp stroke: pointing the way the reader reads, or down
     * on a closed section and up on an open one. It dims with its row.
     */
    static final class Chevron extends Drawable {
        final @ColorInt int color;
        /** 0 points on, 1 down, -1 up. */
        final int direction;
        private final ColorStateList colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(@ColorInt int color, float stroke) {
            this(color, stroke, 0);
        }

        Chevron(@ColorInt int color, float stroke, int direction) {
            this.color = color;
            this.direction = direction;
            colors = dimmedWhenDisabled(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        /** The three points of the stroke, mirrored for a right-to-left reader. */
        float[] points() {
            Rect bounds = getBounds();
            float half = bounds.height() * 0.19f;
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            if (direction != 0) {
                float tip = half / 2 * direction;
                return new float[]{x - half, y - tip, x, y + tip, x + half, y - tip};
            }
            float reach = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -half / 2 : half / 2;
            return new float[]{x - reach, y - half, x + reach, y, x - reach, y + half};
        }

        @ColorInt int currentColor() {
            return paint.getColor();
        }

        @Override
        public void draw(Canvas canvas) {
            float[] points = points();
            path.rewind();
            path.moveTo(points[0], points[1]);
            path.lineTo(points[2], points[3]);
            path.lineTo(points[4], points[5]);
            canvas.drawPath(path, paint);
        }

        @Override
        public boolean isAutoMirrored() {
            return true;
        }

        @Override
        public boolean onLayoutDirectionChanged(int layoutDirection) {
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int now = colors.getColorForState(state, color);
            if (now == paint.getColor()) return false;
            paint.setColor(now);
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
