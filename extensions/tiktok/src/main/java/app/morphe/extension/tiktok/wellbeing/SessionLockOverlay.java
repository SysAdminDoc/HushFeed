/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioManager;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.ShimmerTitle;
import app.morphe.extension.tiktok.spoof.region.RegionSpoof;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Locale;

/**
 * Covers the feed while a hold is running, and nothing else.
 *
 * <p>It is a view over the activity's content root, the same place the block button lives, shown
 * only while the feed itself is on screen. Messages, a profile and search are all still there
 * underneath it, because the only thing that decides whether this is visible is whether the home
 * tab is selected. It also does not touch a single feed item, so the batch TikTok already
 * fetched is still sitting there when the hold ends, and nothing is refetched.
 *
 * <p>The way out of the hold is on the panel. Anything else drawn on the content root ends up
 * underneath it, because this covers the whole root and swallows every touch, so a banner
 * offering an Undo would be both invisible and untappable.
 */
public final class SessionLockOverlay {
    private static final long TICK_MS = 1_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<View> overlayReference = new WeakReference<>(null);
    private static WeakReference<ViewGroup> geometryRootReference = new WeakReference<>(null);
    private static final ViewTreeObserver.OnGlobalLayoutListener GEOMETRY =
            SessionLockOverlay::syncGeometry;
    // All overlay geometry runs on the main thread. Reuse these on player and layout callbacks.
    private static final int[] ROOT_POSITION = new int[2];
    private static final int[] NAVIGATION_POSITION = new int[2];
    /** What a curtained view said about itself before a panel covered it, and which panels are over it. */
    private static final class Curtained {
        int importance;
        /** Its descendant focusability, or -1 for a view that is not a ViewGroup. */
        final int focusability;
        boolean focusable;
        /** NOT_FOCUSABLE, FOCUSABLE or FOCUSABLE_AUTO from API 26, which is what it is put back to. */
        int focusableMode;
        boolean focusableInTouchMode;
        final java.util.Set<View> panels = java.util.Collections.newSetFromMap(
                new java.util.WeakHashMap<>());
        /** The panels hiding everything inside it as well, rather than only its own stop. */
        final java.util.Set<View> whole = java.util.Collections.newSetFromMap(
                new java.util.WeakHashMap<>());
        /** Whether its descendants are blocked from focus. */
        boolean hidden;
        /** The importance for accessibility a panel last set on it, or -1 while none is over it. */
        int set = -1;

        Curtained(View view) {
            importance = view.getImportantForAccessibility();
            focusability = view instanceof ViewGroup
                    ? ((ViewGroup) view).getDescendantFocusability() : -1;
            focusable = view.isFocusable();
            focusableMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? view.getFocusable() : 0;
            focusableInTouchMode = view.isFocusableInTouchMode();
        }
    }

    private static final java.util.WeakHashMap<View, Curtained> curtained =
            new java.util.WeakHashMap<>();
    /** Windows whose callback keeps the keys that move focus off what a curtain goes around. */
    private static final java.util.WeakHashMap<Window, Boolean> STEERED = new java.util.WeakHashMap<>();
    private static WeakReference<TextView> titleReference = new WeakReference<>(null);
    private static WeakReference<TextView> remainingReference = new WeakReference<>(null);
    private static WeakReference<TextView> releaseReference = new WeakReference<>(null);
    private static WeakReference<TextView> hintReference = new WeakReference<>(null);
    private static WeakReference<TextView> messagesReference = new WeakReference<>(null);
    private static volatile boolean ticking;
    /**
     * Whether the app is on screen. A stopped activity whose decor is still attached is not
     * finishing and not destroyed, so nothing in {@link #sync()} could tell the reader had gone
     * to another app, and a hold that runs to the reset hour kept a once-a-second main-thread
     * timer going for the whole of it while TikTok sat in recents.
     */
    private static volatile boolean foreground = true;

    /**
     * Runs only while a hold is running and the app is on screen. A repeating timer that
     * outlives the hold, or the app being looked at, would be a second-by-second wake-up for
     * nobody.
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!foreground) {
                ticking = false;
                return;
            }
            // A change to the feed lock that waited for the day to start over lands here, since
            // nothing counts for a lock that has no budget behind it.
            BudgetChanges.applyDue(SessionBudget.now());
            sync();
            if (!wantsTick()) {
                ticking = false;
                return;
            }
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    /** Written from the audio focus callback, which is not guaranteed to be the main thread. */
    private static volatile boolean quietened;
    private static AudioManager quietManager;
    /** A transient loss keeps the request on the stack without granting playback permission. */
    private static volatile boolean focusGranted;

