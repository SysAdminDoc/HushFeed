/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.spoof.sim;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Takes turns between the SIM presets ticked in the preset dialog, one each time TikTok starts
 * (#538).
 *
 * <p>The turn is per start, not per For You page, because TikTok reads the SIM once as it starts
 * and keeps the answer for the life of the process: on 48.0.2 every SIM read goes through its
 * privacy layer's getters, and Region spoof found the same keeping on the phone. A country that
 * changed mid-session would reach some requests and not others, one feed asking as two
 * countries. So the turn happens once, before anything in this process reads the SIM fields,
 * and the three fields then hold this start's preset. The SIM getters, the store region, the
 * request region and the time zone all read those fields, so they agree on one country until
 * the next start.
 *
 * <p>The saved list is the order the next starts take: its first preset goes into the fields and
 * moves to the back. Right after the dialog fills the fields from that first preset, the start
 * that follows keeps it, so the preset the rows show is the one the restart uses. With fewer
 * than two ticked nothing turns, and fields typed by hand that match none of the ticked presets
 * stop the turns too: a custom SIM is a choice the list shouldn't write over.
 */
public final class SimRotation {
    private static final Object LOCK = new Object();
    private static volatile boolean decided;

    /** Test seam: whether this is TikTok's main process. Null reads the real answer. */
    @Nullable
    static Boolean mainProcessForTests;

    private SimRotation() {
    }

    /**
     * Before any read of the three SIM fields. The first call once the extension has its context
     * decides this start's preset, and every later one returns at once. Readers that come in
     * while the turn is being saved wait for it, so none of them reads the last start's country.
     */
    public static void ensure() {
        if (decided || !Utils.hasContext()) return;
        synchronized (LOCK) {
            if (decided) return;
            decided = true;
            try {
                turn();
            } catch (RuntimeException failure) {
                Logger.printException(() -> "The SIM presets could not take their turn", failure);
            }
        }
    }

    private static void turn() {
        // Only the main process writes settings. The others read what it saved, and a turn in
        // each of them would skip presets.
        if (!mainProcess() || !Settings.SIM_SPOOF.get()) return;
        List<SimPreset> queue = SimPresets.listed(Settings.SIM_SPOOF_PRESETS.get());
        List<SimPreset> after = next(queue, Settings.SIM_SPOOF_ISO.get(),
                Settings.SIMSPOOF_MCCMNC.get(), Settings.SIMSPOOF_OP_NAME.get());
        if (after == null) {
            if (queue.size() > 1) {
                Logger.printInfo(() -> "SIM presets keep their turn: the SIM details are custom");
            }
            return;
        }
        SimPreset preset = after.get(after.size() - 1);
        Map<Setting<?>, Object> values = new HashMap<>();
        values.put(Settings.SIM_SPOOF_ISO, preset.iso);
        values.put(Settings.SIMSPOOF_MCCMNC, preset.mccMnc);
        values.put(Settings.SIMSPOOF_OP_NAME, preset.operatorName);
        values.put(Settings.SIM_SPOOF_PRESETS, SimPresets.saved(after));
        try {
            // One write, so a start that dies halfway can't leave one country's code with
            // another's carrier.
            Setting.saveAll(values);
        } catch (IOException failure) {
            Logger.printException(() -> "The SIM preset for this start could not be saved", failure);
            return;
        }
        Logger.printInfo(() -> "SIM preset for this start: " + preset.country + " / "
                + preset.operatorName + " / " + preset.mccMnc + ", then "
                + SimPresets.saved(after));
    }

    /**
     * The saved order after this start's turn, with the preset it takes moved to the back. Null
     * when nothing turns: fewer than two ticked, or fields that hold none of them.
     */
    @Nullable
    static List<SimPreset> next(List<SimPreset> queue, String iso, String mccMnc,
                                String operatorName) {
        if (queue.size() < 2) return null;
        SimPreset current = SimPresets.findSelected(iso, mccMnc, operatorName);
        if (current == null || !queue.contains(current)) return null;
        List<SimPreset> after = new ArrayList<>(queue.subList(1, queue.size()));
        after.add(queue.get(0));
        return after;
    }

    private static boolean mainProcess() {
        Boolean forced = mainProcessForTests;
        return forced != null ? forced : Utils.isMainProcess();
    }

    /** Lets a test run a start of its own. */
    static void resetForTests() {
        synchronized (LOCK) {
            decided = false;
            mainProcessForTests = null;
        }
    }
}
