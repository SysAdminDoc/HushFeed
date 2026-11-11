/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes request addresses, credentials, device identifiers and the details that name a person
 * before diagnostics are stored.
 */
public final class DiagnosticRedactor {
    private static final int MAX_CHARS = 64_000;
    private static final String TRUNCATED = "\n[diagnostic text truncated]";
    private static final String HOST_SUFFIXES =
            "(?:tiktokv?\\.com|tiktokv\\.(?:us|eu)|tiktokcdn(?:-us|-eu)?\\.com|byteoversea\\.com"
                    + "|bytedance\\.com|musical\\.ly|ibyteimg\\.com)";
    private static final String[] CREDENTIAL_NAMES = {"token", "session", "sid", "secret",
            "password", "passwd", "signature", "cookie", "auth", "credential", "device_id",
            "deviceid", "install_id", "installid", "iid", "openudid", "odin", "ttwid", "uid",
            "sec_user_id", "secuid"};
    /**
     * Names carrying the id of one video, comment or message. Each of these resolves to a post
     * somebody can open, so a shared report would otherwise carry a slice of what was watched.
     * They are matched whole rather than as a substring, so an ordinary setting such as
     * {@code hide_paid_partnership} keeps its value.
     */
    private static final String[] CONTENT_ID_NAMES = {"aid", "awemeid", "aweme_id", "itemid",
            "item_id", "groupid", "group_id", "cid", "commentid", "comment_id", "msgid",
            "msg_id", "messageid", "message_id"};
    /**
     * Names carrying who an account is or how to reach it, the way TikTok's own JSON spells
     * them. Matched whole, so a switch such as {@code copy_comments_without_username} keeps its
     * value.
     */
    private static final String[] PERSONAL_NAMES = {"username", "user_name", "uniqueid",
            "unique_id", "nickname", "nick_name", "display_name", "displayname", "email", "phone",
            "phone_number", "phonenumber", "mobile"};
    /**
     * An email address, and a phone number written the international way, its 10 to 15 digits
     * run together or split by spaces, dashes, dots or brackets. A sign-in or contact screen's own
     * log line can carry either. The domain is one flat class with no repeated group, for the same
     * reason as {@link #HOSTS}. A drawable's density suffix such as ic_like@2x.webp is a file name,
     * while dana@2x.com is an address. No country code starts with 0, so a time zone offset such
     * as +0500 is not a phone number. One from +1000 to +1400 is known by a date from 1900 to 2099
     * after it, or by the clock in front of it when no more digits follow or
     * logcat's process, thread and level columns do, each padded to five wide and no larger than a
     * pid can be (4,199,999 here, a little over pid_max), so a number after a clock still goes. A
     * number never ends right before a dot and a digit, so an IPv4 address after it stays whole.
     */
    private static final String EMAIL = "(?<![\\w.+-])[\\w.+-]+@"
            + "(?!\\d(?:\\.\\d)?x\\.(?i:png|apng|webp|jpe?g|gif|svg|bmp|heic|heif|avif|jxl|tiff?|json|ico|xml"
            + "|webm|mp4|lottie|ttf|otf|9\\.png)(?![\\w.-]))"
            + "[A-Za-z0-9-][A-Za-z0-9.-]*\\.[A-Za-z]{2,}(?![\\w-])";
    private static final String PHONE = "(?<![\\w+])"
            + "(?!(?<=\\d:\\d\\d(?:\\.\\d{1,9})?[ ]{1,2})\\+1[0-4]\\d\\d(?![ ().-]{0,2}\\d)"
            + "|(?<=\\d:\\d\\d\\.\\d{3}(?:\\d{3})?[ ]{1,2})\\+1[0-4]\\d\\d"
            + "(?:[ ](?:[ ]{4}\\d|[ ]{3}\\d\\d|[ ]{2}\\d{3}|[ ]\\d{4}|\\d{5,6}|[1-3]\\d{6}|4[01]\\d{5})){2}[ ][VDIWEFA][ /])"
            + "\\+(?=[1-9])(?!\\d{1,4}[ ]+(?:19|20)\\d\\d-[01]\\d-[0-3]\\d(?![\\d-]))"
            + "(?:\\d[ ().-]{0,2}){9,14}\\d(?!\\.?\\d)";
    /**
     * A bare id, for the places that print a list of them with no name in front. TikTok's ids run
     * to nineteen digits; nothing else these reports carry is a number that long.
     */
    private static final String BARE_CONTENT_ID = "(?<!\\d)\\d{17,21}(?!\\d)";
    /**
     * A network address, the way Java prints one: "failed to connect to host/1.2.3.4 (port 443)
     * from /2607:fb90:... (port 38754)". The second is the phone's own, and on an IPv6 network it
     * is routable and names the subscriber. IPv6 needs "::" or four colons, so a clock time such
     * as 10:23:11 is not taken for one. A period after an address ends the sentence, while one
     * followed by more digits makes a version number.
     */
    private static final String IPV4 = "(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\w|\\.\\w)";
    private static final String IPV6 = "(?<![\\w:.])(?=[0-9A-Fa-f:]*::|(?:[0-9A-Fa-f]{0,4}:){4})"
            + "[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?:(?:\\.\\d{1,3}){3})?(?:%[\\w.]+)?(?![\\w:])";
    /**
     * The name in front of such an address (or of Java's "/&lt;unresolved&gt;"), the one a failed
     * lookup quotes, and the one a proxy prints as "HTTP @ name:port". Each can be the reader's
     * own server, a proxy or a router, which no list of TikTok's hosts covers, and a home network
     * names them in any language. The IPv6 test is {@link #IPV6}'s, so a slash in front of a clock
     * time does not make a name, and newer Java puts the address in brackets. A name can follow a
     * dash standing on its own, as in "via -name/...", but never one inside a run of hyphenated
     * words, so a long run is tried once from its start instead of from every word in it.
     */
    private static final String RESOLVED_NAME = "(?<![\\p{L}\\p{N}\\p{M}\\p{Cs}_.])(?<![\\p{L}\\p{N}\\p{M}\\p{Cs}_.-]-)"
            + "[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}_.-]*+"
            + "(?=/(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\w|\\.\\w)|/<unresolved>"
            + "|/\\[?(?=[0-9A-Fa-f:]*::|(?:[0-9A-Fa-f]{0,4}:){4})[0-9A-Fa-f]{0,4}:)"
            + "|(?<=(?:HTTP|SOCKS) @ )[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}_.-]*+(?=:\\d{1,5}(?!\\d))"
            + "|(?<=Unable to resolve host \\\\{0,3}\")[^\"\\\\\\n]+";
    /**
     * A creator's name, as the bundle writes it into a toast or a banner: between Unicode's
     * first-strong isolate U+2068 and its pop U+2069. Every toast is written to the buffer as it
     * is shown, in whatever language the phone speaks, so the name cannot be found by the words
     * around it. The isolate pair marks it in every language. A run with no pop is cut to the
     * end of the line, so a name that lost its closing mark is still not printed.
     */
    private static final String ISOLATED_NAME = "⁨[^⁩\\n]*⁩?";
    /**
     * An account handle, which starts with @ and stands on its own. One glued to something in
     * front of it is not a handle: an email address, whose name can end in a dash or a plus, or
     * the identity hash Java prints after a class name. After a word and a dash or a plus it is a
     * handle when no domain or version follows and it has a letter, so cc-@dana.q goes, a-@x.com is
     * left for {@link #EMAIL}, and lib-@1.2.3, lib-@v1.2.3 or C++@foo stays.
     */
    private static final String HANDLE = "(?<![\\w.+\\-@/:])@[A-Za-z0-9_.]{2,}"
            + "|(?<=\\w[+-])@(?![A-Za-z0-9-][A-Za-z0-9.-]*\\.[A-Za-z]{2,}(?![\\w-]))(?![vV]?\\d[\\d.]*+(?![A-Za-z_]))"
            + "(?=[0-9.]*[A-Za-z_])[A-Za-z0-9_.]{2,}";

