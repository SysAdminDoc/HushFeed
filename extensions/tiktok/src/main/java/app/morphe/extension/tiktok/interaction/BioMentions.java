/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
import android.text.style.ReplacementSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Makes the @usernames in a profile's bio open those profiles (#247). TikTok links mentions in
 * captions but shows a bio as plain text whose tap offers a translation. Each valid username gets
 * a span drawn in the link color, and a touch listener takes only the short taps that land on one,
 * so the rest of the bio still translates on a tap and the whole bio copies on a long press. The spans go back on
 * whenever TikTok sets the text again (more, a translation, a recycled header). A tap opens
 * {@code https://www.tiktok.com/@name} inside TikTok, whose own link handling opens the profile.
 *
 * <p>Another app's username named in a bio ("IG: nasa", "snap @nasa") becomes a pill in that
 * app's color under Links to other apps in bios (#549). Its tap hands the account's public web
 * address to Android as an ordinary link, so that app or the browser opens it; nothing is fetched
 * here, and the address is only built from the text the bio already shows.
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
    /**
     * Another app's name and then a username for it on the same line: "IG: nasa", "snap - nasa",
     * "yt @NASA", "X: @nasa" (#549). A bare username needs a colon, a dash or a bar after the
     * app's name, or an @ before it, so "my ig is nasa" stays text. X only counts with a colon, as
     * in OTHER_APP. "tw" is left out: in a bio it's a content warning more often than Twitter, and
     * Discord has no public page for a username.
     */
    static final Pattern SOCIAL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_.@])(?:(ig|insta|instagram|sc|snap|snapchat|yt|youtube|twitter|twitch|ttv|"
                    + "threads|fb|facebook)[ \\t]*(?:[:|\\-][ \\t]*@?|@)|(x)[ \\t]*:[ \\t]*@?)"
                    + "([A-Za-z0-9_.\\-]{1,50})(?![\\p{L}\\p{M}\\p{N}_.\\-@])");
    static final String PROFILE_URL = "https://www.tiktok.com/@";
    /** TikTok's own search link, the one its search history rows open, typed in with the keyword. */
    static final String SEARCH_URL = "snssdk1233://search?enter_from=search_history&enter_method=search_history&keyword=";
    /**
     * Opens the search on its Accounts tab, where the person a name stands for is usually the first
     * row (#247). TikTok's search link reads {@code target_tab} by the tab's own name.
     */
    static final String ACCOUNTS_TAB = "&target_tab=user";
    static final String NO_BROWSER = "No app on this phone opens web links";

    /** Bio views already wired with the watcher and touch listener. Main thread only. */
    private static final Map<TextView, Boolean> WIRED = new WeakHashMap<>();
    /** The span a press started on, so only a press that ends on it opens anything. */
    @Nullable private static BioLink pressed;

    private static final View.OnTouchListener TOUCH = BioMentions::onTouch;

    private BioMentions() {
    }

    static boolean enabled() {
        return SettingsStatus.copyIdsEnabled && Settings.BIO_MENTION_LINKS.get();
    }

    static boolean socialEnabled() {
        return SettingsStatus.copyIdsEnabled && Settings.BIO_SOCIAL_LINKS.get();
    }

    /**
     * The other apps a bio names, each with the address its public pages live under, its own
     * username rule, and the pill's colors (fill, text, and an outline for a black fill that would
     * vanish on TikTok's dark header).
     */
    enum Platform {
        INSTAGRAM("https://www.instagram.com/", "[A-Za-z0-9_.]{1,30}", 0xFFE1306C, Color.WHITE, 0),
        SNAPCHAT("https://www.snapchat.com/add/", "[A-Za-z][A-Za-z0-9_.\\-]{2,14}", 0xFFFFFC00, Color.BLACK, 0),
        YOUTUBE("https://www.youtube.com/@", "[A-Za-z0-9_.\\-]{3,30}", 0xFFFF0000, Color.WHITE, 0),
        X("https://x.com/", "[A-Za-z0-9_]{1,15}", 0xFF000000, Color.WHITE, 0x99FFFFFF),
        TWITCH("https://www.twitch.tv/", "[A-Za-z0-9_]{4,25}", 0xFF9146FF, Color.WHITE, 0),
        THREADS("https://www.threads.com/@", "[A-Za-z0-9_.]{1,30}", 0xFF000000, Color.WHITE, 0x99FFFFFF),
        FACEBOOK("https://www.facebook.com/", "[A-Za-z0-9.]{5,50}", 0xFF1877F2, Color.WHITE, 0);

        final String address;
        final Pattern username;
        final int fill;
        final int ink;
        final int outline;

        Platform(String address, String username, int fill, int ink, int outline) {
            this.address = address;
            this.username = Pattern.compile(username);
            this.fill = fill;
            this.ink = ink;
            this.outline = outline;
        }

        @Nullable
        static Platform named(@Nullable String word) {
            if (word == null) return null;
            switch (word.toLowerCase(Locale.ROOT)) {
                case "ig": case "insta": case "instagram": return INSTAGRAM;
                case "sc": case "snap": case "snapchat": return SNAPCHAT;
                case "yt": case "youtube": return YOUTUBE;
                case "x": case "twitter": return X;
                case "twitch": case "ttv": return TWITCH;
                case "threads": return THREADS;
                case "fb": case "facebook": return FACEBOOK;
                default: return null;
            }
        }
    }

    /** Another app's username in a bio: where its pill starts (its @, when typed) and ends. */
    static final class Social {
        final Platform platform;
        final String username;
        final int start;
        final int end;

        Social(Platform platform, String username, int start, int end) {
            this.platform = platform;
            this.username = username;
            this.start = start;
            this.end = end;
        }
    }

    /** A link this class put on a bio, which only a short tap on it opens. */
    abstract static class BioLink extends ClickableSpan {
    }

    /** A username in a bio opening its profile when tapped, or a display name opening a search. */
    static final class MentionSpan extends BioLink {
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

    /** Another app's username, opening its public page there when tapped. Its pill draws it. */
    static final class SocialSpan extends BioLink {
        final Platform platform;
        final String username;

        SocialSpan(Platform platform, String username) {
            this.platform = platform;
            this.username = username;
        }

        @Override
        public void onClick(View widget) {
            openSocial(widget.getContext(), platform, username);
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            // Left as it is: the pill picks its own colors.
        }
    }

    /** The rounded pill in the other app's color that a [SocialSpan]'s text is drawn on. */
    static final class PillSpan extends ReplacementSpan {
        final Platform platform;

        PillSpan(Platform platform) {
            this.platform = platform;
        }

        private static float pad(Paint paint) {
            return paint.getTextSize() * 0.4f;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            boolean bold = paint.isFakeBoldText();
            paint.setFakeBoldText(true);
            float width = paint.measureText(text, start, end);
            paint.setFakeBoldText(bold);
            return Math.round(width + 2 * pad(paint));
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, @NonNull Paint paint) {
            int color = paint.getColor();
            Paint.Style style = paint.getStyle();
            float stroke = paint.getStrokeWidth();
            boolean bold = paint.isFakeBoldText();
            try {
                paint.setFakeBoldText(true);
                float pad = pad(paint);
                Paint.FontMetrics metrics = paint.getFontMetrics();
                RectF pill = new RectF(x, y + metrics.ascent, x + paint.measureText(text, start, end) + 2 * pad,
                        y + metrics.descent);
                float radius = pill.height() / 2;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(platform.fill);
                canvas.drawRoundRect(pill, radius, radius, paint);
                if (platform.outline != 0) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, paint.getTextSize() / 16));
                    paint.setColor(platform.outline);
                    canvas.drawRoundRect(pill, radius, radius, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                paint.setColor(platform.ink);
                canvas.drawText(text, start, end, x + pad, y, paint);
            } finally {
                paint.setColor(color);
                paint.setStyle(style);
                paint.setStrokeWidth(stroke);
                paint.setFakeBoldText(bold);
            }
        }
    }

    /** From the bio's bind: links what it shows now and whenever its text changes. */
    static void onBio(TextView view) {
        if (!enabled() && !socialEnabled()) return;
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
                    if (TextUtils.indexOf(text, '@') >= 0 || (socialEnabled() && SOCIAL.matcher(text).find())) {
                        view.post(() -> link(view));
                    }
                }
            });
            view.setOnTouchListener(TOUCH);
        }
        view.post(() -> link(view));
    }

    /** Puts a span on each username [view] shows that has none, leaving TikTok's own links alone. */
    static void link(TextView view) {
        try {
            boolean mentions = enabled();
            boolean socials = socialEnabled();
            if (!mentions && !socials) return;
            CharSequence text = view.getText();
            if (text == null) return;
            List<int[]> found = mentions && TextUtils.indexOf(text, '@') >= 0
                    ? find(text) : Collections.emptyList();
            List<Social> pills = socials ? socials(text) : Collections.emptyList();
            Spannable spans = text instanceof Spannable ? (Spannable) text : null;
            if (spans != null) {
                for (BioLink old : spans.getSpans(0, spans.length(), BioLink.class)) spans.removeSpan(old);
                for (PillSpan old : spans.getSpans(0, spans.length(), PillSpan.class)) spans.removeSpan(old);
            }
            if (found.isEmpty() && pills.isEmpty()) return;
            if (spans == null) spans = new SpannableString(text);
            for (int[] at : found) {
                if (spans.getSpans(at[0], at[1], ClickableSpan.class).length > 0) continue;
                spans.setSpan(new MentionSpan(text.subSequence(at[0] + 1, at[1]).toString()),
                        at[0], at[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            for (Social pill : pills) {
                if (spans.getSpans(pill.start, pill.end, ClickableSpan.class).length > 0) continue;
                spans.setSpan(new SocialSpan(pill.platform, pill.username), pill.start, pill.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spans.setSpan(new PillSpan(pill.platform), pill.start, pill.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    /** Each other app's username [text] names, when it's one that app allows. */
    static List<Social> socials(CharSequence text) {
        List<Social> found = new ArrayList<>();
        Matcher matcher = SOCIAL.matcher(text);
        while (matcher.find()) {
            Platform platform = Platform.named(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            int start = matcher.start(3);
            // A period or a dash after the name ends the sentence; no app's usernames end in one.
            int end = matcher.end(3);
            while (end > start && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == '-')) end--;
            String username = text.subSequence(start, end).toString();
            if (platform == null || !platform.username.matcher(username).matches()) continue;
            if (start > 0 && text.charAt(start - 1) == '@') start--;
            found.add(new Social(platform, username, start, end));
        }
        return found;
    }

    /**
     * TikTok's view sees every press, so a long press anywhere still copies the bio. Only a short
     * tap that starts and ends on the same link is taken: TikTok's press is cancelled, so it
     * neither translates nor long-presses, and the link opens.
     */
    private static boolean onTouch(View v, MotionEvent event) {
        if (!(v instanceof TextView)) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = enabled() || socialEnabled() ? spanAt((TextView) v, event.getX(), event.getY()) : null;
                return false;
            case MotionEvent.ACTION_UP: {
                BioLink started = pressed;
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

    /** The username or pill span under a touch at ([x], [y]) in [view], if any. */
    @Nullable
    static BioLink spanAt(TextView view, float x, float y) {
        CharSequence text = view.getText();
        Layout layout = view.getLayout();
        if (layout == null || !(text instanceof Spanned)) return null;
        int left = (int) x - view.getTotalPaddingLeft() + view.getScrollX();
        int top = (int) y - view.getTotalPaddingTop() + view.getScrollY();
        if (top < 0 || top > layout.getHeight()) return null;
        int line = layout.getLineForVertical(top);
        if (left < layout.getLineLeft(line) || left > layout.getLineRight(line)) return null;
        int offset = layout.getOffsetForHorizontal(line, left);
        BioLink[] spans = ((Spanned) text).getSpans(offset, offset, BioLink.class);
        return spans.length == 0 ? null : spans[0];
    }

    /** Opens [handle]'s profile, or a search for a display name, through TikTok's own links, never another app. */
    static boolean open(@Nullable Context context, String handle) {
        try {
            if (context == null) return false;
            String link = MENTION.matcher("@" + handle).matches()
                    ? PROFILE_URL + handle
                    : DISPLAY_NAME.matcher("@" + handle).matches() || DATED_NAME.matcher("@" + handle).matches()
                    ? SEARCH_URL + Uri.encode(handle) + ACCOUNTS_TAB : null;
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

    /**
     * Opens [username]'s public page on [platform]'s website as an ordinary web link, which that
     * app (when it's installed and claims its links) or the browser opens. Nothing is fetched here.
     */
    static boolean openSocial(@Nullable Context context, Platform platform, String username) {
        try {
            if (context == null || !platform.username.matcher(username).matches()) return false;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(platform.address + username))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception would close TikTok.
            Utils.showToastShort(L10n.t(NO_BROWSER));
            return false;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open " + platform + " for " + username, ex);
            return false;
        }
    }
}
