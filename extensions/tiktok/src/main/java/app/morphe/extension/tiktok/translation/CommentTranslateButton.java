/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * TikTok's translate button in the comment header (#85), the icon beside the comment count that
 * translates every loaded comment on one tap and shows the originals on the next.
 *
 * <p>The button, the toggle behind it and the translating are all TikTok's own. The patch answers
 * yes to the two gates TikTok builds the button behind while {@link #isOn()} says so; with the
 * switch off, and while Hushfeed is paused, TikTok reads its own values.
 *
 * <p>The toggle is TikTok's stored state, and it outlives the switch. Left on, it would keep TikTok
 * translating comments with the button gone and nothing on screen to turn it off. So the toggle's
 * writes are reported here first, and a write the header's button makes while the switch is on
 * marks the toggle as Hushfeed's to answer for. Its reads come through {@link #toggleOn(boolean)},
 * which answers off for that toggle once the switch is off or Hushfeed is paused. Any write TikTok
 * makes on its own (its server sync, its offer after a few taps of See translation, or the button
 * on an account TikTok built it for) clears the mark, and the toggle is TikTok's again.
 *
 * <p>The toggle is also the one switch for comment translation across the whole app (#282), so
 * Auto translate comments answers it on. The header's icon then shows what the reader sees, one
 * tap shows the originals, and that tap is remembered as {@link Settings#COMMENT_TRANSLATION_TURNED_OFF}
 * so the comments that load after it stay original too, on every video, until the button or the
 * switch turns translation back on. TikTok erases the toggle each time the comment page closes,
 * and that erase leaves the remembered off in place. A button tap made while Auto translate comments is on counts
 * as Hushfeed's for the mark above as well, so turning the switch off hands the toggle back off.
 */
@SuppressWarnings("unused")
public final class CommentTranslateButton {
    /** {@link Settings#COMMENT_TRANSLATE_BUTTON}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "comment_translate_button";

    private CommentTranslateButton() {
    }

    /** Whether the header builds TikTok's translate button. */
    public static boolean isOn() {
        return Utils.getContext() != null ? Settings.COMMENT_TRANSLATE_BUTTON.get() : EarlySwitch.isOn(SWITCH_KEY);
    }

    /**
     * Called when TikTok is about to store the toggle, right after it read the toggle's state to
     * compare the stored one with. Reported after that read, so the answer TikTok compares against
     * is the one from before this write and a change here still refreshes the comment list.
     *
     * @param value  what the toggle is being set to; null erases it.
     * @param source where the change comes from. The header's buttons pass none; TikTok's own
     *               changes pass one.
     */
    public static void onToggleWritten(@Nullable Boolean value, @Nullable Object source) {
        if (Utils.getContext() == null) return;
        try {
            boolean autoTranslating = Settings.COMMENT_BATCH_TRANSLATION.get();
            boolean ours = source == null && Boolean.TRUE.equals(value) && (isOn() || autoTranslating);
            if (Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get() != ours) {
                Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.save(ours);
            }
            // A null is TikTok erasing the toggle, which it does each time the comment page closes,
            // so it leaves the reader's off as it was.
            if (Boolean.TRUE.equals(value)) {
                // Saved whatever it reads: paused, the mark reads off while its stored value is on.
                Settings.COMMENT_TRANSLATION_TURNED_OFF.save(false);
            } else if (value != null && source == null && autoTranslating
                    && !Settings.COMMENT_TRANSLATION_TURNED_OFF.get()) {
                // Only the reader's own off counts. TikTok's (its server sync) leaves the mark as
                // it was, and so does any off while the switch is off or Hushfeed is paused.
                Settings.COMMENT_TRANSLATION_TURNED_OFF.save(true);
            }
        } catch (RuntimeException problem) {
            Logger.printException(() -> "Comment translate button: could not note the toggle", problem);
        }
    }

    /**
     * Auto translate comments was switched either way: a fresh start for comment translation,
     * so an off from the header's button before it no longer counts.
     */
    public static void onAutoTranslateChanged() {
        try {
            // Saved whatever it reads: paused, the mark reads off while its stored value is on.
            Settings.COMMENT_TRANSLATION_TURNED_OFF.save(false);
        } catch (RuntimeException problem) {
            Logger.printException(() -> "Comment translate button: could not clear the off choice", problem);
        }
    }

    /**
     * The toggle as TikTok's code sees it. On while Auto translate comments translates and off
     * while the reader has turned it off, since TikTok's erase leaves its default in its place.
     * Otherwise TikTok's own answer, except that a toggle Hushfeed turned on reads off while
     * neither the button's switch nor Auto translate comments is on, or Hushfeed is paused.
     */
    public static boolean toggleOn(boolean tiktoks) {
        if (Utils.getContext() == null) return tiktoks;
        try {
            if (CommentBatchTranslator.translatesComments()) return true;
            if (Settings.COMMENT_BATCH_TRANSLATION.get() && Settings.COMMENT_TRANSLATION_TURNED_OFF.get()) {
                return false;
            }
            if (!tiktoks) return false;
            if (isOn()) return true;
            return !Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get();
        } catch (RuntimeException problem) {
            Logger.printException(() -> "Comment translate button: could not read the toggle", problem);
            return tiktoks;
        }
    }
}