    private static final Pattern NAMES = Pattern.compile(ISOLATED_NAME);
    private static final Pattern HANDLES = Pattern.compile(HANDLE);
    private static final Pattern URLS = Pattern.compile(
            "(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*:(?:\\\\?/){2}[^\\s\"'<>]+");
    // A flat character class avoids the recursive (label.)+ match. A long dotted value could
    // overflow the regex stack before either an export or a logging hook finished.
    private static final Pattern HOSTS = Pattern.compile(
            "(?i)(?<![a-z0-9.-])[a-z0-9.-]+\\." + HOST_SUFFIXES + "\\b(?:[:/][^\\s\"'<>]*)?");
    private static final Pattern RESOLVED_NAMES = Pattern.compile(RESOLVED_NAME);
    private static final Pattern IDS = Pattern.compile(BARE_CONTENT_ID);
    private static final Pattern EMAILS = Pattern.compile(EMAIL);
    private static final Pattern PHONES = Pattern.compile(PHONE);
    private static final Pattern ADDRESSES = Pattern.compile(IPV4 + "|" + IPV6);
    private static final Pattern ASSIGNMENTS = Pattern.compile(
            "(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)(\\\\*[\"'])?[ \\t]*[=:][ \\t]*");
    /** The next field after a separator, so "Dana, Queen of Cats" is one name and "Dana, uniqueId=dq" is not. */
    private static final Pattern NEXT_FIELD = Pattern.compile(
            "[ \\t]*(?:\\\\*[\"'])?[A-Za-z_][A-Za-z0-9_-]*(?:\\\\*[\"'])?[ \\t]*[=:]");
    /** What a backslash escapes in a JSON or Java string; anything else after one is a path. */
    private static final String JSON_ESCAPED = "\"'\\/bfnrtu";
    /**
     * A stack trace's line. A frame names a dotted method and its place in the source, so "at Dana(DQ)"
     * is a name. Flat classes, for the same reason as {@link #HOSTS}.
     */
    private static final Pattern STACK_FRAME = Pattern.compile(
            "at [\\p{L}\\p{M}\\p{N}_$<>@-]+[./][\\p{L}\\p{M}\\p{N}_$<>@./-]*"
                    + "\\((?:[\\p{L}\\p{M}\\p{N}_$.-]*:\\d+|[\\p{L}\\p{M}\\p{N}_$-]+\\.(?:java|kt)"
                    + "|Unknown Source(?::\\d+)?|SourceFile|Native Method)\\)"
                    + "|\\.\\.\\. \\d+ more\\b|Caused by: |Suppressed: ");
    /** An HTML entity, so the semicolon in "Dana&amp;Co: Bakery" does not end the name. */
    private static final Pattern ENTITY = Pattern.compile("&(?:[A-Za-z]{2,8}|#\\d{1,7}|#[xX][0-9A-Fa-f]{1,6});");

