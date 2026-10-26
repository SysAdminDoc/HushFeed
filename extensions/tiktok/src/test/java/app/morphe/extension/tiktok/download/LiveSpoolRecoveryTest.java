package app.morphe.extension.tiktok.download;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
/** Legacy adoption preserves bytes without inventing a missing publication acknowledgement. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=28)
public class LiveSpoolRecoveryTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void legacyRawAndItsUncommittedRewriteHaveOneDurableOwner() throws Exception {
        File cache=folder.newFolder("cache"), root=folder.newFolder("retained");
        String prefix=LiveSpoolRecovery.spoolPrefix("creator",1_760_480_102_000L);
        File raw=new File(cache,prefix+"1.flv"), rewritten=new File(cache,prefix+"2.mp4");
        Files.write(raw.toPath(),new byte[]{1,2,3}); Files.write(rewritten.toPath(),new byte[]{4,5,6});
        RecordingRetention store=new RecordingRetention(root,cache);
        LiveSpoolRecovery.adoptLegacy(store,cache);
        assertEquals(1,store.ids().size()); RecordingRetention.Record record=store.open(store.ids().get(0));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(record.source().toPath()));
        assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(record.file("legacy-0.mp4").toPath()));
        assertEquals("true",record.get("legacyUnknown"));
        java.io.ByteArrayOutputStream exported = new java.io.ByteArrayOutputStream();
        RecordingExport.exportFiles(record, exported);
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported.toByteArray()))) {
            assertNotNull(zip.getNextEntry()); assertArrayEquals(new byte[]{1,2,3}, zip.readAllBytes());
            assertNotNull(zip.getNextEntry()); assertArrayEquals(new byte[]{4,5,6}, zip.readAllBytes());
        }
        assertTrue(record.source().exists());
        assertEquals(2, RecordingExport.files(record).size());
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(RecordingExport.files(record).get(0).toPath()));
        record.close();
        assertFalse(raw.exists()); assertFalse(rewritten.exists());
        LiveSpoolRecovery.adoptLegacy(store,cache); assertEquals(1,store.ids().size());
    }
    @Test public void unfinishedLegacyScreenWorkIsOwnedButNeverMarkedFinalized() throws Exception {
        File cache=folder.newFolder("cache"), root=folder.newFolder("retained");
        File work=new File(cache,"screen-work-123.mp4"); Files.write(work.toPath(),new byte[]{1,2,3});
        RecordingRetention store=new RecordingRetention(root,cache);
        LiveSpoolRecovery.adoptLegacy(store,cache);
        RecordingRetention.Record record=store.open(store.ids().get(0));
        assertEquals("screen",record.get("format")); assertNull(record.finalized()); assertTrue(record.source().isFile()); record.close();
    }
    @Test public void aLeasedRecordingCannotBeReopenedByAnotherRecoveryWorker() throws Exception {
        RecordingRetention store=new RecordingRetention(folder.newFolder()); RecordingRetention.Record active=store.begin("flv","creator",1);
        assertNull(store.open(active.id)); active.close();
    }
    @Test public void unnamedLegacyRawCannotClaimUnrelatedMp4FilesAsRewrites() throws Exception {
        File cache=folder.newFolder("cache"), root=folder.newFolder("retained");
        File raw=new File(cache,"live-123.flv"), unrelated=new File(cache,"story-123.mp4");
        Files.write(raw.toPath(),new byte[]{1,2,3}); Files.write(unrelated.toPath(),new byte[]{7,8});
        RecordingRetention store=new RecordingRetention(root,cache); LiveSpoolRecovery.adoptLegacy(store,cache);
        RecordingRetention.Record record=store.open(store.ids().get(0)); assertEquals(0,record.number("extras"));
        assertTrue(unrelated.exists()); record.update("state","DISCARDED"); record.close(); assertTrue(unrelated.exists());
    }
    @Test public void oldFilesAndUnrelatedMediaRemainIntactWhenAdoptionCannotStart() throws Exception {
        File cache=folder.newFolder("cache"), root=folder.newFolder("retained");
        File unrelated=new File(cache,"story-123.mp4"); Files.write(unrelated.toPath(),new byte[]{7,8});
        LiveSpoolRecovery.adoptLegacy(new RecordingRetention(root,cache),cache);
        assertTrue(unrelated.exists()); assertEquals(0,new RecordingRetention(root,cache).ids().size());
    }
}
