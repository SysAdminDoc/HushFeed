/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import android.content.Context;
import android.content.res.Configuration;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.List;

/** The LIVE controls patch's switches, each read at the call it guards. */
@SuppressWarnings("unused")
public final class LiveControls {
    private LiveControls() {
    }

    /**
     * True while a LIVE preview in the feed should not start the countdown that takes you into
     * its room. TikTok already skips the countdown on the Following feed, so a preview without
     * one is a state it handles; tapping the preview still opens the room. Paused, the switch
     * answers off.
     */
    public static boolean skipAutoEnter() {
        return SettingsStatus.liveControlsEnabled && Settings.STOP_LIVE_AUTO_ENTER.get();
    }

    /**
     * True while LIVE rooms should offer TikTok's newer quality picker and a pick should
     * carry to the next room. TikTok's own setting for both is off unless its server turns it on,
     * which leaves a quality pick only in rooms whose stream asks for the older one. Paused, the
     * switch answers off and TikTok's setting decides again.
     */
    public static boolean offerQualityEverywhere() {
        return SettingsStatus.liveControlsEnabled && Settings.OFFER_LIVE_QUALITY.get();
    }

    /**
     * What a LIVE room's Share sheet is told about the two rooms that leave the Video quality entry
     * out: LIVE Studio rooms (most gaming rooms) and rooms that support low latency (football). The
     * sheet's provider asks with its own answer, non-zero for "leave it out"; while Offer LIVE
     * quality is on the answer is zero so the entry shows, and otherwise TikTok's answer stands (#37).
     * An int, not a boolean, since the host types that register as an int on some paths.
     */
    public static int qualityEntryBlocked(int blocked) {
        return offerQualityEverywhere() ? 0 : blocked;
    }

    /**
     * True while every portrait LIVE room's menu should offer clear screen, which hides the room's
     * chat, gifts and buttons and leaves the stream on its own (#57). TikTok offers the entry only
     * when its server turns it on. Paused, the switch answers off and TikTok's setting decides.
     */
    public static boolean offerClearScreen() {
        return SettingsStatus.liveControlsEnabled && Settings.OFFER_LIVE_CLEAR_SCREEN.get();
    }