    private DiagnosticRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            boolean truncated = text.length() > MAX_CHARS;
            if (truncated) text = prefix(text, MAX_CHARS);
            text = NAMES.matcher(text).replaceAll("[name omitted]");
            text = HANDLES.matcher(text).replaceAll("[handle omitted]");
            text = redactFields(text);
            text = URLS.matcher(text).replaceAll("[url omitted]");
            // After the handles, which leave an address alone, and after the URLs, so a user
            // name inside a link goes with the link.
            text = EMAILS.matcher(text).replaceAll("[email omitted]");
            text = PHONES.matcher(text).replaceAll("[phone omitted]");
            text = HOSTS.matcher(text).replaceAll("[host omitted]");
            text = RESOLVED_NAMES.matcher(text).replaceAll("[host omitted]");
            text = ADDRESSES.matcher(text).replaceAll("[address omitted]");
            text = IDS.matcher(text).replaceAll("[id omitted]");
            return truncated || text.length() > MAX_CHARS
                    ? prefix(text, MAX_CHARS - TRUNCATED.length()) + TRUNCATED : text;
        } catch (Throwable failure) {
            // A diagnostic must neither escape into a host hook nor fall back to its raw input.
            return "[diagnostic text omitted after redaction failure]";
        }
    }

    private static String redactFields(String text) {
        Matcher fields = ASSIGNMENTS.matcher(text);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        // Which quotes the line leaves open in front of a field, counted once as the fields go by.
        int quotesRead = 0;
        boolean inDouble = false;
        boolean inSingle = false;
        while (fields.find()) {
            String name = fields.group(1).toLowerCase(Locale.ROOT);
            // A key on a string's next line, as in "a\nunique_id: dq", has the escape's letter in front.
            if (name.length() > 1 && "nrt".indexOf(name.charAt(0)) >= 0 && escapedAt(text, fields.start())
                    && !isField(name) && isField(name.substring(1))) name = name.substring(1);
            boolean credential = isCredential(name);
            boolean contentId = isContentId(name);
            // A name or an address can run to several words, so the value goes to the line's end.
            boolean words = !credential && !contentId && isPersonal(name);
            if (!credential && !contentId && !words) continue;
            boolean quotedName = fields.group(2) != null;
            boolean header = !quotedName && (name.equals("authorization")
                    || name.equals("proxy-authorization") || name.equals("cookie") || name.equals("set-cookie"));
            int start = fields.end();
            if (start == text.length()) continue;
            int quote = start;
            while (quote < text.length() && text.charAt(quote) == '\\') quote++;
            boolean quoted = quote < text.length()
                    && (text.charAt(quote) == '"' || text.charAt(quote) == '\'');
            int end;
            String replacement = "[omitted]";
            if (header) {
                // HTTP header values occupy the line even if an opaque token happens to start
                // with a quote, bracket or our own replacement marker.
                end = start;
                while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            } else if (quoted) {
                end = quotedEnd(text, start, quote);
                String delimiter = text.substring(start, quote + 1);
                replacement = delimiter + replacement + delimiter;
            } else if (quotedName && (text.charAt(start) == '{' || text.charAt(start) == '[')) {
                end = structuredEnd(text, start);
                if (!text.regionMatches(start, "[omitted]", 0, end - start)
                        || end - start != "[omitted]".length()) replacement = "\"[omitted]\"";
            } else if (words) {
                // A name keeps the brackets it opens, a smiley's bracket, an apostrophe inside a
                // word and any quote when the field sits in no string: [DQ] Dana "Dee" O'Brien :).
                // It ends at the bracket holding the field, at a line break, at the quote closing
                // the string the field sits in or that string's escaped line break, or at a
                // separator with another field after it.
                int from = fields.start();
                if (quotedName && from > 0 && "\"'".indexOf(text.charAt(from - 1)) >= 0) from--;
                for (; quotesRead < from; quotesRead++) {
                    char c = text.charAt(quotesRead);
                    if (c == '\n' || c == '\r') { inDouble = false; inSingle = false; }
                    else if ((c == '"' || c == '\'') && escapedAt(text, quotesRead)) continue;
                    else if (c == '"') inDouble = !inDouble;
                    else if (c == '\'' && !inWord(text, quotesRead)) inSingle = !inSingle;
                }
                // A value can start on the string's next line, as in "phone:\n2025550123", unless
                // that line is a stack frame or another field of ours and this one was empty.
                int value = start;
                while ((inDouble || inSingle) && value + 1 < text.length() && text.charAt(value) == '\\'
                        && "nrt".indexOf(text.charAt(value + 1)) >= 0 && !pathAhead(text, value + 2)) {
                    value += 2;
                    while (value < text.length() && (text.charAt(value) == ' ' || text.charAt(value) == '\t')) value++;
                }
                Matcher key = ASSIGNMENTS.matcher(text).region(value, text.length());
                if (!STACK_FRAME.matcher(text).region(value, text.length()).lookingAt()
                        && !(key.lookingAt() && isField(key.group(1).toLowerCase(Locale.ROOT)))) start = value;
                char around = openAround(text, fields.start());
                StringBuilder opened = new StringBuilder();
                end = start;
                boolean path = false;
                while (end < text.length()) {
                    char c = text.charAt(end);
                    if ((inDouble || inSingle) && end > start && c < 128 && Character.isLetter(c)
                            && !Character.isLetterOrDigit(text.charAt(end - 1)) && "_-".indexOf(text.charAt(end - 1)) < 0) {
                        // A field of ours inside the name, as in "phone:\nlookup username=\"dq\"", goes
                        // with it whole: its value's escaped quote would end the run and leave the value.
                        // Only at a word's start, so a hyphenated run is read once. A key after an
                        // escaped tab or line break has the escape's letter in front.
                        Matcher inner = ASSIGNMENTS.matcher(text).region(end, text.length());
                        boolean ours = inner.lookingAt() && isField(inner.group(1).toLowerCase(Locale.ROOT));
                        if (!ours && "bfnrt".indexOf(c) >= 0 && escapedAt(text, end)) {
                            inner.region(end + 1, text.length());
                            ours = inner.lookingAt() && isField(inner.group(1).toLowerCase(Locale.ROOT));
                        }
                        if (ours) {
                            int innerQuote = inner.end();
                            while (innerQuote < text.length() && text.charAt(innerQuote) == '\\') innerQuote++;
                            char opens = innerQuote < text.length() ? text.charAt(innerQuote) : 0;
                            // An unescaped quote of the string's own kind closes the string instead.
                            if ((opens == '"' || opens == '\'') && !(innerQuote == inner.end()
                                    && (opens == '"' ? inDouble : inSingle))) {
                                end = innerQuotedEnd(text, inner.end(), innerQuote, inDouble, inSingle);
                                continue;
                            }
                        }
                    }
                    if (c == '\\') {
                        // An odd run escapes what follows it; an even one is backslashes, as in C:\\new.
                        // A drive, or a backslash before what JSON can't escape here or further along
                        // the word, makes the name a path, so C:\new and Files\new\Dana hold no line break.
                        int next = end;
                        while (next < text.length() && text.charAt(next) == '\\') next++;
                        char escaped = next < text.length() ? text.charAt(next) : 0;
                        boolean odd = (next - end) % 2 == 1;
                        if (end >= 2 && text.charAt(end - 1) == ':' && Character.isLetter(text.charAt(end - 2))
                                && (end == 2 || !Character.isLetterOrDigit(text.charAt(end - 3)))
                                || odd && JSON_ESCAPED.indexOf(escaped) < 0) path = true;
                        if (odd && (escaped == '"' ? inDouble : escaped == '\'' ? inSingle
                                : (escaped == 'n' || escaped == 'r') && (inDouble || inSingle)
                                    && !path && !pathAhead(text, next + 1))) break;
                        end = next;
                        continue;
                    }
                    if (c == '&') {
                        Matcher entity = ENTITY.matcher(text).region(end, text.length());
                        if (entity.lookingAt()) { end = entity.end(); continue; }
                    }
                    int close = "])}".indexOf(c);
                    int eyes = smileyEyes(text, end);
                    // A smiley holds nothing, unless its bracket is the one closing the name's own
                    // bracket, as in (DQ :), or the field's, right after a word, as in [nickname=Dana:].
                    if (eyes >= start && !(close >= 0 && (opened.length() > 0
                            ? "[({".indexOf(opened.charAt(opened.length() - 1)) == close
                            : "[({".indexOf(around) == close && eyes > start
                                && Character.isLetterOrDigit(text.charAt(eyes - 1))))) {
                        end++;
                        continue;
                    }
                    if ("[({".indexOf(c) >= 0) opened.append(c);
                    else if (close >= 0 && opened.length() > 0
                            && "[({".indexOf(opened.charAt(opened.length() - 1)) == close) {
                        opened.setLength(opened.length() - 1);
                    } else if (close >= 0) {
                        if ("[({".indexOf(around) == close) break;
                    } else if (c == '\n' || c == '\r' || (c == '"' && inDouble)
                            || (c == '\'' && inSingle && !inWord(text, end)
                                && (end + 1 == text.length() || smileyEyes(text, end + 1) != end - 1))
                            || (",;&<>".indexOf(c) >= 0 && opened.length() == 0 && fieldFollows(text, end + 1))) {
                        break;
                    }
                    end++;
                }
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            } else {
                end = start;
                while (end < text.length()) {
                    char c = text.charAt(end);
                    if (Character.isWhitespace(c) || ";&\"'<>".indexOf(c) >= 0
                            || (quotedName && (c == ']' || c == '}'))
                            || (c == ',' && (!contentId || quotedName || fieldFollows(text, end + 1)))) break;
                    end++;
                }
            }
            if (end == start) continue;
            result.append(text, copied, start).append(replacement);
            copied = end;
            fields.region(end, text.length());
        }
        return copied == 0 ? text : result.append(text, copied, text.length()).toString();
    }

    private static boolean isCredential(String name) {
        for (String token : CREDENTIAL_NAMES) {
            if (name.contains(token)) return true;
        }
        return false;
    }

    private static boolean isField(String name) {
        return isCredential(name) || isContentId(name) || isPersonal(name);
    }

    private static boolean isContentId(String name) {
        for (String suffix : CONTENT_ID_NAMES) {
            if (name.equals(suffix)) return true;
            if (!name.endsWith("_" + suffix)) continue;
            String prefix = name.substring(0, name.length() - suffix.length() - 1);
            if (!prefix.isEmpty() && prefix.charAt(0) != '_' && !prefix.contains("__")
                    && prefix.indexOf('-') < 0 && prefix.charAt(prefix.length() - 1) != '_') return true;
        }
        return false;
    }

    private static boolean isPersonal(String name) {
        for (String whole : PERSONAL_NAMES) {
            if (name.equals(whole)) return true;
        }
        return false;
    }

    /** Letters on both sides, counting a letter outside the Basic Multilingual Plane and its accents. */
    private static boolean inWord(String text, int at) {
        if (at + 1 >= text.length() || !Character.isLetter(text.codePointAt(at + 1))) return false;
        int before = at;
        while (before > 0) {
            int point = text.codePointBefore(before);
            int type = Character.getType(point);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK
                    && type != Character.COMBINING_SPACING_MARK) return Character.isLetter(point);
            before -= Character.charCount(point);
        }
        return false;
    }

    /** The innermost bracket still open on the line in front of a field, or 0 when there is none. */
    private static char openAround(String text, int at) {
        int closed = 0;
        for (int i = at - 1; i >= Math.max(0, at - 2_000); i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') break;
            // In front of the field, eyes right after a word or a quote are a key's colon when a
            // square bracket opens, as in tags:[ or ":[, but a smiley otherwise, as in hi:) or sad:(.
            int eyes = smileyEyes(text, i);
            if (eyes == 0 || (eyes > 0 && (c != '[' || !Character.isLetterOrDigit(text.charAt(eyes - 1))
                    && "_\"'\\".indexOf(text.charAt(eyes - 1)) < 0))) continue;
            if ("])}".indexOf(c) >= 0) closed++;
            else if ("[({".indexOf(c) >= 0 && closed-- == 0) return c;
        }
        return 0;
    }

    /**
     * Where the eyes sit when the bracket at {@code at} closes a smiley, :) ;( :-] :'(, which holds
     * nothing, or -1. A name counts one only with its eyes inside it, so "nickname:[DQ]" opens a bracket.
     * An opening bracket with a word right inside it, as in :(DQ), opens one too, and a double colon
     * is an address or a scope, as in [::], not eyes.
     */
    private static int smileyEyes(String text, int at) {
        char bracket = text.charAt(at);
        if ("()[]".indexOf(bracket) < 0) return -1;
        if ((bracket == '(' || bracket == '[') && at + 1 < text.length()
                && Character.isLetterOrDigit(text.charAt(at + 1))) return -1;
        int eyes = at - 1;
        if (eyes > 0 && (text.charAt(eyes) == '-' || text.charAt(eyes) == '\'')) eyes--;
        return eyes >= 0 && (text.charAt(eyes) == ':' || text.charAt(eyes) == ';')
                && !(eyes > 0 && text.charAt(eyes - 1) == ':') ? eyes : -1;
    }

    /** Whether an odd run of backslashes escapes the character at {@code at}. */
    private static boolean escapedAt(String text, int at) {
        int slashes = 0;
        while (at - slashes > 0 && text.charAt(at - slashes - 1) == '\\') slashes++;
        return slashes % 2 == 1;
    }

    /** Whether a backslash further along the word escapes what JSON can't, as in ew\Dana, so it's a path. */
    private static boolean pathAhead(String text, int at) {
        for (int i = at; i < text.length() && i < at + 256; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'') return false;
            if (c != '\\') continue;
            int next = i;
            while (next < text.length() && text.charAt(next) == '\\') next++;
            if ((next - i) % 2 == 1 && next < text.length() && JSON_ESCAPED.indexOf(text.charAt(next)) < 0) return true;
            i = next;
        }
        return false;
    }

    private static boolean fieldFollows(String text, int at) {
        return NEXT_FIELD.matcher(text).region(at, text.length()).lookingAt();
    }

    /**
     * Where a quoted value inside a name ends: after its closing quote, or first where its line or
     * the string holding it ends, so a value cut short takes nothing from the lines after it.
     */
    private static int innerQuotedEnd(String text, int start, int quote, boolean inDouble, boolean inSingle) {
        char delimiter = text.charAt(quote);
        int escaping = quote - start;
        int slashes = 0;
        for (int i = quote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { slashes++; continue; }
            if (c == delimiter && slashes % (2 * (escaping + 1)) == escaping) return i + 1;
            if (c == '\n' || c == '\r' || slashes == 0 && (c == '"' ? inDouble : c == '\'' && inSingle)) return i;
            if (slashes % 2 == 1 && (c == 'n' || c == 'r') && !pathAhead(text, i + 1)) return i - slashes;
            slashes = 0;
        }
        return text.length();
    }

    private static int quotedEnd(String text, int start, int quote) {
        char delimiter = text.charAt(quote);
        int escaping = quote - start;
        int slashes = 0;
        for (int i = quote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { slashes++; continue; }
            if (c == delimiter && slashes % (2 * (escaping + 1)) == escaping) return i + 1;
            slashes = 0;
        }
        return text.length();
    }

    private static int structuredEnd(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') { i = quotedEnd(text, i, i) - 1; continue; }
            if (c == '{' || c == '[') depth++;
            else if ((c == '}' || c == ']') && --depth == 0) return i + 1;
        }
        return text.length();
    }

    private static String prefix(String text, int limit) {
        limit = Math.max(0, limit);
        if (text.length() <= limit) return text;
        if (limit > 0 && Character.isHighSurrogate(text.charAt(limit - 1))) limit--;
        return text.substring(0, limit);
    }

    /** A bounded copy for our sinks. Never mutate or pass a replacement into TikTok's handler. */
    public static String redactThrowable(Throwable failure) {
        if (failure == null) return "";
        StringBuilder text = new StringBuilder();
        try {
            ArrayDeque<Throwable> pending = new ArrayDeque<>();
            ArrayDeque<String> labels = new ArrayDeque<>();
            IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
            pending.add(failure);
            labels.add("");
            int frames = 0;
            boolean truncated = false;
            while (!pending.isEmpty()) {
                if (seen.size() >= 32 || text.length() >= 32_000) { truncated = true; break; }
                Throwable next = pending.removeFirst();
                String label = labels.removeFirst();
                if (seen.put(next, Boolean.TRUE) != null) {
                    text.append("[circular throwable omitted]\n");
                    continue;
                }
                text.append(label).append(next.getClass().getName());
                try {
                    String message = next.getMessage();
                    if (message != null) {
                        int available = Math.max(0, 32_000 - text.length() - 3);
                        text.append(": ").append(prefix(message, available));
                        if (message.length() > available) truncated = true;
                    }
                } catch (Throwable unreadable) { text.append(": [message unavailable]"); }
                text.append('\n');
                try {
                    for (StackTraceElement frame : next.getStackTrace()) {
                        if (++frames > 128 || text.length() >= 32_000) { truncated = true; break; }
                        String frameText = frame.toString();
                        int available = Math.max(0, 32_000 - text.length() - 5);
                        text.append("\tat ").append(prefix(frameText, available)).append('\n');
                        if (frameText.length() > available) truncated = true;
                    }
                } catch (Throwable unreadable) { text.append("[stack unavailable]\n"); }
                try {
                    Throwable cause = next.getCause();
                    if (cause != null) { pending.addLast(cause); labels.addLast("Caused by: "); }
                } catch (Throwable unreadable) { text.append("[cause unavailable]\n"); }
                for (Throwable suppressed : next.getSuppressed()) {
                    if (pending.size() >= 32) { truncated = true; break; }
                    pending.addLast(suppressed);
                    labels.addLast("Suppressed: ");
                }
            }
            if (truncated) text.append("[throwable trace truncated]\n");
        } catch (Throwable unreadable) {
            text.append("[throwable detail unavailable]\n");
        }
        return redact(text.toString());
    }
}
