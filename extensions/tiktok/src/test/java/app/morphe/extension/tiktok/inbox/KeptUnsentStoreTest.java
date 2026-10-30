package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/** What is written down about kept messages survives a restart, and a damaged file is only an empty one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class KeptUnsentStoreTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private File file;

    @Before public void start() throws Exception {
        file = new File(folder.newFolder(), "kept.json");
        KeptUnsentStore.fileForTests = file;
        KeptUnsentStore.resetForTests();
    }

    @After public void finish() {
        KeptUnsentStore.fileForTests = null;
        KeptUnsentStore.resetForTests();
    }

    private static KeptUnsentStore.Entry entry(String id) {
        return new KeptUnsentStore.Entry(id, "2000", 7, "Ym9keQ==", 1234L);
    }

    @Test public void entriesComeBackAfterTheStoreIsReadAgain() {
        KeptUnsentStore.add(entry("5001"));
        KeptUnsentStore.add(entry("5002"));
        KeptUnsentStore.resetForTests();

        assertEquals(2, KeptUnsentStore.count());
        assertTrue(KeptUnsentStore.ids().contains(5001L));
        assertEquals("Ym9keQ==", KeptUnsentStore.all().get(0).body);
        assertEquals("2000", KeptUnsentStore.all().get(0).recallUid);
        assertEquals(7, KeptUnsentStore.all().get(0).type);
    }

    @Test public void removingOneLeavesTheRestAndSurvivesARestart() {
        KeptUnsentStore.add(entry("5001"));
        KeptUnsentStore.add(entry("5002"));
        KeptUnsentStore.remove("5001");
        KeptUnsentStore.resetForTests();

        assertEquals(1, KeptUnsentStore.count());
        assertEquals(java.util.Collections.singleton(5002L), KeptUnsentStore.ids());
    }

    @Test public void onlyTheNewestFiveHundredAreKept() {
        for (int i = 0; i < 503; i++) KeptUnsentStore.add(entry(String.valueOf(10_000 + i)));
        assertEquals(500, KeptUnsentStore.count());
        assertTrue(!KeptUnsentStore.ids().contains(10_000L));
        assertTrue(KeptUnsentStore.ids().contains(10_502L));
    }

    @Test public void aDamagedFileIsTheSameAsNoFile() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{ this is not a list".getBytes("UTF-8"));
        }
        assertEquals(0, KeptUnsentStore.count());
        KeptUnsentStore.add(entry("5001"));
        KeptUnsentStore.resetForTests();
        assertEquals(1, KeptUnsentStore.count());
    }
}
