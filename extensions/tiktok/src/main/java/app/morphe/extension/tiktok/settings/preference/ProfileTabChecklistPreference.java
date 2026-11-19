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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
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
 * than a word, and the order of the ones left (#466). A pick the list no longer has is kept.
 */
@SuppressWarnings("deprecation")
public final class ProfileTabChecklistPreference extends DialogPreference {
    /** Where a recreated screen keeps the unsaved order, next to the picks. */
    private static final String DRAFT_ORDER = "profile_tab_order";

    private final Set<String> selected = new LinkedHashSet<>();
    /** Every row's key, top to bottom, as the strip will take them. */
    private final List<String> rowOrder = new ArrayList<>();
    private LinearLayout rows;
    private String originalPicks = "";
    private boolean selectionSaved;
    /** The unsaved picks a recreated screen hands back, while it reopens the dialog. */
    private Bundle restoring;

    public ProfileTabChecklistPreference(Context context) {
        super(context);
        setKey("profile_tab_checklist");
        setTitle("Hide or reorder profile tabs");
        setSummary("Pick the tabs to hide from the strip above a profile's videos, such as Reposts or Liked videos, and use the arrows to put the rest in your order. One tab always stays. Restart TikTok to apply this.");
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
        rowOrder.clear();
        rowOrder.addAll(ProfileTabs.shownOrder(Settings.PROFILE_TAB_ORDER.savedValue()));

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
        Context context = getContext();
        for (int index = 0; index < rowOrder.size(); index++) {
            ProfileTabs.Row row = row(rowOrder.get(index));
            if (row == null) continue;
            String label = L10n.t(context, row.label);
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox check = new CheckBox(context);
            check.setText(label);
            SettingsUi.styleCheckBoxRow(check);
            check.setTag("profile_tab_" + row.key);
            check.setChecked(selected.contains(row.key));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(row.key);
                else selected.remove(row.key);
            });
            line.addView(check, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(moveButton(row.key, label, "↑", "Move up", index > 0, -1));
            line.addView(moveButton(row.key, label, "↓", "Move down", index < rowOrder.size() - 1, 1));
            rows.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private TextView moveButton(String key, String label, String arrow, String action, boolean enabled, int step) {
        Context context = getContext();
        TextView button = SettingsUi.text(context, arrow, SettingsUi.TEXT_TITLE, SettingsUi.textPrimary(),
                Typeface.NORMAL);
        SettingsUi.styleTextAction(button, false);
        button.setTag("profile_tab_" + key + (step < 0 ? "_up" : "_down"));
        button.setContentDescription(L10n.f(context, "%1$s: %2$s", L10n.t(context, action), label));
        button.setEnabled(enabled);
        button.setClickable(true);
        button.setOnClickListener(view -> move(key, step));
        return button;
    }

    /** Moves a row one place, and keeps the focus on the arrow that moved it. */
    void move(String key, int step) {
        int from = rowOrder.indexOf(key);
        int to = from + step;
        if (from < 0 || to < 0 || to >= rowOrder.size()) return;
        Collections.swap(rowOrder, from, to);
        renderRows();
        View again = rows.findViewWithTag("profile_tab_" + key + (step < 0 ? "_up" : "_down"));
        if (again != null && again.isEnabled()) again.requestFocus();
    }

    private static ProfileTabs.Row row(String key) {
        for (ProfileTabs.Row row : ProfileTabs.ROWS) {
            if (row.key.equals(key)) return row;
        }
        return null;
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which)
                -> onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
        // Its own click listener below, so it sorts the rows without closing the dialog.
        builder.setNeutralButton(L10n.t(getContext(), "Original order"), null);
    }

    /** Puts the rows back in the list's own order, which saves as TikTok's order. */
    void resetOrder() {
        rowOrder.clear();
        rowOrder.addAll(ProfileTabs.shownOrder(""));
        renderRows();
    }

    /** The order to save: empty while the rows are in the list's own order, so TikTok's stays. */
    String buildOrder() {
        if (rowOrder.equals(ProfileTabs.shownOrder(""))) return "";
        return String.join(", ", rowOrder);
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
            List<String> order = draft.getStringArrayList(DRAFT_ORDER);
            if (order != null) {
                rowOrder.clear();
                rowOrder.addAll(ProfileTabs.shownOrder(String.join(",", order)));
            }
            renderRows();
        }
        SettingsUi.styleFramedDialog(getDialog());
        AlertDialog dialog = (AlertDialog) getDialog();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            if (view.isEnabled()) resetOrder();
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            String picks = buildPicks();
            String order = buildOrder();
            setChoicesEnabled(dialog, false);
            boolean accepted = Utils.runOnBackgroundThread(() -> {
                boolean saved = Settings.PROFILE_TAB_PICKS.save(picks) && Settings.PROFILE_TAB_ORDER.save(order);
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
        Bundle values = DialogDraft.ofPicks(selected, null);
        values.putStringArrayList(DRAFT_ORDER, new ArrayList<>(rowOrder));
        return new DialogDraft(superState, values);
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
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(enabled);
        if (enabled) {
            // The arrows at the ends stay off, so draw the rows again rather than switch them on.
            renderRows();
            return;
        }
        for (int index = 0; index < rows.getChildCount(); index++) {
            ViewGroup line = (ViewGroup) rows.getChildAt(index);
            for (int child = 0; child < line.getChildCount(); child++) {
                line.getChildAt(child).setEnabled(false);
            }
        }
    }

    private void reportSaveFailure() {
        Utils.showToastShort(L10n.t(getContext(), "Couldn't save these choices. Try again."));
    }

    private void saveSelection() {
        if (Settings.PROFILE_TAB_PICKS.save(buildPicks()) && Settings.PROFILE_TAB_ORDER.save(buildOrder())) {
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
