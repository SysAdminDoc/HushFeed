/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.blockauthor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A Block sound button on a sound's own page, the one the spinning record on a video opens, so a
 * sound can be blocked where it's shown whole instead of from one of the videos that use it.
 *
 * <p>The patch calls {@link #onResume(Activity)} from the page's own onResume
 * ({@code MusicDetailActivity}). The page's header is built by a fragment once the sound has
 * loaded, so the button goes in from a layout listener as soon as Save is there, and comes back
 * if TikTok builds the header again. It sits under Save as a second pill of the same shape, so it
 * reads as part of the page.
 *
 * <p>The sound is the page's own: the {@code id} extra every way onto the page carries, which is
 * the id the feed filter matches. A {@code Music} the page was handed (the record on a video
 * passes one) adds the sound's name for the banner, and is what a sound with no id is blocked by.
 * The title on the page names it otherwise; that's only ever shown, never stored.
 */
public final class SoundPageBlockButton {
    /** Tags the row this adds, so a header TikTok rebuilt is told from one that still has it. */
    static final String TAG = "hushfeed_sound_page_block";
    private static final String MUSIC_CLASS = "com.ss.android.ugc.aweme.music.model.Music";
    /** A header that never shows Save costs one tree walk per this long, not one per layout. */
    private static final long SEARCH_GAP_MS = 250;

    private static final Map<Activity, Watcher> WATCHERS = new WeakHashMap<>();

    private SoundPageBlockButton() {
    }

    public static boolean enabled() {
        return SettingsStatus.soundPageBlockEnabled && SettingsStatus.feedFilterEnabled
                && Settings.SOUND_PAGE_BLOCK_BUTTON.get() && !Setting.isPaused();
    }

    /** Called at the start of the sound page's onResume, on the main thread. */
    public static void onResume(Activity activity) {
        try {
            if (activity == null) return;
            Watcher watcher = WATCHERS.get(activity);
            if (!enabled()) {
                if (watcher != null) {
                    WATCHERS.remove(activity);
                    watcher.stop();
                }
                return;
            }
            if (watcher == null) {
                watcher = new Watcher(activity);
                WATCHERS.put(activity, watcher);
                watcher.listen();
            }
            // A sound unblocked in settings or from the feed since the page was last up.
            watcher.refreshLabel();
            watcher.attach();
        } catch (Throwable error) {
            Logger.printException(() -> "Sound page block button failed", error);
        }
    }

    /** The sound the page is about, or null when it names none. */
    static Sound soundOf(Activity activity) {
        Object music = musicOf(activity);
        String id = null;
        Intent intent = activity.getIntent();
        if (intent != null) id = blankToNull(intent.getStringExtra("id"));
        if (id == null && music != null) id = SoundIdentity.idOf(music);
        String name = music == null ? null : SoundIdentity.nameOf(music);
        String author = music == null ? null : SoundIdentity.authorOf(music);
        if (id == null && name == null) return null;
        return new Sound(id, name, author);
    }

    /** The {@code Music} the page was opened with, or null. Read, never written. */
    private static Object musicOf(Activity activity) {
        for (Class<?> type = activity.getClass(); type != null && type != Activity.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !MUSIC_CLASS.equals(field.getType().getName())) continue;
                try {
                    field.setAccessible(true);
                    Object music = field.get(activity);
                    if (music != null) return music;
                } catch (Throwable ignored) {
                    // An unreadable field is a page without a handed-over sound.
                }
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A sound as the page names it: its id, and the name and author a handed-over Music has. */
    static final class Sound {
        final String id;
        /** From the Music only, because a sound with no id is blocked by this. */
        final String name;
        final String author;

        Sound(String id, String name, String author) {
            this.id = id;
            this.name = name;
            this.author = author;
        }

        boolean blocked() {
            return (id != null && SoundIdentity.containsEntry(Settings.BLOCKED_SOUND_IDS.get(), id))
                    || (name != null
                            && SoundIdentity.containsEntry(Settings.BLOCKED_SOUND_NAMES.get(), name));
        }

        /** Records it the way the feed's button does: by id, or by name when there's no id. */
        boolean block() {
            StringSetting setting = id != null ? Settings.BLOCKED_SOUND_IDS : Settings.BLOCKED_SOUND_NAMES;
            String identity = id != null ? id : name;
            return BlockAuthorOverlay.saveAction(setting, SoundIdentity.withEntry(setting.get(), identity));
        }

        /** Takes it off both lists, since the feed's button may have recorded it by name. */
        boolean unblock() {
            if (id != null && SoundIdentity.containsEntry(Settings.BLOCKED_SOUND_IDS.get(), id)
                    && !BlockAuthorOverlay.saveAction(Settings.BLOCKED_SOUND_IDS,
                            SoundIdentity.withoutEntry(Settings.BLOCKED_SOUND_IDS.get(), id))) {
                return false;
            }
            return name == null
                    || !SoundIdentity.containsEntry(Settings.BLOCKED_SOUND_NAMES.get(), name)
                    || BlockAuthorOverlay.saveAction(Settings.BLOCKED_SOUND_NAMES,
                            SoundIdentity.withoutEntry(Settings.BLOCKED_SOUND_NAMES.get(), name));
        }
    }

    /** One page's button: finds Save, puts the row under it, and keeps it there. */
    private static final class Watcher implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> page;
        private WeakReference<View> row = new WeakReference<>(null);
        private WeakReference<TextView> label = new WeakReference<>(null);
        private long lastSearch;
        private boolean listening;

        Watcher(Activity activity) {
            page = new WeakReference<>(activity);
        }

        void listen() {
            Activity activity = page.get();
            if (activity == null || listening) return;
            activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this);
            listening = true;
        }

        void stop() {
            Activity activity = page.get();
            if (activity != null && listening) {
                ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            }
            listening = false;
            View current = row.get();
            if (current != null && current.getParent() instanceof ViewGroup) {
                ((ViewGroup) current.getParent()).removeView(current);
            }
        }

        @Override
        public void onGlobalLayout() {
            try {
                if (placed()) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastSearch < SEARCH_GAP_MS) return;
                lastSearch = now;
                attach();
            } catch (Throwable error) {
                Logger.printException(() -> "Sound page block button failed", error);
            }
        }

        private boolean placed() {
            View current = row.get();
            return current != null && current.getParent() != null && current.isAttachedToWindow();
        }

        void attach() {
            Activity activity = page.get();
            if (activity == null || activity.isFinishing() || placed() || !enabled()) return;
            Sound sound = soundOf(activity);
            if (sound == null) return;
            View content = activity.findViewById(android.R.id.content);
            View save = findSave(content);
            if (save == null || save.getWidth() <= 0 || save.getHeight() <= 0) return;
            HorizontalScrollView scroll = scrollAround(save);
            if (scroll == null) return;
            ViewParent parent = scroll.getParent();
            if (!(parent instanceof LinearLayout)
                    || ((LinearLayout) parent).getOrientation() != LinearLayout.VERTICAL) {
                Logger.printDebug(() -> "Sound page: Save isn't in a column, no Block sound button");
                return;
            }
            LinearLayout column = (LinearLayout) parent;

            int left = leftWithin(save, scroll);
            int top = topWithin(save, scroll);
            int right = Math.max(0, scroll.getWidth() - left - save.getWidth());
            int bottom = Math.max(0, scroll.getHeight() - top - save.getHeight());

            View built = buildRow(activity, save);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, save.getHeight());
            params.setMargins(left, 0, right, bottom);
            column.addView(built, column.indexOfChild(scroll) + 1, params);
            row = new WeakReference<>(built);
            refreshLabel();
            Logger.printDebug(() -> "Sound page: Block sound button added");
        }

        private View buildRow(Activity activity, View save) {
            TextView style = textOf(save);
            FrameLayout pill = new FrameLayout(activity) {
                @Override
                public CharSequence getAccessibilityClassName() {
                    return Button.class.getName();
                }
            };
            pill.setTag(TAG);
            pill.setClickable(true);
            pill.setFocusable(true);
            int textColor = style != null ? style.getCurrentTextColor() : Color.WHITE;
            pill.setBackground(backgroundLike(save, textColor));

            TextView text = new TextView(activity);
            text.setGravity(Gravity.CENTER);
            text.setSingleLine(true);
            text.setIncludeFontPadding(false);
            if (style != null) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.getTextSize());
                text.setTypeface(style.getTypeface());
                text.setTextColor(style.getTextColors());
                text.setLetterSpacing(style.getLetterSpacing());
            } else {
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                text.setTextColor(textColor);
            }
            int glyphSize = Math.round(text.getTextSize() * 1.15f);
            Drawable glyph = new OverlayGlyphDrawable(OverlayGlyphDrawable.Shape.BLOCK, textColor,
                    Math.max(2f, glyphSize / 11f));
            glyph.setBounds(0, 0, glyphSize, glyphSize);
            text.setCompoundDrawablesRelative(glyph, null, null, null);
            text.setCompoundDrawablePadding(SettingsUi.dp(activity, 8));
            pill.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            label = new WeakReference<>(text);

            pill.setOnClickListener(view -> onTapped());
            return pill;
        }

        void refreshLabel() {
            Activity activity = page.get();
            TextView text = label.get();
            if (activity == null || text == null) return;
            Sound sound = soundOf(activity);
            boolean blocked = sound != null && sound.blocked();
            text.setText(L10n.t(activity, blocked ? "Unblock sound" : "Block sound"));
        }

        private void onTapped() {
            Activity activity = page.get();
            if (activity == null) return;
            Sound sound = soundOf(activity);
            if (sound == null) return;
            ViewGroup root = activity.findViewById(android.R.id.content);
            String shown = shownAs(activity, sound);
            if (sound.blocked()) {
                if (!sound.unblock()) return;
                refreshLabel();
                BlockAuthorOverlay.showNoticeBanner(root, L10n.f("Unblocked %1$s", shown));
                return;
            }
            if (!sound.block()) return;
            // The sound's author is a creator, so the line says how it was recorded, not which.
            Logger.printDebug(() -> "Sound page: blocked sound " + (sound.id != null ? "by id" : "by name"));
            refreshLabel();
            BlockAuthorOverlay.showUndoBanner(root, L10n.f("Skipping videos with %1$s", shown), () -> {
                if (sound.unblock()) refreshLabel();
            });
        }

        /** The sound's name for the banner: the Music's, else the title and author on the page. */
        private String shownAs(Activity activity, Sound sound) {
            String name = sound.name;
            String author = sound.author;
            if (name == null) {
                View content = activity.findViewById(android.R.id.content);
                View save = findSave(content);
                HorizontalScrollView scroll = save == null ? null : scrollAround(save);
                ViewParent parent = scroll == null ? null : scroll.getParent();
                if (parent instanceof ViewGroup) {
                    ViewGroup column = (ViewGroup) parent;
                    int index = column.indexOfChild(scroll);
                    if (index > 0) {
                        String[] texts = new String[2];
                        firstTexts(column.getChildAt(index - 1), texts);
                        name = texts[0];
                        if (author == null) author = texts[1];
                    }
                }
            }
            return CurrentVideoSound.of(sound.id, name, author).label();
        }
    }

    /**
     * Save: the first visible button inside a horizontal scroller on the page. TikTok draws it as
     * its own button type, which reports itself to accessibility as a Button, so that's what's
     * matched rather than the class.
     */
    static View findSave(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || TAG.equals(view.getTag())) return null;
        if (view instanceof HorizontalScrollView) {
            return firstButton(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSave(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View firstButton(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        CharSequence role = view.getAccessibilityClassName();
        if (view.isClickable() && (view instanceof Button
                || (role != null && Button.class.getName().contentEquals(role)))) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static HorizontalScrollView scrollAround(View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof HorizontalScrollView) return (HorizontalScrollView) parent;
        }
        return null;
    }

    private static int leftWithin(View view, View ancestor) {
        int left = 0;
        for (View at = view; at != null && at != ancestor;
                at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            left += at.getLeft() - (at.getParent() instanceof View ? ((View) at.getParent()).getScrollX() : 0);
        }
        return left;
    }

    private static int topWithin(View view, View ancestor) {
        int top = 0;
        for (View at = view; at != null && at != ancestor;
                at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            top += at.getTop() - (at.getParent() instanceof View ? ((View) at.getParent()).getScrollY() : 0);
        }
        return top;
    }

    /** Save's own text, or the first text inside it when TikTok's button wraps one. */
    private static TextView textOf(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = textOf(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Drawable backgroundLike(View save, int textColor) {
        Drawable background = save.getBackground();
        Drawable.ConstantState state = background == null ? null : background.getConstantState();
        if (state != null) {
            return state.newDrawable(save.getResources()).mutate();
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(save.getHeight() / 2f);
        // Color.luminance(int) needs API 24, and TikTok runs from Android 6 (API 23).
        boolean lightText = Color.red(textColor) * 299 + Color.green(textColor) * 587
                + Color.blue(textColor) * 114 > 128_000;
        pill.setColor(lightText ? 0x29FFFFFF : 0x14000000);
        return pill;
    }

    private static void firstTexts(View view, String[] into) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            String text = blankToNull(String.valueOf(((TextView) view).getText()));
            if (text != null) {
                for (int i = 0; i < into.length; i++) {
                    if (into[i] == null) {
                        into[i] = text;
                        return;
                    }
                }
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && into[into.length - 1] == null; i++) {
                firstTexts(group.getChildAt(i), into);
            }
        }
    }
}
