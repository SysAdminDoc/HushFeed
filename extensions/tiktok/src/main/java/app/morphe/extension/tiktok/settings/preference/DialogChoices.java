/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 * Modified for hushfeed (TikTok), 2026.
 */
package app.morphe.extension.tiktok.settings.preference;

import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

/** Styles the framework's real list rows without replacing selection or checkbox behavior. */
final class DialogChoices extends BaseAdapter {
    private final ListAdapter source;
    private final ScreenColors colors;
    private final boolean radio;

    DialogChoices(ListAdapter source, ScreenColors colors, boolean radio) {
        this.source = source;
        this.colors = colors;
        this.radio = radio;
    }

    /**
     * Puts [adapter] on [list] with the same rows checked: a new adapter clears ListView's checks.
     * It also drops the scroll position the dialog opened at, so a single choice is scrolled back
     * to the picked row the way AlertDialog put it there.
     */
    static void swap(ListView list, ListAdapter adapter) {
        // The platform's checklist adapter ticks a box in the list only as that row is first
        // drawn, so a box below the fold read as unticked until it was scrolled to, and a swap
        // made before the first layout carried none of them over. Binding each row once puts
        // every tick in the list before it's copied.
        ListAdapter before = list.getAdapter();
        if (before != null && list.getChoiceMode() == ListView.CHOICE_MODE_MULTIPLE) {
            for (int position = 0; position < before.getCount(); position++) {
                before.getView(position, null, list);
            }
        }
        SparseBooleanArray checked = list.getCheckedItemPositions();
        checked = checked == null ? null : checked.clone();
        list.setAdapter(adapter);
        if (checked == null) return;
        for (int index = 0; index < checked.size(); index++) {
            if (checked.valueAt(index)) list.setItemChecked(checked.keyAt(index), true);
        }
        int picked = list.getCheckedItemPosition();
        if (list.getChoiceMode() == ListView.CHOICE_MODE_SINGLE && picked != ListView.INVALID_POSITION) {
            list.setSelection(picked);
        }
    }

    @Override public int getCount() { return source.getCount(); }
    @Override public Object getItem(int position) { return source.getItem(position); }
    @Override public long getItemId(int position) { return source.getItemId(position); }
    @Override public boolean hasStableIds() { return source.hasStableIds(); }
    @Override public boolean isEnabled(int position) { return source.isEnabled(position); }
    @Override public boolean areAllItemsEnabled() { return source.areAllItemsEnabled(); }
    @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
    @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        if (row instanceof CheckedTextView) {
            CheckedTextView choice = (CheckedTextView) row;
            float density = row.getResources().getDisplayMetrics().density;
            // The mark, the text colour and the start padding that puts the mark under the
            // title's first letter are the dialog's own, shared with rows outside a list.
            SettingsUi.styleChoiceRow(choice, radio);
            choice.setTextSize(SettingsUi.TEXT_TITLE);
            choice.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            choice.setSingleLine(false);
            choice.setMaxLines(Integer.MAX_VALUE);
            choice.setMinHeight(Math.round(48 * density));
            choice.setMinimumHeight(Math.round(48 * density));
            GradientDrawable background = new GradientDrawable();
            background.setColor(colors.choiceFill(Color.TRANSPARENT));
            background.setStroke(Math.max(1, Math.round(density)), colors.interactionOutline(Color.TRANSPARENT));
            background.setCornerRadius(Math.round(SettingsUi.RADIUS_FIELD * density));
            // The picked row's tint sits inside the panel's rounded corners instead of running
            // into its edges, and the mark keeps its place. The press shows in the same shape:
            // masked by the clear background of a row that isn't picked, it didn't show at all.
            int inset = Math.round(12 * density);
            GradientDrawable press = new GradientDrawable();
            press.setColor(Color.WHITE);
            press.setCornerRadius(Math.round(SettingsUi.RADIUS_FIELD * density));
            choice.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading),
                    new InsetDrawable(background, inset, 0, inset, 0),
                    new InsetDrawable(press, inset, 0, inset, 0)).mutate());
            // A native choice is a compact full-width row, with room to grow when text wraps.
            int verticalPadding = (int) (8 * density);
            choice.setPaddingRelative(choice.getPaddingStart(), verticalPadding,
                    choice.getPaddingEnd(), verticalPadding);
            return row;
        }
        if (row instanceof TextView) {
            TextView choice = (TextView) row;
            float density = row.getResources().getDisplayMetrics().density;
            choice.setTextColor(ScreenColors.dimmedWhenDisabled(colors.title));
            choice.setTextSize(SettingsUi.TEXT_TITLE);
            choice.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            choice.setSingleLine(false);
            choice.setMaxLines(Integer.MAX_VALUE);
            int side = Math.round(18 * density);
            choice.setMinHeight(Math.round(48 * density));
            choice.setMinimumHeight(Math.round(48 * density));
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(colors.choiceFill(colors.card));
            surface.setStroke(Math.max(1, Math.round(density)), colors.interactionOutline(colors.outline));
            surface.setCornerRadius(Math.round(SettingsUi.RADIUS_SETTINGS_CARD * density));
            // The list's divider is the gap between two cards, so a card fills its row's height.
            choice.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading),
                    new InsetDrawable(surface, side, 0, side, 0), null).mutate());
            // Rounding both sides up at densities such as 450dpi exceeds the measured 48dp row.
            int verticalPadding = (int) (12 * density);
            choice.setPaddingRelative(side + Math.round(16 * density), verticalPadding,
                    side + Math.round(16 * density), verticalPadding);
        }
        return row;
    }
}
