package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Fold long comments: a comment past the chosen number of lines shows that many and "more",
 * and more opens the rest in the same row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CommentFoldTest {
    private static final int WIDTH = 300;
    private static final String EIGHT_LINES =
            "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight";
    private static final String LONG_PARAGRAPH = "I made these buns every weekend for a year and the "
            + "trick nobody tells you is to let the dough rest overnight in the fridge, then shape "
            + "it cold, because warm dough tears and the tops never get that shine you see in the "
            + "bakery window no matter how much egg wash you brush on before they go in the oven.";

    private Context context;

    /** Stands in for TikTok's Comment, whose accessors keep their names. */
    public static final class Comment {
        public final String text;
        public final String cid;

        Comment(String cid, String said) {
            this.cid = cid;
            text = said;
        }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Settings.FOLD_LONG_COMMENTS.save(true);
        CommentFold.resetForTests();
    }

    @After public void tearDown() {
        Settings.FOLD_LONG_COMMENTS.resetToDefault();
        Settings.FOLD_LONG_COMMENTS_LINES.resetToDefault();
        CommentFold.resetForTests();
    }

    private LinearLayout cell(String username, String body) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(username);
        TextView text = new TextView(context);
        text.setTextSize(14);
        text.setText(body);
        root.addView(name);
        root.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private static TextView body(ViewGroup cell) {
        return (TextView) cell.getChildAt(1);
    }

    private static void layOut(View cell) {
        cell.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        cell.layout(0, 0, cell.getMeasuredWidth(), cell.getMeasuredHeight());
    }

    /** The cell as TikTok binds it: its text set and laid out before the posted pass runs. */
    private LinearLayout bound(String body) {
        LinearLayout cell = cell("baker", body);
        layOut(cell);
        return cell;
    }

    private static CommentFold.More more(TextView view) {
        CharSequence text = view.getText();
        CommentFold.More[] spans = text instanceof Spanned
                ? ((Spanned) text).getSpans(0, text.length(), CommentFold.More.class)
                : new CommentFold.More[0];
        assertEquals("the folded comment has no more to tap", 1, spans.length);
        return spans[0];
    }

    private static void assertFolded(LinearLayout cell, String comment, int lines) {
        TextView view = body(cell);
        String shown = view.getText().toString();
        assertTrue("the fold doesn't end in more: " + shown, shown.endsWith((char) 0x2026 + " more"));
        String kept = shown.substring(0, shown.length() - 6);
        assertTrue("the fold isn't the start of the comment: " + kept, comment.startsWith(kept));
        layOut(cell);
        assertTrue("the fold runs to " + view.getLayout().getLineCount() + " lines",
                view.getLayout().getLineCount() <= lines);
    }

    @Test public void aCommentPastTheLimitShowsThatManyLinesAndMore() {
        LinearLayout cell = bound(EIGHT_LINES);

        CommentFold.apply(cell, new Comment("1", EIGHT_LINES));

        assertFolded(cell, EIGHT_LINES, 4);
        assertEquals("one\ntwo\nthree\nfour" + (char) 0x2026 + " more", body(cell).getText().toString());
    }

    @Test public void aLongParagraphIsCutWhereItsLinesWrap() {
        LinearLayout cell = bound(LONG_PARAGRAPH);
        assertTrue("the fixture paragraph doesn't wrap past 4 lines",
                body(cell).getLayout().getLineCount() > 4);

        CommentFold.apply(cell, new Comment("1", LONG_PARAGRAPH));

        assertFolded(cell, LONG_PARAGRAPH, 4);
    }

    @Test public void theLineCountIsTheReadersChoice() {
        Settings.FOLD_LONG_COMMENTS_LINES.save(2);
        LinearLayout cell = bound(EIGHT_LINES);

        CommentFold.apply(cell, new Comment("1", EIGHT_LINES));

        assertEquals("one\ntwo" + (char) 0x2026 + " more", body(cell).getText().toString());
    }

    @Test public void moreOpensTheWholeCommentInPlace() {
        LinearLayout cell = bound(EIGHT_LINES);
        CommentFold.apply(cell, new Comment("1", EIGHT_LINES));

        more(body(cell)).onClick(body(cell));

        assertEquals(EIGHT_LINES, body(cell).getText().toString());
    }

    @Test public void anOpenedCommentStaysOpenWhenItsRowIsBoundAgain() {
        LinearLayout cell = bound(EIGHT_LINES);
        Comment comment = new Comment("1", EIGHT_LINES);
        CommentFold.apply(cell, comment);
        more(body(cell)).onClick(body(cell));

        // A changed like count binds the row again, and TikTok sets the same text.
        body(cell).setText(EIGHT_LINES);
        layOut(cell);
        CommentFold.apply(cell, comment);

        assertEquals(EIGHT_LINES, body(cell).getText().toString());
    }

    @Test public void aRowReusedForAnotherCommentFoldsItFresh() {
        LinearLayout cell = bound(EIGHT_LINES);
        CommentFold.apply(cell, new Comment("1", EIGHT_LINES));
        more(body(cell)).onClick(body(cell));

        String next = EIGHT_LINES.replace("one", "uno");
        body(cell).setText(next);
        layOut(cell);
        CommentFold.apply(cell, new Comment("2", next));

        assertFolded(cell, next, 4);
    }

    @Test public void aShortCommentIsLeftAlone() {
        String said = "two\nlines";
        LinearLayout cell = bound(said);
        CharSequence before = body(cell).getText();

        CommentFold.apply(cell, new Comment("1", said));

        assertTrue("a comment within the limit was changed", before == body(cell).getText());
    }

    @Test public void offLeavesEveryCommentWhole() {
        Settings.FOLD_LONG_COMMENTS.save(false);
        LinearLayout cell = bound(EIGHT_LINES);

        CommentFold.apply(cell, new Comment("1", EIGHT_LINES));

        assertEquals(EIGHT_LINES, body(cell).getText().toString());
    }

    @Test public void aCellNotLaidOutYetFoldsOnItsFirstLayout() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        LinearLayout cell = cell("baker", EIGHT_LINES);
        activity.setContentView(cell);

        CommentFold.apply(cell, new Comment("1", EIGHT_LINES));
        assertEquals("folded before it had a width", EIGHT_LINES, body(cell).getText().toString());

        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("one\ntwo\nthree\nfour" + (char) 0x2026 + " more", body(cell).getText().toString());
    }

    @Test public void aTapOutsideMoreStillReachesTheRow() {
        LinearLayout cell = bound(EIGHT_LINES);
        boolean wasClickable = body(cell).isClickable();

        CommentFold.apply(cell, new Comment("1", EIGHT_LINES));

        assertEquals("folding made the text take the row's tap", wasClickable, body(cell).isClickable());
    }
}
