package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;

import org.junit.Test;

/** The settings header shows the launcher icon only when the build record says the patch ran. */
public class ResourcePatchAppliedTest {
    private static final String LISTED = "resource_patches=changeAppName,customLauncherIcon";

    @Test public void everyListedPatchRan() throws Exception {
        String record = BuildDetailsTest.metadata();
        assertTrue(record.contains(LISTED + "\n"));
        assertTrue(BuildDetails.resourcePatchApplied(record, "customLauncherIcon"));
        assertTrue(BuildDetails.resourcePatchApplied(record, "changeAppName"));
    }

    @Test public void aNameMustMatchAWholeEntry() throws Exception {
        String record = BuildDetailsTest.metadata();
        for (String name : new String[]{"customLauncher", "CustomLauncherIcon", "customLauncherIcon,",
                "changeAppName,customLauncherIcon", "", "none", "unknown", "hideAds"}) {
            assertFalse(name, BuildDetails.resourcePatchApplied(record, name));
        }
        String alone = record.replace(LISTED, "resource_patches=changeAppName");
        assertTrue(BuildDetails.resourcePatchApplied(alone, "changeAppName"));
        assertFalse(BuildDetails.resourcePatchApplied(alone, "customLauncherIcon"));
    }

    @Test public void aRecordThatCantSaySaysNo() throws Exception {
        String record = BuildDetailsTest.metadata();
        String[] records = {
                record.replace(LISTED, "resource_patches=none"),
                // Written before the list existed: one line shorter, and unknown.
                record.replace(LISTED + "\n", ""),
                // Out of order is damaged, so the whole record reads unknown.
                record.replace(LISTED, "resource_patches=customLauncherIcon,changeAppName"),
                record.replace("schema=1", "schema=2"),
                record.replace("\n", "\r\n"),
                "", null};
        for (String damaged : records) {
            assertFalse(String.valueOf(damaged), BuildDetails.resourcePatchApplied(damaged, "customLauncherIcon"));
            assertFalse(String.valueOf(damaged), BuildDetails.resourcePatchApplied(damaged, "unknown"));
            assertFalse(String.valueOf(damaged), BuildDetails.resourcePatchApplied(damaged, "none"));
        }
    }
}
