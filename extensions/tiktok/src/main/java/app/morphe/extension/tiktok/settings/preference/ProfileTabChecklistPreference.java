/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.DialogPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.profile.ProfileTabs;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Picks the profile tabs to hide from a fixed list, since TikTok names every tab by an icon rather
 * than a word. A pick the list no longer has is kept.
 */
@SuppressWarnings("deprecation")
public final class ProfileTabChecklistPreference extends DialogPreference {
    private final Set<String> selected = new LinkedHashSet<>();
    private LinearLayout rows;
    private String originalPicks = "";
    private boolean selectionSaved;
    /** The unsaved picks a recreated screen hands back, while it reopens the dialog. */
    private Bundle restoring;

    public ProfileTabChecklistPreference(Context context) {
        super(context);
        setKey("profile_tab_checklist");
        setTitle("Hide profile tabs");
        setSummary("Pick the tabs to hide from the strip above a profile's videos, such as Reposts or Liked videos. One tab always stays. Restart TikTok to apply this.");
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }

    @Override
    protected View onCreateDialogView() {
        Context context = getContext();
        // The saved value, not get(): paused, get() answers the default, and a save would wipe it.
        originalPicks = Settings.PROFILE_TAB_PICKS.savedValue();
        selected.clear();
        selected.addAll(selectedKeys(originalPicks));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        root.setPadding(padding, padding, padding, SettingsUi.dp(context, 8));

        TextView title = SettingsUi.text(context, getTitle().toString(), SettingsUi.TEXT_HEADLINE,
                SettingsUi.textPrimary(), Typeface.BOLD);
        SettingsUi.markDialogHeading(title);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView summary = SettingsUi.text(context, getSummary().toString(), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.setMargins(0, SettingsUi.dp(context, 14), 0, SettingsUi.dp(context, 10));
        root.addView(summary, summaryParams);

        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dialogListHeight(context, 300));
        scrollParams.topMargin = SettingsUi.dp(context, 10);
        root.addView(scroll, scrollParams);
        renderRows();
        return root;
    }

    private void renderRows() {
        rows.removeAllViews();
        for (ProfileTabs.Row row : ProfileTabs.ROWS) {
            CheckBox check = new CheckBox(getContext());
            check.setText(L10n.t(getContext(), row.label));
            SettingsUi.styleCheckBoxRow(check);
            check.setTag("profile_tab_" + row.key);
            check.setChecked(selected.contains(row.key));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(row.key);
                else selected.remove(row.key);
            });
            rows.addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which)
                -> onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult && !selectionSaved) saveSelection();
        super.onDialogClosed(positiveResult);
    }

    @Override
    protected void showDialog(Bundle state) {
        selectionSaved = false;
        super.showDialog(state);
        Bundle draft = restoring;
        if (draft != null) {
            selected.clear();
            selected.addAll(DialogDraft.picks(draft));
            renderRows();
        }
        SettingsUi.styleFramedDialog(getDialog());
        AlertDialog dialog = (AlertDialog) getDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            String picks = buildPicks();
            setChoicesEnabled(dialog, false);
            boolean accepted = Utils.runOnBackgroundThread(() -> {
                boolean saved = Settings.PROFILE_TAB_PICKS.save(picks);
                Utils.runOnMainThread(() -> {
                    if (saved) notifyChanged();
                    if (getDialog() != dialog || !dialog.isShowing()) {
                        if (!saved) reportSaveFailure();
                        return;
                    }
                    setChoicesEnabled(dialog, true);
                    if (!saved) {
                        reportSaveFailure();
                        return;
                    }
                    selectionSaved = true;
                    onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                    dialog.dismiss();
                });
            });
            if (!accepted) {
                setChoicesEnabled(dialog, true);
                reportSaveFailure();
            }
        });
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        if (!DialogDraft.open(getDialog()) || rows == null) return superState;
        return new DialogDraft(superState, DialogDraft.ofPicks(selected, null));
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof DialogDraft)) {
            super.onRestoreInstanceState(state);
            return;
        }
        // DialogPreference reopens the dialog from inside this call; showDialog puts the draft back.
        restoring = ((DialogDraft) state).values;
        try {
            super.onRestoreInstanceState(((DialogDraft) state).getSuperState());
        } finally {
            restoring = null;
        }
    }

    private void setChoicesEnabled(AlertDialog dialog, boolean enabled) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(enabled);
        for (int index = 0; index < rows.getChildCount(); index++) {
            rows.getChildAt(index).setEnabled(enabled);
        }
    }

    private void reportSaveFailure() {
        Utils.showToastShort(L10n.t(getContext(), "Couldn't save these choices. Try again."));
    }

    private void saveSelection() {
        if (Settings.PROFILE_TAB_PICKS.save(buildPicks())) {
            notifyChanged();
        } else {
            reportSaveFailure();
        }
    }

    private static Set<String> selectedKeys(String stored) {
        Set<String> picked = ProfileTabs.picks(stored);
        Set<String> keys = new HashSet<>();
        for (ProfileTabs.Row row : ProfileTabs.ROWS) {
            if (picked.contains(row.key)) keys.add(row.key);
        }
        return keys;
    }

    String buildPicks() {
        Set<String> known = new HashSet<>();
        for (ProfileTabs.Row row : ProfileTabs.ROWS) known.add(row.key);
        List<String> output = new ArrayList<>();
        for (String token : tokens(originalPicks)) {
            if (!known.contains(ProfileTabs.canonical(token))) output.add(token);
        }
        for (ProfileTabs.Row row : ProfileTabs.ROWS) {
            if (selected.contains(row.key)) output.add(row.key);
        }
        return String.join(", ", output);
    }

    private static List<String> tokens(String stored) {
        List<String> result = new ArrayList<>();
        if (stored == null) return result;
        for (String token : stored.split("[,\\n]")) {
            String value = token.trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }
}
