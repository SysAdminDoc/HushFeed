/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 * Modified for hushfeed (TikTok), 2026.
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.res.ColorStateList;
import android.graphics.Color;

import androidx.annotation.ColorInt;

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
    /** A switch that's on, the filled dialog action and a field's cursor. */
    public final @ColorInt int accent;
    /** A switch that's off, and a field's outline at rest. */
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
}