    /**
     * Called as a LIVE panel asks whether to take TikTok's dark LIVE theme, with the panel's
     * context. TikTok answers no in every LIVE room even in dark mode, so the share menu, the fan
     * club and the first gift dialog opened white over a dark room (#159). True while the switch is
     * on and that context is in dark mode, which gives the panel the dark theme; false leaves
     * TikTok's answer, so light mode stays light. Paused, the switch answers off. It answers
     * false too while a stock panel asks for TikTok's own answer ({@link LivePanelTheme#stockOverlay}).
     */
    public static boolean darkLivePanels(Context context) {
        if (context == null || !SettingsStatus.liveControlsEnabled || !Settings.DARK_LIVE_PANELS.get()
                || LivePanelTheme.askingStock()) {
            return false;
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * True while a LIVE quality pick should outlive TikTok closing. It needs Offer LIVE quality in
     * more rooms as well, which is what makes TikTok carry a pick between rooms at all. Paused,
     * both switches answer off.
     */
    public static boolean rememberQuality() {
        return offerQualityEverywhere() && Settings.REMEMBER_LIVE_QUALITY.get();
    }

    private static volatile boolean restoredLogged;
    private static volatile String lastQualityPick;
    /** The last pick this run made while Offer LIVE quality was on, from either picker, or null. */
    private static volatile String pickThisRun;

    /** The last quality TikTok carried to a room this run, or null. Used to record the quality being watched. */
    static String lastQualityPick() {
        return lastQualityPick;
    }

    /**
     * Called right after a LIVE room's start reads TikTok's last quality pick, with that pick.
     * TikTok keeps it only until it closes, so the first rooms after a cold start read null; while
     * {@link #rememberQuality} is on they get the pick saved by {@link #noteLiveQualityPick}
     * instead. The player start uses it only when the stream has that quality and starts at the
     * stream's own default when it doesn't, as it does with a pick from the same session.
     */
    public static String liveQualityPick(String tiktokPick) {
        // On the S22 a room after a restart opened at TikTok's choice with no restore line, so
        // each read says what TikTok handed it and which start asked (#57).
        Logger.printDebug(() -> "LIVE quality: a start read " + tiktokPick + " in " + startCaller());
        // The older picker, which LIVE Studio rooms open, never writes TikTok's pick, so on the S22
        // a 360p pick there left the next room at AUTO (#57). The last pick from either picker
        // this run carries instead, and TikTok's own is only the newer picker's.
        String runPick = pickThisRun;
        if (runPick != null && offerQualityEverywhere()) return runPick;
        if (tiktokPick != null || !rememberQuality()) return tiktokPick;
        String saved = Settings.REMEMBERED_LIVE_QUALITY.get();
        if (saved == null || saved.isEmpty()) return null;
        if (!restoredLogged) {
            restoredLogged = true;
            Logger.printInfo(() -> "LIVE quality: TikTok had no pick since it started, so rooms start at the saved " + saved);
        }
        return saved;
    }

    /**
     * Called at the end of a game or PC LIVE room's player start, with TikTok's last pick, the
     * stream's data and the quality the room starts at. That branch of TikTok's start jumps past
     * the pick read, so a game, screen-share or LIVE Studio room that takes it opens at the stream's
     * default even with a pick (#57). The S22's account sends those rooms to the regular branch
     * instead (see {@link #liveStudioPickSkip}), so this one comes from the dex alone. With Offer
     * LIVE quality on it answers the pick the other rooms get
     * when the stream lists it and the room isn't on it already, and the patch switches the room to
     * it the way the other rooms' start does. Null leaves TikTok's choice.
     */
    public static String gameRoomQuality(String tiktokPick, String streamData, String current) {
        if (!offerQualityEverywhere()) return null;
        String pick = liveQualityPick(tiktokPick);
        if (pick == null || pick.equals(current) || !streamHas(streamData, pick)) return null;
        Logger.printDebug(() -> "LIVE quality: a game room starts at " + pick + " instead of " + current);
        return pick;
    }

    /**
     * Called in a LIVE room's player start with LiveMode.LIVE_STUDIO, which the start compares the
     * room's mode with to leave LIVE Studio rooms (streamed from TikTok's PC app, most gaming rooms)
     * out of the quality pick. On the S22 those rooms opened at AUTO after a pick in the same run and
     * after a restart alike (#57). With Offer LIVE quality on it answers null, so no room matches and
     * LIVE Studio rooms take the pick when their stream has it, like other rooms. Otherwise, and
     * paused, it answers the mode and TikTok leaves them out as before.
     */
    public static Object liveStudioPickSkip(Object liveStudio) {
        return offerQualityEverywhere() ? null : liveStudio;
    }

    /**
     * Called as TikTok's LIVE player starts a room, with the room's stream data and LIVE mode, for
     * the debug log only. Rooms from the LIVE button logged neither start's pick read on the S22
     * (#57), and a start without stream data takes a path that never reads the pick.
     */
    public static void notePlayerStart(String streamData, Object mode) {
        Logger.printDebug(() -> "LIVE quality: a player start with "
                + (streamData == null || streamData.isEmpty() ? "no stream data" : streamData.length() + " chars of stream data")
                + " in mode " + mode);
    }

    /** Whether the pull stream data's {@code data} object lists the quality key, as TikTok checks. */
    static boolean streamHas(String streamData, String key) {
        if (streamData == null || streamData.isEmpty()) return false;
        try {
            JSONObject data = new JSONObject(streamData).optJSONObject("data");
            return data != null && data.has(key);
        } catch (JSONException malformed) {
            return false;
        }
    }

    /** The TikTok method that called the hook, for the debug log. */
    private static String startCaller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement frame : stack) {
            if (!frame.getClassName().startsWith("app.morphe.")) {
                return frame.getClassName() + "." + frame.getMethodName();
            }
        }
        return "?";
    }

    /** Called with each quality TikTok's picker carries to the next room; saved while the switch is on. */
    public static void noteLiveQualityPick(String pick) {
        if (pick != null && !pick.isEmpty()) {
            lastQualityPick = pick;
            if (offerQualityEverywhere()) pickThisRun = pick;
        }
        if (pick == null || pick.isEmpty() || !rememberQuality()) return;
        if (pick.equals(Settings.REMEMBERED_LIVE_QUALITY.get())) return;
        Settings.REMEMBERED_LIVE_QUALITY.save(pick);
        Logger.printDebug(() -> "LIVE quality: saved the pick " + pick);
    }

