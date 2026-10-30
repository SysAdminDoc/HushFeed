/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.inbox.KeepUnsent;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Removes the messages Keep unsent messages has left in your chats, the way TikTok would have
 * shown them had the unsends gone through. Each chat keeps the usual notice that a message
 * was unsent.
 */
@SuppressWarnings("deprecation")
public final class ClearKeptMessagesPreference extends Preference implements ImmediateAction {
    @Override public boolean actsOnTap() {
        return true;
    }

    public ClearKeptMessagesPreference(Context context, Runnable afterClear) {
        super(context);
        // A key so the settings search can index this row; no Setting has it.
        setKey("action_clear_kept_messages");
        setTitle(L10n.t(context, "Clear kept messages"));
        setSummary(L10n.t(context, "Removes every message that was kept. Each chat shows TikTok's usual notice that a message was unsent."));
        setOnPreferenceClickListener(preference -> {
            if (!KeepUnsent.hasKept()) {
                Utils.showToastShort(L10n.t(context, "Nothing has been kept"));
                return true;
            }
            Utils.showToastShort(L10n.t(context, "Removing the kept messages"));
            KeepUnsent.clearKept(message -> {
                Utils.showToastLong(message);
                if (afterClear != null) afterClear.run();
            });
            return true;
        });
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }
}
