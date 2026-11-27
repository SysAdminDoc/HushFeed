/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.style.ReplacementSpan;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** #549: a picture you pick stands in for the local blue check beside your own name, on this phone only. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CustomBadgeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for TikTok's blue check span: 40 pixels wide, draws nothing. */
    private static final class BlueCheck extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return 40;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint paint) {
        }
    }

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PausedProcess.set(false);
        SignedInUser.idForTests = "account-a";
        SettingsStatus.followStatusEnabled = true;
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        CustomBadge.remove(context);
    }

    @After public void tearDown() {
        CustomBadge.remove(context);
        PausedProcess.set(false);
        SignedInUser.idForTests = null;
        SettingsStatus.followStatusEnabled = false;
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(false);
    }

    @Test public void aPickedPictureStandsInForTheBlueCheckBesideYourOwnName() throws Exception {
        pick(png(200, 100, Color.RED));
        BlueCheck check = new BlueCheck();
        SpannableStringBuilder name = LocalProfileAppearance.badgeText(new Object(),
                new SpannableStringBuilder("me"), check, "account-a", LocalProfileAppearance.NICKNAME);
        ReplacementSpan badge = badgeOf(name);
        assertNotSame("the picture replaces TikTok's blue check", check, badge);
        Paint paint = paint();
        assertEquals("it takes the blue check's own width", 40, badge.getSize(paint, name, name.length() - 1, name.length(), null));
        Bitmap drawn = draw(badge, name, paint);
        int middle = middle(paint);
        assertEquals(Color.RED, drawn.getPixel(20, middle));
        // A picture twice as wide as tall keeps its shape: the square's top quarter stays clear.
        assertEquals(0, Color.alpha(drawn.getPixel(20, middle - 16)));
    }

    @Test public void theNavigationBarNameGetsItToo() throws Exception {
        pick(png(64, 64, Color.GREEN));
        SpannableStringBuilder name = LocalProfileAppearance.badgeText(new Object(),
                new SpannableStringBuilder("me"), new BlueCheck(), "account-a", LocalProfileAppearance.NAVBAR_NICKNAME);
        assertTrue(badgeOf(name) instanceof CustomBadge.PictureSpan);
    }

    @Test public void withNoPictureTheBlueCheckStays() {
        BlueCheck check = new BlueCheck();
        SpannableStringBuilder name = LocalProfileAppearance.badgeText(new Object(),
                new SpannableStringBuilder("me"), check, "account-a", LocalProfileAppearance.NICKNAME);
        assertSame(check, badgeOf(name));
    }

    @Test public void someoneElsesProfileOrTheSwitchOffNeverShowsIt() throws Exception {
        pick(png(64, 64, Color.RED));
        SpannableStringBuilder theirs = new SpannableStringBuilder("them");
        assertSame(theirs, LocalProfileAppearance.badgeText(new Object(), theirs, new BlueCheck(), "account-b",
                LocalProfileAppearance.NICKNAME));
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(false);
        SpannableStringBuilder mine = new SpannableStringBuilder("me");
        assertSame(mine, LocalProfileAppearance.badgeText(new Object(), mine, new BlueCheck(), "account-a",
                LocalProfileAppearance.NICKNAME));
    }

    @Test public void aFileThatIsntAPictureOrIsTooBigChangesNothing() throws Exception {
        pick(png(64, 64, Color.RED));
        try {
            CustomBadge.prepare(new ByteArrayInputStream("not a picture".getBytes(StandardCharsets.UTF_8)));
            fail("text was taken for a picture");
        } catch (CustomBadge.Rejected refused) {
            assertFalse(refused.tooLarge);
        }
        try {
            CustomBadge.prepare(zeros(CustomBadge.MAX_BYTES + 1));
            fail("a file over the limit was read");
        } catch (CustomBadge.Rejected refused) {
            assertTrue(refused.tooLarge);
        }
        assertTrue(CustomBadge.present(context));
        assertEquals(Color.RED, shownColor());
    }

    @Test public void aNewPictureReplacesTheOldOneAtOnce() throws Exception {
        pick(png(64, 64, Color.RED));
        assertEquals(Color.RED, shownColor());
        pick(png(64, 64, Color.BLUE));
        assertEquals(Color.BLUE, shownColor());
    }

    @Test public void removingThePictureBringsBackTheBlueCheck() throws Exception {
        pick(png(64, 64, Color.RED));
        assertTrue(CustomBadge.remove(context));
        assertFalse(CustomBadge.present(context));
        BlueCheck check = new BlueCheck();
        SpannableStringBuilder name = LocalProfileAppearance.badgeText(new Object(),
                new SpannableStringBuilder("me"), check, "account-a", LocalProfileAppearance.NICKNAME);
        assertSame(check, badgeOf(name));
    }

    @Test public void aBigPhotoIsKeptSmall() throws Exception {
        pick(png(1200, 1200, Color.RED));
        Bitmap kept = CustomBadge.bitmap(context);
        assertNotNull(kept);
        assertEquals(CustomBadge.SIDE, kept.getWidth());
        assertEquals(CustomBadge.SIDE, kept.getHeight());
        assertTrue(CustomBadge.file(context).length() < 64 * 1024);
    }

    private void pick(byte[] picture) throws Exception {
        CustomBadge.keep(context, CustomBadge.prepare(new ByteArrayInputStream(picture)));
    }

    private int shownColor() {
        SpannableStringBuilder name = LocalProfileAppearance.badgeText(new Object(),
                new SpannableStringBuilder("me"), new BlueCheck(), "account-a", LocalProfileAppearance.NICKNAME);
        Paint paint = paint();
        return draw(badgeOf(name), name, paint).getPixel(20, middle(paint));
    }

    private static ReplacementSpan badgeOf(SpannableStringBuilder name) {
        ReplacementSpan[] spans = name.getSpans(name.length() - 1, name.length(), ReplacementSpan.class);
        assertEquals(1, spans.length);
        return spans[0];
    }

    private static Paint paint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(48);
        return paint;
    }

    private static final int BASELINE = 60;

    private static int middle(Paint paint) {
        return Math.round(BASELINE + (paint.ascent() + paint.descent()) / 2f);
    }

    private static Bitmap draw(ReplacementSpan badge, CharSequence text, Paint paint) {
        Bitmap bitmap = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888);
        badge.draw(new Canvas(bitmap), text, text.length() - 1, text.length(), 0, 0, BASELINE, 100, paint);
        return bitmap;
    }

    private static byte[] png(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    private static InputStream zeros(int count) {
        return new InputStream() {
            int left = count;

            @Override public int read() {
                return left-- > 0 ? 0 : -1;
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                if (left <= 0) return -1;
                int n = Math.min(length, left);
                left -= n;
                return n;
            }
        };
    }
}
