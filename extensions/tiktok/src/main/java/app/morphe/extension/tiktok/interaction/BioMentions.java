/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Makes the @usernames in a profile's bio open those profiles (#247). TikTok links mentions in
 * captions but shows a bio as plain text whose tap offers a translation. Each valid username gets
 * a span drawn in the link color, and a touch listener takes only the short taps that land on one,
 * so the rest of the bio still translates on a tap and the whole bio copies on a long press. The spans go back on
 * whenever TikTok sets the text again (more, a translation, a recycled header). A tap opens
 * {@code https://www.tiktok.com/@name} inside TikTok, whose own link handling opens the profile.
 */
public final class BioMentions {
    /**
     * A TikTok username after an @: 2 to 24 letters, digits, underscores and periods, not ending
     * in a period. One glued to a word or an email address, or running past 24, isn't a mention,
     * and neither is the start of a word a username can't spell ("@José" isn't @Jos) or one with
     * a date glued on ("@davidj11/30/24" names somebody, but not the account @davidj11). The date
     * check looks past the end of the match, so a dotted handle can't back off to the part before
     * its last period ("@john.doe11/30/24" isn't @john either).
     */
    static final Pattern MENTION = Pattern.compile(
            "(?<![A-Za-z0-9_.@])@([A-Za-z0-9_][A-Za-z0-9_.]{0,22}[A-Za-z0-9_])(?![\\p{L}\\p{M}\\p{N}_@]|[A-Za-z0-9_.]*/\\p{N})");
    /**
     * A display name typed after an @ ("@First Last"), up to four words on one line that each start
     * with a capital. Only a username can open a profile, and linking the first word alone opened
     * a stranger or nothing, so the whole name becomes one link to TikTok's search. A word may end
     * in a glued date ("@Hannah Raines11/30/24"), which people add to a partner's name.
     */
    private static final Pattern DISPLAY_NAME = Pattern.compile(
            "(?<![\\p{L}\\p{N}_.@])@(\\p{Lu}[\\p{L}\\p{M}'’\\-]*(?:[ \\u00A0]\\p{Lu}[\\p{L}\\p{M}\\p{N}'’\\-]*(?:/\\p{N}{1,4}){0,2}){1,3})");
    /**
     * A one-word display name with a date glued on ("@Davidjennings11/30/24"). Without the date it
     * reads as a username, so only the date makes it a name, searched as a whole.
     */
    private static final Pattern DATED_NAME = Pattern.compile(
            "(?<![\\p{L}\\p{N}_.@])@(\\p{Lu}[\\p{L}\\p{M}'’\\-]*\\p{N}{1,4}(?:/\\p{N}{1,4}){1,2})(?![\\p{L}\\p{N}/])");
    /** Whichever starts first, and a display name when both start at the same @. */
    private static final Pattern NAME_OR_MENTION = Pattern.compile(
            DISPLAY_NAME.pattern() + "|" + DATED_NAME.pattern() + "|" + MENTION.pattern());
    /**
     * Another app named just before the @ on the same line ("IG: @name", "snap @name"). That
     * username belongs to the other app, and the TikTok account of the same name may be a stranger.
     */
    private static final Pattern OTHER_APP = Pattern.compile(
            "(?i)(?:^|[^\\p{L}\\p{N}])(?:ig|insta(?:gram)?|twitter|tw|yt|youtube|snap(?:chat)?|sc|threads|"
                    + "twitch|fb|facebook|discord|x(?=\\s*:))\\s*[:\\-|]?\\s*$");
    static final String PROFILE_URL = "https://www.tiktok.com/@";
    /** TikTok's own search link, the one its search history rows open, typed in with the keyword. */
    static final String SEARCH_URL = "snssdk1233://search?enter_from=search_history&enter_method=search_history&keyword=";

    /** Bio views already wired with the watcher and touch listener. Main thread only. */
    private static final Map<TextView, Boolean> WIRED = new WeakHashMap<>();
    /** The span a press started on, so only a press that ends on it opens the profile. */
    @Nullable private static MentionSpan pressed;

    private static final View.OnTouchListener TOUCH = BioMentions::onTouch;

    private BioMentions() {
    }

    static boolean enabled() {
        return SettingsStatus.copyIdsEnabled && Settings.BIO_MENTION_LINKS.get();
    }

    /** A username in a bio opening its profile when tapped, or a display name opening a search. */
    static final class MentionSpan extends ClickableSpan {
        final String handle;

        MentionSpan(String handle) {
            this.handle = handle;
        }

        @Override
        public void onClick(View widget) {
            open(widget.getContext(), handle);
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            // A text view with no link color would draw the name invisible; bold still marks it.
            if (paint.linkColor != 0) paint.setColor(paint.linkColor);
            paint.setFakeBoldText(true);
            paint.setUnderlineText(false);
        }
    }

    /** From the bio's bind: links what it shows now and whenever its text changes. */
    static void onBio(TextView view) {
        if (!enabled()) return;
        if (WIRED.put(view, Boolean.TRUE) == null) {
            view.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable text) {
                    // Not from inside TikTok's setText: the spans go on once it has finished.
                    if (TextUtils.indexOf(text, '@') >= 0) view.post(() -> link(view));
                }
            });
            view.setOnTouchListener(TOUCH);
        }
        view.post(() -> link(view));
    }

    /** Puts a span on each username [view] shows that has none, leaving TikTok's own links alone. */
    static void link(TextView view) {
        try {
            if (!enabled()) return;
            CharSequence text = view.getText();
            if (text == null || TextUtils.indexOf(text, '@') < 0) return;
            List<int[]> found = find(text);
            Spannable spans = text instanceof Spannable ? (Spannable) text : null;
            if (spans != null) {
                for (MentionSpan old : spans.getSpans(0, spans.length(), MentionSpan.class)) spans.removeSpan(old);
            }
            if (found.isEmpty()) return;
            if (spans == null) spans = new SpannableString(text);
            for (int[] at : found) {
                if (spans.getSpans(at[0], at[1], ClickableSpan.class).length > 0) continue;
                spans.setSpan(new MentionSpan(text.subSequence(at[0] + 1, at[1]).toString()),
                        at[0], at[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (spans != text) view.setText(spans);
            else view.invalidate();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not link the bio's usernames", ex);
        }
    }

    /** Where each TikTok username or display name in [text] starts (its @) and ends. */
    static List<int[]> find(CharSequence text) {
        List<int[]> found = new ArrayList<>();
        Matcher matcher = NAME_OR_MENTION.matcher(text);
        while (matcher.find()) {
            int lineStart = TextUtils.lastIndexOf(text, '\n', matcher.start()) + 1;
            if (OTHER_APP.matcher(text.subSequence(lineStart, matcher.start())).find()) continue;
            found.add(new int[]{matcher.start(), matcher.end()});
        }
        return found;
    }

    /**
     * TikTok's view sees every press, so a long press anywhere still copies the bio. Only a short
     * tap that starts and ends on the same username is taken: TikTok's press is cancelled, so it
     * neither translates nor long-presses, and the profile opens.
     */
    private static boolean onTouch(View v, MotionEvent event) {
        if (!(v instanceof TextView)) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = enabled() ? spanAt((TextView) v, event.getX(), event.getY()) : null;
                return false;
            case MotionEvent.ACTION_UP: {
                MentionSpan started = pressed;
                pressed = null;
                if (started == null || started != spanAt((TextView) v, event.getX(), event.getY())) return false;
                if (event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) return false;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                v.onTouchEvent(cancel);
                cancel.recycle();
                started.onClick(v);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                return false;
            default:
                return false;
        }
    }

    /** The username span under a touch at ([x], [y]) in [view], if any. */
    @Nullable
    static MentionSpan spanAt(TextView view, float x, float y) {
        CharSequence text = view.getText();
        Layout layout = view.getLayout();
        if (layout == null || !(text instanceof Spanned)) return null;
        int left = (int) x - view.getTotalPaddingLeft() + view.getScrollX();
        int top = (int) y - view.getTotalPaddingTop() + view.getScrollY();
        if (top < 0 || top > layout.getHeight()) return null;
        int line = layout.getLineForVertical(top);
        if (left < layout.getLineLeft(line) || left > layout.getLineRight(line)) return null;
        int offset = layout.getOffsetForHorizontal(line, left);
        MentionSpan[] spans = ((Spanned) text).getSpans(offset, offset, MentionSpan.class);
        return spans.length == 0 ? null : spans[0];
    }

    /** Opens [handle]'s profile, or a search for a display name, through TikTok's own links, never another app. */
    static boolean open(@Nullable Context context, String handle) {
        try {
            if (context == null) return false;
            String link = MENTION.matcher("@" + handle).matches()
                    ? PROFILE_URL + handle
                    : DISPLAY_NAME.matcher("@" + handle).matches() || DATED_NAME.matcher("@" + handle).matches()
                    ? SEARCH_URL + Uri.encode(handle) : null;
            if (link == null) return false;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .setPackage(context.getPackageName());
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open @" + handle, ex);
            return false;
        }
    }
}