    /**
     * Held while the panel covers the feed. Losing focus prevents an explicit player resume
     * when the hold ends, so another app keeps control of the sound.
     */
    private static final AudioManager.OnAudioFocusChangeListener QUIET = change -> {
        // Losing it for good, to a call or to another app, drops us off the focus stack, and
        // the flag has to follow or nothing would ever ask again. What must not happen is
        // asking again a second later: that is how you take the sound off the call that just
        // took it, once a second, for the length of the hold. So the flag is cleared and the
        // next request waits for the panel to be put up again, which only happens when the
        // reader comes back to the feed.
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            quietened = false;
            focusGranted = false;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusGranted = false;
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusGranted = quietened;
        }
    };

    private SessionLockOverlay() {
    }

    static void resetForTests() {
        ticking = false;
        // PausePlayback's lifecycle callbacks report the app gone when a suite's activities stop,
        // and a stale false stops the countdown, and the sync it runs, for every later suite.
        foreground = true;
    }

    /**
     * Whether the timer has anything to do: a hold running, or the feed lock's panel in place,
     * shown or tucked away. The lock has no countdown, but it has a panel that must come down
     * the moment a bottom tab leaves the feed and go back up when one returns to it, and a
     * paused video sends no progress to say so.
     */
    private static boolean wantsTick() {
        return SessionBudget.isLocked() || (FeedLock.isOn() && overlayReference.get() != null);
    }

    /** The app left the screen: the countdown stops on its next tick and the panel stays as it is. */
    public static void onBackground() {
        foreground = false;
        FeedSchedule.onBackground();
    }

    /** The app is back: a hold still running gets its countdown back. */
    public static void onForeground() {
        foreground = true;
        BudgetChanges.applyDue(SessionBudget.now());
        // The lock's weekly hours are read from the clock as it is now, the zone included.
        FeedSchedule.onForeground();
        ensureRunning();
        // A hold that ended while nothing ticked, the app away past the reset hour, left its
        // panel up with nothing to take it down: the tick needs a running hold to start, and the
        // player's progress never syncs an ended one.
        if (!SessionBudget.isLocked()) {
            Utils.runOnMainThread(() -> {
                // The feed lock has no countdown to bring a panel down or up, so a return to
                // the app is where it follows the switch.
                if (!SessionBudget.isLocked()
                        && (overlayReference.get() != null || FeedLock.isOn())) {
                    sync();
                }
            });
        }
    }

    /**
     * Starts the countdown if a hold is running. Called from the player's progress callback, so
     * the already-running case must not reach the budget's monitor at all.
     */
    public static void ensureRunning() {
        if (ticking || !(SessionBudget.isLocked() || FeedLock.isOn())) return;
        Utils.runOnMainThread(() -> {
            if (ticking || !(SessionBudget.isLocked() || FeedLock.isOn())) return;
            ticking = true;
            MAIN.post(TICK);
        });
    }

    /** Puts the overlay where it belongs for the moment, attaching or removing as needed. */
    public static void sync() {
        try {
            boolean budgetHold = SessionBudget.isLocked();
            if (!budgetHold && !FeedLock.covers()) {
                detach();
                return;
            }
            // The last video is playing out and the feed's swipe is off meanwhile. The panel and
            // its pause follow the video's end, which calls this again; the countdown's own tick
            // catches a wait that ran out at its limit instead.
            if (budgetHold && FinishLastVideo.pending()) {
                detach();
                return;
            }
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                // Mid teardown, so there is nothing to attach to yet. The hold is still on and
                // the player may still be running, so the sound is still worth asking for.
                requestQuiet();
                return;
            }
            // The feed lock alone is stricter about what counts as the feed: a video opened from
            // a message or a search is a page of the same activity, and it is not covered.
            boolean onFeed = budgetHold ? FeedVisibility.isOnFeed(activity)
                    : FeedLock.coversFeedOn(activity);
            if (!onFeed) {
                // Messages, profiles and search still work, which the panel says in as many
                // words, so the hold has no business silencing anything played there.
                releaseQuiet();
                View existing = overlayReference.get();
                if (existing != null) {
                    // Hidden is not attached: the panel is left in place while the reader is on
                    // messages or a profile, and the feed has to be readable again meanwhile.
                    // Handed back first, so focus leaving the hidden panel can land in it.
                    hideBehind(parentOf(existing), existing, false);
                    existing.setVisibility(View.GONE);
                } else {
                    // A panel TikTok took off and the collector took away still left its curtain
                    // on everything it covered.
                    hideBehind(null, null, false);
                }
                return;
            }
            // A native resume can arrive while the same panel is already visible. Reapply the
            // actual player pause on every hold sync, before audio focus changes its play state.
            SessionPlaybackHold.pauseIfPlaying();
            View before = overlayReference.get();
            View overlay = attach(activity);
            if (overlay == null) {
                // No content root to hold the panel. The feed is still running, so ask for the
                // sound anyway rather than leaving the hold with nothing at all. With no panel up,
                // nothing behind one stays out of reach either.
                hideBehind(null, null, false);
                requestQuiet();
                return;
            }
            updateNavigationMargin(activity, parentOf(overlay), overlay);
            // The lock's panel follows the tabs by this timer: no tick or progress report comes
            // for a paused video on a tab the reader just left.
            if (!budgetHold) ensureRunning();
            // A panel that was just built, or one coming back from the reader being away on
            // messages or search. Not every tick: a tick that asked again would be asking a
            // phone call to give the sound back once a second, for as long as the hold runs.
            boolean goingUp = before != overlay || overlay.getVisibility() != View.VISIBLE;
            overlay.setVisibility(View.VISIBLE);
            if (goingUp) {
                ViewGroup root = parentOf(overlay);
                hideBehind(root, overlay, true, navigationBar(activity, root));
            }
            if (goingUp) requestQuiet();
            // Over a cleared screen the tabs the panel points to are gone: bring them back.
            if (goingUp) RememberClearDisplayPatch.leaveForHold();
            if (goingUp && before == overlay && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // A retained panel shown again after the reader was away on messages or search.
                // From P the pane title announces the arrival each time; before it, the one
                // announcement made at attach has already been spent.
                overlay.announceForAccessibility(holdTitle());
            }
            // Only as the panel goes up. Whether there is an Inbox tab changes when TikTok
            // rebuilds its tab bar, not second by second, and for a reader who has hidden Inbox
            // the answer is a failed lookup every time: asking on every tick would be a walk of
            // the whole view tree once a second for the length of the hold.
            if (goingUp) applyMessagesAction(activity);
            // Only when the number moved. The countdown is in whole minutes and this runs once a
            // second, so fifty-nine writes in sixty say exactly what was already there, and each
            // of them interrupted a screen reader.
            SettingsUi.setTextIfChanged(remainingReference.get(), remainingLabel());
            applyLockedState();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not update the session lock overlay", error);
        }
    }

    /**
     * Offers the way to messages only while there is an Inbox tab to open.
     *
     * <p>Absent when this build renames the tab and when the reader has hidden Inbox in Feed
     * navigation, which is the same thing as far as the panel is concerned: the filter drops the
     * tab from the model, so no view is ever built and there is nothing to offer.
     */
    private static void applyMessagesAction(Activity activity) {
        TextView messages = messagesReference.get();
        if (messages == null) return;
        boolean reachable = activity != null && FeedVisibility.inboxTabView(activity) != null;
        messages.setVisibility(reachable ? View.VISIBLE : View.GONE);
    }

    /**
     * Takes the way out away on a day the reader locked, and says when it comes back.
     *
     * <p>Run on every sync rather than only when the panel is built, because the panel can
     * outlive the day: it is left attached while the reader is on messages or a profile.
     */
    private static void applyLockedState() {
        boolean budgetHold = SessionBudget.isLocked();
        // The title, the countdown and the way out belong to the budget's hold. The feed lock
        // alone is a calm panel with the title and the way to messages.
        // Whichever reason holds the panel now: a budget hold can take over a lock's panel and
        // give it back, and the pane title is what a screen reader announces on each arrival.
        String titleText = holdTitle();
        SettingsUi.setTextIfChanged(titleReference.get(), titleText);
        View panel = overlayReference.get();
        if (panel != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && !titleText.contentEquals(String.valueOf(panel.getAccessibilityPaneTitle()))) {
            panel.setAccessibilityPaneTitle(titleText);
        }
        TextView countdown = remainingReference.get();
        if (countdown != null) countdown.setVisibility(budgetHold ? View.VISIBLE : View.GONE);
        boolean locked = SessionBudget.lockedToday();
        TextView release = releaseReference.get();
        if (release != null) {
            int left = SessionBudget.passesLeftToday();
            // Spent is the same as locked as far as this control goes: there is no way through
            // today. The hint below still says what does work, so the panel is not a dead end.
            // The pass is the budget's. While the feed lock is on it would only say the feed is open
            // again over a panel that stays up, so it isn't offered.
            release.setVisibility(!budgetHold || FeedLock.isOn() || locked || left == 0
                    ? View.GONE : View.VISIBLE);
            String label = releaseLabel(left);
            // Both only when they moved: a content description is announced the same way the
            // text is, so writing it back unchanged is the same interruption.
            if (!android.text.TextUtils.equals(release.getText(), label)) {
                release.setText(label);
                release.setContentDescription(label);
            }
        }
        TextView hint = hintReference.get();
        if (hint == null) return;
        // One literal, because the translation gate reads the literal handed to L10n and a
        // string built from two of them is two entries it cannot find.
        String hintText;
        if (budgetHold && locked && !FeedLock.isOn()) {
            hintText = L10n.f("Today's budget is locked. The feed opens again at %1$s. Messages, profiles and search still work.", resetTimeLabel());
        } else if (!budgetHold && FeedLock.onByHoursOnly() && FeedSchedule.nextChangeAt() > 0) {
            // The lock's hours end by themselves, so the panel says when.
            hintText = L10n.f("The feed is locked until %1$s. Messages, profiles and search still work.",
                    FeedSchedule.dayTimeLabel(FeedSchedule.nextChangeAt()));
        } else {
            hintText = L10n.t("Messages, profiles and search still work.");
        }
        SettingsUi.setTextIfChanged(hint, hintText);
    }

    /**
     * What the way out says, which is how many are left when the reader capped them.
     *
     * <p>Each form is its own literal rather than a label with a count appended, because the
     * translation gate reads the literal handed to L10n and a sentence built from two of them is
     * two entries it cannot find.
     */
    static String releaseLabel(int passesLeft) {
        if (passesLeft == Integer.MAX_VALUE) return L10n.t("Open the feed anyway");
        if (passesLeft == 1) return L10n.t("Open the feed anyway, the last time today");
        return L10n.f("Open the feed anyway, %1$d left today", passesLeft);
    }

    /** The hour the locked day ends, on the reader's own clock. */
    public static String resetTimeLabel() {
        long until = SessionBudget.lockedUntilMs();
        if (until <= 0) return "";
        return timeLabel(until);
    }

    /** A moment on the reader's own clock, 4:00 AM or 04:00 as the phone is set. */
    public static String timeLabel(long at) {
        Context context = Utils.getContext();
        if (context == null) {
            return RegionSpoof.phoneClock(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT))
                    .format(new java.util.Date(at));
        }
        return RegionSpoof.phoneClock(android.text.format.DateFormat.getTimeFormat(context))
                .format(new java.util.Date(at));
    }

    static String remainingLabel() {
        long remainingMs = SessionBudget.lockRemainingMs();
        long totalMinutes = (remainingMs + 59_999L) / 60_000L;
        if (totalMinutes <= 0) return L10n.t("Less than a minute left");
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) {
            return L10n.quantity(Utils.getContext(), minutes, "One minute left", "%1$d minutes left");
        }
        // Built before the call so the clock face is not mistaken for text to translate.
        String clock = String.format(Locale.getDefault(), "%d:%02d", hours, minutes);
        return L10n.f("%1$s left", clock);
    }

    /** What the panel says it is for right now: the budget's message, or the feed lock's. */
    private static String holdTitle() {
        return SessionBudget.isLocked() ? SessionBudgetNotice.spentMessage() : L10n.t("The feed is locked");
    }

    private static View attach(Activity activity) {
        View existing = overlayReference.get();
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        if (existing != null && existing.getParent() == root) return existing;
        if (existing != null) {
            // A panel left on a page TikTok has since replaced. Its curtain would keep that
            // page's feed out of reach, and going back there would find two panels.
            ViewGroup oldRoot = parentOf(existing);
            hideBehind(oldRoot, existing, false);
            if (oldRoot != null) oldRoot.removeView(existing);
        }

        // Scrolls once the stack is taller than the room above the tab bar, so both buttons stay
        // reachable at the largest font and display sizes (see HoldPanel).
        LinearLayout panel = new HoldPanel(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int sidePad = SettingsUi.dp(activity, 24);
        panel.setPadding(sidePad, sidePad, sidePad, sidePad);
        panel.setBackgroundColor(SettingsUi.OVERLAY_SCRIM_SOLID);
        // Swallows every touch, so the feed underneath stops scrolling without being emptied.
        panel.setClickable(true);
        // Not a Tab stop of its own: pressing it does nothing. Explicit, because a clickable
        // view counts as focusable from API 26 unless it says otherwise.
        panel.setFocusable(false);
        // Touch was the only thing it swallowed. A screen reader was told nothing when the hold
        // went up, and could still swipe through to the like, comment and share controls behind
        // it, which is the one thing the hold exists to stop. The arrival is announced by the
        // pane title below, and the countdown carries the live region itself: marking the whole
        // panel read the entire hold out again on every tick, because a live region announces
        // for anything that changes anywhere under it.

        TextView title = new TextView(activity);
        String titleText = holdTitle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            panel.setAccessibilityPaneTitle(titleText);
        }
        title.setText(titleText);
        title.setTextColor(SettingsUi.OVERLAY_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_HEADLINE);
        title.setGravity(Gravity.CENTER);
        // In the moving cyan and red, as on the App lock cover. The light ink picks the colours
        // for a dark ground, which this scrim is in either theme.
        ShimmerTitle.wear(title);
        panel.addView(title);
        titleReference = new WeakReference<>(title);

        TextView remaining = new TextView(activity);
        remaining.setText(remainingLabel());
        // The one thing on the panel that changes while the hold runs, so it is the one thing
        // worth announcing. Set here rather than on the panel, and written only when the minute
        // changes (see sync), so the reader hears a new number and nothing else.
        remaining.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        // The panel is always this near-black scrim, so the countdown takes the colour meant
        // for what this project draws over the app rather than the settings accent, which is a
        // dark crimson in the light theme and 3:1 on black. SettingsUi.isDarkMode is a cached
        // flag the settings screen sets, so off the settings screen it answers for the system
        // theme rather than for this panel.
        remaining.setTextColor(SettingsUi.overlayAccentOn(true));
        remaining.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_DISPLAY);
        remaining.setGravity(Gravity.CENTER);
        remaining.setPadding(0, SettingsUi.dp(activity, 12), 0, SettingsUi.dp(activity, 12));
        panel.addView(remaining);
        remainingReference = new WeakReference<>(remaining);

        TextView hint = new TextView(activity);
        hint.setText(L10n.t(activity, "Messages, profiles and search still work."));
        hint.setTextColor(SettingsUi.OVERLAY_TEXT_MUTED);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint);
        hintReference = new WeakReference<>(hint);

        // The way out. A budget nobody can overrule is a budget people switch off instead, and
        // this has to be here rather than on a banner, which the panel would cover.
        TextView release = new TextView(activity);
        release.setText(L10n.t(activity, "Open the feed anyway"));
        release.setContentDescription(L10n.t(activity, "Open the feed anyway"));
        SettingsUi.markAsButton(release);
        release.setTextColor(SettingsUi.OVERLAY_TEXT);
        release.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        release.setGravity(Gravity.CENTER);
        // A radius from the scale. It was 24 on a 48dp control, which is a pill, and it was the
        // only pill in the bundle: the four feed controls and the budget cue it sits beside all
        // read as rounded rectangles.
        // The press and focus states every control this bundle draws now carries. It was a flat
        // fill that looked the same before, during and after a press.
        release.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY));
        release.setFocusable(true);
        int padding = SettingsUi.dp(activity, 20);
        release.setPadding(padding, SettingsUi.dp(activity, 14), padding, SettingsUi.dp(activity, 14));
        release.setMinimumHeight(SettingsUi.dp(activity, 48));
        LinearLayout.LayoutParams releaseParams = new LinearLayout.LayoutParams(-2, -2);
        releaseParams.topMargin = SettingsUi.dp(activity, 28);
        release.setLayoutParams(releaseParams);
        release.setOnClickListener(view -> {
            // The model refuses this on a locked day and on a day whose passes are spent.
            // Checked through its answer, so a refused tap says nothing rather than claiming
            // the feed opened.
            if (FeedLock.isOn() || SessionBudget.lockedToday()) return;
            if (!SessionBudget.releaseLock()) {
                sync();
                return;
            }
            sync();
            Utils.showToastShort(L10n.t("The feed is open again"));
        });
        panel.addView(release);
        releaseReference = new WeakReference<>(release);

        // The panel says messages still work and then covers everything, so the reader has to
        // already know the Inbox tab is under it. This is that tab's own view, clicked the way a
        // finger would: no call into a renamed method, and TikTok's own listener does the rest.
        TextView messages = new TextView(activity);
        messages.setText(L10n.t(activity, "Open messages"));
        messages.setContentDescription(L10n.t(activity, "Open messages"));
        SettingsUi.markAsButton(messages);
        messages.setTextColor(SettingsUi.OVERLAY_TEXT_MUTED);
        messages.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        messages.setGravity(Gravity.CENTER);
        messages.setPadding(padding, SettingsUi.dp(activity, 12), padding,
                SettingsUi.dp(activity, 12));
        messages.setMinimumHeight(SettingsUi.dp(activity, 48));
        messages.setBackground(SettingsUi.overlayAction(activity, SettingsUi.RADIUS_OVERLAY));
        messages.setFocusable(true);
        LinearLayout.LayoutParams messagesParams = new LinearLayout.LayoutParams(-2, -2);
        messagesParams.topMargin = SettingsUi.dp(activity, 8);
        messages.setLayoutParams(messagesParams);
        messages.setOnClickListener(view -> {
            View inbox = FeedVisibility.inboxTabView(activity);
            if (inbox == null) return;
            inbox.performClick();
            // Off the feed the panel hides itself rather than detaching, so the hold is still
            // running and comes back the moment the reader does.
            sync();
        });
        panel.addView(messages);
        messagesReference = new WeakReference<>(messages);
        applyMessagesAction(activity);

        applyLockedState();

        // Stops above the navigation. Covering the whole content root would take the tab bar
        // with it, and then messages, profiles and search are not reachable at all, which is the
        // one thing the panel says it leaves alone.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        params.bottomMargin = navigationHeight(activity, root);
        panel.setLayoutParams(params);
        root.addView(panel);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // There is no pane title before P, so on those builds the arrival is said once by
            // hand. Needs a parent, which is why it is here and not beside the pane title.
            panel.announceForAccessibility(titleText);
        }
        hideBehind(root, panel, true, navigationBar(activity, root));
        overlayReference = new WeakReference<>(panel);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (overlayReference.get() == view) watchGeometry(parentOf(view));
            }

            @Override public void onViewDetachedFromWindow(View view) {
                if (overlayReference.get() == view) stopWatchingGeometry();
            }
        });
        watchGeometry(root);
        Logger.printDebug(() -> "Session lock overlay attached");
        return panel;
    }

    /**
     * The bottom margin that leaves TikTok's navigation reachable, measured on the main thread.
     *
     * <p>The content root can extend behind the system navigation bar. Its margin must include
     * that gap below TikTok's row, while root padding has already been deducted by FrameLayout.
     * Unknown or implausible geometry keeps the feed fully covered until a later layout.
     */
    public static int navigationHeight(Activity activity, ViewGroup root) {
        View bar = navigationBar(activity, root);
        return bar == null ? 0 : marginUnder(root, bar);
    }

    /**
     * TikTok's tab bar, when a panel over {@code root} stops above it: the row
     * {@link #navigationHeight} measures, or null while it covers the whole root.
     */
    public static View navigationBar(Activity activity, ViewGroup root) {
        if (activity == null || root == null || root.getHeight() <= 0) return null;
        View homeTab = FeedVisibility.homeTabView(activity);
        if (homeTab == null) return null;

        View bar = homeTab;
        int contentWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        // Up to the row that spans the width, which is the bar rather than the one tab in it.
        for (int step = 0; step < 4 && bar.getParent() instanceof ViewGroup; step++) {
            ViewGroup parent = (ViewGroup) bar.getParent();
            if (parent == root) break;
            bar = parent;
            if (bar.getWidth() >= contentWidth && bar.getWidth() > 0) break;
        }
        // A bar clear display set GONE keeps its last size and place, and that strip is where
        // TikTok draws its own clear display bar, whose X brings the tabs back. It stays out from
        // under the panel like the tabs do: covered, nothing on screen led anywhere but the
        // panel's own buttons.
        int height = bar.getHeight();
        if (height <= 0 || height >= root.getHeight() / 3) return null;
        View ancestor = bar;
        while (ancestor != root && ancestor.getParent() instanceof View) {
            ancestor = (View) ancestor.getParent();
        }
        if (ancestor != root) return null;
        return marginUnder(root, bar) > 0 ? bar : null;
    }

    private static int marginUnder(ViewGroup root, View bar) {
        root.getLocationOnScreen(ROOT_POSITION);
        bar.getLocationOnScreen(NAVIGATION_POSITION);
        int margin = ROOT_POSITION[1] + root.getHeight() - root.getPaddingBottom()
                - NAVIGATION_POSITION[1];
        return margin >= bar.getHeight() && margin < root.getHeight() / 3 ? margin : 0;
    }

    /** A geometry change cannot request focus or change native playback. */
    private static void syncGeometry() {
        try {
            View overlay = overlayReference.get();
            ViewGroup root = geometryRootReference.get();
            Activity activity = Utils.getActivity();
            if (overlay == null || overlay.getVisibility() != View.VISIBLE || root == null
                    || parentOf(overlay) != root || activity == null
                    || activity.isFinishing() || activity.isDestroyed()
                    || activity.findViewById(android.R.id.content) != root) return;
            updateNavigationMargin(activity, root, overlay);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not update the session hold geometry", error);
        }
    }

    private static void updateNavigationMargin(Activity activity, ViewGroup root, View overlay) {
        if (!(overlay.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlay.getLayoutParams();
        View bar = navigationBar(activity, root);
        int margin = bar == null ? 0 : marginUnder(root, bar);
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin;
            overlay.setLayoutParams(params);
        }
        // Every pass, not only when the bar comes out from under the panel or goes back under
        // it: views TikTok adds beside the bar's parents after the panel went up are behind it too.
        if (overlay.getVisibility() == View.VISIBLE) hideBehind(root, overlay, true, bar);
    }

    private static void watchGeometry(ViewGroup root) {
        if (root == geometryRootReference.get()) return;
        stopWatchingGeometry();
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(GEOMETRY);
        geometryRootReference = new WeakReference<>(root);
    }

    private static void stopWatchingGeometry() {
        ViewGroup root = geometryRootReference.get();
        if (root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(GEOMETRY);
        }
        geometryRootReference = new WeakReference<>(null);
    }

    /**
     * Keeps the hold on the audio focus stack as well as pausing the native player. Focus also
     * gives release a signal that another app took control while the hold was visible.
     */
    @SuppressWarnings("deprecation")
    private static void requestQuiet() {
        if (quietened) return;
        AudioManager audio = audioManager();
        if (audio == null) return;
        try {
            // The request-object form arrived in API 26 and this runs from 23. The older call is
            // deprecated rather than gone, and it is the one both understand.
            int granted = audio.requestAudioFocus(QUIET, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            quietManager = audio;
            quietened = true;
            focusGranted = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception refused) {
            Logger.printDebug(() -> "The hold could not take the audio focus");
        }
    }

    @SuppressWarnings("deprecation")
    private static void releaseQuiet() {
        AudioManager audio = quietManager;
        quietManager = null;
        quietened = false;
        focusGranted = false;
        if (audio == null) return;
        try {
            audio.abandonAudioFocus(QUIET);
        } catch (Exception ignored) {
            Logger.printDebug(() -> "The hold could not hand the audio focus back");
        }
    }

    private static AudioManager audioManager() {
        Context context = Utils.getContext();
        if (context != null && context.getApplicationContext() != null) {
            context = context.getApplicationContext();
        }
        return context == null ? null
                : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private static void detach() {
        stopWatchingGeometry();
        boolean mayResume = quietened && focusGranted;
        releaseQuiet();
        SessionPlaybackHold.release(mayResume);
        View overlay = overlayReference.get();
        overlayReference = new WeakReference<>(null);
        titleReference = new WeakReference<>(null);
        remainingReference = new WeakReference<>(null);
        releaseReference = new WeakReference<>(null);
        hintReference = new WeakReference<>(null);
        if (overlay == null) {
            // Collected after TikTok took it off and before it could lift. What only it was over
            // is still curtained, and lifting no panel hands that back.
            hideBehind(null, null, false);
            return;
        }
        ViewGroup parent = parentOf(overlay);
        // Even when TikTok already took the panel off, what it curtained is still curtained.
        hideBehind(parent, overlay, false);
        if (parent != null) parent.removeView(overlay);
    }

    /**
     * Where a view going over the feed is added to {@code root}: under the hold when one is up
     * there. The hold has to stay on top, or the curtain of what went over it hides the hold's
     * title and its way out from a screen reader and a keyboard.
     */
    public static int indexUnderTheHold(ViewGroup root) {
        int hold = root.indexOfChild(overlayReference.get());
        return hold >= 0 ? hold : root.getChildCount();
    }

    /**
     * Puts a view just added under the hold behind its curtain now. Left to the hold's next
     * layout pass, it was a Tab stop for that frame and TalkBack could announce it.
     */
    public static void curtainWhatWentUnder(ViewGroup root) {
        if (root != null && parentOf(overlayReference.get()) == root) syncGeometry();
    }

    private static ViewGroup parentOf(View view) {
        if (view == null) return null;
        return view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
    }

    /**
     * Whether the event is a mouse wheel or a touchpad scroll. Android hands those to the views
     * under the pointer, front to back, until one takes it, rather than to the view a touch went
     * down on, so a cover that swallows every touch still let them through to the feed.
     */
    static boolean isWheel(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                && event.getActionMasked() == MotionEvent.ACTION_SCROLL;
    }

    /**
     * Keeps a wheel turned over {@code cover} from scrolling what is under it. Shared with the
     * catcher, the app lock's cover, the start page's cover and the profile grid's sort hold,
     * which swallow every touch for the same reason the hold's panel does.
     */
    public static void keepTheWheel(View cover) {
        cover.setOnGenericMotionListener((view, event) -> isWheel(event));
    }

    /**
     * Takes everything behind the panel out of the reading order and out of keyboard reach, or
     * puts it back.
     *
     * <p>The panel covers the feed for anyone looking at it, and covers nothing at all for
     * anyone swiping through it with a screen reader, or pressing Tab on a keyboard or d-pad,
     * which walked into the video and the like and comment buttons underneath. Each view's own
     * settings are kept so that putting them back does not hand TikTok values this project
     * invented. Shared with the catcher {@code PausePlayback} puts up on returning to the app and
     * the app lock's cover, which are the same shape: one view over a root that has to be the
     * only thing on the page, apart from the tab bar the hold and the catcher stop above.
     */
    public static void hideBehind(ViewGroup root, View panel, boolean hidden) {
        hideBehind(root, panel, hidden, null);
    }

    /**
     * As {@link #hideBehind(ViewGroup, View, boolean)}, leaving {@code keep} and everything in it
     * readable and in reach. The panel stops above the tab bar so messages, profiles and search
     * still work, and that has to hold for a screen reader and a keyboard too. The bar sits deep
     * inside the same child of the root as the feed, so the curtain goes around it a level at a
     * time instead of over that whole child.
     */
    public static void hideBehind(ViewGroup root, View panel, boolean hidden, View keep) {
        if (!hidden) {
            // Everything this panel curtained, wherever it sits now, so a tree TikTok rebuilt
            // under the panel is still handed back.
            for (View view : new java.util.ArrayList<>(curtained.keySet())) lift(view, panel);
            return;
        }
        if (root == null) return;
        // Only what is under the panel. The hold and the catcher share a root, and either one
        // curtaining again as the bar moves must not take the other one, put up over it later.
        // A panel TikTok already took off this root covers nothing in it.
        int above = root.indexOfChild(panel);
        if (above >= 0) curtainAround(root, above, panel, keep);
    }

    /** Curtains the first {@code count} children of {@code group}, going around {@code keep}. */
    private static void curtainAround(ViewGroup group, int count, View panel, View keep) {
        for (int index = 0; index < count; index++) {
            View child = group.getChildAt(index);
            if (child == keep) {
                // A bar right under the root, curtained before it could be found, and whatever
                // an earlier pass curtained inside it while the bar was found further down.
                lift(child, panel);
                if (keep instanceof ViewGroup) {
                    for (View view : new java.util.ArrayList<>(curtained.keySet())) {
                        if (encloses((ViewGroup) keep, view)) lift(view, panel);
                    }
                }
                continue;
            }
            if (keep != null && child instanceof ViewGroup && encloses((ViewGroup) child, keep)) {
                // Its own stop only, even when it was curtained whole before the bar was there to
                // find, as on a cold start. On the S22 that is TikTok's full-screen pager around
                // the feed and the tabs, an empty Tab stop that pages on an arrow key.
                curtain(child, panel, false);
                steerAround(child);
                ViewGroup page = (ViewGroup) child;
                curtainAround(page, page.getChildCount(), panel, keep);
                continue;
            }
            curtain(child, panel, true);
        }
    }

    /** Curtains {@code view} and, when {@code whole}, everything in it. */
    private static void curtain(View view, View panel, boolean whole) {
        Curtained state = curtained.get(view);
        if (state == null) {
            state = new Curtained(view);
            curtained.put(view, state);
        }
        // Per panel, not a flag: the hold panel and the tap catcher share this, and the later
        // one lifting must not hand the feed back while the earlier one is up. A panel
        // curtaining twice, as the hold does on a relayout, counts once.
        state.panels.add(panel);
        // Per panel too. On a cold start the hold and the catcher both hide the page whole, and
        // as each finds the bar inside it, the page must open up once neither hides it whole.
        if (whole) state.whole.add(panel);
        else state.whole.remove(panel);
        hideInside(view, state);
        // Blocking covers what is inside, not the view: the catcher under a hold is focusable
        // itself. Read every pass, since TikTok can make a view focusable under the panel, as a
        // click listener does to one left on FOCUSABLE_AUTO, and that is what goes back after.
        if (view.isFocusable()) {
            state.focusable = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) state.focusableMode = view.getFocusable();
            // Going not focusable dropped this on an earlier pass, so a view that had it keeps it.
            state.focusableInTouchMode |= view.isFocusableInTouchMode();
            view.setFocusable(false);
        }
        // Cleared after the block, so wherever the window puts focus next is not back in here.
        View focused = whole ? view.findFocus() : view.isFocused() ? view : null;
        if (focused != null) focused.clearFocus();
    }

    /** Hides what is inside {@code view} while any panel over it hides it whole, and hands it back after. */
    private static void hideInside(View view, Curtained state) {
        int now = view.getImportantForAccessibility();
        // Set otherwise since the last pass means TikTok set it, and that is what goes back.
        if (state.set != -1 && now != state.set) state.importance = now;
        boolean whole = !state.whole.isEmpty();
        // A view a panel only goes around is out of the reading order itself, its children kept:
        // TalkBack's scroll actions on the pager around the feed and the tabs paged it sideways.
        int importance = whole ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : !state.panels.isEmpty() ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : state.importance;
        if (now != importance) view.setImportantForAccessibility(importance);
        state.set = state.panels.isEmpty() ? -1 : importance;
        if (state.focusability == -1) return;
        if (whole) {
            state.hidden = true;
            ((ViewGroup) view).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        } else if (state.hidden) {
            state.hidden = false;
            ((ViewGroup) view).setDescendantFocusability(state.focusability);
        }
    }

    /**
     * Wraps the window's callback once, as the idle wait does, so the keys that move focus skip
     * the views a curtain goes around. TikTok's pager around the feed and the tabs pages on an
     * arrow key whatever its own focus says, and on the S22 DPAD_RIGHT on the last tab paged it
     * over to the creator's profile under the hold. Focus in there is in the tab bar, so those
     * keys go to the bar alone, then to the window's own focus search, which finds the hold.
     */
    private static void steerAround(View view) {
        try {
            Activity activity = activityOf(view.getContext());
            Window window = activity == null ? null : activity.getWindow();
            if (window == null || STEERED.containsKey(window)) return;
            Window.Callback inner = window.getCallback();
            if (inner == null) return;
            InvocationHandler steer = (proxy, method, args) -> {
                try {
                    if (args != null && args.length == 1 && args[0] instanceof KeyEvent
                            && "dispatchKeyEvent".equals(method.getName())) {
                        View kept = keptPart(window, (KeyEvent) args[0]);
                        if (kept != null) {
                            // Someone is at the keyboard, for an idle wait wrapped inside this.
                            IdleLeave.stirred();
                            return kept.dispatchKeyEvent((KeyEvent) args[0]);
                        }
                    }
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() != null ? ex.getCause() : ex;
                }
            };
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, steer));
            STEERED.put(window, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not keep the arrow keys off the page under the hold", ex);
        }
    }

    /**
     * Where a key that moves focus goes while focus is inside a view a curtain goes around: the
     * child of the innermost one on the focus path. Null for any other key, or focus elsewhere.
     */
    private static View keptPart(Window window, KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_TAB:
                break;
            default:
                return null;
        }
        View decor = window.peekDecorView();
        View focused = decor == null ? null : decor.findFocus();
        for (View view = focused; view != null && view.getParent() instanceof View;
                view = (View) view.getParent()) {
            Curtained state = curtained.get((View) view.getParent());
            if (state != null && !state.panels.isEmpty() && state.whole.isEmpty()) return view;
        }
        return null;
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static void lift(View view, View panel) {
        Curtained state = curtained.get(view);
        if (state == null) return;
        state.panels.remove(panel);
        state.whole.remove(panel);
        // The sets are weak, so a panel collected before it lifted leaves them too: what no panel
        // is over any more is handed back whichever panel lifts next.
        hideInside(view, state);
        if (!state.panels.isEmpty()) return;
        curtained.remove(view);
        if (state.focusable) {
            // Focusable again means TikTok set it after the last pass, and its latest word stands.
            boolean refocused = view.isFocusable();
            int now = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? view.getFocusable() : View.FOCUSABLE;
            // Going not focusable dropped this too, and making a view focusable doesn't bring it
            // back. First, because it sets FOCUSABLE over AUTO.
            if (state.focusableInTouchMode) view.setFocusableInTouchMode(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view.setFocusable(refocused ? now : state.focusableMode);
            } else {
                view.setFocusable(true);
            }
        }
    }

    private static boolean encloses(ViewGroup group, View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == group) return true;
        }
        return false;
    }
}