    /**
     * Called as the For You LIVE preview's Service+ row ("Service+ | Ask me anything!") is bound to
     * a LIVE, with the row and that LIVE's Service+ preview. True when the row still shows and the
     * LIVE has no first Service+ item, and the patch then runs the row's own hide before its show.
     * TikTok's show returns without hiding when there's nothing to show, and only the hide a
     * preview runs as it leaves the screen took the row away, so a preview bound to the next LIVE
     * without leaving first kept the last LIVE's row under the next LIVE's name (#94). Paused, it
     * answers false and the row is TikTok's again.
     */
    public static boolean hideStaleServicePlus(Object row, Object preview) {
        if (row == null || Setting.isPaused()) return false;
        try {
            boolean showing = Boolean.TRUE.equals(Reflect.invoke(row, "isShowing"));
            Object items = preview == null ? null : Reflect.readField(preview, "previewItem");
            List<?> entries = items instanceof List ? (List<?>) items : null;
            int count = entries == null ? 0 : entries.size();
            // The native renderer reads only item zero and returns without clearing a null item.
            boolean hasFirst = count > 0 && entries.get(0) != null;
            // Every bind, so a report shows whether a row under a LIVE was that LIVE's own (#94).
            Logger.printDebug(() -> "LIVE preview: Service+ row bound, showing " + showing
                    + ", this LIVE has " + count + " Service+ item" + (count == 1 ? "" : "s")
                    + ", first item available " + hasFirst);
            if (!showing || hasFirst) return false;
            Logger.printDebug(() -> "LIVE preview: hid a Service+ row left from the last LIVE");
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE preview's Service+ row", failure);
            return false;
        }
    }

    /**
     * Called at the top of the Service+ row's show with the row, the preview the last bind
     * stored, the current room holder and the name of its room field. Answers the preview the
     * show should use: the current room's. TikTok enters the show without a bind too, when a
     * preview becomes visible, when an overlay clears and from the recycle path, each with the
     * stored preview, which the recycle never clears; a row showing the last LIVE's Service+
     * line under this LIVE was that path (#94). Paused, the stored preview comes back unchanged.
     */
    public static Object servicePlusPreview(Object row, Object stored, Object holder, String roomField) {
        if (row == null || Setting.isPaused()) return stored;
        try {
            Object room = Reflect.readField(holder, roomField);
            Object current = Reflect.readField(room, "smbLivePreview");
            if (current != stored) {
                Logger.printDebug(() -> "LIVE preview: Service+ row showing with " + (stored == null ? "no" : "the last LIVE's")
                        + " stored preview, this LIVE has " + (current == null ? "none" : "its own"));
            }
            return current;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read the LIVE preview's Service+ item", failure);
            return stored;
        }
    }

    /**
     * Called next with the row and the preview the show is about to use. False hides the row
     * instead: when Hide the Service+ row under LIVE previews is on, or when the preview has no
     * first item, which TikTok's show leaves as it was (#94). Paused, the row is TikTok's again.
     */
    public static boolean showServicePlusRow(Object row, Object preview) {
        if (row == null || Setting.isPaused()) return true;
        try {
            if (SettingsStatus.liveControlsEnabled && Settings.HIDE_LIVE_SERVICE_PLUS.get()) {
                Logger.printDebug(() -> "LIVE preview: Service+ row hidden by its switch");
                return false;
            }
            Object items = preview == null ? null : Reflect.readField(preview, "previewItem");
            List<?> entries = items instanceof List ? (List<?>) items : null;
            boolean hasFirst = entries != null && !entries.isEmpty() && entries.get(0) != null;
            Logger.printDebug(() -> "LIVE preview: Service+ row "
                    + (hasFirst ? "shows this LIVE's item" : "hidden, this LIVE has no Service+ item"));
            return hasFirst;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE preview's Service+ row", failure);
            return true;
        }
    }

    private static final long NONE = -1;
    private static long pendingViewers = NONE;

    /**
     * Called just before the room widget turns its viewer count into text, with the count. The
     * formatter overwrites the register the count sits in, so the patch hands it over here and
     * {@link #viewerCountText} picks it up from the same straight line of code on the main thread.
     */
    public static void noteViewerCount(long count) {
        pendingViewers = count;
    }

    /**
     * Called with TikTok's text for the count noted just before ("1.2K+"). Returns the exact
     * count, grouped the way the reader's locale writes numbers, while the switch is on, and
     * TikTok's own text otherwise. Paused, the switch answers off.
     */
    public static String viewerCountText(String tiktokText) {
        long count = pendingViewers;
        pendingViewers = NONE;
        if (tiktokText == null || count < 0 || !SettingsStatus.liveControlsEnabled
                || !Settings.SHOW_EXACT_LIVE_VIEWERS.get()) {
            return tiktokText;
        }
        return NumberFormat.getIntegerInstance().format(count);
    }
}
