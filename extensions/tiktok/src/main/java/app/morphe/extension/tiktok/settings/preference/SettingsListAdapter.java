/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.database.DataSetObserver;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.WrapperListAdapter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/** Adds presentation to the platform adapter without changing preference binding or persistence. */
@SuppressWarnings("deprecation")
final class SettingsListAdapter extends BaseAdapter implements WrapperListAdapter {
    private static final int SEARCH_INPUT_OWNER = 0x7f7f4006;
    private final ListAdapter delegate;
    private final int searchInputViewType;
    /** The search's results, drawn as one section's card the way an open section draws its rows. */
    private final List<?> searchRows;
    /** The rows inside an open section or the search's results, by identity: they wear the inset card. */
    private final Map<Object, Boolean> sectionRows = new IdentityHashMap<>();
    private boolean sectionRowsStale = true;
    private final DataSetObserver observer = new DataSetObserver() {
        @Override public void onChanged() { sectionRowsStale = true; notifyDataSetChanged(); }
        @Override public void onInvalidated() { sectionRowsStale = true; notifyDataSetInvalidated(); }
    };

    SettingsListAdapter(ListAdapter delegate) {
        this(delegate, Collections.emptyList());
    }

    SettingsListAdapter(ListAdapter delegate, List<?> searchRows) {
        this.delegate = delegate;
        this.searchRows = searchRows;
        searchInputViewType = delegate.getViewTypeCount();
        delegate.registerDataSetObserver(observer);
    }

    void dispose() { delegate.unregisterDataSetObserver(observer); }
    @Override public ListAdapter getWrappedAdapter() { return delegate; }
    @Override public int getCount() { return delegate.getCount(); }
    @Override public Object getItem(int position) { return delegate.getItem(position); }
    @Override public long getItemId(int position) { return delegate.getItemId(position); }
    @Override public boolean hasStableIds() { return delegate.hasStableIds(); }
    @Override public int getItemViewType(int position) {
        return getItem(position) instanceof SettingsSearchInputPreference
                ? searchInputViewType : delegate.getItemViewType(position);
    }
    @Override public int getViewTypeCount() { return searchInputViewType + 1; }
    @Override public boolean areAllItemsEnabled() { return delegate.areAllItemsEnabled(); }
    @Override public boolean isEnabled(int position) { return delegate.isEnabled(position); }

    private boolean isBoundary(int position) {
        if (position < 0 || position >= getCount()) return true;
        Object item = getItem(position);
        return isErrorMessage(item)
                || item instanceof SettingsHeaderPreference || item instanceof PreferenceCategory
                || item instanceof SectionHeadingPreference
                || item instanceof SettingsStatusPreference
                || item instanceof SettingsSearchInputPreference
                || item instanceof SettingsSearchEntryPreference
                || item instanceof SettingsHeader
                // The restart bar is a button on the 18 dp line, not a row of the page.
                || item instanceof RestartPendingPreference;
    }

    /**
     * Whether [item] sits inside an open section or among the search's results. The main page's
     * own rows run full bleed; these sit in their section's card.
     */
    private boolean isSectionRow(Object item) {
        if (sectionRowsStale) {
            sectionRows.clear();
            for (int position = 0; position < getCount(); position++) {
                Object each = getItem(position);
                if (each instanceof SettingsMenuPreference) addDescendants((PreferenceGroup) each);
            }
            for (Object row : searchRows) sectionRows.put(row, Boolean.TRUE);
            sectionRowsStale = false;
        }
        return sectionRows.containsKey(item);
    }

    private void addDescendants(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            sectionRows.put(child, Boolean.TRUE);
            if (child instanceof PreferenceGroup) addDescendants((PreferenceGroup) child);
        }
    }

    /** The recovery page's message, which is drawn as the page's title rather than as a row. */
    private static boolean isErrorMessage(Object item) {
        return item instanceof Preference && AbstractPreferenceFragment.INITIALIZATION_ERROR_KEY
                .equals(((Preference) item).getKey());
    }

    /** The width a row of this list gets: the list's own once it has one, the screen's before. */
    private static int rowWidth(ViewGroup list) {
        int width = list.getWidth() > 0 ? list.getWidth() : list.getMeasuredWidth();
        if (width <= 0) width = list.getResources().getDisplayMetrics().widthPixels;
        return width - list.getPaddingLeft() - list.getPaddingRight();
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        TikTokPreferenceFragment.clearRecycledHighlight(convertView, getItem(position));
        SearchTogglePreference.clearLocationAction(convertView);
        // The platform freezes its view types when attached. Inline search arrives later,
        // so reserve its type here and bind it directly to keep the focused field reusable.
        View row;
        if (getItem(position) instanceof SettingsSearchInputPreference) {
            Preference input = (Preference) getItem(position);
            if (convertView != null && convertView.getTag(SEARCH_INPUT_OWNER) != input) convertView = null;
            row = input.getView(convertView, parent);
            row.setTag(SEARCH_INPUT_OWNER, input);
        } else row = delegate.getView(position, convertView, parent);
        if (isErrorMessage(getItem(position))) {
            SettingsUi.styleErrorHeading(row);
            return row;
        }
        if (isBoundary(position)) return row;
        Preference preference = (Preference) getItem(position);
        if (parent instanceof android.widget.ListView && parent.isLongClickable()
                && (preference.getClass() == Preference.class || preference instanceof TogglePreference
                    || (preference instanceof SettingsMenuPreference
                        && !((SettingsMenuPreference) preference).isExpandable()))
                && preference.isSelectable())
            SearchTogglePreference.bindTapOnly(row, preference);
        // A section's card opens on its first row and closes on its last: a heading, the end of
        // the section or a main page row on either side ends it.
        boolean section = isSectionRow(preference);
        boolean first = !section || isBoundary(position - 1) || !isSectionRow(getItem(position - 1));
        boolean last = !section || isBoundary(position + 1) || !isSectionRow(getItem(position + 1));
        ScreenColors.current().paintRow(row, preference, !section, first, last);
        NewSettingDots.paint(row, preference, SettingsUi.accent());
        if (preference instanceof SwitchPreference) SettingsUi.reflowSwitchRow(row, rowWidth(parent));
        // The recovery page's two actions were styled while the rows were bound, and the row
        // styling above painted over it, so Retry and Back came out as two identical rows.
        String key = preference.getKey();
        if (AbstractPreferenceFragment.INITIALIZATION_RETRY_KEY.equals(key)
                || AbstractPreferenceFragment.INITIALIZATION_BACK_KEY.equals(key)) {
            SettingsUi.styleErrorAction(row,
                    AbstractPreferenceFragment.INITIALIZATION_RETRY_KEY.equals(key));
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null && (preference instanceof ListPreference
                || preference instanceof DownloadPathPreference)) {
            summary.setTextColor(preference.isEnabled() ? SettingsUi.accent() : SettingsUi.textDisabled());
        }
        return row;
    }
}
