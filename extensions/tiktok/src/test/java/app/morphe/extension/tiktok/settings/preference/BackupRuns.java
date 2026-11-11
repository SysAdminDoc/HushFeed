package app.morphe.extension.tiktok.settings.preference;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Ends the backup page's current run the way its worker does, for tests of what a run redraws. */
public final class BackupRuns {
    public static final int BACKUP = 7311;
    public static final int RESET = 7313;
    public static final int UNDO = 7314;

    private BackupRuns() {
    }

    /** A backup ({@link #BACKUP}), a reset ({@link #RESET}) or an undo ({@link #UNDO}). */
    public static void finish(int action) {
        AtomicInteger run = ReflectionHelpers.getStaticField(SettingsBackupPreference.class, "RUN");
        ReflectionHelpers.callStaticMethod(SettingsBackupPreference.class, "finishRun",
                ClassParameter.from(int.class, run.get()), ClassParameter.from(int.class, action),
                ClassParameter.from(WeakReference.class, new WeakReference<TikTokPreferenceFragment>(null)));
    }
}
