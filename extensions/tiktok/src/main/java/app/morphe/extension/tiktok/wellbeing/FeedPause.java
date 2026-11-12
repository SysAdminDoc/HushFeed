/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.ShimmerTitle;

/**
 * Pause before the feed: a countdown in front of the first feed after TikTok starts or comes
 * back, with Continue once it runs out and a way to leave instead.
 *
 * <p>Drawn on the hold's panel and scrim, since it is the same kind of stop. PausePlayback
 * decides when it goes up and keeps it over the feed and above the tab bar; this draws it and
 * runs the count.
 */
public final class FeedPause {
    private FeedPause() {
    }

    /** The pause's length, or zero when it is off. */
    public static long lengthMs() {
        return Math.max(0, Settings.PAUSE_BEFORE_FEED_SECONDS.get()) * 1000L;
    }

    /**
     * The panel, counting down to {@code endsAt} on {@link SystemClock#elapsedRealtime}. A panel
     * put up again after the reader looked at their messages carries on from the same end, so a
     * trip to the Inbox tab neither starts the wait over nor skips it.
     */
    public static View panel(Activity activity, long endsAt, Runnable onContinue, Runnable onLeave) {
        HoldPanel panel = new HoldPanel(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int sidePad = SettingsUi.dp(activity, 24);
        panel.setPadding(sidePad, sidePad, sidePad, sidePad);
        panel.setBackgroundColor(SettingsUi.OVERLAY_SCRIM_SOLID);
        // Swallows every touch, so the feed underneath can't be swiped while it waits.
        panel.setClickable(true);
        panel.setFocusable(false);

        String titleText = L10n.t(activity, "Pause before the feed");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) panel.setAccessibilityPaneTitle(titleText);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(SettingsUi.OVERLAY_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_HEADLINE);
        title.setGravity(Gravity.CENTER);
        ShimmerTitle.wear(title);
        panel.addView(title);

        // Not a live region while it counts: one announcement a second would talk over
        // everything else. It becomes one for the line that replaces the count at the end.
        TextView count = new TextView(activity);
        count.setTextColor(SettingsUi.overlayAccentOn(true));
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_DISPLAY);
        count.setGravity(Gravity.CENTER);
        count.setPadding(0, SettingsUi.dp(activity, 12), 0, SettingsUi.dp(activity, 12));
        panel.addView(count);

        TextView hint = new TextView(activity);
        hint.setText(L10n.t(activity, "Messages, profiles and search still work."));
        hint.setTextColor(SettingsUi.OVERLAY_TEXT_MUTED);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint);

        int padding = SettingsUi.dp(activity, 20);
        TextView go = new TextView(activity);
        go.setText(L10n.t(activity, "Continue"));
        SettingsUi.markAsButton(go);
        go.setTextColor(SettingsUi.OVERLAY_TEXT);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        go.setGravity(Gravity.CENTER);
        go.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY));
        go.setFocusable(true);
        go.setPadding(padding, SettingsUi.dp(activity, 14), padding, SettingsUi.dp(activity, 14));
        go.setMinimumHeight(SettingsUi.dp(activity, 48));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(-2, -2);
        goParams.topMargin = SettingsUi.dp(activity, 28);
        go.setLayoutParams(goParams);
        go.setOnClickListener(view -> {
            if (SystemClock.elapsedRealtime() >= endsAt) onContinue.run();
        });
        panel.addView(go);

        TextView leave = new TextView(activity);
        leave.setText(L10n.t(activity, "Leave TikTok"));
        SettingsUi.markAsButton(leave);
        leave.setTextColor(SettingsUi.OVERLAY_TEXT_MUTED);
        leave.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        leave.setGravity(Gravity.CENTER);
        leave.setPadding(padding, SettingsUi.dp(activity, 12), padding, SettingsUi.dp(activity, 12));
        leave.setMinimumHeight(SettingsUi.dp(activity, 48));
        leave.setBackground(SettingsUi.overlayAction(activity, SettingsUi.RADIUS_OVERLAY));
        leave.setFocusable(true);
        LinearLayout.LayoutParams leaveParams = new LinearLayout.LayoutParams(-2, -2);
        leaveParams.topMargin = SettingsUi.dp(activity, 8);
        leave.setLayoutParams(leaveParams);
        leave.setOnClickListener(view -> onLeave.run());
        panel.addView(leave);

        Runnable[] tick = new Runnable[1];
        boolean[] done = new boolean[1];
        tick[0] = () -> {
            if (done[0]) return;
            long left = endsAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                done[0] = true;
                count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                count.setText(L10n.t(activity, "You can continue now."));
                go.setEnabled(true);
                go.setAlpha(1f);
                return;
            }
            // Whole seconds, rounded up, so it never reads 0 while Continue still waits.
            long seconds = (left + 999) / 1000;
            count.setText(L10n.quantity(activity, seconds, "%1$s second", "%1$s seconds"));
            go.setEnabled(false);
            go.setAlpha(0.4f);
            // Only while attached. A panel TikTok takes off the page stops ticking, and the
            // attach below picks the count up from the same end when it goes back.
            if (panel.isAttachedToWindow()) panel.postDelayed(tick[0], left - (seconds - 1) * 1000);
        };
        tick[0].run();
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                panel.removeCallbacks(tick[0]);
                tick[0].run();
            }

            @Override public void onViewDetachedFromWindow(View view) {
                panel.removeCallbacks(tick[0]);
            }
        });
        return panel;
    }
}
