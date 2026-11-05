/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Shows TikTok's own Inbox filter tabs (Main, Requests, Unread, Starred) on an account TikTok
 * keeps in its personal Inbox.
 *
 * <p>TikTok builds the tab bar, the tab list and every tab's filter from one answer, the inbox
 * mode its professional inbox manager holds for the account: PERSONAL_INBOX, PRO_CREATOR_INBOX or
 * BUSINESS_INBOX. The tabs only appear when that answer isn't personal. The patch hands the two
 * places that read the mode for the Inbox, the mode getter and the tab list, to
 * {@link #shownInboxMode}, which answers the creator mode in place of the personal one while the
 * switch is on. The stored mode is never written, so the code that syncs the account's mode with
 * TikTok's server reads the real one and sends nothing new. Filtering happens on the phone, over
 * the chats already loaded, so Unread and Starred work the way they do for a creator account.
 *
 * <p>The creator mode also makes TikTok offer its pop-up about the upgraded creator Inbox.
 * {@link #hideProInboxGuide} keeps it from showing while the switch is on.
 */
public final class InboxFilterTabs {
    static final String PERSONAL = "PERSONAL_INBOX";
    static final String CREATOR = "PRO_CREATOR_INBOX";
    private static final String FAMILY = "inbox filters";

    /** The creator constant of TikTok's mode enum, looked up on first use. */
    @Nullable private static volatile Enum<?> creator;
    /** A mode enum found without the creator constant. */
    @Nullable private static volatile Class<?> lacking;
    private static volatile boolean reported;

    private InboxFilterTabs() {
    }

    /**
     * The inbox mode TikTok's Inbox should act on. Answers the creator mode for the personal
     * one while the switch is on, and anything else unchanged.
     */
    public static Object shownInboxMode(@Nullable Object mode) {
        if (!(mode instanceof Enum) || !PERSONAL.equals(((Enum<?>) mode).name())) return mode;
        if (!Settings.SHOW_INBOX_FILTERS.get()) return mode;
        Enum<?> shown = creatorOf((Enum<?>) mode);
        if (shown == null) return mode;
        if (!reported) {
            reported = true;
            HookStatus.bound(FAMILY, "creator tabs shown");
        }
        return shown;
    }

    /** Whether TikTok's pop-up about the creator Inbox is kept from showing. */
    public static boolean hideProInboxGuide() {
        return Settings.SHOW_INBOX_FILTERS.get();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    private static Enum<?> creatorOf(Enum<?> personal) {
        Enum<?> known = creator;
        Class<?> type = personal.getDeclaringClass();
        if (known != null && known.getDeclaringClass() == type) return known;
        if (type == lacking) return null;
        try {
            known = Enum.valueOf((Class) type, CREATOR);
        } catch (IllegalArgumentException missing) {
            // The getter runs for every chat the Inbox filters, so the lookup isn't retried.
            lacking = type;
            HookStatus.missingMember(FAMILY, "enum constant", type.getName(), CREATOR);
            return null;
        }
        creator = known;
        return known;
    }
}
