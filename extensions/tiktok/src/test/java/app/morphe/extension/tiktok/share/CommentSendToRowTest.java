package app.morphe.extension.tiktok.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The Send to row in a comment's long press menu, with the same switch and list as the share sheet (#313). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentSendToRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** What the row's contacts answer, a BaseContact's display name. */
    public static final class Contact {
        private final String name;

        Contact(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return name;
        }
    }

    @After public void restoreSettings() {
        Settings.HIDE_SHARE_CONTACTS.resetToDefault();
        Settings.SHARE_HIDDEN_ITEMS.resetToDefault();
    }

    /** A RecyclerView lays a GONE child out anyway, so the row has to lose its height too. */
    @Test public void theRowCollapsesWithTheSwitchAndComesBackWithoutIt() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LinearLayout list = new LinearLayout(activity);
            View row = new View(activity);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(720, 298);
            list.addView(row, size);

            CommentSendToRow.onAttached(row);
            assertEquals(View.VISIBLE, row.getVisibility());
            assertEquals(298, size.height);

            Settings.HIDE_SHARE_CONTACTS.save(true);
            CommentSendToRow.onAttached(row);
            assertEquals(View.GONE, row.getVisibility());
            assertEquals(0, size.height);

            Settings.HIDE_SHARE_CONTACTS.save(false);
            CommentSendToRow.onAttached(row);
            assertEquals(View.VISIBLE, row.getVisibility());
            assertEquals(298, size.height);
        }
    }

    @Test public void peopleOnTheHiddenListLeaveTheRowWhicheverWayTheyAreWritten() {
        Contact kept = new Contact("National Geographic");
        Contact hidden = new Contact("James Paul");
        Contact unnamed = new Contact(null);
        Settings.SHARE_HIDDEN_ITEMS.save("copy_link, james paul\nWhatsApp");

        assertEquals(List.of(kept, unnamed), CommentSendToRow.people(List.of(kept, hidden, unnamed)));
    }

    @Test public void aListWithNobodyToHideIsHandedBackAsItCame() {
        List<Contact> contacts = List.of(new Contact("James Paul"));
        assertSame(contacts, CommentSendToRow.people(contacts));

        Settings.SHARE_HIDDEN_ITEMS.save("copy_link");
        assertSame(contacts, CommentSendToRow.people(contacts));

        Settings.SHARE_HIDDEN_ITEMS.save("James Paul");
        assertNotSame(contacts, CommentSendToRow.people(contacts));
    }
}
