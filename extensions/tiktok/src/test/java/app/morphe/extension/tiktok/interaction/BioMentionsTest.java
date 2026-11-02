package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BioMentionsTest {
    private ActivityController<Activity> owner;
    private Activity activity;
    private boolean previousStatus;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousStatus = SettingsStatus.copyIdsEnabled;
        SettingsStatus.copyIdsEnabled = true;
        Settings.COPY_IDS.save(true);
        Settings.BIO_MENTION_LINKS.save(true);
        owner = Robolectric.buildActivity(Activity.class).setup();
        activity = owner.get();
    }

    @After public void tearDown() {
        SettingsStatus.copyIdsEnabled = previousStatus;
        Settings.COPY_IDS.resetToDefault();
        Settings.BIO_MENTION_LINKS.resetToDefault();
        if (owner != null) owner.close();
    }

    private static List<String> handles(String text) {
        List<String> found = new ArrayList<>();
        for (int[] at : BioMentions.find(text)) found.add(text.substring(at[0] + 1, at[1]));
        return found;
    }

    @Test public void onlyRealUsernamesCount() {
        assertEquals(List.of("nasa", "jo.hn_9"), handles("Follow @nasa and @jo.hn_9!"));
        assertEquals("a trailing period ends the sentence, not the name",
                List.of("nasa"), handles("Backup: @nasa."));
        assertTrue("an email isn't a mention", handles("mail me@example.com").isEmpty());
        assertTrue("one letter is too short", handles("@a").isEmpty());
        assertTrue("past 24 characters isn't a username",
                handles("@abcdefghijklmnopqrstuvwxy").isEmpty());
        assertEquals(List.of("abcdefghijklmnopqrstuvwx"), handles("@abcdefghijklmnopqrstuvwx"));
        assertTrue("@@ isn't a mention", handles("@@nasa").isEmpty());
        assertEquals("a line break or emoji before it is fine",
                List.of("nasa", "esa"), handles("hi\n@nasa 🚀@esa"));
    }

    @Test public void anotherAppsUsernameIsLeftAlone() {
        assertTrue(handles("IG: @nasa").isEmpty());
        assertTrue(handles("insta @nasa").isEmpty());
        assertTrue(handles("Snapchat - @nasa").isEmpty());
        assertTrue(handles("X: @nasa").isEmpty());
        assertEquals("only the line that names the other app",
                List.of("esa"), handles("ig @nasa\nbackup @esa"));
        assertEquals("a word that merely ends in those letters still links",
                List.of("nasa", "esa"), handles("big fan of @nasa, max @esa"));
    }

    @Test public void aDisplayNameIsOneLinkAndNotItsFirstWord() {
        assertEquals(List.of("Mary Jane Watson"), handles("Sister: @Mary Jane Watson"));
        assertEquals("the name stops at the first word without a capital",
                List.of("First Last"), handles("Say hi to @First Last on here"));
        assertEquals("a name and a username side by side",
                List.of("José García", "nasa"), handles("@José García and @nasa"));
        assertEquals("a name doesn't run past its line",
                List.of("SKIMS", "Update"), handles("@SKIMS\n@Update"));
        assertEquals("punctuation ends it", List.of("First Last"), handles("@First Last, backup"));
        assertEquals("a lowercase handle before a new sentence stays a username",
                List.of("nasa"), handles("@nasa Follow me"));
        assertEquals("a username with its digits or punctuation stays a username",
                List.of("Nasa2", "Mr.Beast"), handles("@Nasa2 Backup, @Mr.Beast Official"));
        assertEquals("four words is as long as a name goes",
                List.of("One Two Three Four"), handles("@One Two Three Four Five"));
        assertTrue("another app's name is left alone", handles("IG: @First Last").isEmpty());
    }

    @Test public void aWordAUsernameCantSpellLinksNothing() {
        assertTrue(handles("@José").isEmpty());
        assertTrue(handles("@naïve").isEmpty());
    }

    @Test public void tappingADisplayNameSearchesForTheWholeName() {
        TextView view = bio("Sister: @First Last");
        BioMentions.MentionSpan[] found = spans(view);
        assertEquals(1, found.length);
        assertEquals("First Last", found[0].handle);
        tapAt(view, view.getText().toString().indexOf("Last") + 1);
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("snssdk1233", started.getData().getScheme());
        assertEquals("First Last", started.getData().getQueryParameter("keyword"));
        assertEquals(activity.getPackageName(), started.getPackage());
    }

    @Test public void onlyAUsernameOrADisplayNameOpensAnything() {
        assertFalse(BioMentions.open(activity, "not a name"));
        assertFalse(BioMentions.open(activity, "First Last&keyword=other"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    private TextView bio(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        FrameLayout root = new FrameLayout(activity);
        root.addView(view, new FrameLayout.LayoutParams(1000, FrameLayout.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
        CopyIds.onBio(view, text);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, 1000, root.getMeasuredHeight());
        return view;
    }

    private static BioMentions.MentionSpan[] spans(TextView view) {
        CharSequence text = view.getText();
        if (!(text instanceof Spanned)) return new BioMentions.MentionSpan[0];
        return ((Spanned) text).getSpans(0, text.length(), BioMentions.MentionSpan.class);
    }

    private static void tapAt(TextView view, int offset) {
        pressAt(view, offset, 50);
    }

    /** Presses at the middle of character [offset] of [view]'s text and lifts after [millis]. */
    private static void pressAt(TextView view, int offset, long millis) {
        Layout layout = view.getLayout();
        int line = layout.getLineForOffset(offset);
        float x = (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2
                + view.getTotalPaddingLeft();
        float y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + view.getTotalPaddingTop();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        view.dispatchTouchEvent(down);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
        MotionEvent up = MotionEvent.obtain(now, now + millis, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(up);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        down.recycle();
        up.recycle();
    }

    @Test public void aBioGetsItsUsernamesLinkedAndKeepsThemAcrossNewText() {
        TextView view = bio("Main account @nasa, backup @esa_web");
        BioMentions.MentionSpan[] found = spans(view);
        assertEquals(2, found.length);

        view.setText("Translated: moved to @nasa_two");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        found = spans(view);
        assertEquals("TikTok setting the text again gets the spans back", 1, found.length);
        assertEquals("nasa_two", found[0].handle);
    }

    @Test public void tappingAUsernameOpensItsProfileInsideTikTok() {
        TextView view = bio("Main account @nasa");
        boolean[] translated = {false};
        view.setOnClickListener(v -> translated[0] = true);
        tapAt(view, view.getText().toString().indexOf("nasa") + 1);
        assertFalse("TikTok's tap doesn't also translate", translated[0]);
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://www.tiktok.com/@nasa", started.getDataString());
        assertEquals(activity.getPackageName(), started.getPackage());
    }

    @Test public void tappingTheRestOfTheBioStillReachesTikTok() {
        TextView view = bio("Main account @nasa");
        boolean[] clicked = {false};
        view.setOnClickListener(v -> clicked[0] = true);
        tapAt(view, 2);
        assertNull("no profile opens", Shadows.shadowOf(activity).getNextStartedActivity());
        assertTrue("the translate tap still gets through", clicked[0]);
    }

    @Test public void aLongPressOnAUsernameStillCopiesTheBio() {
        TextView view = bio("@SKIMS\n@Update");
        boolean[] longPressed = {false};
        view.setOnLongClickListener(v -> longPressed[0] = true);
        pressAt(view, 2, ViewConfiguration.getLongPressTimeout() + 200);
        assertTrue(longPressed[0]);
        assertNull("no profile opens", Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void offLeavesTheBioAsTikTokSetIt() {
        Settings.BIO_MENTION_LINKS.save(false);
        TextView view = bio("Main account @nasa");
        assertEquals(0, spans(view).length);
        tapAt(view, view.getText().toString().indexOf("nasa") + 1);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }
}
