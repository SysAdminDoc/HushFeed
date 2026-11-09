/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.Preference;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;

/**
 * Opens the watch history kept on the phone (#172): a search box over the videos, newest first. A
 * tap opens the video over the list, so Back returns to it with the search still typed. Thumbnails
 * aren't kept, since TikTok's links to them run out.
 */
@SuppressWarnings("deprecation")
public final class LocalWatchHistoryPreference extends Preference {
    /** A stable key, so the settings search can find the row. */
    public static final String KEY = "action_local_watch_history";

    /** TikTok's video page, which loads a video from its id alone. */
    private static final String VIDEO_PAGE = "com.ss.android.ugc.aweme.detail.ui.DetailActivity";

    /**
     * The app's own link to a video, for when the page can't be started. It works too, but it
     * brings the feed forward on the way and closes settings, so Back doesn't return here.
     */
    private static final String VIDEO_LINK = "snssdk1233://aweme/detail/";

    public LocalWatchHistoryPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(L10n.t(context, "My watch history"));
        setSummary(L10n.t(context, "Find and reopen the videos you've watched on this phone"));
        setOnPreferenceClickListener(preference -> {
            show(context, false);
            return true;
        });
    }

    /**
     * The same list over TikTok's feed, from a long press on For You (#172). That press is
     * otherwise a way into these settings, so the list carries a button to them.
     */
    public static void showOverFeed(Context context) {
        show(context, true);
    }

    private static void show(Context context, boolean overFeed) {
        if (SettingsUi.dialogOpen(KEY, context)) return;
        int gap = SettingsUi.dp(context, 20);
        int height = SettingsUi.dialogListHeight(context, 420);

        EditText search = new EditText(context);
        search.setHint(L10n.t(context, "Search"));
        search.setSingleLine(true);
        SettingsUi.styleEditText(search);

        TextView empty = SettingsUi.text(context, L10n.t(context, "Videos you watch show up here"),
                SettingsUi.TEXT_BODY_SMALL, SettingsUi.textSecondary(), Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(gap, gap, gap, gap);
        empty.setVisibility(View.GONE);

        Rows rows = new Rows(context);
        ListView list = new ListView(context);
        list.setAdapter(rows);
        list.setDivider(null);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(gap, 0, gap, 0);
        page.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The same height as the list, so the dialog doesn't jump as a search empties it.
        page.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        page.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(L10n.t(context, "My watch history"))
                .setView(page)
                .setPositiveButton(L10n.t(context, "Close"), (target, which) -> target.dismiss())
                .setNeutralButton(L10n.t(context, "Clear"), null);
        if (overFeed) {
            builder.setNegativeButton(L10n.t(context, "Open Hushfeed settings"), (target, which) -> {
                target.dismiss();
                TikTokActivityHook.openSettings();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.show();
        SettingsUi.dialogOpened(KEY, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);

        TextView heading = dialog.findViewById(android.R.id.title);
        if (heading != null) {
            heading.setGravity(Gravity.START);
            SettingsUi.markDialogHeading(heading);
        }

        View.OnClickListener[] clearOrUndo = new View.OnClickListener[1];
        Button clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        boolean[] undoing = new boolean[1];
        Runnable refresh = () -> {
            List<LocalWatchHistory.Entry> shown = LocalWatchHistory.filter(rows.all, search.getText().toString());
            rows.show(shown);
            boolean nothing = shown.isEmpty();
            list.setVisibility(nothing ? View.GONE : View.VISIBLE);
            empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
            empty.setText(L10n.t(context, rows.all.isEmpty() ? "Videos you watch show up here" : "No videos match"));
            clear.setVisibility(undoing[0] || !rows.all.isEmpty() ? View.VISIBLE : View.GONE);
        };
        // A clear happens at once and stays a tap away from coming back while the list is open.
        clearOrUndo[0] = view -> LocalWatchHistory.clear(removed -> {
            undoing[0] = true;
            rows.all = Collections.emptyList();
            refresh.run();
            Utils.showToastShort(L10n.t(context, "Watch history cleared"));
            clear.setText(L10n.t(context, "Undo"));
            clear.setOnClickListener(undo -> {
                LocalWatchHistory.restore(removed);
                undoing[0] = false;
                rows.all = removed;
                refresh.run();
                clear.setText(L10n.t(context, "Clear"));
                clear.setOnClickListener(clearOrUndo[0]);
            });
        });
        clear.setOnClickListener(clearOrUndo[0]);

        list.setOnItemClickListener((parent, view, position, id) ->
                open(context, rows.shown.get(position).aid));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                refresh.run();
            }
        });
        LocalWatchHistory.load(loaded -> {
            rows.all = loaded;
            refresh.run();
        });
    }

    static void open(Context context, String aid) {
        try {
            try {
                start(context, new Intent().setClassName(context.getPackageName(), VIDEO_PAGE)
                        .putExtra("id", aid));
            } catch (ActivityNotFoundException ex) {
                start(context, new Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO_LINK + aid))
                        .setPackage(context.getPackageName()));
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open a video from the watch history", ex);
            Utils.showToastShort(L10n.t(context, "Couldn't open that video. Try again."));
        }
    }

    private static void start(Context context, Intent intent) {
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static final class Rows extends BaseAdapter {
        private final Context context;
        List<LocalWatchHistory.Entry> all = Collections.emptyList();
        List<LocalWatchHistory.Entry> shown = Collections.emptyList();

        Rows(Context context) {
            this.context = context;
        }

        void show(List<LocalWatchHistory.Entry> rows) {
            shown = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reused, ViewGroup parent) {
            LinearLayout row = (LinearLayout) reused;
            if (row == null) {
                int gap = SettingsUi.dp(context, 10);
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                // The search box's text sits a little inside its own edge.
                row.setPaddingRelative(SettingsUi.dp(context, 4), gap, 0, gap);
                row.addView(SettingsUi.text(context, "", SettingsUi.TEXT_BODY,
                        SettingsUi.textPrimary(), Typeface.BOLD));
                TextView caption = SettingsUi.text(context, "", SettingsUi.TEXT_BODY_SMALL,
                        SettingsUi.textSecondary(), Typeface.NORMAL);
                caption.setMaxLines(2);
                caption.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(caption);
            }
            LocalWatchHistory.Entry entry = shown.get(position);
            // Never younger than a minute, which would read "0 minutes ago".
            long now = System.currentTimeMillis();
            CharSequence when = DateUtils.getRelativeTimeSpanString(
                    Math.min(entry.watchedMs, now - DateUtils.MINUTE_IN_MILLIS), now,
                    DateUtils.MINUTE_IN_MILLIS);
            TextView title = (TextView) row.getChildAt(0);
            title.setText(entry.creator.isEmpty() ? when : entry.creator + " · " + when);
            TextView caption = (TextView) row.getChildAt(1);
            caption.setText(entry.caption);
            caption.setVisibility(entry.caption.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
