/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.graphics.Color;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Fold long comments: a comment longer than the chosen number of lines shows that many, ending
 * in "more", and a tap on more opens the rest in place (Metra #212).
 *
 * <p>The comment's text view is found the way {@link CommentLinks} finds it, by the comment it
 * shows. The fold is a shorter text set on that view, so TikTok's next bind of the row puts its
 * own full text back: a row reused for another comment is folded fresh instead of keeping the
 * last one's open state, and turning the setting off leaves every row stock once it's bound
 * again. A row bound again for the same comment, as its like count changes, stays the way the
 * reader left it.
 */
public final class CommentFold {
    private static final String FAMILY = "fold long comments";

    /** Deep enough for a comment cell, the same reach {@link CommentLinks} has. */
    private static final int MAX_DEPTH = 8;

    /**
     * A comment this short with this few line breaks can't fill more lines than are kept, so
     * the cell isn't searched for it. Generous: even a narrow row of wide characters holds ten.
     */
    private static final int MIN_CHARS_PER_LINE = 10;

    /** Which comment each folded or opened text view shows. Main thread only. */
    private static final Map<TextView, Shown> SHOWN = new WeakHashMap<>();

    private CommentFold() {
    }

    /** The comment a text view was given and whether the reader opened it. */
    private static final class Shown {
        final String id;
        final boolean opened;

        Shown(String id, boolean opened) {
            this.id = id;
            this.opened = opened;
        }
    }

    public static boolean enabled() {
        return Settings.FOLD_LONG_COMMENTS.get();
    }

    /** Called from the posted cell bind, with the comment that row is showing. */
    public static void apply(View cell, Object comment) {
        if (cell == null || comment == null) return;
        try {
            if (!enabled()) return;
            // The dialog keeps it to 1 to 20, a restored backup is never asked.
            int lines = Math.max(1, Math.min(20, Settings.FOLD_LONG_COMMENTS_LINES.get()));
            String raw = Reflect.string(comment, "getText", "text");
            if (raw == null) return;
            String text = raw.trim();
            if (text.length() < lines * MIN_CHARS_PER_LINE && breaks(text) < lines) return;

            // A row TikTok didn't give its text again still shows the fold, which no longer
            // carries the whole comment, so it isn't found and stays as it is.
            TextView view = findCommentTextView(cell, text, 0);
            if (view == null) return;
            String id = Objects.toString(Reflect.string(comment, "getCid", "cid"), text);
            Shown shown = SHOWN.get(view);
            if (shown != null && shown.opened && shown.id.equals(id)) return;
            SHOWN.remove(view);
            if (view.getLayout() != null && view.getWidth() > 0) {
                fold(view, text, lines, id);
                return;
            }
            // Not laid out yet, and the lines depend on its width: fold on its first layout.
            view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    v.removeOnLayoutChangeListener(this);
                    v.post(() -> foldSafely(view, text, lines, id));
                }
            });
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "fold", ex);
            Logger.printException(() -> "Could not fold a long comment", ex);
        }
    }

    private static void foldSafely(TextView view, String comment, int lines, String id) {
        try {
            if (enabled()) fold(view, comment, lines, id);
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "fold", ex);
            Logger.printException(() -> "Could not fold a long comment", ex);
        }
    }

    private static int breaks(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    /** The view whose text carries the whole comment; the username's never does. */
    private static TextView findCommentTextView(View view, String comment, int depth) {
        if (view == null || depth > MAX_DEPTH) return null;
        if (view instanceof TextView) {
            CharSequence shown = ((TextView) view).getText();
            return shown != null && shown.toString().contains(comment) ? (TextView) view : null;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = findCommentTextView(group.getChildAt(index), comment, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Cuts the text after {@code lines} lines and ends it with "more". */
    private static void fold(TextView view, String comment, int lines, String id) {
        Layout layout = view.getLayout();
        int width = view.getWidth() - view.getTotalPaddingLeft() - view.getTotalPaddingRight();
        CharSequence full = view.getText();
        // A row rebound for another comment before its first layout shows that one now.
        if (layout == null || width <= 0 || full == null || !full.toString().contains(comment)) return;
        if (layout.getLineCount() <= lines) return;

        String more = L10n.t("… more");
        TextPaint paint = view.getPaint();
        float suffix = paint.measureText(more);
        int start = layout.getLineStart(lines - 1);
        int cut = layout.getLineEnd(lines - 1);
        // Back off until the kept part of the last line and "... more" fit on it together.
        while (cut > start && (Character.isWhitespace(full.charAt(cut - 1))
                || paint.measureText(full, start, cut) + suffix > width)) {
            cut--;
        }
        if (cut > 0 && Character.isHighSurrogate(full.charAt(cut - 1))) cut--;
        if (cut <= 0) return;

        SpannableStringBuilder folded = new SpannableStringBuilder(full, 0, cut);
        folded.append(more);
        folded.setSpan(new More(view, full, id), cut, folded.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Read before setText, the way CommentLinks does: a movement method of ours makes the
        // view clickable, and a clickable view would take the row's own tap to reply.
        MovementMethod before = view.getMovementMethod();
        boolean wasClickable = view.isClickable();
        boolean wasLongClickable = view.isLongClickable();
        view.setText(folded);
        if (!(before instanceof LinkMovementMethod)) {
            view.setMovementMethod(CommentLinks.LinkOnlyMovementMethod.INSTANCE);
            view.setClickable(wasClickable);
            view.setLongClickable(wasLongClickable);
        }
        SHOWN.put(view, new Shown(id, false));
        HookStatus.bound(FAMILY, "folded");
    }

    /** The "more" at the end of a fold, which puts TikTok's whole text back. */
    static final class More extends ClickableSpan {
        private final TextView view;
        private final CharSequence full;
        private final String id;

        More(TextView view, CharSequence full, String id) {
            this.view = view;
            this.full = full;
            this.id = id;
        }

        @Override public void onClick(@NonNull View widget) {
            view.setText(full);
            SHOWN.put(view, new Shown(id, true));
        }

        /** In the comment's own color, dimmed the way TikTok dims its own "more", not as a link. */
        @Override public void updateDrawState(@NonNull TextPaint paint) {
            int color = view.getCurrentTextColor();
            paint.setColor(Color.argb(Color.alpha(color) * 6 / 10,
                    Color.red(color), Color.green(color), Color.blue(color)));
            paint.setFakeBoldText(true);
            paint.setUnderlineText(false);
        }
    }

    static void resetForTests() {
        SHOWN.clear();
    }
}
